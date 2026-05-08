# ep-warning-watch

Two-app system that detects sustained shaking on a Samsung Galaxy Watch and sends an SMS with a Google Maps link from the paired Samsung phone.

> **Not a medical device.** This is a hobby project. False negatives and false positives will happen. Do not rely on it as the only safety net for someone who is at risk during a seizure. Treat it as one signal among others (caregiver presence, monitoring pad, professional alarm system).

## Target hardware

- **Watch**: Samsung Galaxy Watch 4 or later (Wear OS — Galaxy Watch 6 is the planned target).
- **Phone**: Samsung Galaxy phone, Android 8+.
- **Watch ↔ phone**: Wearable Data Layer over the existing system Bluetooth pairing.

> Earlier development targeted a Galaxy Watch 3 (Tizen 5.5) but installs were blocked at the firmware level. The Tizen-Native (C) implementation is preserved at `_archive/tizen/` for reference. See the *Project history* section below.

## Modules

| Module               | Target              | Purpose                                                                          |
|----------------------|---------------------|----------------------------------------------------------------------------------|
| `wear/`              | Wear OS app         | Foreground `DetectorService` + Compose UI: gyroscope monitoring + alarm sender.  |
| `mobile/`            | Android phone app   | Receives Data Layer alarms, fetches GPS, sends SMS, manages contacts.            |
| `shared/`            | Android library     | `AlarmPayload` JSON schema and `DataLayerProtocol` constants.                    |
| `_archive/wear-os/` *(removed — promoted to `wear/`)* |     | (Pre-pivot scaffold; now the active watch module.)                  |
| `_archive/tizen/`    | (archived)          | Tizen Native (C) implementation kept as reference for the C port of the algorithm and SAP wiring. |

## Building

Both apps build from this Gradle project. The system JDK 25 is incompatible with AGP 8.5.2 — use Homebrew JDK 21:

```bash
# Phone app
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :mobile:assembleDebug

# Watch app
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  ./gradlew :wear:assembleDebug
```

APKs:

- `mobile/build/outputs/apk/debug/mobile-debug.apk`
- `wear/build/outputs/apk/debug/wear-debug.apk`

## How it works

```
[Galaxy Watch — Wear OS]                    [Samsung phone — Android]
 wear/DetectorService  ── MessageClient ──► mobile/PhoneListenerService
   gyro + accel            /ep-warning/alarm        ↓
                                                    SmsSender → SMS to contacts
                                                    + Google Maps link with location
```

Pairing is done once in the system Wear OS / Galaxy Wearable app. Apps find each other via the Wearable Capability API (`ep_warning_detector` on the watch, `ep_warning_receiver` on the phone) — no in-app Bluetooth setup needed.

## Detection algorithm

`wear/src/main/java/com/epwarning/wear/detection/ShakeDetector.kt` is the brain. It maintains a rolling 2-second window of gyroscope angular-speed magnitude. When the window mean exceeds a sensitivity-mapped threshold continuously for the configured sustain time (default 8 s), it fires a trigger and resets. A 60-second cooldown suppresses repeats within the same event.

The threshold is sensitivity-mapped between 3 rad/s (most sensitive) and 12 rad/s (least sensitive). For reference, a quick wrist flick peaks around 4–6 rad/s but is not sustained, so it gets rejected by the sustain requirement.

The Kotlin implementation has no Android dependencies (only `kotlin.math`), so it can be unit-tested with synthetic samples. A reference C port lives at `_archive/tizen/service/src/shake_detector.c` from the earlier Tizen attempt — kept for cross-checking the math, not built.

## Battery strategy

The watch's foreground service runs two stages:

1. **Idle** — `Sensor.TYPE_ACCELEROMETER` at `SENSOR_DELAY_NORMAL` with a 5-second batch latency. The SoC sleeps between batches; we only wake to scan for motion above a low gate.
2. **Active** — once linear acceleration exceeds the wake gate (~2.5 m/s² over gravity), `Sensor.TYPE_GYROSCOPE` is registered at `SENSOR_DELAY_GAME` (~50 Hz) and feeds the detector. After 15 s of below-gate motion the service drops back to idle.

