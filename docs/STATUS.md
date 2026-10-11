# RELAY status report

Build date: 2026-10-10 (Firebase-enabled build: 2026-10-11). Build machine: macOS (Apple Silicon), Temurin JDK 21, Android SDK 35.
App: 9,900 lines of Kotlin; tests: 1,750 lines.

**Read this first:** everything below marked "automated" ran on a computer with mocked or
simulated links. **No physical-phone test has been run yet**, because no phones were connected to
the build machine. The team must run [PHYSICAL_TESTS.md](PHYSICAL_TESTS.md) and update this file.

## Final report

| Question | Answer |
|---|---|
| Does the project compile? | **Yes.** `assembleDebug` and `testDebugUnitTest` succeed. |
| Was the APK generated? | **Yes.** `app/build/outputs/apk/debug/app-debug.apk`, 23.6 MB, Firebase enabled, SHA-256 `ea7de0d2146968cac878810d61476fbfefdd07b1ecf146bac07e3f8804cf514a`, signed with the shared team debug key (cert SHA-1 `9b:99:de:ec:3c:c3:6e:01:7e:91:47:6b:f9:d1:a0:a6:b4:14:27:11`). |
| Bluetooth messaging tested on physical phones? | **No. NOT TESTED** (no phones available). Implemented; framing/ACK logic verified over real loopback TCP sockets. |
| Wi-Fi Direct messaging tested on physical phones? | **No. NOT TESTED.** Implemented and compiles. |
| Automatic transport switching tested? | **Automated only** (mocked links: preferred transport, fallback after failure, switching when a link appears). **Not tested physically.** |
| Does the queue survive app restarts? | **Verified in an automated test** with a real Room database file closed and reopened (Robolectric). Not yet verified on a phone (Test C). |
| Does queued delivery work when a transport becomes available? | **Automated only** (mocked links). Not tested physically (Test C). |
| Does location sharing work? | **Partly verified.** Opt-in UI, validation, serialization, staleness and "Location unavailable" are tested. A real GPS fix has not been tried on a phone. |
| Is Firebase configured? | **Yes.** Project `relay-97b6b` (Anonymous Auth, Firestore, `firestore.rules` published). The console setup and security rules passed a 19-check self-test run from the build Mac with the same requests the app makes (`scripts/firebase_selftest.py`). The app itself has not yet talked to Firebase from a phone. |
| Has internet-restoration delivery been verified? | **On the build Mac, yes; on phones, not yet.** The app's real relay code delivered a message end to end through the real Firebase project: queued offline, uploaded when internet returned, received and confirmed (`FirebaseRelayIntegrationTest`, opt-in). Test E on phones still to be re-run after the fix below. |
| Has mixed-medium physical multi-hop been verified? | **No. NOT TESTED.** Only in the clearly labelled simulator (Relay Lab test passes). |

## Automated test results (146 passing, 0 failures, plus 1 opt-in real-Firebase test)

Run with `gradlew testDebugUnitTest`. Report: `app/build/reports/tests/testDebugUnitTest/index.html`.

