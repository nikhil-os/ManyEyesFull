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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.manyeyes.MainActivity
import com.manyeyes.TurnConfig
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
    // Embedded streamer — runs WebRTC camera capture inside this service
    private var embeddedStreamer: EmbeddedStreamer? = null
    // WebSocket reconnection
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private var reconnecting = false
    private val maxReconnectDelay = 30_000L // 30 seconds max
    private val baseReconnectDelay = 2_000L // 2 seconds initial
    // Wake lock to keep CPU alive for signaling
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Acquire a partial wake lock so the CPU stays alive for WebSocket signaling
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ManyEyes::SignalingWakeLock")
            wakeLock?.acquire()
            Timber.i("[Signaling] Wake lock acquired")
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Failed to acquire wake lock")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Start as dataSync initially; will upgrade to camera|mic when streaming starts
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
            embeddedStreamer?.stopStreaming()
            // Close floating camera bubble when streaming stops
            closeFloatingCameraBubble()
            // Downgrade foreground type back to dataSync
            downgradeForegroundType()
        }

        val tkn = token ?: run { Timber.d("[Signaling] using existing token"); token }
        val dev = deviceId ?: run { Timber.d("[Signaling] using existing deviceId"); deviceId }
        val wsUrl = baseWs ?: run { Timber.d("[Signaling] using existing baseWs"); baseWs }

        // Establish or reuse persistent WS
        if (wsClient == null && wsUrl != null && tkn != null && dev != null) {
            Timber.i("[Signaling] Starting with deviceId=$dev url=$wsUrl")
            connectWebSocket(wsUrl, tkn, dev)
        }

        // Outbound send requested by other services (e.g., streamer or viewer)
        intent?.getStringExtra("outSigType")?.let { outType ->
            handleOutboundSignaling(intent, outType, dev)
            return START_STICKY
        }

        // Handle forwarded signaling from StreamForegroundService (legacy path)
        intent?.getStringExtra("sigType")?.let { sigType ->
            handleInboundSignalingForward(intent, sigType)
        }

        return START_STICKY
    }

    /**
     * Connect WebSocket with automatic reconnection on failure.
     */
    private fun connectWebSocket(wsUrl: String, tkn: String, dev: String) {
        reconnecting = false
        wsClient = WsClient(wsUrl, tkn, dev).also { client ->
            client.connect(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.i("[Signaling] WebSocket connected")
                    wsConnected = true
                    reconnectAttempt = 0 // Reset backoff on successful connection
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
                            "REQUEST_STREAM" -> handleRequestStream(fromId, tkn, dev, wsUrl)
                            "ANSWER" -> handleAnswer(j, fromId)
                            "ICE" -> handleIce(j, fromId)
                            "OFFER" -> handleOffer(j, fromId)
                            "SWITCH_CAMERA" -> handleSwitchCamera(fromId)
                            "DISCONNECT" -> handleDisconnect(fromId)
                            else -> Timber.d("[Signaling] Unknown type: $msgType")
                        }
                    } catch (t: Throwable) {
                        Timber.e(t, "[Signaling] onMessage parse error")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.w("[Signaling] WebSocket closing: code=$code reason=$reason")
                    wsConnected = false
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Timber.w("[Signaling] WebSocket closed: code=$code reason=$reason")
                    wsConnected = false
                    wsClient = null
                    scheduleReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Timber.e(t, "[Signaling] WebSocket failed")
                    wsConnected = false
                    wsClient = null
                    scheduleReconnect()
                }
            })
        }
    }

    /**
     * Reconnect WebSocket with exponential backoff.
     */
    private fun scheduleReconnect() {
        if (reconnecting) return
        val tkn = token ?: return
        val dev = deviceId ?: return
        val wsUrl = baseWs ?: return
        reconnecting = true
        reconnectAttempt++
        val delay = (baseReconnectDelay * (1L shl minOf(reconnectAttempt, 4))).coerceAtMost(maxReconnectDelay)
        Timber.i("[Signaling] Scheduling reconnect #$reconnectAttempt in ${delay}ms")
        mainHandler.postDelayed({
            if (wsClient == null) {
                Timber.i("[Signaling] Reconnecting now (attempt #$reconnectAttempt)")
                connectWebSocket(wsUrl, tkn, dev)
            } else {
                reconnecting = false
            }
        }, delay)
    }

    // ─── Message Handlers ────────────────────────────────────────────────

    private fun handleRequestStream(fromId: String, tkn: String, dev: String, wsUrl: String) {
        Timber.i("[Signaling] REQUEST_STREAM from=$fromId -> starting embedded camera")

        // Show heads-up notification
        showIncomingRequestNotification(fromId)

        // Strategy: Launch full-screen intent to bring app to foreground,
        // then start embedded camera capture.
        // On Android 12+, we can't start camera FGS from background,
        // so the full-screen intent is essential.
        launchFullScreenCameraIntent(fromId)

        // Upgrade this service's foreground type to include camera/mic
        upgradeForegroundType()

        // Initialize embedded streamer if needed
        if (embeddedStreamer == null) {
            embeddedStreamer = EmbeddedStreamer(this@SignalingForegroundService) { type, toDeviceId, data ->
                // Callback to send signaling messages out
                val payload = JSONObject().apply {
                    put("type", type)
                    put("toDeviceId", toDeviceId)
                    put("fromDeviceId", dev)
                    data.forEach { (k, v) -> put(k, v) }
                }.toString()
                if (wsConnected) wsClient?.send(payload) else outbox += payload
                Timber.d("[Signaling] EmbeddedStreamer outbound $type -> $toDeviceId queued=${!wsConnected}")
            }
        }

        // Start embedded streaming to the requesting device
        embeddedStreamer?.startStreaming(fromId, dev)
        streamingRemoteId = fromId
        streamerActive = true
        Timber.i("[Signaling] Embedded streamer started for remote=$fromId")
    }

    private fun handleAnswer(j: JSONObject, fromId: String) {
        val sdp = j.optString("sdp")
        Timber.i("[Signaling] ANSWER received from=$fromId streamerActive=$streamerActive")

        // Debounce duplicate ANSWER
        val fp = sdp.hashCode().toString()
        if (lastAnswerFingerprint == fp) {
            Timber.i("[Signaling] ANSWER duplicate ignored fp=$fp from=$fromId")
            return
        }
        lastAnswerFingerprint = fp

        // Forward to embedded streamer first
        val streamer = embeddedStreamer
        if (streamer != null && streamer.isStreaming()) {
            Timber.i("[Signaling] Forwarding ANSWER to EmbeddedStreamer from=$fromId")
            streamer.handleAnswer(sdp, fromId)
        } else {
            // Fallback: forward to StreamForegroundService (legacy path)
            Timber.i("[Signaling] Forward ANSWER to StreamForegroundService from=$fromId len=${sdp.length}")
            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
            svc.putExtra("sigType", "ANSWER")
            svc.putExtra("sdp", sdp)
            svc.putExtra("remoteDeviceId", fromId)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            } catch (e: Exception) {
                Timber.e(e, "[Signaling] Failed delivering ANSWER to StreamForegroundService")
            }
        }
    }

    private fun handleIce(j: JSONObject, fromId: String) {
        val cand = j.optString("candidate")
        val mid = j.optString("sdpMid")
        val idx = j.optInt("sdpMLineIndex")
        Timber.i("[Signaling] ICE received from=$fromId mid=$mid")

        // Forward to embedded streamer first
        val streamer = embeddedStreamer
        if (streamer != null && streamer.isStreaming()) {
            Timber.d("[Signaling] Forwarding ICE to EmbeddedStreamer from=$fromId")
            streamer.handleIce(cand, mid, idx, fromId)
        } else {
            // Fallback: forward to StreamForegroundService (legacy)
            Timber.i("[Signaling] Forward ICE to StreamForegroundService from=$fromId mid=$mid")
            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
            svc.putExtra("sigType", "ICE")
            svc.putExtra("candidate", cand)
            svc.putExtra("sdpMid", mid)
            svc.putExtra("sdpMLineIndex", idx)
            svc.putExtra("remoteDeviceId", fromId)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            } catch (e: Exception) {
                Timber.e(e, "[Signaling] Failed delivering ICE to StreamForegroundService")
            }
        }
    }

    private fun handleOffer(j: JSONObject, fromId: String) {
        // Remember streamer id to enforce viewer outbound routing later
        if (fromId.isNotEmpty()) {
            lastStreamerId = fromId
            Timber.i("[Signaling] Learned lastStreamerId=$lastStreamerId from OFFER")
        }
    }

    private fun handleSwitchCamera(fromId: String) {
        Timber.i("[Signaling] SWITCH_CAMERA command received from=$fromId")
        val streamer = embeddedStreamer
        if (streamer != null && streamer.isStreaming()) {
            streamer.switchCamera { success ->
                Timber.i("[Signaling] EmbeddedStreamer camera switch: $success")
            }
        } else {
            // Fallback to StreamForegroundService
            val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
            svc.putExtra("sigType", "SWITCH_CAMERA")
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            } catch (e: Exception) {
                Timber.e(e, "[Signaling] Failed delivering SWITCH_CAMERA")
            }
        }
    }

    private fun handleDisconnect(fromId: String) {
        Timber.i("[Signaling] DISCONNECT command received from=$fromId")
        val streamer = embeddedStreamer
        if (streamer != null && streamer.isStreaming()) {
            streamer.stopStreaming()
            Timber.i("[Signaling] EmbeddedStreamer stopped")
        }
        // Also forward to StreamForegroundService if it's running
        val svc = Intent(this@SignalingForegroundService, StreamForegroundService::class.java)
        svc.putExtra("sigType", "DISCONNECT")
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
        } catch (_: Exception) {}

        // Reset streaming state
        streamerActive = false
        streamingRemoteId = null
        lastAnswerFingerprint = null
        closeFloatingCameraBubble()
        downgradeForegroundType()
    }

    // ─── Outbound Signaling ─────────────────────────────────────────────

    private fun handleOutboundSignaling(intent: Intent, outType: String, dev: String?) {
        val to = intent.getStringExtra("toDeviceId") ?: return
        // Safety guard: never send signaling to self
        if (to.isNotEmpty() && deviceId != null && to == deviceId) {
            Timber.e("[Signaling] Refusing to send $outType to self (to=$to, me=$deviceId)")
            return
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
                val sdp = intent.getStringExtra("sdp") ?: return
                streamingRemoteId = target
                Timber.i("[Signaling] Outbound OFFER -> $target; set streamingRemoteId=$streamingRemoteId")
                JSONObject().apply {
                    put("type", "OFFER"); put("sdp", sdp); put("toDeviceId", target); put("fromDeviceId", dev)
                }.toString()
            }
            "ANSWER" -> {
                val sdp = intent.getStringExtra("sdp") ?: return
                JSONObject().apply {
                    put("type", "ANSWER"); put("sdp", sdp); put("toDeviceId", target); put("fromDeviceId", dev)
                }.toString()
            }
            "ICE" -> {
                val cand = intent.getStringExtra("candidate") ?: return
                val mid = intent.getStringExtra("sdpMid")
                val idx = intent.getIntExtra("sdpMLineIndex", -1)
                JSONObject().apply {
                    put("type", "ICE"); put("candidate", cand); put("sdpMid", mid); put("sdpMLineIndex", idx); put("toDeviceId", target); put("fromDeviceId", dev)
                }.toString()
            }
            "SWITCH_CAMERA" -> {
                Timber.i("[Signaling] Outbound SWITCH_CAMERA -> $target")
                JSONObject().apply {
                    put("type", "SWITCH_CAMERA"); put("toDeviceId", target); put("fromDeviceId", dev)
                }.toString()
            }
            "DISCONNECT" -> {
                Timber.i("[Signaling] Outbound DISCONNECT -> $target")
                JSONObject().apply {
                    put("type", "DISCONNECT"); put("toDeviceId", target); put("fromDeviceId", dev)
                }.toString()
            }
            else -> null
        }
        payload?.let {
            if (wsConnected) wsClient?.send(it) else outbox += it
            Timber.d("[Signaling] Outbound $outType queued=${!wsConnected} -> $target")
        }
    }

    private fun handleInboundSignalingForward(intent: Intent, sigType: String) {
        when (sigType) {
            "ICE_QUEUE_VIEWER" -> {
                val cand = intent.getStringExtra("candidate")
                val mid = intent.getStringExtra("sdpMid")
                val idx = intent.getIntExtra("sdpMLineIndex", -1)
                if (!cand.isNullOrEmpty() && idx >= 0) {
                    try {
                        val ice = org.webrtc.IceCandidate(mid, idx, cand)
                        viewerIceQueue += ice
                        Timber.i("[Signaling] Queued viewer ICE mid=$mid idx=$idx; size=${viewerIceQueue.size}")
                    } catch (e: Exception) {
                        Timber.e(e, "[Signaling] Failed to queue viewer ICE")
                    }
                }
            }
            "SWITCH_CAMERA_LOCAL" -> {
                // Triggered by FloatingCameraActivity UI button
                Timber.i("[Signaling] SWITCH_CAMERA_LOCAL from FloatingCameraActivity")
                embeddedStreamer?.switchCamera { success ->
                    Timber.i("[Signaling] EmbeddedStreamer local camera switch: $success")
                }
            }
        }
    }

    // ─── Foreground Type Management ─────────────────────────────────────

    /**
     * Upgrade foreground service type to include camera + mic for streaming.
     */
    private fun upgradeForegroundType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                val camGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (camGranted) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (micGranted) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIF_ID, buildStreamingNotification(), fgsType)
                Timber.i("[Signaling] Upgraded FGS type to camera=$camGranted mic=$micGranted")
            } catch (e: Exception) {
                Timber.e(e, "[Signaling] Failed to upgrade FGS type (expected on Android 12+ from background)")
            }
        }
    }

    /**
     * Downgrade back to dataSync only.
     */
    private fun downgradeForegroundType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                Timber.i("[Signaling] Downgraded FGS type to dataSync")
            } catch (e: Exception) {
                Timber.e(e, "[Signaling] Failed to downgrade FGS type")
            }
        }
    }

    // ─── Notifications ──────────────────────────────────────────────────

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

    private fun buildStreamingNotification(): Notification {
        val channelId = ensureChannel()
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("ManyEyes Streaming")
            .setContentText("Camera/Mic streaming active")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
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

    private fun ensureHighPriorityChannel(): String {
        val id = "manyeyes_incoming_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(id) == null) {
                val ch = NotificationChannel(id, "Incoming Requests", NotificationManager.IMPORTANCE_HIGH)
                ch.description = "Notifications for incoming monitoring requests"
                ch.enableVibration(true)
                ch.enableLights(true)
                ch.lightColor = Color.RED
                ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                nm.createNotificationChannel(ch)
            }
        }
        return id
    }

    // ─── Full-Screen Intent (Camera Activation) ─────────────────────────

    /**
     * Launch a full-screen intent to bring the app to foreground.
     * This works even on Android 12+/14+ lock screens.
     * The Activity auto-starts camera capture on creation.
     */
    private fun launchFullScreenCameraIntent(remoteDeviceId: String) {
        try {
            val intent = FloatingCameraActivity.createIntent(this, remoteDeviceId, showPreview = false)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)

            val fullScreenPi = PendingIntent.getActivity(
                this, 100, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val tapPi = PendingIntent.getActivity(
                this, 101, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val channelId = ensureHighPriorityChannel()
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Monitoring Active")
                .setContentText("Camera streaming to $remoteDeviceId")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(tapPi)
                .setOngoing(true)
                .setAutoCancel(false)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(INCOMING_NOTIF_ID, notification)
            Timber.i("[Signaling] Full-screen camera intent launched for remote=$remoteDeviceId")

            // Also try direct Activity launch as fallback
            try {
                startActivity(intent)
                Timber.i("[Signaling] Direct Activity launch succeeded")
            } catch (e: Exception) {
                Timber.w(e, "[Signaling] Direct Activity launch failed (expected on Android 14+); full-screen intent will handle it")
            }
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Failed to launch full-screen camera intent")
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
            // Also dismiss the incoming notification
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(INCOMING_NOTIF_ID)
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Failed to close floating camera bubble")
        }
    }

    private fun showIncomingRequestNotification(fromDeviceId: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = ensureHighPriorityChannel()
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

    override fun onDestroy() {
        Timber.i("[Signaling] Service destroyed")
        embeddedStreamer?.stopStreaming()
        embeddedStreamer = null
        wsClient?.close()
        wsClient = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 2001
        private const val INCOMING_NOTIF_ID = 2010
    }
}
