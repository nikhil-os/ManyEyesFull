package com.manyeyes.streaming

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import timber.log.Timber

/**
 * Transparent Activity that launches the Android system screen capture consent dialog.
 * When the user taps "Start now", the result is broadcast back to SignalingForegroundService.
 * This Activity auto-finishes itself immediately after handling the result.
 */
class ScreenCaptureActivity : Activity() {

    companion object {
        const val REQUEST_CODE_SCREEN_CAPTURE = 9001
        const val ACTION_SCREEN_CAPTURE_RESULT = "com.manyeyes.SCREEN_CAPTURE_RESULT"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        const val EXTRA_REMOTE_DEVICE_ID = "remoteDeviceId"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("[ScreenCapture] Activity created — launching consent dialog")

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CODE_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_SCREEN_CAPTURE) {
            val remoteId = intent?.getStringExtra(EXTRA_REMOTE_DEVICE_ID) ?: ""
            if (resultCode == RESULT_OK && data != null) {
                Timber.i("[ScreenCapture] User CONSENTED to screen capture for remote=$remoteId")
                // Send result back to the service via broadcast
                val resultIntent = Intent(ACTION_SCREEN_CAPTURE_RESULT).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_RESULT_CODE, resultCode)
                    putExtra(EXTRA_RESULT_DATA, data)
                    putExtra(EXTRA_REMOTE_DEVICE_ID, remoteId)
                }
                sendBroadcast(resultIntent)
            } else {
                Timber.w("[ScreenCapture] User DENIED screen capture (resultCode=$resultCode)")
            }
        }
        // Auto-finish — this activity is invisible
        finish()
    }
}
