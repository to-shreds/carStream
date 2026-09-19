# Canonical web snapshot

This directory is build input for CarStream 2.0, not an independently maintained UI. `generated/` is regenerated only from the full canonical commit in `TORBOX_WEB_REVISION`. Edit application behavior in `to-shreds/torbox-web-player`, never here.

Current development pin: `f5fd321ac72f05e7c2524266001a2689e72bf0d2`, on the canonical project's `carstream-runtime` branch, derived from the exact v1.0.0 baseline `0b810f493cd19b2210a6bc4525a7c33cb486cfd5`. The production `browser-key-clone` branch and live frontend/backend were not changed by this milestone.

Regenerate explicitly with a full local canonical clone:

```sh
python scripts/sync_torbox_web.py --canonical-repo ../torbox-web-player --update
python scripts/sync_torbox_web.py --canonical-repo ../torbox-web-player --check
CARSTREAM_CANONICAL_REPO=../torbox-web-player python -m unittest discover -s validation -p test_web_sync.py
```

The sync command reads both the builder and all frontend bytes from pinned Git objects. Dirty canonical working files cannot change the result. It verifies the full revision, asset inventory, byte lengths, SHA-256 hashes, runtime contract and local-only CSP. Check mode rejects missing, modified or extra assets. Update stages a replacement and restores the previous snapshot on a failed final rename. Unmanaged directories, symlinks and unrelated Android assets are not overwritten.

## Integration boundary still to implement

This is a staged snapshot, not a working CarStream 2.0 APK. The v1.5.0 Android implementation, package and fallback remain unchanged. Android does not yet package or serve this snapshot. The next host integration must copy these exact generated assets into the APK and serve them locally, implement `/carstream/host.js`, and hydrate durable phone storage plus a real phone-side Parent PIN service before the canonical app can boot.

The new runtime deliberately fails closed if these phone services are absent. It must not fall back to browser-local parental state. The phone gateway must implement paired same-origin `/tw/api/...` actions, sanctioned Render native sessions, approved opaque artwork, opaque Range-capable media, per-tablet identities, durable state and server-enforced parental authorization. Do not change the primary QR or claim offline playback until that path exists and is tested.

The generated CSP permits required scripts, styles, API traffic, media and images only from the local origin. This is not yet an observed real-tablet zero-external-request result. Physical acceptance and Mint metering remain unverified.
