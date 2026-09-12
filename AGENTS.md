# AGENTS.md

Guidance for AI coding agents working in this fork of Immich.

## Repository layout

- `mobile/` — Flutter app (Android/iOS). Main work area of this fork.
- `server/` — NestJS API server. `web/` — SvelteKit web app. `i18n/` — translations.
- `shell.nix` — nix-shell with the whole mobile toolchain (Flutter 3.47.2, Android
  SDK 33-36 + NDK 28.2.13676358 + CMake 3.22.1, JDK 21). `.env/tools/` holds local
  performance-profiling artifacts; gitignored.

## Fork-specific feature: Android Ultra HDR viewer

This fork adds Google Photos-style Ultra HDR (JPEG + gain map) rendering to the
Android asset viewer. Flutter cannot render HDR, so the photo is drawn by a
native window-level overlay above FlutterView (pure HWUI, which applies the
gain map when `window.colorMode = COLOR_MODE_HDR`).

Key files:

- `mobile/android/app/src/main/kotlin/app/alextran/immich/hdr/`
  - `UltraHdrPlugin.kt` — MethodChannel `immich_ultra_hdr_overlay`; HDR colorMode
    ref-counting and gradual brightness via `desiredHdrHeadroom` ramp (log-space,
    ease-in-out). Never fade HDR views via alpha: offscreen layers flatten the
    gain map.
  - `UltraHdrImageView.kt` — `HdrOverlayLayout` (overlay attached to
    `android.R.id.content`, touch arbitration: pinch/double-tap/zoomed are native,
    everything else forwarded verbatim to FlutterView) and `ZoomableHdrView`
    (matrix zoom/pan/fling, chrome clipRect so Flutter bars show through).
  - `HdrImagesImpl.kt` — Pigeon `HdrImageApi`: gain-map probing, remote original
    download cache (`cacheDir/uhdr`, sha1 keys, `nohdr` markers, 512 MB LRU).
- `mobile/lib/presentation/widgets/asset_viewer/ultra_hdr_image.widget.dart` —
  Dart bridge (`UltraHdrSession`).
- `mobile/lib/providers/asset_viewer/ultra_hdr.provider.dart` — source resolution
  provider and viewer state providers.
- `mobile/lib/presentation/widgets/asset_viewer/asset_page.widget.dart` — gating:
  the overlay only shows when the page is current, route is idle/topmost, and no
  details/OCR/stack/dialog/menu covers it. Anything drawn by Flutter above the
  photo must gate the overlay off (dialogs drop `route.isCurrent`; MenuAnchor
  overlays must signal via `viewerMenuOpenProvider`).
- `mobile/pigeon/hdr_image_api.dart` — Pigeon definition; generated files are
  `mobile/lib/platform/hdr_image_api.g.dart` and `hdr/HdrImages.g.kt`.

Behavioral contract (validated on a 120 Hz Android 16 device):

- Page swipes are Flutter-driven; the overlay slides in lockstep via
  `setSwipeOffset` so the outgoing photo keeps HDR brightness.
- Landing on a new page waits ~350 ms, then the photo fades in and highlights
  ramp up over ~800 ms.
- Do NOT reintroduce platform views for the photo: hybrid composition is
  pathologically slow during Flutter animations on test hardware.

## Mobile build & test

All mobile tooling comes from the repo-root `nix-shell` (Flutter 3.47.2, Android
SDK, JDK 21), so no env vars need to be exported. Run the codegen commands below
whenever generated code is missing or stale (a fresh checkout has none of it).

```sh
nix-shell                                    # from the repo root
cd mobile
dart run build_runner build                  # freezed/drift/router codegen
dart run drift_dev make-migrations           # lib/data/db/main/database.steps.dart
dart run drift_dev schema generate --data-classes --companions drift_schemas/main/ test/drift/main/generated/
dart run easy_localization:generate -S ../i18n && dart run bin/generate_keys.dart
ls pigeon/*.dart | xargs -n1 -P4 -I{} dart run pigeon --input {}
dart analyze --fatal-infos
flutter build apk --release --target-platform android-arm64 --split-per-abi
adb install -r build/app/outputs/flutter-apk/app-arm64-v8a-release.apk
```

The generated OpenAPI client lives in `mobile/generated/openapi`; regenerate it
from `open-api/` with `bash ./bin/generate-dart-sdk.sh` (the shell provides a
pinned `openapi-generator-cli`). The other codegen steps above run from `mobile/`.

Debug builds are too slow for perf testing; always verify HDR/scroll smoothness
with a release build on a real device.

## Conventions

- Commit style: conventional commits (`feat(mobile): ...`, `fix: ...`).
- iOS must remain untouched by HDR work; all HDR code paths are Android-gated
  (`CurrentPlatform.isAndroid`, `@RequiresApi(34)`).
- New user-facing strings go through `i18n/en.json`.
