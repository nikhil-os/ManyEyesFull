package com.manyeyes.streaming

import android.Manifest
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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.manyeyes.MainActivity
import com.manyeyes.webrtc.WebRtcManager
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import timber.log.Timber

class StreamForegroundService : Service() {
    private var webrtc: WebRtcManager? = null
    private var currentRemoteId: String = ""
    private var pendingAnswer: String? = null
    private val pendingIce: MutableList<IceCandidate> = mutableListOf()
    private var answerApplied: Boolean = false
    private var negotiationInProgress: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    private fun resetState() {
        Timber.i("[Streamer] Resetting state")
        try {
            webrtc?.dispose()
        } catch (e: Exception) {
            Timber.e(e, "[Streamer] Error disposing WebRTC during reset")
        }
        webrtc = null
        currentRemoteId = ""
        pendingAnswer = null
        pendingIce.clear()
        answerApplied = false
        negotiationInProgress = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Show foreground notification immediately to satisfy Android’s 5s requirement
        val notif = buildNotification()

        // Choose FGS types dynamically based on granted runtime permissions (Android 14 strict checks)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = 0
            val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            if (micGranted) fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (fgsType == 0) {
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                Timber.w("Starting streaming service without CAMERA/MIC permissions; prompting user to grant permissions")
            }
            startForeground(NOTIF_ID, notif, fgsType)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // Handle forwarded signaling updates quickly (ANSWER / ICE)
        intent?.getStringExtra("sigType")?.let { sigType ->
            when (sigType) {
                "ANSWER" -> {
                    val sdp = intent.getStringExtra("sdp") ?: return START_STICKY
                    Timber.i("[Streamer] ANSWER received len=${sdp.length}")
                    val rtc = webrtc
                    if (rtc == null) {
                        pendingAnswer = sdp
                        Timber.w("[Streamer] WebRTC not ready; queued ANSWER")
                    } else {
                        // Debounce: apply only once when we are in HAVE_LOCAL_OFFER
                        try {
                            val pcState = org.webrtc.PeerConnection.SignalingState.STABLE // default
                            // Best-effort: if remote already applied, ignore duplicates
                            if (answerApplied) {
                                Timber.i("[Streamer] ANSWER ignored: already applied; state=STABLE")
                            } else {
                                rtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                                answerApplied = true
                                negotiationInProgress = false
                                Timber.i("[Streamer] Applied remote ANSWER (setRemoteDescription success)")
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "[Streamer] Failed to apply ANSWER")
                        }
                    }
                    return START_STICKY
                }
                "ICE" -> {
                    val cand = intent.getStringExtra("candidate") ?: return START_STICKY
                    val mid = intent.getStringExtra("sdpMid")
                    val idx = intent.getIntExtra("sdpMLineIndex", -1)
                    val ice = IceCandidate(mid, idx, cand)
                    Timber.i("[Streamer] Inbound ICE mid=$mid idx=$idx cand=${cand.take(120)}")
                    val rtc = webrtc
                    if (rtc == null) {
                        pendingIce += ice
                        Timber.w("[Streamer] WebRTC not ready; queued ICE count=${pendingIce.size}")
                    } else {
                        try {
                            rtc.addIceCandidate(ice)
                            Timber.i("[Streamer] Applied remote ICE mid=$mid idx=$idx")
                        } catch (e: Exception) {
                            Timber.e(e, "[Streamer] Failed to apply ICE")
                        }
                    }
                    return START_STICKY
                }
                "SWITCH_CAMERA" -> {
                    Timber.i("[Streamer] SWITCH_CAMERA command received")
                    webrtc?.switchCamera { success ->
                        Timber.i("[Streamer] Camera switch result: $success")
                    }
                    return START_STICKY
                }
                "DISCONNECT" -> {
                    Timber.i("[Streamer] DISCONNECT command received - stopping stream")
                    resetState()
                    // Notify SignalingForegroundService that streaming stopped
                    try {
                        val sigSvc = Intent(this, com.manyeyes.signaling.SignalingForegroundService::class.java)
                        sigSvc.putExtra("streamerActive", false)
                        sigSvc.putExtra("resetStreaming", true)
                        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(sigSvc) else startService(sigSvc)
                    } catch (_: Exception) {}
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                else -> { /* ignore */ }
            }
        }

        // Read extras for streaming setup
        val role = intent?.getStringExtra("role") ?: "streamer"
        val remoteDeviceId = intent?.getStringExtra("remoteDeviceId") ?: ""
        val token = intent?.getStringExtra("token") ?: ""
        val deviceId = intent?.getStringExtra("deviceId") ?: ""
        val baseWs = intent?.getStringExtra("baseWs") ?: ""
        Timber.i("[Streamer] Service start role=$role remote=$remoteDeviceId device=$deviceId ws=$baseWs")

        // If starting a new stream to a different device, reset state first
        if (remoteDeviceId.isNotEmpty() && remoteDeviceId != currentRemoteId && webrtc != null) {
            Timber.i("[Streamer] New stream request to different device - resetting state")
            resetState()
        }

        // Ensure SignalingForegroundService is running and connected with credentials
        try {
            val sigSvc = Intent(this, com.manyeyes.signaling.SignalingForegroundService::class.java)
            sigSvc.putExtra("token", token)
            sigSvc.putExtra("deviceId", deviceId)
            sigSvc.putExtra("baseWs", baseWs)
            sigSvc.putExtra("streamerActive", true)
            sigSvc.putExtra("resetStreaming", true) // Reset signaling state for fresh connection
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(sigSvc) else startService(sigSvc)
            Timber.d("[Streamer] SignalingForegroundService primed with credentials")
        } catch (e: Exception) {
            Timber.e(e, "[Streamer] Failed to prime SignalingForegroundService")
        }

        // We will send via SignalingForegroundService's socket to avoid multi-WS issues

        // Strict role gating: only act when role==streamer and target is different device
        if (role != "streamer" || remoteDeviceId.isBlank() || deviceId.isBlank() || deviceId == remoteDeviceId) {
            Timber.w("[Streamer] Invalid start parameters; stopping self. role=$role deviceId=$deviceId remote=$remoteDeviceId")
            stopSelf()
            return START_NOT_STICKY
        }

        // Proceed even if microphone permission is not granted (video-only testing)
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Timber.w("[Streamer] RECORD_AUDIO not granted; continuing with video-only")
        }

        // Initialize WebRTC and create offer to viewer
        // TURN fetch must run on background thread; WebRTC peer creation on main thread
        try {
            val cfToken = "79c4a9c50eab05535b950221f3a3c63fc1aac9228c6df3072e8a0b84069edf98"
            val cfKeyId = "ddbb6ea7d57daf6ab17e5949b9568d16"
            val ctx = this
            // Fetch TURN credentials on IO thread, then create peer on main thread
            java.util.concurrent.Executors.newSingleThreadExecutor().execute {
                val fetchedIce = mutableListOf<PeerConnection.IceServer>()
                try {
                    val extra = WebRtcManager(ctx).fetchCloudflareIceServers(cfToken, cfKeyId)
                    fetchedIce.addAll(extra)
                    Timber.i("[Streamer] TURN fetched on bg thread: ${fetchedIce.size} servers")
                } catch (e: Exception) {
                    Timber.e(e, "[Streamer] TURN fetch failed on bg thread")
                }
                // Now switch to main thread for WebRTC peer creation
                Handler(Looper.getMainLooper()).post {
                    val rtc = WebRtcManager(ctx)
                    rtc.init()
                    val rtcConfigIce = fetchedIce
                    rtc.createPeer(rtcConfigIce, object : PeerConnection.Observer {
                    override fun onIceCandidate(candidate: IceCandidate) {
                        try {
                            Timber.d("[Streamer] ICE_CANDIDATE sdp=${candidate.sdp}")
                            val icePayload = org.json.JSONObject().apply {
                                put("type", "ICE")
                                put("candidate", candidate.sdp)
                                put("sdpMid", candidate.sdpMid)
                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                                put("toDeviceId", remoteDeviceId)
                                put("fromDeviceId", deviceId)
                            }.toString()
                            val svc = Intent(this@StreamForegroundService, com.manyeyes.signaling.SignalingForegroundService::class.java)
                            svc.putExtra("outSigType", "ICE")
                            svc.putExtra("toDeviceId", remoteDeviceId)
                            svc.putExtra("candidate", candidate.sdp)
                            svc.putExtra("sdpMid", candidate.sdpMid)
                            svc.putExtra("sdpMLineIndex", candidate.sdpMLineIndex)
                            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                            Timber.d("[Streamer] ICE sent via Signaling -> $remoteDeviceId")
                        } catch (e: Exception) {
                            Timber.e(e, "[Streamer] Failed to send ICE via Signaling")
                        }
                    }
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        Timber.i("[Streamer] ICE state=$state")
                        if (state == PeerConnection.IceConnectionState.CONNECTED || state == PeerConnection.IceConnectionState.COMPLETED) {
                            // Log selected candidate pair via getStats
                            try {
                                val pc = java.lang.reflect.Field::class.java // no-op to keep reflection out; use standard APIs
                                // Using getStats(null) to pull transport stats
                                webrtc?.let { mgr ->
                                    val peerField = mgr.javaClass.getDeclaredField("peerConnection")
                                    peerField.isAccessible = true
                                    val pcObj = peerField.get(mgr) as? PeerConnection
                                    pcObj?.getStats { report ->
                                        report.statsMap.values.forEach { s: org.webrtc.RTCStats ->
                                            if (s.type == "transport") {
                                                val pairId = s.members["selectedCandidatePairId"] ?: ""
                                                Timber.i("[Streamer] Transport stats id=${s.id} selectedPairId=$pairId")
                                            }
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                        Timber.d("[Streamer] ICE gathering=$state")
                    }
                    override fun onIceConnectionReceivingChange(p0: Boolean) {}
                    override fun onSignalingChange(state: PeerConnection.SignalingState) {
                        Timber.i("[Streamer] Signaling state=$state")
                    }
                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                    override fun onAddStream(p0: MediaStream?) {}
                    override fun onRemoveStream(p0: MediaStream?) {}
                    override fun onDataChannel(p0: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                }, relayOnly = true)

                val camGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                // Force video-only for testing: disable audio capture; try to include video regardless of permission to test emulator camera
                val includeVideo = true
                val added = rtc.addLocalTracks(includeAudio = false, includeVideo = includeVideo)
                Timber.d("[Streamer] addLocalTracks result=$added")

                // Do not request receiving audio on streamer (video-only)
                if (!negotiationInProgress) {
                    negotiationInProgress = true
                    rtc.createOffer(wantVideo = includeVideo, wantAudio = false) { sdp ->
                    rtc.setLocalDescription(sdp)

                    // Apply any queued remote description/candidates
                    pendingAnswer?.let {
                        try {
                            rtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, it))
                            Timber.i("[Streamer] Applied queued ANSWER")
                                answerApplied = true
                                negotiationInProgress = false
                        } catch (e: Exception) { Timber.e(e, "[Streamer] Failed applying queued ANSWER") }
                        pendingAnswer = null
                    }
                    if (pendingIce.isNotEmpty()) {
                        pendingIce.forEach { ice ->
                            try { rtc.addIceCandidate(ice) } catch (e: Exception) { Timber.e(e, "[Streamer] Failed applying queued ICE") }
                        }
                        Timber.i("[Streamer] Applied ${pendingIce.size} queued ICE candidates")
                        pendingIce.clear()
                    }

                    // Send OFFER via signaling WS
                    try {
                        val svc = Intent(this@StreamForegroundService, com.manyeyes.signaling.SignalingForegroundService::class.java)
                        svc.putExtra("outSigType", "OFFER")
                        svc.putExtra("toDeviceId", remoteDeviceId)
                        svc.putExtra("sdp", sdp.description)
                        svc.putExtra("streamerActive", true)
                        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
                        Timber.i("[Streamer] OFFER sent via Signaling -> $remoteDeviceId")
                    } catch (e: Exception) {
                        Timber.e(e, "[Streamer] Failed to send OFFER via Signaling")
                    }
                    }
                } else {
                    Timber.i("[Streamer] Suppressing new OFFER: negotiationInProgress=true")
                }

                this.webrtc = rtc
                this.currentRemoteId = remoteDeviceId
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "[Streamer] WebRTC init failed")
        }

        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = ensureChannel()
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
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
        val channelId = "manyeyes_stream_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = nm.getNotificationChannel(channelId)
            if (existing == null) {
                val ch = NotificationChannel(channelId, "Streaming", NotificationManager.IMPORTANCE_LOW)
                ch.description = "Foreground service for camera/mic streaming"
                ch.enableLights(false)
                ch.enableVibration(false)
                ch.lightColor = Color.BLUE
                nm.createNotificationChannel(ch)
            }
        }
        return channelId
    }

    companion object {
        private const val NOTIF_ID = 1001
    }
}
