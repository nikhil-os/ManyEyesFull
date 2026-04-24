package com.manyeyes.signaling

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import com.manyeyes.webrtc.WebRtcManager
import org.webrtc.*
import timber.log.Timber

/**
 * Screen sharing streamer that captures the device screen via MediaProjection
 * and streams it over WebRTC to a remote viewer device.
 *
 * Uses ScreenCapturerAndroid from the WebRTC library which wraps MediaProjection.
 */
class ScreenShareStreamer(
    private val context: Context,
    private val onSendSignaling: (type: String, toDeviceId: String, data: Map<String, Any>) -> Unit
) {
    private var webrtc: WebRtcManager? = null
    private var currentRemoteId: String = ""
    private var pendingAnswer: String? = null
    private val pendingIce: MutableList<IceCandidate> = mutableListOf()
    private var answerApplied: Boolean = false
    private var isInitializing: Boolean = false
    private var deviceId: String = ""
    private var screenCapturer: ScreenCapturerAndroid? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isSharing(): Boolean = webrtc != null

    fun getCurrentRemoteId(): String = currentRemoteId

    /**
     * Start screen sharing to a remote device.
     * @param remoteDeviceId The device that wants to view our screen
     * @param myDeviceId Our own device ID
     * @param resultCode The result code from the MediaProjection consent dialog
     * @param resultData The result data Intent from the MediaProjection consent dialog
     */
    fun startSharing(remoteDeviceId: String, myDeviceId: String, resultCode: Int, resultData: Intent) {
        Timber.i("[ScreenShare] >>> startSharing CALLED: remote=$remoteDeviceId, my=$myDeviceId")
        if (isInitializing) {
            Timber.w("[ScreenShare] Already initializing, ignoring")
            return
        }
        if (webrtc != null && currentRemoteId == remoteDeviceId) {
            Timber.i("[ScreenShare] Already sharing to $remoteDeviceId")
            return
        }

        // Reset if sharing to different device
        if (webrtc != null) {
            Timber.i("[ScreenShare] Resetting for new remote device")
            stopSharing()
        }

        isInitializing = true
        deviceId = myDeviceId
        currentRemoteId = remoteDeviceId

        val startTime = System.currentTimeMillis()

        // Fetch TURN credentials on background thread
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            val fetchedIce = mutableListOf<PeerConnection.IceServer>()
            try {
                val tempMgr = WebRtcManager(context)
                val extra = tempMgr.fetchCloudflareIceServers(
                    com.manyeyes.TurnConfig.CLOUDFLARE_TOKEN,
                    com.manyeyes.TurnConfig.CLOUDFLARE_KEY_ID
                )
                fetchedIce.addAll(extra)
                Timber.i("[ScreenShare] TURN fetched: ${fetchedIce.size} servers in ${System.currentTimeMillis() - startTime}ms")
            } catch (e: Exception) {
                Timber.e(e, "[ScreenShare] TURN fetch failed")
            }

            mainHandler.post {
                if (!isInitializing) {
                    Timber.w("[ScreenShare] Cancelled while fetching TURN")
                    return@post
                }
                createPeerAndOffer(fetchedIce, remoteDeviceId, myDeviceId, resultCode, resultData, startTime)
            }
        }
    }

    private fun createPeerAndOffer(
        iceServers: List<PeerConnection.IceServer>,
        remoteDeviceId: String,
        myDeviceId: String,
        resultCode: Int,
        resultData: Intent,
        startTime: Long
    ) {
        try {
            val rtc = WebRtcManager(context)
            rtc.init()
            webrtc = rtc

            rtc.createPeer(iceServers, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate ?: return
                    Timber.d("[ScreenShare] Local ICE: mid=${candidate.sdpMid}")
                    onSendSignaling("SCREEN_ICE", remoteDeviceId, mapOf(
                        "sdpMid" to (candidate.sdpMid ?: ""),
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "candidate" to (candidate.sdp ?: "")
                    ))
                }

                override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                    Timber.i("[ScreenShare] ICE state: $newState")
                    if (newState == PeerConnection.IceConnectionState.DISCONNECTED ||
                        newState == PeerConnection.IceConnectionState.FAILED) {
                        Timber.w("[ScreenShare] ICE disconnected/failed — stopping screen share")
                        mainHandler.post { stopSharing() }
                    }
                }

                override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
                override fun onIceConnectionReceivingChange(p0: Boolean) {}
                override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
                override fun onAddStream(p0: MediaStream?) {}
                override fun onRemoveStream(p0: MediaStream?) {}
                override fun onDataChannel(p0: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            })

            // Create screen capturer using MediaProjection result
            val mediaProjectionCallback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.i("[ScreenShare] MediaProjection stopped")
                    mainHandler.post { stopSharing() }
                }
            }

            screenCapturer = ScreenCapturerAndroid(resultData, mediaProjectionCallback)

            // Add screen capture as video track
            val factory = rtc.getPeerConnectionFactory() ?: run {
                Timber.e("[ScreenShare] PeerConnectionFactory is null")
                isInitializing = false
                return
            }

            val videoSource = factory.createVideoSource(screenCapturer!!.isScreencast)
            val eglCtx = rtc.getEglBaseContext()
            val surfaceTextureHelper = SurfaceTextureHelper.create("ScreenCaptureThread", eglCtx)

            screenCapturer!!.initialize(surfaceTextureHelper, context, videoSource.capturerObserver)
            // 720p @ 10fps — lightweight but smooth
            screenCapturer!!.startCapture(1280, 720, 10)
            Timber.i("[ScreenShare] Screen capture started at 1280x720@10fps")

            val videoTrack = factory.createVideoTrack("SCREENv0", videoSource)
            val pc = rtc.getPeerConnection()
            val sender = pc?.addTrack(videoTrack)

            // Cap bitrate for smooth TURN relay performance
            try {
                val params = sender?.parameters
                if (params != null && params.encodings.isNotEmpty()) {
                    params.encodings.forEach { enc ->
                        enc.maxBitrateBps = 800_000  // 800kbps — smooth for screen content
                        enc.maxFramerate = 10
                    }
                    sender.setParameters(params)
                    Timber.i("[ScreenShare] Bitrate capped at 800kbps, 10fps")
                }
            } catch (e: Exception) {
                Timber.e(e, "[ScreenShare] Failed to set encoding params")
            }

            // Create and send offer
            rtc.createOffer(wantVideo = true, wantAudio = false) { sdp ->
                rtc.setLocalDescription(sdp)
                Timber.i("[ScreenShare] SCREEN_OFFER created, sending to $remoteDeviceId")
                onSendSignaling("SCREEN_OFFER", remoteDeviceId, mapOf("sdp" to sdp.description))
                isInitializing = false

                // Apply any pending answer/ICE that arrived while we were setting up
                mainHandler.post {
                    pendingAnswer?.let { handleAnswer(it) }
                    val iceCopy = pendingIce.toList()
                    pendingIce.clear()
                    iceCopy.forEach { webrtc?.addIceCandidate(it) }
                }
            }

            val elapsed = System.currentTimeMillis() - startTime
            Timber.i("[ScreenShare] Setup complete in ${elapsed}ms")

        } catch (e: Exception) {
            Timber.e(e, "[ScreenShare] Failed to create peer and offer")
            isInitializing = false
        }
    }

    fun handleAnswer(sdp: String) {
        val rtc = webrtc
        if (rtc == null) {
            Timber.w("[ScreenShare] handleAnswer: webrtc is null, queueing")
            pendingAnswer = sdp
            return
        }
        Timber.i("[ScreenShare] Applying SCREEN_ANSWER")
        rtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
        answerApplied = true
        pendingAnswer = null
    }

    fun handleIce(candidate: IceCandidate) {
        val rtc = webrtc
        if (rtc == null || !answerApplied) {
            Timber.d("[ScreenShare] Queueing ICE (webrtc=${rtc != null}, answerApplied=$answerApplied)")
            pendingIce.add(candidate)
            return
        }
        rtc.addIceCandidate(candidate)
    }

    fun stopSharing() {
        Timber.i("[ScreenShare] Stopping screen share")
        try { screenCapturer?.stopCapture() } catch (_: Exception) {}
        try { screenCapturer?.dispose() } catch (_: Exception) {}
        screenCapturer = null
        try { webrtc?.dispose() } catch (_: Exception) {}
        webrtc = null
        currentRemoteId = ""
        pendingAnswer = null
        pendingIce.clear()
        answerApplied = false
        isInitializing = false
    }
}
