package com.lagradost

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element

class StarLiveProvider : MainAPI() {
    override var mainUrl = "https://starlive.xyz"
    override var name = "StarLive"
    override val hasMainPage = true
    override var lang = "it"
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live)
    private val interceptor = CloudflareKiller()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, interceptor = interceptor).document
        val sections = document.select("div.panel").groupBy { it.selectFirst("h4 b")?.text() }.values
        if (sections.isEmpty()) throw ErrorLoadingException()
        val prova = sections.map {elements ->
            val home = elements.mapNotNull { it.toMainPageResult() }
            HomePageList(elements.first()?.selectFirst("h4 b")?.text()?:"Altro", home)
        }
        return HomePageResponse(prova)
    }

    private fun Element.toMainPageResult() : LiveSearchResponse {
        val name = this.selectFirst("b")?.text()?:"Altro"
        val links = this.select("tr")
            .toList()
            .filter { it.hasAttr("class") && it.attr("class") !in listOf("", "audio") }
            .map { LinkParser(
                fixUrl(it.selectFirst("a")?.attr("href")?:""),
                it.attr("class"),
                it.selectFirst("span")?.text()?:""
            ) }
        val dayMatch = this.previousElementSiblings().toList().firstOrNull() { it.`is`("h3") }?.text()

        val matchData = MatchDataParser(
            dayMatch?.plus(" - ".plus(this.selectFirst("#evento")?.text()?.substringBefore(" ")))?:"no data",
            fixUrl(
                this.selectFirst("h4")?.attr("style")
                    ?.substringAfter("(")?.substringBefore(")") ?: ""
            ),
            this.selectFirst("#evento")?.text()?.substringAfter(" ")?:"Match in $name || $dayMatch"
        )
        val href = MatchParser(links, matchData)
        return LiveSearchResponse(
            this.selectFirst("#evento")?.text()?.substringAfter(" ")?:"Match in $name",
            href.toJson(),
            this@StarLiveProvider.name,
            TvType.Live,
            fixUrl(
                this.selectFirst("h4")?.attr("style")
                    ?.substringAfter("(")?.substringBefore(")") ?: ""
            ),
            posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val matchdata = tryParseJson<MatchParser>(url)
        val poster = matchdata?.MatchData?.poster
        val matchstart = matchdata?.MatchData?.time
        return LiveStreamLoadResponse(
            dataUrl = url,
            url = matchdata?.linkData?.firstOrNull()?.link ?: mainUrl,
            name = matchdata?.MatchData?.name ?: "No name",
            posterUrl = poster,
            plot = matchstart,
            apiName = this@StarLiveProvider.name,
            posterHeaders = interceptor.getCookieHeaders(mainUrl).toMap()
        )
    }

    private suspend fun extractVideoLinks(
        data: LinkParser,
        callback: (ExtractorLink) -> Unit
    ) {
        val linktoStream = fixUrl(app.get(data.link, interceptor = interceptor).document.selectFirst("iframe")!!.attr("src"))
        val referrerLink = if (linktoStream.contains("starlive")) {
            app.get(linktoStream, referer = data.link, interceptor = interceptor).document.selectFirst("iframe")?.attr("src")
                ?: linktoStream
        } else {
            linktoStream
        }
        val packed = when (linktoStream.contains("starlive")) {
            true -> app.get(
                referrerLink,
                referer = linktoStream
            ).document.select("script").toString()
            false -> app.get(linktoStream, referer = data.link).document.select("script")
                .select("script").toString()
        }
        var streamUrl = getAndUnpack(packed).substringAfter("var src=\"").substringBefore("\"")
        if (streamUrl.contains("allowedDomains")){streamUrl = packed.substringAfter("source:'").substringBefore("'")}
        if (!streamUrl.contains("m3u8")){
            val script = app.get(linktoStream, referer = data.link, interceptor = interceptor).document.selectFirst("body")?.selectFirst("script").toString()
            streamUrl = Regex("source: [\\\"'](.*?)[\\\"']").find(script)?.groupValues?.last()?:""
        }
        
        callback(
            ExtractorLink(
                source = this.name,
                name = data.name + " - " + data.language,
                url = streamUrl,
                quality = Qualities.Unknown.value,
                referer = referrerLink,
                isM3u8 = true
            )
        )

    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        tryParseJson<MatchParser>(data)?.linkData?.map { link ->
            extractVideoLinks(link, callback)
        }

        return true
    }

    private data class LinkParser(
        @JsonProperty("link") val link: String,
        @JsonProperty("lang") val language: String,
        @JsonProperty("name") val name: String
    )

    private data class MatchDataParser(
        @JsonProperty("time") val time: String,
        @JsonProperty("poster") val poster: String,
        @JsonProperty("name") val name: String
    )

    private data class MatchParser(
        @JsonProperty("linkData") val linkData: List<LinkParser>,
        @JsonProperty("matchData") val MatchData: MatchDataParser
    )

}