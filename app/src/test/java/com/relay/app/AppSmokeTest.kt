package com.relay.app

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.test.core.app.ApplicationProvider
import com.relay.app.model.MessageDraft
import com.relay.app.model.MessageType
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.screens.compose.ComposeScreen
import com.relay.app.ui.screens.detail.MessageDetailScreen
import com.relay.app.ui.screens.home.HomeScreen
import com.relay.app.ui.screens.inbox.InboxScreen
import com.relay.app.ui.screens.lab.RelayLabScreen
import com.relay.app.ui.screens.nearby.NearbyScreen
import com.relay.app.ui.screens.outbox.OutboxScreen
import com.relay.app.ui.screens.routes.RoutesScreen
import com.relay.app.ui.screens.settings.SettingsScreen
import com.relay.app.ui.screens.sos.SosScreen
import com.relay.app.ui.theme.RelayTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Starts the real Application (AppContainer, transports, transport manager), MainActivity, and
 * then renders every screen once under Robolectric. Catches crashes at startup and during
 * composition. Robolectric fakes the radios, so this says nothing about real Bluetooth/Wi-Fi.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = RelayApp::class)
class AppSmokeTest {

    private val noNav = object : RelayNavigator {
        override fun open(route: String) = Unit
        override fun back() = Unit
    }

    @Test
    fun `app and main screen start without crashing`() {
        val app = ApplicationProvider.getApplicationContext<RelayApp>()
        val container = app.container
        assertTrue(container.settings.current.deviceId.startsWith("RLY-"))

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(4, container.transportManager.statuses.value.size)
        val relayDetail = container.internet.status.value.detail
        if (BuildConfig.FIREBASE_CONFIGURED) {
            // Configured build, but Robolectric has no internet: it must wait, not claim to be online.
            assertTrue(relayDetail, relayDetail != "Internet relay not configured")
        } else {
            // Without google-services.json the internet relay must say so honestly.
            assertEquals("Internet relay not configured", relayDetail)
        }
        assertTrue(!container.internet.isUsable())
    }

    @Test
    fun `every screen renders with real data`() {
        val app = ApplicationProvider.getApplicationContext<RelayApp>()
        // Like the "Remove animations" accessibility setting: endless animations would keep the
        // simulated screen busy forever in this test.
        android.provider.Settings.Global.putFloat(app.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        // One real queued SOS so cards, outbox facts and detail screen have content.
        val message = runBlocking {
            app.container.repository.send(MessageDraft("RLY-TEST0001", "Test peer", "🆘 smoke test", MessageType.SOS, null))
        }
        val screens: List<Pair<String, @Composable () -> Unit>> = listOf(
            "home" to { HomeScreen(noNav) },
            "compose" to { ComposeScreen(noNav, null, null, null) },
            "sos" to { SosScreen(noNav) },
            "inbox" to { InboxScreen(noNav) },
            "outbox" to { OutboxScreen(noNav) },
            "detail" to { MessageDetailScreen(noNav, message.id) },
            "nearby" to { NearbyScreen(noNav) },
            "routes" to { RoutesScreen(noNav) },
            "settings" to { SettingsScreen(noNav) },
            "lab" to { RelayLabScreen(noNav) },
            "radar" to { com.relay.app.ui.screens.radar.RadarScreen(noNav, message.id) },
            "findme" to { com.relay.app.ui.screens.findme.FindMeScreen(noNav) },
            "map" to { com.relay.app.ui.screens.map.NetworkMapScreen(noNav) },
            "people" to { com.relay.app.ui.screens.people.PeopleScreen(noNav) },
        )
        for ((name, screen) in screens) {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            try {
                controller.get().setContent { RelayTheme { screen() } }
                shadowOf(Looper.getMainLooper()).idle()
            } catch (e: Throwable) {
                throw AssertionError("Screen '$name' crashed: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                controller.pause().stop().destroy()
            }
        }
    }
}
