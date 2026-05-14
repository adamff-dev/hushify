package com.addev.hushify

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatterySaver
import androidx.compose.material.icons.filled.VolunteerActivism
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.addev.hushify.ui.theme.HushifyTheme

class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val deferredAutoOpenSpotify = Runnable {
        openSpotify(showInstallHint = false)
    }

    private val postNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefsForLaunchCheck = getSharedPreferences(PREFS_FILE, MODE_PRIVATE)
        if (savedInstanceState == null && prefsForLaunchCheck.getBoolean(KEY_AUTO_OPEN_SPOTIFY, false)) {
            Toast.makeText(this, R.string.toast_opening_spotify, Toast.LENGTH_SHORT).show()
            mainHandler.postDelayed(deferredAutoOpenSpotify, AUTO_OPEN_SPOTIFY_DELAY_MS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            val prefs = remember { getSharedPreferences(PREFS_FILE, MODE_PRIVATE) }
            var autoOpenSpotify by remember {
                mutableStateOf(prefs.getBoolean(KEY_AUTO_OPEN_SPOTIFY, false))
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            var listenerEnabled by remember { mutableStateOf(syncListenerAndKeepAlive()) }
            var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations()) }

            DisposableEffect(lifecycleOwner) {
                listenerEnabled = syncListenerAndKeepAlive()
                batteryUnrestricted = isIgnoringBatteryOptimizations()
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        listenerEnabled = syncListenerAndKeepAlive()
                        batteryUnrestricted = isIgnoringBatteryOptimizations()
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            HushifyTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(
                        listenerEnabled = listenerEnabled,
                        batteryUnrestricted = batteryUnrestricted,
                        onGrantClick = { openNotificationListenerSettings() },
                        onBatteryClick = { openBatteryOptimizationExemption() },
                        onDonateClick = {
                            startActivity(Intent(this, DonationActivity::class.java))
                        },
                        onAboutClick = {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/adamff-dev/hushify")
                                )
                            )
                        },
                        onOpenSpotifyClick = { openSpotify(showInstallHint = true) },
                        autoOpenSpotifyEnabled = autoOpenSpotify,
                        onAutoOpenSpotifyChange = { enabled ->
                            prefs.edit().putBoolean(KEY_AUTO_OPEN_SPOTIFY, enabled).apply()
                            autoOpenSpotify = enabled
                            if (!enabled) {
                                mainHandler.removeCallbacks(deferredAutoOpenSpotify)
                            }
                        },
                        onExitClick = { closeAppUi() },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(deferredAutoOpenSpotify)
        super.onDestroy()
    }

    /** Stops foreground keep-alive, unbinds the notification listener, and removes this task from recents. */
    private fun closeAppUi() {
        HushKeepAliveService.stop(applicationContext)
        SpotifyAdMuteService.sendFullStop(this)
        finishAndRemoveTask()
    }

    private fun syncListenerAndKeepAlive(): Boolean {
        val enabled = NotificationAccess.isSpotifyMuteListenerEnabled(this)
        if (enabled) {
            NotificationAccess.requestListenerReconnect(this)
            HushKeepAliveService.start(this)
        } else {
            HushKeepAliveService.stop(this)
        }
        return enabled
    }

    private fun openNotificationListenerSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openBatteryOptimizationExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (_: Exception) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
            )
        }
    }

    private fun openSpotify(showInstallHint: Boolean): Boolean {
        val launch = packageManager.getLaunchIntentForPackage(SpotifyAdMuteService.SPOTIFY_PACKAGE)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (launch != null) {
            startActivity(launch)
            true
        } else {
            if (showInstallHint) {
                Toast.makeText(this, R.string.toast_spotify_not_installed, Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    companion object {
        private const val PREFS_FILE = "hushify_prefs"
        private const val KEY_AUTO_OPEN_SPOTIFY = "auto_open_spotify"
        private const val AUTO_OPEN_SPOTIFY_DELAY_MS = 2_800L
    }
}

@Composable
private fun MainScreen(
    listenerEnabled: Boolean,
    batteryUnrestricted: Boolean,
    onGrantClick: () -> Unit,
    onBatteryClick: () -> Unit,
    onDonateClick: () -> Unit,
    onAboutClick: () -> Unit,
    onOpenSpotifyClick: () -> Unit,
    autoOpenSpotifyEnabled: Boolean,
    onAutoOpenSpotifyChange: (Boolean) -> Unit,
    onExitClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(horizontal = 24.dp)
            .padding(top = 36.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.main_tagline),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                StatusRow(
                    ok = listenerEnabled,
                    icon = if (listenerEnabled) {
                        Icons.Outlined.NotificationsActive
                    } else {
                        Icons.Outlined.NotificationsOff
                    },
                    label = stringResource(R.string.status_group_notifications),
                    value = stringResource(
                        if (listenerEnabled) R.string.status_listener_on else R.string.status_listener_off
                    )
                )
                if (listenerEnabled) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                    )
                    StatusRow(
                        ok = batteryUnrestricted,
                        icon = if (batteryUnrestricted) {
                            Icons.Outlined.BatteryChargingFull
                        } else {
                            Icons.Filled.BatterySaver
                        },
                        label = stringResource(R.string.status_group_battery),
                        value = stringResource(
                            if (batteryUnrestricted) {
                                R.string.battery_status_ok
                            } else {
                                R.string.battery_status_restricted
                            }
                        )
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.pref_auto_launch_spotify),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = autoOpenSpotifyEnabled,
                onCheckedChange = onAutoOpenSpotifyChange
            )
        }

        if (!listenerEnabled) {
            Button(
                onClick = onGrantClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.cta_open_listener_settings))
            }
            Text(
                text = stringResource(R.string.battery_need_listener_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else if (!batteryUnrestricted) {
            OutlinedButton(
                onClick = onBatteryClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.BatterySaver,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(stringResource(R.string.cta_disable_battery_optimization))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = onDonateClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.VolunteerActivism,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.donate))
        }

        TextButton(
            onClick = onAboutClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.about))
        }

        TextButton(
            onClick = onExitClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Outlined.StopCircle,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.cta_exit_app))
        }

        Button(
            onClick = onOpenSpotifyClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(stringResource(R.string.cta_open_spotify))
        }
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = if (ok) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            }
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    HushifyTheme {
        MainScreen(
            listenerEnabled = false,
            batteryUnrestricted = false,
            onGrantClick = {},
            onBatteryClick = {},
            onDonateClick = {},
            onAboutClick = {},
            onOpenSpotifyClick = {},
            autoOpenSpotifyEnabled = false,
            onAutoOpenSpotifyChange = {},
            onExitClick = {}
        )
    }
}
