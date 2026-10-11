package com.relay.app.transport

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.relay.app.connectivity.InternetState
import com.relay.app.model.DeliveryState
import com.relay.app.model.DeviceIdentity
import com.relay.app.model.Direction
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.model.RelayConfig
import com.relay.app.model.SendState
import com.relay.app.model.SystemClock
import com.relay.app.model.TransportState
import com.relay.app.protocol.ProtocolCodec
import com.relay.app.relay.InMemoryRelayStore
import com.relay.app.relay.ProcessTrigger
import com.relay.app.relay.QueueProcessor
import com.relay.app.relay.RelayEngine
import com.relay.app.transport.internet.InternetRelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * OPT-IN end-to-end test against the REAL Firebase project in app/google-services.json, using
 * the app's real InternetRelayTransport for two simulated phones. Needs internet.
 *
 * Run:  RELAY_FIREBASE_IT=1 ./gradlew testDebugUnitTest --tests '*FirebaseRelayIntegrationTest*'
 *
 * Covers: sign-in with retry, device registration, "offline then internet returns" upload,
 * real-time delivery to the recipient, and the sender learning about the confirmation.
 * Leaves one delivered test message in Firestore (rules forbid deleting delivered messages).
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseRelayIntegrationTest {

    private class Phone(context: Context, label: String, options: FirebaseOptions, online: MutableStateFlow<InternetState>, scope: CoroutineScope) {
        val identity = DeviceIdentity("RLY-IT$label" + UUID.randomUUID().toString().take(5).uppercase(), "IT phone $label")
        val app: FirebaseApp = FirebaseApp.initializeApp(context, options, "relay-it-$label-${identity.deviceId}")
        val store = InMemoryRelayStore()
        val engine = RelayEngine(store, { identity }, { RelayConfig() }, SystemClock)
        val processor = QueueProcessor(store, engine, { identity }, { RelayConfig() }, SystemClock)
        val relay = InternetRelayTransport(context, { identity }, engine, online, TransferLog(), scope, SystemClock, app)
    }

    /** Firebase delivers callbacks on the main looper, so keep it running while waiting. */
    private fun pumpUntil(what: String, timeoutMs: Long = 90_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("Timed out waiting for: $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
    }

    private fun <T> onBackground(what: String, block: suspend () -> T): T {
        var result: Result<T>? = null
        thread { result = runCatching { runBlocking { block() } } }
        pumpUntil(what) { result != null }
        return result!!.getOrThrow()
    }

    @Test
    fun `real Firebase delivers a message queued offline once internet returns`() {
        assumeTrue("Set RELAY_FIREBASE_IT=1 to run against the real Firebase project", System.getenv("RELAY_FIREBASE_IT") == "1")
        val config = File("google-services.json")
        assumeTrue("app/google-services.json is required", config.exists())

        val json = ProtocolCodec.json.parseToJsonElement(config.readText()).jsonObject
        val client = json["client"]!!.jsonArray[0].jsonObject
        val options = FirebaseOptions.Builder()
            .setProjectId(json["project_info"]!!.jsonObject["project_id"]!!.jsonPrimitive.content)
            .setApplicationId(client["client_info"]!!.jsonObject["mobilesdk_app_id"]!!.jsonPrimitive.content)
            .setApiKey(client["api_key"]!!.jsonArray[0].jsonObject["current_key"]!!.jsonPrimitive.content)
            .build()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val senderOnline = MutableStateFlow(InternetState.NONE) // sender starts OFFLINE
        val recipientOnline = MutableStateFlow(InternetState(true, true, true, false, "test"))
        val a = Phone(context, "A", options, senderOnline, scope)
        val b = Phone(context, "B", options, recipientOnline, scope)
        try {
            b.relay.start()
            a.relay.start()
            pumpUntil("recipient registered") { b.relay.isUsable() }
            assertEquals(TransportState.AVAILABLE_NOT_CONNECTED, a.relay.status.value.state)

            // 1. Sender is offline: the message is saved and queued.
            val message = onBackground("create") {
                a.engine.createOutgoing(MessageDraft(b.identity.deviceId, b.identity.displayName, "🆘 Firebase integration test", MessageType.SOS, null))
            }
            onBackground("offline pass") { a.processor.processOnce(emptyList(), null, ProcessTrigger.NEW_MESSAGE) }
            assertEquals(SendState.QUEUED, onBackground("read") { a.store.get(message.id)!!.sendState })

            // 2. Internet returns: the relay signs in by itself (with retries) and the queue uploads.
            senderOnline.value = InternetState(true, true, true, false, "test")
            pumpUntil("sender signed in and registered") { a.relay.isUsable() }
            val report = onBackground("online pass") { a.processor.processOnce(emptyList(), a.relay, ProcessTrigger.INTERNET_UP) }
            assertEquals("upload count", 1, report.uploaded)
            assertEquals(SendState.UPLOADED, onBackground("read") { a.store.get(message.id)!!.sendState })

            // 3. The recipient's live listener stores it, then confirms; the sender sees Delivered.
            pumpUntil("recipient stored the message") { onBackground("read") { b.store.get(message.id) } != null }
            val received = onBackground("read") { b.store.get(message.id)!! }
            assertEquals(Direction.INCOMING, received.direction)
            assertEquals(message.createdAtUtc, received.createdAtUtc)
            pumpUntil("sender saw the delivery confirmation") {
                onBackground("read") { a.store.get(message.id)!!.deliveryState } == DeliveryState.DELIVERED
            }
        } finally {
            for (phone in listOf(a, b)) {
                runCatching {
                    onBackground("cleanup") {
                        FirebaseFirestore.getInstance(phone.app).collection("devices").document(phone.identity.deviceId).delete().await()
                        FirebaseAuth.getInstance(phone.app).currentUser?.delete()?.await()
                    }
                }
                phone.relay.stop()
            }
            scope.cancel()
        }
    }
}
