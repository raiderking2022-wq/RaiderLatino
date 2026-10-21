package com.raiderlatino.plugin

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addRecommendations
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPage
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.metaproviders.TmdbProvider
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class RaiderLatinoPlugin : MainAPI() {
    override var mainUrl = "https://www.cinecalidad.my"
    override var name = "Raider-Latino"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "es"
    override val hasMainPage = true
    override val hasQuickSearch = false

    // The site source is intentionally hidden from the user-facing provider name.
    private val SITE_URL = "https://www.cinecalidad.my"
    private val TAG = "RaiderLatino"

    // ---- Home page categories ----
    override val mainPage = mainPageOf(
        "page/1/?s=&trfilter=1" to "Estrenos",
        "category/peliculas-de-accion/page/1/" to "Acción",
        "category/peliculas-de-comedia/page/1/" to "Comedia",
        "category/peliculas-de-drama/page/1/" to "Drama",
        "category/peliculas-de-terror/page/1/" to "Terror",
        "category/peliculas-de-aventura/page/1/" to "Aventura",
        "category/peliculas-de-ciencia-ficcion/page/1/" to "Ciencia Ficción",
        "category/peliculas-de-animacion/page/1/" to "Animación",
        "category/peliculas-de-suspense/page/1/" to "Suspense",
        "category/peliculas-de-romance/page/1/" to "Romance",
        "category/peliculas-de-fantasia/page/1/" to "Fantasía",
        "category/peliculas-familiares/page/1/" to "Familiar",
        "category/peliculas-de-biografia/page/1/" to "Biografía",
        "category/peliculas-de-crimen/page/1/" to "Crimen",
        "category/series/page/1/" to "Series",
        "page/1/?s=&trfilter=2" to "Estreno HD",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionUrl = if (page <= 1) {
            "$SITE_URL/${request.data}"
        } else {
            request.data.replace("page/1/", "page/$page/")
        }
        val res = app.get(sectionUrl)
        if (!res.isSuccessful) {
            Log.w(TAG, "getMainPage failed ${res.code} for $sectionUrl")
            return HomePageResponse(emptyList())
        }
        val items = parseMovieList(res.text)
        return newHomePageResponse(request.name, items, page < 6)
    }

    // ---- Search ----
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$SITE_URL/?s=${query.replace(" ", "+")}"
        val res = app.get(searchUrl)
        if (!res.isSuccessful) return emptyList()
        return parseMovieList(res.text)
    }

    // ---- Details ----
    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url)
        if (!res.isSuccessful) return null
        val doc = Jsoup.parse(res.text)

        val title = doc.selectFirst("h1.entry-title, h1[itemprop=name], .sheader h1")?.text()
            ?: return null

        val poster = doc.selectFirst("img[itemprop=image], .poster img, .snoor img")?.absUrl("src")
        val year = doc.selectFirst("span.date, .extra-tags .date, [itemprop=dateCreated]")
            ?.text()?.substringAfter(",")?.trim()?.toIntOrNull()
        val description = doc.selectFirst("div[itemprop=description], .sinopsis, .sino, p[itemprop=description]")
            ?.text()?.trim()

        val tags = doc.select("a[rel=tag], .genres a, .genres-list a").map { it.text().trim() }.distinct()
        val actors = doc.select("[itemprop=actor] a, .cast a").map { it.text().trim() }.filter { it.isNotBlank() }

        val trailer = doc.selectFirst("iframe[src*=youtube], .trailer iframe")?.absUrl("src")

        val recommendations = doc.select(".related .item, .relacionados .post")
            .mapNotNull { parsePostToSearch(it) }

        val data = url // we use the movie page url as loadable data
        val movie = newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
        }
        movie.addActors(actors)
        if (trailer != null) movie.addTrailer(trailer)
        movie.addRecommendations(recommendations)
        return movie
    }

    // ---- Links ----
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pageRes = app.get(data)
        if (!pageRes.isSuccessful) return false
        val doc = Jsoup.parse(pageRes.text)

        // Download / streaming buttons link to external providers (e.g. Fembed, MixDrop, etc.)
        val links = doc.select("a[href*='download'], a[href*='tlink'], .links a, .down-btn, .btn-download")
            .map { it.absUrl("href") }
            .filter { it.startsWith("http") }
            .distinct()

        for (link in links) {
            // Pass through to CloudStream's built-in extractor resolution.
            loadExtractor(link, data, subtitleCallback, callback)
        }

        // Subtitles (if present on page, usually .srt / direct vtt links)
        doc.select("a[href\$=.srt], a[href\$=.vtt]").forEach {
            subtitleCallback.invoke(SubtitleFile(it.text().ifBlank { "Latino" }, it.absUrl("href")))
        }

        return links.isNotEmpty()
    }

    // ---- Helpers ----
    private fun parseMovieList(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html)
        val posts = doc.select("article.post, .item, .movie, .pelicula, .entry")
        return posts.mapNotNull { parsePostToSearch(it) }
    }

    private fun parsePostToSearch(el: Element): SearchResponse? {
        val a = el.selectFirst("a[href]") ?: return null
        val href = a.absUrl("href").ifBlank { return null }
        val title = el.selectFirst("h2, h3, .title, .entry-title")?.text()
            ?: a.attr("title").ifBlank { a.text() }.ifBlank { return null }
        val poster = el.selectFirst("img")?.absUrl("src")
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}
