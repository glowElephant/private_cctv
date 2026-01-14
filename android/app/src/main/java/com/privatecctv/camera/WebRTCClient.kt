package com.privatecctv.camera

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import org.webrtc.*
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator

class WebRTCClient(
    private val context: Context
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
    private var localRenderer: SurfaceViewRenderer? = null

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    private val externalSinks = mutableListOf<VideoSink>()

    fun getEglContext(): EglBase.Context? = eglBase?.eglBaseContext

    fun addVideoSink(sink: VideoSink) {
        externalSinks.add(sink)
        localVideoTrack?.addSink(sink)
    }

    fun removeVideoSink(sink: VideoSink) {
        localVideoTrack?.removeSink(sink)
        externalSinks.remove(sink)
    }

    fun setLocalRenderer(renderer: SurfaceViewRenderer) {
        localRenderer = renderer
        localRenderer?.init(eglBase?.eglBaseContext, null)
        localRenderer?.setMirror(false)
        localVideoTrack?.addSink(localRenderer)
        Log.d(TAG, "로컬 렌더러 설정됨")
    }

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

        Log.d(TAG, "Video capturer 생성: ${videoCapturer != null}")

        if (videoCapturer == null) {
            Log.e(TAG, "카메라를 찾을 수 없습니다!")
            return
        }

        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
        Log.d(TAG, "SurfaceTextureHelper 생성: ${surfaceTextureHelper != null}")

        videoCapturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)

        try {
            // 1920x1080 30fps (Full HD)
            videoCapturer?.startCapture(1920, 1080, 30)
            Log.d(TAG, "카메라 캡처 시작 (1920x1080@30fps)")
        } catch (e: Exception) {
            Log.e(TAG, "카메라 캡처 시작 실패: ${e.message}")
        }

        localVideoTrack = peerConnectionFactory?.createVideoTrack("video0", videoSource)
        localVideoTrack?.setEnabled(true)
        Log.d(TAG, "Video track 생성: ${localVideoTrack != null}, enabled: ${localVideoTrack?.enabled()}")

        // Audio
        val audioConstraints = MediaConstraints()
        val audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory?.createAudioTrack("audio0", audioSource)
        localAudioTrack?.setEnabled(true)
        Log.d(TAG, "Audio track 생성: ${localAudioTrack != null}")
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        // Camera2 사용 (고해상도 지원)
        Log.d(TAG, "Camera2 사용 시도")
        val enumerator = Camera2Enumerator(context)
        val cameraHandler = object : CameraVideoCapturer.CameraEventsHandler {
            override fun onCameraError(errorDescription: String) {
                Log.e(TAG, "카메라 에러: $errorDescription")
            }
            override fun onCameraDisconnected() {
                Log.w(TAG, "카메라 연결 끊김")
            }
            override fun onCameraFreezed(errorDescription: String) {
                Log.e(TAG, "카메라 멈춤: $errorDescription")
            }
            override fun onCameraOpening(cameraName: String) {
                Log.d(TAG, "카메라 열기: $cameraName")
            }
            override fun onFirstFrameAvailable() {
                Log.d(TAG, "첫 프레임 수신!")
            }
            override fun onCameraClosed() {
                Log.d(TAG, "카메라 닫힘")
            }
        }

        Log.d(TAG, "사용 가능한 카메라: ${enumerator.deviceNames.joinToString()}")

        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isBackFacing(deviceName)) {
                Log.d(TAG, "후면 카메라 사용: $deviceName")
                return enumerator.createCapturer(deviceName, cameraHandler)
            }
        }
        for (deviceName in enumerator.deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                Log.d(TAG, "전면 카메라 사용: $deviceName")
                return enumerator.createCapturer(deviceName, cameraHandler)
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
            val candidate = iceCandidateFromJson(candidateJson)
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

    fun switchCamera(onDone: ((Boolean) -> Unit)? = null) {
        videoCapturer?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                Log.d(TAG, "카메라 전환 완료: ${if (isFrontCamera) "전면" else "후면"}")
                onDone?.invoke(isFrontCamera)
            }

            override fun onCameraSwitchError(error: String) {
                Log.e(TAG, "카메라 전환 실패: $error")
            }
        })
    }

    fun release() {
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        localVideoTrack?.removeSink(localRenderer)
        externalSinks.forEach { localVideoTrack?.removeSink(it) }
        externalSinks.clear()
        localRenderer?.release()
        localRenderer = null
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

    private fun iceCandidateFromJson(json: String): IceCandidate {
        val gson = Gson()
        val obj = gson.fromJson(json, JsonObject::class.java)
        val sdpMid = obj.get("sdpMid")?.asString ?: "0"
        val sdpMLineIndex = obj.get("sdpMLineIndex")?.asInt ?: 0
        val candidate = obj.get("candidate")?.asString ?: ""
        return IceCandidate(sdpMid, sdpMLineIndex, candidate)
    }
}
