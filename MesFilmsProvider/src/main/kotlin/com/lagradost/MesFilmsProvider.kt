package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addRating
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.metaproviders.CrossTmdbProvider
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup


class MesFilmsProvider : MainAPI() {
    override var mainUrl = "https://mesfilms.lol"
    override var name = "Mes Films"
    override val hasQuickSearch = false // recherche rapide (optionel, pas vraimet utile)
    override val hasMainPage = true // page d'accueil (optionel mais encoragé)
    override var lang = "fr" // fournisseur est en francais
    override val supportedTypes = setOf(TvType.Movie) // ici on ne supporte que les films
    //     override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries) // films et series
    // liste des types: https://recloudstream.github.io/dokka/app/com.lagradost.cloudstream3/-tv-type/index.html

    /**
    Cherche le site pour un titre spécifique

    La recherche retourne une SearchResponse, qui peut être des classes suivants: AnimeSearchResponse, MovieSearchResponse, TorrentSearchResponse, TvSeriesSearchResponse
    Chaque classes nécessite des données différentes, mais a en commun le nom, le poster et l'url
     **/
    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/?s=$query"
        val document = app.get(link).document // on convertit le html en un document
        return document.select("div.search-page > div.result-item > article") // on séléctione tous les éléments 'enfant' du type articles
            .filter { article -> // on filtre la liste obtenue de tous les articles
                val type = article?.selectFirst("> div.image > div.thumbnail > a > span")?.text()
                    ?.replace("\t", "")?.replace("\n", "")

                type != "Épisode" // enlève tous les éléments qui sont des épisodes de série
            }.mapNotNull { div -> // apmap crée une liste des éléments (ici newMovieSearchResponse et newTvSeriesSearchResponse)
                val posterContainer = div.selectFirst("> div.image > div.thumbnail > a") // selectione le premier élément correspondant à ces critères
                val type = posterContainer?.selectFirst("> span")?.text()?.replace("\t", "")?.replace("\n", "")
                // replace enlève tous les '\t' et '\n' du titre
                val mediaPoster = posterContainer?.selectFirst("> img")?.attr("src") // récupère le texte de l'attribut src de l'élément

                val href = posterContainer?.attr("href") ?: return@mapNotNull null // renvoie une erreur si il n'y a pas de lien vers le média
                // val test1 = stringVariable ?: "valeur par défault"
                // val test2 = stringVariable ?: doSomething()  // si stringVariable est null, on appelle la fonction doSomething
                // '?:' est appelé Elvis Operator, si la variable stringVariable est null, alors on utilise la "valeur par défault"
                // https://stackoverflow.com/questions/48253107/what-does-do-in-kotlin-elvis-operator
                // val details = div.select("> div.details > div.meta")
                //val rating = details.select("> span.rating").text()
                // val year = details.select("> span.year").text().toIntOrNull()

                val title = div.selectFirst("> div.details > div.title > a")?.text().toString()

                when (type) {
                    "FILM" -> (
                            newMovieSearchResponse( // réponse du film qui sera ajoutée à la liste map qui sera ensuite return
                                title,
                                href,
                                TvType.Movie,
                                false
                            ) {
                                this.posterUrl = mediaPoster
                                // this.year = year
                                // this.rating = rating
                            }
                            )
                    else -> {
                        return@mapNotNull null // le type n'est pas reconnu ==> enlever
                    }
                }
            }
    }

    private data class EmbedUrlClass(
        @JsonProperty("embed_url") val url: String?,
    )

    /**
     * charge la page d'informations, il ya toutes les donées, les épisodes, le résumé etc ...
     * Il faut retourner soit: AnimeLoadResponse, MovieLoadResponse, TorrentLoadResponse, TvSeriesLoadResponse.
     */

    data class EpisodeData(
        @JsonProperty("url") val url: String,
        @JsonProperty("episodeNumber") val episodeNumber: String?,
    )

    override suspend fun load(url: String): LoadResponse {
        // url est le lien retourné par la fonction search (la variable href) ou la fonction getMainPage
        val document = app.get(url).document // convertit en document

        val meta = document.selectFirst("div.sheader")
        val poster = meta?.select("div.poster > img")?.attr("data-src") // récupere le texte de l'attribut 'data-src'

        val title = meta?.select("div.data > h1")?.text() ?: throw ErrorLoadingException("Invalid title")

        val data = meta.select("div.data")
        val extra = data.select("div.extra")

        val description = extra.select("span.tagline").first()?.text() // first() selectione le premier élément de la liste

        val rating1 = document.select("div.custom_fields > span.valor > b#repimdb > strong").text()
        val rating2 = document.select("div.custom_fields > span.valor > strong").text()

        val rating = when {
            !rating1.isNullOrBlank() -> rating1
            !rating2.isNullOrBlank() -> rating2
            else -> null
        }


        val date = extra.select("span.date").first()?.text()?.takeLast(4) // prends les 4 dernier chiffres

        val tags = data.select("div.sgeneros > a").apmap {it.text()} // séléctione tous les tags et les ajoutes à une liste

        val postId = document.select("#report-video-button-field > input[name=postID]").first()?.attr("value") // élémennt spécifique à ce site

        val mediaType = if(url.contains("/film/")) {
            "movie"
        } else {
            "TV"
        }
        // un api est disponible sur ce site pour récupérer le trailer (lien vers youtube)
        val trailerUrl = postId?.let{
            try {
                val payloadRequest = mapOf(
                    "action" to "doo_player_ajax",
                    "post" to postId,
                    "nume" to "trailer",
                    "type" to mediaType
                ) // on crée les donées de la requetes ici
                val getTrailer =
                    app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                        data = payloadRequest
                    ).text
                parseJson<EmbedUrlClass>(getTrailer).url
                // parseJson lit le contenu de la réponse (la variable getTrailer) et cherche la valeur d'embed_url dans cette réponse

            } catch (e: Exception){
                null
            }
        }


        if (mediaType == "movie") {
            return newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) { // retourne les informations du film
                this.posterUrl = poster
                addRating(rating)
                this.year = date?.toIntOrNull()
                this.tags = tags
                this.plot = description
                trailerUrl?.let{addTrailer(trailerUrl)}
            }
        } else  // a tv serie
        {
            throw ErrorLoadingException("Nothing besides movies are implemented for this provider")
        }
    }


    // récupere les liens .mp4 ou m3u8 directement à partir du paramètre data généré avec la fonction load()
    override suspend fun loadLinks(
        data: String, // fournit par load()
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val document = app.get(data).document

        document.select("ul#playeroptionsul > li:not(#player-option-trailer)").apmap { li -> // séléctione tous les players sauf celui avec l'id player-option-trailer
            // https://jsoup.org/cookbook/extracting-data/selector-syntax
            val quality = li.selectFirst("span.title")?.text()
            val server = li.selectFirst("> span.server")?.text()
            val languageInfo =

                li.selectFirst("span.flag > img")?.attr("data-src")
                    ?.substringAfterLast("/") // séléctione la partie de la chaine de caractère apr_s le dernier /
                    ?.replace(".png", "") ?: ""

            val postId = li.attr("data-post")

            val indexOfPlayer = li.attr("data-nume")

            // un api est disponible sur le site pour récupéré les liens vers des embed (iframe) type uqload
            val payloadRequest = mapOf(
                "action" to "doo_player_ajax",
                "post" to postId,
                "nume" to indexOfPlayer,
                "type" to "movie"
            )


            val getPlayerEmbed =
                app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"),
                    data = payloadRequest
                ).text
            val playerUrl = parseJson<EmbedUrlClass>(getPlayerEmbed).url // récupère l'url de l'embed en lisant le json

            if (playerUrl != null)
                loadExtractor(
                    httpsify(playerUrl),
                    playerUrl,
                    subtitleCallback
                ) { link -> // charge un extracteur d'extraire le lien direct .mp4
                    callback.invoke(ExtractorLink( // ici je modifie le callback pour ajouter des informations, normalement ce n'est pas nécessaire
                        link.source,
                        link.name + " $languageInfo",
                        link.url,
                        link.referer,
                        getQualityFromName(quality),
                        link.isM3u8,
                        link.headers,
                        link.extractorData
                    ))
                }
        }


        return true
    }


    override val mainPage = mainPageOf(
        Pair("$mainUrl/film/", "Récemment ajouté"),
        Pair("$mainUrl/tendance/?get=movies", "Tendance"),
        Pair("$mainUrl/evaluations/?get=movies", "Les plus notés"),
        Pair("$mainUrl/films-classiques/", "Quelques classiques"),
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val movies = document.select("div.items > article.movies")
        val categoryTitle = request.name
        val returnList = movies.mapNotNull { article ->
            // ici si un élément est null, il sera automatiquement enlevé de la liste
            val poster = article.select("div.poster")
            val posterUrl = poster.select("> img").attr("data-src")
            val qualityExtracted = poster.select("> div.mepo > span.quality").text().uppercase()

            val quality = getQualityFromString(
                when (!qualityExtracted.isNullOrBlank()) {
                    qualityExtracted.contains("WEBRIP") -> {
                        "WebRip"
                    }
                    qualityExtracted.contains("HDRIP") -> {
                        "HD"
                    }
                    qualityExtracted.contains("HDLIGHT") -> {
                        "HD"
                    }
                    qualityExtracted.contains("BDRIP") -> {
                        "BlueRay"
                    }
                    qualityExtracted.contains("DVD") -> {
                        "DVD"
                    }
                    qualityExtracted.contains("CAM") -> {
                        "Cam"
                    }

                    else -> {
                        null
                    }
                }
            )

            val data = article.select("div.data")
            val title = data.select("> h3 > a").text()
            val link = data.select("> h3 > a").attr("href")
            newMovieSearchResponse(
                title,
                link,
                TvType.Movie,
                false,
            ) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
        if (returnList.isEmpty()) throw ErrorLoadingException("No movies")
        return HomePageResponse(
            listOf(
                HomePageList(categoryTitle, returnList)
            )
        )
    }
}
