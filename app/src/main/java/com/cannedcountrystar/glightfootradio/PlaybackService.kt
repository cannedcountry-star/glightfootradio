package com.cannedcountrystar.glightfootradio

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Metadata
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * 再生を受け持つサービス。画面を閉じても・画面を消しても再生を続け、
 * 通知・ロック画面・Bluetooth 機器・ホーム画面ウィジェットから操作できる。
 *
 * - 曲名は配信に埋め込まれた ICY メタデータから取り、iTunes でジャケットとアルバム名を探す
 * - 電波が切れても自動でつなぎ直す (読み込みエラーは粘り強く再試行、それでもダメなら間隔を空けて再接続)
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private val io: ExecutorService = Executors.newSingleThreadExecutor()
    private var nowPlaying: NowPlaying? = null
    private var retryCount = 0

    override fun onCreate() {
        super.onCreate()
        nowPlaying = Radio.loadNowPlaying(this)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("GLightfootRadio/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val sourceFactory = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setLoadErrorHandlingPolicy(StubbornLoadErrorPolicy())

        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(sourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true,
            )
            .setHandleAudioBecomingNoisy(true) // イヤホンを抜いたら止める
            .setWakeMode(C.WAKE_MODE_NETWORK)  // 画面を消しても通信と再生を続ける
            .build()
        exo.setMediaItem(Radio.buildItem(Radio.streamUrl(this), nowPlaying))
        exo.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, LivePlayer(exo))
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // アプリを履歴から消したとき: 再生していなければサービスも終える
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        session?.run {
            player.release()
            release()
        }
        session = null
        Radio.setPlaying(this, false)
        RadioWidgetProvider.updateAll(this)
        super.onDestroy()
    }

    // ---------- 曲名 ----------
    private val playerListener = object : Player.Listener {
        override fun onMetadata(metadata: Metadata) {
            for (i in 0 until metadata.length()) {
                val entry = metadata.get(i)
                if (entry is IcyInfo) {
                    val title = entry.title?.trim()
                    if (!title.isNullOrEmpty()) onStreamTitle(title)
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady) cancelRetry()
            Radio.setPlaying(this@PlaybackService, playWhenReady)
            RadioWidgetProvider.updateAll(this@PlaybackService)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) retryCount = 0
        }

        override fun onPlaybackStateChanged(state: Int) {
            // 配信側が接続を切った (= 終わりまで来た) ときは、すぐ今の放送につなぎ直す
            if (state == Player.STATE_ENDED && exo.playWhenReady) exo.seekToDefaultPosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRetry()
        }
    }

    private fun onStreamTitle(raw: String) {
        if (raw == nowPlaying?.raw) return
        val (artist, title) = Radio.splitTitle(raw)
        val now = NowPlaying(raw = raw, title = title, artist = artist)
        nowPlaying = now
        applyMetadata(now)
        Radio.saveNowPlaying(this, now)
        Radio.addHistory(this, raw)
        RadioWidgetProvider.updateAll(this)

        io.execute {
            val info = Net.lookupArtwork(artist, title) ?: return@execute
            if (info.artUrl.isNotEmpty()) RadioWidgetProvider.cacheArtwork(this, info.artUrl)
            handler.post {
                if (nowPlaying?.raw != raw) return@post
                val withArt = now.copy(album = info.album, artUrl = info.artUrl)
                nowPlaying = withArt
                applyMetadata(withArt)
                Radio.saveNowPlaying(this, withArt)
                RadioWidgetProvider.updateAll(this)
            }
        }
    }

    // 通知・ロック画面に出す曲名を更新する。URI は同じなので再生は途切れない
    private fun applyMetadata(now: NowPlaying) {
        val item = exo.currentMediaItem ?: return
        exo.replaceMediaItem(
            exo.currentMediaItemIndex,
            item.buildUpon().setMediaMetadata(Radio.metadataFor(now)).build(),
        )
    }

    // ---------- 再接続 ----------
    private val retryRunnable = Runnable {
        if (exo.playWhenReady) {
            exo.seekToDefaultPosition()
            exo.prepare()
        }
    }

    // 2, 4, 8, 16, 30, 30… 秒おきにつなぎ直す
    private fun scheduleRetry() {
        if (!exo.playWhenReady) return
        handler.removeCallbacks(retryRunnable)
        retryCount++
        val sec = min(30, 1 shl min(retryCount, 5))
        handler.postDelayed(retryRunnable, sec * 1000L)
    }

    private fun cancelRetry() {
        handler.removeCallbacks(retryRunnable)
    }

    /**
     * 外から来た「再生」を、ライブ配信向けに少し手直しするプレーヤー。
     * - しばらく一時停止したあとの再生や、エラー後の再生では、古い音ではなく「今」の放送につなぎ直す
     * - 配信 URL が設定で変わっていたら差し替える
     */
    private inner class LivePlayer(private val exo: ExoPlayer) : ForwardingPlayer(exo) {
        private var pausedAt = 0L

        override fun play() {
            beforePlay()
            super.play()
        }

        override fun setPlayWhenReady(playWhenReady: Boolean) {
            if (playWhenReady) beforePlay() else pausedAt = SystemClock.elapsedRealtime()
            super.setPlayWhenReady(playWhenReady)
        }

        override fun pause() {
            pausedAt = SystemClock.elapsedRealtime()
            super.pause()
        }

        private fun beforePlay() {
            cancelRetry()
            val url = Radio.streamUrl(this@PlaybackService)
            val currentUrl = exo.currentMediaItem?.localConfiguration?.uri?.toString()
            val longPause = pausedAt != 0L && SystemClock.elapsedRealtime() - pausedAt > 10_000
            if (exo.mediaItemCount == 0 || currentUrl != url) {
                exo.setMediaItem(Radio.buildItem(url, nowPlaying))
                exo.prepare()
            } else if (exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED || longPause) {
                exo.seekToDefaultPosition()
                exo.prepare()
            }
            pausedAt = 0L
        }
    }

    private inner class SessionCallback : MediaSession.Callback {
        // ライブ配信なので、前後の曲送り・シークのボタンは出さない
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                )
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        // 通知の「再開」やイヤホンの再生ボタンで、アプリを開かずに再生を始めるとき
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
            Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    listOf(Radio.buildItem(Radio.streamUrl(this@PlaybackService), nowPlaying)),
                    0,
                    C.TIME_UNSET,
                ),
            )
    }
}

/**
 * 読み込みエラーで再生を止めず、間隔を空けながら何度でもつなぎ直す。
 * ただし形式が読めない・URL が間違っている (4xx) 場合はすぐ諦めて、上の再接続処理に任せる。
 */
@OptIn(UnstableApi::class)
private class StubbornLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        val e = loadErrorInfo.exception
        if (e is ParserException) return C.TIME_UNSET
        if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
        return min(loadErrorInfo.errorCount * 2_000L, 30_000L)
    }

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
}
