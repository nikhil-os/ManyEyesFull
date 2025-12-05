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
import kotlinx.coroutines.launch
import com.manyeyes.signaling.WsClient
import timber.log.Timber
import android.content.Intent
import com.manyeyes.streaming.StreamForegroundService
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

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
            }
            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                Timber.d("[Viewer WS] RX: $text")
                try {
                    val j = JSONObject(text)
                    when (j.optString("type")) {
                        "OFFER" -> {
                            Timber.i("[Viewer] OFFER received from=${j.optString("fromDeviceId")}")
                            // Viewer: build PC, set remote, create ANSWER, send via WS
                            val sdp = j.optString("sdp")
                            val fromId = j.optString("fromDeviceId")
                            val webrtc = com.manyeyes.webrtc.WebRtcManager(ctx)
                            webrtc.init()
                            val iceServers = listOf(
                                org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                            )
                            webrtc.createPeer(iceServers, object : org.webrtc.PeerConnection.Observer {
                                override fun onIceCandidate(c: org.webrtc.IceCandidate) {
                                    val payload = org.json.JSONObject().apply {
                                        put("type", "ICE")
                                        put("candidate", c.sdp)
                                        put("sdpMid", c.sdpMid)
                                        put("sdpMLineIndex", c.sdpMLineIndex)
                                        put("toDeviceId", fromId)
                                    }.toString()
                                    wsClient?.send(payload)
                                }
                                override fun onIceConnectionChange(state: org.webrtc.PeerConnection.IceConnectionState) {}
                                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                                override fun onSignalingChange(state: org.webrtc.PeerConnection.SignalingState) {}
                                override fun onIceGatheringChange(state: org.webrtc.PeerConnection.IceGatheringState) {}
                                override fun onIceCandidatesRemoved(p0: Array<out org.webrtc.IceCandidate>?) {}
                                override fun onAddStream(p0: org.webrtc.MediaStream?) {}
                                override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
                                override fun onDataChannel(p0: org.webrtc.DataChannel?) {}
                                override fun onRenegotiationNeeded() {}
                                override fun onAddTrack(receiver: org.webrtc.RtpReceiver?, streams: Array<out org.webrtc.MediaStream>?) {}
                            })
                            val remote = org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.OFFER, sdp)
                            try {
                                webrtc.setRemoteDescription(remote)
                            } catch (e: Exception) {
                                Timber.e(e, "[Viewer] Failed to set remote description")
                            }
                            webrtc.createAnswer { ans ->
                                webrtc.setLocalDescription(ans)
                                val payload = org.json.JSONObject().apply {
                                    put("type", "ANSWER")
                                    put("sdp", ans.description)
                                    put("toDeviceId", fromId)
                                }.toString()
                                wsClient?.send(payload)
                            }
                        }
                        "REQUEST_STREAM" -> {
                            val toId = j.optString("toDeviceId")
                            val fromId = j.optString("fromDeviceId")
                            if (toId == deviceId) {
                                Timber.i("[Viewer] REQUEST_STREAM targeted to me ($toId) from=$fromId -> starting StreamForegroundService")
                                val i = Intent(ctx, StreamForegroundService::class.java)
                                i.putExtra("role", "streamer")
                                i.putExtra("remoteDeviceId", fromId)
                                i.putExtra("token", token)
                                i.putExtra("deviceId", deviceId)
                                i.putExtra("baseWs", secureWs)
                                if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
                            } else {
                                Timber.d("[Viewer] Ignoring REQUEST_STREAM to=$toId (me=$deviceId)")
                            }
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
        Text("Status: $status")
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
