package com.privatecctv.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.privatecctv.camera.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private var webRTCClient: WebRTCClient? = null
    private var signalingClient: SignalingClient? = null
    private var isStreaming = false
    private var cameraProvider: ProcessCameraProvider? = null
    private var isPreviewVisible = false

    // 녹화 관련
    private var motionDetector: MotionDetector? = null
    private var videoRecorder: VideoRecorder? = null
    private lateinit var storageManager: StorageManager
    private var isMotionDetectionEnabled = false
    private var isRecording = false
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private val NETWORK_OPTIONS = arrayOf("내부망 (집 안)", "외부망 (외부 접속)")
        private val NETWORK_URLS = arrayOf(
            "https://192.168.1.3:8443",
            "https://175.213.169.230:8443"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraExecutor = Executors.newSingleThreadExecutor()
        storageManager = StorageManager(this)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setupUI()
        updateStorageInfo()
    }

    private fun setupUI() {
        // 네트워크 선택 스피너 설정
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, NETWORK_OPTIONS)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerNetwork.adapter = adapter

        binding.btnConnect.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            webRTCClient?.switchCamera { isFrontCamera ->
                runOnUiThread {
                    Toast.makeText(this, if (isFrontCamera) "전면 카메라" else "후면 카메라", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnPreview.setOnClickListener {
            togglePreview()
        }

        // 움직임 감지 스위치
        binding.switchMotion.setOnCheckedChangeListener { _, isChecked ->
            isMotionDetectionEnabled = isChecked
            if (isChecked && isStreaming) {
                startMotionDetection()
            } else {
                stopMotionDetection()
            }
        }

        updateStatus("대기 중")
    }

    private fun startMotionDetection() {
        if (motionDetector != null) return

        addLog("움직임 감지 시작")
        updateRecordingStatus("감지 대기 중...")

        motionDetector = MotionDetector().apply {
            setMotionListener { motionDetected ->
                runOnUiThread {
                    if (motionDetected) {
                        startRecording()
                    } else {
                        stopRecording()
                    }
                }
            }
        }

        videoRecorder = VideoRecorder(
            baseDir = storageManager.recordingDir,
            onRecordingStarted = { file ->
                runOnUiThread {
                    addLog("녹화 시작: ${file.name}")
                    updateRecordingStatus("● 녹화 중: ${file.name}")
                    binding.tvRecordingStatus.setTextColor(0xFFFF5252.toInt())
                }
            },
            onRecordingStopped = { file ->
                runOnUiThread {
                    addLog("녹화 완료: ${file.name}")
                    updateRecordingStatus("감지 대기 중...")
                    binding.tvRecordingStatus.setTextColor(0xFF888888.toInt())
                    updateStorageInfo()
                    // 용량 체크 및 정리
                    cameraExecutor.execute {
                        storageManager.cleanupIfNeeded()
                        runOnUiThread { updateStorageInfo() }
                    }
                }
            },
            onMaxDurationReached = {
                runOnUiThread {
                    addLog("5분 초과, 녹화 분할")
                    // 현재 녹화 중지 후 새로 시작
                    videoRecorder?.stopRecording()
                    videoRecorder?.startRecording()
                }
            }
        )

        webRTCClient?.addVideoSink(motionDetector!!)
        webRTCClient?.addVideoSink(videoRecorder!!)
    }

    private fun stopMotionDetection() {
        stopRecording()

        motionDetector?.let {
            webRTCClient?.removeVideoSink(it)
            it.release()
        }
        motionDetector = null

        videoRecorder?.let {
            webRTCClient?.removeVideoSink(it)
            it.release()
        }
        videoRecorder = null

        updateRecordingStatus("녹화 대기")
        binding.tvRecordingStatus.setTextColor(0xFF888888.toInt())
        addLog("움직임 감지 중지")
    }

    private fun startRecording() {
        if (isRecording) return
        isRecording = true
        videoRecorder?.startRecording()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        videoRecorder?.stopRecording()
    }

    private fun updateRecordingStatus(status: String) {
        binding.tvRecordingStatus.text = status
    }

    private fun updateStorageInfo() {
        val size = storageManager.getTotalSizeFormatted()
        val count = storageManager.getRecordingCount()
        binding.tvStorageInfo.text = "저장: $size ($count 개 파일)"
    }

    private fun togglePreview() {
        isPreviewVisible = !isPreviewVisible
        if (isPreviewVisible) {
            binding.streamingBackground.visibility = View.GONE
            binding.localVideoView.visibility = View.VISIBLE
            webRTCClient?.setLocalRenderer(binding.localVideoView)
            binding.btnPreview.text = "전송화면 숨기기"
        } else {
            binding.localVideoView.visibility = View.GONE
            binding.streamingBackground.visibility = View.VISIBLE
            binding.btnPreview.text = "전송화면 보기"
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                Log.e(TAG, "카메라 바인딩 실패", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun startStreaming() {
        val selectedIndex = binding.spinnerNetwork.selectedItemPosition
        val serverUrl = NETWORK_URLS[selectedIndex]
        val token = binding.etToken.text.toString()

        if (token.isEmpty()) {
            Toast.makeText(this, "카메라 토큰을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        val networkName = if (selectedIndex == 0) "내부망" else "외부망"
        updateStatus("연결 중... ($networkName)")

        // CameraX 중지 후 WebRTC에서 카메라 사용
        stopCamera()
        binding.previewView.visibility = View.GONE
        binding.streamingBackground.visibility = View.VISIBLE
        binding.btnPreview.visibility = View.VISIBLE

        webRTCClient = WebRTCClient(this)
        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            token = token,
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
                    runOnUiThread {
                        updateStatus("서버 연결됨")
                        isStreaming = true
                        binding.btnConnect.text = "스트리밍 중지"

                        // 움직임 감지가 켜져있으면 시작
                        if (isMotionDetectionEnabled) {
                            startMotionDetection()
                        }
                    }
                }

                override fun onViewerJoined(viewerId: String) {
                    runOnUiThread {
                        updateStatus("뷰어 연결: $viewerId")
                    }
                    webRTCClient?.createOffer(viewerId) { offer ->
                        signalingClient?.sendOffer(viewerId, offer)
                    }
                }

                override fun onViewerLeft(viewerId: String) {
                    runOnUiThread {
                        updateStatus("뷰어 퇴장: $viewerId")
                    }
                    webRTCClient?.removeViewer(viewerId)
                }

                override fun onAnswer(viewerId: String, answer: String) {
                    runOnUiThread { addLog("Answer 수신") }
                    webRTCClient?.setRemoteAnswer(viewerId, answer)
                }

                override fun onIceCandidate(viewerId: String, candidate: String) {
                    runOnUiThread { addLog("ICE candidate 수신") }
                    webRTCClient?.addIceCandidate(viewerId, candidate)
                }

                override fun onDisconnected() {
                    runOnUiThread {
                        updateStatus("연결 끊김")
                        stopStreaming()
                    }
                }

                override fun onError(error: String) {
                    runOnUiThread {
                        updateStatus("에러: $error")
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )

        webRTCClient?.initialize { localVideoTrack, localAudioTrack ->
            runOnUiThread {
                addLog("WebRTC 초기화 완료")
                addLog("비디오: ${localVideoTrack != null}, 오디오: ${localAudioTrack != null}")
            }
            webRTCClient?.setIceCandidateListener { viewerId, candidate ->
                signalingClient?.sendIceCandidate(viewerId, candidate)
            }
            // 트랙 준비 완료 후 서버 연결
            signalingClient?.connect()
        }
    }

    private fun stopStreaming() {
        // 녹화 중지
        stopMotionDetection()
        binding.switchMotion.isChecked = false

        signalingClient?.disconnect()
        webRTCClient?.release()
        signalingClient = null
        webRTCClient = null
        isStreaming = false
        isPreviewVisible = false
        binding.btnConnect.text = "스트리밍 시작"
        binding.btnPreview.visibility = View.GONE
        binding.btnPreview.text = "전송화면 보기"
        binding.localVideoView.visibility = View.GONE
        binding.streamingBackground.visibility = View.GONE
        binding.previewView.visibility = View.VISIBLE
        updateStatus("대기 중")
        // CameraX 프리뷰 재시작
        startCamera()
    }

    private val logList = mutableListOf<String>()

    private fun updateStatus(status: String) {
        binding.tvStatus.text = status
        addLog(status)
    }

    private fun addLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logList.add("[$time] $msg")
        if (logList.size > 50) logList.removeAt(0) // 최대 50개 유지
        binding.tvLog.text = logList.joinToString("\n")
        binding.logScrollView.post {
            binding.logScrollView.fullScroll(View.FOCUS_DOWN)
        }
        Log.d(TAG, msg)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "카메라/마이크 권한이 필요합니다", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMotionDetection()
        stopStreaming()
        cameraExecutor.shutdown()
    }
}
