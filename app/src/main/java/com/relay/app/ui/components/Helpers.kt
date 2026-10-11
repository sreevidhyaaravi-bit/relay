package com.relay.app.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay

/** Current time that re-composes every [intervalMs], so "3 min ago" labels stay fresh. */
@Composable
fun rememberNow(intervalMs: Long = 15_000L): State<Long> = produceState(System.currentTimeMillis(), intervalMs) {
    while (true) {
        value = System.currentTimeMillis()
        delay(intervalMs)
    }
}

/**
 * Returns a function that asks for runtime permissions and then calls [onResult] with
 * whether ALL requested permissions were granted. An empty array counts as granted
 * (the feature needs no runtime permission on this Android version).
 */
@Composable
fun rememberPermissionRequester(onResult: (allGranted: Boolean) -> Unit): (Array<String>) -> Unit {
    val callback by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        callback(results.isNotEmpty() && results.values.all { it })
    }
    return { permissions ->
        if (permissions.isEmpty()) callback(true) else launcher.launch(permissions)
    }
}

/** Copies [text] to the clipboard and shows a short confirmation. */
fun copyToClipboard(context: Context, label: String, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
