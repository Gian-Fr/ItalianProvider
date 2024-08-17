package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.FormBody
import org.jsoup.nodes.Element

class EurostreamingProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://eurostreaming.money"
    override var name = "Eurostreaming"
    override val hasMainPage = false
    override val hasChromecastSupport = true
    private val interceptor = CloudflareKiller()
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    fun extractSeasonAndEpisode(line: String): Pair<Int, Int> {
        val regex = Regex("""(\d+)×(\d+)""")
        val matchResult = regex.find(line)
        return if (matchResult != null) {
            val (season, episode) = matchResult.destructured
            Pair(season.toInt(), episode.toInt())
        } else {
            throw IllegalArgumentException("No match found in line: $line")
        }
    }

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/serie-tv-archive/page/" to "Ultime serie Tv",
        "$mainUrl/animazione/page/" to "Ultime serie Animazione",
        )

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val image = fixUrlNull(this.selectFirst("img.Thumbnail")?.attr("src")?.trim())

        return newTvSeriesSearchResponse(title, link, TvType.TvSeries){
            this.posterUrl = image
            this.posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val encodedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/index.php?s=$encodedQuery"
        val results = app.get(
            headers = mapOf("user-agent" to userAgent),
            url = searchUrl,
            ).document

        return results.select("div.post-thumb").mapNotNull {
            it?.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val page = app.get(url,headers = mapOf("user-agent" to userAgent))
        val document = page.document
        val title = document.selectFirst(".entry-title")!!.text()
        val style = document.selectFirst("div.entry-content")!!.attr("style")
        val poster = fixUrl(document.selectFirst("div.entry-content > p > img")!!.attr("src"))
        val episodeList = ArrayList<Episode>()
        document.select("div.entry-content > div > div").map { element ->
            var nextEpisode=false
            val season= element.select("div:nth-child(2)")
                //process episode names
            // Split the text by '\n' and process each line
            val lines = season.text().split("\n")
                .map { it.trim() } // Trim each line
                .filter { it.isNotEmpty() } // Filter out any empty lines
                .map { it.substringBefore("–") } // Remove part after the first '–'

            // Create a mutable list to store the processed lines
            val episodes_names = mutableListOf<String>()
            var EpisodeNameIndex= 0
            // Add each line to the list
            episodes_names.addAll(lines)

            //no more <li> , but only <a> separated with <br>

            val links = season.first()!!.children()
            for (link in links){
                if (link.tagName()=="a" && !nextEpisode){
                    val data = link.attr("href")
                    val epTitle = episodes_names.get(EpisodeNameIndex)
                    val (seasonNum, epNum) = extractSeasonAndEpisode(epTitle)

                    EpisodeNameIndex++
                    episodeList.add(
                        Episode(
                            data,
                            epTitle,
                            seasonNum,
                            epNum

                        ))
                    nextEpisode=true
                }
                if(link.tagName()=="br"){
                    nextEpisode=false
                }

            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        }

    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        parseJson<List<String>>(data).map { videoUrl ->
            loadExtractor(videoUrl, data, subtitleCallback, callback)
        }
        return true
    }
}
