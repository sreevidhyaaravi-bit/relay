package com.relay.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.widget.Toast

/** Small helpers for things the phone does outside RELAY. */
object DeviceActions {

    /** Battery level 0-100, or null if unknown. */
    fun batteryPercent(context: Context): Int? {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
    }

    /**
     * Opens the phone dialer with the emergency number filled in. Android does not let apps place
     * emergency calls by themselves, so the person taps Call. Needs a mobile network; emergency
     * calls can use any carrier's network when one is in range, even without a SIM on many phones.
     */
    fun dialEmergency(context: Context, number: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "This device cannot make phone calls.", Toast.LENGTH_LONG).show()
        }
    }
}
