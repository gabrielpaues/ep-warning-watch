# Tizen watch app — setup and build

The target watch (Galaxy Watch 3) runs **Tizen 5.5**, so this watch app is built
with **Tizen Studio**, not Android Studio. Communication with the phone uses the
**Samsung Accessory Protocol (SAP)** rather than the Wear OS Data Layer.

The source files in this directory (C, manifest, resources) are platform-correct
but Tizen Studio normally generates its own Eclipse-CDT project metadata. The
recommended workflow is to create a fresh project in Tizen Studio, then drop our
files in. Steps below.

## 1. Install Tizen Studio

Download Tizen Studio with **IDE** for macOS:
<https://developer.tizen.org/development/tizen-studio/download>

- Pick the **macOS Intel/Apple Silicon** installer that matches your Mac.
- During install, accept the default install path (`~/tizen-studio`) so the
  later `sdb` instructions work without edits.

After install, run **Tizen Studio**. First launch will offer to open Package Manager.

## 2. Install the SDK packages we need

In Package Manager, install:

| Package | Why |
|---|---|
| **Wearable v5.5** → Native | Compiler + headers for the watch |
| **Wearable v5.5** → Tools | Emulator + image |
| **Extras → Samsung Wearable Extension** | Adds SAP (`<sap.h>`) and signing tooling |
| **Extras → Samsung Certificate Extension** | Generates the dev cert and Samsung distributor cert |

Confirm `~/tizen-studio/platforms/tizen-5.5/wearable/native/` exists after install.

## 3. Add `sdb` to your PATH

```bash
echo 'export PATH="$HOME/tizen-studio/tools:$HOME/tizen-studio/tools/ide/bin:$PATH"' >> ~/.zshrc
```

Open a new terminal and verify: `sdb version`.

## 4. Generate signing certificates

Tizen Studio → menu **Tools → Certificate Manager** → **+** → **Samsung**.

- **Author certificate**: pick a name + password. Sign in with a Samsung account.
- **Distributor certificate**: choose **Individual**.
- When prompted for the watch's DUID:
  1. Pair the watch in **Galaxy Wearable** on the phone if you haven't already.
  2. Connect the watch over Wi-Fi (next step) and run `sdb shell 0 getduid` —
     paste the printed DUID into the dialog.
- Save the active profile.

## 5. Connect the watch over Wi-Fi (`sdb`)

The Watch 3 has no USB; debugging is over Wi-Fi.

1. On the watch: **Settings → About watch → Software information** → tap
   **Software version** 7 times.
2. **Settings → Developer options** → enable **ADB debugging** and
   **Debug over Wi-Fi**. Note the IP shown.
3. From the Mac:
   ```bash
   sdb connect 192.168.1.42:26101
   sdb devices
   ```
   Accept the RSA prompt on the watch. (Yes — port 26101, not 5555. That's
   Tizen's `sdb` default.)

## 6. Create the project in Tizen Studio

**File → New → Tizen Project**:

- **Template** → Wearable v5.5 → Native → **Service Application** → "Basic Service Application"
- Project name: `ep_warning_service`
- Package ID: `com.epwarning.watch`

Then again:

- **Template** → Wearable v5.5 → Native → **Application** → "Basic UI Application"
- Project name: `ep_warning_ui`
- Package ID: same `com.epwarning.watch`, mark it as a **secondary application** in the same package.

(Alternatively, check the "multi-package" checkbox during creation and add both apps to one tizen-manifest.xml from the start.)

## 7. Drop in our sources

Replace the generated stub files with the ones in this directory:

| Tizen Studio path | Repo path |
|---|---|
| `ep_warning_service/src/*.c` and `inc/*.h` | `tizen/service/src/*` + `tizen/service/inc/*` |
| `ep_warning_ui/src/*.c` and `inc/*.h` | `tizen/ui/src/*` + `tizen/ui/inc/*` |
| `tizen-manifest.xml` (package root) | `tizen/tizen-manifest.xml` |
| `res/accessoryservices.xml` | `tizen/res/accessoryservices.xml` |

In **Project Properties → C/C++ Build → Settings → Tool Settings → Linker → Libraries**, add `sap` and (if not already present) `capi-system-sensor`, `capi-appfw-application`, `capi-appfw-preference`, `dlog`.

## 8. Build, sign, install

- **Project → Build Project** (or `⌘B`).
- Right-click the project → **Run As → Tizen Native Application**.
- Tizen Studio packages, signs with the active certificate profile, installs to
  the connected watch via `sdb`, and launches.

## 9. Verify

On the watch, **EP Warning** should appear in the app drawer. Open it, press
**Start**, shake the watch sustained for 8 s — `dlog` should show:

```
sdb dlog -d EP_WARNING:I '*:S'
```

(`alarm fired peak=… sustained=…` line.)

The phone side will see nothing yet — that's the next module to rewrite (SAP
consumer in `mobile/`).

## File map

```
tizen/
├── tizen-manifest.xml         package + service-app + ui-app + privileges
├── res/
│   └── accessoryservices.xml  SAP service profile (provider role, channel 104)
├── service/
│   ├── inc/
│   │   ├── service.h          shared constants (app IDs, prefs, app_control ops)
│   │   ├── shake_detector.h
│   │   └── sap_provider.h
│   └── src/
│       ├── service.c          service-app lifecycle + sensor stages
│       ├── shake_detector.c   port of the Kotlin detector to C
│       └── sap_provider.c     SAP wrapper (init/start/stop/send_alarm)
└── ui/
    ├── inc/ui.h
    └── src/main.c             minimal Elementary UI: status + toggle + sensitivity
```

## Snags you'll hit

- **"Not authorized" when launching from Tizen Studio**: the watch's DUID isn't
  in your active certificate profile. Re-run Certificate Manager → re-create
  the distributor cert with the correct DUID.
- **`sdb connect` fails**: watch and Mac must be on the same Wi-Fi, and "Debug
  over Wi-Fi" must show the IP. Some routers isolate clients (guest networks,
  enterprise); fall back to a phone hotspot if needed.
- **`sap.h` not found**: the Samsung Wearable Extension isn't installed in the
  Package Manager.
- **App killed after a few minutes**: in **Settings → Apps → EP Warning →
  Battery**, set **Allow background activity** to ON.
- **Sensors silent when wrist drops**: the watch may be entering low-power.
  We set `SENSOR_PAUSE_NONE` in the service to opt out of the auto-pause.
