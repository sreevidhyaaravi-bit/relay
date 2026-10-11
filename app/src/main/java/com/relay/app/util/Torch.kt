package com.relay.app.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/** The camera flashlight, for "Find me" Morse signalling. Needs no permission on Android 6+. */
class Torch(context: Context) {
    private val cameras = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val cameraId: String? = runCatching {
        cameras?.cameraIdList?.firstOrNull { id ->
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    val isAvailable: Boolean get() = cameraId != null

    fun set(on: Boolean) {
        val id = cameraId ?: return
        runCatching { cameras?.setTorchMode(id, on) }
    }
}
