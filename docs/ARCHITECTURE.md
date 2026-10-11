# RELAY Architecture

RELAY moves emergency messages over whatever real route exists at each hop: Bluetooth Classic,
Wi-Fi Direct, local Wi-Fi, or an optional Firebase internet relay. When no route exists, the
message waits in a persistent queue. This document is the contract every component follows.

## 1. Golden rules (non-negotiable)

1. A message is saved to the database **before** any transmission attempt.
2. A socket write, an open connection, or a Firebase upload is **never** proof of delivery. Only
   an ACK with `kind = FINAL` from the intended recipient, a receipt (`RCPT`), or the recipient
   marking the Firestore document delivered sets `DeliveryState.DELIVERED`.
3. A hop ACK (`kind = HOP`) from an intermediate relay sets `SendState.HOP_ACKED` only. The UI
   says "Sent to nearby relay, waiting for final recipient".
4. A radio being on is not a route. A transport is usable only through a live, handshaken
   `PeerLink` (or a usable internet relay that can reach the recipient).
5. The original `id`, `senderId`, `recipientId`, `createdAtUtc`, `type`/`priority`,
   `expiresAtUtc` and `location` never change across hops. Relays only increment `hopCount` and
   append to `route`.
6. One message id = one row. Receivers deduplicate across all transports.
7. Expired messages are marked `EXPIRED`, never silently deleted.
8. Never fabricate peers, coordinates, statuses, acknowledgements or test results.
9. Never log message bodies or coordinates. Use `TransferLog.shortId(id)`.
10. Simulated data (Relay Lab) lives only in memory and is always labelled SIMULATED.

## 2. Package map

```
com.relay.app
├── RelayApp, AppContainer, MainActivity   wiring (start reading at AppContainer)
├── model/Models.kt                        enums, LocationInfo, RelayConfig, Clock
├── protocol/                              Frames, LineFramer, ProtocolCodec, MessageValidator
├── data/
│   ├── db/                                Room entities, DAOs, RelayDatabase
│   ├── SettingsStore, MessageRepository   settings and the UI-facing repository
│   └── RoomRelayStore                     persistent RelayStore
├── relay/
│   ├── RelayEngine                        all message state transitions
│   ├── QueueProcessor                     one pass over the queue
│   ├── RoutePolicy, QueuePolicy           pure selection/ordering/backoff rules
│   ├── MessageMapper, RelayEvents         wire mapping, events
│   └── RelayStore, InMemoryRelayStore     storage interface + in-memory version
├── transport/
│   ├── TransportContracts, StreamPeerLink, TransferLog
│   ├── TransportManager                   central coordinator
│   ├── bluetooth/                         Bluetooth Classic RFCOMM
│   ├── wifidirect/                        Wi-Fi Direct (P2P) + TCP
│   ├── localwifi/                         NSD + TCP + LanAuth
│   └── internet/                          Firebase relay (optional)
├── connectivity/                          radio and internet monitoring
├── location/                              framework LocationManager (offline GPS)
├── notifications/                         local notifications
├── work/                                  WorkManager retries
├── service/                               optional foreground "keep listening" service
├── permissions/                           runtime permission table
├── sim/                                   Relay Lab (simulated network)
└── ui/                                    theme, components, formatters, navigation, screens/
```

## 3. Wire protocol (v1)

Newline-delimited UTF-8 JSON, max 64 KiB per frame (`protocol/Frames.kt`).

| Frame | Payload | Notes |
|---|---|---|
| `HELLO` | deviceId, name, protocol range | Both sides send immediately; link is usable after both arrive |
| `MSG` | `WireMessage` | Receiver replies with `ACK` for the same id |
| `ACK` | messageId, kind (`HOP`/`FINAL`), status (`STORED`/`DUPLICATE`/`REJECTED`) | `DUPLICATE` counts as success |
| `RCPT` | `Receipt` | Final-delivery proof travelling back to the original sender |
| `PING`/`PONG` | none | Keepalive every 15 s; link closes after 45 s of silence |
| `BYE` | reason | Polite close |

`StreamPeerLink` implements all of this for any `InputStream`/`OutputStream` pair, so each
transport only opens sockets. Partial reads, multiple frames per read, oversize frames,
malformed JSON and unsupported versions are handled by `LineFramer` + `ProtocolCodec`.

## 4. Message state machine

Two independent fields on `MessageEntity`:

* `sendState` (transmission progress): `QUEUED → SENDING → HOP_ACKED | UPLOADED | RETRY_WAIT | PAUSED → DONE`
* `deliveryState` (final recipient): `PENDING → DELIVERED | EXPIRED | CANCELLED` (`RECEIVED` for INCOMING)

`direction`: `OUTGOING` (created here), `INCOMING` (for me or broadcast, shown in inbox),
`RELAY` (carried for others).

### Engine transitions (`RelayEngine`)

