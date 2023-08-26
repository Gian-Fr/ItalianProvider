package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.FormBody
import org.jsoup.nodes.Element

class CineBlog01Provider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://cb01.red"
    override var name = "CineBlog01"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override var sequentialMainPage = true
    override val supportedTypes = setOf(
        TvType.Movie,
    )
    override val mainPage = mainPageOf(
        Pair("$mainUrl/cinema/page/", "Film Cinema"),
        Pair("$mainUrl/sub-ita/page/", "Film Sub-ita")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = request.data + page
        val soup = app.get(url).document
        val home = soup.select("div.card").mapNotNull { series ->
            series.toSearchResult()
        }
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title =
            this.selectFirst("img")?.attr("alt") ?: throw ErrorLoadingException("No Title found")
        val link =
            this.selectFirst("a")?.attr("href") ?: throw ErrorLoadingException("No Link found")
        val posterUrl = fixUrl(
            this.selectFirst("img")?.attr("src") ?: throw ErrorLoadingException("No Poster found")
        )
        val year = Regex("\\(([^)]*)\\)").find(
            title
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        return newMovieSearchResponse(
            title,
            link,
            TvType.TvSeries
        ) {
            this.year = year
            addPoster(posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val body = FormBody.Builder()
            .addEncoded("do", "search")
            .addEncoded("subaction", "search")
            .addEncoded("story", query)
            .addEncoded("sortby", "news_read")
            .build()
        val doc = app.post(
            "$mainUrl/index.php",
            requestBody = body
        ).document

        return doc.select("div.card").mapNotNull { series ->
            series.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: ""
        val description = document.selectFirst("div.ignore-css")?.text()?.removeSuffix(" +Info »")
            ?.substringAfter("′ - ")
        val year = Regex("\\(([^)]*)\\)").find(
            title
        )?.groupValues?.getOrNull(1)?.toIntOrNull()
        val poster = fixUrl(document.getElementsByAttributeValue("itemprop", "image").firstOrNull()?.attr("src")?: throw ErrorLoadingException("No Poster found"))
        val dataUrl = document.selectFirst("div.guardahd-player iframe")?.attr("src")?: throw ErrorLoadingException("No Links found")
        val trailerUrl =Regex("src=\"(https:\\/\\/www\\.youtube\\.com\\/embed\\/[a-zA-Z0-9_-]+)\" ").find(document.toString())?.groupValues?.lastOrNull()

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            dataUrl = dataUrl
        ) {
            this.plot = description
            this.year = year
            this.posterUrl = poster
            addTrailer(trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        res.select("ul._player-mirrors > li").forEach { source ->
            loadExtractor(
                fixUrl(source.attr("data-link")),
                "https://guardahd.stream",
                subtitleCallback,
                callback
            )
        }
        return true
    }
}