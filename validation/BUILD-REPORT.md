# CarStream 1.5.0 Stremio experiment

Package: `com.carstream.app`, versionCode 25, minimum/target Android API 29, compile API 35.
APK: 206,521 bytes. SHA-256: `08f7660124bf88e6e52c3d00df7efee2cbbb1326d22ebe85861498ba33ff6518`.
Signing certificate SHA-256: `57097bb3ab2cc2d64276801127f396bd2455894b6c18025aff48e26f9612d116`, identical to the earlier CarStream releases.

## What changed

- Added a phone-hosted Stremio add-on: manifest, Shows/Movies catalogs, local metadata with episode lists, local PNG title cards, and direct video relay URLs.
- Ready files are grouped by TorBox download. Filename season/episode markers take priority over download-name markers; unnumbered collections use list positions. Existing Elena downloads sort first.
- The native Home screen has a Stremio setup QR. Its local setup page checks the add-on and copies the full HTTP manifest address for manual installation in Stremio Android.
- A persistent random 128-bit-format UUID token protects all Stremio resources, including video. Resetting the pairing code revokes it. Returned URLs use the server socket address, not the request Host header.
- Existing Chrome assets, upstream routing and media relay implementation remain unchanged. No TorBox API keys or remote video URLs are returned by the add-on.

## Tests passed

- All 30 app Java sources compiled; Android resource linking, D8, APK signing and verification succeeded.
- APK ZIP integrity passed. Every packaged asset and the packaged DEX match the final source/build outputs.
- 25 JVM integration checks ran the actual production LocalHttpServer, StremioAddon, TorBox download-cache path and byte relay over real loopback HTTP sockets. They covered token enforcement and revocation, methods/CORS/HEAD, catalog filtering and encoded search, ordering, missing/unready/wrong-type media, local URLs, hostile Host headers, full bytes, exact byte ranges, and stream-slot cleanup.
- The JVM test supplies a fixture library, fake preferences and a local upstream HTTP server. It exercises no TorBox account, Android network services or video codec.
- Four simulated DOM setup states passed: populated catalog, empty catalog, unreachable phone and clipboard fallback. Eleven existing Chrome DOM/media logic groups passed again.

## Compatibility evidence

- Official Stremio core commit `4c6b44ed8cfc4264c4724e187a63da7a3d0f7ff2` preserves explicit HTTP URLs in `src/models/addon_details.rs`; only the stremio:// scheme is rewritten to HTTPS. Its HTTP transport retains the token path. Video release dates are optional in its current types, so this adapter does not invent air dates.
- Static inspection of the official Android Mobile 2.3.2 ARM64 APK (versionCode 7145730) found `android:usesCleartextTraffic=true` and no application networkSecurityConfig override. This removes a manifest-level HTTP concern but does not prove installation or offline behavior.
- Official add-on SDK docs checked at commit `2728da3ee853207cd5ee200aabe15a08cc1d01d1`.
- References: [protocol](https://github.com/Stremio/stremio-addon-sdk/blob/2728da3ee853207cd5ee200aabe15a08cc1d01d1/docs/protocol.md), [transport URL handling](https://github.com/Stremio/stremio-core/blob/4c6b44ed8cfc4264c4724e187a63da7a3d0f7ff2/src/models/addon_details.rs), [official downloads](https://www.stremio.com/downloads).

## Not verified

No physical Android installation, Stremio UI interaction, offline add-on installation/account startup, QR scanning, local-only Wi-Fi connection, live TorBox playback, real video decoding or visual rendering was tested. Prior browser execution and local-preview access restrictions still apply; no browser or artwork screenshot pass is claimed. Tablet codec compatibility and Stremio automatic-next behavior remain device checks. This is not proof of how a carrier meters phone traffic.

Use the native Stremio Android app for this experiment. The web client is not the target. Keep Chrome watching available using Watch QR. CarStream browser remote controls, parent lock, Smart Skip and browser progress do not control Stremio.

## Reproduce

Build with `scripts/build_release.py` and the environment variables in README.md. For JVM tests, set JSON_JAR to org.json 20240303 and run `python validation/run_stremio_test.py` after building. For simulated DOM tests, install the pinned dependency from validation/package.json and run `node validation/test_logic.cjs` and `node validation/test_stremio_setup.cjs`. The harness uses JVM-only reflection to supply Android preferences and a fixture TorBox cache; it is never packaged in the APK.
