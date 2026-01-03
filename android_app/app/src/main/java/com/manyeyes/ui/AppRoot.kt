package com.manyeyes.ui

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.manyeyes.network.*
import com.manyeyes.data.Prefs
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import com.manyeyes.signaling.WsClient
import timber.log.Timber
import android.content.Intent
import com.manyeyes.streaming.StreamForegroundService
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.EglBase

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
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL) }
    var baseUrl by remember { mutableStateOf("https://manyeyes.onrender.com") } // cloud default; change if self-hosting
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
    
    // Function to send control commands to the streamer
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
        
        // Reset state BEFORE sending disconnect (so we're ready for new connection)
        isViewingStream = false
        lastStreamerId = null
        videoDebug = "Disconnected"
        status = "Connected"
        
        // Send DISCONNECT command to streamer (use saved targetId since we cleared lastStreamerId)
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
                            // Ensure track is enabled
                            track.setEnabled(true)
                            // Add the sink to receive frames
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
        // fetch devices list
        try {
            devices = api.devices("Bearer $token")
        } catch (e: Exception) { Timber.e(e) }

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
                            // Clear any pending ICE from previous session
                            pendingViewerIce.clear()
                            
                            // Viewer: build PC, set remote, create ANSWER, send via WS
                            val sdp = j.optString("sdp")
                            val fromId = j.optString("fromDeviceId")
                            // Remember streamer id from OFFER to enforce correct routing
                            lastStreamerId = if (fromId.isNullOrEmpty()) null else fromId
                            // Pass shared eglBase so decoded frames render in same EGL context as SurfaceViewRenderer
                            val webrtc = com.manyeyes.webrtc.WebRtcManager(ctx, eglBase)
                            webrtc.init()
                            val baseIce = mutableListOf<org.webrtc.PeerConnection.IceServer>()
                            // Include Cloudflare TURN/ICE servers
                            val cfToken = "79c4a9c50eab05535b950221f3a3c63fc1aac9228c6df3072e8a0b84069edf98"
                            val cfKeyId = "ddbb6ea7d57daf6ab17e5949b9568d16"
                            try {
                                val extra = com.manyeyes.webrtc.WebRtcManager(ctx, eglBase).fetchCloudflareIceServers(cfToken, cfKeyId)
                                Timber.i("[Viewer] Cloudflare ICE servers fetched: ${extra.size}")
                                baseIce.addAll(extra)
                            } catch (e: Exception) { Timber.e(e, "[Viewer] TURN fetch failed") }
                            val iceServers = baseIce
                            var viewerIceCount = 0
                            webrtc.createPeer(iceServers, object : org.webrtc.PeerConnection.Observer {
                                override fun onIceCandidate(c: org.webrtc.IceCandidate) {
                                    viewerIceCount += 1
                                    Timber.d("[Viewer] ICE_CANDIDATE sdp=${c.sdp}")
                                    // Route ICE via SignalingForegroundService to unify outbound path
                                    val svc = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
                                    svc.putExtra("outSigType", "ICE")
                                    val targetId = lastStreamerId ?: fromId
                                    if (targetId == deviceId) {
                                        Timber.e("[Viewer] ICE target resolved to self; overriding to lastStreamerId=$lastStreamerId")
                                    }
                                    svc.putExtra("toDeviceId", targetId)
                                    svc.putExtra("candidate", c.sdp)
                                    svc.putExtra("sdpMid", c.sdpMid)
                                    svc.putExtra("sdpMLineIndex", c.sdpMLineIndex)
                                    if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
                                    // Also send over the UI WebSocket as a fallback to ensure delivery
                                    try {
                                        val icePayload = JSONObject().apply {
                                            put("type", "ICE")
                                            put("candidate", c.sdp)
                                            put("sdpMid", c.sdpMid)
                                            put("sdpMLineIndex", c.sdpMLineIndex)
                                            put("toDeviceId", targetId)
                                            put("fromDeviceId", deviceId)
                                        }.toString()
                                        wsClient?.send(icePayload)
                                        Timber.d("[Viewer] ICE sent via UI WS -> $targetId (fallback)")
                                    } catch (e: Exception) {
                                        Timber.e(e, "[Viewer] Failed to send ICE via UI WS")
                                    }
                                }
                                override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState) {
                                    Timber.i("[Viewer] ICE state=$state")
                                    videoDebug = "ICE: $state"
                                    if (state == org.webrtc.PeerConnection.IceConnectionState.CONNECTED || state == org.webrtc.PeerConnection.IceConnectionState.COMPLETED) {
                                        try {
                                            // Log selected candidate pair
                                            webrtcViewer?.let { mgr ->
                                                val peerField = mgr.javaClass.getDeclaredField("peerConnection")
                                                peerField.isAccessible = true
                                                val pcObj = peerField.get(mgr) as? org.webrtc.PeerConnection
                                                pcObj?.getStats { report ->
                                                    report.statsMap.values.forEach { s: org.webrtc.RTCStats ->
                                                        if (s.type == "transport") {
                                                            val pairId = s.members["selectedCandidatePairId"] ?: ""
                                                            Timber.i("[Viewer] Transport stats id=${s.id} selectedPairId=$pairId")
                                                        }
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
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
                            // Prepare to receive only video (disable audio for testing)
                            val remote = org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, sdp)
                            try {
                                // In Unified Plan, add RECV_ONLY transceivers before setting remote SDP
                                Timber.d("[Viewer] Preparing RECV_ONLY transceivers (video-only)")
                                videoDebug = "Preparing receivers"
                                webrtc.prepareReceivers(receiveAudio = false, receiveVideo = true)
                                Timber.d("[Viewer] setRemoteDescription(OFFER) invoked")
                                webrtc.setRemoteDescription(remote)
                                webrtcViewer = webrtc
                                isViewingStream = true
                                status = "Viewing stream from ${lastStreamerId?.take(8)}..."
                                // Drain any queued ICE now that the peer is ready
                                try {
                                    if (pendingViewerIce.isNotEmpty()) {
                                        val toApply = pendingViewerIce.toList()
                                        pendingViewerIce.clear()
                                        toApply.forEach { ice ->
                                            try {
                                                webrtc.addIceCandidate(ice)
                                            } catch (e: Exception) {
                                                Timber.e(e, "[Viewer] Failed applying queued ICE")
                                            }
                                        }
                                        Timber.i("[Viewer] Applied ${toApply.size} queued ICE candidates after SRD")
                                    } else {
                                        Timber.d("[Viewer] No queued ICE to apply after SRD")
                                    }
                                } catch (e: Exception) { Timber.e(e, "[Viewer] Drain queued ICE failed") }
                            } catch (e: Exception) {
                                Timber.e(e, "[Viewer] Failed to prepare or set remote description")
                            }
                            webrtc.createAnswer { ans ->
                                Timber.d("[Viewer] createAnswer success; setting local and sending ANSWER")
                                videoDebug = "ANSWER created/sent"
                                webrtc.setLocalDescription(ans)
                                // Route ANSWER via SignalingForegroundService to unify outbound path
                                val svc = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
                                svc.putExtra("outSigType", "ANSWER")
                                val targetIdAns = lastStreamerId ?: fromId
                                if (targetIdAns == deviceId) {
                                    Timber.e("[Viewer] ANSWER target resolved to self; overriding to lastStreamerId=$lastStreamerId")
                                }
                                svc.putExtra("toDeviceId", targetIdAns)
                                svc.putExtra("sdp", ans.description)
                                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc) else ctx.startService(svc)
                                Timber.i("[Viewer] ANSWER sent -> $targetIdAns")

                                // Fallback: if no ICE candidates gathered within 6s, retry with non-relay to diagnose
                                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                    if (viewerIceCount == 0) {
                                        Timber.w("[Viewer] No ICE candidates gathered; retrying with non-relay policy for diagnostics")
                                        try {
                                            val webrtc2 = com.manyeyes.webrtc.WebRtcManager(ctx, eglBase)
                                            webrtc2.init()
                                            webrtc2.createPeer(iceServers, object : org.webrtc.PeerConnection.Observer {
                                                override fun onIceCandidate(c: org.webrtc.IceCandidate) {
                                                    Timber.d("[Viewer/R1] ICE_CANDIDATE sdp=${c.sdp}")
                                                    val svc2 = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
                                                    svc2.putExtra("outSigType", "ICE"); svc2.putExtra("toDeviceId", fromId)
                                                    svc2.putExtra("candidate", c.sdp); svc2.putExtra("sdpMid", c.sdpMid); svc2.putExtra("sdpMLineIndex", c.sdpMLineIndex)
                                                    if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc2) else ctx.startService(svc2)
                                                }
                                                override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState) { Timber.i("[Viewer/R1] ICE state=$state") }
                                                override fun onIceGatheringChange(state: org.webrtc.PeerConnection.IceGatheringState) { Timber.d("[Viewer/R1] ICE gathering=$state") }
                                                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                                                override fun onSignalingChange(state: org.webrtc.PeerConnection.SignalingState) { Timber.i("[Viewer/R1] Signaling state=$state") }
                                                override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
                                                override fun onAddStream(p0: org.webrtc.MediaStream?) {}
                                                override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
                                                override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
                                                override fun onRenegotiationNeeded() {}
                                                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) { handleRemoteTrack(receiver?.track()) }
                                                override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) { handleRemoteTrack(transceiver?.receiver?.track()) }
                                            }, relayOnly = false)
                                            webrtc2.prepareReceivers(receiveAudio = false, receiveVideo = true)
                                            webrtc2.setRemoteDescription(remote)
                                            webrtc2.createAnswer { ans2 ->
                                                webrtc2.setLocalDescription(ans2)
                                                val svc2 = android.content.Intent(ctx, com.manyeyes.signaling.SignalingForegroundService::class.java)
                                                svc2.putExtra("outSigType", "ANSWER"); svc2.putExtra("toDeviceId", fromId); svc2.putExtra("sdp", ans2.description)
                                                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc2) else ctx.startService(svc2)
                                                Timber.i("[Viewer/R1] ANSWER sent (non-relay) -> $fromId")
                                            }
                                        } catch (e: Exception) {
                                            Timber.e(e, "[Viewer] Non-relay retry failed")
                                        }
                                    }
                                }, 6000)
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
                                        // Queue ICE until peer is ready
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
                            } else {
                                Timber.w("[Viewer] ICE missing fields; candidate=$cand mid=$mid idx=$idx")
                            }
                        }
                        "REQUEST_STREAM" -> {
                            // Viewer UI should not auto-start streaming on incoming REQUEST_STREAM.
                            // Streaming start is handled by SignalingForegroundService when appropriate.
                            val toId = j.optString("toDeviceId")
                            val fromId = j.optString("fromDeviceId")
                            Timber.i("[Viewer] REQUEST_STREAM to=$toId from=$fromId -> handled by Signaling service; UI ignoring")
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
                    // Set Z-order BEFORE init to ensure proper rendering in Compose hierarchy
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
        
        // Video control buttons - only show when viewing a stream
        if (isViewingStream) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Switch Camera button
                Button(
                    onClick = { sendControlCommand("SWITCH_CAMERA") },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF607D8B))
                ) {
                    Text("🔄 Switch Camera")
                }
                
                // Disconnect button
                Button(
                    onClick = { disconnectStream() },
                    colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFFE53935))
                ) {
                    Text("⏹ Disconnect")
                }
            }
        }
        
        Spacer(Modifier.height(12.dp))
        Text("Devices:")
        Spacer(Modifier.height(8.dp))
        devices.filter { it.deviceId != deviceId }.forEach { dev ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(dev.deviceName, modifier = Modifier.weight(1f))
                val onlineText = if (dev.isOnline) "Online" else "Offline"
                Text(onlineText)
                Spacer(Modifier.width(12.dp))
                Button(enabled = dev.isOnline, onClick = {
                    val req = """{"type":"REQUEST_STREAM","toDeviceId":"${dev.deviceId}"}"""
                    wsClient?.send(req)
                }) { Text("Monitor Camera") }
            }
        }
    }
}
