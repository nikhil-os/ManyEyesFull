package com.manyeyes.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.manyeyes.network.*
import com.manyeyes.data.Prefs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import com.manyeyes.signaling.WsClient
import timber.log.Timber
import android.content.Intent
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.EglBase
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.content.Context
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.media.MediaPlayer
import android.media.RingtoneManager

@Composable
fun AppRoot() {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    var token by remember { mutableStateOf<String?>(null) }
    var deviceId by remember { mutableStateOf<String?>(null) }
    var baseUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        prefs.tokenFlow.collect { token = it }
    }
    LaunchedEffect(Unit) {
        prefs.deviceIdFlow.collect { deviceId = it }
    }
    LaunchedEffect(Unit) {
        prefs.baseUrlFlow.collect {
            // Auto-migrate old emulator-only URL to the deployed Render URL
            val migrated = when {
                it == null -> "https://manyeyes.onrender.com"
                it.contains("10.0.2.2") -> "https://manyeyes.onrender.com"
                it.contains("manyeyes-pxvf") -> "https://manyeyes.onrender.com"
                else -> it
            }
            baseUrl = migrated
            if (migrated != it) {
                // persist migration so future launches use Render URL
                prefs.setBaseUrl(migrated)
            }
        }
    }

    if (token == null || deviceId == null || baseUrl == null) {
        LoginScreen(onLoggedIn = { t, id, url ->
            scope.launch {
                prefs.setToken(t)
                prefs.setDeviceId(id)
                prefs.setBaseUrl(url)
                // Start persistent signaling service so device can be controlled in background
                val baseWsRaw = url.replaceFirst("http", "ws")
                val baseWs = if (url.startsWith("https://")) baseWsRaw.replaceFirst("ws://", "wss://") else baseWsRaw
                val s = Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
                s.putExtra("token", t)
                s.putExtra("deviceId", id)
                s.putExtra("baseWs", baseWs)
                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s) else ctx.startService(s)
            }
        })
    } else {
        // Ensure background signaling runs even if user reopened the app later
        LaunchedEffect(token, deviceId, baseUrl) {
            val baseWsRaw = baseUrl!!.replaceFirst("http", "ws")
            val baseWs = if (baseUrl!!.startsWith("https://")) baseWsRaw.replaceFirst("ws://", "wss://") else baseWsRaw
            val s = Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
            s.putExtra("token", token)
            s.putExtra("deviceId", deviceId)
            s.putExtra("baseWs", baseWs)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(s) else ctx.startService(s)
        }
        DeviceListScreen(token!!, deviceId!!, baseUrl!!)
    }
}

@Composable
fun LoginScreen(onLoggedIn: (String, String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL) }
    var baseUrl by remember { mutableStateOf("https://manyeyes.onrender.com") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("ManyEyes Login", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = deviceName, onValueChange = { deviceName = it }, label = { Text("Device Name") })
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text("Backend URL") })
        Spacer(Modifier.height(16.dp))
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)
        Button(enabled = !loading, onClick = {
            loading = true; error = null
            scope.launch {
                try {
                    val api = ServiceBuilder.api(baseUrl)
                    val req = LoginReq(email, password, deviceName, null)
                    var res: LoginRes
                    try {
                        res = api.login(req)
                    } catch (e: retrofit2.HttpException) {
                        if (e.code() == 401) {
                            // auto-register then retry login to get token
                            val reg = api.register(req)
                            if (!reg.isSuccessful) throw e
                            res = api.login(req)
                        } else throw e
                    }
                    onLoggedIn(res.token, res.deviceId, baseUrl)
                } catch (e: Exception) {
                    error = e.message
                } finally { loading = false }
            }
        }) { Text(if (loading) "Logging in..." else "Login") }
        Spacer(Modifier.height(8.dp))
        Text("Permissions will be requested on first launch and remembered.")
    }
}

