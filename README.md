# Hushify

<p align="center">
  <img src="art/ic_launcher.svg" alt="Hushify Logo" width="128" height="128" />
</p>

A lightweight, open-source, privacy-first Android utility designed to **automatically mute Spotify advertisements** in real-time. By utilizing deep on-device notifications analysis and media session heuristics, Hushify detects advertisements in seconds and lowers system media volumes to zero. 

Hushify operates **completely on-device** without requiring Spotify account linking, API access, or internet permissions.

---

## 🚀 Key Features

* **Instant Ad Muting**: Dynamically lowers the multimedia stream (`STREAM_MUSIC`) and Bluetooth SCO channels (volume index 6) to zero the millisecond an advertisement begins.
* **Smart Volume Restoration**: Captures your current playback volume right before muting and restores it perfectly when the advertisement ends.
* **Dual-Channel Protection**: Seamlessly mutes wired/wireless headsets, Bluetooth speakers, and native device speakers.
* **Advanced Multi-Heuristic Detection**:
  * **System Flags**: Scans standard Android `MediaMetadata.METADATA_KEY_ADVERTISEMENT` markers.
  * **Media URI Analysis**: Inspects player tracks for Spotify's internal ad identifier prefix (`spotify:ad:`).
  * **Multilingual Heuristic Engine**: Scans text payloads against 51 unique localized ad phrases (extracted from official Spotify APK resources) spanning dozens of languages.
* **Anti-Latency Framework**: Eliminates audio bleed-through using dynamic audio routing callbacks and high-priority notification triggers.
* **Resilient Playback Debouncing**: Avoids unmuting during track changes or when playback is paused to prevent volume spikes.
* **Auto-Open Spotify**: Optional configuration to automatically launch the Spotify app immediately after opening Hushify.
* **Auto-Shutdown on Spotify Inactivity**: If Spotify is idle (e.g., you close the player or stop music) for 15 minutes, Hushify automatically stops its listener, releases background resources, and shuts down the UI to conserve battery.
* **Automatic Boot Recovery**: Optionally restarts the keep-alive listener on system reboot (`RECEIVE_BOOT_COMPLETED`) so you're always protected hands-free.
* **100% Privacy & Security**: Processes all heuristics locally on-device. No network access, no trackers, and no data collection.

---

## 🛠️ How It Works (Under the Hood)

1. **Service Registration**: Hushify uses Android's `NotificationListenerService` API to securely monitor notification events generated *only* by `com.spotify.music`.
2. **Media Session Hooking**: The service integrates with `MediaSessionManager` to capture real-time playback state updates and `MediaMetadata` changes directly from the media controllers.
3. **Multi-Heuristic Engine**: When a Spotify update occurs, the app runs three parallel checks:
   * Checks if the `android.media.metadata.ADVERTISEMENT` long value equals `1`.
   * Checks if the media identifier starts with `spotify:ad:`.
   * Runs an optimized, accent-folded text heuristic searching for matches in the notification title, text, subtext, actions, ticker text, and media session metadata against the compiled localized ad phrases dictionary.
4. **Volume Capture and Suppression**:
   * Upon ad detection, the app stores the current volume for `STREAM_MUSIC` and Bluetooth SCO.
   * Volume is immediately suppressed to `0`.
   * An active `AudioDeviceCallback` watches for audio hardware changes (e.g., plugging in headphones mid-ad) to re-apply the suppression instantly.
5. **Debounced Restore**: Once the ad signature clears and playback resumes as active, the volume is restored back to the cached levels.

---

## 📱 User Preferences & Options

Hushify features a modern, clean, edge-to-edge UI built entirely using **Jetpack Compose** that makes configuration a breeze:

