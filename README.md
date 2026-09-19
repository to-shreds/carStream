# CarStream 1.5.0

One Android phone host, with Chrome watching and an experimental local Stremio add-on for Android tablets. The phone fetches TorBox video using its normal Internet route while tablets receive video over Android LocalOnlyHotspot. Ordinary Internet tethering is not enabled by this app.

## Try Stremio

Follow INSTALL.txt. Install/open Stremio on home Wi-Fi first, then join CarStream Wi-Fi. The new **Stremio setup** button shows a QR for a local page. Copy the full HTTP manifest address from that page into Stremio's add-on URL installer. Open **CarStream Shows** or **CarStream Movies**. Only ready TorBox files are listed; the APK contains no videos.

This experiment supplies catalogs, metadata, title cards and stream URLs locally. It has passed automated server and relay tests, but actual Stremio installation and playback without tablet Internet need a device check. Stremio's unrelated catalogs or account services may still require Internet.

CarStream's browser parent lock, remote playback controls, Smart Skip and watch progress do not apply to Stremio. Stremio manages its own playback and auto-next settings. Download addresses can change when the local hotspot restarts; reinstall the add-on from a fresh QR if needed. Resetting the phone's pairing code also revokes Stremio links. The manifest address contains a private local token and may sync with a Stremio account.

## Chrome watching

The version 1.4.0 browser experience is retained: **1. Join Wi-Fi**, **2. Watch QR**, optional screen name, browse-first episode list, large player, automatic resume, and **More > Auto next / Start over**. **Episodes** returns to browsing; **Back to video** resumes. Elena of Avalor sorts first when present in the existing library.

## Build

Package `com.carstream.app`, version 1.5.0, code 25. Minimum/target API 29; compile API 35. Android Studio/Gradle is supported. The standalone `scripts/build_release.py` uses official Android build tools 35.0.0, platform 35, Java 17 and Eclipse ECJ 3.33.0. Set ANDROID_BUILD_TOOLS, ANDROID_JAR, ECJ_JAR, CARSTREAM_KEYSTORE, CARSTREAM_KEY_ALIAS, CARSTREAM_STORE_PASS and CARSTREAM_KEY_PASS. The signing key is excluded and retained separately in CarStream-Update-Key-KEEP-PRIVATE-v0.2.1.zip.

## Verification

See validation/BUILD-REPORT.md and validation/stremio-results.json. The current APK builds, signs and passes 25 production HTTP/relay checks, four setup-page logic scenarios and 11 Chrome logic groups. Physical Android/Stremio playback and appearance remain unverified.
