# RELAY

Relay is an emergency communication system designed to help people send SOS messages and share their location during network outages using alternative communication methods such as Bluetooth and peer-to-peer connectivity.

**Offline-first emergency messaging for Android.** RELAY moves messages over whichever route
really works at each hop (Bluetooth Classic, Wi-Fi Direct, local Wi-Fi, or an optional Firebase
internet relay) and keeps them safely queued when no route exists.

## Install

Download `RELAY-debug.apk` from the [Releases](https://github.com/sreevidhyaaravi-bit/relay/releases)
page onto an Android 8.0+ phone, open it and tap **Install**. No internet is needed to use it.

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
firestore.rules        security rules for the optional internet relay
scripts/               install helpers for Windows and macOS
```

## Limitations

No end-to-end encryption; delivery receipts are not signed; background operation is limited by
Android battery rules; RELAY does not contact emergency services.