| Test class | Tests | Result | What it covers | Kind |
|---|---|---|---|---|
| LineFramerTest | 7 | pass | partial reads, many frames per read, split UTF-8, oversize, CRLF | pure |
| ProtocolCodecTest | 10 | pass | round-trip, malformed JSON, missing payload, unsupported version, REJECTED-able ids, ack validation, location serialization | pure |
| MessageValidatorTest | 17 | pass | ids, body limits, types/priority, lifetime, hops, location rules | pure |
| MessageMapperTest | 3 | pass | original time/sender/recipient/location/priority preserved across hops; route grows by one hop per forward | pure |
| PolicyTest | 13 | pass | SOS priority + FIFO, backoff bounds, Bluetooth chosen, Wi-Fi Direct when Bluetooth unavailable, preferred order, sticky transport, relay exclusions, hop limit, broadcast | pure |
| RelayEngineTest | 14 | pass | store-before-send, dedup, relay vs final ack, rejects (relay off, hop limit, expired), receipts, upload is not delivery, backoff and pause, expiry keeps messages, cancel, restart recovery | mocked devices |
| AdaptiveTransportTest | 17 | pass | all routes down = queued, radio on but no route = no claim, Bluetooth, Wi-Fi Direct fallback, retry when a route appears, same message over two transports stored once, hop ack is not delivery, mixed-medium metadata + receipt back, no Firebase still works, retry limits, SOS first, expiry, no loops, copy limits | mocked links |
| StreamPeerLinkTest | 8 | pass | handshake, ACK, burst of 25 frames, timeout + late ACK, connection loss, self-connection refused, malformed frame gets REJECTED, receipts | real loopback TCP |
| LanAuthTest | 3 | pass | matching key accepted, wrong key rejected both ways | real loopback TCP |
| FormattersTest | 7 | pass | UI never says Delivered without final ack; required queued label; stale location; clock skew; route text | pure |
| RelayLabTest | 6 | pass | simulated A→B→C forwarding with mixed media, priority, expiry, hop limit, duplicates, fallback | simulation |
| PersistenceTest | 5 | pass | queue survives database restart with all metadata; dedup; ordering; receipts/handoffs | Robolectric + real Room |
| LocationAndComposeTest | 4 | pass | no fake location without permission; fix from any provider used while GPS is silent, with its real capture time; message sent without location when none is available; manual description attached | Robolectric |
| FirebaseRelayIntegrationTest | 1 | pass when run with `RELAY_FIREBASE_IT=1` (skipped otherwise) | two simulated phones, real Firebase: offline queue, sign-in when internet returns, upload, live delivery, confirmation back to sender | real Firebase, opt-in |
| AutoConnectPolicyTest | 5 | pass | which known RELAY phones get automatic Bluetooth reconnection; skips connected phones, phones the user disconnected, stale phones; backoff | pure |
| VibrationPatternTest | 4 | pass | Morse SOS timing (ITU), repeats, distinct pattern per type | pure |
| AppSmokeTest | 2 | pass | real Application + MainActivity start; all 10 screens render with real data; "not configured" shown honestly | Robolectric |

Automated tests are not proof of physical networking.

## Physical tests

| Test | Status |
|---|---|
| A: Bluetooth transfer | NOT TESTED |
| B: Wi-Fi Direct transfer | NOT TESTED |
| C: Total disconnection and queue persistence | NOT TESTED |
| D: Automatic transport switching | NOT TESTED |
| E: Internet restoration | NOT TESTED (Firebase is now configured; ready to run) |
| F: Mixed-medium multi-hop | NOT TESTED (stretch goal) |

## Features

### Implemented and covered by automated tests

* Message model with UUID ids, protocol version, UTC timestamps, priority, expiry, hop count, route
  history, separate transmission state and final delivery state.
* Newline-delimited JSON protocol with HELLO / MSG / ACK / RCPT / PING / BYE, 64 KiB frame limit.
* Store-before-send persistent queue (Room), SOS-first FIFO ordering, bounded exponential backoff,
  retry limit with manual retry, cancel before delivery, expiry without deletion.
* Deduplication across transports and retries; hop acknowledgements vs final acknowledgements;
  multi-hop delivery receipts.
* Route selection policy with fallback, sticky transport, relay copy limits, loop prevention.
* Clearly labelled Relay Lab simulator running the real engine.

### Implemented, compiles, NOT yet verified on phones

* Vibration alerts (added 2026-10-11): Morse ··· ––– ··· for SOS (alarm usage, through silent
  mode on most phones), distinct patterns for urgent / coordination / normal, Settings switch and
  "feel it" buttons. How strong it feels and whether it passes Do Not Disturb varies by phone.

* Bluetooth Classic RFCOMM: auto-listen, discovery, paired list, connect with retries, duplicate
  link resolution, auto-reconnect (3 attempts), make-visible request.
* Wi-Fi Direct: discovery, invitation, group owner server / client socket on port 47321.
* Local Wi-Fi: NSD discovery, manual IP, mutual HMAC authentication with a team key.
* Firebase internet relay: anonymous auth, device registration, idempotent uploads, recipient
  listener, delivery confirmation. Project `relay-97b6b` configured; rules verified by self-test.
