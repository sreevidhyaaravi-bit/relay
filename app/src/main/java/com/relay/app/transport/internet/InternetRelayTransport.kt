package com.relay.app.transport.internet

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.relay.app.BuildConfig
import com.relay.app.connectivity.InternetState
import com.relay.app.model.Clock
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.PeerIdentity
import com.relay.app.model.TransportKind
import com.relay.app.model.TransportState
import com.relay.app.model.TransportStatus
import com.relay.app.protocol.AckKind
import com.relay.app.protocol.AckStatus
import com.relay.app.protocol.MessageValidator
import com.relay.app.protocol.WireHop
import com.relay.app.protocol.WireLocation
import com.relay.app.protocol.WireMessage
import com.relay.app.transport.InboundHandler
import com.relay.app.transport.InternetRelay
import com.relay.app.transport.Reach
import com.relay.app.transport.TransferLog
import com.relay.app.transport.Transport
import com.relay.app.transport.UploadOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional Firebase relay (Anonymous Auth + Cloud Firestore). See docs/FIREBASE_SETUP.md and
 * firestore.rules.
 *
 * Inactive, and reported as "Internet relay not configured", when app/google-services.json was
 * not present at build time or FirebaseApp failed to initialise. Firebase classes are only
 * touched after that check, so an unconfigured build never crashes.
 *
 * Data model:
 *  devices/{deviceId}   {uid, deviceId, displayName, updatedAt}
 *  messages/{messageId} WireMessage fields + uploaderUid/uploaderDeviceId/uploaderName,
 *                       recipientUid, serverCreatedAt, deliveredAtUtc/deliveredByDeviceId/Name
 *
 * An upload is NOT delivery. The recipient's app stores the message locally first, and only
 * then writes deliveredAtUtc; the uploader's listener turns that into a final delivery.
 */
