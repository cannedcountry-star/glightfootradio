package com.cannedcountrystar.glightfootradio

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 再生画面。再生そのものは PlaybackService が受け持ち、ここは MediaController で操作・表示するだけ */
class MainActivity : Activity() {

    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var status: TextView
    private lateinit var play: ImageButton
    private lateinit var history: LinearLayout

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var shownArtUrl: String? = "-"

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Radio.KEY_HISTORY) renderHistory()
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        art = findViewById(R.id.art)
        title = findViewById(R.id.title)
        artist = findViewById(R.id.artist)
        album = findViewById(R.id.album)
        status = findViewById(R.id.status)
        play = findViewById(R.id.play)
        history = findViewById(R.id.history)

        art.outlineProvider = ViewOutlineProvider.BACKGROUND
        art.clipToOutline = true
        play.setOnClickListener { toggle() }
        findViewById<View>(R.id.menu).setOnClickListener { showMenu(it) }

        // 再生サービスにつながるまでは、前回の曲を表示しておく
        Radio.loadNowPlaying(this)?.let {
            title.text = it.title.ifBlank { Radio.STATION_NAME }
            artist.text = it.artist.ifBlank { Radio.ARTIST }
            setAlbum(it.album)
            showArt(it.artUrl)
        } ?: showArt(null)
        renderHistory()
    }

    override fun onStart() {
        super.onStart()
        Radio.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        renderHistory()
        connect()
    }

    override fun onStop() {
        Radio.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        controller?.removeListener(playerListener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        controllerFuture = f
        f.addListener({
            if (controllerFuture !== f) return@addListener
            controller = try { f.get() } catch (e: Exception) { null }
            controller?.addListener(playerListener)
            render()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggle() {
        val c = controller ?: run { connect(); return }
        if (c.playWhenReady) c.pause() else c.play()
    }

    // ---------- 表示 ----------
    private fun render() {
        val c = controller ?: return
        val md = c.mediaMetadata
        title.text = md.title?.toString()?.ifBlank { null } ?: Radio.STATION_NAME
        artist.text = md.artist?.toString()?.ifBlank { null } ?: Radio.ARTIST
        setAlbum(md.albumTitle?.toString())
        showArt(md.artworkUri?.toString())

        val err = c.playerError
        val (text, live) = when {
            c.isPlaying -> "● LIVE 再生中" to true
            c.playWhenReady && err != null -> "接続が切れました。自動でつなぎ直します…" to false
            c.playWhenReady -> "接続中…" to false
            err != null -> "再生できませんでした（${err.errorCodeName}）" to false
            else -> "停止中" to false
        }
        status.text = text
        status.setTextColor(getColor(if (live) R.color.accent else R.color.muted))
        play.setImageResource(if (c.playWhenReady) R.drawable.ic_stop else R.drawable.ic_play)
        play.contentDescription = getString(if (c.playWhenReady) R.string.stop else R.string.play)
    }

    private fun setAlbum(name: String?) {
        album.text = name ?: ""
        album.visibility = if (name.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private fun showArt(url: String?) {
        val u = url?.ifBlank { null }
        if (u == shownArtUrl) return
        shownArtUrl = u
        if (u == null) {
            art.setImageResource(R.drawable.art_placeholder)
            return
        }
        io.execute {
            val bmp = Net.fetchBitmap(u, 800)
            handler.post {
                if (shownArtUrl != u) return@post
                if (bmp != null) art.setImageBitmap(bmp) else art.setImageResource(R.drawable.art_placeholder)
            }
        }
    }

    private fun renderHistory() {
        history.removeAllViews()
        val list = Radio.loadHistory(this)
        if (list.isEmpty()) {
            history.addView(TextView(this).apply {
                text = getString(R.string.history_empty)
                setTextColor(getColor(R.color.muted))
                textSize = 13f
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })
            return
        }
        val today = Calendar.getInstance()
        val timeFmt = SimpleDateFormat("HH:mm", Locale.JAPAN)
        val dayFmt = SimpleDateFormat("M/d HH:mm", Locale.JAPAN)
        val ripple = TypedValue().also { theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true) }.resourceId
        list.forEach { h ->
            val cal = Calendar.getInstance().apply { timeInMillis = h.at }
            val sameDay = cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(9), dp(12), dp(9))
                setBackgroundResource(ripple)
                contentDescription = "${h.raw} を YouTube で検索"
                setOnClickListener {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(h.raw))))
                }
            }
            row.addView(TextView(this).apply {
                text = (if (sameDay) timeFmt else dayFmt).format(Date(h.at))
                setTextColor(getColor(R.color.muted))
                textSize = 12f
                minWidth = dp(48)
            })
            row.addView(TextView(this).apply {
                text = h.raw
                setTextColor(getColor(R.color.text))
                textSize = 14f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) }
            })
            history.addView(row)
        }
    }

    // ---------- メニュー ----------
    private fun showMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.menu_stream_url)
            menu.add(0, 2, 1, R.string.menu_station_page)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> showStreamUrlDialog()
                    2 -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Radio.STATION_PAGE)))
                }
                true
            }
        }.show()
    }

    /** 配信元の URL が変わったときに書き換えられるようにする (.pls / .m3u のプレイリスト URL も可) */
    private fun showStreamUrlDialog() {
        val input = EditText(this).apply {
            setText(Radio.streamUrl(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.menu_stream_url)
            .setMessage(R.string.stream_url_help)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ -> saveStreamUrl(input.text.toString().trim()) }
            .setNeutralButton(R.string.reset) { _, _ ->
                Radio.setStreamUrl(this, null)
                reconnectIfPlaying()
                toast(getString(R.string.stream_url_reset))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun saveStreamUrl(url: String) {
        if (url.isBlank()) {
            Radio.setStreamUrl(this, null)
            reconnectIfPlaying()
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            toast(getString(R.string.stream_url_invalid))
            return
        }
        io.execute {
            val result = try { Result.success(Net.resolvePlaylist(url)) } catch (e: Exception) { Result.failure(e) }
            handler.post {
                result.onSuccess {
                    Radio.setStreamUrl(this, it)
                    reconnectIfPlaying()
                    toast(getString(R.string.stream_url_saved))
                }.onFailure {
                    toast(getString(R.string.stream_url_error, it.message ?: ""))
                }
            }
        }
    }

    // 再生中なら、新しい URL でつなぎ直す (PlaybackService 側が URL の変化を見て差し替える)
    private fun reconnectIfPlaying() {
        val c = controller ?: return
        if (c.playWhenReady) {
            c.pause()
            c.play()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
