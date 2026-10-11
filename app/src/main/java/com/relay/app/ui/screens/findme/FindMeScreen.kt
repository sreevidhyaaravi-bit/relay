package com.relay.app.ui.screens.findme

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.notifications.MorsePattern
import com.relay.app.ui.nav.RelayNavigator
import com.relay.app.ui.nav.rememberContainer
import com.relay.app.util.Torch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * "Find me" mode for the person who needs help: the flashlight blinks SOS in Morse
 * (··· ––– ···) and the whole screen flashes white and red in step, at full brightness, so
 * rescuers can spot the phone in the dark or in smoke. Runs until Stop; keeps the screen on.
 */
@Composable
fun FindMeScreen(nav: RelayNavigator) {
    val context = LocalContext.current
    val name = rememberContainer().settings.current.displayName
    val torch = remember { Torch(context) }
    var lit by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val before = window?.attributes?.screenBrightness
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.attributes = window?.attributes?.apply { screenBrightness = 1f }
        onDispose {
            torch.set(false)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.attributes = window?.attributes?.apply {
                screenBrightness = before ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    LaunchedEffect(Unit) {
        // Waveform format [wait, on, off, on, ..., on]: odd positions are "light on".
        val pattern = MorsePattern.timings("SOS", unitMs = 200)
        while (isActive) {
            pattern.forEachIndexed { i, ms ->
                if (i > 0) {
                    lit = i % 2 == 1
                    torch.set(lit)
                }
                delay(ms)
            }
            lit = false
            torch.set(false)
            delay(7 * 200L) // word gap before repeating
        }
    }

    Box(
        Modifier.fillMaxSize().background(if (lit) Color.White else Color(0xFFC62828)),
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (lit) Color(0xFFC62828) else Color.White
        Column(
            Modifier.systemBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("🆘", fontSize = 72.sp)
            Text("FIND ME", color = fg, fontSize = 48.sp, fontWeight = FontWeight.Black)
            Text(name, color = fg, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                (if (torch.isAvailable) "Flashlight and screen are signalling SOS in Morse code." else "Screen is signalling SOS (this phone has no flashlight).") +
                    "\nHold the phone up so rescuers can see it.",
                color = fg,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = nav::back,
                colors = ButtonDefaults.buttonColors(containerColor = fg, contentColor = if (lit) Color.White else Color(0xFFC62828)),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stop", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
