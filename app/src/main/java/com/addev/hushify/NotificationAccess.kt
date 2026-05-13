package com.addev.hushify

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

object NotificationAccess {

    fun isSpotifyMuteListenerEnabled(context: Context): Boolean {
        val component = ComponentName(context, SpotifyAdMuteService::class.java)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.split(':').any { part ->
            ComponentName.unflattenFromString(part.trim()) == component
        }
    }
}
