package eu.kanade.tachiyomi.extension.all.netraragi

import android.content.SharedPreferences
import android.net.Uri
import android.text.InputType
import android.util.Base64
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.CacheControl
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import rx.Single
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import kotlin.math.max

open class NETraragi(private val suffix: String = "") : ConfigurableSource, UnmeteredSource, HttpSource() {
    override val baseUrl by lazy { getPrefBaseUrl() }

    override val lang = "all"

    override val name by lazy { "NetRaragi (${getPrefCustomLabel()})" }

    override val supportsLatest = true

    private val apiKey by lazy { getPrefAPIKey() }

    private val latestNamespacePref by lazy { getPrefLatestNS() }

    private val json by lazy { Injekt.get<Json>() }

    private var randomArchiveID: String = ""

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val id = getReaderId(manga.url)
        val uri = getApiUriBuilder("/gallery/$id/metadata").build()

        return client.newCall(GET(uri.toString(), headers))
            .asObservableSuccess()
            .map { mangaDetailsParse(it).apply { initialized = true } }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        // Catch-all that includes random's ID via thumbnail
        val id = getThumbnailId(manga.thumbnail_url!!)

        return GET("$baseUrl/gallery/$id", headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val gallery = json.decodeFromString<Gallery>(response.body.string())

        return archiveToSManga(gallery)
    }

    override fun getMangaUrl(manga: SManga): String {
        val namespace = preferences.getString(URL_TAG_PREFIX_KEY, URL_TAG_PREFIX_DEFAULT)

        if (namespace.isNullOrEmpty()) {
            return super.getMangaUrl(manga)
        }

        val tag = manga.genre?.split(", ")?.find { it.startsWith("$namespace:") }
        return tag?.substringAfter("$namespace:") ?: super.getMangaUrl(manga)
    }

