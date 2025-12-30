package com.manyeyes.signaling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.manyeyes.MainActivity
import com.manyeyes.streaming.FloatingCameraActivity
import com.manyeyes.streaming.StreamForegroundService
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import timber.log.Timber

class SignalingForegroundService : Service() {
    private var wsClient: WsClient? = null
    private var wsConnected: Boolean = false
    private val outbox: MutableList<String> = mutableListOf()
    private var token: String? = null
    private var deviceId: String? = null
    private var baseWs: String? = null
    // Track if this device is currently acting as streamer and for which remote device
    private var streamingRemoteId: String? = null
    private var streamerActive: Boolean = false
    private var lastAnswerFingerprint: String? = null
    // Queue for viewer ICE received before viewer's PeerConnection is ready
    private val viewerIceQueue: MutableList<org.webrtc.IceCandidate> = mutableListOf()
    // Persist last streamer id learned from OFFER for enforcing viewer outbound routing
    private var lastStreamerId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }

    // Update credentials if provided
    intent?.getStringExtra("token")?.let { token = it }
    intent?.getStringExtra("deviceId")?.let { deviceId = it }
    intent?.getStringExtra("baseWs")?.let { baseWs = it }
    val prevStreamerActive = streamerActive
    streamerActive = intent?.getBooleanExtra("streamerActive", streamerActive) ?: streamerActive
    Timber.i("[Signaling] onStartCommand: streamerActive=$streamerActive (was $prevStreamerActive), deviceId=$deviceId")
    
    // Handle reset request - clear all streaming state
    if (intent?.getBooleanExtra("resetStreaming", false) == true) {
        Timber.i("[Signaling] Resetting streaming state")
        streamingRemoteId = null
        lastAnswerFingerprint = null
        viewerIceQueue.clear()
        lastStreamerId = null
        streamerActive = false
        // Close floating camera bubble when streaming stops
        closeFloatingCameraBubble()
    }
    
    val tkn = token ?: run { Timber.d("[Signaling] using existing token"); token }
    val dev = deviceId ?: run { Timber.d("[Signaling] using existing deviceId"); deviceId }
    val wsUrl = baseWs ?: run { Timber.d("[Signaling] using existing baseWs"); baseWs }
    if (wsClient == null && tkn != null && dev != null && wsUrl != null) {
        Timber.i("[Signaling] Starting with deviceId=$dev url=$wsUrl")
    }

    // Establish or reuse persistent WS
    if (wsClient == null && wsUrl != null && tkn != null && dev != null) {
        wsClient = WsClient(wsUrl!!, tkn!!, dev!!).also { client ->
            client.connect(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.i("Signaling connected")
                    wsConnected = true
                    if (outbox.isNotEmpty()) {
                        Timber.i("[Signaling] Flushing ${outbox.size} queued outbound messages")
                        outbox.forEach { client.send(it) }
                        outbox.clear()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                Timber.d("[Signaling] RX: $text")
                try {
                    val j = JSONObject(text)
                    val msgType = j.optString("type")
                    val toId = j.optString("toDeviceId", "")
                    val fromId = j.optString("fromDeviceId", "")
                    // Only act on messages targeted to this device
                    val myId = this@SignalingForegroundService.deviceId
                    if (toId.isNotEmpty() && myId != null && toId != myId) {
                        Timber.w("[Signaling] Dropping $msgType not for me (to=$toId, me=$myId, from=$fromId)")
                        return
                    }
                    Timber.d("[Signaling] Dispatching $msgType (to=$toId, me=$myId, from=$fromId)")
                    when (msgType) {
                        "REQUEST_STREAM" -> {
                            Timber.i("[Signaling] REQUEST_STREAM from=$fromId to=$toId -> starting StreamForegroundService")
                            // Show a heads-up notification so user can confirm the incoming share
                            showIncomingRequestNotification(fromId)
                            
                            // Launch floating camera bubble to keep app in foreground
                            // This is required for Android 12+ to maintain camera access
                            launchFloatingCameraBubble(fromId)
                            
                            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
                            svc.putExtra("role", "streamer")
                            svc.putExtra("remoteDeviceId", fromId)
                            svc.putExtra("token", tkn)
                            svc.putExtra("deviceId", dev)
                            svc.putExtra("baseWs", wsUrl)
                            try {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                                streamingRemoteId = fromId
                                Timber.i("[Signaling] StreamForegroundService started as streamer for remote=$fromId")
                            } catch (e: Exception) {
                                Timber.e(e, "[Signaling] Failed to start StreamForegroundService")
                            }
                        }
                        "ANSWER" -> {
                            val sdp = j.optString("sdp")
                            Timber.i("[Signaling] ANSWER received from=$fromId streamerActive=$streamerActive streamingRemoteId=$streamingRemoteId")
                            // Always forward ANSWER to StreamForegroundService - it will decide if relevant
                            // This avoids race conditions where streamerActive hasn't been set yet
                            // Debounce duplicate ANSWER by fingerprinting SDP
                            val fp = (sdp.hashCode()).toString()
                            if (lastAnswerFingerprint == fp) {
                                Timber.i("[Signaling] ANSWER duplicate ignored fp=$fp from=$fromId")
                                return
                            } else {
                                lastAnswerFingerprint = fp
                            }
                            Timber.i("[Signaling] Forward ANSWER to StreamForegroundService from=$fromId len=${sdp.length}")
                            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
                            svc.putExtra("sigType", "ANSWER")
                            svc.putExtra("sdp", sdp)
                            svc.putExtra("remoteDeviceId", fromId)
                            try {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                                Timber.d("[Signaling] Delivered ANSWER to StreamForegroundService")
                            } catch (e: Exception) {
                                Timber.e(e, "[Signaling] Failed delivering ANSWER to StreamForegroundService")
                            }
                        }
                        "ICE" -> {
                            val cand = j.optString("candidate")
                            val mid = j.optString("sdpMid")
                            val idx = j.optInt("sdpMLineIndex")
                            Timber.i("[Signaling] ICE received from=$fromId streamerActive=$streamerActive deviceId=$deviceId mid=$mid")
                            // Always forward ICE to StreamForegroundService - it will decide if it's relevant
                            // This avoids race conditions where streamerActive hasn't been set yet
                            Timber.i("[Signaling] Forward ICE to StreamForegroundService from=$fromId mid=$mid idx=$idx len=${cand.length}")
                            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
                            svc.putExtra("sigType", "ICE")
                            svc.putExtra("candidate", cand)
                            svc.putExtra("sdpMid", mid)
                            svc.putExtra("sdpMLineIndex", idx)
                            svc.putExtra("remoteDeviceId", fromId)
                            try {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                                Timber.d("[Signaling] Delivered ICE to StreamForegroundService")
                            } catch (e: Exception) {
                                Timber.e(e, "[Signaling] Failed delivering ICE to StreamForegroundService")
                            }
                        }
                        "OFFER" -> {
                            // Remember streamer id to enforce viewer outbound routing later
                            if (fromId.isNotEmpty()) {
                                lastStreamerId = fromId
                                Timber.i("[Signaling] Learned lastStreamerId=$lastStreamerId from OFFER")
                            }
                        }
                        "SWITCH_CAMERA" -> {
                            Timber.i("[Signaling] SWITCH_CAMERA command received from=$fromId")
                            // Forward to StreamForegroundService
                            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
                            svc.putExtra("sigType", "SWITCH_CAMERA")
                            try {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                                Timber.d("[Signaling] Delivered SWITCH_CAMERA to StreamForegroundService")
                            } catch (e: Exception) {
                                Timber.e(e, "[Signaling] Failed delivering SWITCH_CAMERA")
                            }
                        }
                        "DISCONNECT" -> {
                            Timber.i("[Signaling] DISCONNECT command received from=$fromId")
                            // Forward to StreamForegroundService
                            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
                            svc.putExtra("sigType", "DISCONNECT")
                            try {
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                                Timber.d("[Signaling] Delivered DISCONNECT to StreamForegroundService")
                            } catch (e: Exception) {
                                Timber.e(e, "[Signaling] Failed delivering DISCONNECT")
                            }
                        }
                        else -> Timber.d("[Signaling] Unknown type: $msgType")
                    }
                } catch (t: Throwable) {
                    Timber.e(t, "[Signaling] onMessage parse error")
                }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "Signaling failed")
                    wsConnected = false
                }
            })
        }
    }

    // Outbound send requested by other services (e.g., streamer or viewer)
    intent?.getStringExtra("outSigType")?.let { outType ->
        val to = intent.getStringExtra("toDeviceId") ?: return@let
        val from = dev
        // Safety guard: never send signaling to self; viewer must target the streamer
        if (to.isNotEmpty() && deviceId != null && to == deviceId) {
            Timber.e("[Signaling] Refusing to send $outType to self (to=$to, me=${deviceId})")
            return@let
        }
        // Enforce viewer outbound routing to lastStreamerId when known
        var target = to
        if (outType == "ANSWER" || outType == "ICE" || outType == "SWITCH_CAMERA" || outType == "DISCONNECT") {
            if (!lastStreamerId.isNullOrEmpty() && target != lastStreamerId) {
                Timber.w("[Signaling] Outbound $outType target=$target overridden to lastStreamerId=$lastStreamerId")
                target = lastStreamerId!!
            }
        }
        val payload = when (outType) {
            "OFFER" -> {
                val sdp = intent.getStringExtra("sdp") ?: return@let
                // Mark streaming target for subsequent ANSWER/ICE gating
                streamingRemoteId = target
                Timber.i("[Signaling] Outbound OFFER -> $target; set streamingRemoteId=$streamingRemoteId")
                JSONObject().apply {
                    put("type", "OFFER"); put("sdp", sdp); put("toDeviceId", target); put("fromDeviceId", from)
                }.toString()
            }
            "ANSWER" -> {
                val sdp = intent.getStringExtra("sdp") ?: return@let
                JSONObject().apply {
                    put("type", "ANSWER"); put("sdp", sdp); put("toDeviceId", target); put("fromDeviceId", from)
                }.toString()
            }
            "ICE" -> {
                val cand = intent.getStringExtra("candidate") ?: return@let
                val mid = intent.getStringExtra("sdpMid")
                val idx = intent.getIntExtra("sdpMLineIndex", -1)
                JSONObject().apply {
                    put("type", "ICE"); put("candidate", cand); put("sdpMid", mid); put("sdpMLineIndex", idx); put("toDeviceId", target); put("fromDeviceId", from)
                }.toString()
            }
            "SWITCH_CAMERA" -> {
                Timber.i("[Signaling] Outbound SWITCH_CAMERA -> $target")
                JSONObject().apply {
                    put("type", "SWITCH_CAMERA"); put("toDeviceId", target); put("fromDeviceId", from)
                }.toString()
            }
            "DISCONNECT" -> {
                Timber.i("[Signaling] Outbound DISCONNECT -> $target")
                JSONObject().apply {
                    put("type", "DISCONNECT"); put("toDeviceId", target); put("fromDeviceId", from)
                }.toString()
            }
            else -> null
        }
        payload?.let {
            if (wsConnected) wsClient?.send(it) else outbox += it
            Timber.d("[Signaling] Outbound $outType queued=${!wsConnected} -> $target")
        }
        return START_STICKY
    }

    // Handle queued viewer ICE until peer is ready
    intent?.getStringExtra("sigType")?.let { sigType ->
        if (sigType == "ICE_QUEUE_VIEWER") {
            val cand = intent.getStringExtra("candidate")
            val mid = intent.getStringExtra("sdpMid")
            val idx = intent.getIntExtra("sdpMLineIndex", -1)
            if (!cand.isNullOrEmpty() && idx >= 0) {
                try {
                    val ice = org.webrtc.IceCandidate(mid, idx, cand)
                    viewerIceQueue += ice
                    Timber.i("[Signaling] Queued viewer ICE mid=$mid idx=$idx; size=${viewerIceQueue.size}")
                } catch (e: Exception) { Timber.e(e, "[Signaling] Failed to queue viewer ICE") }
            }
        } else if (sigType == "ICE_APPLY_VIEWER") {
            // Apply queued ICE to the viewer peer when ready
            try {
                val applied = viewerIceQueue.size
                val mgrField = com.manyeyes.webrtc.WebRtcManager::class.java // marker
                // We cannot directly access viewer's PC here; UI will pull from queue when ready.
                Timber.i("[Signaling] Viewer ICE queue ready to apply; count=$applied (UI will drain)")
            } catch (e: Exception) { Timber.e(e, "[Signaling] Failed during viewer ICE apply notification") }
        }
    }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ManyEyes Ready")
            .setContentText("Listening for monitor requests")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun ensureChannel(): String {
        val id = "manyeyes_signal_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                val ch = NotificationChannel(id, "Signaling", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Foreground service for signaling"
                ch.enableVibration(false)
                ch.enableLights(false)
                ch.lightColor = Color.BLUE
                nm.createNotificationChannel(ch)
            }
        }
        return id
    }

    /**
     * Launch the floating camera bubble Activity.
     * This keeps the app in a "foreground" state from Android's perspective,
     * allowing camera access even when the main Activity is backgrounded.
     * Required for Android 12+ which restricts background camera access.
     */
    private fun launchFloatingCameraBubble(remoteDeviceId: String) {
        try {
            // Check if we have overlay permission (SYSTEM_ALERT_WINDOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && 
                !android.provider.Settings.canDrawOverlays(this)) {
                Timber.w("[Signaling] No overlay permission, cannot launch floating bubble")
                return
            }
            
            val intent = FloatingCameraActivity.createIntent(this, remoteDeviceId)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Timber.i("[Signaling] Launched floating camera bubble for remote=$remoteDeviceId")
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Failed to launch floating camera bubble")
        }
    }

    /**
     * Close the floating camera bubble Activity when streaming stops.
     */
    private fun closeFloatingCameraBubble() {
        try {
            val intent = Intent(FloatingCameraActivity.ACTION_CLOSE_BUBBLE)
            intent.setPackage(packageName)
            sendBroadcast(intent)
            Timber.i("[Signaling] Sent close bubble broadcast")
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Failed to close floating camera bubble")
        }
    }

    private fun showIncomingRequestNotification(fromDeviceId: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        val text = "Camera/Mic requested by $fromDeviceId"
        val n = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Incoming Monitor Request")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIF_ID + 1, n)
    }

    companion object { private const val NOTIF_ID = 2001 }
}
