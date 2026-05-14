package com.addev.hushify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After reboot, bring the keep-alive foreground service back if the listener is still enabled. */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!NotificationAccess.isSpotifyMuteListenerEnabled(context)) return
        NotificationAccess.requestListenerReconnect(context.applicationContext)
        HushKeepAliveService.start(context.applicationContext)
    }
}
