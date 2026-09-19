# CarStream 2.0 product and ownership contract

## Product

The phone hosts the canonical TorBox Web Player over Android LocalOnlyHotspot. Tablets use Chrome on a normal local IP network with no Internet. All required assets, API calls, artwork and video terminate at the phone. The phone uses its own Internet connection. Do not enable ordinary Internet tethering or replace LocalOnlyHotspot with Wi-Fi Direct without a demonstrated problem.

TorBox Web Player owns the application and user-facing playback logic. CarStream owns the Android host, pairing, network routing, local gateway, approved image relay, media byte relay, durable phone storage, phone-side Parent PIN and environment capabilities. Do not merge the repositories or maintain an independently edited UI copy.

Canonical web baseline: `to-shreds/torbox-web-player`, branch `browser-key-clone`, v1.0.0 commit `0b810f493cd19b2210a6bc4525a7c33cb486cfd5`. Its unchanged baseline reproduces 241 tests: 235 passed, six optional skips, zero failures. Normal frontend and Render backend remain separate from this runtime.

## Nonnegotiable boundaries

- Preserve `com.carstream.app` and the original private signing identity. Keep the key out of repositories and Actions.
- Bundle a generated, revision-pinned frontend snapshot. Upstream UI changes belong in the canonical repository; generated files are not hand edited.
- Use the existing runtime module as the API/media/image/auth/storage/capability boundary, not scattered product forks.
- Keep Render as the discovery/preparation control plane. Add explicit native-phone authentication; never spoof an approved browser Origin or weaken browser origin/CSRF checks.
- Local pairing authorizes tablet requests. Pairing reset revokes old sessions and media access.
- Never return TorBox keys, Render bearer credentials or raw signed CDN URLs to tablets. Use opaque local media and image identifiers.
- Gateway methods/routes are allowlisted. Artwork hosts and redirects are allowlisted. No arbitrary URL proxy or general Internet access.
- Reuse the proven relay with GET, HEAD, exact byte ranges, cancellation, bounded concurrency, upstream cleanup and recovery.
- CarStream CSP permits required requests only to the local origin.
- LAN HTTP cannot assume secure-context APIs. Phone verifies a derived Parent PIN; PIN changes require the old PIN. Wake lock is optional, PWA/Drive OAuth controls capability-gated.
- Browser origins change with hotspot IP. Persist viewer, resume, settings and parental limits/usage on the phone. Loss of browser storage must not bypass Kid Mode.
- Keep existing Chrome and optional Stremio routes as fallback until physical acceptance. Do not silently leave a parental-control bypass through a fallback route.

## Canonical behavior to preserve

Search: empty means Home; nonempty immediately hides home rows; results directly below search; debounced typing; Enter/Search executes and blurs; clearing restores Home. Simple is default; Full retains supported power controls; Kid Mode forces Simple. Preserve My List, history, source learning/quality preferences, Continue Watching with 10-second rewind, Next Up, released-episode auto-next, source recovery and two-tablet independence.

Still Watching is independent of Kid Mode, defaults to 90 minutes, offers Off/60/75/90/120, and carries across auto-next. Retain sleep timer.

Kid Mode is per viewer with combined custom time/episode/movie allowances, zero unlimited, daily/manual reset. Only actual advancing playback counts; pause/stall/seek jumps do not. Episodes charge at five actual watched minutes or 20% of runtime, whichever comes first; movies at ten actual watched minutes. Charged items may finish/resume after count exhaustion; a different item is blocked. Time exhaustion pauses immediately. Parent extensions are 15/30 minutes, one episode/movie, reset or disable. Protect settings, viewer changes and sign-out through phone-side authorization.

## Sequence and acceptance

Phase 0 is verified in BASELINE-VERIFICATION.md. Then runtime contract, local assets/CSP, authenticated Render gateway, artwork relay, media integration, durable state/Parent PIN, complete behavior regression, physical acceptance, optional carrier experiment.

Automated evidence must cover local asset inventory/revision/CSP, supported API shapes and auth, sanitized errors/credentials, image allowlists/cache, full/HEAD/Range/cancellation/cleanup, unchanged normal runtime, secure-context fallback, durable state/restart/concurrent tablets, parental authorization, and focused-search/keyboard behavior.

Physical acceptance remains mandatory: phone starts LocalOnlyHotspot while Android tethering is off; tablet reports no Internet; dynamically generated Join Wi-Fi/Open Player QRs work; canonical UI loads; discover a title not already in the library; prepare/play with picture and sound; seek both directions; pause/resume; reload and continue; Android Search dismisses keyboard; auto-next; Still Watching; time and episode limits; parent extension; Chrome and hotspot restart preserve state; second tablet works concurrently; neither tablet gets general Internet; client responses contain no upstream credentials or signed URLs.

Do not declare 2.0 complete from CI, JVM or simulated-browser tests. Mint separate hotspot classification is unverified. A later controlled byte-count transfer and manual before/after Mint hotspot counter reading is diagnostic, not a core dependency. Do not add Mint credentials or private-API dependence.

## Readiness

`to-shreds/ProjectStatus/projects/carstream/STATUS.md` controls CarStream readiness and next steps. Canonical web readiness stays in `projects/torbox-web-player-browser-key/STATUS.md`. Update each when its owned behavior changes. Source archives in ProjectStatus are historical only after migration.
