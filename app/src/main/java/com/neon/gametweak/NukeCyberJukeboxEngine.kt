package com.neon.gametweak

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * NukeCyberJukeboxEngine — In-Game Music Streaming Core.
 *
 * Powered by the iTunes Search API (Apple's free, public, no-auth API) for
 * real-time music discovery + Android's native MediaPlayer for zero-dependency
 * audio streaming.
 *
 *  - 0 API key required — iTunes API is fully public.
 *  - 0 rate-limit issues — iTunes API is highly permissive for mobile apps.
 *  - 0 WebView dependency — MediaPlayer handles all audio streaming.
 *  - Background playback while gaming across Android 11–17.
 *  - Built-in curated gaming presets (Phonk, Lo-Fi, Trap, NCS, Anime).
 */
class NukeCyberJukeboxEngine private constructor(private val context: Context) {

    data class Track(
        val id: String,
        val title: String,
        val artist: String,
        val duration: String = "",
        val thumbnailUrl: String = "",
        val previewUrl: String = "",   // Direct MP3 stream URL from iTunes
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Track>>(emptyList())
    val searchResults: StateFlow<List<Track>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _volumePercent = MutableStateFlow(85)
    val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    private var mediaPlayer: MediaPlayer? = null

    companion object {
        private const val TAG = "NukeCyberJukebox"

        /**
         * Curated gaming presets — these are iTunes track IDs that reliably have
         * preview URLs. Actual playback uses the runtime-fetched previewUrl.
         */
        val PRESET_TRACKS = listOf(
            Track(
                id = "phonk_drift",
                title = "Phonk Drift Compilation",
                artist = "Gaming Phonk",
                duration = "0:30",
                previewUrl = ""  // resolved at runtime via search
            ),
            Track(
                id = "lofi_chill",
                title = "Lo-Fi Beats to Relax / Game",
                artist = "Chillhop Music",
                duration = "0:30",
                previewUrl = ""
            ),
            Track(
                id = "ncs_legends",
                title = "Legends Never Die (NCS Gaming Mix)",
                artist = "NCS / League of Legends",
                duration = "0:30",
                previewUrl = ""
            ),
            Track(
                id = "trap_bass",
                title = "Heavy Trap Bass Mix",
                artist = "Trap City",
                duration = "0:30",
                previewUrl = ""
            ),
            Track(
                id = "anime_hype",
                title = "Gurenge / LiSA (Anime Battle Mix)",
                artist = "LiSA",
                duration = "0:30",
                previewUrl = ""
            ),
            Track(
                id = "alan_walker",
                title = "Faded",
                artist = "Alan Walker",
                duration = "0:30",
                previewUrl = ""
            ),
            Track(
                id = "neffex_fight",
                title = "Fight Back",
                artist = "NEFFEX",
                duration = "0:30",
                previewUrl = ""
            ),
        )

        // Map preset IDs to search queries for iTunes API
        private val PRESET_QUERIES = mapOf(
            "phonk_drift" to "phonk drift gaming",
            "lofi_chill" to "lofi chill beats relax",
            "ncs_legends" to "Legends Never Die League Legends",
            "trap_bass" to "trap bass gaming mix",
            "anime_hype" to "Gurenge LiSA",
            "alan_walker" to "Faded Alan Walker",
            "neffex_fight" to "Fight Back NEFFEX",
        )

        @Volatile
        private var instance: NukeCyberJukeboxEngine? = null

        fun getInstance(context: Context): NukeCyberJukeboxEngine {
            return instance ?: synchronized(this) {
                instance ?: NukeCyberJukeboxEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    init {
        _currentTrack.value = PRESET_TRACKS.first()
    }

    // ─── Player Controls ───────────────────────────────────────────────────

    fun playTrack(track: Track) {
        _currentTrack.value = track
        if (track.previewUrl.isNotBlank()) {
            startStream(track.previewUrl)
        } else {
            // Resolve via iTunes search then stream
            scope.launch {
                val query = PRESET_QUERIES[track.id] ?: track.title
                val resolved = searchItunes(query).firstOrNull()
                if (resolved != null) {
                    _currentTrack.value = resolved
                    mainHandler.post { startStream(resolved.previewUrl) }
                }
            }
        }
    }

    fun togglePlayPause() {
        val player = mediaPlayer
        if (player != null && player.isPlaying) {
            pause()
        } else if (player != null) {
            try {
                player.start()
                _isPlaying.value = true
            } catch (_: Throwable) {
                val track = _currentTrack.value ?: PRESET_TRACKS.first()
                playTrack(track)
            }
        } else {
            val track = _currentTrack.value ?: PRESET_TRACKS.first()
            playTrack(track)
        }
    }

    fun play() {
        val player = mediaPlayer
        if (player != null && !player.isPlaying) {
            runCatching { player.start(); _isPlaying.value = true }
        } else if (player == null) {
            val track = _currentTrack.value ?: PRESET_TRACKS.first()
            playTrack(track)
        }
    }

    fun pause() {
        runCatching {
            mediaPlayer?.pause()
            _isPlaying.value = false
        }
    }

    fun stop() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.reset()
            _isPlaying.value = false
        }
    }

    fun setVolume(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        _volumePercent.value = clamped
        val vol = clamped / 100f
        runCatching { mediaPlayer?.setVolume(vol, vol) }
    }

    fun playNextPreset() {
        val current = _currentTrack.value
        val idx = PRESET_TRACKS.indexOfFirst { it.id == current?.id }
        val next = if (idx >= 0 && idx < PRESET_TRACKS.size - 1) idx + 1 else 0
        playTrack(PRESET_TRACKS[next])
    }

    fun playPrevPreset() {
        val current = _currentTrack.value
        val idx = PRESET_TRACKS.indexOfFirst { it.id == current?.id }
        val prev = if (idx > 0) idx - 1 else PRESET_TRACKS.size - 1
        playTrack(PRESET_TRACKS[prev])
    }

    // ─── Search (iTunes Search API — free, no key, always active) ─────────

    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _searchResults.value = PRESET_TRACKS
            return
        }
        scope.launch {
            _isSearching.value = true
            val results = searchItunes(trimmed)
            withContext(Dispatchers.Main) {
                _searchResults.value = if (results.isNotEmpty()) results else PRESET_TRACKS
                _isSearching.value = false
            }
        }
    }

    private suspend fun searchItunes(query: String): List<Track> = withContext(Dispatchers.IO) {
        val list = mutableListOf<Track>()
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            // iTunes Search API — Apple's free public endpoint, no auth, no rate limit for typical usage
            val url = URL("https://itunes.apple.com/search?term=$encoded&media=music&limit=15&entity=song")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "GameNuke/3.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            val responseCode = conn.responseCode
            if (responseCode != 200) {
                Log.w(TAG, "iTunes API returned $responseCode")
                return@withContext list
            }
            val json = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val results = root.optJSONArray("results") ?: return@withContext list

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val previewUrl = item.optString("previewUrl", "")
                if (previewUrl.isBlank()) continue  // Skip tracks with no streamable preview

                val trackId = item.optLong("trackId", 0L).toString()
                val title = item.optString("trackName", "Unknown Track")
                val artist = item.optString("artistName", "Unknown Artist")
                val artworkUrl = item.optString("artworkUrl100", "")
                    .replace("100x100bb", "300x300bb")  // Get higher-res art
                val durationMs = item.optLong("trackTimeMillis", 30000L)
                val durationSec = (durationMs / 1000).toInt()
                val duration = "%d:%02d".format(durationSec / 60, durationSec % 60)

                list.add(
                    Track(
                        id = trackId,
                        title = title,
                        artist = artist,
                        duration = duration,
                        thumbnailUrl = artworkUrl,
                        previewUrl = previewUrl,
                    )
                )
                if (list.size >= 12) break
            }
            Log.d(TAG, "iTunes search '$query': ${list.size} results")
        } catch (t: Throwable) {
            Log.w(TAG, "iTunes search failed: ${t.message}")
        }
        list
    }

    // ─── Internal Streaming ────────────────────────────────────────────────

    private fun startStream(previewUrl: String) {
        if (previewUrl.isBlank()) {
            Log.w(TAG, "startStream: no preview URL")
            return
        }
        mainHandler.post {
            try {
                releasePlayer()
                val vol = _volumePercent.value / 100f
                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    setDataSource(previewUrl)
                    setVolume(vol, vol)
                    isLooping = true   // Loop the 30-second preview for continuous gaming music
                    setOnPreparedListener { mp ->
                        mp.start()
                        _isPlaying.value = true
                        Log.d(TAG, "Streaming started: $previewUrl")
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                        _isPlaying.value = false
                        true
                    }
                    prepareAsync()
                }
                mediaPlayer = mp
            } catch (t: Throwable) {
                Log.e(TAG, "startStream error: ${t.message}")
                _isPlaying.value = false
            }
        }
    }

    private fun releasePlayer() {
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        }
        mediaPlayer = null
        _isPlaying.value = false
    }
}
