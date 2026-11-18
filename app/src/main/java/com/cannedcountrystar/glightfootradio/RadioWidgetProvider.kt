package com.cannedcountrystar.glightfootradio

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import java.io.File

/** ホーム画面ウィジェット: 今の曲・ジャケット・▶/■ ボタン */
class RadioWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        update(context, appWidgetManager, appWidgetIds)
    }

    companion object {
        private const val ART_FILE = "widget_art.png"
        private const val KEY_ART_URL = "widget_art_url"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RadioWidgetProvider::class.java))
            if (ids.isNotEmpty()) update(context, manager, ids)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val now = Radio.loadNowPlaying(context)
            val playing = Radio.isPlaying(context)
            val views = RemoteViews(context.packageName, R.layout.widget_radio)

            views.setTextViewText(R.id.widget_title, now?.title?.ifBlank { null } ?: Radio.STATION_NAME)
            views.setTextViewText(R.id.widget_artist, now?.artist?.ifBlank { null } ?: Radio.ARTIST)
            views.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_stop else R.drawable.ic_play)
            views.setContentDescription(R.id.widget_play, context.getString(if (playing) R.string.stop else R.string.play))

            val art = loadArt(context, now?.artUrl)
            if (art != null) views.setImageViewBitmap(R.id.widget_art, art)
            else views.setImageViewResource(R.id.widget_art, R.drawable.art_placeholder)

            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val toggle = Intent(context, ToggleActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            views.setOnClickPendingIntent(R.id.widget_play, PendingIntent.getActivity(context, 1, toggle, flags))
            val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 2, open, flags))

            manager.updateAppWidget(ids, views)
        }

        /** ウィジェット用にジャケットを小さくして保存する (バックグラウンドスレッドから呼ぶ) */
        fun cacheArtwork(context: Context, url: String) {
            val bmp = Net.fetchBitmap(url, 200) ?: return
            try {
                File(context.filesDir, ART_FILE).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Radio.prefs(context).edit().putString(KEY_ART_URL, url).apply()
            } catch (e: Exception) {
                // 保存できなければ既定の絵を出す
            }
        }

        private fun loadArt(context: Context, url: String?): Bitmap? {
            if (url.isNullOrBlank() || Radio.prefs(context).getString(KEY_ART_URL, null) != url) return null
            return try {
                BitmapFactory.decodeFile(File(context.filesDir, ART_FILE).path)
            } catch (e: Exception) {
                null
            }
        }
    }
}
