# CarStream repository migration verification

Verified 2026-09-19. The authoritative source is now `to-shreds/carStream`, branch `main`. ProjectStatus retains historical release archives and readiness, not a competing editable source tree.

## Immutable baseline

- Imported commit: `20d5102a2def828c40e923c4f8ec72aef73ccc0a`.
- Source archive SHA-256: `cc05a9428424ac347cbef4b700f98970c808e11fc047c7ef41117c029dd2e553`.
- All 72 original files imported byte-for-byte. The per-file inventory is `docs/baseline-provenance.json`.
- Package `com.carstream.app`, version 1.5.0, version code 25, minimum/target API 29, compile API 35 unchanged.
- No signing key, account API key, or plaintext private download grant was committed. The one-time import workflow has been removed after successful migration.

## Rebuild and regression evidence

- Repository build commit: `8be2d99afe7c8ec7a8fdd49147371383d6a15b7c`.
- Successful GitHub Actions run: https://github.com/to-shreds/carStream/actions/runs/35463720294 .
- All 30 Android Java sources compiled with the original SDK/ECJ toolchain.
- 25 production JVM HTTP/relay checks passed, including full GET, exact Range, HEAD, authorization/revocation and slot cleanup.
- 11 Chrome DOM/media logic groups and four Stremio setup DOM scenarios passed.
- Original APK SHA-256 verified: `08f7660124bf88e6e52c3d00df7efee2cbbb1326d22ebe85861498ba33ff6518`.
- Every non-signature entry in the repository-built APK, including classes.dex, AndroidManifest.xml, resources and all web assets, is byte-identical to that original APK.
- Repository-built unsigned artifact was signed locally with the original private key, outside GitHub and Actions. Both old and rebuilt signatures verified with certificate SHA-256 `57097bb3ab2cc2d64276801127f396bd2455894b6c18025aff48e26f9612d116`.
- Locally signed verification APK SHA-256: `92838de5738077c3ab74bbc783165b3d6696931273df45000bbd3560c6022fd1`. Its signature packaging differs, so whole-APK byte equality is not claimed.
- CI uses an ephemeral test signing identity solely to exercise the unchanged build script. Only the unsigned APK is published by CI. Never distribute a CI-test-signed APK as a CarStream update.

The first migration workflow imported the baseline successfully but stopped before compilation because sdkmanager was not on PATH. The subsequent CI workflow explicitly sets up the SDK and passed. Historical passing-result files were removed before rerunning tests so they could not be mistaken for fresh results.

## Limits

No physical Android installation, phone hotspot, tablet playback, codec, live TorBox or Mint metering test was performed. Preserving package, payload and signing certificate establishes the build/update identity, not physical acceptance. CarStream 2.0 integration remains in progress.
