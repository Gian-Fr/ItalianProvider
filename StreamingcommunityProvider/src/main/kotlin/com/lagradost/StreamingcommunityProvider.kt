package com.lagradost

import android.util.Log
import com.fasterxml.jackson.annotation.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.*
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

fun getImageUrl(mainUrl: String,url: String): String {
    val cdnprefix = mainUrl.replace("streamingcommunity", "cdn.streamingcommunity")
    val poster = "$cdnprefix/images/$url"
    return poster
}
class StreamingcommunityProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://streamingcommunity.prof"
    override var name = "StreamingCommunity"
    override val hasMainPage = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    private val gson = Gson()

    private fun TitleGson.toSearchResult(): SearchResponse? {
        val title = this.name
        val link = "$mainUrl/titles/${this.id}-${this.slug}"
        val cdnprefix = mainUrl.replace("streamingcommunity", "cdn.streamingcommunity")
        val posterUrl = "${cdnprefix}/images/${this.images[0].filename}"
        val response= when (this.type){
            "movie"->{
                 newMovieSearchResponse(title, link, TvType.Movie) {
                    addPoster(posterUrl)
                }
            }
            "tv"->{

                 newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                    addPoster(posterUrl)
                }
            }
            else->{
                return null
            }
        }
        return response
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val queryFormatted = query.replace(" ", "%20")
        val url = "$mainUrl/search?q=$queryFormatted"
        val document = app.get(url, headers = mapOf("user-agent" to userAgent)).document
        val resultsJson = document.select("#app").attr("data-page")
        val results = gson.fromJson(resultsJson, FullResponseGson::class.java).props.titles
        val resultsList = mutableListOf<SearchResponse>()
        for (title in results) {
            resultsList.add(title.toSearchResult()!!)
        }
        return resultsList
    }

    //RESPONSE DATACLASSES
    data class Image(
        val id: Int,
        val filename: String
    )

    data class Title(
        val id: Int,
        val images: List<Image>,
        val type: String,
        val release_date: String,
        val name: String,
        val plot:String,
        val seasons: List<Season>,
        val seasons_count: Int
    )
data class LoadedSeason(
 val episodes: List<LoadedEpisode>,
    val number: Int
)
    data class LoadedEpisode(
        val id: Int,
        val name: String,
        val plot: String,
        val number: Int,
        val images: List<Image>
    )
    data class Props(
        val title: Title,
        val loadedSeason: LoadedSeason,
    )

    data class Response(
        val props: Props
    )

    data class Season(
        val id: Int,
        val number: Int,
        val episodes_count: Int
    )


    override suspend fun load(url: String): LoadResponse? {
//        Log.d("JSONLOG", url)
        val document = app.get(url, headers = mapOf("user-agent" to userAgent)).document
        val resultJson = document.select("#app").attr("data-page")
        val response = gson.fromJson(resultJson, Response::class.java)
        val plot= response.props.title.plot
        val poster=getImageUrl(mainUrl, response.props.title.images[3].filename)

        when (response.props.title.type) {
            "movie" -> {
                return newMovieLoadResponse(
                    response.props.title.name,
                    mainUrl+"/watch/"+response.props.title.id,
                    TvType.Movie,
                    mainUrl+"/watch/"+response.props.title.id
                ) {
                    posterUrl = poster
                    this.plot =plot
                    this.year= response.props.title.release_date.substringBefore("-").toInt()

                }

            }
            "tv" -> {
                val name = response.props.title.name
                val episodesList = mutableListOf<Episode>()

                val seasons = response.props.title.seasons
                var episode_id=response.props.loadedSeason.episodes[0].id
                seasons.map { season ->
                    val seasonDocument = app.get(url+"/stagione-"+season.number.toString(), headers = mapOf("user-agent" to userAgent)).document
                    val resultJson = seasonDocument.select("#app").attr("data-page")
                    val episodeResponse = gson.fromJson(resultJson, Response::class.java)
                    for (episode in episodeResponse.props.loadedSeason.episodes) {
                        val href = mainUrl+"/watch/"+response.props.title.id+"?episode_id="+episode_id
                        val postImage = getImageUrl(mainUrl, episode.images.firstOrNull()!!.filename)
                        episodesList.add(newEpisode(href) {
                            this.name = episode.name
                            this.season = season.number
                            this.episode = episode.number
                            this.description = episode.plot
                            this.posterUrl = postImage
                        })
episode_id++
                    }
                }
                return newTvSeriesLoadResponse(name,mainUrl,TvType.TvSeries,episodesList){
                    this.posterUrl=poster
                    this.plot =plot
                }
            }

            else -> {
                return null
            }
        }
     }



    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dataLink= data.replace("watch","iframe")
//        Log.d("JSONLOG","LINK ORIGINALE "+dataLink)
        val links= app.get(dataLink).document.select("iframe").attr("src")
//        Log.d("JSONLOG","LINK IFRAME "+links)
        val regex = "[\"']https.*?[\"']".toRegex()
        val vixUrl= app.get(links).document.select("script")[4]
        val vixUrls=regex.findAll(vixUrl.toString()).map { it.value }.toList()
//            Log.d("JSONLOG","link extratto: "+vixUrls[2].substring(1,vixUrls[2].length-1,))
        val urlnoexpire=(vixUrls[2].substring(1,vixUrls[2].length-1))
        val expire = (System.currentTimeMillis() / 1000 + 172800).toString()
        val finalUrl= urlnoexpire+"&expires="+expire
//        Log.d("JSONLOG","urlfinale "+finalUrl)
        callback.invoke(
            ExtractorLink(
                name,
                name,
                finalUrl,
                isM3u8 = true,
                referer = mainUrl,
                quality = Qualities.Unknown.value
            )
        )
        return true
    }


}


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


data class Image(
    @JsonProperty("imageable_id") val imageableID: Long,
    @JsonProperty("imageable_type") val imageableType: String,
    @JsonProperty("server_id") val serverID: Long,
    @JsonProperty("proxy_id") val proxyID: Long,
    @JsonProperty("url") val url: String,
    @JsonProperty("type") val type: String,
)


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


