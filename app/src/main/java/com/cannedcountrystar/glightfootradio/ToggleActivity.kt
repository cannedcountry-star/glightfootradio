package com.cannedcountrystar.glightfootradio

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

/**
 * ホーム画面ウィジェットの ▶/■ から呼ばれる、画面を持たない中継役。
 * 一瞬アプリを「前面」にしてから再生を始めることで、Android 12 以降の
 * バックグラウンド起動の制限に引っかからずに再生サービスを立ち上げられる。
 */
class ToggleActivity : Activity() {
    private var future: ListenableFuture<MediaController>? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener({
            val controller = try { f.get() } catch (e: Exception) { null }
            if (controller == null) {
                finish()
                return@addListener
            }
            if (controller.playWhenReady) {
                controller.pause()
                finish()
            } else {
                controller.play()
                // 再生サービスが前面サービスに切り替わるまで少し待ってから閉じる
                controller.addListener(object : Player.Listener {
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (playWhenReady) handler.postDelayed({ finish() }, 800)
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_READY) {
                            handler.postDelayed({ finish() }, 800)
                        }
                    }
                })
                handler.postDelayed({ finish() }, 3_000)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
