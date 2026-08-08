# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**HyperPods** is an Xposed/LSPosed module (package `io.github.zuohl.hyperpods`) that gives third-party Bluetooth earphones HyperOS system capabilities — popup, battery display, noise control, status-bar icon, super island. It is a multi-brand refactor of the upstream single-brand [Leaf-lsgtky/OppoPods](https://github.com/Leaf-lsgtky/OppoPods). Currently supports **OPPO / QCY / vivo(iQOO)** + generic passthrough. UI is HyperOS-style Compose using [Miuix](https://github.com/YuKongA/miuix).

## Build & run

- Build debug: `./gradlew :app:assembleDebug`
- Build release (minified + shrunk): `./gradlew :app:assembleRelease`
- There is **no test suite**.
- Gradle requires JDK 21 (foojay toolchain resolves it). Local build reads keystore props from `~/.gradle/gradle.properties` (see machine config note below).
- CI: `.github/workflows/build.yml` auto-builds, signs, and creates a GitHub release on **every push to main** (release/tag version derives from `git rev-list --count HEAD` → `3.0.<count>`). No manual tagging needed. Do not add a tag trigger back — it caused duplicate builds.

## Core architecture

### LSPosed entry & per-package hooks

`hook/HookEntry.kt` is the `XposedModule` entry (`java_init.list`). It routes each loaded process to a singleton `HookContext` implementation based on the **package name** (hot-reload normalizes to package via `processName.substringBefore(':')`):

| Process/package | Hook |
|---|---|
| `com.android.bluetooth` | `HeadsetStateDispatcher` |
| `com.milink.service` | `MiLinkServiceHook` |
| `com.xiaomi.bluetooth` | `MiBluetoothToastHook` |
| `com.android.settings` | `SettingsHeadsetHook` |

Scope (see `resources/META-INF/xposed/scope.list`): `com.android.bluetooth`, `com.milink.service`, `com.xiaomi.bluetooth`, `com.android.settings`. Module is `staticScope=true`, `autoHotReload=true` (API 102), so hot reload must clean up state in `onHotReloading`.

### Multi-brand router (the heart)

`pods/Pod.kt` defines `PodBrand { OPPO, QCY, VIVO, GENERIC }`, the `Pod` interface, and `PodStatusSnapshot` (battery + unified ANC level 1=Off…8=Deep + address/connected state).

- `PodDetector` — classifies a device by name keywords (OPPO/oneplus → QCY/crossky → vivo tws/iqoo). Order matters.
- `PodController` — brand-agnostic router. `connectPod` tears down the previous pod on brand switch, `disconnectedPod` is a no-op when no active pod (dedupes A2DP-hook + profile-receiver double calls), `isActivePod` prevents re-adoption by the periodic sync.
- Pod implementations each own their transport + protocol:
  - **`OppoPod` → `RfcommController`** (upstream OppoPods, largest; OPPO RFCOMM + device-model registry + MiRing/SpatialAudio)
  - **`QcyPod` → `QcyController`** (QCY BLE GATT + advertisement scan; `QcyProtocol`, `QcyEqPrefs`)
  - **`VivoPod` → `VivoController`** (vivo/iQOO GAIA over Bluetooth Classic RFCOMM; `VivoProtocol`)
  - **`PassthroughPod`** (generic/manual-MAC bind: connected state + popup + icon only, no protocol)

### How status reaches HyperOS

Controllers broadcast via `OppoPodsAction` intent constants and the `BatteryParams`/`PodParams` types (package `utils/miuiStrongToast/data/`). The hook layer (`MiBluetoothToastHook`, `MiLinkServiceHook`, `SettingsHeadsetHook`) reads `PodController.currentStatusSnapshot()` and injects battery/ANC into the MIUI Bluetooth, MiLink, and Settings UIs. Popups / super island / persistent notification go through `MiuiStrongToastUtil` (sends `chen.action.oppopods.sendstrongtoast` to `com.xiaomi.bluetooth`).

### Status-bar icon

SystemUI only shows the `wireless_headset` icon if the device is marked `is_untethered_headset` (metadata 6) AND the MIUI headset binder (`IMiuiHeadsetService`) reports support. `HeadsetStateDispatcher` both marks the metadata (`PodMetadata`) and fakes the binder (`checkSupport`/`isMiTWS`/`setCommonCommand`) for known pod addresses. `setIconVisibility("wireless_headset", …)` fills the slot.

## Hard-won rules (respect these)

- **Keep `OppoPodsAction` string values** (the `chen.action.oppopods.*` constants) and **prefs group names** (`oppopods_settings`, `oppopods_milink_state`) byte-for-byte stable — they cross process boundaries and changing them breaks the module + loses user settings. `HookEntry` uses `getRemotePreferences("oppopods_settings")`.
- **Status-bar icon**: `is_untethered_headset` metadata must be **sticky** — do NOT clear it on transient ACL drops (that was the "icon disappears" root cause). Treat `ACTION_ACL_DISCONNECTED` / `ACL_DISCONNECT_REQUESTED` as *not* a real disconnect (dual-mode earphones like vivo briefly drop/re-establish ACL); only HEADSET/A2DP profile disconnects can be real, and only when no profile remains. Derive the icon from actually-connected profiles.
- **vivo RFCOMM**: keep a **persistent SPP session** (open on connect, poll, send commands over the live socket) — a connect-send-close pattern dropped/misapplied commands. The super island fires on the *first battery report of each fresh connect*, so `showedConnected` must be reset in `connectPod` (not just `disconnectedPod`).
- **Per-address state isolation**: battery/ANC state is keyed per device address, not a shared instance — otherwise one brand's battery leaks into another coexisting earphone (shared-instance was the vivo-shows-QCY-battery bug).
- **Package visibility**: on Android 11+ any package we `setPackage`/launch must be in the manifest `<queries>` (e.g. `com.qcy.audio`, `com.vivo.vivotws`) or `getLaunchIntentForPackage`/broadcasts fail silently.
- **Don't reboot the device / don't `setIconVisibility` in a tight re-assert loop.** Prefer `svc bluetooth` or killing the bluetooth process for state resets, and periodic sync (`syncConnectedPods`, 5s) over hot-path re-asserts.
- **Machine-specific config must not be committed**: `local.properties`, and the keystore paths/passwords in `D:\Dev\.gradle\gradle.properties` (outside this repo). Only repo-level `gradle.properties` is safe.
- **`resopt` collapses resource names in release builds** (the `org.lsposed.lsplugin.resopt` plugin renames drawables to `res/0z.png` …). Never look up the module's own drawables by string (`Resources.getIdentifier("img_left", …)`) from a host process — it returns 0 in release and features silently break (this was the "battery super island missing on the CI/release APK while debug works" bug). Load module images via `utils/ModuleImageUtil` (reads `assets/`, immune to resopt + resource-ID shift). R-constant IDs DO survive resopt; only name-based lookup breaks.

## Protocols & reverse-engineering

- vivo GAIA protocol is captured in `docs/vivo-protocol-capture.md` and `docs/parse_btsnoop.py` / `docs/capture_ble.sh` are capture helpers.
- QCY protocol derives from GATT + BLE advertisement; dual-mode LE peer identity is resolved via advertisement source MAC (`matchAdvPeerAddress`), not just the classic→LE address suffix.
- Brand protocol ports trace back to public HyperEars / TWS-Pods-PC sources (GPL-3.0) — keep attribution in the `VivoProtocol.kt` / `QcyProtocol.kt` headers.
