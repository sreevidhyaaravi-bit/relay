package com.relay.app.location

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.relay.app.RelayApp
import com.relay.app.data.db.MessageEntity
import com.relay.app.model.LocationSource
import com.relay.app.model.SystemClock
import com.relay.app.ui.screens.compose.ComposeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Location capture and sending, with Robolectric's simulated location service. These cover the
 * Test D report ("unable to send location"). A real GPS fix still has to be tried on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = RelayApp::class)
class LocationAndComposeTest {

    private val app = ApplicationProvider.getApplicationContext<RelayApp>()
    private val lm = app.getSystemService(LocationManager::class.java)

    private fun idleUntil(timeoutMs: Long = 10_000, done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done() && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun `no permission is reported, never a fake location`() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(LocationResult.NoPermission, LocationProvider(app, SystemClock).currentLocation(1_000))
    }

    @Test
    fun `a fix from any provider is used even when GPS stays silent`() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val shadowLm = shadowOf(lm)
        shadowLm.setLocationEnabled(true)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)

        val provider = LocationProvider(app, SystemClock)
        var result: LocationResult? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope.launch { result = provider.currentLocation(30_000) }
        idleUntil(500) { false } // let both providers register their requests

        val fixTime = System.currentTimeMillis() - 2_000
        shadowLm.simulateLocation(
            Location(LocationManager.NETWORK_PROVIDER).apply {
                latitude = 12.9716
                longitude = 77.5946
                accuracy = 25f
                time = fixTime
            },
        )
        idleUntil { result != null }
        scope.cancel()

        val success = result as? LocationResult.Success ?: throw AssertionError("expected a fix, got $result")
        assertEquals(12.9716, success.location.latitude!!, 1e-6)
        assertEquals(25f, success.location.accuracyMeters!!, 0.01f)
        assertEquals(fixTime, success.location.capturedAtUtc) // the real fix time, not "now"
        assertEquals(LocationSource.DEVICE, success.location.source)
    }

    @Test
    fun `message is sent without location when none is available`() {
        val vm = ComposeViewModel(app.container)
        vm.selectRecipient("RLY-PEER0001", "Peer")
        vm.body = "Help at the north gate"
        vm.attachLocation = true // switched on, but no fix was obtained
        var stored: MessageEntity? = null
        vm.send { stored = it }
        idleUntil { stored != null }

        val message = stored ?: throw AssertionError("send was blocked: ${vm.error}")
        assertNull(vm.error)
        assertNull(message.locationSource)
        assertNull(message.latitude)
    }

    @Test
    fun `manual location description is attached`() {
        val vm = ComposeViewModel(app.container)
        vm.selectRecipient("RLY-PEER0001", "Peer")
        vm.body = "We are safe"
        vm.attachLocation = true
        vm.useManualLocation = true
        vm.manualLocation = "Library, 2nd floor, north stairs"
        var stored: MessageEntity? = null
        vm.send { stored = it }
        idleUntil { stored != null }

        val message = stored
        assertNotNull(message)
        assertEquals(LocationSource.MANUAL, message!!.locationSource)
        assertEquals("Library, 2nd floor, north stairs", message.locationLabel)
        assertTrue(message.latitude == null)
    }
}
