package com.lagradost


import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ShortLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element


//TEST
suspend fun main(){
    val providerTester= com.lagradost.cloudstreamtest.ProviderTester(FilmpertuttiProvider())
    providerTester.testAll("Z Nation")
}


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

        // Select all elements with a style attribute
        val styles = document.select("style")
        // Regular expression to match the background-image URL
        val regex = "background-image:\\s*url\\(['\"]([^'\"]+)['\"]\\)".toRegex()

        var poster: String? =null
         outer@ for (styleElement in styles) {
            val styleContent = styleElement.html()
            val matchResults = regex.findAll(styleContent)

            for (matchResult in matchResults) {
                val imageUrl = matchResult.groupValues[1]
                if (imageUrl.contains("https://image.tmdb.org/t/p/w1920_and_h800_multi_faces")) {
                poster=imageUrl
                break@outer}
            }
        }

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
                    val epTitle= episodio.text().substringAfter(".").trim()
                    val epNum= episodio.text().substringBefore(". ").substringAfter("►").toIntOrNull()
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
                this.posterUrl = fixUrlNull(poster)
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
        val page=app.get(data).document
        val iframeUrl= page.selectFirst("iframe")!!.attr("src")
        val iframe = app.get(iframeUrl).document
        val links=iframe.select(".megaButton")
        val linkList: List<String> = links.eachAttr("meta-link")

        // Remove the first and last elements
        val processedLinks: List<String> = if (linkList.size > 2) {
            linkList.subList(1, linkList.size )
        } else {
            emptyList()
        }

        processedLinks.forEach{
            loadExtractor(it,data,subtitleCallback,callback)
        }
        return true
    }
}
