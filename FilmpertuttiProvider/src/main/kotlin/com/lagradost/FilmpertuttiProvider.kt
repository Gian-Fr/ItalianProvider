package com.lagradost


import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ShortLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element


class FilmpertuttiProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://filmpertutti.casino"
    override var name = "FilmPerTutti"
    override val hasMainPage = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"
    override var sequentialMainPage = true
    override var sequentialMainPageDelay: Long = 50
    override val mainPage = mainPageOf(
        Pair("$mainUrl/category/film/", "Film Popolari"),
        Pair("$mainUrl/category/serie-tv/", "Serie Tv Popolari"),
        Pair("$mainUrl/prime-visioni/", "Ultime uscite")
    )


    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h3 > a")?.text() ?: return null
        val link = this.selectFirst("a")?.attr("href") ?: return null
        val image = this.selectFirst("a > div > img")?.attr("src") ?: return null
        var quality : SearchQuality? = null

        val qualityRegex = "\\s*\\[HD\\]\\s*".toRegex()
        val qualityFound = qualityRegex.containsMatchIn(title)
        val cleanedTitle = title.replace(qualityRegex, "").trim()

        if (qualityFound) {
            quality = getQualityFromString("HD")
        }

        return newMovieSearchResponse(cleanedTitle, link, TvType.Movie){
                this.posterUrl = image
                this.quality= quality
            }
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val doc = app.get(
            headers = mapOf("user-agent" to userAgent),
            url = searchUrl
        ).document
        return doc.select("article").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val type =
            if (document.selectFirst("div > div.barra-tre-sezioni-nav > span.nav-item")?.text()
                    ?.lowercase()
                    .equals("episodi")
            ) TvType.TvSeries else TvType.Movie
        val titleRegex = Regex("(.*?)Streaming.*")
        val title = titleRegex.replace(document.title(),"$1")

        val description = document.select(".synopsis-meta").text().replace("Leggi di meno","")

        val rating = document.selectFirst("span.imdb-votes")?.ownText()?.replace("Voto ","")

        val yearRegex = Regex("""\b\d{4}\b""")
        val released = document.selectFirst("span.released")?.text().toString()
        val year=yearRegex.find(released)?.value?.toIntOrNull()

        val horizontalPosterData = document.selectFirst("body > main")?.attr("style")?:""
        val poster =
            Regex("url\\('(.*)'").find(horizontalPosterData)?.groups?.lastOrNull()?.value?:
            document.selectFirst("div.meta > div > img")?.attr("src")


        val trailerurl =
            document.selectFirst("div.youtube-player")?.attr("data-id")?.let { urldata ->
                "https://www.youtube.com/watch?v=$urldata"
            }

        if (type == TvType.TvSeries) {

            val episodeList = ArrayList<Episode>()
            document.select(".episodi-lista").mapIndexed { index, stagione ->
                val season = index + 1
                stagione.select("li").map {episodio->
                    val href = episodio.selectFirst("a")!!.attr("href")
                    val epTitle= episodio.ownText()
                    val epNum= epTitle.substringBefore(". ").toIntOrNull()
                    val posterUrl=episodio.selectFirst("img")?.attr("src")
                    episodeList.add(
                        Episode(
                            href,
                            epTitle,
                            season,
                            epNum,
                            posterUrl,
                        )
                    )}
            }
            return newTvSeriesLoadResponse(
                title,
                url, type, episodeList
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                addRating(rating)
                addTrailer(trailerurl)
            }
        } else {

            val urls0 = document.select("div.embed-player")
            val urls = if (urls0.isNotEmpty()) {
                urls0.map { it.attr("data-id") }.toJson()
            } else {
                document.select("#info > ul > li ").mapNotNull { it.selectFirst("a")?.attr("href") }
                    .toJson()
            }

            return newMovieLoadResponse(
                title,
                url,
                type,
                urls
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = description
                addRating(rating)
                addTrailer(trailerurl)

            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        tryParseJson<List<String>>(data)?.apmap { id ->
            val link = ShortLink.unshorten(id).trim().replace("/v/", "/e/").replace("/f/", "/e/")
            loadExtractor(link, data, subtitleCallback, callback)
        }
        return true
    }
}
