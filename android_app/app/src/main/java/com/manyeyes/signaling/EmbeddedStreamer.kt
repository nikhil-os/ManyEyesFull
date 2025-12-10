package com.manyeyes.signaling

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.manyeyes.webrtc.WebRtcManager
import org.webrtc.*
import timber.log.Timber

/**
 * Embedded WebRTC streamer that runs within SignalingForegroundService.
 * This avoids Android 12+ restrictions on starting camera/mic foreground services from background.
 */
class EmbeddedStreamer(
    private val context: Context,
    private val onSendSignaling: (type: String, toDeviceId: String, data: Map<String, Any>) -> Unit
) {
    private var webrtc: WebRtcManager? = null
    private var currentRemoteId: String = ""
    private var pendingAnswer: String? = null
    private val pendingIce: MutableList<IceCandidate> = mutableListOf()
    private var answerApplied: Boolean = false
    private var negotiationInProgress: Boolean = false
    private var isInitializing: Boolean = false
    private var deviceId: String = ""
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    fun isStreaming(): Boolean = webrtc != null
    
    fun getCurrentRemoteId(): String = currentRemoteId
    
    /**
     * Start streaming to a remote device
     * Optimized for IMMEDIATE response - no unnecessary delays
     */
    fun startStreaming(remoteDeviceId: String, myDeviceId: String) {
        Timber.i("[EmbeddedStreamer] >>> startStreaming CALLED: remote=$remoteDeviceId, my=$myDeviceId")
        Timber.i("[EmbeddedStreamer] State: isInitializing=$isInitializing, webrtc=${webrtc != null}, currentRemoteId=$currentRemoteId")
        if (isInitializing) {
            Timber.w("[EmbeddedStreamer] Already initializing, ignoring")
            return
        }
        if (webrtc != null && currentRemoteId == remoteDeviceId) {
            Timber.i("[EmbeddedStreamer] Already streaming to $remoteDeviceId")
            return
        }
        
        // Reset if streaming to different device
        if (webrtc != null) {
            Timber.i("[EmbeddedStreamer] Resetting for new remote device")
            resetState()
        }
        
        isInitializing = true
        deviceId = myDeviceId
        currentRemoteId = remoteDeviceId
        
        val startTime = System.currentTimeMillis()
        Timber.i("[EmbeddedStreamer] Starting stream to $remoteDeviceId from $myDeviceId")
        Timber.i("[EmbeddedStreamer] About to start background thread for TURN fetch...")
        
        // Fetch TURN credentials on background thread
        // Using cached TURN servers makes this nearly instant
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            Timber.i("[EmbeddedStreamer] Background thread started for TURN fetch")
            val fetchedIce = mutableListOf<PeerConnection.IceServer>()
            try {
                Timber.i("[EmbeddedStreamer] Fetching TURN credentials...")
                val cfToken = "79c4a9c50eab05535b950221f3a3c63fc1aac9228c6df3072e8a0b84069edf98"
                val cfKeyId = "ddbb6ea7d57daf6ab17e5949b9568d16"
                val tempMgr = WebRtcManager(context)
                Timber.i("[EmbeddedStreamer] Calling fetchCloudflareIceServers...")
                val extra = tempMgr.fetchCloudflareIceServers(cfToken, cfKeyId)
                fetchedIce.addAll(extra)
                val elapsed = System.currentTimeMillis() - startTime
                Timber.i("[EmbeddedStreamer] TURN fetched in ${elapsed}ms: ${fetchedIce.size} servers")
            } catch (e: Exception) {
                Timber.e(e, "[EmbeddedStreamer] TURN fetch failed: ${e.message}")
            }
            
            Timber.i("[EmbeddedStreamer] Posting to main thread to create peer...")
            // Create peer on main thread IMMEDIATELY - no delay
            mainHandler.post {
                Timber.i("[EmbeddedStreamer] Main thread handler executing, isInitializing=$isInitializing")
                if (!isInitializing) {
                    Timber.w("[EmbeddedStreamer] Cancelled while fetching TURN")
                    return@post
                }
                Timber.i("[EmbeddedStreamer] Calling createPeerAndOffer...")
                createPeerAndOffer(fetchedIce, remoteDeviceId, myDeviceId, startTime)
            }
        }
    }
    
    private fun createPeerAndOffer(iceServers: List<PeerConnection.IceServer>, remoteDeviceId: String, myDeviceId: String, startTime: Long = System.currentTimeMillis()) {
        Timber.i("[EmbeddedStreamer] createPeerAndOffer called with ${iceServers.size} servers")
        
        // Use try-catch around each step to identify exactly where it fails
        val rtc: WebRtcManager
        try {
            Timber.i("[EmbeddedStreamer] Step 1: Creating WebRtcManager...")
            rtc = WebRtcManager(context)
            Timber.i("[EmbeddedStreamer] Step 1 DONE: WebRtcManager created")
        } catch (e: Exception) {
            Timber.e(e, "[EmbeddedStreamer] FAILED at Step 1: WebRtcManager constructor: ${e.message}")
            isInitializing = false
            return
        }
        
        try {
            Timber.i("[EmbeddedStreamer] Step 2: Initializing WebRtcManager...")
            rtc.init()
            Timber.i("[EmbeddedStreamer] Step 2 DONE: WebRtcManager initialized")
        } catch (e: Exception) {
            Timber.e(e, "[EmbeddedStreamer] FAILED at Step 2: rtc.init(): ${e.message}")
            isInitializing = false
            return
        }
        
        try {
            Timber.i("[EmbeddedStreamer] Step 3: Creating peer connection...")
            rtc.createPeer(iceServers, object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Timber.d("[EmbeddedStreamer] ICE candidate: ${candidate.sdp.take(50)}")
                    onSendSignaling("ICE", remoteDeviceId, mapOf(
                        "candidate" to candidate.sdp,
                        "sdpMid" to (candidate.sdpMid ?: ""),
                        "sdpMLineIndex" to candidate.sdpMLineIndex
                    ))
                }
                
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Timber.i("[EmbeddedStreamer] ICE state: $state")
                    // Log when connected so we know the stream should be working
                    if (state == PeerConnection.IceConnectionState.CONNECTED) {
                        Timber.i("[EmbeddedStreamer] ICE CONNECTED - video should be flowing")
                    }
                }
                
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                    Timber.d("[EmbeddedStreamer] ICE gathering: $state")
                }
                
                override fun onSignalingChange(state: PeerConnection.SignalingState) {
                    Timber.i("[EmbeddedStreamer] Signaling state: $state")
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {
                    Timber.i("[EmbeddedStreamer] onAddStream: ${stream?.videoTracks?.size} video, ${stream?.audioTracks?.size} audio")
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(channel: DataChannel?) {}
                override fun onRenegotiationNeeded() {
                    Timber.d("[EmbeddedStreamer] onRenegotiationNeeded")
                }
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    Timber.i("[EmbeddedStreamer] onAddTrack: ${receiver?.track()?.kind()}")
                }
            }, relayOnly = true)
            Timber.i("[EmbeddedStreamer] Step 3 DONE: Peer created with ${iceServers.size} ICE servers")
        } catch (e: Exception) {
            Timber.e(e, "[EmbeddedStreamer] FAILED at Step 3: createPeer: ${e.message}")
            isInitializing = false
            return
        }
        
        // Check permissions (outside try block so variables are accessible later)
        Timber.i("[EmbeddedStreamer] Step 4: Checking permissions...")
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Timber.i("[EmbeddedStreamer] Step 4 DONE: Permissions: camera=$cameraGranted, mic=$micGranted")
        
        if (!cameraGranted) {
            Timber.e("[EmbeddedStreamer] Camera permission not granted - cannot stream")
            isInitializing = false
            return
        }
        
        try {
            Timber.i("[EmbeddedStreamer] Step 5: Adding local tracks...")
            val added = rtc.addLocalTracks(includeAudio = micGranted, includeVideo = true)
            Timber.i("[EmbeddedStreamer] Step 5 DONE: Added local tracks: $added (${System.currentTimeMillis() - startTime}ms elapsed)")
        } catch (e: Exception) {
            Timber.e(e, "[EmbeddedStreamer] FAILED at Step 5: addLocalTracks: ${e.message}")
            isInitializing = false
            return
        }
            
        // MINIMAL delay - just enough for camera to initialize (100ms instead of 500ms)
        Timber.i("[EmbeddedStreamer] Step 6: Scheduling offer creation in 100ms...")
        mainHandler.postDelayed({
            Timber.i("[EmbeddedStreamer] Step 6: Delayed handler executing...")
            if (!isInitializing) {
                Timber.w("[EmbeddedStreamer] Cancelled during camera warmup")
                return@postDelayed
            }
            
            // Create offer AFTER tracks are added and camera has started
            negotiationInProgress = true
            Timber.i("[EmbeddedStreamer] Step 7: Creating offer... (${System.currentTimeMillis() - startTime}ms elapsed)")
            rtc.createOffer(wantVideo = true, wantAudio = micGranted) { sdp ->
                val offerTime = System.currentTimeMillis() - startTime
                Timber.i("[EmbeddedStreamer] Step 7 DONE: Offer created in ${offerTime}ms, SDP length=${sdp.description.length}")
                
                // Check SDP for video/audio lines
                val hasVideo = sdp.description.contains("m=video")
                val hasAudio = sdp.description.contains("m=audio")
                Timber.i("[EmbeddedStreamer] Offer has video=$hasVideo audio=$hasAudio")
                
                // Set local description FIRST
                rtc.setLocalDescription(sdp)
                    Timber.i("[EmbeddedStreamer] Local description set")
                    
                    // Save webrtc reference NOW (after offer created and local desc set)
                    webrtc = rtc
                    
                    // Apply any queued answer/ICE now that webrtc is set
                    mainHandler.post {
                        pendingAnswer?.let { answer ->
                            try {
                                Timber.i("[EmbeddedStreamer] Applying queued ANSWER...")
                                rtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, answer))
                                answerApplied = true
                                Timber.i("[EmbeddedStreamer] Applied queued ANSWER")
                            } catch (e: Exception) {
                                Timber.e(e, "[EmbeddedStreamer] Failed to apply queued ANSWER")
                            }
                            pendingAnswer = null
                        }
                        
                        if (pendingIce.isNotEmpty()) {
                            val iceToApply = pendingIce.toList()
                            pendingIce.clear()
                            iceToApply.forEach { ice ->
                                try {
                                    rtc.addIceCandidate(ice)
                                } catch (e: Exception) {
                                    Timber.e(e, "[EmbeddedStreamer] Failed to apply queued ICE")
                                }
                            }
                            Timber.i("[EmbeddedStreamer] Applied ${iceToApply.size} queued ICE")
                        }
                    }
                    
                    // Send OFFER
                    onSendSignaling("OFFER", remoteDeviceId, mapOf("sdp" to sdp.description))
                    Timber.i("[EmbeddedStreamer] OFFER sent to $remoteDeviceId (total ${System.currentTimeMillis() - startTime}ms)")
                    
                    negotiationInProgress = false
                    isInitializing = false
                    Timber.i("[EmbeddedStreamer] Initialization complete")
                }
            }, 100) // 100ms - minimal delay for camera init, fast response
    }
    
    /**
     * Handle incoming ANSWER
     */
    fun handleAnswer(sdp: String, fromDeviceId: String) {
        Timber.i("[EmbeddedStreamer] ANSWER received from $fromDeviceId, len=${sdp.length}")
        
        // Check SDP for video/audio lines
        val hasVideo = sdp.contains("m=video")
        val hasAudio = sdp.contains("m=audio")
        Timber.i("[EmbeddedStreamer] ANSWER has video=$hasVideo audio=$hasAudio")
        
        val rtc = webrtc
        if (rtc == null) {
            pendingAnswer = sdp
            Timber.w("[EmbeddedStreamer] WebRTC not ready, queued ANSWER")
            return
        }
        
        if (answerApplied) {
            Timber.i("[EmbeddedStreamer] ANSWER already applied, ignoring")
            return
        }
        
        try {
            rtc.setRemoteDescription(SessionDescription(SessionDescription.Type.ANSWER, sdp))
            answerApplied = true
            Timber.i("[EmbeddedStreamer] Applied ANSWER - media should start flowing")
        } catch (e: Exception) {
            Timber.e(e, "[EmbeddedStreamer] Failed to apply ANSWER")
        }
    }
    
    /**
     * Handle incoming ICE candidate
     */
    fun handleIce(candidate: String, sdpMid: String?, sdpMLineIndex: Int, fromDeviceId: String) {
        Timber.d("[EmbeddedStreamer] ICE from $fromDeviceId: mid=$sdpMid idx=$sdpMLineIndex")
        
        val ice = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        
        val rtc = webrtc
        if (rtc == null) {
            pendingIce.add(ice)
            Timber.w("[EmbeddedStreamer] WebRTC not ready, queued ICE (${pendingIce.size})")
            return
        }
        
        try {
            rtc.addIceCandidate(ice)
            Timber.d("[EmbeddedStreamer] Applied ICE")
        } catch (e: Exception) {
            Timber.e(e, "[EmbeddedStreamer] Failed to apply ICE")
        }
    }
    
    /**
     * Switch camera
     */
    fun switchCamera(callback: ((Boolean) -> Unit)? = null) {
        webrtc?.switchCamera(callback) ?: run {
            Timber.w("[EmbeddedStreamer] Cannot switch camera - not streaming")
            callback?.invoke(false)
        }
    }
    
    /**
     * Stop streaming and cleanup
     */
    fun stopStreaming() {
        Timber.i("[EmbeddedStreamer] Stopping stream")
        resetState()
    }
    
    private fun resetState() {
        val rtcToDispose = webrtc
        webrtc = null
        currentRemoteId = ""
        pendingAnswer = null
        pendingIce.clear()
        answerApplied = false
        negotiationInProgress = false
        isInitializing = false
        
        rtcToDispose?.let { rtc ->
            mainHandler.post {
                try {
                    rtc.dispose()
                    Timber.i("[EmbeddedStreamer] WebRTC disposed")
                } catch (e: Exception) {
                    Timber.e(e, "[EmbeddedStreamer] Error disposing WebRTC")
                }
            }
        }
    }
}