@Composable
fun DeviceListScreen(token: String, deviceId: String, baseUrl: String) {
    val baseWs = remember(baseUrl) { baseUrl.replaceFirst("http", "ws") }
    // If using HTTPS, ws scheme should be wss
    val secureWs = remember(baseWs) { if (baseWs.startsWith("ws://") && baseUrl.startsWith("https://")) baseWs.replaceFirst("ws://", "wss://") else baseWs }
    val scope = rememberCoroutineScope()
    var wsClient by remember { mutableStateOf<WsClient?>(null) }
    var status by remember { mutableStateOf("Connecting...") }
    var devices by remember { mutableStateOf<List<DeviceDto>>(emptyList()) }
    val api = remember(baseUrl) { ServiceBuilder.api(baseUrl) }
    val ctx = LocalContext.current
    var rendererView by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var videoDebug by remember { mutableStateOf("Waiting for video...") }
    val eglBase by remember { mutableStateOf(EglBase.create()) }
    var webrtcViewer by remember { mutableStateOf<com.manyeyes.webrtc.WebRtcManager?>(null) }
    // Track the streamer deviceId from the last OFFER to ensure correct targeting
    var lastStreamerId by remember { mutableStateOf<String?>(null) }
    // Queue for ICE received before the viewer's PeerConnection is ready
    val pendingViewerIce = remember { mutableListOf<org.webrtc.IceCandidate>() }
    // Track if we're currently viewing a stream
    var isViewingStream by remember { mutableStateOf(false) }
    // Location tracking state
    var isTrackingLocation by remember { mutableStateOf(false) }
    var trackingDeviceId by remember { mutableStateOf<String?>(null) }
    var trackingDeviceName by remember { mutableStateOf("") }
    var remoteLat by remember { mutableStateOf(0.0) }
    var remoteLng by remember { mutableStateOf(0.0) }
    var remoteAccuracy by remember { mutableStateOf(0f) }
    var remoteSpeed by remember { mutableStateOf(0f) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var showMapDialog by remember { mutableStateOf(false) }
    // SOS state
    var showSosAlert by remember { mutableStateOf(false) }
    var sosFromDevice by remember { mutableStateOf("") }
    var sosLat by remember { mutableStateOf(0.0) }
    var sosLng by remember { mutableStateOf(0.0) }
    // Battery & network status from remote devices
    val remoteBattery = remember { mutableStateMapOf<String, Int>() }       // deviceId -> battery %
    val remoteCharging = remember { mutableStateMapOf<String, Boolean>() }   // deviceId -> isCharging
    val remoteNetwork = remember { mutableStateMapOf<String, String>() }     // deviceId -> network type
    // Screen sharing state
    var isViewingScreen by remember { mutableStateOf(false) }
    var screenStreamerId by remember { mutableStateOf<String?>(null) }
    var webrtcScreenViewer by remember { mutableStateOf<com.manyeyes.webrtc.WebRtcManager?>(null) }
    val pendingScreenIce = remember { mutableListOf<org.webrtc.IceCandidate>() }

    // Function to send control commands to the streamer via SignalingForegroundService
    fun sendControlCommand(command: String) {
        val targetId = lastStreamerId
        if (targetId.isNullOrEmpty()) {
            Timber.w("[Viewer] Cannot send $command - no streamer connected")
            return
        }
        Timber.i("[Viewer] Sending $command to streamer $targetId")
        val svc = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
        svc.putExtra("outSigType", command)
        svc.putExtra("toDeviceId", targetId)
        if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
    }

    // Function to disconnect from the stream
    fun disconnectStream() {
        Timber.i("[Viewer] Disconnecting from stream")
        val targetId = lastStreamerId

        // Clean up local WebRTC first
        try {
            webrtcViewer?.dispose()
        } catch (e: Exception) {
            Timber.e(e, "[Viewer] Error disposing WebRTC")
        }
        webrtcViewer = null

        // Clear renderer
        try {
            rendererView?.clearImage()
        } catch (_: Exception) {}

        // Clear pending ICE
        pendingViewerIce.clear()

        // Reset state
        isViewingStream = false
        lastStreamerId = null
        videoDebug = "Disconnected"
        status = "Connected"

        // Send DISCONNECT command to streamer
        if (!targetId.isNullOrEmpty()) {
            Timber.i("[Viewer] Sending DISCONNECT to streamer $targetId")
            val svc = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
            svc.putExtra("outSigType", "DISCONNECT")
            svc.putExtra("toDeviceId", targetId)
            if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
        }
    }

    fun handleRemoteTrack(track: org.webrtc.MediaStreamTrack?) {
        try {
            if (track is VideoTrack) {
                Timber.i("[Viewer] Received remote video track: enabled=${track.enabled()} state=${track.state()}")
                val sink = rendererView
                if (sink == null) {
                    Timber.w("[Viewer] Renderer not ready when track arrived")
                    videoDebug = "Renderer not ready"
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            track.setEnabled(true)
                            track.addSink(sink)
                            videoDebug = "Video track attached"
                            Timber.i("[Viewer] Remote video track attached to renderer")
                        } catch (e: Exception) {
                            Timber.e(e, "[Viewer] Failed to attach track to sink")
                            videoDebug = "Attach error: ${e.message}"
                        }
                    }
                }
            } else {
                Timber.d("[Viewer] Received non-video track: ${track?.kind()}")
            }
        } catch (e: Exception) {
            Timber.e(e, "[Viewer] handleRemoteTrack failed")
            videoDebug = "Attach failed: ${e.message}"
        }
    }

    LaunchedEffect(Unit) {
        // Fetch devices list
        try {
            devices = api.devices("Bearer $token")
        } catch (e: Exception) { Timber.e(e) }

        // This WS connection is ONLY for the viewer side (receiving OFFERs, sending ANSWERs/ICE).
        // The SignalingForegroundService has its own WS for the streamer side.
        val client = WsClient(secureWs, token, deviceId)
        wsClient = client
        client.connect(object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                status = "Connected"
                Timber.i("[Viewer WS] Connected")
            }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                Timber.d("[Viewer WS] RX: $text")
                try {
                    val j = JSONObject(text)
                    when (j.optString("type")) {
                        "OFFER" -> {
                            Timber.i("[Viewer] OFFER received from=${j.optString("fromDeviceId")} len=${j.optString("sdp").length}")

                            // Clean up any existing viewer WebRTC before creating new one
                            if (webrtcViewer != null) {
                                Timber.i("[Viewer] Disposing existing WebRTC before handling new OFFER")
                                try {
                                    webrtcViewer?.dispose()
                                } catch (e: Exception) {
                                    Timber.e(e, "[Viewer] Error disposing existing WebRTC")
                                }
                                webrtcViewer = null
                            }
                            pendingViewerIce.clear()

                            val sdp = j.optString("sdp")
                            val fromId = j.optString("fromDeviceId")
                            lastStreamerId = if (fromId.isNullOrEmpty()) null else fromId
                            // Pass shared eglBase so decoded frames render in same EGL context
                            val webrtc = com.manyeyes.webrtc.WebRtcManager(ctx, eglBase)
                            webrtc.init()
                            val baseIce = mutableListOf<org.webrtc.PeerConnection.IceServer>()
                            // Use centralized TURN config — reuse the webrtc instance to avoid native leak
                            try {
                                val extra = webrtc.fetchCloudflareIceServers(com.manyeyes.TurnConfig.CLOUDFLARE_TOKEN, com.manyeyes.TurnConfig.CLOUDFLARE_KEY_ID)
                                Timber.i("[Viewer] Cloudflare ICE servers fetched: ${extra.size}")
                                baseIce.addAll(extra)
                            } catch (e: Exception) { Timber.e(e, "[Viewer] TURN fetch failed") }
                            val iceServers = baseIce

                            webrtc.createPeer(iceServers, object : org.webrtc.PeerConnection.Observer {
                                override fun onIceCandidate(c: org.webrtc.IceCandidate) {
                                    Timber.d("[Viewer] ICE_CANDIDATE sdp=${c.sdp}")
                                    // Route ICE via SignalingForegroundService
                                    val svc = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
                                    svc.putExtra("outSigType", "ICE")
                                    val targetId = lastStreamerId ?: fromId
                                    svc.putExtra("toDeviceId", targetId)
                                    svc.putExtra("candidate", c.sdp)
                                    svc.putExtra("sdpMid", c.sdpMid)
                                    svc.putExtra("sdpMLineIndex", c.sdpMLineIndex)
                                    if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
                                }
                                override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState) {
                                    Timber.i("[Viewer] ICE state=$state")
                                    videoDebug = "ICE: $state"
                                }
                                override fun onIceGatheringChange(state: org.webrtc.PeerConnection.IceGatheringState) {
                                    Timber.d("[Viewer] ICE gathering=$state")
                                }
                                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                                override fun onSignalingChange(state: org.webrtc.PeerConnection.SignalingState) {
                                    Timber.i("[Viewer] Signaling state=$state")
                                }
                                override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
                                override fun onAddStream(p0: org.webrtc.MediaStream?) {}
                                override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
                                override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
                                override fun onRenegotiationNeeded() {}
                                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {
                                    handleRemoteTrack(receiver?.track())
                                }
                                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                                    handleRemoteTrack(transceiver?.receiver?.track())
                                }
                            })

                            val remote = org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, sdp)
                            try {
                                Timber.d("[Viewer] Preparing RECV_ONLY transceivers (video-only)")
                                videoDebug = "Preparing receivers"
                                webrtc.prepareReceivers(receiveAudio = false, receiveVideo = true)
                                webrtc.setRemoteDescription(remote)
                                webrtcViewer = webrtc
                                isViewingStream = true
                                status = "Viewing stream from ${lastStreamerId?.take(8)}..."

                                // Drain any queued ICE
                                if (pendingViewerIce.isNotEmpty()) {
                                    val toApply = pendingViewerIce.toList()
                                    pendingViewerIce.clear()
                                    toApply.forEach { ice ->
                                        try { webrtc.addIceCandidate(ice) } catch (e: Exception) { Timber.e(e, "[Viewer] Failed applying queued ICE") }
                                    }
                                    Timber.i("[Viewer] Applied ${toApply.size} queued ICE candidates after SRD")
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "[Viewer] Failed to prepare or set remote description")
                            }

                            webrtc.createAnswer { ans ->
                                Timber.d("[Viewer] createAnswer success; setting local and sending ANSWER")
                                videoDebug = "ANSWER created/sent"
                                webrtc.setLocalDescription(ans)
                                // Route ANSWER via SignalingForegroundService
                                val svc = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
                                svc.putExtra("outSigType", "ANSWER")
                                val targetIdAns = lastStreamerId ?: fromId
                                svc.putExtra("toDeviceId", targetIdAns)
                                svc.putExtra("sdp", ans.description)
                                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
                                Timber.i("[Viewer] ANSWER sent -> $targetIdAns")
                            }
                        }
                        "ICE" -> {
                            val cand = j.optString("candidate")
                            val midRaw = j.optString("sdpMid", "")
                            val mid: String? = if (midRaw.isEmpty()) null else midRaw
                            val idx = j.optInt("sdpMLineIndex", -1)
                            if (!cand.isNullOrEmpty() && idx >= 0) {
                                try {
                                    val ice = org.webrtc.IceCandidate(mid, idx, cand)
                                    val rtc = webrtcViewer
                                    if (rtc == null) {
                                        synchronized(pendingViewerIce) {
                                            pendingViewerIce += ice
                                        }
                                        Timber.w("[Viewer] Peer not ready; queued ICE mid=$mid idx=$idx (size=${pendingViewerIce.size})")
                                    } else {
                                        rtc.addIceCandidate(ice)
                                        Timber.d("[Viewer] Applied remote ICE")
                                    }
                                } catch (e: Exception) {
                                    Timber.e(e, "[Viewer] Failed to apply ICE")
                                }
                            }
                        }
                        "REQUEST_STREAM" -> {
                            val toId = j.optString("toDeviceId")
                            val fromId = j.optString("fromDeviceId")
                            Timber.i("[Viewer] REQUEST_STREAM to=$toId from=$fromId -> handled by Signaling service; UI ignoring")
                        }
                        "LOCATION" -> {
                            // Live location update from remote device
                            val fromId = j.optString("fromDeviceId")
                            val errMsg = j.optString("error", "")
                            if (errMsg.isNotEmpty()) {
                                locationError = errMsg
                                Timber.w("[Viewer] Location error from=$fromId: $errMsg")
                            } else {
                                remoteLat = j.optDouble("latitude", 0.0)
                                remoteLng = j.optDouble("longitude", 0.0)
                                remoteAccuracy = j.optDouble("accuracy", 0.0).toFloat()
                                remoteSpeed = j.optDouble("speed", 0.0).toFloat()
                                locationError = null
                                Timber.i("[Viewer] LOCATION from=$fromId lat=$remoteLat lng=$remoteLng acc=$remoteAccuracy")
                            }
                        }
                        "SOS" -> {
                            val fromId = j.optString("fromDeviceId")
                            sosFromDevice = fromId
                            sosLat = j.optDouble("latitude", 0.0)
                            sosLng = j.optDouble("longitude", 0.0)
                            showSosAlert = true
                            Timber.w("[Viewer] 🆘 SOS ALERT from=$fromId lat=$sosLat lng=$sosLng")
                            // Play alarm sound
                            try {
                                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                                val ringtone = RingtoneManager.getRingtone(ctx, uri)
                                ringtone?.play()
                                // Stop after 5 seconds
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ ringtone?.stop() }, 5000)
                            } catch (e: Exception) { Timber.e(e, "SOS alarm failed") }
                        }
                        "BATTERY_STATUS" -> {
                            val fromId = j.optString("fromDeviceId")
                            remoteBattery[fromId] = j.optInt("battery", -1)
                            remoteCharging[fromId] = j.optBoolean("charging", false)
                            remoteNetwork[fromId] = j.optString("network", "Unknown")
                            Timber.d("[Viewer] BATTERY_STATUS from=$fromId batt=${remoteBattery[fromId]}% net=${remoteNetwork[fromId]}")
                        }
                        "SCREEN_OFFER" -> {
                            val fromId = j.optString("fromDeviceId")
                            val sdp = j.optString("sdp", "")
                            Timber.i("[Viewer] SCREEN_OFFER from=$fromId len=${sdp.length}")
                            if (sdp.isEmpty()) return@onMessage

                            // Clean up existing screen viewer
                            try { webrtcScreenViewer?.dispose() } catch (_: Exception) {}
                            webrtcScreenViewer = null
                            pendingScreenIce.clear()
                            screenStreamerId = fromId

                            val webrtc = com.manyeyes.webrtc.WebRtcManager(ctx, eglBase)
                            webrtc.init()

                            // Fetch TURN servers (reuse same instance)
                            val baseIce = mutableListOf<org.webrtc.PeerConnection.IceServer>()
                            try {
                                val extra = webrtc.fetchCloudflareIceServers(com.manyeyes.TurnConfig.CLOUDFLARE_TOKEN, com.manyeyes.TurnConfig.CLOUDFLARE_KEY_ID)
                                baseIce.addAll(extra)
                            } catch (_: Exception) {}

                            webrtc.createPeer(baseIce, object : org.webrtc.PeerConnection.Observer {
                                override fun onIceCandidate(candidate: org.webrtc.IceCandidate?) {
                                    candidate ?: return
                                    val ice = """{"type":"SCREEN_ICE","toDeviceId":"$fromId","sdpMid":"${candidate.sdpMid}","sdpMLineIndex":${candidate.sdpMLineIndex},"candidate":"${candidate.sdp}"}"""
                                    wsClient?.send(ice)
                                }
                                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {
                                    val track = transceiver?.receiver?.track()
                                    if (track is VideoTrack) {
                                        Timber.i("[Viewer] Screen video track received")
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            try {
                                                track.setEnabled(true)
                                                rendererView?.let { track.addSink(it) }
                                                isViewingScreen = true
                                                videoDebug = "Screen sharing active"
                                            } catch (e: Exception) {
                                                Timber.e(e, "[Viewer] Failed to attach screen track")
                                            }
                                        }
                                    }
                                }
                                override fun onIceConnectionChange(s: org.webrtc.PeerConnection.IceConnectionState?) {
                                    Timber.i("[Viewer] Screen ICE: $s")
                                    if (s == org.webrtc.PeerConnection.IceConnectionState.DISCONNECTED ||
                                        s == org.webrtc.PeerConnection.IceConnectionState.FAILED) {
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            isViewingScreen = false
                                            videoDebug = "Screen share ended"
                                        }
                                    }
                                }
                                override fun onSignalingChange(p0: org.webrtc.PeerConnection.SignalingState?) {}
                                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                                override fun onIceGatheringChange(p0: org.webrtc.PeerConnection.IceGatheringState?) {}
                                override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
                                override fun onAddStream(p0: org.webrtc.MediaStream?) {}
                                override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
                                override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
                                override fun onRenegotiationNeeded() {}
                                override fun onAddTrack(r: org.webrtc.RtpReceiver?, s: Array<out org.webrtc.MediaStream>?) {}
                            })

                            webrtc.prepareReceivers(receiveAudio = false, receiveVideo = true)
                            webrtc.setRemoteDescription(org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, sdp))
                            webrtc.createAnswer { answerSdp ->
                                webrtc.setLocalDescription(answerSdp)
                                val ans = """{"type":"SCREEN_ANSWER","toDeviceId":"$fromId","sdp":"${answerSdp.description}"}"""
                                wsClient?.send(ans)
                            }
                            webrtcScreenViewer = webrtc

                            // Apply pending ICE
                            val iceCopy = pendingScreenIce.toList()
                            pendingScreenIce.clear()
                            iceCopy.forEach { webrtc.addIceCandidate(it) }
                        }
                        "SCREEN_ICE" -> {
                            val mid = j.optString("sdpMid", "")
                            val idx = j.optInt("sdpMLineIndex", 0)
                            val cand = j.optString("candidate", "")
                            if (cand.isEmpty()) return@onMessage
                            val candidate = org.webrtc.IceCandidate(mid, idx, cand)
                            val rtc = webrtcScreenViewer
                            if (rtc != null) {
                                rtc.addIceCandidate(candidate)
                            } else {
                                pendingScreenIce.add(candidate)
                            }
                        }
                        "STOP_SCREEN" -> {
                            Timber.i("[Viewer] STOP_SCREEN received")
                            try { webrtcScreenViewer?.dispose() } catch (_: Exception) {}
                            webrtcScreenViewer = null
                            isViewingScreen = false
                            screenStreamerId = null
                            videoDebug = "Screen share ended"
                        }
                        "PRESENCE" -> {
                            // Refresh list
                            scope.launch { devices = api.devices("Bearer $token") }
                        }
                    }
                } catch (_: Exception) {}
            }
            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                status = "Closing: $reason"
            }
            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                status = "Failed: ${t.message}"
            }
        })
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        // Overlay permission check for Android 12+ floating bubble
        val context = LocalContext.current
        var hasOverlayPermission by remember {
            mutableStateOf(
                android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(context)
            )
        }

        // Recheck permission when app resumes
        DisposableEffect(Unit) {
            onDispose { }
        }

        if (!hasOverlayPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "⚠️ Overlay Permission Required",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "For background camera streaming on Android 12+, please enable 'Display over other apps' permission.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(intent)
                    }) {
                        Text("Grant Permission")
                    }
                }
            }
        }

        Text("Status: $status")
        Spacer(Modifier.height(12.dp))
        // Remote video renderer
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(240.dp),
            factory = { context ->
                SurfaceViewRenderer(context).apply {
                    setZOrderMediaOverlay(true)
                    init(eglBase.eglBaseContext, object : org.webrtc.RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            Timber.i("[Viewer] First frame rendered!")
                            videoDebug = "First frame rendered"
                        }
                        override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                            Timber.i("[Viewer] Frame resolution: ${width}x${height} rotation=$rotation")
                            videoDebug = "Resolution: ${width}x${height}"
                        }
                    })
                    setEnableHardwareScaler(true)
                    setScalingType(org.webrtc.RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setMirror(false)
                    visibility = android.view.View.VISIBLE
                    Timber.i("[Viewer] SurfaceViewRenderer initialized with shared EGL context")
                }
            },
            update = { view ->
                rendererView = view
            }
        )
        Spacer(Modifier.height(8.dp))
        Text("Video Debug: $videoDebug")

        // Video control buttons - only show when viewing a stream or screen
        if (isViewingStream || isViewingScreen) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (isViewingStream) {
                    // Switch Camera button (only for camera streams)
                    Button(
                        onClick = { sendControlCommand("SWITCH_CAMERA") },
                        colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF607D8B))
                    ) {
                        Text("🔄 Switch Camera")
                    }
                }

                // Disconnect/End button
                Button(
                    onClick = {
                        if (isViewingScreen) {
                            // Send STOP_SCREEN to end screen sharing
                            val stopReq = """{"type":"STOP_SCREEN","toDeviceId":"$screenStreamerId"}"""
                            wsClient?.send(stopReq)
                            try { webrtcScreenViewer?.dispose() } catch (_: Exception) {}
                            webrtcScreenViewer = null
                            isViewingScreen = false
                            screenStreamerId = null
                            videoDebug = "Screen share ended"
                        } else {
                            disconnectStream()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFE53935))
                ) {
                    Text(if (isViewingScreen) "⏹ End Screen Share" else "⏹ Disconnect")
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Devices:", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        devices.filter { it.deviceId != deviceId }.forEach { dev ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (dev.isOnline) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(dev.deviceName, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                        Text(
                        if (dev.isOnline) "● Online" else "○ Offline",
                        color = if (dev.isOnline) Color(0xFF4CAF50) else Color.Gray,
                        fontSize = 12.sp
                    )
                }
                // Battery & Network info row
                val batt = remoteBattery[dev.deviceId]
                val charging = remoteCharging[dev.deviceId] ?: false
                val net = remoteNetwork[dev.deviceId]
                if (batt != null && batt >= 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val battIcon = when {
                            charging -> "⚡"
                            batt <= 15 -> "🪭"
                            batt <= 50 -> "🔋"
                            else -> "🔋"
                        }
                        val battColor = when {
                            batt <= 15 -> Color(0xFFE53935)
                            batt <= 40 -> Color(0xFFFF9800)
                            else -> Color(0xFF4CAF50)
                        }
                        Text("$battIcon ${batt}%", color = battColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        if (net != null) {
                            val netIcon = when (net) {
                                "WiFi" -> "📶"
                                "Cellular" -> "📡"
                                else -> "🌐"
                            }
                            Text("$netIcon $net", color = Color.Gray, fontSize = 11.sp)
                        }
                    }
                }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Monitor Camera button
                        Button(
                            enabled = dev.isOnline,
                            onClick = {
                                val req = """{"type":"REQUEST_STREAM","toDeviceId":"${dev.deviceId}"}"""
                                wsClient?.send(req)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                        ) { Text("📷 Camera", fontSize = 11.sp) }

                        // Screen Share button
                        Button(
                            enabled = dev.isOnline,
                            onClick = {
                                val req = """{"type":"REQUEST_SCREEN","toDeviceId":"${dev.deviceId}"}"""
                                wsClient?.send(req)
                                Timber.i("[Viewer] REQUEST_SCREEN sent to ${dev.deviceId}")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2))
                        ) { Text("🖥 Screen", fontSize = 11.sp) }

                        // Track Location button
                        Button(
                            enabled = dev.isOnline,
                            onClick = {
                                if (isTrackingLocation && trackingDeviceId == dev.deviceId) {
                                    // Already tracking this device, just show the map
                                    showMapDialog = true
                                } else {
                                    // Stop tracking previous device if any
                                    if (isTrackingLocation && trackingDeviceId != null) {
                                        val stopReq = """{"type":"STOP_LOCATION","toDeviceId":"$trackingDeviceId"}"""
                                        wsClient?.send(stopReq)
                                    }
                                    // Start tracking new device
                                    trackingDeviceId = dev.deviceId
                                    trackingDeviceName = dev.deviceName
                                    isTrackingLocation = true
                                    remoteLat = 0.0
                                    remoteLng = 0.0
                                    locationError = null
                                    val req = """{"type":"REQUEST_LOCATION","toDeviceId":"${dev.deviceId}"}"""
                                    wsClient?.send(req)
                                    showMapDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isTrackingLocation && trackingDeviceId == dev.deviceId)
                                    Color(0xFFFF9800) else Color(0xFF388E3C)
                            )
                        ) {
                            Text(
                                if (isTrackingLocation && trackingDeviceId == dev.deviceId) "📍 Tracking..."
                                else "📍 Location",
                                fontSize = 12.sp
                            )
                        }

                        // SOS button
                        Button(
                            enabled = dev.isOnline,
                            onClick = {
                                // Send SOS to ALL paired devices
                                // First grab current location
                                try {
                                    val locClient = com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(ctx)
                                    if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                        locClient.lastLocation.addOnSuccessListener { loc ->
                                            val lat = loc?.latitude ?: 0.0
                                            val lng = loc?.longitude ?: 0.0
                                            // Send SOS to each online device
                                            devices.filter { it.deviceId != deviceId && it.isOnline }.forEach { d ->
                                                val sos = """{"type":"SOS","toDeviceId":"${d.deviceId}","latitude":$lat,"longitude":$lng}"""
                                                wsClient?.send(sos)
                                            }
                                            Timber.w("[SOS] Emergency SOS sent! lat=$lat lng=$lng")
                                        }
                                    } else {
                                        // No location, send SOS without coordinates
                                        devices.filter { it.deviceId != deviceId && it.isOnline }.forEach { d ->
                                            val sos = """{"type":"SOS","toDeviceId":"${d.deviceId}","latitude":0,"longitude":0}"""
                                            wsClient?.send(sos)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.e(e, "[SOS] Failed to send SOS")
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            Text("🆘 SOS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // ─── Periodic battery/network broadcast ──────────────────────────────
    LaunchedEffect(wsClient) {
        val ws = wsClient ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(10000) // every 10 seconds
            try {
                val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val battLevel = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
                val isCharging = bm?.isCharging ?: false
                val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val netType = cm?.activeNetwork?.let { net ->
                    val caps = cm.getNetworkCapabilities(net)
                    when {
                        caps == null -> "None"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        else -> "Other"
                    }
                } ?: "None"
                val payload = """{"type":"BATTERY_STATUS","battery":$battLevel,"charging":$isCharging,"network":"$netType"}"""
                ws.send(payload)
            } catch (_: Exception) {}
        }
    }

    // ─── SOS Alert Dialog ────────────────────────────────────────
    if (showSosAlert) {
        AlertDialog(
            onDismissRequest = { showSosAlert = false },
            containerColor = Color(0xFFB71C1C),
            title = { Text("🆘 EMERGENCY SOS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp) },
            text = {
                Column {
                    Text("SOS sent from device:", color = Color(0xFFFFCDD2), fontSize = 14.sp)
                    Text(sosFromDevice, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    if (sosLat != 0.0 || sosLng != 0.0) {
                        Spacer(Modifier.height(8.dp))
                        Text("Location: %.5f, %.5f".format(sosLat, sosLng), color = Color(0xFFFFCDD2), fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showSosAlert = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White)
                ) { Text("Dismiss", color = Color(0xFFB71C1C)) }
            }
        )
    }

    // ─── Full-Screen Map Dialog ──────────────────────────────────────────
    if (showMapDialog) {
        LocationMapDialog(
            deviceName = trackingDeviceName,
            latitude = remoteLat,
            longitude = remoteLng,
            accuracy = remoteAccuracy,
            speed = remoteSpeed,
            error = locationError,
            onDismiss = {
                showMapDialog = false
            },
            onStopTracking = {
                // Send STOP_LOCATION to the remote device
                if (trackingDeviceId != null) {
                    val stopReq = """{"type":"STOP_LOCATION","toDeviceId":"$trackingDeviceId"}"""
                    wsClient?.send(stopReq)
                }
                isTrackingLocation = false
                trackingDeviceId = null
                trackingDeviceName = ""
                showMapDialog = false
                locationError = null
            }
        )
    }
}

@Composable
fun LocationMapDialog(
    deviceName: String,
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    speed: Float,
    error: String?,
    onDismiss: () -> Unit,
    onStopTracking: () -> Unit
) {
    val context = LocalContext.current
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var markerRef by remember { mutableStateOf<Marker?>(null) }

    // Configure osmdroid
    LaunchedEffect(Unit) {
        Configuration.getInstance().userAgentValue = context.packageName
    }

    // Update marker when coordinates change
    LaunchedEffect(latitude, longitude) {
        val map = mapViewRef ?: return@LaunchedEffect
        if (latitude == 0.0 && longitude == 0.0) return@LaunchedEffect

        val point = GeoPoint(latitude, longitude)

        if (markerRef == null) {
            // First location — create marker and center map
            val marker = Marker(map)
            marker.position = point
            marker.title = deviceName
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            map.overlays.add(marker)
            markerRef = marker
            map.controller.setZoom(17.0)
            map.controller.animateTo(point)
        } else {
            // Update existing marker position
            markerRef?.position = point
            map.controller.animateTo(point)
        }
        map.invalidate()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar — no shadowElevation to prevent flicker on recomposition
                Surface(
                    color = Color(0xFF1B5E20)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "📍 Live Location",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                "Tracking: $deviceName",
                                color = Color(0xFFA5D6A7),
                                fontSize = 13.sp
                            )
                        }
                        Button(
                            onClick = onStopTracking,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                        ) {
                            Text("Stop", fontSize = 12.sp)
                        }
                    }
                }

                // Error banner
                if (error != null) {
                    Surface(
                        color = Color(0xFFFFEBEE),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "⚠️ $error",
                            color = Color(0xFFC62828),
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp
                        )
                    }
                }

                // Waiting state
                if (latitude == 0.0 && longitude == 0.0 && error == null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(12.dp))
                            Text("Waiting for GPS signal from $deviceName...", fontSize = 14.sp)
                            Spacer(Modifier.height(4.dp))
                            Text("Updates every 5 seconds", color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                // Map view (shows once we have coordinates)
                Box(modifier = Modifier.weight(1f)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                setMultiTouchControls(true)
                                controller.setZoom(3.0) // World zoom initially
                                controller.setCenter(GeoPoint(20.0, 78.0)) // Center on India initially
                                mapViewRef = this
                            }
                        }
                    )
                }

                // Bottom info bar with coordinates
                if (latitude != 0.0 || longitude != 0.0) {
                    Surface(
                        color = Color(0xFF212121),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    "Lat: %.6f".format(latitude),
                                    color = Color.White, fontSize = 12.sp
                                )
                                Text(
                                    "Lng: %.6f".format(longitude),
                                    color = Color.White, fontSize = 12.sp
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "Accuracy: %.0fm".format(accuracy),
                                    color = Color(0xFF81C784), fontSize = 12.sp
                                )
                                Text(
                                    "Speed: %.1f m/s".format(speed),
                                    color = Color(0xFF81C784), fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
