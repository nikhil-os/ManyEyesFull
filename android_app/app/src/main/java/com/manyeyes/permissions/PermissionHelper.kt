package com.manyeyes.permissions

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class PermissionHelper(private val activity: ComponentActivity, private val onGranted: () -> Unit) {
    private val launcher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cam = results[Manifest.permission.CAMERA] == true
        val mic = results[Manifest.permission.RECORD_AUDIO] == true
        if (cam && mic) onGranted()
    }

    fun ensureCameraMic() {
        val camOk = ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (camOk && micOk) {
            onGranted()
        } else {
            launcher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }
}
