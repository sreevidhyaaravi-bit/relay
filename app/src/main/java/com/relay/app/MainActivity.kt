package com.relay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.relay.app.relay.ProcessTrigger
import com.relay.app.ui.nav.RelayNavHost
import com.relay.app.ui.theme.RelayTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RelayTheme {
                RelayNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-evaluate routes whenever the user comes back: expire old messages, retry the queue.
        val container = relayContainer
        lifecycleScope.launch {
            runCatching { container.engine.expireDue() }
            container.transportManager.requestProcessing(ProcessTrigger.APP_RESUMED)
        }
    }
}
