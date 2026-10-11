# Troubleshooting, permissions and limitations

## Permissions guide

RELAY asks for a permission only when you use the feature that needs it.

| Feature | Android 12+ (API 31+) | Android 11 and lower |
|---|---|---|
| Bluetooth listen / connect / paired list | Nearby devices (`BLUETOOTH_CONNECT`) | none (install-time) |
| Bluetooth discovery | Nearby devices (`BLUETOOTH_SCAN`) | Location (Android requires it for scans) |
| Make phone visible | Nearby devices (`BLUETOOTH_ADVERTISE`) | none |
| Wi-Fi Direct | Android 13+: Nearby devices (`NEARBY_WIFI_DEVICES`); Android 12: Location | Location, and Location must be ON |
| Attach location | Location (approximate is accepted) | Location |
| Notifications | Android 13+: Notifications | none |

Bluetooth and Wi-Fi Direct scans are declared `neverForLocation`: RELAY does not derive your
location from them. Location is only read when you tap **Get my location** on a message.

If you denied a permission permanently: **Settings > Apps > RELAY > Permissions**.

## Bluetooth

| Problem | Fix |
|---|---|
| Home shows a red "Allow Nearby devices" banner | Tap Allow. Without it the phone cannot listen or connect (Android 12+). |
| "Handshake timed out" | The other phone is reachable but RELAY is not open there. Open it and connect again. |
| "Could not connect ... read failed" | Open RELAY on the other phone; check Listen is on; pair the phones in Android Settings > Bluetooth; retry. Some phones need a second attempt. |
| Search finds nothing | Android only lets a phone be found while it is visible. On the other phone tap **Let others find this phone** (5 minutes), then search again. Only needed the first time two phones meet: afterwards they reconnect by themselves whenever both have RELAY open nearby. Paired phones always appear without searching. On Android 11 and lower turn Location on. |
| Pairing prompt appears | Accept it on BOTH phones (codes must match). |
| Link drops repeatedly | Keep phones within a few metres; RELAY reconnects up to 3 times automatically for links it started. |

## Wi-Fi Direct

| Problem | Fix |
|---|---|
| "Wi-Fi is off" | Wi-Fi Direct needs the Wi-Fi radio ON. No network or internet is needed. |
| "BUSY" | Wait 10 seconds, or toggle Wi-Fi off and on. |
| Peers list empty | RELAY must be open on both phones (each stays findable automatically while open) and the Wi-Fi Direct permission allowed once on each. Tap **Discover peers** on the sending phone to refresh. Android 12 and lower: Location must be ON. Disconnect from hotspots. |
| Invitation never appears | Some phones show it as a notification; pull down the notification shade. |
| "Group formed; opening the RELAY connection" stays | RELAY must be open on both phones. Disconnect and connect again. |
| Connected to a Wi-Fi network at the same time | Usually fine, but some phones refuse Wi-Fi Direct while on certain networks; forget the network for the test. |

## Alarm, voice, radar and QR

| Problem | Fix |
|---|---|
| Alarm is quiet or silent | Turn up the **alarm** volume (separate from media volume). Settings > Alerts > SOS alarm must be on. Some phones block alarms in Do Not Disturb unless alarms are allowed. |
| Nothing is read aloud | Settings > Alerts > Read messages aloud must be on ("SOS only" limits it to SOS). The phone needs a text-to-speech voice installed (Settings > System > Languages > Text-to-speech). |
| Call button does nothing | The phone must be able to make calls. Emergency calls need some mobile signal (any carrier). |
| Radar says "no GPS location" | The sender attached a typed description, not a GPS fix. Only GPS locations can be pointed to. |
| Radar arrow spins or points wrongly | Calibrate the compass: move the phone in a figure 8. Keep it away from magnets and metal. Hold it flat and upright. |
| Radar distance jumps around | Indoors GPS is rough (tens of metres). It improves outside. Both phones' accuracy is shown. |
| Find me: no flashlight | Some phones do not allow the flashlight while the camera is in use, and some have none. The screen still flashes. |
| QR scan does nothing | Allow the camera when asked. Only RELAY codes are accepted ("That is not a RELAY QR code"). |
| Group message: some members never receive it | Each member is a separate message; check each copy's status in the Outbox. Members must be reachable like any recipient. |

## Vibration

| Problem | Fix |
|---|---|
| No vibration when a message arrives | Settings > Vibration alerts must be on. Check the phone's own vibration setting (some phones have "Vibration and haptics" off). Use the "feel it" buttons to test. |
| SOS does not vibrate in Do Not Disturb | Some phones block all vibration in Do Not Disturb. Allow alarms in Do Not Disturb, or turn it off during the event. |
| Phone vibrates twice / pattern feels wrong | Older builds used a notification channel with Android's own vibration; install the latest APK (it replaces those channels). |

## Location

