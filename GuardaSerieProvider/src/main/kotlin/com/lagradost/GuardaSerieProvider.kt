package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class GuardaSerieProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://guardaserie.food"
    override var name = "GuardaSerie"
    override val hasMainPage = false
    override val hasChromecastSupport = true
    override var sequentialMainPage = true
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    override val supportedTypes = setOf(
        TvType.TvSeries,
    )
    private val interceptor= CloudflareKiller()



    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/?story=$encodedQuery&do=search&subaction=search"
        val doc = app.get(
            headers = mapOf("user-agent" to userAgent),
           url = searchUrl, interceptor = interceptor
        ).document
        return doc.select("div.mlnew").drop(1).map { series ->
            val title = series.selectFirst("div.mlnh-2")!!.text()
            val link = series.selectFirst("div.mlnh-2 > h2 > a")!!.attr("href")
            val posterUrl = fixUrl(series.selectFirst("img")!!.attr("src")).replace("/60x85-0-85/", "/400x600-0-85/")
            newMovieSearchResponse(
                title,
                link,
                TvType.Movie
            ) {
                this.posterUrl = posterUrl
                this.posterHeaders = mapOf("user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36")
            }

        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            headers = mapOf("user-agent" to userAgent),
            url = url, interceptor = interceptor
        ).document
        val title = document.selectFirst("h1")!!.text().removeSuffix(" streaming")
        val description = document.selectFirst("div.tv_info_right")?.textNodes()?.joinToString("")?.removeSuffix("!")?.trim()
        val rating = document.selectFirst("span.post-ratings")?.text()
        var year = document.select("div.tv_info_list > ul").find { it.text().contains("Anno") }?.text()?.substringBefore("-")?.filter { it.isDigit() }?.toIntOrNull()
        val poster = Regex("poster: '(.*)'").find(document.html())?.groups?.lastOrNull()?.value?.let {
            fixUrl( it )
        }?: fixUrl(document.selectFirst("#cover")!!.attr("src"))

        val episodeList = document.select("div.tab-content > div").mapIndexed { season, data ->
            data.select("li").mapIndexed { epNum, epData ->
                val epName = epData.selectFirst("a")?.attr("data-title")
                val data = epData.select("div.mirrors > a")
                    .map { it.attr("data-link") }
                    .filter { !it.contains("#") }
                    .joinToString(",")

                Episode(
                    data = data,
                    name = epName,
                    season = season + 1,
                    episode = epNum + 1,
                )
            }
        }.flatten()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodeList
        ) {
            addRating(rating)
            this.plot = description
            this.year = year
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split(",")

        //until supervideoExtractor url is not updated in the library this is needed
        val updatedLinks = links.map { url ->
            url.replace("supervideo.cc", "supervideo.tv")
        }

        updatedLinks.forEach { url ->
            loadExtractor(url,data, subtitleCallback, callback)
        }

        return true
    }
}
