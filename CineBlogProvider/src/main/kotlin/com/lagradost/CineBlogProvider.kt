package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.network.CloudflareKiller


class CineBlogProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://cb01.church"
    override var name = "CB01"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    val interceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        Pair("$mainUrl/popolari/page/number/?get=movies", "Film Popolari"),
        Pair("$mainUrl/popolari/page/number/?get=tv", "Serie Tv Popolari"),
        Pair("$mainUrl/i-piu-votati/page/number/?get=movies", "Film più votati"),
        Pair("$mainUrl/i-piu-votati/page/number/?get=tv", "Serie Tv più votate"),
        Pair("$mainUrl/anno/2022/page/number", "Ultime uscite"),
    )

    override suspend fun getMainPage(
        page: Int, request: MainPageRequest
    ): HomePageResponse {
        val url = request.data.replace("number", page.toString())
        val soup = app.get(url, referer = url.substringBefore("page")).document
        val home = soup.select("article.item").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(arrayListOf(HomePageList(request.name, home)), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title =
            this.selectFirst("div.data > h3 > a")?.text()?.substringBefore("(") ?: this.selectFirst(
                "a > img"
            )?.attr("alt")?.substringBeforeLast("(")
            ?: throw ErrorLoadingException("No Title found")

        val link =
            this.selectFirst("div.poster > a")?.attr("href") ?: this.selectFirst("a")?.attr("href")
            ?: throw ErrorLoadingException("No Link found")

        val quality = this.selectFirst("span.quality")?.text()

        val posterUrl =
            this.selectFirst("img")?.attr("src") ?: this.selectFirst("a > img")?.attr("src")

        return newMovieSearchResponse(title, link, TvType.Movie) {
            addPoster(posterUrl)
            if (quality != null) {
                addQuality(quality)
            }
        }
    }

    private fun Element.toEpisode(season: Int): Episode {
        val href = this.selectFirst("div.episodiotitle > a")?.attr("href")
            ?: throw ErrorLoadingException("No Link found")
        val epNum =
            this.selectFirst("div.numerando")?.text()?.substringAfter("-")?.filter { it.isDigit() }
                ?.toIntOrNull()
        val epTitle = this.selectFirst("div.episodiotitle > a")?.text()
            ?: throw ErrorLoadingException("No Title found")
        val posterUrl = this.selectFirst("div.imagen > img")?.attr("src")
        return Episode(
            href,
            epTitle,
            season,
            epNum,
            posterUrl,
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val queryFormatted = query.replace(" ", "+")
        val url = "$mainUrl?s=$queryFormatted"
        val doc = app.get(url, referer = mainUrl).document
        return doc.select("div.result-item").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val type = if (url.contains("film")) TvType.Movie else TvType.TvSeries
        val title = document.selectFirst("div.data > h1")?.text()?.substringBefore("(")
            ?: throw ErrorLoadingException("No Title found")
        val description = document.select("#info > div.wp-content > p").html().toString()
        val year =
            document.selectFirst(" div.data > div.extra > span.date")?.text()?.substringAfter(",")
                ?.filter { it.isDigit() }.let { it?.dropLast(4) }
        val poster = document.selectFirst("#dt_galery")?.selectFirst("a")?.attr("href")?.trim()
            ?: document.selectFirst("div.poster > img")?.attr("src")
        val recommendations = document.select("#single_relacionados >article").map {
            it.toSearchResult()
        }

        if (type == TvType.TvSeries) {
            val episodeList = document.select("#seasons > div").reversed().map { element ->
                val season = element.selectFirst("div.se-q > span.se-t")?.text()?.toIntOrNull()
                    ?: throw ErrorLoadingException("No Season found")
                element.select("div.se-a > ul > li")
                    .filter { it -> it.text() != "There are still no episodes this season" }
                    .map { episode ->
                        episode.toEpisode(season)
                    }
            }.flatten()

            return newTvSeriesLoadResponse(
                title, url, type, episodeList
            ) {
                this.recommendations = recommendations
                this.year = year?.toIntOrNull()
                this.plot = description
                addPoster(poster)
            }
        } else {
            val actors: List<ActorData> = document.select("div.person").filter { it ->
                it.selectFirst("div.img > a > img")?.attr("src")?.contains("/no/cast.png")?.not()
                    ?: false
            }.map { actordata ->
                val actorName = actordata.selectFirst("div.data > div.name > a")?.text()
                    ?: throw ErrorLoadingException("No Actor name found")
                val actorImage: String? = actordata.selectFirst("div.img > a > img")?.attr("src")
                val roleActor = actordata.selectFirst("div.data > div.caracter")?.text()
                ActorData(actor = Actor(actorName, image = actorImage), roleString = roleActor)
            }
            return newMovieLoadResponse(
                title, url, type, url
            ) {
                this.recommendations = recommendations
                this.year = year?.toIntOrNull()
                this.plot = description
                this.actors = actors
                addPoster(poster)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        /*

        Retrieve postId from the "video" page.
        Send a POST request to mainUrl/wp-admin/admin-ajax.php with the postId.
        Receive a JSON response with the format {"embed_url":"https:\/\/wws.vedere.stream\/aabbcc","type":"iframe"}.
        Extract the embed_url from the JSON using regular expressions.
        Replace the \/ characters in the embed_url with / and wws to v2.
        Send a GET request to the vedere.stream link to obtain the actual video link.
        The video link is then passed through the extractor.

        */

        val doc = app.get(data).document
        val type = if (data.contains("film")) {
            "movie"
        } else {
            "tv"
        }
        val idPost = doc.select("#player-option-1").attr("data-post")
        val getMiddleUrl = app.post(
            "$mainUrl/wp-admin/admin-ajax.php", headers = mapOf(
                "content-type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
            ), data = mapOf(
                "action" to "doo_player_ajax",
                "post" to idPost,
                "nume" to "1",
                "type" to type,
            )
        )
        val middleUrl =
            Regex("""embed_url":"(.*)",""").find(getMiddleUrl.text)?.groups?.get(1)?.value.toString()
                .replace(
                    "\\/", "/"
                )
                .replace(
                    "/wws.", "/v2."
                )
        val trueUrl = app.get(
            middleUrl, headers = mapOf("referer" to mainUrl), interceptor = interceptor
        ).url
        loadExtractor(trueUrl, data, subtitleCallback, callback)

        return true
    }
}