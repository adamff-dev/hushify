package com.addev.hushify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Listens for Spotify notifications and lowers media output while ads play.
 * Mutes [AudioManager.STREAM_MUSIC] (multimedia, including typical Bluetooth A2DP playback)
 * and the Bluetooth SCO stream (volume index 6).
 */
class SpotifyAdMuteService : NotificationListenerService() {

    private val audioManager: AudioManager by lazy {
        getSystemService(AUDIO_SERVICE) as AudioManager
    }
    private val muteHandler = Handler(Looper.getMainLooper())
    private var savedMusicVolume: Int = -1
    private var savedBluetoothScoVolume: Int = -1
    private var mutedForAd = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (mutedForAd) reapplyAdMute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (mutedForAd) reapplyAdMute()
        }
    }

    private val idleRecheckRunnable = Runnable {
        val active = activeNotifications ?: return@Runnable
        val spotify = active.filter { it.packageName == SPOTIFY_PACKAGE }
        applyAdStateFromSpotifyNotifications(spotify)
    }

    private val fullStopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION_STOP_FULLY) return
            if (mutedForAd) {
                endMuteSession()
            } else {
                dismissPassiveMuteNotification()
            }
            HushKeepAliveService.stop(this@SpotifyAdMuteService)
            try {
                requestUnbind()
            } catch (_: RuntimeException) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_STOP_FULLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fullStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(fullStopReceiver, filter)
        }
    }

    override fun onDestroy() {
        muteHandler.removeCallbacks(idleRecheckRunnable)
        try {
            unregisterReceiver(fullStopReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ensurePassiveChannel()
        HushKeepAliveService.start(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != SPOTIFY_PACKAGE) return
        reevaluateSpotifyFromActive()
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        super.onNotificationRankingUpdate(rankingMap)
        if (!mutedForAd) {
            val active = activeNotifications ?: return
            if (active.none { it.packageName == SPOTIFY_PACKAGE }) return
        }
        reevaluateSpotifyFromActive()
    }

    /**
     * Uses every active Spotify notification: media updates sometimes arrive as separate keys or
     * non-media notifications can appear first in the list. Debounces [endMuteSession] when the
     * list is momentarily empty during notification refresh.
     */
    private fun reevaluateSpotifyFromActive() {
        muteHandler.removeCallbacks(idleRecheckRunnable)
        val active = activeNotifications ?: return
        val spotify = active.filter { it.packageName == SPOTIFY_PACKAGE }
        if (spotify.isEmpty()) {
            if (mutedForAd) {
                muteHandler.postDelayed(idleRecheckRunnable, EMPTY_REEVALUATION_MS)
            }
            return
        }
        applyAdStateFromSpotifyNotifications(spotify)
    }

    private fun applyAdStateFromSpotifyNotifications(spotify: List<StatusBarNotification>) {
        if (spotify.isEmpty()) {
            if (mutedForAd) endMuteSession()
            return
        }
        val anyAd = spotify.any { AdSignalDetector.isLikelyAd(it.notification) }
        when {
            anyAd && !mutedForAd -> beginMuteSession()
            !anyAd && mutedForAd -> endMuteSession()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != SPOTIFY_PACKAGE) return
        reevaluateSpotifyFromActive()
    }

    override fun onListenerDisconnected() {
        if (mutedForAd) {
            endMuteSession()
        }
        HushKeepAliveService.stop(this)
        super.onListenerDisconnected()
    }

    private fun beginMuteSession() {
        muteHandler.removeCallbacks(idleRecheckRunnable)
        savedMusicVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        savedBluetoothScoVolume =
            audioManager.getStreamVolume(STREAM_BLUETOOTH_SCO_LEGACY)
        if (savedMusicVolume == 0 && savedBluetoothScoVolume == 0) {
            mutedForAd = false
            return
        }
        mutedForAd = true
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, muteHandler)
        applyAdMute()
        showPassiveMuteNotification()
    }

    private fun endMuteSession() {
        if (!mutedForAd) return
        muteHandler.removeCallbacks(idleRecheckRunnable)
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: IllegalArgumentException) {
        }
        if (savedMusicVolume >= 0) {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = savedMusicVolume.coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
        if (savedBluetoothScoVolume >= 0) {
            val maxSco = audioManager.getStreamMaxVolume(STREAM_BLUETOOTH_SCO_LEGACY)
            val targetSco = savedBluetoothScoVolume.coerceIn(0, maxSco)
            audioManager.setStreamVolume(STREAM_BLUETOOTH_SCO_LEGACY, targetSco, 0)
        }
        mutedForAd = false
        savedMusicVolume = -1
        savedBluetoothScoVolume = -1
        dismissPassiveMuteNotification()
    }

    private fun applyAdMute() {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        audioManager.setStreamVolume(STREAM_BLUETOOTH_SCO_LEGACY, 0, 0)
    }

    private fun reapplyAdMute() {
        if (!mutedForAd) return
        applyAdMute()
    }

    private fun ensurePassiveChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_PASSIVE_ID,
            getString(R.string.channel_passive_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.channel_passive_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun showPassiveMuteNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensurePassiveChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_PASSIVE_ID)
            .setSmallIcon(R.drawable.ic_stat_hushify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.passive_notification_ad_detected))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(PASSIVE_NOTIFICATION_ID, notification)
    }

    private fun dismissPassiveMuteNotification() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.cancel(PASSIVE_NOTIFICATION_ID)
    }

    companion object {
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val ACTION_STOP_FULLY = "com.addev.hushify.action.STOP_FULLY"

        private const val STREAM_BLUETOOTH_SCO_LEGACY = 6
        private const val EMPTY_REEVALUATION_MS = 450L
        private const val CHANNEL_PASSIVE_ID = "hushify_passive"
        private const val PASSIVE_NOTIFICATION_ID = 0x6855

        /** Stops keep-alive, dismisses ad notification if any, and unbinds this listener service. */
        fun sendFullStop(context: Context) {
            context.applicationContext.sendBroadcast(
                Intent(ACTION_STOP_FULLY).setPackage(context.packageName)
            )
        }
    }
}