The service uses `foregroundServiceType="health"` so it isn't killed when the screen is off. Closing the UI does not stop the service.

## Pre-alarm countdown

Between detector trigger and SMS dispatch, the watch runs a configurable countdown (default 5 s, range 0–15 s; 0 disables it). During the countdown the watch vibrates and beeps each second — escalating to a longer/stronger pulse on the final second — and shows a full-screen "ALARM IN N" screen with a Cancel button. The wearer can cancel a false positive within the window; cancelled triggers are still recorded in History (marked ✗) so the sensitivity can be tuned over time.

The service owns the countdown timer, not the activity, so the alarm still fires if the visual is killed by the OS. Cancel works via a `RECEIVER_NOT_EXPORTED` broadcast back to the service.

## Boot restart

A `BootReceiver` listens for `ACTION_BOOT_COMPLETED` and re-starts `DetectorService` if `monitoringEnabled` was on when the watch rebooted — so the wearer doesn't have to re-arm after a reboot.

## Phone-side flow

When the phone's `WearableListenerService` receives an alarm message on `/ep-warning/alarm`:

1. Decode `AlarmPayload`.
2. Read configured contacts from DataStore.
3. Request a current GPS fix (`Priority.HIGH_ACCURACY`, 8 s timeout, falls back to `lastLocation`).
4. Build SMS body: `EP Warning: a possible seizure was detected at HH:mm. Location: https://maps.google.com/?q=lat,lng`.
5. Send via `SmsManager` (multipart for long bodies).
6. Persist a `ReceivedAlarm` record so the user can dismiss it as a false alarm later.

## Privileges & permissions

**Watch (`wear/src/main/AndroidManifest.xml`)**: `BODY_SENSORS`, `HIGH_SAMPLING_RATE_SENSORS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`. The detector service runs with `foregroundServiceType="health"` so it survives screen-off.

**Phone**: `SEND_SMS`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `POST_NOTIFICATIONS`. `SEND_SMS` is a "dangerous" Play-Store-restricted permission — for private/sideloaded use, grant it on first launch.

## Configuration

On the watch (`com.epwarning.wear`):
- **Sensitivity** slider (0–100 %).
- **Sustain** slider (4–20 s).
- **Countdown** slider (0–15 s; 0 = instant fire).
- **Start / Stop** monitoring toggle.
- **History** of recent alarms with delivery status (sent ✓, undelivered !, cancelled ✗).

On the phone (`com.epwarning.mobile`):
- **Status** tab — watch reachability + permission grant button.
- **Contacts** tab — add/remove phone numbers (with optional labels).
- **Alarms** tab — received events with maps link, sent-recipient count, and "Dismiss as false alarm" button.

## Project history (2026-05-08 pivot)

The project originally targeted a Galaxy Watch 3 (Tizen 5.5) and a Tizen Native (C) implementation was written. Install proved blocked at the firmware level on `R850XXU1DWK2`: the underlying ADB debug daemon is disabled at firmware-build time (developer-options menu missing the relevant toggles, `sdb` over Wi-Fi rejected on port 26101, and Samsung's own `sdboverbt` BT-bridge APK reports `Socket: disable / Bluetooth: disable`). All three need the same daemon, no software path turns it on without `sdb` first, and Samsung publishes no end-user flashing tool for Watch 3 — so it's a hardware dead-end without swapping watches.

The unblock chosen: switch to a Galaxy Watch 6 (Wear OS), promote the previously-archived `_archive/wear-os/` Kotlin scaffold to the active `wear/` module, and archive the Tizen C implementation to `_archive/tizen/`. The phone-side `mobile/` app is unchanged — it was already built against the Wearable Data Layer.

## What's not included yet

- No end-to-end test on real hardware — the Wear OS watch is not in hand at the time of the pivot.
- No tests — the `ShakeDetector` is the obvious candidate (it has no Android deps).
- No end-to-end synthetic-shake replay tooling for tuning the threshold curve.
