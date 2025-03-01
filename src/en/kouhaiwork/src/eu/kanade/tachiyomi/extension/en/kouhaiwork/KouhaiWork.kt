package eu.kanade.tachiyomi.extension.en.kouhaiwork

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class KouhaiWork : HttpSource() {
    override val name = "Kouhai Work"

    override val baseUrl = "https://kouhai.work"

    override val lang = "en"

    override val supportsLatest = true

    override val id = 1273675602267580928L

    private val json by lazy {
        Json(Injekt.get()) { isLenient = true }
    }

    override fun latestUpdatesRequest(page: Int) =
        GET("$API_URL/manga/recent", headers)

    override fun latestUpdatesParse(response: Response) =
        response.decode<List<KouhaiSeries>>().map {
            SManga.create().apply {
                url = it.url
                title = it.toString()
                thumbnail_url = it.thumbnail
            }
        }.let { MangasPage(it, false) }

    override fun popularMangaRequest(page: Int) =
        GET("$API_URL/manga/all", headers)

    override fun popularMangaParse(response: Response) =
        latestUpdatesParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList) =
        FormBody.Builder().add("search", query).add("tags", filters.json())
            .let { POST("$API_URL/search/manga", headers, it.build()) }

    override fun searchMangaParse(response: Response) =
        response.decode<List<KouhaiSearch>>().map {
            SManga.create().apply {
                url = it.url
                title = it.title
                thumbnail_url = it.thumbnail
            }
        }.let { MangasPage(it, false) }

    // Request the actual manga URL for the webview
    override fun mangaDetailsRequest(manga: SManga) =
        GET("$baseUrl/series/${manga.url}", headers)

    override fun fetchMangaDetails(manga: SManga) =
        client.newCall(chapterListRequest(manga)).asObservableSuccess().map {
            val series = it.decode<KouhaiSeriesDetails>()
            manga.description = series.toString()
            manga.author = series.authors?.joinToString()
            manga.artist = series.artists?.joinToString()
            manga.genre = series.tags.joinToString()
            manga.status = when (series.status) {
                "ongoing" -> SManga.ONGOING
                "finished" -> SManga.COMPLETED
                else -> SManga.UNKNOWN
            }
            manga.initialized = true
            return@map manga
        }!!

    override fun chapterListRequest(manga: SManga) =
        GET("$API_URL/manga/get/${manga.url}", headers)

    override fun chapterListParse(response: Response) =
        response.decode<KouhaiSeriesDetails>().chapters.map {
            SChapter.create().apply {
                url = it.url
                name = it.toString()
                chapter_number = it.number
                date_upload = it.timestamp
                scanlator = it.groups.joinToString()
            }
        }.reversed()

    override fun pageListRequest(chapter: SChapter) =
        GET("$API_URL/chapters/get/${chapter.url}", headers)

    override fun pageListParse(response: Response) =
        response.decode<KouhaiPages>("chapter")
            .mapIndexed { idx, img -> Page(idx, "", img.toString()) }

    override fun getFilterList() = FilterList(
        GenresFilter(),
        ThemesFilter(),
        DemographicsFilter(),
        StatusFilter()
    )

    override fun mangaDetailsParse(response: Response) =
        throw UnsupportedOperationException("Not used")

    override fun imageUrlParse(response: Response) =
        throw UnsupportedOperationException("Not used")

    private fun FilterList.json() = json.encodeToJsonElement(
        KouhaiTagList(
            find<GenresFilter>()?.state?.filter { it.state }?.map {
                KouhaiTag(it.id, it.name)
            } ?: emptyList(),
            find<ThemesFilter>()?.state?.filter { it.state }?.map {
                KouhaiTag(it.id, it.name)
            } ?: emptyList(),
            find<DemographicsFilter>()?.takeIf { it.state != 0 }?.let {
                listOf(KouhaiTag(it.state, it.values[it.state]))
            } ?: emptyList(),
            find<StatusFilter>()?.takeIf { it.state != 0 }?.let {
                KouhaiTag(it.state - 1, it.values[it.state])
            }
        )
    ).toString()

    private inline fun <reified T> Response.decode(key: String = "data") =
        json.decodeFromJsonElement<T>(
            json.parseToJsonElement(body!!.string()).jsonObject[key]!!
        )
}
