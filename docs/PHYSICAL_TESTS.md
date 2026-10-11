# Physical phone tests (Tests A to F)

Automated tests prove the relay logic. Only these tests prove real phone-to-phone transfer.
**None of them have been run yet**: no phones were connected to the build machine.
Record what really happens. If a step cannot be done, mark it BLOCKED and write why.

Result values: `PASS`, `FAIL`, `BLOCKED` (with the blocker), `NOT TESTED`.

Before you start, fill in:

| Phone | Model | Android version | RELAY device ID | Display name |
|---|---|---|---|---|
| A | | | | Phone A |
| B | | | | Phone B |
| C | | | | Phone C |
| D | | | | Phone D |

**How to make sure there is no internet:** turn on Airplane mode, then turn Bluetooth (and Wi-Fi
when a test needs it) back on. Do NOT connect to any Wi-Fi network. In RELAY, Connectivity &
routes should show "Internet: No network" or "Local network only".

---

## Test A: Bluetooth transfer (the mandatory demo)

1. Install RELAY on Phone A and Phone B. Open it on both.
2. Turn off mobile data on both.
3. Disconnect both from internet-connected Wi-Fi (Airplane mode, then Bluetooth on).
4. Keep Bluetooth on.
5. Pair the phones in Android Settings > Bluetooth if they are not paired (or let RELAY trigger
   the pairing prompt in step 7 and accept it on both phones).
6. Phone B: open **Nearby devices**. Check **Listen for connections** is ON (it starts
   automatically). Allow the Nearby devices permission if asked. If the phones have never
   connected before, tap **Let others find this phone** and accept Android's prompt.