    override fun chapterListRequest(manga: SManga): Request {
        val id = getReaderId(manga.url)
        val uri = getApiUriBuilder("/gallery/$id/metadata").build()

        return GET(uri.toString(), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val gallery = json.decodeFromString<Gallery>(response.body.string())
        val uri = getApiUriBuilder("/gallery/${gallery.id}/files")
        val prefClearNew = preferences.getBoolean(NEW_ONLY_KEY, NEW_ONLY_DEFAULT)

        return listOf(
            SChapter.create().apply {
                val uriBuild = uri.build()

                url = uriBuild.toString()
                chapter_number = 1F
                name = "Chapter"

                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
                val dateAdded = parser.parse(gallery.dateAdded).time

                dateAdded.let {
                    date_upload = it
                }
            },
        )
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return GET(chapter.url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val archivePage = json.decodeFromString<ArchivePage>(response.body.string())

        return archivePage.pages.mapIndexed { index, url ->
            var newUrl = url
            val subPath = URL(baseUrl).path
            if (!subPath.isNullOrEmpty()) {
                newUrl = newUrl.replaceFirst(subPath, "")
            }
            val uri = Uri.parse("$baseUrl/${newUrl.trimStart('.')}")
            Page(index, uri.toString(), uri.toString(), uri)
        }
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException("imageUrlParse is unused")

    override fun popularMangaRequest(page: Int): Request {
        return searchMangaRequest(page, "", FilterList())
    }

    override fun popularMangaParse(response: Response): MangasPage {
        return searchMangaParse(response)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        val filters = mutableListOf<Filter<*>>()
        val prefNewOnly = preferences.getBoolean(NEW_ONLY_KEY, NEW_ONLY_DEFAULT)

        if (latestNamespacePref.isNotBlank()) {
            filters.add(DescendingOrder(true))
        }

        return searchMangaRequest(page, "", FilterList(filters))
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return searchMangaParse(response)
    }

    private var lastResultCount: Int = 100
    private var lastRecordsFiltered: Int = 0
    private var maxResultCount: Int = 0
    private var totalRecords: Int = 0

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val uri = getApiUriBuilder("/gallery")
        var startPageOffset = 1

        filters.forEach { filter ->
            when (filter) {
                is StartingPage -> {
                    startPageOffset = filter.state.toIntOrNull() ?: 1
                }
                is DescendingOrder -> if (filter.state) uri.appendQueryParameter("sort", "DateDescending")
                else -> {}
            }
        }

        uri.appendQueryParameter("page", ((page - 1 + startPageOffset)).toString())

        if (query.isNotEmpty()) {
            uri.appendQueryParameter("searchTerm", query)
        }

        return GET(uri.toString(), headers, CacheControl.FORCE_NETWORK)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val jsonResult = json.decodeFromString<ArchiveSearchResult>(response.body.string())
        val archives = arrayListOf<SManga>()

        lastResultCount = jsonResult.galleryItems.size
        maxResultCount = max(lastResultCount, maxResultCount)
        lastRecordsFiltered = jsonResult.galleryItems.size
        totalRecords = jsonResult.count

        jsonResult.galleryItems.map {
            archives.add(archiveToSManga(it))
        }

        return MangasPage(archives, lastResultCount == 25)
    }

    private fun archiveToSManga(gallery: Gallery) = SManga.create().apply {
        url = "/gallery/${gallery.id}"
        title = gallery.title
        description = gallery.title
        thumbnail_url = getThumbnailUri(gallery.thumbnailPath)
        genre = gallery.tags?.joinToString(", ")
        artist = getArtist(genre)
        author = artist
        status = SManga.COMPLETED
    }

    override fun headersBuilder() = Headers.Builder().apply {
        if (apiKey.isNotEmpty()) {
            val apiKey64 = Base64.encodeToString(apiKey.toByteArray(), Base64.NO_WRAP)
            add("Authorization", "Bearer $apiKey64")
        }
    }

    private class DescendingOrder(overrideState: Boolean = false) : Filter.CheckBox("Ascending Order", overrideState)
    private class StartingPage(stats: String) : Filter.Text("Starting Page$stats", "")

    override fun getFilterList() = FilterList(
        Filter.Separator(),
        DescendingOrder(),
        StartingPage(startingPageStats()),
    )

    private var categories = emptyList<Category>()

    // Preferences
    override val id by lazy {
        // Retain previous ID for first entry
        val key = "netraragi" + (if (suffix == "1") "" else "_$suffix") + "/all/$versionId"
        val bytes = MessageDigest.getInstance("MD5").digest(key.toByteArray())
        (0..7).map { bytes[it].toLong() and 0xff shl 8 * (7 - it) }.reduce(Long::or) and Long.MAX_VALUE
    }

    internal val preferences: SharedPreferences by getPreferencesLazy()

    private fun getPrefBaseUrl(): String = preferences.getString(HOSTNAME_KEY, HOSTNAME_DEFAULT)!!
    private fun getPrefAPIKey(): String = preferences.getString(APIKEY_KEY, "")!!
    private fun getPrefLatestNS(): String = preferences.getString(SORT_BY_NS_KEY, SORT_BY_NS_DEFAULT)!!
    private fun getPrefCustomLabel(): String = preferences.getString(CUSTOM_LABEL_KEY, suffix)!!.ifBlank { suffix }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        if (suffix == "1") {
            ListPreference(screen.context).apply {
                key = EXTRA_SOURCES_COUNT_KEY
                title = "Number of extra sources"
                summary = "Number of additional sources to create. There will always be at least one NETraragi source."
                entries = EXTRA_SOURCES_ENTRIES
                entryValues = EXTRA_SOURCES_ENTRIES

                setDefaultValue(EXTRA_SOURCES_COUNT_DEFAULT)
                setOnPreferenceChangeListener { _, newValue ->
                    try {
                        val setting = preferences.edit().putString(EXTRA_SOURCES_COUNT_KEY, newValue as String).commit()
                        Toast.makeText(screen.context, "Restart Tachiyomi to apply new setting.", Toast.LENGTH_LONG).show()
                        setting
                    } catch (e: Exception) {
                        e.printStackTrace()
                        false
                    }
                }
            }.also(screen::addPreference)
        }
        screen.addPreference(screen.editTextPreference(HOSTNAME_KEY, "Hostname", HOSTNAME_DEFAULT, baseUrl, refreshSummary = true))
        screen.addPreference(screen.editTextPreference(APIKEY_KEY, "API Key", "", "Required if No-Fun Mode is enabled.", true))
        screen.addPreference(screen.editTextPreference(CUSTOM_LABEL_KEY, "Custom Label", "", "Show the given label for the source instead of the default."))
        screen.addPreference(screen.checkBoxPreference(CLEAR_NEW_KEY, "Clear New status", CLEAR_NEW_DEFAULT, "Clear an entry's New status when its details are viewed."))
        screen.addPreference(screen.checkBoxPreference(NEW_ONLY_KEY, "Latest - New Only", NEW_ONLY_DEFAULT))
        screen.addPreference(screen.editTextPreference(SORT_BY_NS_KEY, "Latest - Sort by Namespace", SORT_BY_NS_DEFAULT, "Sort by the given namespace for Latest, such as date_added."))
        screen.addPreference(screen.editTextPreference(URL_TAG_PREFIX_KEY, "Set tag prefix to get WebView URL", URL_TAG_PREFIX_DEFAULT, "Example: 'source:' will try to get the URL from the first tag starting with 'source:' and it will open it in the WebView. Leave empty for the default behavior."))
    }

    private fun PreferenceScreen.checkBoxPreference(key: String, title: String, default: Boolean, summary: String = ""): CheckBoxPreference {
        return CheckBoxPreference(context).apply {
            this.key = key
            this.title = title
            this.summary = summary
            setDefaultValue(default)

            setOnPreferenceChangeListener { _, newValue ->
                preferences.edit().putBoolean(this.key, newValue as Boolean).commit()
            }
        }
    }

    private fun PreferenceScreen.editTextPreference(key: String, title: String, default: String, summary: String, isPassword: Boolean = false, refreshSummary: Boolean = false): EditTextPreference {
        return EditTextPreference(context).apply {
            this.key = key
            this.title = title
            this.summary = summary
            this.setDefaultValue(default)

            if (isPassword) {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val newString = newValue.toString()
                    val res = preferences.edit().putString(this.key, newString).commit()

                    if (refreshSummary) {
                        this.apply {
                            this.summary = newValue as String
                        }
                    }

                    Toast.makeText(context, "Restart Tachiyomi to apply new setting.", Toast.LENGTH_LONG).show()
                    res
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }
    }

    private fun getCategories() {
        Single.fromCallable {
            client.newCall(GET("$baseUrl/categories", headers)).execute()
        }
            .subscribeOn(Schedulers.io())
            .observeOn(Schedulers.io())
            .subscribe(
                {
                    categories = try {
                        json.decodeFromString(it.body.string())
                    } catch (e: Exception) {
                        emptyList()
                    }
                },
                {},
            )
    }

    private fun startingPageStats(): String {
        return if (maxResultCount > 0 && totalRecords > 0) " ($maxResultCount / $lastRecordsFiltered items)" else ""
    }

    private fun getApiUriBuilder(path: String): Uri.Builder {
        return Uri.parse("$baseUrl$path").buildUpon()
    }

    private fun getThumbnailUri(id: String): String {
        val uri = getApiUriBuilder("/thumbnail/$id")

        return uri.toString()
    }

    private tailrec fun getTopResponse(response: Response): Response {
        return if (response.priorResponse == null) response else getTopResponse(response.priorResponse!!)
    }

    private fun getReaderId(url: String): String {
        return url.split("/").last()
    }

    private fun getThumbnailId(url: String): String {
        return Regex("""/thumbnail/(\w{40})""").find(url)?.groupValues?.get(1) ?: ""
    }

    private fun getNSTag(tags: String?, tag: String): List<String>? {
        tags?.split(',')?.forEach {
            if (it.contains(':')) {
                val temp = it.trim().split(":", limit = 2)
                if (temp[0].equals(tag, true)) return temp
            }
        }

        return null
    }

    private fun getArtist(tags: String?): String = getNSTag(tags, "artist")?.get(1) ?: "N/A"

    private fun getDateAdded(tags: String?): String {
        // Pad Date Added NS to milliseconds
        return getNSTag(tags, "date_added")?.get(1)?.padEnd(13, '0') ?: ""
    }

    // Headers (currently auth) are done in headersBuilder
    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .dns(Dns.SYSTEM)
        .addInterceptor { chain ->
            val response = chain.proceed(chain.request())
            if (response.code == 401) throw IOException("If the server is in No-Fun Mode make sure the extension's API Key is correct.")
            response
        }
        .build()

    init {
        if (baseUrl.isNotBlank()) {
            // Save a FilterList reset
            // getCategories()
        }
    }

    companion object {
        internal const val EXTRA_SOURCES_COUNT_KEY = "extraSourcesCount"
        internal const val EXTRA_SOURCES_COUNT_DEFAULT = "2"
        private val EXTRA_SOURCES_ENTRIES = (0..10).map { it.toString() }.toTypedArray()

        private const val HOSTNAME_DEFAULT = "http://127.0.0.1:3000"
        private const val HOSTNAME_KEY = "hostname"
        private const val APIKEY_KEY = "apiKey"
        private const val CUSTOM_LABEL_KEY = "customLabel"
        private const val NEW_ONLY_DEFAULT = true
        private const val NEW_ONLY_KEY = "latestNewOnly"
        private const val SORT_BY_NS_DEFAULT = "date_added"
        private const val SORT_BY_NS_KEY = "latestNamespacePref"
        private const val CLEAR_NEW_KEY = "clearNew"
        private const val CLEAR_NEW_DEFAULT = true
        private const val URL_TAG_PREFIX_KEY = "urlTagPrefix"
        private const val URL_TAG_PREFIX_DEFAULT = ""
    }
}
