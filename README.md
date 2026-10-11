# RELAY

Relay is an emergency communication system designed to help people send SOS messages and share their location during network outages using alternative communication methods such as Bluetooth and peer-to-peer connectivity.

**Offline-first emergency messaging for Android.** RELAY moves messages over whichever route
really works at each hop (Bluetooth Classic, Wi-Fi Direct, local Wi-Fi, or an optional Firebase
internet relay) and keeps them safely queued when no route exists.

> **Status:** compiles, debug APK built, 146 automated tests passing.
> **Not yet tested on physical phones.** See [docs/STATUS.md](docs/STATUS.md).

## Get started

* **Install and demo:** [HANDOVER.md](HANDOVER.md) (Windows-first; no tools needed for a plain install).
* **Test on real phones:** [docs/PHYSICAL_TESTS.md](docs/PHYSICAL_TESTS.md).
* **Demo script:** [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md).

## What it does

* 🆘 **SOS messages** with confirmation, priority over everything else, red badge on every screen.
* 📞 **Call emergency** (112, configurable) from Home, the SOS screen and every incoming SOS.
* 🚨 **SOS alarm** on the alarm volume with a Stop banner, and 🗣️ **read-aloud** of incoming messages (offline voice).
* 🧭 **Rescue Radar**: compass arrow and distance to an SOS sender, offline. 🔦 **Find me**: flashlight and screen flash SOS in Morse.
* 📡 **Self-spreading SOS**: an SOS to everyone hops phone to phone. 🗺️ **Live network map** of nearby phones.
* ✅ **Safety check** quick messages, 🔋 sender battery level on every message.
* 👥 **QR contacts and groups**: scan to add someone; message a whole group (one tracked copy per person).
* **Vibration you can read without looking:** an incoming SOS vibrates Morse ··· ––– ··· (through
  silent mode on most phones); urgent, coordination and normal messages each feel different.
* **Real device-to-device transfer** over Bluetooth RFCOMM and Wi-Fi Direct; local Wi-Fi with a
  team key; Firebase relay when configured.
* **Automatic route choice and fallback:** nobody picks a radio per message.
* **Store-and-forward:** saved before sending, survives restarts, retried automatically when a
  route appears, carried by intermediate phones, deduplicated everywhere.
* **Honest status:** "Sent to nearby relay" and "Uploaded" are never shown as "Delivered".
  Delivered requires the intended recipient's acknowledgement or receipt.
* **Original timestamps:** sent time (sender's clock) and received time (receiver's clock) shown
  separately and never overwritten by forwarding.
* **Opt-in location** with capture time and stale marking; manual description fallback.
* **Route history** per message, built from real recorded hops.
* **Relay Lab:** a clearly labelled simulated network (devices A to D) running the real relay
  engine, for explaining multi-hop routes.

## Screens

Home · New message · SOS · Inbox · Outbox (waiting / finished / carrying) · Message details and
route · Nearby devices (Bluetooth, Wi-Fi Direct, local Wi-Fi, transfer log) · Connectivity & routes ·
Settings · Relay Lab.

## Build and test

Windows (PowerShell, JDK 17+ such as Android Studio's `jbr`):

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
```

macOS / Linux:

```bash
./gradlew assembleDebug testDebugUnitTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Install on every connected phone with
`scripts/install-all.ps1` (Windows) or `scripts/install-all.sh` (macOS/Linux).

Toolchain: Gradle 8.11.1 (wrapper), AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, minSdk 26
(Android 8.0), Jetpack Compose, Room, WorkManager, kotlinx.serialization, Firebase (optional).

## Project layout

```
app/src/main/java/com/relay/app/
  AppContainer.kt      wiring of every component (start here)
  model/               message types, states, config
  protocol/            wire format, framing, validation
  relay/               RelayEngine (state machine), QueueProcessor, RoutePolicy, QueuePolicy
  data/                Room database, repository, settings
  transport/           StreamPeerLink, TransportManager, bluetooth/, wifidirect/, localwifi/, internet/
  connectivity/ location/ notifications/ work/ service/ permissions/
  sim/                 Relay Lab simulator
  ui/                  theme, shared components, formatters, navigation, screens/
app/src/test/          146 unit / Robolectric tests (+1 opt-in real-Firebase test)
docs/                  architecture, status, tests, demo, troubleshooting, Firebase
firestore.rules        security rules for the optional internet relay
scripts/               install helpers for Windows and macOS
```

## Documentation

| Doc | Contents |
|---|---|
| [HANDOVER.md](HANDOVER.md) | Install, USB debugging, adb, rebuild on Windows, demo-day checklist |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Protocol, state machine, routing policy, data model |
| [docs/STATUS.md](docs/STATUS.md) | What works, what is unverified, test results, next steps |
| [docs/PHYSICAL_TESTS.md](docs/PHYSICAL_TESTS.md) | Tests A to F with result tables |
| [docs/DEMO_SCRIPT.md](docs/DEMO_SCRIPT.md) | 6-minute hackathon demo |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Permissions, Bluetooth / Wi-Fi Direct / LAN issues, battery limits, build errors, security notes |
| [docs/FIREBASE_SETUP.md](docs/FIREBASE_SETUP.md) | Optional internet relay setup |

## Limitations

No end-to-end encryption; delivery receipts are not signed; background operation is limited by
Android battery rules; RELAY does not contact emergency services. Details in
[docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md#security-properties-and-limitations-hackathon-mvp).
