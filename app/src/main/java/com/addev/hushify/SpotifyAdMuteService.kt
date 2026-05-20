package com.addev.hushify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.Notification
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.NotificationListenerService.RankingMap
import android.service.notification.StatusBarNotification
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.os.BundleCompat

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

    private val unmuteRunnable = Runnable {
        if (mutedForAd) {
            endMuteSession()
        }
    }

    /**
     * True after we've shown [R.string.toast_listener_connected] for this bind.
     * Spotify often has no (or no usable) notification row while still exposing a media session;
     * toast and ad checks must use tray and/or [hasSpotifyMediaSession].
     */
    private var announcedSpotifyWatching = false

    /** Elapsed-realtime ms of last Spotify-listening toast (debounce duplicate UI events). */
    private var lastSpotifyListeningToastElapsed = 0L

    private var sessionsMonitorRegistered = false
    private var watchedSpotifyController: MediaController? = null
    private var watchedSpotifyCallback: MediaController.Callback? = null

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener {
            muteHandler.post {
                ensureSpotifyMediaControllerWatch()
                reevaluateSpotifyFromActive()
            }
        }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (mutedForAd) reapplyAdMute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (mutedForAd) reapplyAdMute()
        }
    }

    private val idleHandler = Handler(Looper.getMainLooper())

    private val idleRecheckRunnable = Runnable {
        val active = activeNotifications ?: return@Runnable
        val spotify = active.filter { it.packageName == SPOTIFY_PACKAGE }
        applyAdStateFromSpotifyNotifications(spotify)
    }

    private val idleExitRunnable = Runnable {
        if (!isCloseOnIdleEnabled()) return@Runnable
        val active = activeNotifications
        if (active != null && active.any { it.packageName == SPOTIFY_PACKAGE }) return@Runnable
        performIdleShutdownFromListener()
    }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                ACTION_CANCEL_IDLE_SHUTDOWN -> idleHandler.removeCallbacks(idleExitRunnable)
                ACTION_PROBE_IDLE_SHUTDOWN -> scheduleIdleCloseIfNeeded()
                ACTION_STOP_FULLY -> {
                    idleHandler.removeCallbacks(idleExitRunnable)
                    muteHandler.removeCallbacks(unmuteRunnable)
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
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(ACTION_STOP_FULLY)
            addAction(ACTION_CANCEL_IDLE_SHUTDOWN)
            addAction(ACTION_PROBE_IDLE_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(controlReceiver, filter)
        }
    }

    override fun onDestroy() {
        muteHandler.removeCallbacks(unmuteRunnable)
        muteHandler.removeCallbacks(idleRecheckRunnable)
        idleHandler.removeCallbacks(idleExitRunnable)
        unregisterSessionsMonitor()
        clearSpotifyMediaControllerWatch()
        try {
            unregisterReceiver(controlReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        ensurePassiveChannel()
        HushKeepAliveService.start(this)
        announcedSpotifyWatching = false
        registerSessionsMonitor()
        reevaluateSpotifyFromActive()
        // Retry after a short delay: media sessions may not be surfaced to the listener
        // immediately on binding, so a deferred check catches Spotify already playing.
        muteHandler.postDelayed({ reevaluateSpotifyFromActive() }, LISTENER_CONNECTED_RETRY_MS)
        scheduleIdleCloseIfNeeded()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != SPOTIFY_PACKAGE) return
        reevaluateSpotifyFromActive()
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap) {
        super.onNotificationRankingUpdate(rankingMap)
        if (!mutedForAd) {
            val active = activeNotifications
            if (active == null || active.none { it.packageName == SPOTIFY_PACKAGE }) {
                scheduleIdleCloseIfNeeded()
                return
            }
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
        toastIfSpotifyDetectedFirstTime(active)
        val spotify = active.filter { it.packageName == SPOTIFY_PACKAGE }
        applyAdStateFromSpotifyNotifications(spotify)
        ensureSpotifyMediaControllerWatch()
    }

    /** Tray alone misses many devices; Spotify can still expose a controllable media session. */
    private fun hasSpotifyMediaSession(): Boolean {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return false
        val component = ComponentName(this, SpotifyAdMuteService::class.java)
        return try {
            msm.getActiveSessions(component).any { it.packageName == SPOTIFY_PACKAGE }
        } catch (_: SecurityException) {
            false
        }
    }

    private fun registerSessionsMonitor() {
        if (sessionsMonitorRegistered) return
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        val cn = ComponentName(this, SpotifyAdMuteService::class.java)
        try {
            msm.addOnActiveSessionsChangedListener(activeSessionsChangedListener, cn, muteHandler)
            sessionsMonitorRegistered = true
        } catch (_: SecurityException) {
        }
    }

    private fun unregisterSessionsMonitor() {
        if (!sessionsMonitorRegistered) return
        sessionsMonitorRegistered = false
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return
        try {
            msm.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
        } catch (_: SecurityException) {
        }
    }

    /**
     * Ad titles often arrive only via [MediaMetadata] updates, not ranking/posted churn.
     * Register once per distinct [MediaController] instance.
     */
    private fun ensureSpotifyMediaControllerWatch() {
        val controller = primarySpotifyController()
        if (controller == null) {
            clearSpotifyMediaControllerWatch()
            return
        }
        if (watchedSpotifyController === controller) return
        clearSpotifyMediaControllerWatch()
        watchedSpotifyController = controller
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(metadata: MediaMetadata?) {
                muteHandler.post { reevaluateSpotifyFromActive() }
            }

            override fun onPlaybackStateChanged(state: PlaybackState?) {
                muteHandler.post { reevaluateSpotifyFromActive() }
            }
        }
        watchedSpotifyCallback = cb
        controller.registerCallback(cb, muteHandler)
    }

    private fun clearSpotifyMediaControllerWatch() {
        val c = watchedSpotifyController
        val cb = watchedSpotifyCallback
        if (c != null && cb != null) {
            try {
                c.unregisterCallback(cb)
            } catch (_: Exception) {
            }
        }
        watchedSpotifyController = null
        watchedSpotifyCallback = null
    }

    private fun applyAdStateFromSpotifyNotifications(spotify: List<StatusBarNotification>) {
        val anyAd = spotify.any { AdSignalDetector.isLikelyAd(it.notification) } ||
            spotifyAdSignalFromSessions(spotify)
        if (anyAd) {
            muteHandler.removeCallbacks(unmuteRunnable)
        }
        when {
            anyAd && !mutedForAd -> beginMuteSession()
            !anyAd && mutedForAd -> {
                if (!shouldDeferUnmuteDueToPausedOrQuietSpotify()) {
                    muteHandler.removeCallbacks(unmuteRunnable)
                    muteHandler.postDelayed(unmuteRunnable, UNMUTE_DELAY_MS)
                } else {
                    muteHandler.removeCallbacks(idleRecheckRunnable)
                    muteHandler.postDelayed(idleRecheckRunnable, EMPTY_REEVALUATION_MS)
                }
            }
        }
        scheduleIdleCloseIfNeeded()
    }

    private fun isCloseOnIdleEnabled(): Boolean {
        return applicationContext.getSharedPreferences(MainActivity.PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_CLOSE_ON_SPOTIFY_IDLE, false)
    }

    private fun scheduleIdleCloseIfNeeded() {
        idleHandler.removeCallbacks(idleExitRunnable)
        if (!isCloseOnIdleEnabled()) return
        val active = activeNotifications
        if (active != null && active.any { it.packageName == SPOTIFY_PACKAGE }) return
        idleHandler.postDelayed(idleExitRunnable, IDLE_CLOSE_AFTER_MS)
    }

    private fun isSpotifyPlaying(): Boolean {
        val controller = primarySpotifyController() ?: return false
        val state = controller.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING
    }

    private fun performIdleShutdownFromListener() {
        SpotifyAdMuteService.sendFullStop(applicationContext)
        muteHandler.post { MainActivity.dismissIfOpenAfterIdleShutdown() }
    }

    private fun primarySpotifyController(): MediaController? {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return null
        val component = ComponentName(this, SpotifyAdMuteService::class.java)
        return try {
            msm.getActiveSessions(component).firstOrNull { it.packageName == SPOTIFY_PACKAGE }
        } catch (_: SecurityException) {
            null
        }
    }

    /**
     * When paused, Spotify often drops ad wording from the media notification/metadata while the
     * ad is still queued — unmuting looks like volume "coming back on resume". Only clear the mute
     * session once playback hits [PlaybackState.STATE_PLAYING] without an ad signal.
     *
     * If there is no session (force-stop), do not defer: allow volume restore like before.
     */
    private fun shouldDeferUnmuteDueToPausedOrQuietSpotify(): Boolean {
        val controller = primarySpotifyController() ?: return false
        val state = controller.playbackState ?: return false
        return state.state != PlaybackState.STATE_PLAYING
    }

    /**
     * Spotify often shows titles like "Anuncio" via [MediaMetadata] tied to EXTRA_MEDIA_SESSION
     * rather than flattened notification extras — read that path and enumerate active controllers
     * as a fallback.
     *
     * Also checks the Android-standard [MediaMetadata.METADATA_KEY_ADVERTISEMENT] long flag and
     * the Spotify-specific "spotify:ad:" URI prefix, both of which modern Spotify uses to mark ads
     * without embedding localised "Advertisement" text in the notification.
     */
    private fun spotifyAdSignalFromSessions(spotify: List<StatusBarNotification>): Boolean {
        // Check via media session tokens linked directly to each Spotify notification.
        for (sbn in spotify) {
            val extras = sbn.notification.extras ?: continue
            val token = extras.mediaSessionToken() ?: continue
            try {
                val controller = MediaController(this, token)
                if (controller.packageName != SPOTIFY_PACKAGE) continue
                val metadata = controller.metadata
                if (AdSignalDetector.isAdByMetadataFlag(metadata)) return true
                if (AdSignalDetector.isAdByMediaId(metadata)) return true
            } catch (_: IllegalArgumentException) {
            }
        }
        // Check via all active Spotify sessions (covers the case where there is no tray
        // notification but Spotify still owns a live MediaSession).
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager
        if (msm != null) {
            val component = ComponentName(this, SpotifyAdMuteService::class.java)
            try {
                for (controller in msm.getActiveSessions(component)) {
                    if (controller.packageName != SPOTIFY_PACKAGE) continue
                    val metadata = controller.metadata
                    if (AdSignalDetector.isAdByMetadataFlag(metadata)) return true
                    if (AdSignalDetector.isAdByMediaId(metadata)) return true
                }
            } catch (_: SecurityException) {
            }
        }
        // Fall back to the text-phrase heuristic for older Spotify builds that still embed
        // localised "Advertisement" (or equivalent) in the notification payload.
        val fromLinkedTokens = supplementarySpotifyTextFromLinkedSessions(spotify)
        if (fromLinkedTokens.isNotBlank() && AdSignalDetector.appliesAdPhraseHeuristic(fromLinkedTokens)) {
            return true
        }
        val fromGlobalSessions = supplementarySpotifyTextFromActiveSessions()
        return fromGlobalSessions.isNotBlank() &&
            AdSignalDetector.appliesAdPhraseHeuristic(fromGlobalSessions)
    }

    private fun supplementarySpotifyTextFromLinkedSessions(spotify: List<StatusBarNotification>): String {
        val sb = StringBuilder()
        for (sbn in spotify) {
            val extras = sbn.notification.extras ?: continue
            val token = extras.mediaSessionToken() ?: continue
            try {
                val controller = MediaController(this, token)
                if (controller.packageName != SPOTIFY_PACKAGE) continue
                sb.append('\n').append(
                    AdSignalDetector.concatenatedMediaMetadata(controller.metadata)
                )
            } catch (_: IllegalArgumentException) {
            }
        }
        return sb.toString()
    }

    private fun supplementarySpotifyTextFromActiveSessions(): String {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as? MediaSessionManager ?: return ""
        val component = ComponentName(this, SpotifyAdMuteService::class.java)
        return try {
            val sb = StringBuilder()
            for (controller in msm.getActiveSessions(component)) {
                if (controller.packageName != SPOTIFY_PACKAGE) continue
                sb.append('\n').append(
                    AdSignalDetector.concatenatedMediaMetadata(controller.metadata)
                )
            }
            sb.toString()
        } catch (_: SecurityException) {
            ""
        }
    }

    private fun android.os.Bundle.mediaSessionToken(): MediaSession.Token? {
        return BundleCompat.getParcelable(
            this,
            Notification.EXTRA_MEDIA_SESSION,
            MediaSession.Token::class.java,
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.packageName != SPOTIFY_PACKAGE) return
        reevaluateSpotifyFromActive()
    }

    override fun onListenerDisconnected() {
        muteHandler.removeCallbacks(unmuteRunnable)
        if (mutedForAd) {
            endMuteSession()
        }
        unregisterSessionsMonitor()
        clearSpotifyMediaControllerWatch()
        HushKeepAliveService.stop(this)
        announcedSpotifyWatching = false
        lastSpotifyListeningToastElapsed = 0L
        super.onListenerDisconnected()
    }

    /** Listener (re-)bind toast; debounced so connect + immediate ranking/posted do not duplicate. */
    private fun showSpotifyListeningToast() {
        muteHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (lastSpotifyListeningToastElapsed != 0L &&
                now - lastSpotifyListeningToastElapsed < SPOTIFY_LISTENING_TOAST_DEBOUNCE_MS) {
                return@post
            }
            lastSpotifyListeningToastElapsed = now
            Toast.makeText(
                applicationContext,
                getString(R.string.toast_listener_connected),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Toast once per listener bind when Spotify is visible via notification and/or media session. */
    private fun toastIfSpotifyDetectedFirstTime(active: Array<StatusBarNotification>) {
        if (announcedSpotifyWatching) return
        val seesTray = active.any { it.packageName == SPOTIFY_PACKAGE }
        if (!seesTray && !hasSpotifyMediaSession()) return
        announcedSpotifyWatching = true
        showSpotifyListeningToast()
    }

    private fun beginMuteSession() {
        muteHandler.removeCallbacks(unmuteRunnable)
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
        const val ACTION_CANCEL_IDLE_SHUTDOWN = "com.addev.hushify.action.CANCEL_IDLE_SHUTDOWN"
        /** Triggers [SpotifyAdMuteService.scheduleIdleCloseIfNeeded] from UI when prefs change. */
        const val ACTION_PROBE_IDLE_SHUTDOWN = "com.addev.hushify.action.PROBE_IDLE_SHUTDOWN"

        private const val STREAM_BLUETOOTH_SCO_LEGACY = 6
        private const val EMPTY_REEVALUATION_MS = 450L
        private const val UNMUTE_DELAY_MS = 300L
        private const val LISTENER_CONNECTED_RETRY_MS = 1_500L
        /** No Spotify tray notification and not PLAYING for this long ⇒ optional auto-stop. */
        private const val IDLE_CLOSE_AFTER_MS = 15 * 60 * 1000L
        private const val SPOTIFY_LISTENING_TOAST_DEBOUNCE_MS = 2_500L
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
