package com.privatecctv.camera

import android.graphics.Bitmap
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.YuvHelper
import java.nio.ByteBuffer
import kotlin.math.abs

class MotionDetector(
    private val sensitivity: Int = 30,  // 픽셀 차이 임계값 (0-255)
    private val threshold: Double = 0.02  // 변화 픽셀 비율 임계값 (2%)
) : VideoSink {

    companion object {
        private const val TAG = "MotionDetector"
        private const val SAMPLE_INTERVAL = 10  // 10프레임마다 감지
    }

    private var previousFrame: ByteArray? = null
    private var frameCount = 0
    private var motionListener: ((Boolean) -> Unit)? = null
    private var lastMotionTime = 0L
    private var isMotionDetected = false

    fun setMotionListener(listener: (Boolean) -> Unit) {
        motionListener = listener
    }

    override fun onFrame(frame: VideoFrame) {
        frameCount++
        if (frameCount % SAMPLE_INTERVAL != 0) return

        try {
            val buffer = frame.buffer
            val width = buffer.width
            val height = buffer.height

            // I420 버퍼로 변환
            val i420Buffer = buffer.toI420() ?: return
            val yPlane = i420Buffer.dataY
            val ySize = width * height

            // Y 평면만 사용 (밝기 정보)
            val currentFrame = ByteArray(ySize)
            yPlane.position(0)
            yPlane.get(currentFrame)

            previousFrame?.let { prev ->
                if (prev.size == currentFrame.size) {
                    val motionDetected = detectMotion(prev, currentFrame, width, height)

                    if (motionDetected) {
                        lastMotionTime = System.currentTimeMillis()
                        if (!isMotionDetected) {
                            isMotionDetected = true
                            Log.d(TAG, "움직임 감지! 녹화 시작")
                            motionListener?.invoke(true)
                        }
                    } else if (isMotionDetected) {
                        // 5초간 움직임 없으면 종료
                        val elapsed = System.currentTimeMillis() - lastMotionTime
                        if (elapsed > 5000) {
                            isMotionDetected = false
                            Log.d(TAG, "5초 경과, 움직임 종료. 녹화 중지")
                            motionListener?.invoke(false)
                        } else {
                            Log.d(TAG, "움직임 없음, 대기 중... ${elapsed/1000}초")
                        }
                    }
                }
            }

            previousFrame = currentFrame
            i420Buffer.release()
        } catch (e: Exception) {
            Log.e(TAG, "프레임 처리 에러: ${e.message}")
        }
    }

    private fun detectMotion(prev: ByteArray, current: ByteArray, width: Int, height: Int): Boolean {
        var changedPixels = 0
        val totalPixels = width * height

        // 샘플링해서 비교 (성능 최적화)
        val step = 4
        for (i in 0 until totalPixels step step) {
            val diff = abs(current[i].toInt() and 0xFF - (prev[i].toInt() and 0xFF))
            if (diff > sensitivity) {
                changedPixels++
            }
        }

        val changeRatio = changedPixels.toDouble() / (totalPixels / step)
        return changeRatio > threshold
    }

    fun release() {
        previousFrame = null
        motionListener = null
    }
}
