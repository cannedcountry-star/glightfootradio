package com.cannedcountrystar.glightfootradio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** ネットワーク処理 (すべてバックグラウンドスレッドから呼ぶこと) */
object Net {
    private const val UA = "GLightfootRadio/1.0 (Android)"

    fun getBytes(url: String, maxBytes: Int = 4 * 1024 * 1024): ByteArray {
        var current = url
        repeat(5) { // http ⇔ https をまたぐリダイレクトも自分でたどる
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: error("redirect without location")
                    current = URL(URL(current), loc).toString()
                    return@repeat
                }
                if (code != 200) error("HTTP $code")
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) error("too large")
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        error("too many redirects")
    }

    // ---------- ジャケット画像とアルバム名 (iTunes Search API) ----------
    data class ArtInfo(val album: String, val artUrl: String)

    private val artCache = LruCache<String, ArtInfo>(300)

    fun lookupArtwork(artist: String, title: String): ArtInfo? {
        val a = artist.ifBlank { Radio.ARTIST }
        val t = Radio.cleanTitle(title)
        if (t.isBlank()) return null
        val key = "$a\n$t".lowercase()
        artCache.get(key)?.let { return it }
        return try {
            val term = URLEncoder.encode("$a $t", "UTF-8")
            val json = JSONObject(String(getBytes("https://itunes.apple.com/search?media=music&entity=song&limit=10&term=$term"), Charsets.UTF_8))
            val results = json.optJSONArray("results")
            val last = a.lowercase().split(Regex("\\s+")).last()
            var info = ArtInfo("", "")
            if (results != null) {
                for (i in 0 until results.length()) {
                    val r = results.getJSONObject(i)
                    if (r.optString("artistName").lowercase().contains(last)) {
                        info = ArtInfo(
                            album = r.optString("collectionName"),
                            artUrl = r.optString("artworkUrl100").replace(Regex("/\\d+x\\d+bb\\."), "/600x600bb."),
                        )
                        break
                    }
                }
            }
            artCache.put(key, info)
            info
        } catch (e: Exception) {
            null
        }
    }

    // ---------- 画像 ----------
    private val bitmapCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun fetchBitmap(url: String, maxSize: Int): Bitmap? {
        val key = "$url@$maxSize"
        bitmapCache.get(key)?.let { return it }
        return try {
            val bytes = getBytes(url)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            bmp?.also { bitmapCache.put(key, it) }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- プレイリスト (.pls / .m3u) から実際の配信 URL を取り出す ----------
    fun resolvePlaylist(url: String): String {
        val path = url.substringBefore('?').lowercase()
        if (!path.endsWith(".pls") && !path.endsWith(".m3u")) return url
        val text = String(getBytes(url, 256 * 1024), Charsets.UTF_8)
        val line = text.lineSequence()
            .map { it.trim() }
            .map { if (it.startsWith("File", ignoreCase = true) && it.contains('=')) it.substringAfter('=').trim() else it }
            .firstOrNull { it.startsWith("http://", true) || it.startsWith("https://", true) }
        return line ?: error("プレイリストの中に配信 URL が見つかりませんでした")
    }
}
