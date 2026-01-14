package com.privatecctv.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        setupUI()
    }

    private fun setupUI() {
        binding.btnConnect.setOnClickListener {
            if (isStreaming) {
                stopStreaming()
            } else {
                startStreaming()
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            webRTCClient?.switchCamera()
        }

        updateStatus("대기 중")
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                Log.e(TAG, "카메라 바인딩 실패", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startStreaming() {
        val serverUrl = binding.etServerUrl.text.toString()
        val token = binding.etToken.text.toString()

        if (serverUrl.isEmpty() || token.isEmpty()) {
            Toast.makeText(this, "서버 URL과 토큰을 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }

        updateStatus("연결 중...")

        webRTCClient = WebRTCClient(this, binding.previewView)
        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            token = token,
            listener = object : SignalingClient.Listener {
                override fun onConnected() {
                    runOnUiThread {
                        updateStatus("서버 연결됨")
                        isStreaming = true
                        binding.btnConnect.text = "스트리밍 중지"
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
                    webRTCClient?.setRemoteAnswer(viewerId, answer)
                }

                override fun onIceCandidate(viewerId: String, candidate: String) {
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
            webRTCClient?.setIceCandidateListener { viewerId, candidate ->
                signalingClient?.sendIceCandidate(viewerId, candidate)
            }
        }

        signalingClient?.connect()
    }

    private fun stopStreaming() {
        signalingClient?.disconnect()
        webRTCClient?.release()
        signalingClient = null
        webRTCClient = null
        isStreaming = false
        binding.btnConnect.text = "스트리밍 시작"
        updateStatus("대기 중")
    }

    private fun updateStatus(status: String) {
        binding.tvStatus.text = status
        Log.d(TAG, "Status: $status")
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
        stopStreaming()
        cameraExecutor.shutdown()
    }
}
