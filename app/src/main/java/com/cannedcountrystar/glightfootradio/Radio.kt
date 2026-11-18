package com.cannedcountrystar.glightfootradio

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

/** 局の情報と、アプリ全体で共有する保存データ (SharedPreferences) */
object Radio {
    const val DEFAULT_STREAM_URL = "https://streaming.exclusive.radio/er/gordonlightfoot/icecast.audio"
    const val STATION_NAME = "Exclusively Gordon Lightfoot"
    const val ARTIST = "Gordon Lightfoot"
    const val STATION_PAGE = "https://play.exclusive.radio/"
    const val MEDIA_ID = "glr-live"

    private const val PREFS = "glr"
    const val KEY_STREAM_URL = "stream_url"
    const val KEY_HISTORY = "history"
    const val KEY_NOW_RAW = "now_raw"
    const val KEY_NOW_TITLE = "now_title"
    const val KEY_NOW_ARTIST = "now_artist"
    const val KEY_NOW_ALBUM = "now_album"
    const val KEY_NOW_ART = "now_art"
    const val KEY_PLAYING = "playing"
    private const val HISTORY_MAX = 50

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun streamUrl(context: Context): String =
        prefs(context).getString(KEY_STREAM_URL, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_STREAM_URL

    fun setStreamUrl(context: Context, url: String?) {
        prefs(context).edit().apply {
            if (url.isNullOrBlank() || url == DEFAULT_STREAM_URL) remove(KEY_STREAM_URL) else putString(KEY_STREAM_URL, url.trim())
        }.apply()
    }

    /** 再生する MediaItem。曲が変わるたびに metadata だけ差し替える (URI が同じなら再生は途切れない) */
    fun buildItem(url: String, now: NowPlaying?): MediaItem =
        MediaItem.Builder()
            .setMediaId(MEDIA_ID)
            .setUri(url)
            .setMediaMetadata(metadataFor(now))
            .build()

    fun metadataFor(now: NowPlaying?): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(now?.title?.ifBlank { null } ?: STATION_NAME)
            .setArtist(now?.artist?.ifBlank { null } ?: ARTIST)
            .setAlbumTitle(now?.album?.ifBlank { null })
            .setStation(STATION_NAME)
            .setArtworkUri(now?.artUrl?.ifBlank { null }?.let { Uri.parse(it) })
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()

    // ---------- 今かかっている曲 ----------
    fun saveNowPlaying(context: Context, now: NowPlaying) {
        prefs(context).edit()
            .putString(KEY_NOW_RAW, now.raw)
            .putString(KEY_NOW_TITLE, now.title)
            .putString(KEY_NOW_ARTIST, now.artist)
            .putString(KEY_NOW_ALBUM, now.album)
            .putString(KEY_NOW_ART, now.artUrl)
            .apply()
    }

    fun loadNowPlaying(context: Context): NowPlaying? {
        val p = prefs(context)
        val raw = p.getString(KEY_NOW_RAW, null) ?: return null
        return NowPlaying(
            raw = raw,
            title = p.getString(KEY_NOW_TITLE, "") ?: "",
            artist = p.getString(KEY_NOW_ARTIST, "") ?: "",
            album = p.getString(KEY_NOW_ALBUM, "") ?: "",
            artUrl = p.getString(KEY_NOW_ART, "") ?: "",
        )
    }

    fun setPlaying(context: Context, playing: Boolean) {
        prefs(context).edit().putBoolean(KEY_PLAYING, playing).apply()
    }

    fun isPlaying(context: Context): Boolean = prefs(context).getBoolean(KEY_PLAYING, false)

    // ---------- 最近かかった曲 ----------
    fun addHistory(context: Context, raw: String) {
        val list = loadHistory(context).toMutableList()
        if (list.firstOrNull()?.raw == raw) return
        list.add(0, HistoryEntry(raw, System.currentTimeMillis()))
        val arr = JSONArray()
        list.take(HISTORY_MAX).forEach { arr.put(JSONObject().put("raw", it.raw).put("at", it.at)) }
        prefs(context).edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    fun loadHistory(context: Context): List<HistoryEntry> {
        val json = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(o.optString("raw"), o.optLong("at"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** "Gordon Lightfoot - Sundown" → (アーティスト, 曲名) */
    fun splitTitle(raw: String): Pair<String, String> {
        val s = raw.trim()
        val idx = s.indexOf(" - ")
        return if (idx > 0) s.substring(0, idx).trim() to s.substring(idx + 3).trim() else "" to s
    }

    /** ジャケット検索用に (Remastered 2004) や (Live) などを外す */
    fun cleanTitle(title: String): String =
        title.replace(Regex("""\s*[(\[][^)\]]*(remaster|live|version|mono|stereo|edit|mix)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .trim()
}

data class NowPlaying(
    val raw: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artUrl: String = "",
)

data class HistoryEntry(val raw: String, val at: Long)
