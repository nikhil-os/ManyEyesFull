package com.manyeyes.webrtc

import android.content.Context
import org.webrtc.*
import timber.log.Timber
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class WebRtcManager(private val context: Context, private var externalEglBase: EglBase? = null) {
    companion object {
        @Volatile
        private var initialized: Boolean = false
    }
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var localVideoSource: VideoSource? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    fun init() {
        try {
            if (!initialized) {
                val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    // Force TCP-only by disabling UDP; keep IPv6 disabled
                    .setFieldTrials("WebRTC-DisableUdpTransport/Enabled/;WebRTC-IPv6Default/Disabled/")
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initializationOptions)
                initialized = true
                Timber.i("PeerConnectionFactory initialized")
            } else {
                Timber.d("PeerConnectionFactory already initialized; skipping")
            }

            val options = PeerConnectionFactory.Options()
            // Use external EglBase if provided (for shared context with renderer), otherwise create new
            eglBase = externalEglBase ?: EglBase.create()
            Timber.i("WebRtcManager using EglBase: external=${externalEglBase != null}")
            
            // Use hardware-accelerated codecs with EGL context for proper video rendering
            // DefaultVideoEncoderFactory/DecoderFactory use MediaCodec with EGL for GPU acceleration
            val encoderFactory = DefaultVideoEncoderFactory(eglBase!!.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)
            
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            Timber.e(e, "PeerConnectionFactory init failed")
        }
    }

    fun createPeer(iceServers: List<PeerConnection.IceServer>, observer: PeerConnection.Observer, relayOnly: Boolean = true) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        // Force TURN relay when requested to avoid NAT/UDP issues
        rtcConfig.iceTransportsType = if (relayOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        // Prefer TCP candidates wherever possible
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
    }

    fun fetchCloudflareIceServers(apiToken: String, turnKeyId: String, ttlSeconds: Int = 86400): List<PeerConnection.IceServer> {
        return try {
            val client = OkHttpClient()
            val bodyUrl = "https://rtc.live.cloudflare.com/v1/turn/keys/$turnKeyId/credentials/generate-ice-servers"
            val json = JSONObject().apply { put("ttl", ttlSeconds) }.toString()
            val media = "application/json".toMediaType()
            val req = Request.Builder()
                .url(bodyUrl)
                .header("Authorization", "Bearer $apiToken")
                .post(json.toRequestBody(media))
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Timber.w("TURN fetch failed: ${resp.code}")
                emptyList()
            } else {
                val txt = resp.body?.string() ?: "{}"
                val j = JSONObject(txt)
                val arr = j.optJSONArray("iceServers")
                val out = mutableListOf<PeerConnection.IceServer>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val srv = arr.getJSONObject(i)
                        val urls = srv.optJSONArray("urls")
                        val user = srv.optString("username", "")
                        val cred = srv.optString("credential", "")
                        if (urls != null) {
                            // Strict TCP-only TURN: prefer turns: (TLS/TCP) then turn: with transport=tcp. Exclude UDP and STUN.
                            val tcpOnly = mutableListOf<String>()
                            for (u in 0 until urls.length()) {
                                val url = urls.getString(u)
                                if (url.startsWith("turns:")) tcpOnly += url
                                else if (url.startsWith("turn:") && url.contains("transport=tcp")) tcpOnly += url
                            }
                            // Prepend Cloudflare static endpoints to force TCP-only allocations when available
                            if (user.isNotEmpty() && cred.isNotEmpty()) {
                                // Use documented global TURN host
                                tcpOnly.add(0, "turns:global.turn.cloudflare.com:443?transport=tcp")
                                tcpOnly.add(1, "turn:global.turn.cloudflare.com:3478?transport=tcp")
                            }
                            if (tcpOnly.isEmpty()) continue
                            // Log the final TURN URLs chosen for visibility
                            Timber.i("TURN URLs (tcp-only) for server #$i: ${tcpOnly.joinToString(", ")}")
                            val ice = if (user.isNotEmpty() && cred.isNotEmpty()) {
                                PeerConnection.IceServer.builder(tcpOnly).setUsername(user).setPassword(cred).createIceServer()
                            } else {
                                PeerConnection.IceServer.builder(tcpOnly).createIceServer()
                            }
                            out += ice
                        }
                    }
                }
                Timber.i("Fetched ${out.size} ICE servers from Cloudflare")
                out
            }
        } catch (e: Exception) {
            Timber.e(e, "TURN fetch exception")
            emptyList()
        }
    }

    fun prepareReceivers(receiveAudio: Boolean = true, receiveVideo: Boolean = true) {
        val pc = peerConnection ?: return
        try {
            if (receiveAudio) {
                val t = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
                t?.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
            if (receiveVideo) {
                val t = pc.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO)
                t?.direction = RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
            }
        } catch (e: Exception) {
            Timber.w(e, "prepareReceivers failed; continuing")
        }
    }

    fun addLocalTracks(includeAudio: Boolean = true, includeVideo: Boolean = true): Boolean {
        val factory = peerConnectionFactory ?: return false
        val pc = peerConnection ?: return false
        if (includeAudio) {
            localAudioSource = factory.createAudioSource(MediaConstraints())
            localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource)
            try { pc.addTrack(localAudioTrack) } catch (_: Exception) {}
        }
        if (includeVideo) {
            try {
                val enumerator = Camera2Enumerator(context)
                val deviceNames = enumerator.deviceNames
                val front = deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                val chosen = front ?: deviceNames.firstOrNull()
                if (chosen == null) {
                    Timber.w("No camera device available; skipping video")
                } else {
                    videoCapturer = enumerator.createCapturer(chosen, null)
                    val eglCtx = eglBase?.eglBaseContext
                    surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglCtx)
                    localVideoSource = factory.createVideoSource(false)
                    videoCapturer?.initialize(surfaceTextureHelper, context, localVideoSource?.capturerObserver)
                    try {
                        // Prefer 16:9 low bitrate-friendly capture
                        videoCapturer?.startCapture(640, 360, 15)
                        Timber.i("Video capture started at 640x360@15")
                    } catch (e: Exception) {
                        Timber.e(e, "startCapture 640x360@15 failed; trying fallback 640x480@15 then 320x240@15")
                        try {
                            videoCapturer?.startCapture(640, 480, 15)
                            Timber.i("Video capture started at 640x480@15 (fallback)")
                        } catch (e2: Exception) {
                            Timber.e(e2, "startCapture 640x480@15 failed; trying 320x240@15")
                            try { videoCapturer?.startCapture(320, 240, 15) } catch (_: Exception) {}
                            Timber.i("Video capture started at 320x240@15 (fallback)")
                        }
                    }
                    localVideoTrack = factory.createVideoTrack("ARDAMSv0", localVideoSource)
                    val sender = try { pc.addTrack(localVideoTrack) } catch (_: Exception) { null }
                    // Cap bitrate/framerate to be TURN TCP friendly
                    try {
                        val params = sender?.parameters
                        if (params != null && params.encodings.isNotEmpty()) {
                            params.encodings.forEach { enc ->
                                enc.maxBitrateBps = 600_000
                                enc.maxFramerate = 15
                                enc.scaleResolutionDownBy = 1.0
                            }
                            sender.setParameters(params)
                            Timber.i("Video encoding params set: maxBitrate=600kbps, maxFramerate=15")
                        } else {
                            Timber.w("RtpSender parameters unavailable; skipping bitrate cap")
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to set video encoding parameters")
                    }
                    Timber.i("Local video track added to peer")
                }
            } catch (e: Exception) {
                Timber.e(e, "Video track setup failed; proceeding without video")
            }
        }
        return true
    }

    /**
     * Switch between front and back camera.
     * Only works if videoCapturer is a CameraVideoCapturer.
     */
    fun switchCamera(callback: ((Boolean) -> Unit)? = null) {
        val capturer = videoCapturer
        if (capturer is CameraVideoCapturer) {
            Timber.i("[WebRtcManager] Switching camera...")
            capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
                override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                    Timber.i("[WebRtcManager] Camera switched, isFrontFacing=$isFrontFacing")
                    callback?.invoke(true)
                }
                override fun onCameraSwitchError(error: String?) {
                    Timber.e("[WebRtcManager] Camera switch failed: $error")
                    callback?.invoke(false)
                }
            })
        } else {
            Timber.w("[WebRtcManager] Cannot switch camera - capturer is not CameraVideoCapturer")
            callback?.invoke(false)
        }
    }

    fun dispose() {
        try { videoCapturer?.stopCapture() } catch (_: Exception) {}
        videoCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        localVideoSource?.dispose()
        localAudioSource?.dispose()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
    }

    fun createOffer(wantVideo: Boolean = false, wantAudio: Boolean = true, onSdp: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", if (wantAudio) "true" else "false"))
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (wantVideo) "true" else "false"))
        pc.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { onSdp(sdp) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { Timber.e("Offer failed: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, constraints)
    }

    fun setLocalDescription(sdp: SessionDescription) {
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { Timber.e("Set local failed: $p0") }
        }, sdp)
    }

    fun setRemoteDescription(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { Timber.e("Set remote failed: $p0") }
        }, sdp)
    }

    fun createAnswer(onSdp: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) { onSdp(sdp) }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { Timber.e("Answer failed: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
    }

    fun addIceCandidate(candidate: IceCandidate) {
        // Log candidate type/protocol for diagnostics
        try {
            val sdp = candidate.sdp ?: ""
            val proto = when {
                sdp.contains(" typ relay ") && sdp.contains(" udp ") -> "relay/udp"
                sdp.contains(" typ relay ") && (sdp.contains(" tcp ") || sdp.contains(" tcptype")) -> "relay/tcp"
                sdp.contains(" typ srflx ") -> "srflx"
                sdp.contains(" typ host ") -> "host"
                else -> "unknown"
            }
            Timber.i("Applying remote ICE proto=$proto mid=${candidate.sdpMid} idx=${candidate.sdpMLineIndex}")
        } catch (_: Exception) {}
        peerConnection?.addIceCandidate(candidate)
    }
}