| Event | Effect |
|---|---|
| `createOutgoing(draft)` | validate; new UUID; createdAt = now; expiresAt = now + expiry(type); QUEUED/PENDING; route `CREATED` |
| `onAttemptStarted` | `SENDING`, lastAttemptAt, lastTransport; route `ATTEMPT` |
| outcome `Acked(FINAL)` | non-broadcast: `DELIVERED`, `DONE`, acknowledgedAt = now, deliveredAt = ack.receivedAtUtc, deliveredVia, finalAckCount+1; route `FINAL_ACK`; handoff; event `Delivered`. Broadcast: finalAckCount+1, stays PENDING, sendState back to QUEUED, handoff (each peer once) |
| outcome `Acked(HOP)` | if not delivered: `HOP_ACKED`, relayAckBy/Name/At, hopAckCount+1; route `HOP_ACKED`; handoff; event `HopAcked` |
| outcome `Rejected` | handoff with result REJECTED (never resend to that peer); route `ATTEMPT_FAILED`; if reason says expired, expire |
| outcome `Failed`/`Timeout` | route `ATTEMPT_FAILED`; lastError; event `AttemptFailed` (no handoff: retry is safe, receiver deduplicates) |
| `onAttemptRoundFailed` | retryCount+1; backoff = min(base·2^(n-1), max) ±10% deterministic jitter; RETRY_WAIT (or PAUSED when retryCount ≥ maxAutoAttempts). HOP_ACKED/UPLOADED keep that sendState but still get nextAttemptAt |
| `onNoRoute` | stays/returns to `QUEUED` (unless HOP_ACKED/UPLOADED/RETRY_WAIT/PAUSED); no retry count |
| `onUploadOutcome(Uploaded/AlreadyUploaded)` | if not delivered: `UPLOADED`, uploadedAt; route `UPLOADED` |
| `onRemoteFinalDelivery` | OUTGOING: `DELIVERED` (via INTERNET_RELAY). RELAY copy: `DELIVERED` (stop forwarding) and create a receipt for the original sender |
| `onReceipt` | store if absent (dedup). If it is for our pending OUTGOING message: `DELIVERED`, route `RECEIPT`. If we hold a RELAY copy: `DELIVERED`. Emit `ReceiptAvailable` for receipts we should pass on |
| `cancel` | only PENDING OUTGOING: `CANCELLED`, `DONE`; route `CANCELLED` |
| `retryNow` | PENDING: clear nextAttemptAt; PAUSED/RETRY_WAIT → QUEUED (keep HOP_ACKED/UPLOADED); retryCount reset to 0 when PAUSED |
| `expireDue` | PENDING past expiry: `EXPIRED`, `DONE`, lastError "Expired: no longer forwarded"; route `EXPIRED`; event `Expired` |

### Incoming (`onMessage(msg, from, via)`)

1. Re-validate (`MessageValidator`); invalid → `ACK REJECTED`.
2. `expiresAtUtc <= now` → `REJECTED "expired"`.
3. `senderId == me` → `ACK HOP DUPLICATE` (our own message looped back).
4. For me or broadcast → INCOMING; otherwise RELAY. RELAY requires `relayForOthers`
   (else `REJECTED "relay disabled on this device"`) and `hopCount < maxHops`
   (else `REJECTED "hop limit reached"`).
5. In a transaction: if the id exists → record `DUPLICATE_RECEIVED`, reply `DUPLICATE` with
   kind `FINAL` (if for me/broadcast) or `HOP`, and store nothing new. Otherwise insert via `MessageMapper.fromWire`,
   add `FORWARDED_BY` route events for each carried hop and a `RECEIVED` event.
6. If for me and it arrived through relays (`from.deviceId != senderId`), create a receipt
   (`createdLocally = true`) and emit `ReceiptAvailable`.
7. Emit `IncomingStored` or `RelayStored` (never for duplicates). Reply `STORED` with kind
   `FINAL` (for me/broadcast) or `HOP` (relay).

## 5. Route selection policy (`RoutePolicy`, used by `QueueProcessor`)

For each pending message, in queue order (priority DESC, createdAt ASC, id ASC):

1. Expired → expire. Not due (`nextAttemptAt > now`) and trigger respects backoff → skip.
   PAUSED → skip unless trigger is MANUAL.
2. **Direct**: links whose `peer.deviceId == recipientId`. Several transports to the same peer:
   the peer's `lastSuccessTransport` first (sticky, avoids flip-flopping), then
   `RelayConfig.preferredOrder` (default Bluetooth, Wi-Fi Direct, local Wi-Fi). Try in order;
   stop at the first FINAL ack. If a transport fails, fall back to the next one.
3. **Broadcast** (`recipientId == "*"`): one direct attempt per connected peer not yet handed.
   Never uploaded to the internet relay. Ordinary broadcasts stay one hop. **SOS broadcasts
   spread:** a receiver stores the SOS in its inbox and marks it QUEUED, then passes it to its
   own neighbours (never the sender or phones already on its route) while hops remain.
