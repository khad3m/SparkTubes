package com.sparktube.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.sparktube.app.R
import com.sparktube.app.playback.PlaybackCenter
import com.sparktube.app.ui.MainActivity
import com.sparktube.app.ui.music.NowPlayingActivity

/**
 * SparkTube Home Screen Glass Mini-Widget:
 * Displays current track/video title, artist/channel, and interactive
 * Previous / Play-Pause / Next buttons right on the Android launcher home screen.
 */
class SparkTubeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, buildRemoteViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        PlaybackCenter.init(context)
        when (intent.action) {
            ACTION_TOGGLE -> {
                PlaybackCenter.togglePlayPause()
                refreshAll(context)
            }
            ACTION_PREV -> {
                PlaybackCenter.previous()
                refreshAll(context)
            }
            ACTION_NEXT -> {
                PlaybackCenter.next()
                refreshAll(context)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.sparktube.app.widget.ACTION_TOGGLE"
        const val ACTION_PREV = "com.sparktube.app.widget.ACTION_PREV"
        const val ACTION_NEXT = "com.sparktube.app.widget.ACTION_NEXT"

        fun refreshAll(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(ComponentName(context, SparkTubeWidgetProvider::class.java))
                if (ids.isNotEmpty()) {
                    val views = buildRemoteViews(context)
                    ids.forEach { mgr.updateAppWidget(it, views) }
                }
            }
        }

        private fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_sparktube_glass)
            val entry = PlaybackCenter.currentEntry
            val playing = PlaybackCenter.isPlaying

            views.setTextViewText(
                R.id.widgetTitle,
                entry?.title?.ifBlank { "SparkTube Glass Player" } ?: "SparkTube Glass Player"
            )
            views.setTextViewText(
                R.id.widgetSubtitle,
                entry?.uploader?.ifBlank { "Tap to open SparkTube" } ?: "Tap to play music or videos"
            )
            views.setImageViewResource(
                R.id.widgetPlayPause,
                if (playing) R.drawable.ic_pause else R.drawable.ic_play_arrow
            )

            val openTarget = if (PlaybackCenter.mode == PlaybackCenter.Mode.AUDIO) {
                NowPlayingActivity::class.java
            } else {
                MainActivity::class.java
            }
            val openIntent = Intent(context, openTarget).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val openPi = PendingIntent.getActivity(
                context,
                100,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openPi)

            views.setOnClickPendingIntent(R.id.widgetPlayPause, broadcastPi(context, ACTION_TOGGLE, 101))
            views.setOnClickPendingIntent(R.id.widgetPrev, broadcastPi(context, ACTION_PREV, 102))
            views.setOnClickPendingIntent(R.id.widgetNext, broadcastPi(context, ACTION_NEXT, 103))
            return views
        }

        private fun broadcastPi(context: Context, action: String, reqCode: Int): PendingIntent {
            val intent = Intent(context, SparkTubeWidgetProvider::class.java).setAction(action)
            return PendingIntent.getBroadcast(
                context,
                reqCode,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
    }
}
