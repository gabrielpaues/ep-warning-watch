# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Big-picture architecture

Two Android apps that talk to each other over Bluetooth via the **Wearable Data Layer**, both built from this Gradle project:

- **`wear/`** — watch app, Kotlin + Compose for Wear OS. A foreground `LifecycleService` (`DetectorService`) runs the two-stage sensor pipeline; a small Compose UI (`MainActivity` + `MainApp`) handles configuration. Data Layer messaging uses `MessageClient` / `CapabilityClient` from `play-services-wearable`. Capability `ep_warning_detector` (declared in `wear/src/main/res/values/wear.xml`) lets the phone discover the watch.
- **`mobile/`** — phone app, Kotlin + Compose. Receives alarms via `WearableListenerService` (`PhoneListenerService`), fetches GPS, sends SMS via `SmsManager`, persists everything in DataStore. Capability `ep_warning_receiver` is how the watch finds the phone.
- **`shared/`** — Android library used by both `:wear` and `:mobile`. Holds the `AlarmPayload` JSON schema and `DataLayerProtocol` constants (paths and capability names).

**Wire protocol**: JSON-encoded `AlarmPayload` over `MessageClient.sendMessage`, path `/ep-warning/alarm`. Discovery uses the Wearable Capability API; pairing comes from the system Galaxy Wearable / Wear OS pairing — no in-app Bluetooth UI.

**Detection algorithm** lives in `wear/src/main/java/com/epwarning/wear/detection/ShakeDetector.kt`. Pure Kotlin, no Android deps, unit-testable: rolling-mean of gyroscope angular-speed magnitude with a sustain requirement (default 8 s) and a 60 s post-trigger cooldown. Sensitivity 0..1 maps linearly to a threshold of 12 → 3 rad/s.

**Battery strategy** (in `DetectorService`): two-stage sensor pipeline. Stage 1 (idle) registers the accelerometer at `SENSOR_DELAY_NORMAL` with a 5 s batch latency so the SoC can sleep between batches. When linear acceleration crosses `WAKE_GATE_M_S2` (2.5 m/s²), stage 2 swaps to gyroscope at `SENSOR_DELAY_GAME` feeding the detector. Drops back after `IDLE_TIMEOUT_NS` (15 s) of below-gate motion. The service runs as a foreground service with `foregroundServiceType="health"`; partial wakelocks are acquired only briefly when posting an alarm.

## Critical project history (not obvious from code)

- The project pivoted **from Tizen back to Wear OS** on 2026-05-08 once it became clear the only realistic test hardware is a Galaxy Watch 6 (Wear OS), not the Galaxy Watch 3 (Tizen 5.5) that was originally on hand. The Tizen Native (C) implementation that lived under `tizen/` is preserved at `_archive/tizen/` for reference; don't extend it.
- The phone is **not** yet in hand at the time of the pivot — `wear/` builds clean against the Gradle build, but end-to-end testing is blocked until a Wear OS watch is available.
- `mobile/messaging/` was already built against the Wearable Data Layer (`WearableListenerService`, `MessageClient`, `CapabilityClient`) — it was correct before the pivot and continues to be correct now. No SAP rewrite is needed.

## Build & run commands

The system JDK on this machine (Adoptium 25) is **incompatible with AGP 8.5.2**. Use the Homebrew JDK 21 for every Gradle invocation:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :mobile:assembleDebug

JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :wear:assembleDebug
```

APKs:
- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`

Install/uninstall:

```bash
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb -s <watch-serial> install -r wear/build/outputs/apk/debug/wear-debug.apk
adb uninstall com.epwarning.mobile
adb uninstall com.epwarning.wear
```

`local.properties` points at `~/Library/Android/sdk` (gitignored).

For the watch, pair `adb` over Wi-Fi from Wear OS developer options (Settings → Developer options → ADB debugging + Debug over Wi-Fi). Wear OS uses standard `adb` — port 5555, not Tizen's 26101.

## Module conventions

- **Code comments**: defaults to no comments. Only explain *why* when a hidden constraint or surprise is at play. Do not add docstrings or restate-the-code comments.
- **Watch ↔ phone wire format**: JSON, defined by `AlarmPayload` (in `:shared`). Both sides use `AlarmPayload.encodeToBytes` / `ByteArray.decodeAlarmPayload` so the schema only lives in one place — keep it that way; don't duplicate the data class on either side.
- **kotlinx.serialization**: `Json.encodeToString<List<T>>(value)` only resolves to the reified extension when `kotlinx.serialization.encodeToString` is *explicitly imported*. Without that import, Kotlin picks the non-inline overload and complains about a missing `SerializationStrategy` argument. The data-store repositories in `mobile/` and `wear/` already do this — preserve the import if you touch them.
- **Capability discovery, not direct addressing**: never hardcode node IDs. Use `CapabilityClient.getCapability(...)` against the constants in `DataLayerProtocol` — that's what survives the user re-pairing or switching watches.

## Known sharp edges

- **`stripDebug` warning** from `:wear:assembleDebug` (`Unable to strip libandroidx.graphics.path.so, libdatastore_shared_counter.so`) is benign — the NDK strip tool can't process those AAR-bundled binaries. They get packaged unstripped, which is fine for debug.
- The `_archive/` directory is intentionally **not** wired into Gradle. Don't add `include(":archive/...")` for `_archive/tizen/` — it's reference material only.
