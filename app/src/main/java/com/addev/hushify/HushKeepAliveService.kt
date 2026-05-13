package com.addev.hushify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service so the OS is less likely to kill the host process that also runs
 * [SpotifyAdMuteService]. Restarted sticky if the process is recreated.
 */
class HushKeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NotificationAccess.isSpotifyMuteListenerEnabled(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        val fgType =
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
        return START_STICKY
    }

    override fun onDestroy() {
        @Suppress("Deprecation")
        stopForeground(true)
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_KEEPALIVE_ID,
            getString(R.string.channel_keepalive_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_keepalive_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannel()
        val openMain = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or pendingImmutableFlag()
        )
        return NotificationCompat.Builder(this, CHANNEL_KEEPALIVE_ID)
            .setSmallIcon(R.drawable.ic_stat_hushify)
            .setContentTitle(getString(R.string.keepalive_notification_title))
            .setContentText(getString(R.string.keepalive_notification_text))
            .setContentIntent(openMain)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun pendingImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    companion object {
        private const val CHANNEL_KEEPALIVE_ID = "hushify_keepalive"
        private const val NOTIFICATION_ID = 0x6856

        fun start(context: Context) {
            if (!NotificationAccess.isSpotifyMuteListenerEnabled(context)) return
            val intent = Intent(context, HushKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HushKeepAliveService::class.java))
        }
    }
}
