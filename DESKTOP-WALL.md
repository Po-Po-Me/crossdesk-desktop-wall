# Desktop Wall (桌面墙) — Android fork

This fork is [kunkundi/crossdesk](https://github.com/kunkundi/crossdesk) **v1.7.7** (AGPL-3.0) plus a desktop-wall feature for the Android controller.

## What the wall does

The Android home screen gets a 桌面墙 button. It shows one card per saved desktop with a live picture. **Preview never sends input**: the preview peer connects with audio muted and no `pointer`/`key`/`text` calls. Tapping a card takes control of that desktop, reusing the live peer (no second password prompt).

Design points:

| Concern | Behaviour |
| --- | --- |
| Media budget | One decode surface for the whole wall; the streaming peer is handed from tile to tile every 4.2 s (dwell restarts on the first frame). No N-way fan-out. |
| Cadence | Subscribes to `deskhub_preview` (fps 1 wall / 15 remote) at 1440×810 and 2.5 Mbps when the host advertises `supports_deskhub_preview`; older hosts keep their own rate. |
| No input | Preview peers run with `previewOnly()`, no `AudioPlayer`, no input API calls. |
| Handoff | `NativeSession.leaveWall()` releases the wall surface and asks for an interactive cadence; `MainActivity.beginControl()` adopts the same peer. |
| Battery | Wall hidden (`onPause`, leaving the page) subscribes fps=0; the page also has a pause button. |

## Changed files (vs upstream v1.7.7)

```
apps/android/app/src/main/java/cn/crossdesk/mobile/
  DesktopWallController.java  new  wall orchestration: members, round-robin, dwell, backoff, handoff
  PreviewSession.java         new  one preview peer: connect, cadence, pause, handoff
  WallTile.java               new  card: still frame, caption/state strip, live window
  MirrorTexture.java          new  the wall's single decode SurfaceTexture
  NativeSession.java          add  previewOnly/useMirror/attachMirror/leaveWall/previewStream/previewAccepted,
                                   event 11 (first frame), event 4 {type:17} (preview verdict)
  MainActivity.java           add  toolbar entry, wallPage(), beginControl(), lifecycle pause
  MobileUi.java               add  selectable(alpha) press overlay
  L10n.java                   add  applicationContext()
apps/android/native/
  rtc_bridge.cpp              add  nPreview (deskhub_preview), event 11, preview verdict forwarding
  controller_protocol.h       add  PreviewSubscriptionMessage()
localization/catalog.json     add  13 wall strings × 10 languages (then `python3 localization/generate.py`)
.github/workflows/wall-apk.yml      build + sign + release APK
.github/workflows/upstream-sync.yml weekly merge of upstream master (skipped on conflict)
```

## Build

CI does everything: `.github/workflows/wall-apk.yml` runs on every push to `master` (and manually via **Actions → Build wall APK → Run workflow**). It installs NDK 28.2.13676358 / SDK 36, xmake 3.1.1, builds the native MiniRTC library, assembles debug + release, verifies both APKs, then publishes to **Releases** as `wall-v<version>[-patch]-<YYYYMMDD>`.

Signing secrets (optional — without them the debug APK is published):

| Secret | Meaning |
| --- | --- |
| `WALL_KEYSTORE_BASE64` | `base64 -w0 release.keystore` |
| `WALL_KEYSTORE_PASSWORD` | keystore password |
| `WALL_KEY_ALIAS` | key alias |
| `WALL_KEY_PASSWORD` | key password (defaults to the keystore password) |

Create the keystore once:

```bash
keytool -genkeypair -v -keystore release.keystore -alias wall \
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
base64 -w0 release.keystore | pbcopy   # paste as WALL_KEYSTORE_BASE64
```

Local build (same toolchain as upstream):

```bash
git clone --recursive https://github.com/Po-Po-Me/crossdesk-desktop-wall.git
cd crossdesk-desktop-wall/apps/android
python3 scripts/build_native.py          # needs xmake + Android SDK/NDK
./gradlew :app:assembleDebug
```

## Known limits

- The wall shows **one live picture at a time** (round-robin). True simultaneous live tiles need the host to send several streams for one peer, which v1.1.38 servers do not do.
- Hosts older than the `deskhub_preview` capability still stream, but the wall cannot throttle them.
- Desktops without a saved password only show the cached still frame; tapping them falls back to the password sheet.
- Licence: this fork stays AGPL-3.0, same as upstream; the wall code is published under the same licence.