| Problem | Fix |
|---|---|
| "Getting your location" keeps counting | Without internet only GPS works, and it needs a view of the sky: go near a window or outside. RELAY keeps trying for up to 60 s. |
| No location at all | Tap **Use my last known location** (sent with the time it was saved, so the receiver knows it may be out of date) or **Describe manually**. Or just Send: the message is never blocked and arrives marked "Location unavailable". |
| "Location is turned off" | Turn on Location in the quick settings. Approximate location is accepted. |
| "Location permission not granted" | Settings > Apps > RELAY > Permissions > Location. |

## Internet relay

| Status | Meaning |
|---|---|
| "Online, registered as RLY-..." | Ready. Queued messages upload automatically. |
| "Internet relay not reachable yet; retrying in N s" | Internet just came back or is weak. RELAY keeps retrying by itself. |
| Message says "Internet relay is online, but RLY-... has not registered yet" | Open RELAY once on the recipient's phone while it has internet. RELAY re-checks every minute and uploads as soon as it has registered. |
| "Uploaded to internet relay, waiting for recipient" | In Firebase. It becomes Delivered when the recipient's phone is online with RELAY open. |

## Local Wi-Fi

* All phones need the **same team network key** (Settings) and must be on the **same Wi-Fi**.
* "Could not reach ... client isolation": the router blocks phone-to-phone traffic (common on
  guest, campus and hotel Wi-Fi, and some phone hotspots). Use Wi-Fi Direct or Bluetooth.
* Discovery blocked: connect by IP and port (shown on the other phone under "This phone").
* Messages over local Wi-Fi are authenticated with the key but **not encrypted**.

## Messages

| Symptom | Meaning |
|---|---|
| `QUEUED — WAITING FOR CONNECTION` | Saved on the phone; no real route exists. It goes out automatically when one appears. |
| "Sent to nearby relay, waiting for final recipient" | Another RELAY phone stored it. The recipient has NOT confirmed yet. |
| "Uploaded to internet relay, waiting for recipient" | Firebase stored it. The recipient has NOT confirmed yet. |
| "Send failed, will retry" | A real attempt failed; RELAY retries with increasing delays and immediately when a route changes. |
| "Retries paused after N failed attempts" | Tap **Retry now** in the Outbox. |
| "Expired: no longer forwarded" | Past its expiry (24 h normal, 72 h SOS by default; Settings). Kept for reference; send a new one. |
| Same message twice? | Cannot happen in the inbox: every message has one id and is stored once, whatever the route. |
| "Sender's clock appears ahead" | Phone clocks differ. Sent time comes from the sender's clock, received time from yours. |

## Background behaviour and battery

* RELAY works best with the app **open**. Android limits background apps.
* WorkManager retries queued messages roughly every 15 minutes in the background, but Android may
  delay this (Doze, battery saver, manufacturer restrictions). Retries also run whenever the app
  is opened and whenever a connection appears.
* **Keep listening in the background** (Settings) starts a foreground service with a permanent
  notification so Bluetooth / Wi-Fi Direct / LAN listeners stay up with the screen off. It uses
  more battery. Force-stopping the app or aggressive battery savers still stop it.
* For demos: **Settings > Apps > RELAY > Battery > Unrestricted**.
* Nothing runs after the app is force-stopped until it is opened again.

## Building

| Problem | Fix |
|---|---|
| `SDK location not found` | Delete `local.properties` (it may contain the Mac path) and reopen in Android Studio, or create it with `sdk.dir=C\:\\Users\\YOU\\AppData\\Local\\Android\\Sdk`. |
| `jlink executable ... does not exist` | The JDK in use is incomplete (for example PyCharm's runtime). Use Android Studio's JBR: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"`. |
| `Unsupported class file major version` / Gradle needs Java 17 | Point `JAVA_HOME` to JDK 17 or 21 (Android Studio's JBR). |
| Downloads time out | Hackathon Wi-Fi can be slow. `gradle.properties` already waits longer and retries; run the build again. Do the first build before the event. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | A copy signed with a different key is installed. Uninstall RELAY from that phone first (this deletes its messages). Builds from this project all share one key. |
| `adb devices` shows `unauthorized` | Unlock the phone and accept the USB debugging prompt. |
| `adb devices` shows nothing (Windows) | Install the phone's USB driver; try another cable (many are charge-only). |

## Debugging

* In the app: **Nearby devices > Transfer log** (connections, ACKs, failures; never message text).
* Message details: full route history with every attempt, failure, hop ack and confirmation.
* With a cable: `adb logcat -s RELAY`.

## Security properties and limitations (hackathon MVP)

* **No end-to-end encryption.** Relay phones and Firebase project owners can read messages they
  carry or store.
* Bluetooth uses Android's secure (paired, encrypted) RFCOMM link. Wi-Fi Direct uses Android's
  WPA2-protected group. Local Wi-Fi authenticates peers with an HMAC challenge but does not
  encrypt. Firebase uses TLS plus the security rules in `firestore.rules`.
* Delivery receipts are not cryptographically signed: a malicious RELAY phone could forge one.
* Input validation, size limits (2000 characters), hop limits, expiry, deduplication and limits
  on malformed frames are enforced on every transport.
* Location is never attached without the per-message toggle, never invented, and old fixes are
  marked stale.
* RELAY is not an emergency dispatch service and does not contact emergency services.
