package com.addev.hushify

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

object NotificationAccess {

    fun isSpotifyMuteListenerEnabled(context: Context): Boolean {
        val component = listenerComponent(context)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return flat.split(':')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { fragment ->
                runCatching { ComponentName.unflattenFromString(fragment) }.getOrNull()
            }
            .any { it == component }
    }

    /** After [android.service.notification.NotificationListenerService.requestUnbind], toggles stay on but bind can stall; this asks the OS to reconnect. */
    fun requestListenerReconnect(context: Context) {
        NotificationListenerService.requestRebind(listenerComponent(context))
    }

    internal fun listenerComponent(context: Context): ComponentName =
        ComponentName(context, SpotifyAdMuteService::class.java)
}
