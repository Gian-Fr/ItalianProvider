package com.lagradost

import android.text.Html
import com.fasterxml.jackson.annotation.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.security.MessageDigest
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

data class PropsGson(
    val titles: List<TitleGson>
)

data class FullResponseGson(
    val props: PropsGson
)
data class ImageGson(
    @SerializedName("imageable_id") val imageableId: Int,
    @SerializedName("imageable_type") val imageableType: String,
    val filename: String,
    val type: String,
    @SerializedName("original_url_field") val originalUrlField: String? = null
)

data class TitleGson(
    val id: Int,
    val slug: String,
    val name: String,
    val type: String,
    val score: String,
    @SerializedName("sub_ita") val subIta: Int,
    @SerializedName("last_air_date") val lastAirDate: String? = null,
    @SerializedName("seasons_count") val seasonsCount: Int,
    val images: List<ImageGson>
)

data class ResponseDataGson(
    val titles: List<TitleGson>
)

class StreamingcommunityProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://streamingcommunity.photos"
    override var name = "StreamingCommunity"
    override val hasMainPage = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"


    private fun TitleGson.toSearchResult(): SearchResponse {
        val title= this.name
        val link= "$mainUrl/titles/${this.id}-${this.slug}"
        val cdnprefix= mainUrl.replace("streamingcommunity","cdn.streamingcommunity")
        val posterUrl="${cdnprefix}/images/${this.images[0].filename}"
        return newMovieSearchResponse(title, link, TvType.Movie) {
            addPoster(posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val queryFormatted = query.replace(" ", "%20")
        val url = "$mainUrl/search?q=$queryFormatted"
        val document = app.get(url, headers = mapOf("user-agent" to userAgent)).document
        val results= document.select("#app").attr("data-page")
        val gson = Gson()
        val fullResponse = gson.fromJson(results, FullResponseGson::class.java)
        val titles = fullResponse.props.titles
        val searchResults = mutableListOf<SearchResponse>()
        for( title in titles){
            searchResults.add(title.toSearchResult())
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("user-agent" to userAgent)).document
        val poster = Regex("url\\('(.*)'").find(
            document.selectFirst("div.title-wrap")?.attributes()
                ?.get("style") ?: ""
        )?.groupValues?.lastOrNull() //posterMap[url]
        val id = url.substringBefore("-").filter { it.isDigit() }
        val datajs = app.post(
            "$mainUrl/api/titles/preview/$id",
            referer = mainUrl,
            headers = mapOf("user-agent" to userAgent)
        ).parsed<Moviedata>()

        val type = if (datajs.type == "movie") {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        val trailerInfoJs = document.select("slider-trailer").attr("videos")
        val trailerInfo = parseJson<List<TrailerElement>>(trailerInfoJs)
        val trailerUrl = trailerInfo.firstOrNull()?.url?.let { code ->
            "https://www.youtube.com/watch?v=$code"
        }

        val year = datajs.releaseDate.substringBefore("-")
        val correlates = document.selectFirst("slider-title")!!.attr("titles-json")
        val correlatesData = parseJson<List<VideoElement>>(correlates)
        // Max size 15 to prevent network spam
        val size = minOf(correlatesData.size, 15)

        val correlatesList = correlatesData.take(size).apmap {
            it.toSearchResponse()
        }

        if (type == TvType.TvSeries) {
            val name = datajs.name

            val episodes =
                Html.fromHtml(document.selectFirst("season-select")!!.attr("seasons")).toString()
            val jsonEpisodes = parseJson<List<Season>>(episodes)

            val episodeList = jsonEpisodes.map { seasons ->
                val season = seasons.number.toInt()
                val sid = seasons.title_id
                seasons.episodes.map { ep ->
                    val href = "$mainUrl/watch/$sid?e=${ep.id}"
                    val postImage = ep.images.firstOrNull()?.originalURL

                    newEpisode(href) {
                        this.name = ep.name
                        this.season = season
                        this.episode = ep.number.toInt()
                        this.description = ep.plot
                        this.posterUrl = postImage
                    }
                }
            }.flatten()

            if (episodeList.isEmpty()) throw ErrorLoadingException("No Seasons Found")

            return newTvSeriesLoadResponse(name, url, type, episodeList) {
                this.posterUrl = poster
                this.year = year.filter { it.isDigit() }.toInt()
                this.plot = document.selectFirst("div.plot-wrap > p")!!.text()
                this.duration = datajs.runtime?.toInt()
                this.rating = (datajs.votes[0].average.toFloatOrNull()?.times(1000))?.toInt()
                this.tags = datajs.genres.map { it.name }
                addTrailer(trailerUrl)
                this.recommendations = correlatesList
            }
        } else {
            return newMovieLoadResponse(
                document.selectFirst("div > div > h1")!!.text(),
                document.select("a.play-hitzone").attr("href"),
                type,
                document.select("a.play-hitzone").attr("href")
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year.filter { it.isDigit() }.toInt()
                this.plot = document.selectFirst("p.plot")!!.text()
                this.rating = datajs.votes[0].average.toFloatOrNull()?.times(1000)?.toInt()
                this.tags = datajs.genres.map { it.name }
                this.duration = datajs.runtime?.toInt()
                addTrailer(trailerUrl)
                this.recommendations = correlatesList
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ip = app.get("https://api.ipify.org/").text
        val videosPage = app.get(data, headers = mapOf("user-agent" to userAgent)).document
        val scwsidJs = videosPage.select("video-player").attr("response").replace("&quot;", """"""")
        val jsn = JSONObject(scwsidJs)
        val scwsid = jsn.getString("scws_id")
        val expire = (System.currentTimeMillis() / 1000 + 172800).toString()

        val token0 = "$expire$ip Yc8U6r8KjAKAepEA".toByteArray()
        val token1 = MessageDigest.getInstance("MD5").digest(token0)
        val token2 = base64Encode(token1)
        val token = token2.replace("=", "").replace("+", "-").replace("/", "_")

        val link = "https://scws.work/master/$scwsid?token=$token&expires=$expire&n=1"

        callback.invoke(
            ExtractorLink(
                name,
                name,
                link,
                isM3u8 = true,
                referer = mainUrl,
                quality = Qualities.Unknown.value
            )
        )
        return true
    }

    private suspend fun VideoElement.toSearchResponse(): MovieSearchResponse {
        val id = this.id
        val name = this.slug
        val img = this.images.firstOrNull()
        val posterUrl = if (img != null){
            val number = translateNumber(this.images[0].serverID.toInt())
            val ip = translateIp(this.images[0].proxyID.toInt())
            "https://$ip/images/$number/${img.url}"
        } else {
            ""
        }
        val videoUrl = "$mainUrl/titles/$id-$name"
        //posterMap[videourl] = posterurl
        val data = app.post(
            "$mainUrl/api/titles/preview/$id",
            referer = mainUrl,
            headers = mapOf("user-agent" to userAgent)
        ).text
        val datajs = parseJson<Moviedata>(data)
        val type = if (datajs.type == "movie") {
            TvType.Movie
        } else {
            TvType.TvSeries
        }

        return newMovieSearchResponse(datajs.name, videoUrl, type) {
            this.posterUrl = posterUrl
            this.year =
                datajs.releaseDate.substringBefore("-").filter { it.isDigit() }.toIntOrNull()
        }
    }

    private fun translateNumber(num: Int): Int? {
        return when (num) {
            67 -> 1
            71 -> 2
            72 -> 3
            73 -> 4
            74 -> 5
            75 -> 6
            76 -> 7
            77 -> 8
            78 -> 9
            79 -> 10
            133 -> 11
            else -> null
        }
    }

    private fun translateIp(num: Int): String? {
        return when (num) {
            16 -> "sc-b1-01.scws-content.net"
            17 -> "sc-b1-02.scws-content.net"
            18 -> "sc-b1-03.scws-content.net"
            85 -> "sc-b1-04.scws-content.net"
            95 -> "sc-b1-05.scws-content.net"
            117 -> "sc-b1-06.scws-content.net"
            141 -> "sc-b1-07.scws-content.net"
            142 -> "sc-b1-08.scws-content.net"
            143 -> "sc-b1-09.scws-content.net"
            144 -> "sc-b1-10.scws-content.net"
            else -> null
        }
    }
}

data class Moviedata(
    @JsonProperty("id") val id: Long,
    @JsonProperty("name") val name: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("release_date") val releaseDate: String,
    @JsonProperty("seasons_count") val seasonsCount: Long? = null,
    @JsonProperty("genres") val genres: List<Genre>,
    @JsonProperty("votes") val votes: List<Vote>,
    @JsonProperty("runtime") val runtime: Long? = null
)

data class Genre(
    @JsonProperty("name") val name: String,
    @JsonProperty("pivot") val pivot: Pivot,
)

data class Pivot(
    @JsonProperty("titleID") val titleID: Long,
    @JsonProperty("genreID") val genreID: Long,
)

data class Vote(
    @JsonProperty("title_id") val title_id: Long,
    @JsonProperty("average") val average: String,
    @JsonProperty("count") val count: Long,
    @JsonProperty("type") val type: String,
)

data class VideoElement(
    @JsonProperty("id") val id: Long,
    @JsonProperty("slug") val slug: String,
    @JsonProperty("images") val images: List<Image>,
)

data class Image(
    @JsonProperty("imageable_id") val imageableID: Long,
    @JsonProperty("imageable_type") val imageableType: String,
    @JsonProperty("server_id") val serverID: Long,
    @JsonProperty("proxy_id") val proxyID: Long,
    @JsonProperty("url") val url: String,
    @JsonProperty("type") val type: String,
//    @JsonProperty("sc_url") val scURL: String,
//    @JsonProperty("proxy") val proxy: Proxy,
//    @JsonProperty("server") val server: Proxy
)
// Proxy is not used and crashes otherwise
//data class Proxy(
//    @JsonProperty("id") val id: Long,
//    @JsonProperty("type") val type: String,
//    @JsonProperty("ip") val ip: String,
//    @JsonProperty("number") val number: Long,
//    @JsonProperty("storage") val storage: Long,
//    @JsonProperty("max_storage") val maxStorage: Long,
//    @JsonProperty("max_conversions") val maxConversions: Any? = null,
//    @JsonProperty("max_publications") val maxPublications: Any? = null,
//    @JsonProperty("created_at") val createdAt: String,
//    @JsonProperty("updated_at") val updatedAt: String,
//    @JsonProperty("upload_bandwidth") val uploadBandwidth: Any? = null,
//    @JsonProperty("upload_bandwidth_limit") val uploadBandwidthLimit: Any? = null
//)

data class Season(
    @JsonProperty("id") val id: Long,
    @JsonProperty("name") val name: String? = "",
    @JsonProperty("plot") val plot: String? = "",
    @JsonProperty("date") val date: String? = "",
    @JsonProperty("number") val number: Long,
    @JsonProperty("title_id") val title_id: Long,
    @JsonProperty("createdAt") val createdAt: String? = "",
    @JsonProperty("updated_at") val updatedAt: String? = "",
    @JsonProperty("episodes") val episodes: List<Episodejson>
)

data class Episodejson(
    @JsonProperty("id") val id: Long,
    @JsonProperty("number") val number: Long,
    @JsonProperty("name") val name: String? = "",
    @JsonProperty("plot") val plot: String? = "",
    @JsonProperty("season_id") val seasonID: Long,
    @JsonProperty("images") val images: List<ImageSeason>
)

data class ImageSeason(
    @JsonProperty("imageable_id") val imageableID: Long,
    @JsonProperty("imageable_type") val imageableType: String,
    @JsonProperty("server_id") val serverID: Long,
    @JsonProperty("proxy_id") val proxyID: Long,
    @JsonProperty("url") val url: String,
    @JsonProperty("type") val type: String,
    @JsonProperty("original_url") val originalURL: String
)

data class TrailerElement(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("host") val host: String? = null,
    @JsonProperty("videoable_id") val videoableID: Long? = null,
    @JsonProperty("videoable_type") val videoableType: String? = null,
    @JsonProperty("created_at") val createdAt: String? = null,
    @JsonProperty("updated_at") val updatedAt: String? = null,
    @JsonProperty("size") val size: String? = null,
    @JsonProperty("created_by") val createdBy: String? = null,
    @JsonProperty("server_id") val serverID: Long? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("original_name") val originalName: Any? = null,
    @JsonProperty("views") val views: Long? = null,
    @JsonProperty("public") val public: Long? = null,
    @JsonProperty("proxy_id") val proxyID: Any? = null,
    @JsonProperty("proxy_default_id") val proxyDefaultID: Any? = null,
    @JsonProperty("scws_id") val scwsID: Any? = null
)