* Location capture via framework LocationManager (offline GPS), manual fallback.
* Notifications (SOS high priority), WorkManager retries, optional foreground "keep listening".
* Permission requests per Android version.

### Not implemented / known gaps

* End-to-end encryption (not claimed anywhere).
* Signed receipts (a malicious RELAY phone could forge a receipt).
* Configured recipient groups (only one recipient or "everyone nearby" broadcast, one hop).
* Withdrawing an already uploaded Firebase message on cancel.
* Reverse geocoding of coordinates to place names (optional in the spec; not needed offline).
* On-device instrumented tests and emulator runs.
* Release signing / Play Store build.

## Fixed after the team's first phone tests (2026-10-11)

* **Could not send location (Test D):** the 20 s location budget was split across providers
  (about 7 s for GPS), far too short for a GPS fix without internet; and a missing fix blocked
  the send. Now all providers are asked at once for up to 60 s with a visible counter, the phone's
  last known location is offered immediately (with the time it was saved), and a message is never blocked by a
  missing location (it is sent marked "Location unavailable").
* **Wi-Fi Direct needed "Discover peers" on both phones:** Android only lets a phone be found
  while it is discovering. RELAY now keeps each phone findable automatically while the app is
  open (or always, with Keep listening on), so only the sending phone taps Connect.
* **Upload did not happen when internet came back (Test E):** the relay signed in once when
  internet returned; if that attempt failed (Android often reports internet a few seconds before
  connections work), it stayed offline until the network changed again, so nothing uploaded. It
  now retries (5 s, 10 s, 20 s ... up to 60 s). A slow recipient lookup is now a failed attempt
  that is retried, and "recipient has not registered yet" is shown on the message instead of a
  silent wait, with a re-check every minute.
* **Bluetooth only connected after someone tapped "Make visible":** Android only lets a phone be
  FOUND by a search while it is visible (a privacy rule apps cannot change), so the first meeting
  of two unpaired phones needs one of them visible. The button is now "Let others find this phone"
  with an explanation, and after one connection RELAY **reconnects by itself** (every 20 s while
  open, backing off to 2 min for phones out of range) using the remembered address, which needs no
  search and no visibility. Known RELAY phones are listed first and labelled.
* All fixes are covered by automated tests where possible but still need re-checking on phones.

## Round two additions (2026-10-11, not yet tested on phones)

| Feature | Automated checks |
|---|---|
| Call emergency button (opens dialer with 112, configurable) | screen render |
| SOS alarm (alarm volume, Stop banner and notification action, 60 s limit) | screen render |
| Read messages aloud (offline voice, optional, "SOS only") | spoken text built correctly (no emoji, includes place) |
| Safety check quick messages (I'm safe / Need medical / Trapped / Need water) | priorities checked |
| Sender battery level on every message (new database column) | travels over the wire and through relays; upgrade from the round-one database keeps queued messages |
| Rescue Radar (compass + distance to sender, offline) | distance and bearing maths against known values |
| Find me mode (flashlight + screen flash Morse SOS) | Morse timing; screen render |
| Self-spreading SOS broadcast | spreads across phones, never loops back, stops at the hop limit, ordinary broadcasts stay one hop; Relay Lab scenario |
| Live network map | screen render |
| QR contacts and groups | QR content round trip and rejection of other codes; screen render |
| "On my way" | removed at the team's request |

Automated tests now: 146 passing (plus the opt-in real-Firebase test).

## What the team should do next

1. Install the APK on two phones and run **Test A** and **Test C** first. They are the demo.
2. Run **Test B** and **Test D** (Wi-Fi Direct + switching).
3. Record results in PHYSICAL_TESTS.md and update the tables above. Never mark a test PASS that
   was not physically run.
4. Install the Firebase-enabled APK on every phone, open RELAY once on each with internet so it
   registers, then run **Test E**, then try **Test F**.
5. Rehearse [DEMO_SCRIPT.md](DEMO_SCRIPT.md) at the venue with the actual phones.
6. If a phone model misbehaves, capture `adb logcat -s RELAY` and the in-app Transfer log.