* **Notification Access Toggle**: One-tap shortcut to grant special notification permissions.
* **Battery Restrictions Toggle**: Opens system settings to allow **Unrestricted** background processing, preventing the OS from killing the listener.
* **Open Spotify after opening Hushify**: When enabled, launching Hushify automatically starts Spotify so you can get straight to your music.
* **Close app when Spotify is idle**: Auto-terminates Hushify's foreground listener and UI after 15 minutes of Spotify inactivity to preserve resources.

---

## 📋 Requirements

* Android **7.0+** (API 24 or higher)
* Official **Spotify** Android app installed
* **Notification Access**: Must be enabled under **Settings → Apps → Special access → Notification access**
* **Unrestricted Battery Usage** (highly recommended for background longevity on aggressive OEM battery managers)

---

## 🔒 Permissions Breakdown

| Permission | Scope | Technical Purpose |
| :--- | :--- | :--- |
| `BIND_NOTIFICATION_LISTENER_SERVICE` | System | Reads Spotify notification text and metadata for ad heuristics. |
| `MODIFY_AUDIO_SETTINGS` | Audio | Adjusts stream volumes (`STREAM_MUSIC` & Bluetooth SCO) to mute and restore audio. |
| `FOREGROUND_SERVICE` | Keep-alive | Runs the keep-alive service so the application maintains persistence in the background. |
| `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` | Keep-alive | Adheres to Android 14+ (API 34) requirements for background listener classification. |
| `POST_NOTIFICATIONS` | Local UI | Enables Hushify to show the keep-alive notification and active-mute status on Android 13+. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Battery | Directs you to the system battery page to exempt Hushify from aggressive power savings. |
| `RECEIVE_BOOT_COMPLETED` | Boot | Restores the listening service seamlessly after device restart. |

---

## 🏗️ Build & Development

To build the APK from the command line:

```bash
./gradlew assembleDebug
```

Alternatively, open the project in **Android Studio** (Koala or newer recommended). The project is built using:
* Kotlin 1.9+
* Gradle 8.0+
* Jetpack Compose (Material 3)
* Kotlin DSL (`build.gradle.kts`) and Version Catalog (`gradle/libs.versions.toml`)

---

## ⚠️ Limitations & Disclaimers

* **Heuristics Dependent**: Ad detection relies entirely on what Spotify exposes via its system notification and media metadata. If an ad contains no advertisement flags, ad URIs, or localized ad texts, the app will not mute it.
* **Language Support**: Includes translations and heuristics for 51 languages, but may experience rare false positives or misses on custom modified Spotify clients or regional variants.
* **Affiliation**: Hushify is **not** affiliated, associated, authorized, endorsed by, or in any way officially connected with Spotify AB or any of its subsidiaries.

---

## 💝 Support the Project

Hushify is entirely open-source, free, and developed out of passion. If it makes your music experience better, consider supporting development and maintenance:

### 💳 Traditional Platforms
* **PayPal**: [Donate via PayPal](https://www.paypal.com/donate/?hosted_button_id=3T9XNAPWW36Z2)
* **Buy Me a Coffee**: [Buy Me a Coffee](https://www.buymeacoffee.com/rsiztb3)
* **Ko-fi**: [Support on Ko-fi](https://ko-fi.com/adamffdev)

### 🪙 Cryptocurrency Donations
If you prefer decentralized support, you can copy the wallet addresses below:

* **Bitcoin (BTC)**: `bc1qrcdyq2yjgv5alm9kky2e6vyfhnafn3wgd2gjls`
* **Ethereum (ETH)**: `0x43b9649985d6789452abe23beb1eb610cee88817`
* **Solana (SOL)**: `4qK7eSQemRj85VY9CQp5XHRwX5fNjoSJ1ou4gmqk6jtM`
* **Litecoin (LTC)**: `ltc1qp6mya23a73n36dc7r0tfwfphn2v53phmhen99j`

Your support is deeply appreciated! Thank you! 🙌

---

## 📄 License

This repository does not currently include a formal open-source license. Feel free to use the utility on your personal devices. If you plan to republish, fork, or accept external contributions commercially, please reach out or add an appropriate license file.
