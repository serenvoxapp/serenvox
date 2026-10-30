// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright 2026 Ryan Lu
package com.serenvox.app.tts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.serenvox.app.MainActivity
import com.serenvox.app.R
import com.serenvox.app.SerenvoxApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground service that keeps speech playing in the background with a media notification. */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSessionCompat
    private val player get() = (application as SerenvoxApp).player

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        session = MediaSessionCompat(this, "Serenvox").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = player.resume()
                override fun onPause() = player.pause()
                override fun onStop() = player.stop()
            })
            isActive = true
        }
        ServiceCompat.startForeground(this, NOTIF_ID, build(player.state.value),
            if (android.os.Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
        scope.launch {
            player.state.collect { s ->
                if (s.status == Status.IDLE) {
                    ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    updateSession(s)
                    nm.notify(NOTIF_ID, build(s))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> player.pause()
            ACTION_RESUME -> player.resume()
            ACTION_STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

    private fun updateSession(s: PlayerState) {
        val st = if (s.status == Status.PAUSED) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        session.setPlaybackState(PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP)
            .setState(st, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f).build())
        session.setMetadata(MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.chunks.getOrNull(s.index) ?: "")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.engine).build())
    }

    private fun action(a: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(s: PlayerState) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(getString(R.string.app_name) + if (s.engine.isNotEmpty()) " · ${s.engine}" else "")
        .setContentText(s.chunks.getOrNull(s.index) ?: "")
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(s.status != Status.PAUSED)
        .setSilent(true)
        .setProgress(s.chunks.size.coerceAtLeast(1), s.index, s.status == Status.PREPARING)
        .apply {
            if (s.status == Status.PAUSED) addAction(android.R.drawable.ic_media_play, "Play", action(ACTION_RESUME, 1))
            else addAction(android.R.drawable.ic_media_pause, "Pause", action(ACTION_PAUSE, 2))
            addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", action(ACTION_STOP, 3))
        }
        .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
        .build()

    override fun onDestroy() { scope.cancel(); session.release(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42
        const val ACTION_PAUSE = "pause"
        const val ACTION_RESUME = "resume"
        const val ACTION_STOP = "stop"
        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
    }
}
