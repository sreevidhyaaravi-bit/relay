package com.relay.app.location

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.relay.app.util.Geo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Which way the phone's top edge points (0 = north), from the rotation-vector sensor (no
 * internet, no permission). Smoothed so the radar arrow does not jitter. Assumes portrait.
 */
class Compass(context: Context) {
    data class Reading(val headingDegrees: Double, val reliable: Boolean)

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotation: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    fun isSupported(): Boolean = rotation != null

    fun readings(): Flow<Reading> = callbackFlow {
        val sm = sensors
        val sensor = rotation
        if (sm == null || sensor == null) {
            close()
            return@callbackFlow
        }
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed: Double? = null
        var reliable = true
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                val raw = Geo.normalize(Math.toDegrees(orientation[0].toDouble()))
                val prev = smoothed
                // Low-pass filter along the shortest way round the circle.
                smoothed = if (prev == null) raw else Geo.normalize(prev + 0.15 * (((raw - prev + 540) % 360) - 180))
                trySend(Reading(smoothed!!, reliable))
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {
                reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
            }
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }
}
