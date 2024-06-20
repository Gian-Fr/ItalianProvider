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
    override val hasMainPage = true
    override val hasChromecastSupport = true
    private val interceptor = CloudflareKiller()
    override val supportedTypes = setOf(
        TvType.TvSeries
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/serie-tv-archive/page/" to "Ultime serie Tv",
        "$mainUrl/animazione/page/" to "Ultime serie Animazione",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page

        val soup = app.get(url, interceptor = interceptor).document
        val home = soup.select("div.post-thumb").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title") ?: return null
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val image = fixUrlNull(mainUrl + this.selectFirst("img")?.attr("src")?.trim())

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
            interceptor = interceptor
            ).document

        return results.select("div.post-thumb").mapNotNull {
            it?.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val page = app.get(url, interceptor = interceptor)
        val document = page.document
        val title = document.selectFirst("h2")!!.text().replace("^([1-9+]]$","")
        val style = document.selectFirst("div.entry-cover")!!.attr("style")
        val poster = fixUrl(Regex("(/upload.+\\))").find(style)!!.value.dropLast(1))
        val episodeList = ArrayList<Episode>()
        document.select("div.tab-pane.fade").map { element ->
            val season = element.attr("id").filter { it.isDigit() }.toInt()
            element.select("li").filter { it-> it.selectFirst("a")?.hasAttr("data-title")?:false }.map{episode ->
                val data = episode.select("div.mirrors > a").map { it.attr("data-link") }.toJson()
                val epnameData = episode.selectFirst("a")
                val epTitle = epnameData!!.attr("data-title")
                val epNum = epnameData.text().toInt()
                episodeList.add(
                    Episode(
                        data,
                        epTitle,
                        season,
                        epNum

                    )
                )
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