class InternetRelayTransport(
    private val context: Context,
    private val identity: () -> DeviceIdentity,
    private val inbound: InboundHandler,
    /** Internet reachability (ConnectivityMonitor.internet in the app; a fake in tests). */
    private val internetState: StateFlow<InternetState>,
    private val log: TransferLog,
    private val scope: CoroutineScope,
    private val clock: Clock,
    /** Tests pass their own Firebase app (one per simulated phone); the app uses the default one. */
    private val firebaseApp: FirebaseApp? = null,
) : Transport, InternetRelay {

    override val kind: TransportKind = TransportKind.INTERNET_RELAY

    private val _status = MutableStateFlow(
        TransportStatus(kind, TransportState.UNAVAILABLE, NOT_CONFIGURED),
    )
    override val status: StateFlow<TransportStatus> = _status

    /** Firebase config present and FirebaseApp initialised. */
    val isConfigured: Boolean by lazy {
        firebaseApp != null || BuildConfig.FIREBASE_CONFIGURED && try {
            FirebaseApp.getApps(context).isNotEmpty() || FirebaseApp.initializeApp(context) != null
        } catch (e: Exception) {
            false
        }
    }

    private val _uid = MutableStateFlow<String?>(null)

    /** Firebase anonymous-auth uid once signed in. */
    val signedInUid: StateFlow<String?> = _uid

    private val _message = MutableStateFlow(NOT_CONFIGURED)

    /** User-facing one-liner, e.g. "Internet relay not configured" / "Online, registered as RLY-1234ABCD". */
    val configurationMessage: StateFlow<String> = _message

    private val enabled = MutableStateFlow(true)
    private val onlineMutex = Mutex()
    private var watchJob: Job? = null
    @Volatile private var registered = false
    private var inboxListener: ListenerRegistration? = null
    private var sentListener: ListenerRegistration? = null
    private val processing = ConcurrentHashMap.newKeySet<String>()
    private val reportedDelivered = ConcurrentHashMap.newKeySet<String>()
    private val recipientCache = ConcurrentHashMap<String, Pair<String, Long>>()

    private val db: FirebaseFirestore by lazy { firebaseApp?.let { FirebaseFirestore.getInstance(it) } ?: FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { firebaseApp?.let { FirebaseAuth.getInstance(it) } ?: FirebaseAuth.getInstance() }

    override fun isSupported(): Boolean = isConfigured

    override fun isUsable(): Boolean =
        isConfigured && enabled.value && registered && _uid.value != null && internetState.value.usable

    /** Settings toggle. When disabled: no uploads, no listeners. */
    fun setEnabled(enabled: Boolean) {
        this.enabled.value = enabled
    }

    override fun start() {
        if (!isConfigured) {
            setStatus(TransportState.UNAVAILABLE, NOT_CONFIGURED)
            return
        }
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            combine(internetState, enabled) { net, on -> on to net.usable }
                .distinctUntilChanged()
                // collectLatest: a new connectivity change cancels a retry loop that is waiting.
                .collectLatest { (on, online) ->
                    when {
                        !on -> {
                            detachListeners()
                            setStatus(TransportState.DISABLED, "Internet relay turned off in Settings")
                        }
                        !online -> {
                            setStatus(TransportState.AVAILABLE_NOT_CONNECTED, "Configured; waiting for internet")
                        }
                        else -> {
                            // Android often reports "internet back" seconds before connections
                            // succeed, so one failed sign-in must not leave the relay offline.
                            var wait = FIRST_RETRY_MS
                            while (!goOnline()) {
                                val error = _status.value.lastError ?: _status.value.detail
                                setStatus(TransportState.FAILED, "Internet relay not reachable yet; retrying in ${wait / 1000} s", error)
                                delay(wait)
                                wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
                            }
                        }
                    }
                }
        }
    }

    override fun stop() {
        watchJob?.cancel()
        watchJob = null
        detachListeners()
    }

    override suspend fun canReach(recipientId: String): Boolean = reach(recipientId) is Reach.Reachable

    override suspend fun reach(recipientId: String): Reach {
        if (!isUsable()) return Reach.Unknown("offline or not signed in")
        if (!MessageValidator.isValidDeviceId(recipientId)) return Reach.NotRegistered("invalid recipient id")
        return when (val r = lookupRecipient(recipientId)) {
            is Lookup.Found -> Reach.Reachable
            Lookup.Missing -> Reach.NotRegistered(
                "$recipientId has not registered yet (open RELAY once on that phone while it has internet)",
            )
            is Lookup.Failed -> Reach.Unknown("could not look up the recipient: ${r.reason}")
        }
    }

    override suspend fun upload(msg: WireMessage): UploadOutcome {
        if (!isConfigured) return UploadOutcome.Unreachable(NOT_CONFIGURED)
        if (!isUsable()) return UploadOutcome.Unreachable("offline or not signed in")
        val uid = _uid.value ?: return UploadOutcome.Unreachable("not signed in")
        val recipientUid = when (val r = lookupRecipient(msg.recipientId)) {
            is Lookup.Found -> r.uid
            Lookup.Missing -> return UploadOutcome.Unreachable("the recipient has not registered with the internet relay")
            is Lookup.Failed -> return UploadOutcome.Failed("could not look up the recipient: ${r.reason}")
        }
        val me = identity()
        val ref = db.collection(COLLECTION_MESSAGES).document(msg.id)
        return try {
            val alreadyThere = withTimeout(UPLOAD_TIMEOUT_MS) {
                db.runTransaction { tx ->
                    val existing = tx.get(ref)
                    if (existing.exists()) {
                        true
                    } else {
                        tx.set(ref, toDocument(msg, uid, me, recipientUid))
                        false
                    }
                }.await()
            }
            if (alreadyThere) UploadOutcome.AlreadyUploaded else UploadOutcome.Uploaded
        } catch (e: TimeoutCancellationException) {
            UploadOutcome.Failed("timed out waiting for the server")
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                // The document exists but belongs to another uploader (another relay already
                // uploaded this message id), or the security rules rejected our write.
                if (runCatching { ref.get(Source.SERVER).await().exists() }.getOrDefault(true)) {
                    UploadOutcome.AlreadyUploaded
                } else {
                    UploadOutcome.Failed("security rules rejected the upload (apply firestore.rules)")
                }
            } else {
                UploadOutcome.Failed(e.code.name.lowercase().replace('_', ' '))
            }
        } catch (e: Exception) {
            UploadOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ online / offline

    /** Returns true when finished (online, or a problem retrying cannot fix); false to retry. */
    private suspend fun goOnline(): Boolean = onlineMutex.withLock {
        val me = identity()
        try {
            setStatus(TransportState.CONNECTING, "Signing in to the internet relay…")
            val user = auth.currentUser ?: withTimeout(AUTH_TIMEOUT_MS) { auth.signInAnonymously().await() }.user
            val uid = user?.uid ?: throw IllegalStateException("sign-in returned no user")
            _uid.value = uid
            registerDevice(uid, me)
            registered = true
            attachListeners(uid)
            setStatus(TransportState.CONNECTED, "Online, registered as ${me.deviceId}")
            log.info(kind, "Internet relay online as ${me.deviceId}")
            true
        } catch (e: DeviceIdTakenException) {
            registered = false
            setStatus(TransportState.FAILED, "This device id is registered by another install", e.message)
            true // retrying cannot fix this; see FIREBASE_SETUP.md troubleshooting
        } catch (e: TimeoutCancellationException) {
            registered = false
            setStatus(TransportState.FAILED, "Internet relay did not respond", "timed out")
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            registered = false
            val hint = if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "Firestore rules rejected the request; apply firestore.rules (see docs/FIREBASE_SETUP.md)"
            } else {
                e.code.name
            }
            setStatus(TransportState.FAILED, "Internet relay error", hint)
            false
        } catch (e: Exception) {
            registered = false
            setStatus(
                TransportState.FAILED, "Internet relay sign-in failed",
                (e.message ?: e.javaClass.simpleName) + ". Is Anonymous sign-in enabled in Firebase?",
            )
            log.warn(kind, "Internet relay sign-in failed: ${e.javaClass.simpleName}")
            false
        }
    }

    private class DeviceIdTakenException(message: String) : Exception(message)

    private suspend fun registerDevice(uid: String, me: DeviceIdentity) {
        val ref = db.collection(COLLECTION_DEVICES).document(me.deviceId)
        val taken = withTimeout(AUTH_TIMEOUT_MS) {
            db.runTransaction { tx ->
                val snap = tx.get(ref)
                when {
                    !snap.exists() -> {
                        tx.set(
                            ref,
                            mapOf(
                                "uid" to uid,
                                "deviceId" to me.deviceId,
                                "displayName" to me.displayName,
                                "updatedAt" to FieldValue.serverTimestamp(),
                            ),
                        )
                        false
                    }
                    snap.getString("uid") == uid -> {
                        tx.update(ref, mapOf("displayName" to me.displayName, "updatedAt" to FieldValue.serverTimestamp()))
                        false
                    }
                    else -> true
                }
            }.await()
        }
        if (taken) throw DeviceIdTakenException("Device id ${me.deviceId} belongs to another Firebase user")
    }

    private fun attachListeners(uid: String) {
        if (inboxListener == null) {
            inboxListener = db.collection(COLLECTION_MESSAGES)
                .whereEqualTo("recipientUid", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        log.warn(kind, "Inbox listener error: ${error.code}")
                        return@addSnapshotListener
                    }
                    snapshot?.documentChanges.orEmpty()
                        .filter { it.type == DocumentChange.Type.ADDED || it.type == DocumentChange.Type.MODIFIED }
                        .map { it.document }
                        .filter { it.get("deliveredAtUtc") == null && it.get("rejectedReason") == null }
                        .forEach { doc -> scope.launch(Dispatchers.IO) { handleIncoming(doc) } }
                }
        }
        if (sentListener == null) {
            sentListener = db.collection(COLLECTION_MESSAGES)
                .whereEqualTo("uploaderUid", uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null) {
                        log.warn(kind, "Delivery listener error: ${error.code}")
                        return@addSnapshotListener
                    }
                    snapshot?.documents.orEmpty()
                        .filter { it.getLong("deliveredAtUtc") != null && reportedDelivered.add(it.id) }
                        .forEach { doc ->
                            scope.launch(Dispatchers.IO) {
                                inbound.onRemoteFinalDelivery(
                                    messageId = doc.id,
                                    recipientId = doc.getString("recipientId") ?: return@launch,
                                    recipientName = doc.getString("deliveredByName"),
                                    deliveredAtUtc = doc.getLong("deliveredAtUtc") ?: clock.now(),
                                    via = kind,
                                )
                            }
                        }
                }
        }
    }

    private fun detachListeners() {
        inboxListener?.remove()
        sentListener?.remove()
        inboxListener = null
        sentListener = null
        registered = false
    }

    /** Store locally FIRST, then mark the document delivered. */
    private suspend fun handleIncoming(doc: DocumentSnapshot) {
        if (!processing.add(doc.id)) return
        try {
            val me = identity()
            val wire = parse(doc)
            if (wire == null) {
                runCatching { doc.reference.update("rejectedReason", "malformed").await() }
                return
            }
            val from = PeerIdentity(
                deviceId = doc.getString("uploaderDeviceId") ?: wire.senderId,
                displayName = doc.getString("uploaderName") ?: wire.senderName,
            )
            val ack = inbound.onMessage(wire, from, kind)
            if (ack.status == AckStatus.REJECTED) {
                runCatching { doc.reference.update("rejectedReason", ack.reason ?: "rejected").await() }
                return
            }
            if (ack.kind == AckKind.FINAL) {
                doc.reference.update(
                    mapOf(
                        "deliveredAtUtc" to ack.receivedAtUtc,
                        "deliveredByDeviceId" to me.deviceId,
                        "deliveredByName" to me.displayName,
                    ),
                ).await()
                log.info(kind, "MSG ${TransferLog.shortId(wire.id)} received via internet relay and confirmed")
            }
        } catch (e: Exception) {
            log.warn(kind, "Could not process an internet relay message: ${e.message}")
            processing.remove(doc.id) // allow a retry on the next snapshot
            return
        }
    }

    private sealed interface Lookup {
        data class Found(val uid: String) : Lookup
        data object Missing : Lookup
        data class Failed(val reason: String) : Lookup
    }

    /** Finds the Firebase user that owns [deviceId]. Distinguishes "not registered" from "could not check". */
    private suspend fun lookupRecipient(deviceId: String): Lookup {
        val cached = recipientCache[deviceId]
        if (cached != null && clock.now() - cached.second < RECIPIENT_CACHE_MS) return Lookup.Found(cached.first)
        return try {
            val snap = withTimeout(LOOKUP_TIMEOUT_MS) {
                db.collection(COLLECTION_DEVICES).document(deviceId).get(Source.SERVER).await()
            }
            val uid = snap.getString("uid")
            if (uid == null) {
                Lookup.Missing
            } else {
                recipientCache[deviceId] = uid to clock.now()
                Lookup.Found(uid)
            }
        } catch (e: TimeoutCancellationException) {
            Lookup.Failed("timed out")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            Lookup.Failed(e.code.name.lowercase().replace('_', ' '))
        } catch (e: Exception) {
            Lookup.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // ------------------------------------------------------------------ mapping

    private fun toDocument(msg: WireMessage, uid: String, me: DeviceIdentity, recipientUid: String): Map<String, Any?> = mapOf(
        "id" to msg.id,
        "senderId" to msg.senderId,
        "senderName" to msg.senderName,
        "recipientId" to msg.recipientId,
        "recipientName" to msg.recipientName,
        "body" to msg.body,
        "type" to msg.type,
        "priority" to msg.priority.toLong(),
        "createdAtUtc" to msg.createdAtUtc,
        "expiresAtUtc" to msg.expiresAtUtc,
        "location" to msg.location?.let { loc ->
            mapOf(
                "lat" to loc.lat,
                "lon" to loc.lon,
                "accuracyM" to loc.accuracyM?.toDouble(),
                "capturedAtUtc" to loc.capturedAtUtc,
                "label" to loc.label,
                "source" to loc.source,
            )
        },
        "hopCount" to msg.hopCount.toLong(),
        "batteryPercent" to msg.batteryPercent?.toLong(),
        "maxHops" to msg.maxHops.toLong(),
        "route" to msg.route.map { hop ->
            mapOf("nodeId" to hop.nodeId, "nodeName" to hop.nodeName, "transport" to hop.transport, "atUtc" to hop.atUtc)
        },
        "uploaderUid" to uid,
        "uploaderDeviceId" to me.deviceId,
        "uploaderName" to me.displayName,
        "recipientUid" to recipientUid,
        "serverCreatedAt" to FieldValue.serverTimestamp(),
        "deliveredAtUtc" to null,
        "deliveredByDeviceId" to null,
        "deliveredByName" to null,
    )

    private fun parse(doc: DocumentSnapshot): WireMessage? = try {
        val loc = (doc.get("location") as? Map<*, *>)?.let { m ->
            WireLocation(
                lat = (m["lat"] as? Number)?.toDouble(),
                lon = (m["lon"] as? Number)?.toDouble(),
                accuracyM = (m["accuracyM"] as? Number)?.toFloat(),
                capturedAtUtc = (m["capturedAtUtc"] as? Number)?.toLong(),
                label = m["label"] as? String,
                source = m["source"] as? String ?: return null,
            )
        }
        val route = (doc.get("route") as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            WireHop(
                nodeId = m["nodeId"] as? String ?: return@mapNotNull null,
                nodeName = m["nodeName"] as? String ?: "",
                transport = m["transport"] as? String ?: return@mapNotNull null,
                atUtc = (m["atUtc"] as? Number)?.toLong() ?: return@mapNotNull null,
            )
        }
        val wire = WireMessage(
            id = doc.getString("id") ?: doc.id,
            senderId = doc.getString("senderId") ?: return null,
            senderName = doc.getString("senderName") ?: return null,
            recipientId = doc.getString("recipientId") ?: return null,
            recipientName = doc.getString("recipientName"),
            body = doc.getString("body") ?: return null,
            type = doc.getString("type") ?: return null,
            priority = doc.getLong("priority")?.toInt() ?: return null,
            createdAtUtc = doc.getLong("createdAtUtc") ?: return null,
            expiresAtUtc = doc.getLong("expiresAtUtc") ?: return null,
            location = loc,
            hopCount = doc.getLong("hopCount")?.toInt() ?: 0,
            maxHops = doc.getLong("maxHops")?.toInt() ?: 6,
            route = route,
            batteryPercent = doc.getLong("batteryPercent")?.toInt(),
        )
        if (wire.id != doc.id || MessageValidator.validate(wire) is MessageValidator.Result.Invalid) null else wire
    } catch (e: Exception) {
        null
    }

    private fun setStatus(state: TransportState, detail: String, error: String? = null) {
        _status.value = TransportStatus(kind, state, detail, error, clock.now())
        _message.value = if (error != null) "$detail: $error" else detail
    }

    companion object {
        const val NOT_CONFIGURED = "Internet relay not configured"
        private const val COLLECTION_DEVICES = "devices"
        private const val COLLECTION_MESSAGES = "messages"
        private const val AUTH_TIMEOUT_MS = 15_000L
        private const val UPLOAD_TIMEOUT_MS = 15_000L
        private const val LOOKUP_TIMEOUT_MS = 15_000L
        private const val FIRST_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 60_000L
        private const val RECIPIENT_CACHE_MS = 5 * 60_000L
    }
}
