# ep-warning-watch

Two-app system that detects sustained shaking on a Samsung Galaxy Watch and sends an SMS with a Google Maps link from the paired Samsung phone.

> **Not a medical device.** This is a hobby project. False negatives and false positives will happen. Do not rely on it as the only safety net for someone who is at risk during a seizure. Treat it as one signal among others (caregiver presence, monitoring pad, professional alarm system).

## Target hardware

- **Watch**: Samsung Galaxy Watch 3, **Tizen 5.5**. (Galaxy Watch 4+ are Wear OS — different toolchain.)
- **Phone**: Samsung Galaxy phone, Android 8+.
- **Watch ↔ phone**: Samsung Accessory Protocol (SAP) over the existing Galaxy Wearable pairing.

## Modules

| Module               | Target              | Purpose                                                                |
|----------------------|---------------------|------------------------------------------------------------------------|
| `tizen/`             | Tizen Native (C)    | Service-app + UI-app: gyroscope monitoring + SAP provider. See `tizen/README.md`. |
| `mobile/`            | Android phone app   | Receives SAP alarms, fetches GPS, sends SMS, manages contacts.         |
| `shared/`            | Android library     | `AlarmPayload` JSON schema (used by `mobile/`).                        |
| `_archive/wear-os/`  | (archived)          | Original Wear-OS-targeted Kotlin scaffold. Not built. Kept as reference for the detection algorithm and UI structure.

## Building

**Phone app** (Android, this repo's Gradle build):

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
./gradlew :mobile:assembleDebug
```

APK: `mobile/build/outputs/apk/debug/mobile-debug.apk`.

**Watch app** (Tizen, separate toolchain): see [`tizen/README.md`](tizen/README.md). Requires Tizen Studio + Samsung Wearable Extension + Samsung Certificate Extension. Built from the IDE, not Gradle.

## How it works

```
[Galaxy Watch — Tizen]                       [Samsung phone — Android]
 ep_warning_service  ── SAP / channel 104 ──► mobile (SAAgentV2 consumer)
   gyro + accel                                  ↓
                                                 SmsSender → SMS to contacts
                                                 + Google Maps link with location
```

Pairing is done once in the Galaxy Wearable app. The apps find each other via SAP service-profile lookup (provider `com.epwarning.watch` / `/com/epwarning/alarm`) — no in-app Bluetooth setup needed.

## Detection algorithm

`tizen/service/src/shake_detector.c` (and the reference `_archive/wear-os/wear/.../ShakeDetector.kt`) is the brain. It maintains a rolling 2-second window of gyroscope angular-speed magnitude. When the window mean exceeds a sensitivity-mapped threshold continuously for the configured sustain time (default 8 s), it fires a trigger and resets. A 60-second cooldown suppresses repeats within the same event.

The threshold is sensitivity-mapped between 3 rad/s (most sensitive) and 12 rad/s (least sensitive). For reference, a quick wrist flick peaks around 4–6 rad/s but is not sustained, so it gets rejected by the sustain requirement.

The C implementation has no Tizen dependencies (only `<math.h>` and `<string.h>`), so it can be unit-tested with synthetic samples on the host.

## Battery strategy

The Tizen service-application runs two stages:

1. **Idle** — `SENSOR_ACCELEROMETER` at 200 ms interval (~5 Hz) with a 5-second batch (`sensor_listener_set_max_batch_latency`). The SoC sleeps between batches.
2. **Active** — once linear acceleration exceeds a wake gate (~2.5 m/s² over gravity), `SENSOR_GYROSCOPE` is registered at 20 ms interval (50 Hz) and feeds the detector. After 15 s of below-gate motion the service drops back to idle.

`SENSOR_PAUSE_NONE` keeps the listener running while the screen is off, so monitoring continues regardless of UI focus. The UI app and service are separate processes — closing the UI doesn't stop the service.

## Phone-side flow

When the phone's SAP consumer receives an alarm message:

1. Decode `AlarmPayload`.
2. Read configured contacts from DataStore.
3. Request a current GPS fix (`Priority.HIGH_ACCURACY`, 8 s timeout, falls back to `lastLocation`).
4. Build SMS body: `EP Warning: a possible seizure was detected at HH:mm. Location: https://maps.google.com/?q=lat,lng`.
5. Send via `SmsManager` (multipart for long bodies).
6. Persist a `ReceivedAlarm` record so the user can dismiss it as a false alarm later.

## Privileges & permissions

**Watch (Tizen `tizen-manifest.xml`)**: `healthinfo`, `appmanager.launch`, `bluetooth`, `network.get`, Samsung `accessoryprotocol`. Background categories `sensor` and `iot-communication` so the service-app keeps running with the screen off.

**Phone (Android manifest)**: `SEND_SMS`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`. `SEND_SMS` is a "dangerous" Play-Store-restricted permission — for private/sideloaded use, grant it on first launch.

## Configuration

On the watch (`com.epwarning.watch.ui`):
- **Sensitivity** slider (0–100 %).
- **Start / Stop** toggle.

On the phone (`mobile/`):
- **Status** tab — watch reachability + permission grant button.
- **Contacts** tab — add/remove phone numbers (with optional labels).
- **Alarms** tab — received events with maps link, sent-recipient count, and "Dismiss as false alarm" button.

## Status

- ✅ `mobile/` Android phone app — builds, installs, runs.
- ✅ `tizen/` source files — written, but not yet compiled (needs Tizen Studio + Samsung Wearable Extension).
- ⏳ `mobile/` SAP consumer — currently uses Wear OS Data Layer; needs rewrite to Samsung Accessory SDK before the two ends can talk.
- ⏳ End-to-end test on real Watch 3 hardware.

## What's not included yet

- No boot-restart of the watch service (user must tap Start after a watch reboot).
- No haptic countdown on the watch when an alarm is about to fire — useful for letting the wearer cancel false positives.
- No tests — the C `shake_detector` is the obvious candidate (it has no Tizen deps).
- No end-to-end synthetic-shake replay tooling for tuning the threshold curve.
