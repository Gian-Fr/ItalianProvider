package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class TantifilmProvider : MainAPI() {
    override var lang = "it"
    override var mainUrl = "https://www.tantifilm.casa"
    override var name = "Tantifilm"
    override val hasMainPage = false
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/109.0.0.0 Safari/537.36"

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val doc = app.get(
            headers = mapOf("user-agent" to userAgent),
            url = searchUrl
        ).document
        return doc.select("article").map {
            val href = it.selectFirst("a")!!.attr("href")
            val poster = it.selectFirst("img")!!.attr("src")
            val name = it.selectFirst(".title > a")!!.text()
            val type = if (it.select("span.tvshows").isNotEmpty()) {
                TvType.TvSeries
            } else {
                TvType.Movie
            }
            newMovieSearchResponse(
                name,
                href,
                type,
            ){
                this.posterUrl=poster
            }

        }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document
       val title = document.selectFirst(".data>h1")!!.text()
        val description = document.select(".wp-content>p").text()
        var year = document.selectFirst(".date")!!.text().substringAfter(",").substring(1).toInt()

        val poster = document.selectFirst("img")!!.attr("src")

//        val recomm = document.select("div.mediaWrap.mediaWrapAlt.recomended_videos").map {
//            val href = it.selectFirst("a")!!.attr("href")
//            val poster = it.selectFirst("img")!!.attr("src")
//            val name = it.selectFirst("a > p")!!.text().substringBeforeLast("(")
//            MovieSearchResponse(
//                name,
//                href,
//                this.name,
//                TvType.Movie,
//                poster,
//                null,
//            )
//
//        }

//        val trailerurl = document.selectFirst("#trailer_mob > iframe")!!.attr("src")

        val type=
            if(document.select(".single_tabs>ul>li").first()!!.text().equals("Episodi")){
            TvType.TvSeries
        }
        else{
            TvType.Movie
        }
        if (type == TvType.TvSeries) {
            val episodeList = ArrayList<Episode>()
            document.select("#seasons>.se-c").forEach { element ->
                val season = element.selectFirst(".se-t")!!.text().toInt()
                val href = element.attr("value")
                element.select(".episodios > li").forEach{episodio ->
                    Log.d("JSONLOG","EPISODIO "+episodio.select("a").attr("href"))
                    episodeList.add(Episode(episodio.select("a").attr("href"),episodio.select("a").text(),season))
                }
            }
            if (episodeList.isEmpty()) throw ErrorLoadingException("No Seasons Found")

            return newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodeList
            ) {
                this.posterUrl = fixUrlNull(poster)
                this.year = year
//                this.plot = description[0]
                this.rating = rating
//                this.recommendations = recomm
//                addTrailer(trailerurl)
            }
        } else {
            val url2 = document.selectFirst("iframe")!!.attr("src")
            val actorpagelink =
                document.select("div.content-left-film > p:nth-child(2) > a").attr("href")
            val actorpagelink2 = document.select("div.content-left-film > p > a").attr("href")
            val Linkactor: String = actorpagelink.ifEmpty {
                actorpagelink2
            }

            val actors: List<ActorData>? = if (Linkactor.isNotEmpty()) {
                val actorpage = app.get(Linkactor + "cast/").document
                actorpage.select("article.membro-cast").filter { it ->
                    it.selectFirst("img")
                        ?.attr("src") != "https://www.filmtv.it/imgbank/DUMMY/no_portrait.jpg"
                }.mapNotNull {
                    val name = it.selectFirst("div.info > h3")!!.text()
                    val image = it.selectFirst("img")?.attr("src")
                    val roleString: String = if (it.selectFirst("h2")?.text() == "Regia") {
                        "Regia"
                    } else {
                        "Attore"
                    }
                    val mainActor = Actor(name, image)
                    ActorData(actor = mainActor, roleString = roleString)
                }
            } else {
                null
            }


//            val duratio: Int? = if (description.size == 2) {
//                description[0].filter { it.isDigit() }.toInt()
//            } else {
//                null
//            }
//            val tags: List<String>? = if (description.size == 2) {
//                mutableListOf(description[0].substringBefore(" "))
//            } else {
//                null
//            }
//            val plot: String = if (description.size == 2) {
//                description[1]
//            } else {
//                description[0]
//            }
            return newMovieLoadResponse(
                title,
                url2,
                type,
                url2
            ) {
                posterUrl = fixUrlNull(poster)
                this.year = year
                this.plot = plot
                this.rating = rating
//                this.recommendations = recomm
                this.tags = tags
//                this.duration = duratio
                this.actors = actors
//                addTrailer(trailerurl)

            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("JSONLOG",data
        )
        val doc = app.get(data).document
        val video = doc.selectFirst("video")!!.attr("src")
        Log.d("JSONLOG",video)
            loadExtractor(video, data, subtitleCallback, callback)
        return true
    }
}
