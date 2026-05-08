# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Big-picture architecture

Two apps that talk to each other over Bluetooth, **using two completely separate toolchains**:

- **`tizen/`** — watch app, **Tizen Native (C)**, built with **Tizen Studio** (not Gradle).
  Two binaries inside one package (`com.epwarning.watch`): a headless `service-application` (`com.epwarning.watch.service`) that runs the sensor pipeline + SAP provider, and a `ui-application` (`com.epwarning.watch.ui`) that draws the configuration screens. The two processes coordinate via `app_control` ops and shared `app_preference` keys (`PREF_SENSITIVITY`, `PREF_MONITORING_ON`, etc., defined in `tizen/service/inc/service.h`).
- **`mobile/`** — Android phone app, Kotlin + Compose, built with Gradle. Receives alarms, fetches GPS, sends SMS via `SmsManager`, persists everything in DataStore.
- **`shared/`** — Android library used only by `:mobile`. Holds the JSON `AlarmPayload` schema.

**Wire protocol**: Samsung Accessory Protocol (SAP) over the existing Galaxy Wearable Bluetooth pairing.
Service profile id `/com/epwarning/alarm`, channel `104`, watch is the **provider** (`tizen/res/accessoryservices.xml`), phone is the **consumer**. Discovery uses SAP's built-in profile lookup — no in-app pairing UI.

**Detection algorithm** lives in `tizen/service/src/shake_detector.c`. Pure C, no Tizen deps, unit-testable: rolling-mean of gyroscope angular-speed magnitude with a sustain requirement (default 8 s) and a 60 s post-trigger cooldown. Sensitivity 0..1 maps linearly to a threshold of 12 → 3 rad/s. The same algorithm exists in Kotlin under `_archive/wear-os/` — that file is the reference port and should stay byte-for-byte equivalent if either side is changed.

**Battery strategy** (in `tizen/service/src/service.c`): two-stage sensor pipeline. Idle stage runs accelerometer at ~5 Hz with a 5 s batch latency so the SoC sleeps; once linear acceleration crosses `WAKE_GATE_M_S2` (2.5 m/s²), it switches to gyroscope at 50 Hz feeding the detector. Drops back after `IDLE_TIMEOUT_NS` (15 s) of below-gate motion. `SENSOR_PAUSE_NONE` keeps listeners alive when the screen is off. Closing the UI does **not** stop the service — they're separate processes.

## Critical project history (not obvious from code)

- The **target watch is a Galaxy Watch 3 running Tizen 5.5**, not Wear OS. The Kotlin/Wear-OS scaffold under `_archive/wear-os/` was written before the watch model was confirmed and is **dead code**. Don't extend it. Use it only as algorithm/UI reference. See `~/.claude/projects/-Users-gabrielpaues-github-ep-warning-watch/memory/watch_platform_tizen.md` for the full pivot rationale.
- **`mobile/messaging/`** still uses Wear OS Data Layer APIs (`WearableListenerService`, `MessageClient`, `CapabilityClient`). It compiles, but it cannot talk to the Tizen watch as-is. Rewriting it to use the Samsung Accessory SDK for Android (`SAAgentV2`) is the next planned change (task #19). Don't add features against the Wear OS APIs in `mobile/`.

## Build & run commands

### Phone (Gradle)

The system JDK on this machine (Adoptium 25) is **incompatible with AGP 8.5.2**. Use the Homebrew JDK 21:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :mobile:assembleDebug
```

APK at `mobile/build/outputs/apk/debug/mobile-debug.apk`.

Install/uninstall via `adb` (already on PATH):

```bash
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb uninstall com.epwarning.mobile
```

`local.properties` points at `~/Library/Android/sdk` (gitignored).

### Watch (Tizen Studio + CLI)

The watch app is built from a **Tizen Studio project**, not from Gradle. Source files in `tizen/` are dropped into a Tizen Studio project that supplies the Eclipse-CDT metadata.

`sdb` and `tizen` CLIs are on PATH from `~/tizen-studio/tools` and `~/tizen-studio/tools/ide/bin`.

Common CLI operations once the project exists:

```bash
# Build a native package from a project dir
tizen build-native -C Debug -a aarch64 -c llvm -r wearable-6.5-device.core

# Sign + package
tizen package -t tpk -s <cert-profile> -- Debug

# Install on the watch
sdb install <project>.tpk

# Live log
sdb dlog -d EP_WARNING:I '*:S'

# Connect / list
sdb connect 10.0.0.56:26101    # Tizen sdb default port is 26101, not 5555
sdb devices
```

### Tizen native rootstrap layout (counter-intuitive)

We build against the **6.5 wearable** rootstrap (`~/tizen-studio/platforms/tizen-6.5/wearable/rootstraps/wearable-6.5-device.core`) for base headers (sensor, appfw, EFL), but declare `api-version="5.5"` in `tizen/tizen-manifest.xml` so the binary refuses to install on watches older than 5.5.

**SAP headers and libs are not in the 6.5 rootstrap.** They come from the 5.5 rootstrap (which Tizen Studio's Package Manager UI doesn't list, but the Baseline SDK installs on disk anyway):

```
~/tizen-studio/platforms/tizen-5.5/wearable/rootstraps/wearable-5.5-device.core/usr/include/sap_client/sap.h
~/tizen-studio/platforms/tizen-5.5/wearable/rootstraps/wearable-5.5-device.core/usr/lib/libsap_client.so
```

Tizen Studio project must add these as extra include + library paths (Project Properties → C/C++ Build → Settings → Tool Settings → Linker `-lsap_client` and a `-L` to the 5.5 lib dir).

## Module conventions

- **Code comments**: defaults to no comments. Only explain *why* when a hidden constraint or surprise is at play. Do not add docstrings or restate-the-code comments.
- **Watch ↔ phone wire format**: JSON, defined by `AlarmPayload` (Kotlin) and the `alarm_payload_encode` snprintf in `tizen/service/src/sap_provider.c`. **Both sides must stay in sync.** If a field changes, change both, not just one.
- **kotlinx.serialization**: `Json.encodeToString<List<T>>(value)` only resolves to the reified extension when `kotlinx.serialization.encodeToString` is *explicitly imported*. Without that import, Kotlin picks the non-inline overload and complains about a missing `SerializationStrategy` argument. The four data-store repositories in `mobile/` and `shared/` already do this — preserve the import if you touch them.

## Known sharp edges

- `tizen/README.md` documents the original Wearable-5.5-Native install path. Reality is what's in this file's "Tizen native rootstrap layout" section above (Samsung delisted the 5.5 wearable native package from Package Manager UI in late 2025; Baseline SDK still ships the rootstrap).
- The `_archive/wear-os/` Kotlin code references a `:wear` Gradle module that is **not** in `settings.gradle.kts` — Gradle won't build it. Leave it that way.
- Galaxy Watch 3 firmware `R850XXU1DWK2` (Tizen 5.5.0.2) has the ADB debug daemon disabled at the firmware level. Verified via three independent attempts: trimmed dev-options menu (no ADB / Wi-Fi-debug toggles), `sdb connect` rejected over Wi-Fi (port 26101 closed), Samsung's own `sdboverbt` BT-bridge APK reports `Socket: disable / Bluetooth: disable` (SPP debug profile not exposed). All three need the same daemon. Install on this hardware is blocked at the device level until hardware changes — do not propose enabling debug, sdb-over-WiFi, the BT bridge, or firmware downgrade as workarounds. See `~/.claude/projects/.../memory/watch_install_blocked.md`.
