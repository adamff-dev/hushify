# Hushify

Android app that **lowers system media volume** (and the Bluetooth SCO stream) when Spotify’s **media notification** looks like an ad, based on text heuristics. There is **no** Spotify API or account linking.

## How it works

1. You grant **notification access** to Hushify (notification listener).
2. Hushify watches notifications from `com.spotify.music` only.
3. When the notification payload (title, text, extras, actions, `MediaMetadata`, etc.) matches known ad-related phrases in several languages, volumes are set to **0**.
4. When the ad signal clears, volumes are **restored** to the levels captured just before muting.

A small **foreground service** helps the listener stay alive on aggressive OEM battery policies; you can grant **unrestricted battery** for Hushify if the system stops it.

## Requirements

- Android **7.0+** (API 24)
- Official **Spotify** Android app installed
- User must enable Hushify under **Settings → Apps → Special access → Notification access**

## Permissions (high level)

| Permission | Why |
|------------|-----|
| `BIND_NOTIFICATION_LISTENER_SERVICE` | Read Spotify notification content for ad heuristics |
| `MODIFY_AUDIO_SETTINGS` | Set stream volumes for mute / restore |
| `FOREGROUND_SERVICE` / `specialUse` (API 34+) | Keep-alive service type |
| `POST_NOTIFICATIONS` (API 33+) | Optional local notifications |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Open system UI to disable optimization for this app |
| `RECEIVE_BOOT_COMPLETED` | Optional restart path after reboot |

## Build

```bash
./gradlew assembleDebug
```

Open the project in **Android Studio** with a recent **AGP** / **Kotlin** toolchain matching `gradle/libs.versions.toml`.

## Limitations

- **Not 100% reliable.** Detection depends on what Spotify (and your language/build) puts in the notification. If there is **no** identifiable ad wording, Hushify cannot know an ad is playing.
- **Heuristic only** — false positives or misses are possible.
- **Not affiliated** with Spotify.

## Privacy

Processing is **on-device**. Hushify does not send notification content to a server as part of this design (verify the codebase if you extend it).

## License

No license file is included in this repository yet; add one if you publish or accept contributions.
