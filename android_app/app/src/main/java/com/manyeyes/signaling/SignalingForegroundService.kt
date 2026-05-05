package com.manyeyes.signaling

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.manyeyes.MainActivity
import com.manyeyes.TurnConfig
import com.manyeyes.streaming.FloatingCameraActivity
import com.manyeyes.streaming.StreamForegroundService
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import android.content.BroadcastReceiver
import android.content.IntentFilter
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
    // Location tracking
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var locationCallback: LocationCallback? = null
    private var locationTrackingForDevice: String? = null // which remote device requested our location
    // Screen sharing
    private var screenShareStreamer: ScreenShareStreamer? = null
    private var pendingScreenRemoteId: String? = null
    private var screenCaptureReceiver: BroadcastReceiver? = null

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
        // Initialize location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        // Register broadcast receiver for screen capture consent result
        registerScreenCaptureReceiver()
        instance = this
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
                            "TOGGLE_FLASH" -> handleToggleFlash(fromId)
                            "DISCONNECT" -> handleDisconnect(fromId)
                            "REQUEST_LOCATION" -> handleRequestLocation(fromId)
                            "STOP_LOCATION" -> handleStopLocation(fromId)
                            "LOCATION" -> { /* Viewer receives this — handled in AppRoot UI */ }
                            "REQUEST_SCREEN" -> handleRequestScreen(fromId)
                            "SCREEN_ANSWER" -> handleScreenAnswer(j, fromId)
                            "SCREEN_ICE" -> handleScreenIce(j, fromId)
                            "STOP_SCREEN" -> handleStopScreen(fromId)
                            "REQUEST_NOTIFICATIONS" -> handleRequestNotifications(fromId)
                            "STOP_NOTIFICATIONS" -> handleStopNotifications(fromId)
                            "SCREEN_OFFER" -> { /* Viewer receives this — handled in AppRoot UI */ }
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
                if (success) sendFlashStatus(fromId, streamer.isFrontFacing(), streamer.isFlashOn())
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

    // ── Flashlight ──────────────────────────────────────────────────────
    // Service-level flash state used only when no streamer is active (so the
    // user can still toggle the LED via Camera2 from the device list).
    private var serviceFlashOn: Boolean = false

    private fun handleToggleFlash(fromId: String) {
        Timber.i("[Signaling] TOGGLE_FLASH command received from=$fromId")
        val streamer = embeddedStreamer
        if (streamer != null && streamer.isStreaming()) {
            val newState = streamer.toggleFlash()
            Timber.i("[Signaling] Flash via EmbeddedStreamer: $newState (frontFacing=${streamer.isFrontFacing()})")
            sendFlashStatus(fromId, streamer.isFrontFacing(), newState)
        } else {
            // No active stream — drive Camera2 directly so the user can still
            // toggle the LED from the viewer side.
            serviceFlashOn = !serviceFlashOn
            val applied = setServiceTorch(serviceFlashOn)
            if (!applied) serviceFlashOn = false
            sendFlashStatus(fromId, isFrontCamera = true, flashOn = serviceFlashOn)
        }
    }

    private fun setServiceTorch(enable: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE)
                    as android.hardware.camera2.CameraManager
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val facing = chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                if (facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) continue
                val hasFlash = chars.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) ?: false
                if (!hasFlash) continue
                cm.setTorchMode(id, enable)
                Timber.i("[Signaling] setTorchMode($enable) cameraId=$id")
                return true
            }
            Timber.w("[Signaling] No back camera with flash available")
            false
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Direct torch toggle failed")
            false
        }
    }

    private fun sendFlashStatus(toId: String, isFrontCamera: Boolean, flashOn: Boolean) {
        val payload = JSONObject().apply {
            put("type", "FLASH_STATUS")
            put("toDeviceId", toId)
            put("isFrontCamera", isFrontCamera)
            put("flashOn", flashOn)
        }.toString()
        if (wsConnected) wsClient?.send(payload) else outbox += payload
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
        if (outType == "ANSWER" || outType == "ICE" || outType == "SWITCH_CAMERA" || outType == "TOGGLE_FLASH" || outType == "DISCONNECT") {
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
            "TOGGLE_FLASH" -> {
                Timber.i("[Signaling] Outbound TOGGLE_FLASH -> $target")
                JSONObject().apply {
                    put("type", "TOGGLE_FLASH"); put("toDeviceId", target); put("fromDeviceId", dev)
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

    // ─── Location Tracking ───────────────────────────────────────────────

    private fun handleRequestLocation(fromId: String) {
        Timber.i("[Signaling] REQUEST_LOCATION from=$fromId — starting GPS updates every 5s")
        locationTrackingForDevice = fromId

        // Check location permission
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Timber.e("[Signaling] Location permission not granted — cannot track")
            // Send error back
            val errorPayload = JSONObject().apply {
                put("type", "LOCATION")
                put("toDeviceId", fromId)
                put("fromDeviceId", deviceId)
                put("error", "Location permission not granted on this device")
            }.toString()
            if (wsConnected) wsClient?.send(errorPayload) else outbox += errorPayload
            return
        }

        // Upgrade foreground service type to include location
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                }
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIF_ID, buildNotification(), fgsType)
                Timber.i("[Signaling] Upgraded FGS type to include location")
            }
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Failed to upgrade FGS type for location")
        }

        // Stop any existing location updates
        stopLocationUpdates()

        // Request periodic location updates every 5 seconds
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                Timber.i("[Signaling] GPS: lat=${loc.latitude} lng=${loc.longitude} acc=${loc.accuracy}m")
                val targetDevice = locationTrackingForDevice ?: return
                val payload = JSONObject().apply {
                    put("type", "LOCATION")
                    put("toDeviceId", targetDevice)
                    put("fromDeviceId", deviceId)
                    put("latitude", loc.latitude)
                    put("longitude", loc.longitude)
                    put("accuracy", loc.accuracy.toDouble())
                    put("altitude", loc.altitude)
                    put("speed", loc.speed.toDouble())
                    put("bearing", loc.bearing.toDouble())
                    put("timestamp", System.currentTimeMillis())
                }.toString()
                if (wsConnected) wsClient?.send(payload) else outbox += payload
                Timber.d("[Signaling] LOCATION sent -> $targetDevice")
            }
        }

        try {
            fusedLocationClient?.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )
            Timber.i("[Signaling] Location updates started for remote=$fromId")
        } catch (e: SecurityException) {
            Timber.e(e, "[Signaling] SecurityException requesting location updates")
        }
    }

    private fun handleStopLocation(fromId: String) {
        Timber.i("[Signaling] STOP_LOCATION from=$fromId — stopping GPS updates")
        stopLocationUpdates()
        locationTrackingForDevice = null
    }

    private fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient?.removeLocationUpdates(it)
            Timber.i("[Signaling] Location updates stopped")
        }
        locationCallback = null
    }

    override fun onDestroy() {
        Timber.i("[Signaling] onDestroy()")
        instance = null
        stopLocationUpdates()
        embeddedStreamer?.stopStreaming()
        embeddedStreamer = null
        screenShareStreamer?.stopSharing()
        screenShareStreamer = null
        try { screenCaptureReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        wsClient?.close()
        wsClient = null
        mainHandler.removeCallbacksAndMessages(null)
        try {
            wakeLock?.release()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ─── Screen Sharing ──────────────────────────────────────────────────

    private fun registerScreenCaptureReceiver() {
        screenCaptureReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != com.manyeyes.streaming.ScreenCaptureActivity.ACTION_SCREEN_CAPTURE_RESULT) return
                val resultCode = intent.getIntExtra(com.manyeyes.streaming.ScreenCaptureActivity.EXTRA_RESULT_CODE, -1)
                // Pull the raw consent Intent from process-memory -- the
                // broadcast no longer carries it because Binder re-parcelling
                // strips the embedded MediaProjection token. See
                // ScreenCaptureActivity.pendingResultData for full rationale.
                val resultData = com.manyeyes.streaming.ScreenCaptureActivity.pendingResultData
                com.manyeyes.streaming.ScreenCaptureActivity.pendingResultData = null
                val remoteId = intent.getStringExtra(com.manyeyes.streaming.ScreenCaptureActivity.EXTRA_REMOTE_DEVICE_ID) ?: ""

                // Dismiss the full-screen-intent notification regardless of
                // outcome (user may have tapped the notification or denied
                // the system consent dialog).
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(SCREEN_CONSENT_NOTIF_ID)
                } catch (_: Exception) {}

                if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                    Timber.i("[Signaling] Screen capture consented for remote=$remoteId")

                    // Upgrade FGS type to include mediaProjection
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or
                                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                            }
                            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            }
                            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                            }
                            startForeground(NOTIF_ID, buildNotification(), fgsType)
                            Timber.i("[Signaling] Upgraded FGS type to include mediaProjection")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "[Signaling] Failed to upgrade FGS for mediaProjection")
                    }

                    // Start the screen share streamer
                    val myId = deviceId ?: return
                    val streamer = ScreenShareStreamer(this@SignalingForegroundService) { type, toId, data ->
                        val payload = JSONObject().apply {
                            put("type", type)
                            put("toDeviceId", toId)
                            put("fromDeviceId", myId)
                            data.forEach { (k, v) -> put(k, v) }
                        }.toString()
                        if (wsConnected) wsClient?.send(payload) else outbox += payload
                    }
                    screenShareStreamer = streamer
                    streamer.startSharing(remoteId, myId, resultCode, resultData)
                } else {
                    Timber.w("[Signaling] Screen capture denied for remote=$remoteId")
                }
            }
        }

        val filter = IntentFilter(com.manyeyes.streaming.ScreenCaptureActivity.ACTION_SCREEN_CAPTURE_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenCaptureReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenCaptureReceiver, filter)
        }
        Timber.i("[Signaling] Screen capture broadcast receiver registered")
    }

    private fun handleRequestScreen(fromId: String) {
        Timber.i("[Signaling] REQUEST_SCREEN from=$fromId -- launching consent dialog")
        pendingScreenRemoteId = fromId

        val captureIntent = Intent(this, com.manyeyes.streaming.ScreenCaptureActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            putExtra(com.manyeyes.streaming.ScreenCaptureActivity.EXTRA_REMOTE_DEVICE_ID, fromId)
        }

        // Wrap the consent Activity in a full-screen-intent notification.
        // Android 14+ silently blocks plain startActivity() calls from a
        // backgrounded foreground service (Background Activity Launch) --
        // that was why the target device never displayed the consent prompt.
        // A high-priority notification with setFullScreenIntent is the
        // sanctioned escape-hatch (USE_FULL_SCREEN_INTENT is declared in
        // the manifest). Mirrors launchFullScreenCameraIntent above.
        try {
            val fullScreenPi = PendingIntent.getActivity(
                this, 200, captureIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val tapPi = PendingIntent.getActivity(
                this, 201, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val channelId = ensureHighPriorityChannel()
            val notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Screen Share Request")
                .setContentText("Tap to allow screen sharing to $fromId")
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(tapPi)
                .setAutoCancel(true)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(SCREEN_CONSENT_NOTIF_ID, notification)
            Timber.i("[Signaling] Full-screen consent notification posted for remote=$fromId")
        } catch (e: Exception) {
            Timber.e(e, "[Signaling] Failed to post consent notification")
        }

        // Best-effort direct launch -- works on older Android / when the app
        // is already in the foreground. Harmless duplicate on newer Android
        // because ScreenCaptureActivity is launchMode=singleInstance.
        try {
            startActivity(captureIntent)
            Timber.i("[Signaling] Direct ScreenCaptureActivity launch attempted")
        } catch (e: Exception) {
            Timber.w(e, "[Signaling] Direct ScreenCaptureActivity launch failed (expected on Android 14+); full-screen intent will handle it")
        }
    }

    private fun handleScreenAnswer(j: JSONObject, fromId: String) {
        val sdp = j.optString("sdp", "")
        if (sdp.isEmpty()) {
            Timber.w("[Signaling] SCREEN_ANSWER with empty SDP from=$fromId")
            return
        }
        Timber.i("[Signaling] SCREEN_ANSWER from=$fromId")
        screenShareStreamer?.handleAnswer(sdp)
    }

    private fun handleScreenIce(j: JSONObject, fromId: String) {
        val mid = j.optString("sdpMid", "")
        val idx = j.optInt("sdpMLineIndex", 0)
        val cand = j.optString("candidate", "")
        if (cand.isEmpty()) return
        Timber.d("[Signaling] SCREEN_ICE from=$fromId mid=$mid")
        screenShareStreamer?.handleIce(org.webrtc.IceCandidate(mid, idx, cand))
    }

    private fun handleStopScreen(fromId: String) {
        Timber.i("[Signaling] STOP_SCREEN from=$fromId — stopping screen share")
        screenShareStreamer?.stopSharing()
        screenShareStreamer = null
    }

    private fun handleRequestNotifications(fromId: String) {
        Timber.i("[Signaling] REQUEST_NOTIFICATIONS from=$fromId")
        notificationViewerId = fromId
        // Send current active notifications
        val arr = com.manyeyes.signaling.NotificationCaptureService.instance?.getAllActiveNotificationsAsJson() ?: org.json.JSONArray()
        val msg = org.json.JSONObject().apply {
            put("type", "NOTIFICATION_DATA")
            put("toDeviceId", fromId)
            put("notifications", arr)
        }.toString()
        sendWsMessage(msg)
    }

    private fun handleStopNotifications(fromId: String) {
        Timber.i("[Signaling] STOP_NOTIFICATIONS from=$fromId")
        if (notificationViewerId == fromId) {
            notificationViewerId = null
        }
    }

    fun sendWsMessage(msg: String) {
        if (wsConnected && wsClient != null) {
            wsClient?.send(msg)
        } else {
            outbox.add(msg)
        }
    }

    companion object {
        private const val NOTIF_ID = 2001
        private const val INCOMING_NOTIF_ID = 2010
        private const val SCREEN_CONSENT_NOTIF_ID = 2011
        var instance: SignalingForegroundService? = null
        var notificationViewerId: String? = null
    }
}
