package com.privatecctv.camera

import android.content.Context
import android.util.Log
import androidx.camera.view.PreviewView
import org.webrtc.*

class WebRTCClient(
    private val context: Context,
    private val previewView: PreviewView
) {
    companion object {
        private const val TAG = "WebRTCClient"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    private var iceCandidateListener: ((String, String) -> Unit)? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun initialize(onReady: (VideoTrack?, AudioTrack?) -> Unit) {
        eglBase = EglBase.create()

        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase?.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        createLocalTracks()
        onReady(localVideoTrack, localAudioTrack)
    }

    private fun createLocalTracks() {
        // Video
        val videoSource = peerConnectionFactory?.createVideoSource(false)
        videoCapturer = createCameraCapturer()
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)

        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
        videoCapturer?.startCapture(1280, 720, 30)

        localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)

        // Audio
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                return enumerator.createCapturer(deviceName, null)
            }
        }
        return null
    }

    fun createOffer(viewerId: String, onOffer: (String) -> Unit) {
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    Log.d(TAG, "ICE candidate: ${candidate.sdp}")
                    iceCandidateListener?.invoke(viewerId, candidate.toJson())
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE connection state: $state")
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
            }
        )

        peerConnection?.let { pc ->
            localVideoTrack?.let { pc.addTrack(it) }
            localAudioTrack?.let { pc.addTrack(it) }

            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }

            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    pc.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            onOffer(sdp.description)
                        }
                        override fun onSetFailure(error: String) {
                            Log.e(TAG, "setLocalDescription 실패: $error")
                        }
                        override fun onCreateSuccess(sdp: SessionDescription) {}
                        override fun onCreateFailure(error: String) {}
                    }, sdp)
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String) {
                    Log.e(TAG, "createOffer 실패: $error")
                }
                override fun onSetFailure(error: String) {}
            }, constraints)

            peerConnections[viewerId] = pc
        }
    }

    fun setRemoteAnswer(viewerId: String, answer: String) {
        peerConnections[viewerId]?.let { pc ->
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, answer)
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    Log.d(TAG, "Remote answer 설정 완료")
                }
                override fun onSetFailure(error: String) {
                    Log.e(TAG, "Remote answer 설정 실패: $error")
                }
                override fun onCreateSuccess(sdp: SessionDescription) {}
                override fun onCreateFailure(error: String) {}
            }, sdp)
        }
    }

    fun addIceCandidate(viewerId: String, candidateJson: String) {
        try {
            val candidate = IceCandidate.fromJson(candidateJson)
            peerConnections[viewerId]?.addIceCandidate(candidate)
        } catch (e: Exception) {
            Log.e(TAG, "ICE candidate 추가 실패: ${e.message}")
        }
    }

    fun removeViewer(viewerId: String) {
        peerConnections[viewerId]?.close()
        peerConnections.remove(viewerId)
    }

    fun setIceCandidateListener(listener: (String, String) -> Unit) {
        iceCandidateListener = listener
    }

    fun switchCamera() {
        videoCapturer?.switchCamera(null)
    }

    fun release() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()
        surfaceTextureHelper?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()
    }

    private fun IceCandidate.toJson(): String {
        return """{"sdpMid":"$sdpMid","sdpMLineIndex":$sdpMLineIndex,"candidate":"$sdp"}"""
    }

    companion object {
        fun IceCandidate.Companion.fromJson(json: String): IceCandidate {
            val regex = Regex(""""sdpMid":"([^"]*)".*"sdpMLineIndex":(\d+).*"candidate":"([^"]*)"""")
            val match = regex.find(json) ?: throw Exception("Invalid ICE candidate JSON")
            return IceCandidate(
                match.groupValues[1],
                match.groupValues[2].toInt(),
                match.groupValues[3]
            )
        }
    }
}
