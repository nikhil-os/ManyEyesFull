package com.manyeyes.streaming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Minimal floating Activity that keeps the app in foreground state,
 * allowing camera access to continue even when the main app is backgrounded.
 *
 * On Android 12+, this Activity is launched via a full-screen intent from
 * SignalingForegroundService when a remote device requests camera access.
 * It brings the app process to "foreground" so the camera foreground service
 * type can be used.
 *
 * The Activity appears as a small floating bubble showing:
 * - Recording indicator
 * - Stop button
 * - Camera switch button
 *
 * After 3 seconds it auto-minimizes (moves to back) to stay unobtrusive,
 * but the process remains in foreground state.
 */
class FloatingCameraActivity : ComponentActivity() {

    companion object {
        const val EXTRA_REMOTE_DEVICE_ID = "remote_device_id"
        const val EXTRA_SHOW_PREVIEW = "show_preview"
        const val EXTRA_AUTO_START = "auto_start"
        const val ACTION_CLOSE_BUBBLE = "com.manyeyes.ACTION_CLOSE_BUBBLE"

        fun createIntent(context: android.content.Context, remoteDeviceId: String, showPreview: Boolean = false): Intent {
            return Intent(context, FloatingCameraActivity::class.java).apply {
                putExtra(EXTRA_REMOTE_DEVICE_ID, remoteDeviceId)
                putExtra(EXTRA_SHOW_PREVIEW, showPreview)
                putExtra(EXTRA_AUTO_START, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
        }
    }

    private var remoteDeviceId: String? = null
    private var showPreview: Boolean = false

    // Broadcast receiver to close the bubble when streaming stops
    private val closeBubbleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_BUBBLE) {
                Timber.i("[FloatingCamera] Received close bubble broadcast")
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Turn on screen and show on lock screen — essential for full-screen intent
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        remoteDeviceId = intent.getStringExtra(EXTRA_REMOTE_DEVICE_ID)
        showPreview = intent.getBooleanExtra(EXTRA_SHOW_PREVIEW, false)

        Timber.i("[FloatingCamera] Started for remote=$remoteDeviceId, preview=$showPreview")

        // Register broadcast receiver
        val filter = IntentFilter(ACTION_CLOSE_BUBBLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeBubbleReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeBubbleReceiver, filter)
        }

        // Configure window to be small and floating
        configureFloatingWindow()

        setContent {
            FloatingBubbleContent(
                remoteDeviceId = remoteDeviceId ?: "Unknown",
                showPreview = showPreview,
                onStop = { stopStreamingAndFinish() },
                onSwitchCamera = { switchCamera() },
                onExpand = { expandToFullApp() }
            )
        }

        // Auto-minimize after 3 seconds — move Activity to back so user
        // sees their home screen / previous app, but the process stays foreground
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                moveTaskToBack(true)
                Timber.i("[FloatingCamera] Auto-minimized after 3s")
            } catch (e: Exception) {
                Timber.w(e, "[FloatingCamera] moveTaskToBack failed")
            }
        }, 3000)
    }

    private fun configureFloatingWindow() {
        window?.apply {
            // Make window floating and small
            setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            setGravity(Gravity.TOP or Gravity.END)

            // Allow touches outside the bubble to pass through
            addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)

            // Keep screen on while streaming
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Dim amount 0 = no dimming behind
            setDimAmount(0f)

            // Make status bar transparent
            statusBarColor = android.graphics.Color.TRANSPARENT
            navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }

    private fun stopStreamingAndFinish() {
        Timber.i("[FloatingCamera] Stopping stream and closing bubble")

        // Send disconnect to SignalingForegroundService
        try {
            val sigSvc = Intent(this, com.manyeyes.signaling.SignalingForegroundService::class.java).apply {
                putExtra("resetStreaming", true)
                putExtra("streamerActive", false)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(sigSvc) else startService(sigSvc)
        } catch (e: Exception) {
            Timber.e(e, "[FloatingCamera] Failed to send stop to SignalingService")
        }

        finish()
    }

    private fun switchCamera() {
        Timber.i("[FloatingCamera] Requesting camera switch")

        // The embedded streamer is inside SignalingForegroundService,
        // so we need to forward the switch command there
        try {
            val svc = Intent(this, com.manyeyes.signaling.SignalingForegroundService::class.java)
            // Use a special sigType that the service recognizes internally
            svc.putExtra("sigType", "SWITCH_CAMERA_LOCAL")
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } catch (e: Exception) {
            Timber.e(e, "[FloatingCamera] Failed to send switch camera command")
        }
    }

    private fun expandToFullApp() {
        Timber.i("[FloatingCamera] Expanding to full app")

        val mainIntent = Intent(this, com.manyeyes.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(mainIntent)
        // Don't finish - keep bubble running
    }

    override fun onDestroy() {
        Timber.i("[FloatingCamera] Activity destroyed")
        try {
            unregisterReceiver(closeBubbleReceiver)
        } catch (e: Exception) {
            Timber.e(e, "[FloatingCamera] Failed to unregister receiver")
        }
        super.onDestroy()
    }
}

@Composable
fun FloatingBubbleContent(
    remoteDeviceId: String,
    showPreview: Boolean,
    onStop: () -> Unit,
    onSwitchCamera: () -> Unit,
    onExpand: () -> Unit
) {
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var isRecording by remember { mutableStateOf(true) }

    // Pulsing animation for recording indicator
    val indicatorColor by animateColorAsState(
        targetValue = if (isRecording) Color.Red else Color.Gray,
        label = "recording_indicator"
    )

    Surface(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    offsetX += dragAmount.x
                    offsetY += dragAmount.y
                }
            }
            .widthIn(min = 160.dp, max = 200.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xE6212121), // Dark semi-transparent
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with recording indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Pulsing red dot
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(indicatorColor)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Close button
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Stop",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Status text
            Text(
                text = "Streaming to",
                color = Color.Gray,
                fontSize = 10.sp
            )
            Text(
                text = remoteDeviceId.take(12) + if (remoteDeviceId.length > 12) "..." else "",
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.height(8.dp))

            // Control buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Switch camera
                IconButton(
                    onClick = onSwitchCamera,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF424242))
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Switch Camera",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Stop button
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE53935))
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(Color.White, RoundedCornerShape(2.dp))
                    )
                }
            }

            // Tap to expand hint
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Tap to open app",
                color = Color.Gray,
                fontSize = 9.sp,
                modifier = Modifier
                    .padding(4.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { _, _ -> }
                    }
            )
        }
    }
}
