# RELAY hackathon demo script (about 7 minutes)

Roles: **Presenter** talks. **Operator A** holds Phone A, **Operator B** holds Phone B.
Optional: mirror Phone A on the projector with scrcpy.

Rehearse once at the venue. If a step fails live, say what happened and use a backup line.
Honesty is part of the pitch.

## Setup (before going on stage)

* RELAY installed on Phones A and B (and C, D if you show multi-hop).
* Both phones: Airplane mode ON, then **Bluetooth ON**. Wi-Fi ON but not connected to any network.
* Phones A and B have connected over Bluetooth at least once before (first time: Phone B taps
  **Let others find this phone**, Phone A searches and taps Connect). After that they reconnect
  by themselves when both have RELAY open.
* Both phones: the red "Allow Nearby devices" banner on Home is gone (permission granted).
* Phone B: RELAY open on the Home screen (it listens automatically).
* Phone A: Nearby devices > Bluetooth shows Phone B in the list.

## 1. The problem (30 s)

"When networks go down in a disaster, phones become useless for coordination exactly when they
matter most. RELAY sends emergency messages over whatever real route exists (Bluetooth,
Wi-Fi Direct, local Wi-Fi or the internet) and stores them safely when nothing exists."

## 2. Prove there is no internet (20 s)

Show both phones: Airplane mode on, Bluetooth on. Open **Connectivity & routes** on Phone A:
"Internet: No network". Internet relay: "not configured" (not used in this demo).

## 3. Live SOS over Bluetooth (90 s)

1. Phone A: **Nearby devices > Connect** next to Phone B. "Connected to Phone B" appears.
2. Phone A: **SOS**. Recipient: Phone B. Edit the text to
   `🆘 RELAY TEST 7319 — Please confirm receipt.`
3. Turn on **Attach my location**, tap **Get my location**. Indoors in Airplane mode GPS may take
   up to a minute: tap **Use my last known location** or **Describe manually** instead of waiting on stage.
4. Tap the big SOS button. Show the confirmation dialog ("no accidental SOS"). Tap **Send SOS**.
5. Phone B vibrates Morse SOS (··· ––– ···). Operator B holds it up: "You can feel an SOS in your
   pocket, even on silent." Then show the notification and the Inbox card with the red 🆘 badge.
   Point at **Sent** (Phone A's clock) vs **Received** (Phone B's clock), and the location with
   its own capture time.
6. Phone A: status becomes **Delivered to intended recipient**.

   "Delivered only appears after Phone B stored the message and acknowledged it. A successful
   send on its own is not proof."

7. Phone B: **Reply** `RELAY REPLY 8426 — Message received.` Phone A receives it.

## Round two highlights (insert after section 3, about 2 minutes)

1. **The alarm (Phone B):** when the SOS lands, Phone B vibrates Morse SOS, rings on the alarm
   volume and reads the message aloud. "Even on silent, even in a pocket, you will know." Tap
   **Stop alarm** on the red banner.
2. **Rescue Radar (Phone B):** tap **🧭 Find sender**. "This arrow points to Priya and says how far
   she is, with no internet and no map." Turn around to show the arrow following.
3. **Find me (Phone A):** SOS screen > **🔦 Find me mode**. Dim the room lights if you can: the
   flashlight and screen flash SOS in Morse. "Rescuers can spot her in the dark or in smoke."
4. **Self-spreading SOS (3 phones):** A is only connected to B; B to C. A sends an SOS to
   **Everyone nearby**: C receives it through B. Open **🗺️ Live map** to show the links.
5. **QR and groups:** scan a teammate's code on **👥 People & QR**, then send to a group.
6. **Call 112:** point at the Call button. "When there is any mobile signal, one tap opens the
   emergency dialer; RELAY never pretends to contact services itself."

## 4. Total disconnection and store-and-forward (90 s)

1. Phone A: turn **Bluetooth OFF**. Now nothing at all is available.
2. Phone A: send a new SOS to Phone B. Status: `QUEUED — WAITING FOR CONNECTION`.
3. Swipe RELAY away (force-close) and reopen: the SOS is still there, same time, same priority.
4. Turn Bluetooth ON, Nearby devices > Connect to Phone B.
5. Without touching anything else, the SOS goes out and turns **Delivered**.

   "Messages are saved before any transmission, survive restarts, and go out automatically the
   moment a real route appears."

## 5. Automatic transport switching (60 s, optional)

1. Both phones: Bluetooth OFF. Wi-Fi stays ON (no network).
2. Phone A only: Nearby devices > Wi-Fi Direct > Connect next to Phone B (Phone B just keeps RELAY
   open; it is findable automatically). Accept the invitation on Phone B.
3. Send a message. Open Message details on Phone B: the route shows **Wi-Fi Direct**.

   "Nobody picks a radio per message. RELAY chooses a working route and falls back if one fails."

## 6. Multi-hop, in the clearly labelled simulator (60 s)

Phone A: **Relay Lab > Mixed-medium SOS > Run until idle**.
Show the purple **SIMULATED RELAY NETWORK** banner, then the route
`Sim A → Bluetooth → Sim B → Wi-Fi Direct → Sim C → Internet Relay → Sim D`,
and Sim A turning Delivered only after the receipt travels back.

"This runs our real relay engine on simulated devices. It shows the multi-hop logic; it is NOT a
physical test. Physically, we have tested: [say only what you actually tested]."

## 7. Close (20 s)

"RELAY: real offline messaging today, honest statuses, and a design that uses every route a phone
has. RELAY does not contact emergency services; it keeps people connected to each other."

## Backup lines

* Bluetooth will not connect: "Android Bluetooth sometimes needs a second try." Tap Connect again,
  or switch to Wi-Fi Direct.
* Something fails: open **Nearby devices > Transfer log** and show the real error. "RELAY never
  pretends a message was delivered."
* Total failure: show the Relay Lab (clearly called a simulation) and the automated test report
  (`app/build/reports/tests/testDebugUnitTest/index.html`, 146 tests passing).