4. If no direct success, **secondary routes**:
   a. Internet relay, if `isUsable()` and `canReach(recipientId)` and not already uploaded.
   b. Relay peers: links whose peer is not the recipient, not the original sender, not already in
      the carried route, not already handed (HOP/FINAL/DUPLICATE/REJECTED), and only if
      `hopCount + 1 < maxHops`. One link per peer (preferred transport).
   Non-SOS: stop after `relayCopies` (default 2) successful secondary hand-offs, counting the
   upload and earlier relay hand-offs. SOS: try every eligible secondary route (maximum spread).
5. After an upload or hop ack, the message stays PENDING and is still eligible for a direct
   attempt when the recipient connects (receiver deduplicates).
6. No candidate at all → `onNoRoute`. Candidates existed but all failed → `onAttemptRoundFailed`.
7. Before each attempt re-read the row; skip if it became DELIVERED/EXPIRED/CANCELLED.

`QueueProcessor.shareReceipts(links)` sends up to 50 shareable receipts to each link (called on
LINK_UP and when `ReceiptAvailable` fires).

## 6. Transports

| Transport | Discovery | Connection | Notes |
|---|---|---|---|
| Bluetooth Classic | bonded list + inquiry scan | RFCOMM `SERVICE_UUID`, secure socket; server = "Listen" | Pair in system settings if the secure connect fails |
| Wi-Fi Direct | `WifiP2pManager.discoverPeers` | group owner runs `ServerSocket(47321)`, client connects to `groupOwnerAddress` | Other phone may show an accept prompt; Android 12 and lower need location ON |
| Local Wi-Fi | NSD `_relaymsg._tcp.` | TCP + mutual HMAC-SHA256 challenge with the team network key, then HELLO | Client isolation on routers blocks it, shown as an error |
| Internet relay | n/a | Firebase Anonymous Auth + Firestore | Optional; disabled without `google-services.json` |

All produce `StreamPeerLink`s (except the internet relay) and publish them in `links` only after
the handshake. Every blocking call runs on `Dispatchers.IO`.

### Firebase data model (internet relay)

* `devices/{deviceId}`: `{ uid, deviceId, displayName, updatedAt }`. Create only if
  `uid == request.auth.uid` and the doc does not exist (no id hijacking).
* `messages/{messageId}`: all `WireMessage` fields (route as a list of maps) plus
  `uploaderUid`, `uploaderDeviceId`, `uploaderName`, `recipientUid`, `serverCreatedAt`
  (server timestamp), `deliveredAtUtc`, `deliveredByDeviceId`. Created in a transaction only if
  absent (idempotent). Readable by uploader or recipient. Only the recipient may update, and only
  the delivery fields.
* The recipient listens to `messages where recipientUid == myUid`, calls
  `inbound.onMessage(...)`, and only after a successful local store marks the document delivered.
* The uploader listens to `messages where uploaderUid == myUid` and calls
  `inbound.onRemoteFinalDelivery(...)` when `deliveredAtUtc` appears.

## 7. Store-and-forward and retries

* `TransportManager` re-evaluates on: link up/down, radio change, internet validated, app resume,
  new message, relay message stored, retry timer, WorkManager.
* Passes are serialised with a `Mutex` and requests are conflated.
* After each pass it schedules `RetryScheduler.scheduleAt(nextRetryAtUtc)`, and
  `scheduleWhenInternet` if internet-eligible messages are waiting, plus a 15-minute periodic
  safety net. WorkManager timing is not guaranteed by Android.

## 8. Clocks

Times are stored as epoch milliseconds UTC and shown in the phone's time zone. Device clocks are
not synchronised: the sent time comes from the sender's clock, the received time from the
receiver's clock. The UI flags suspected skew (sent more than 2 minutes after received). Expiry
uses the receiver's clock against the sender's `expiresAtUtc`.

## 9. Security properties (hackathon MVP)

* No end-to-end encryption is claimed. Bluetooth uses the OS secure RFCOMM link; Wi-Fi Direct
  uses the OS WPA2 group; local Wi-Fi peers are authenticated with an HMAC challenge on the shared
  team key, but traffic is not encrypted; Firestore traffic uses TLS and security rules.
* Input validation, size limits, hop limits, expiry, dedup and malformed-frame limits are enforced.
* Location is attached only when the user turns it on for that message.

## 10. Coding conventions

* Kotlin, coroutines, no blocking on the main thread. Compose + Material 3.
* Keep classes small and commented for a beginner team. Explain "why", not "what".
* Build files must stay OS-neutral (team builds on macOS and Windows).
* Unit tests use JUnit4 + kotlinx-coroutines-test; Room tests use Robolectric (`@Config(sdk = [34])`).
