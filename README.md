# TiviWave TV

An **Android TV-first IPTV application** built with **Kotlin**, **Jetpack Compose**, **Media3 ExoPlayer**, and **Room**, engineered specifically for 10-foot landscape TV screens and D-Pad remote navigation.

## Key Features

- **Android TV Native First**
  - Configured with `LEANBACK_LAUNCHER`, `android.software.leanback`, 16:9 landscape layout, `largeHeap="true"`, cleartext stream support, and custom TV Leanback banner + adaptive icon.
  - Full D-Pad remote control navigation (`UP`, `DOWN`, `LEFT`, `RIGHT`, `OK/CENTER`, `BACK`, `MENU`, `CHANNEL_UP/DOWN`) with glowing focus indicators and smooth zoom feedback.

- **Multi-Source IPTV Support**
  - **Stalker Portal (MAG/STB):** Supports Portal URL, MAC Address, Serial Number, Device ID1, Device ID2, Signature, MAG250 headers/cookies, `handshake`, `get_profile`, metadata-only category fetching, selective item import, short EPG, and dynamic `create_link` stream URL resolution (stripping `ffmpeg ` / `ffrt ` prefixes).
  - **Xtream Codes API:** Live TV, Movies (VOD), Web Series (with Seasons & Episodes), and XMLTV EPG.
  - **M3U / M3U8 (Remote URL & Local File):** Parses `#EXTINF` (`tvg-id`, `tvg-name`, `tvg-logo`, `group-title`, `tvg-chno`) and `#EXTVLCOPT` headers.
  - **GitHub Gist JSON:** Loads raw Gist JSON playlists (or M3U text hosted on Gist).

- **2-Step Country & Category Pre-Load Filter**
  - Step 1 fetches categories only across **Live TV**, **Movies (VOD)**, and **Web Series** and auto-groups them by Country/Region (`India`, `United Kingdom`, `United States`, `UAE & Middle East`, `Pakistan`, `Canada`, `Sports`, `Movies & Cinema`, `International`).
  - Step 2 imports strictly checked categories (completely excluding unchecked categories) and can be reopened anytime to edit selections.

- **Country / Proxy Routing Module**
  - Built-in `VpnService` (`TunnelVpnService`) with foreground notification + proxy-aware `OkHttpClient` supporting **HTTP** and **SOCKS5** proxy nodes, optional authentication, and latency testing.

- **TiviMate-Inspired UI & Hardware-Accelerated Media3 Player**
  - Collapsible TV Left Navigation Rail (`Live TV`, `Movies`, `Web Series`, `Favorites`, `Country / Proxy`, `Playlists & Filter`).
  - 3-Pane Live TV Guide: Selected Categories, Channel List with logos & EPG bars, and Live Media3 Preview + EPG Timeline.
  - 2:3 Poster Grids for Movies & Web Series with details view, Season/Episode lists, and automatic resume playback.
  - Full-screen Media3 player supporting HLS, DASH, RTSP, and TS streams with instant `UP`/`DOWN` channel zapping, bottom OSD banner (channel number, logo, current/next EPG, resolution/codec/bitrate), `OK` transparent channel & EPG overlay, audio/subtitle track switchers, aspect ratio toggle (`FIT`, `FILL`, `ZOOM`, `16:9`), and automatic reconnect.

## Remote Control Shortcuts (Player)

| Key | Live TV | Movies / Web Series |
| --- | --- | --- |
| **OK / Center** | Toggle transparent Channel & EPG Overlay | Play / Pause |
| **DPAD Up / Down** | Zap Previous / Next Channel + OSD Banner | Open Player Controls |
| **DPAD Left / Right** | Open Overlay (Left) / Track & Aspect Controls (Right) | Seek -15s / +15s |
| **Menu / Info** | Toggle Audio, Subtitles & Aspect Ratio Bar | Toggle Audio, Subtitles & Aspect Ratio Bar |
| **Back** | Close Overlay / Exit Player | Close Controls / Exit Player |

## Building the APK

1. Open the project in **Android Studio** (or trigger the included GitHub Actions workflow under **Actions → Build Android TV APK**).
2. Ensure a `debug.keystore` exists in the project root (or generate one with `keytool`):
   ```bash
   keytool -genkeypair -v -keystore debug.keystore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```
3. Build the debug APK:
   ```bash
   gradle :app:assembleDebug
   ```
   The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.
