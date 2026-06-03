package eu.kanade.tachiyomi.extension.ar.neverscans

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class NeverScans : HttpSource() {

    override val name = "Never Scans"
    override val baseUrl = "https://never-by-garo.lovable.app"
    override val lang = "ar"
    override val supportsLatest = true

    private val supabaseUrl = "https://idxqgixfckisrcefcvsp.supabase.co"
    private val storageUrl = "$supabaseUrl/storage/v1/object/public/chapters"
    private val supabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImlkeHFnaXhmY2tpc3JjZWZjdnNwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzY4MDQwOTksImV4cCI6MjA5MjM4MDA5OX0.Mospaymp0inRNpWySQCQsn6cfdkagH3lAM-LFkigqNc"

    private val json: Json by injectLazy()

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("apikey", supabaseAnonKey)
        .add("Authorization", "Bearer $supabaseAnonKey")
        .add("Content-Type", "application/json")

    override fun getMangaUrl(manga: SManga): String {
        val slug = manga.url.removePrefix("/manga/")
        return "$baseUrl/manga/$slug"
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val parts = chapter.url.split("/")
        val slug = parts.getOrNull(2) ?: ""
        val number = parts.getOrNull(3) ?: ""
        return "$baseUrl/manga/$slug/$number"
    }

    override fun popularMangaRequest(page: Int): Request {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$supabaseUrl/rest/v1/manga?select=id,slug,title,cover_url,status,type,views&order=views.desc.nullslast&limit=$PAGE_SIZE&offset=$offset"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val array = json.parseToJsonElement(response.body.string()).jsonArray
        val mangas = array.map { it.jsonObject.toSManga() }
        return MangasPage(mangas, mangas.size == PAGE_SIZE)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$supabaseUrl/rest/v1/manga?select=id,slug,title,cover_url,status,type,updated_at&order=updated_at.desc.nullslast&limit=$PAGE_SIZE&offset=$offset"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage = popularMangaParse(response)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val offset = (page - 1) * PAGE_SIZE
        val url = "$supabaseUrl/rest/v1/manga?select=id,slug,title,cover_url,status,type&title=ilike.*${query.trim()}*&order=title.asc&limit=$PAGE_SIZE&offset=$offset"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/manga/")
        val url = "$supabaseUrl/rest/v1/manga?select=id,slug,title,cover_url,status,type,description,genre,author&slug=eq.$slug&limit=1"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val array = json.parseToJsonElement(response.body.string()).jsonArray
        if (array.isEmpty()) throw Exception("لم يتم العثور على المانغا")
        return array[0].jsonObject.toSMangaDetails()
    }

    override fun chapterListRequest(manga: SManga): Request {
        val slug = manga.url.removePrefix("/manga/")
        val url = "$supabaseUrl/rest/v1/manga?select=id,slug&slug=eq.$slug&limit=1"
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val array = json.parseToJsonElement(response.body.string()).jsonArray
        if (array.isEmpty()) return emptyList()
        val obj = array[0].jsonObject
        val mangaId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val slug = obj["slug"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val chaptersUrl = "$supabaseUrl/rest/v1/chapters?select=id,number,title,created_at,manga_id&manga_id=eq.$mangaId&published=eq.true&order=number.desc"
        val chaptersResp = client.newCall(GET(chaptersUrl, headers)).execute()
        val chaptersArray = json.parseToJsonElement(chaptersResp.body.string()).jsonArray
        return chaptersArray.map { it.jsonObject.toSChapter(slug) }
    }

    // pageListRequest gets chapter info including manga_id and page count
    override fun pageListRequest(chapter: SChapter): Request {
        val chapterId = chapter.url.substringAfterLast("/cid/")
        val url = "$supabaseUrl/rest/v1/chapters?select=id,manga_id&id=eq.$chapterId&limit=1"
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val array = json.parseToJsonElement(response.body.string()).jsonArray
        if (array.isEmpty()) return emptyList()
        val obj = array[0].jsonObject
        val chapterId = obj["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        val mangaId = obj["manga_id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()

        // Fetch page count from pages table
        val pagesUrl = "$supabaseUrl/rest/v1/pages?select=index&chapter_id=eq.$chapterId&order=index.asc"
        val pagesResp = client.newCall(GET(pagesUrl, headers)).execute()
        val pagesArray = json.parseToJsonElement(pagesResp.body.string()).jsonArray

        // Build image URLs using storage path: chapters/{manga_id}/{chapter_id}/{0001}.webp
        return pagesArray.mapIndexed { index, el ->
            val pageIndex = el.jsonObject["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: index
            val pageNumber = "%04d".format(pageIndex + 1)
            val imageUrl = "$storageUrl/$mangaId/$chapterId/$pageNumber.webp"
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, Headers.Builder().build())
    }

    private fun kotlinx.serialization.json.JsonObject.toSManga() = SManga.create().apply {
        val slug = this@toSManga["slug"]?.jsonPrimitive?.contentOrNull ?: ""
        url = "/manga/$slug"
        title = this@toSManga["title"]?.jsonPrimitive?.contentOrNull ?: "?"
        thumbnail_url = this@toSManga["cover_url"]?.jsonPrimitive?.contentOrNull
        status = when (this@toSManga["status"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            "ongoing", "مستمر" -> SManga.ONGOING
            "completed", "مكتمل" -> SManga.COMPLETED
            "hiatus", "متوقف" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun kotlinx.serialization.json.JsonObject.toSMangaDetails() = toSManga().apply {
        description = this@toSMangaDetails["description"]?.jsonPrimitive?.contentOrNull
        genre = this@toSMangaDetails["genre"]?.jsonPrimitive?.contentOrNull
        author = this@toSMangaDetails["author"]?.jsonPrimitive?.contentOrNull
    }

    private fun kotlinx.serialization.json.JsonObject.toSChapter(mangaSlug: String) = SChapter.create().apply {
        val chapterId = this@toSChapter["id"]?.jsonPrimitive?.contentOrNull ?: ""
        val number = this@toSChapter["number"]?.jsonPrimitive?.contentOrNull ?: "0"
        val extraTitle = this@toSChapter["title"]?.jsonPrimitive?.contentOrNull
        url = "/manga/$mangaSlug/$number/cid/$chapterId"
        name = if (!extraTitle.isNullOrBlank()) "الفصل $number - $extraTitle" else "الفصل $number"
        chapter_number = number.toFloatOrNull() ?: -1f
        date_upload = this@toSChapter["created_at"]?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { dateFormat.parse(it.take(19))?.time }.getOrNull() } ?: 0L
    }

    companion object {
        private const val PAGE_SIZE = 24
    }
}