7. Phone A: **Nearby devices > Search for nearby phones** (or use the paired list) > **Connect**
   next to Phone B. Wait for "Connected to Phone B".
   After this first connection the phones reconnect by themselves. To check: swipe RELAY away on
   Phone B and reopen it. Within about 30 seconds both phones show "Connected" again, without
   anyone tapping Connect or making a phone visible. (Tapping **Disconnect** is treated as "stay
   disconnected": RELAY will not reconnect to that phone until someone taps Connect again.)
8. Phone A: **New message** > To: Phone B > Priority: SOS > type exactly:
   `🆘 RELAY TEST 7319 — Please confirm receipt.`
9. Turn on **Attach my location** > **Get my location** (allow location). With Airplane mode on,
   only GPS works: stand near a window or outside and allow up to a minute (the seconds count up).
   You can also tap **Use my last known location** (it keeps its real time) or **Describe manually**. If no
   location arrives, Send anyway: the message goes out marked "Location unavailable". Send.
10. Phone B: open **Inbox**. Check:
    * exact text `🆘 RELAY TEST 7319 — Please confirm receipt.`
    * red SOS badge
    * "Sent" time = time on Phone A when you pressed Send; "Received" time = arrival on Phone B
    * location with its capture time (or "Location unavailable" if you did not attach one)
11. Phone B: **Reply** with `RELAY REPLY 8426 — Message received.`
12. Phone A: the reply is in the Inbox.
13. Acknowledgements: Phone A's Outbox shows **Delivered to intended recipient** for the SOS.
    Phone B's Outbox shows Delivered for the reply.
14. Force-close RELAY on both phones (swipe away), reopen. All messages and statuses are still there.

| Check | Result | Notes |
|---|---|---|
| Bluetooth connection established | | |
| Exact SOS text received on B | | |
| SOS badge on B | | |
| Original sent time preserved | | |
| Location + capture time shown | | |
| Reply received on A | | |
| Delivered status on both senders | | |
| Data survives restart | | |

## Test B: Wi-Fi Direct transfer

1. Mobile data off on both phones, no internet.
2. Turn **Bluetooth OFF** on both phones.
3. Keep **Wi-Fi ON** (not connected to any network).
4. On Android 12 and lower, also turn **Location ON**.
5. Phone A and Phone B: open RELAY and allow the Wi-Fi Direct permission once (Home banner, or
   Nearby devices > Wi-Fi Direct > Discover peers). While RELAY is open, each phone stays findable
   automatically; Phone B does not need to tap anything else.
6. Phone A only: **Nearby devices > Wi-Fi Direct**, wait for Phone B to appear (tap **Discover
   peers** to refresh), then **Connect**. Accept the invitation on Phone B if asked.
7. Wait for "Connected to Phone B" under Wi-Fi Direct.
8. Phone A: send `WIFI DIRECT TEST 5521` to Phone B.
9. Phone B: check the message and metadata. Message details > Route shows `Wi-Fi Direct`.
10. Phone B: reply. Phone A: check the reply.

| Check | Result | Notes |
|---|---|---|
| Wi-Fi Direct group formed | | |
| RELAY link connected | | |
| Message + metadata on B | | |
| Route shows Wi-Fi Direct | | |
| Reply on A | | |

## Test C: Total disconnection and queue persistence

1. Phone A: mobile data, Wi-Fi and Bluetooth all OFF.
2. Check Connectivity & routes: no transport connected.
3. Phone A: **SOS** > recipient Phone B > optional location > **SOS** > **Send SOS**.
4. Check the status: `QUEUED — WAITING FOR CONNECTION`, Outbox tab "Waiting", "Saved on this phone only".
5. Force-close RELAY and reopen it.
6. Check: same text, same Sent time, SOS badge, location still attached, still queued.
7. Turn Bluetooth ON (internet stays off). Phone B listening. Phone A: Nearby devices > Connect to Phone B.
8. Within a few seconds the SOS is sent automatically (no tap needed). Phone B receives it.
9. Phone A shows **Delivered to intended recipient**.

| Check | Result | Notes |
|---|---|---|
| Queued while fully offline | | |
| Survived force-close | | |
| Sent automatically when Bluetooth connected | | |
| Received on B with original time | | |
| Delivered status on A | | |

## Test D: Automatic transport switching

1. Connect A and B over Bluetooth. Send `SWITCH TEST 1`; check delivery.
2. Phone A: Nearby devices > Disconnect the Bluetooth link (or turn Bluetooth off).
3. Connect A and B over Wi-Fi Direct (Test B steps 5 to 7).
4. Send `SWITCH TEST 2`. Message details on Phone B: route shows `Wi-Fi Direct`.
5. Failure-and-fallback (if feasible): connect BOTH Bluetooth and Wi-Fi Direct. Send a message and
   immediately turn Bluetooth off on Phone B. RELAY should retry over Wi-Fi Direct.
   Check Phone A's message details for a "Failed Bluetooth" entry followed by delivery via Wi-Fi Direct.
6. Phone B's Inbox has each message once (no duplicates).

Do not count the Relay Lab fallback scenario as this test: it is simulated.

| Check | Result | Notes |
|---|---|---|
| Delivery over Bluetooth | | |
| Delivery over Wi-Fi Direct after switching | | |
| Fallback after a real failure | | |
| No duplicate inbox entries | | |
| Route history shows real transports | | |

## Test E: Internet restoration (needs Firebase)

Requires [FIREBASE_SETUP.md](FIREBASE_SETUP.md). Without it, mark **BLOCKED BY CONFIGURATION**.
Follow section 8 of that guide.

| Check | Result | Notes |
|---|---|---|
| Queued while offline | | |
| Uploaded when internet returned (status says Uploaded, not Delivered) | | |
| Recipient received and stored it | | |
| Sender shows Delivered only after recipient confirmation | | |
| Works again after app restart | | |
| No duplicates | | |

## Test F: Mixed-medium multi-hop (stretch goal)

Goal: A → B over Bluetooth, B → C over Wi-Fi Direct, C → D over the internet relay (if Firebase is
configured; otherwise C → D over Bluetooth).

1. Phone D must be known to Phone A: connect A and D once (any transport), then disconnect, or type
   D's device ID manually when composing.
2. Links: A-B Bluetooth only. B-C Wi-Fi Direct only. C has internet + D has internet (or C-D Bluetooth).
   A must NOT have a direct link to D.
3. Phone A: send `MULTIHOP TEST 9043` to Phone D with a location.
4. Watch Phone A: **Sent to nearby relay, waiting for final recipient** (not Delivered).
5. Phone B: Outbox > Carrying tab shows the message; it forwards to C automatically when linked.
6. Phone D receives it. Message details on D: route `Phone A → Bluetooth → Phone B → Wi-Fi Direct → Phone C → Internet Relay → Phone D`,
   original Sent time, sender Phone A, location from Phone A.
7. Phone A turns Delivered only after the receipt travels back (keep A-B connected so B can pass it on).

| Check | Result | Notes |
|---|---|---|
| A shows relay ack, not delivery | | |
| B carried and forwarded | | |
| D received with original metadata | | |
| Route history matches real hops | | |
| A shows Delivered after receipt | | |

Do not sacrifice Tests A and C for this one.

---

# Round two features (quick checks)

Install the latest APK on all phones (over the old one; messages are kept). Turn the **alarm
volume** up. Record PASS / FAIL / NOT TESTED like above.

| # | Check | How | Result | Notes |
|---|---|---|---|---|
| G1 | Call emergency | Home: tap **📞 Call 112**. The dialer opens with 112 filled in. Do not call. | | |
| G2 | SOS alarm | Settings > Alerts > **Test alarm**. Then send an SOS from A to B: B rings loudly (also on silent), red **Stop alarm** banner appears; Stop works; it stops by itself after 1 minute. | | |
| G3 | Read aloud | Settings > Alerts > turn on **Read messages aloud** > **Test voice**. Send an SOS with a location description: B reads "S O S from ..." and the place. | | |
| G4 | Vibration | B vibrates Morse SOS (··· ––– ···) when the SOS arrives. | | |
| G5 | Safety check | Home > **Safety check** > "I'm safe": the New message screen opens filled in. Send it. | | |
| G6 | Battery | B's message card shows "Sender's battery: N%" (red at 20% or lower). | | |
| H1 | Rescue Radar | A sends an SOS **with GPS location** (outdoors or near a window). On B tap **🧭 Find sender**: distance and arrow appear; turning B turns the arrow; walking towards A reduces the distance. | | |
| H2 | Find me | A: SOS screen > **🔦 Find me mode**: flashlight and screen flash ··· ––– ··· until Stop. | | |
| I1 | Spreading SOS | Three phones in a line: A-B connected, B-C connected, A and C NOT connected. A sends an SOS to **Everyone nearby**. C receives it (route shows A → B → C). | | |
| I2 | Live map | Home > **🗺️ Live map**: connected phones appear with coloured lines; disconnect one and it moves to the grey outer ring. | | |
| J1 | QR contact | Phone C: Home > **👥 People & QR** shows a QR code. Phone A: **Scan someone's QR code** (allow camera). A can now pick Phone C as a recipient without ever connecting. | | |
| J2 | Group | A: People & QR > **New group** with B and C. Send a message to the group: both receive it; A's Outbox shows one copy per person with its own status. | | |
