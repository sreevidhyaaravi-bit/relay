package com.relay.app.ui.nav

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.relay.app.AppContainer
import com.relay.app.relayContainer
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

/** Route strings. Use the helpers to build routes with arguments. */
object Routes {
    const val HOME = "home"
    const val INBOX = "inbox"
    const val OUTBOX = "outbox"
    const val SOS = "sos"
    const val NEARBY = "nearby"
    const val ROUTES = "routes"
    const val SETTINGS = "settings"
    const val LAB = "lab"
    const val COMPOSE = "compose?recipientId={recipientId}&type={type}&replyTo={replyTo}&body={body}"
    const val DETAIL = "message/{messageId}"
    const val RADAR = "radar/{messageId}"
    const val FIND_ME = "findme"
    const val MAP = "map"
    const val PEOPLE = "people"

    fun compose(recipientId: String? = null, type: String? = null, replyTo: String? = null, body: String? = null): String =
        "compose?recipientId=${enc(recipientId)}&type=${enc(type)}&replyTo=${enc(replyTo)}&body=${enc(body)}"

    fun detail(messageId: String): String = "message/${Uri.encode(messageId)}"

    fun radar(messageId: String): String = "radar/${Uri.encode(messageId)}"

    private fun enc(v: String?) = if (v == null) "" else Uri.encode(v)
}

/** What screens use to move around. */
interface RelayNavigator {
    fun open(route: String)
    fun back()
}

private class NavControllerNavigator(private val nav: NavHostController) : RelayNavigator {
    override fun open(route: String) {
        nav.navigate(route) { launchSingleTop = true }
    }

    override fun back() {
        if (!nav.popBackStack()) nav.navigate(Routes.HOME)
    }
}

@Composable
fun RelayNavHost() {
    val nav = rememberNavController()
    val navigator: RelayNavigator = remember(nav) { NavControllerNavigator(nav) }
    androidx.compose.foundation.layout.Box {
        RelayGraph(nav, navigator)
        com.relay.app.ui.components.AlarmBanner(onOpen = { id -> navigator.open(Routes.detail(id)) })
    }
}

@Composable
private fun RelayGraph(nav: NavHostController, navigator: RelayNavigator) {
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(navigator) }
        composable(Routes.INBOX) { InboxScreen(navigator) }
        composable(Routes.OUTBOX) { OutboxScreen(navigator) }
        composable(Routes.SOS) { SosScreen(navigator) }
        composable(Routes.NEARBY) { NearbyScreen(navigator) }
        composable(Routes.ROUTES) { RoutesScreen(navigator) }
        composable(Routes.SETTINGS) { SettingsScreen(navigator) }
        composable(Routes.LAB) { RelayLabScreen(navigator) }
        composable(Routes.FIND_ME) { com.relay.app.ui.screens.findme.FindMeScreen(navigator) }
        composable(Routes.MAP) { com.relay.app.ui.screens.map.NetworkMapScreen(navigator) }
        composable(Routes.PEOPLE) { com.relay.app.ui.screens.people.PeopleScreen(navigator) }
        composable(Routes.RADAR, arguments = listOf(navArgument("messageId") { type = NavType.StringType })) { entry ->
            com.relay.app.ui.screens.radar.RadarScreen(navigator, entry.arguments?.getString("messageId").orEmpty())
        }
        composable(
            Routes.COMPOSE,
            arguments = listOf(
                navArgument("recipientId") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("replyTo") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("body") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { entry ->
            val args = entry.arguments
            ComposeScreen(
                nav = navigator,
                recipientId = args?.getString("recipientId")?.takeIf { it.isNotBlank() },
                typeName = args?.getString("type")?.takeIf { it.isNotBlank() },
                replyToId = args?.getString("replyTo")?.takeIf { it.isNotBlank() },
                presetBody = args?.getString("body")?.takeIf { it.isNotBlank() },
            )
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("messageId") { type = NavType.StringType }),
        ) { entry ->
            MessageDetailScreen(navigator, entry.arguments?.getString("messageId").orEmpty())
        }
    }
}

/** The app-wide [AppContainer] from inside a composable. */
@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { context.relayContainer }
}

/**
 * Creates (or reuses) a ViewModel that gets the [AppContainer]:
 * `val vm = relayViewModel { c -> HomeViewModel(c) }`
 */
@Composable
inline fun <reified VM : ViewModel> relayViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = rememberContainer()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(container) } })
}
