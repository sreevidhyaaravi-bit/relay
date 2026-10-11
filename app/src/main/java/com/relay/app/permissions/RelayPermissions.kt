package com.relay.app.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which runtime permissions each feature needs on the running Android version.
 * Every screen and transport uses this one table so requests stay minimal and consistent.
 * Permissions are requested only when the user uses the feature that needs them.
 */
object RelayPermissions {

    /** Connect to / list paired Bluetooth devices, and run the RFCOMM server. */
    fun bluetoothConnect(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray() // BLUETOOTH / BLUETOOTH_ADMIN are install-time on Android 11 and lower

    /** Bluetooth device discovery (inquiry scan). */
    fun bluetoothDiscovery(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    /** Asking the system to make this phone discoverable. */
    fun bluetoothAdvertise(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

    /** Wi-Fi Direct peer discovery and connection. */
    fun wifiDirect(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** Optional location attachment. Coarse alone (approximate) is accepted. */
    fun location(): Array<String> =
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun notifications(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS)
        else emptyArray()

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun missing(context: Context, permissions: Array<String>): List<String> =
        permissions.filterNot { isGranted(context, it) }

    fun allGranted(context: Context, permissions: Array<String>): Boolean = missing(context, permissions).isEmpty()

    /** For location: true if at least approximate location is granted. */
    fun hasAnyLocation(context: Context): Boolean =
        isGranted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasPreciseLocation(context: Context): Boolean = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION)
}
