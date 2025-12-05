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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.manyeyes.MainActivity
import com.manyeyes.webrtc.WebRtcManager
import timber.log.Timber

class StreamForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Show foreground notification immediately to satisfy Android’s 5s requirement
        val notif = buildNotification()

        // Choose FGS types dynamically based on granted runtime permissions (Android 14 strict checks)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var fgsType = 0
            val cameraGranted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

            if (cameraGranted) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (micGranted) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }

            if (fgsType == 0) {
                // No runtime permissions granted; use DATA_SYNC to avoid ForegroundServiceStartNotAllowedException and inform user
                fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                Timber.w("Starting streaming service without CAMERA/MIC permissions; prompting user to grant permissions")
            }
            startForeground(NOTIF_ID, notif, fgsType)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        // Read extras defensively
        val role = intent?.getStringExtra("role") ?: "streamer"
        val remoteDeviceId = intent?.getStringExtra("remoteDeviceId") ?: ""
        val token = intent?.getStringExtra("token") ?: ""
        val deviceId = intent?.getStringExtra("deviceId") ?: ""
        val baseWs = intent?.getStringExtra("baseWs") ?: ""
    Timber.i("[Streamer] Service start role=$role remote=$remoteDeviceId device=$deviceId ws=$baseWs")

        // Strict role gating: only act when role==streamer and target is different device
        if (role != "streamer" || remoteDeviceId.isBlank() || deviceId.isBlank() || deviceId == remoteDeviceId) {
            Timber.w("[Streamer] Invalid start parameters; stopping self. role=$role deviceId=$deviceId remote=$remoteDeviceId")
            stopSelf()
            return START_NOT_STICKY
        }

        // Require microphone permission for audio-only streaming; bail out early if missing
        val micGranted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!micGranted) {
            Timber.e("[Streamer] RECORD_AUDIO permission missing; cannot start")
            stopSelf()
            return START_NOT_STICKY
        }

        // Initialize WebRTC and create offer to viewer
        try {
            // WebRTC JNI expects calls on a thread with a Looper; use main thread
            android.os.Handler(mainLooper).post {
                val webrtc = WebRtcManager(this)
                webrtc.init()
                val iceServers = listOf(
                    org.webrtc.PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                )
                webrtc.createPeer(iceServers, object : org.webrtc.PeerConnection.Observer {
                override fun onIceCandidate(candidate: org.webrtc.IceCandidate) {
                    try {
                        val icePayload = org.json.JSONObject().apply {
                            put("type", "ICE")
                            put("candidate", candidate.sdp)
                            put("sdpMid", candidate.sdpMid)
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("toDeviceId", remoteDeviceId)
                        }.toString()
                        val ws = com.manyeyes.signaling.WsClient(baseWs, token, deviceId)
                        ws.connect(object : okhttp3.WebSocketListener() {})
                        ws.send(icePayload)
                        Timber.d("[Streamer] ICE sent -> $remoteDeviceId")
                    } catch (e: Exception) {
                        Timber.e(e, "[Streamer] Failed to send ICE")
                    }
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
                // Only add local tracks if CAMERA permission is granted; otherwise skip to avoid crashes
                // Use camera if permission granted; otherwise fallback to audio-only
                val camGranted = checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                val added = webrtc.addLocalTracks(audioOnly = !camGranted)
                Timber.d("[Streamer] addLocalTracks result=$added")
                webrtc.createOffer { sdp ->
                    webrtc.setLocalDescription(sdp)
                    // Send OFFER via signaling WS
                    val offerPayload = org.json.JSONObject().apply {
                        put("type", "OFFER")
                        put("sdp", sdp.description)
                        put("toDeviceId", remoteDeviceId)
                    }.toString()
                    try {
                        val ws = com.manyeyes.signaling.WsClient(baseWs, token, deviceId)
                        ws.connect(object : okhttp3.WebSocketListener() {})
                        ws.send(offerPayload)
                        Timber.i("[Streamer] OFFER sent -> $remoteDeviceId")
                    } catch (e: Exception) {
                        Timber.e(e, "[Streamer] Failed to send OFFER")
                    }
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
