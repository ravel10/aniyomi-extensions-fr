package eu.kanade.tachiyomi.animeextension.fr.empirestreaming

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.EpisodeDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.MovieInfoDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.SearchResultsDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.SerieEpisodesDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.TravelguardDataBase
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.TravelguardDataDirect
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.TravelguardDataIframe
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.TravelguardSlugDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.dto.VideoDto
import eu.kanade.tachiyomi.animeextension.fr.empirestreaming.extractors.EplayerExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.ParsedAnimeHttpSource
import eu.kanade.tachiyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.lib.voeextractor.VoeExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMap
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class EmpireStreaming : ConfigurableAnimeSource, ParsedAnimeHttpSource() {

    override val name = "EmpireStreaming"

    override val baseUrl = "https://empire-stream.net"

    override val lang = "fr"

    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = GET(baseUrl, headers)

    override fun popularAnimeSelector() = "div.block-forme:has(p:contains(Les plus vus)) div.content-card"

    override fun popularAnimeFromElement(element: Element) = SAnime.create().apply {
        setUrlWithoutDomain(element.selectFirst("a.play")!!.attr("abs:href"))
        thumbnail_url = baseUrl + element.selectFirst("picture img")!!.attr("data-src")
        title = element.selectFirst("h3.line-h-s, p.line-h-s")!!.text()
    }

    override fun popularAnimeNextPageSelector() = null

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = GET(baseUrl, headers)

    override fun latestUpdatesSelector() = "div.block-forme:has(p:contains(Ajout récents)) div.content-card"

    override fun latestUpdatesFromElement(element: Element) = popularAnimeFromElement(element)

    override fun latestUpdatesNextPageSelector() = null

    // =============================== Search ===============================
    override fun searchAnimeFromElement(element: Element) = throw UnsupportedOperationException()
    override fun searchAnimeNextPageSelector() = null
    override fun searchAnimeSelector() = throw UnsupportedOperationException()
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) = throw UnsupportedOperationException()
    override fun searchAnimeParse(response: Response) = throw UnsupportedOperationException()

    private val searchItems by lazy {
        client.newCall(GET("$baseUrl/api/views/contenitem", headers)).execute()
            .let {
                json.decodeFromString<SearchResultsDto>(it.body.string()).items
            }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val entriesPages = searchItems.filter { it.title.contains(query, true) }
            .sortedBy { it.title }
            .chunked(30) // to prevent exploding the user screen with 984948984 results

        val hasNextPage = entriesPages.size > page
        val entries = entriesPages.getOrNull(page - 1)?.map {
            SAnime.create().apply {
                title = it.title
                setUrlWithoutDomain("/${it.urlPath}")
                thumbnail_url = "$baseUrl/images/medias/${it.thumbnailPath}"
            }
        } ?: emptyList()

        return AnimesPage(entries, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(document: Document) = SAnime.create().apply {
        setUrlWithoutDomain(document.location())
        title = document.selectFirst("h3#title_media")!!.text()
        val thumbPath = document.html().substringAfter("backdrop\":\"").substringBefore('"')
        thumbnail_url = "$baseUrl/images/medias/$thumbPath".replace("\\", "")
        genre = document.select("div > button.bc-w.fs-12.ml-1.c-b").eachText().joinToString()
        description = document.selectFirst("div.target-media-desc p.content")!!.text()
        status = SAnime.UNKNOWN
    }

    // ============================== Episodes ==============================
    override fun episodeListSelector() = throw UnsupportedOperationException()

    override fun episodeListParse(response: Response): List<SEpisode> {
        val doc = response.asJsoup()
        val scriptJson = doc.selectFirst("script:containsData(window.empire):containsData(data:)")!!
            .data()
            .substringAfter("data:")
            .substringBefore("\n")
            .substringBeforeLast(",")
        return if (doc.location().contains("serie")) {
            val data = json.decodeFromString<SerieEpisodesDto>(scriptJson)
            data.seasons.values
                .flatMap { it.map(::episodeFromObject) }
                .sortedByDescending { it.episode_number }
        } else {
            val data = json.decodeFromString<MovieInfoDto>(scriptJson)
            SEpisode.create().apply {
                name = data.title
                date_upload = data.date.toDate()
                url = "${data.id}!film!${data.videos.encode()}"
                episode_number = 1F
            }.let(::listOf)
        }
    }

    private fun episodeFromObject(obj: EpisodeDto) = SEpisode.create().apply {
        name = "Saison ${obj.season} Épisode ${obj.episode} : ${obj.title}"
        episode_number = "${obj.season}.${obj.episode.toString().padStart(3, '0')}".toFloatOrNull() ?: 1F
        url = "${obj.id}!serie!${obj.video.encode()}"
        date_upload = obj.date.toDate()
    }

    override fun episodeFromElement(element: Element): SEpisode = throw UnsupportedOperationException()

    // ============================ Video Links =============================
    // val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val hosterSelection = preferences.getStringSet(PREF_HOSTER_SELECTION_KEY, PREF_HOSTER_SELECTION_DEFAULT)!!
        val (idContent, type, videoStr) = episode.url.split("!")
        val slug = client.newCall(
            POST(
                "$baseUrl/api/travelguard/prev",
                body = buildJsonObject {
                    putJsonObject("data") {
                        put("idContent", idContent.toInt())
                        put("typeContent", type)
                        put("ip", "127.0.0.1")
                        put("keyCall", "ermxxe\$1")
                        put("type", "")
                        put("infoIp", "")
                    }
                }.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
            ),
        ).await().parseAs<TravelguardSlugDto>().slug
        val videos = videoStr.split(", ").parallelCatchingFlatMap {
            val (id, lang, hoster) = it.split("|")
            if (hoster !in hosterSelection) return@parallelCatchingFlatMap emptyList()
            val url = client.newCall(
                POST(
                    "$baseUrl/api/travelguard/get_data",
                    headers,
                    body = buildJsonObject {
                        putJsonObject("data") {
                            put("idVideo", id.toInt())
                            put("slug", slug)
                            put("ip", "127.0.0.1")
                            put("info", "")
                        }
                    }.toString().toRequestBody("application/json; charset=utf-8".toMediaType()),
                ),
            ).await().body.string().let {
                when (val data = json.decodeFromString<TravelguardDataBase>(it)) {
                    is TravelguardDataDirect -> data.response
                    is TravelguardDataIframe ->
                        data.response.content
                            .substringAfter("window.location.href = \"")
                            .substringBefore("\"")
                }
            }

            when (hoster) {
                "doodstream" -> DoodExtractor(client).videosFromUrl(url)
                "voe" -> VoeExtractor(client).videosFromUrl(url)
                "Eplayer" -> EplayerExtractor(client).videosFromUrl(url)
                "Eplayer_light" -> PlaylistUtils(client).extractFromHls(url, videoNameGen = { res ->
                    "Eplayer - $res (${lang.uppercase()})"
                })
                else -> emptyList()
            }
        }
        return videos
    }

    override fun videoListParse(response: Response) = throw UnsupportedOperationException()

    override fun List<Video>.sort(): List<Video> {
        val hoster = preferences.getString(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)!!
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(hoster) },
                { it.quality.contains(quality) },
            ),
        ).reversed()
    }

    override fun videoListSelector() = throw UnsupportedOperationException()

    override fun videoFromElement(element: Element) = throw UnsupportedOperationException()

    override fun videoUrlParse(document: Document) = throw UnsupportedOperationException()

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_HOSTER_KEY
            title = PREF_HOSTER_TITLE
            entries = PREF_HOSTER_ENTRIES
            entryValues = PREF_HOSTER_VALUES
            setDefaultValue(PREF_HOSTER_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_ENTRIES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTER_SELECTION_KEY
            title = PREF_HOSTER_SELECTION_TITLE
            entries = PREF_HOSTER_SELECTION_ENTRIES
            entryValues = PREF_HOSTER_SELECTION_VALUES
            setDefaultValue(PREF_HOSTER_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private fun List<VideoDto>.encode() = joinToString { it.encoded }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }

        private const val PREF_HOSTER_KEY = "preferred_hoster_new"
        private const val PREF_HOSTER_TITLE = "Hébergeur standard"
        private const val PREF_HOSTER_DEFAULT = "Voe"
        private val PREF_HOSTER_ENTRIES = arrayOf("Voe", "Dood", "E-Player")
        private val PREF_HOSTER_VALUES = PREF_HOSTER_ENTRIES

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_TITLE = "Qualité préférée" // DeepL
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "800p", "720p", "480p")

        private const val PREF_HOSTER_SELECTION_KEY = "hoster_selection_new"
        private const val PREF_HOSTER_SELECTION_TITLE = "Sélectionnez l'hôte"
        private val PREF_HOSTER_SELECTION_ENTRIES = arrayOf("Voe", "Dood", "Eplayer", "Eplayer Light")
        private val PREF_HOSTER_SELECTION_VALUES = arrayOf("voe", "doodstream", "Eplayer", "Eplayer_light")
        private val PREF_HOSTER_SELECTION_DEFAULT by lazy { PREF_HOSTER_SELECTION_VALUES.toSet() }
    }
}
