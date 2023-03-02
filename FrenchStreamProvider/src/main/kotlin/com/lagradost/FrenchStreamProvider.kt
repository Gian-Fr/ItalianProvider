package com.lagradost


import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.extractorApis
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element


class FrenchStreamProvider : MainAPI() {
    override var mainUrl = "https://streem.re" //re ou ac ou city
    override var name = "FrenchStream"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "fr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?do=search&subaction=search&story=$query" // search'
        val document =
            app.post(link).document // app.get() permet de télécharger la page html avec une requete HTTP (get)
        val results = document.select("div#dle-content > > div.short")

        val allresultshome =
            results.mapNotNull { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return allresultshome
    }

    private fun Element.takeEpisode(
        url: String,
    ): List<Episode> {
        return this.select("a").map { a ->
            val epNum =
                Regex("""pisode[\s]+(\d+)""").find(a.text().lowercase())?.groupValues?.get(1)
                    ?.toIntOrNull()
            val epTitle = if (a.text().contains("Episode")) {
                val type = if ("honey" in a.attr("id")) {
                    "VF"
                } else {
                    "Vostfr"
                }
                "Episode $type"
            } else {
                a.text()
            }

            Episode(
                loadLinkData(
                    fixUrl(url),
                    epTitle.contains("Vostfr"),
                    epNum,
                ).toJson(),
                epTitle,
                null,
                epNum,
                a.selectFirst("div.fposter > img")?.attr("src"),
            )
        }
    }

    data class loadLinkData(
        val embedUrl: String,
        val isVostFr: Boolean? = null,
        val episodenumber: Int? = null,
    )

