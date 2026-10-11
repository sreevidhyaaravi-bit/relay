# RELAY handover: from this Mac to the team's Windows laptop

This is everything the team needs to install, rebuild and demo RELAY. Windows steps come first;
macOS equivalents follow where they differ.

**Do all downloads before demo day, while you have internet.** The demo itself needs none.

## What you are getting

| Item | Where |
|---|---|
| Ready-to-install app | `RELAY-debug.apk` (also built at `app/build/outputs/apk/debug/app-debug.apk`) |
| Full Android Studio project | this folder |
| Phone test scripts (Tests A to F) | [docs/PHYSICAL_TESTS.md](docs/PHYSICAL_TESTS.md) |
| Demo script | [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) |
| Troubleshooting and permissions | [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) |
| What works / what is unverified | [docs/STATUS.md](docs/STATUS.md) |
| Optional internet relay | [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md) |

Phones need **Android 8.0 or newer**. Nothing else is installed on the phones.

**This APK has the internet relay switched on** (Firebase project `relay-97b6b`). After installing,
open RELAY once on each phone **while it has internet** so it registers; Connectivity & routes then
shows "Online, registered as RLY-...". Offline messaging works the same with or without this.

---

## Option 1: Install on the phones without the laptop (simplest)

1. Copy `RELAY-debug.apk` to each phone (USB cable in "File transfer" mode, a USB stick, or any
   file-sharing method you like).
2. On the phone, open the file with the Files app and tap **Install**.
3. If Android asks, allow **Install unknown apps** for the Files app (one time).
4. Open RELAY. Write each phone's name and device ID (on the Home screen) on a sticky note.

## Option 2: Install from the Windows laptop with a cable

### 2a. Get adb (about 15 MB, no installer)

1. Download **SDK Platform-Tools for Windows**:
   <https://developer.android.com/tools/releases/platform-tools>
2. Unzip it to `C:\platform-tools`.

### 2b. Prepare each phone (one time)

1. **Settings > About phone**, tap **Build number** 7 times (enables Developer options).
2. **Settings > System > Developer options**, turn on **USB debugging**.
3. Connect the phone with a USB data cable. On the phone, tap **Allow** on "Allow USB debugging?"
   (tick "Always allow from this computer").

**USB drivers:** many phones work immediately. If `adb devices` shows nothing:
* Pixel / Nexus: install the Google USB Driver
  (<https://developer.android.com/studio/run/win-usb>).
* Samsung: install the Samsung Android USB Driver from Samsung's developer site.
* Others: install the manufacturer's USB driver, or let Windows Update find it.

### 2c. Install

PowerShell, in the `relay` folder:

```powershell
C:\platform-tools\adb.exe devices
```

```powershell
C:\platform-tools\adb.exe install -r RELAY-debug.apk
```

Or install on every connected phone at once (finds adb in `C:\platform-tools` only if it is on
PATH; add it with `$env:Path += ";C:\platform-tools"` first):

```powershell
.\scripts\install-all.ps1 -Apk RELAY-debug.apk
```

## Option 3: Rebuild the app on Windows

Only needed if you change the code. To keep the internet relay working in a rebuilt APK, get
`google-services.json` from Sasneh and put it in `app/` first; without it the rebuilt app
works offline only and shows "Internet relay not configured".

1. Install **Android Studio** (about 1.2 GB): <https://developer.android.com/studio>.
   It includes the Java JDK (JetBrains Runtime 21) and the Android SDK manager.
2. **Delete `local.properties`** if it came from the Mac (it contains a Mac path).
   Android Studio recreates it.
3. Android Studio: **File > Open**, choose the `relay` folder. Wait for "Gradle sync" to finish.
   The first sync downloads Gradle 8.11.1, the Android 15 (API 35) SDK, build-tools 35.0.0 and
   the libraries (about 1.5 GB). Accept the SDK license if asked.
4. Build: **Build > Build App Bundle(s) / APK(s) > Build APK(s)**, or in PowerShell:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

5. The APK is at `app\build\outputs\apk\debug\app-debug.apk`.
6. Run the automated tests:

```powershell
.\gradlew.bat testDebugUnitTest
```

Test reports: `app\build\reports\tests\testDebugUnitTest\index.html`.

### Versions used

| Component | Version |
|---|---|
| JDK | 17 or newer (21 recommended; Android Studio's bundled JBR works) |
| Gradle | 8.11.1 (downloaded by the wrapper) |
| Android Gradle Plugin | 8.7.3 |
| Kotlin | 2.0.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Build-tools | 35.0.0 |

### Same signature on every computer

The project contains a shared debug signing key (`app/relay-debug.keystore`). An APK built on the
Mac and one built on the Windows laptop have the **same signature**, so you can install one over
the other without uninstalling. Uninstalling RELAY would delete its queued messages and give the
phone a new device ID. This key is for the hackathon only; never use it for a store release.

---

## Before demo day: checklist

- [ ] RELAY installed on all 4 phones (same build).
- [ ] Each phone has a clear display name (Settings > Display name): Phone A, Phone B, Phone C, Phone D.
- [ ] Device IDs written down.
- [ ] Phones A and B paired once in **Android Settings > Bluetooth** (makes the demo faster).
- [ ] Run Test A from [docs/PHYSICAL_TESTS.md](docs/PHYSICAL_TESTS.md) at least once at the venue.
- [ ] Notifications allowed on every phone (Home screen banner).
- [ ] Battery: phones charged; consider **Settings > Apps > RELAY > Battery > Unrestricted**.
- [ ] Optional: [scrcpy](https://github.com/Genymobile/scrcpy) on the laptop to show a phone screen
      on the projector (USB, no internet needed once installed).

## Seeing logs while testing

Every phone shows a live **Transfer log** on the Nearby devices screen. With a cable you can also
watch the system log:

```powershell
C:\platform-tools\adb.exe logcat -s RELAY
```

Logs never contain message text or coordinates.

## Notes from building on the Mac

* The Mac had no Java or Android tools; the Android SDK was installed into
  `~/Library/Android/sdk`, and the build used a full Temurin JDK 21. PyCharm's bundled Java
  runtime cannot build Android apps (it lacks `jlink`).
* Downloads from Google's servers were very slow on that network. `gradle.properties` has longer
  network timeouts and retries so slow hackathon Wi-Fi does not break the first build.
