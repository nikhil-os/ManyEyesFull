package com.manyeyes.webrtc

import android.content.Context
import org.webrtc.*
import timber.log.Timber

class WebRtcManager(private val context: Context) {
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
                    .createInitializationOptions()
                PeerConnectionFactory.initialize(initializationOptions)
                initialized = true
                Timber.i("PeerConnectionFactory initialized")
            } else {
                Timber.d("PeerConnectionFactory already initialized; skipping")
            }

            val options = PeerConnectionFactory.Options()
            // Use software codecs to avoid EGL/JNI crashes during bring-up
            val encoderFactory = SoftwareVideoEncoderFactory()
            val decoderFactory = SoftwareVideoDecoderFactory()
            eglBase = EglBase.create()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            Timber.e(e, "PeerConnectionFactory init failed")
        }
    }

    fun createPeer(iceServers: List<PeerConnection.IceServer>, observer: PeerConnection.Observer) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, observer)
    }

    fun addLocalTracks(audioOnly: Boolean = true): Boolean {
        val factory = peerConnectionFactory ?: return false
        localAudioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack("ARDAMSa0", localAudioSource)
        val pc = peerConnection ?: return false
        pc.addTrack(localAudioTrack)
        if (!audioOnly) {
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
                        videoCapturer?.startCapture(640, 480, 30)
                    } catch (e: Exception) {
                        Timber.e(e, "startCapture failed; trying fallback 320x240@15")
                        try { videoCapturer?.startCapture(320, 240, 15) } catch (_: Exception) {}
                    }
                    localVideoTrack = factory.createVideoTrack("ARDAMSv0", localVideoSource)
                    pc.addTrack(localVideoTrack)
                }
            } catch (e: Exception) {
                Timber.e(e, "Video track setup failed; proceeding with audio-only")
            }
        }
        return true
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

    fun createOffer(onSdp: (SessionDescription) -> Unit) {
        val pc = peerConnection ?: return
        val constraints = MediaConstraints()
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        // Force audio-only to avoid video path JNI crashes during bring-up
        constraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
        peerConnection?.addIceCandidate(candidate)
    }
}