    override suspend fun load(url: String): LoadResponse {
        val soup = app.get(url).document
        var subEpisodes = listOf<Episode>()
        var dubEpisodes = listOf<Episode>()
        val title = soup.selectFirst("h1#s-title")!!.text().toString()
        val isMovie = !url.contains("/serie/", ignoreCase = true)
        val description =
            soup.selectFirst("div.fdesc")!!.text().toString()
                .split("streaming", ignoreCase = true)[1].replace(":", "")
        val poster = soup.selectFirst("div.fposter > img")?.attr("src")
        val listEpisode = soup.select("div.elink")
        val tags = soup.select("ul.flist-col > li").getOrNull(1)
        //val rating = soup.select("span[id^=vote-num-id]")?.getOrNull(1)?.text()?.toInt()

        if (isMovie) {
            val yearRegex = Regex("""ate de sortie\: (\d*)""")
            val year = yearRegex.find(soup.text())?.groupValues?.get(1)
            val tagsList = tags?.select("a")
                ?.mapNotNull {   // all the tags like action, thriller ...; unused variable
                    it?.text()
                }
            return newMovieLoadResponse(title, url, TvType.Movie, loadLinkData(url)) {
                this.posterUrl = poster
                this.year = year?.toIntOrNull()
                this.tags = tagsList
                this.plot = description
                //this.rating = rating
                addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
            }
        } else {
            if ("<a" in listEpisode[1].toString()) {  // check if VF is empty
                subEpisodes = listEpisode[1].takeEpisode(url)//  return vostfr
            }
            if ("<a" in listEpisode[0].toString()) {
                dubEpisodes = listEpisode[0].takeEpisode(url)//  return vf
            }
            val yearRegex = Regex("""Titre .* \/ (\d*)""")
            val year = yearRegex.find(soup.text())?.groupValues?.get(1)
            return newAnimeLoadResponse(
                title,
                url,
                TvType.TvSeries,
            ) {
                this.posterUrl = poster
                this.plot = description
                this.year = year?.toInt()
                addTrailer(soup.selectFirst("button#myBtn > a")?.attr("href"))
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        }
    }

    fun translate(
        // the website has weird naming of series for episode 2 and 1 and original version content
        episodeNumber: String,
        is_vf_available: Boolean,
    ): String {
        return if (episodeNumber == "1") {
            if (is_vf_available) {  // 1 translate differently if vf is available or not
                "FGHIJK"
            } else {
                "episode033"
            }
        } else {
            "episode" + (episodeNumber.toInt() + 32).toString()
        }
    }


    override suspend fun loadLinks( // TODO FIX *Garbage* data transmission betwenn function
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val parsedData =  tryParseJson<loadLinkData>(data)
        val url = parsedData?.embedUrl ?: return false
        val servers =
            if (parsedData.episodenumber != null)// It's a serie:
            {
                val isvostfr = parsedData.isVostFr == true


                val wantedEpisode =
                    if (parsedData.episodenumber.toString() == "2") { // the episode number 2 has id of ABCDE, don't ask any question
                        "ABCDE"
                    } else {
                        "episode" + parsedData.episodenumber.toString()
                    }


                val soup = app.get(fixUrl(url)).document
                val div =
                    if (wantedEpisode == "episode1") {
                        "> div.tabs-sel "  // this element is added when the wanted episode is one (the place changes in the document)
                    } else {
                        ""
                    }
                val serversvf =// French version servers
                    soup.select("div#$wantedEpisode > div.selink > ul.btnss $div> li")
                        .mapNotNull { li ->  // list of all french version servers
                            val serverUrl = fixUrl(li.selectFirst("a")!!.attr("href"))
//                            val litext = li.text()
                            if (serverUrl.isNotBlank()) {
                                if (li.text().replace("&nbsp;", "").replace(" ", "").isNotBlank()) {
                                    Pair(li.text().replace(" ", ""), fixUrl(serverUrl))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }

                val translated = translate(parsedData.episodenumber.toString(), serversvf.isNotEmpty())
                val serversvo =  // Original version servers
                    soup.select("div#$translated > div.selink > ul.btnss $div> li")
                        .mapNotNull { li ->
                            val serverUrl = fixUrlNull(li.selectFirst("a")?.attr("href"))
                            if (!serverUrl.isNullOrEmpty()) {
                                if (li.text().replace("&nbsp;", "").isNotBlank()) {
                                    Pair(li.text().replace(" ", ""), fixUrl(serverUrl))
                                } else {
                                    null
                                }
                            } else {
                                null
                            }
                        }
                if (isvostfr) {
                    serversvo
                } else {
                    serversvf
                }
            } else {  // it's a movie
                val movieServers =
                    app.get(fixUrl(url)).document.select("nav#primary_nav_wrap > ul > li > ul > li > a")
                        .mapNotNull { a ->
                            val serverurl = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                            val parent = a.parents()[2]
                            val element = parent.selectFirst("a")!!.text().plus(" ")
                            if (a.text().replace("&nbsp;", "").isNotBlank()) {
                                Pair(element.plus(a.text()), fixUrl(serverurl))
                            } else {
                                null
                            }
                        }
                movieServers
            }
        servers.apmap {
            val urlplayer = it.second

            val playerUrl = if (urlplayer.contains("opsktp.com") || urlplayer.contains("flixeo.xyz")) {
                val header = app.get(
                    "https" + it.second.split("https")[1],
                    allowRedirects = false
                ).headers
                header["location"].toString()
            } else {
                urlplayer
            }.replace("https://doodstream.com", "https://dood.yt")
            loadExtractor(playerUrl, mainUrl, subtitleCallback, callback)
        }

        return true
    }


    private fun Element.toSearchResponse(): SearchResponse {

        val posterUrl = fixUrl(select("a.short-poster > img").attr("src"))
        val qualityExtracted = select("span.film-ripz > a").text()
        val type = select("span.mli-eps").text().lowercase()
        val title = select("div.short-title").text()
        val link = select("a.short-poster").attr("href").replace("wvw.", "") //wvw is an issue
        val quality = getQualityFromString(
            when (!qualityExtracted.isNullOrBlank()) {
                qualityExtracted.contains("HDLight") -> "HD"
                qualityExtracted.contains("Bdrip") -> "BlueRay"
                qualityExtracted.contains("DVD") -> "DVD"
                qualityExtracted.contains("CAM") -> "Cam"
                else -> null
            }
        )

        if (!type.contains("eps")) {
            return MovieSearchResponse(
                name = title,
                url = link,
                apiName = title,
                type = TvType.Movie,
                posterUrl = posterUrl,
                quality = quality

            )


        } else  // a Serie
        {
            return newAnimeSearchResponse(
                name = title,
                url = link,
                type = TvType.TvSeries,

                ) {
                this.posterUrl = posterUrl
                addDubStatus(
                    isDub = select("span.film-verz").text().uppercase().contains("VF"),
                    episodes = select("span.mli-eps>i").text().toIntOrNull()
                )
            }


        }
    }

    data class mediaData(
        @JsonProperty("title") var title: String,
        @JsonProperty("url") val url: String,
    )

    override val mainPage = mainPageOf(
        Pair("/xfsearch/version-film/page/", "Derniers Films"),
        Pair("/xfsearch/version-serie/page/", "Dernieres Séries"),
        Pair("/film/arts-martiaux/page/", "Films Arts martiaux"),
        Pair("/film/action/page/", "Films Action"),
        Pair("/film/romance/page/", "Films Romance"),
        Pair("/serie/aventure-serie/page/", "Séries aventure"),
        Pair("/film/documentaire/page/", "Documentaires")

    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = mainUrl + request.data + page
        val document = app.get(url).document
        val movies = document.select("div#dle-content > div.short")

        val home =
            movies.map { article ->  // avec mapnotnull si un élément est null, il sera automatiquement enlevé de la liste
                article.toSearchResponse()
            }
        return newHomePageResponse(request.name, home)
    }
}

