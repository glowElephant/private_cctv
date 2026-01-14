package com.privatecctv.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

class VideoRecorder(
    private val baseDir: File,
    private val onRecordingStarted: ((File) -> Unit)? = null,
    private val onRecordingStopped: ((File) -> Unit)? = null,
    private val onMaxDurationReached: (() -> Unit)? = null
) : VideoSink {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val MIME_TYPE = "video/avc"
        private const val BIT_RATE = 2_000_000  // 2 Mbps
        private const val FRAME_RATE = 15
        private const val I_FRAME_INTERVAL = 2
        private const val MAX_RECORDING_MS = 5 * 60 * 1000L  // 5분
    }

    private var encoder: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var isRecording = false
    private var encoderReady = false
    private var currentFile: File? = null

    private var videoWidth = 0
    private var videoHeight = 0

    private val frameQueue = LinkedBlockingQueue<ByteArray>(10)
    private var encoderThread: Thread? = null
    private var frameCount = 0L
    private var startTimeNs = 0L
    private var recordingStartMs = 0L

    @Synchronized
    fun startRecording() {
        if (isRecording) return
        isRecording = true
        encoderReady = false
        frameCount = 0
        startTimeNs = System.nanoTime()
        Log.d(TAG, "녹화 대기 중... (첫 프레임 기다리는 중)")
    }

    private fun initializeEncoder(width: Int, height: Int) {
        try {
            videoWidth = width
            videoHeight = height

            // 날짜별 폴더 생성
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val timeFormat = SimpleDateFormat("HH_mm_ss", Locale.getDefault())
            val now = Date()

            val dateFolder = File(baseDir, dateFormat.format(now))
            if (!dateFolder.exists()) {
                dateFolder.mkdirs()
            }

            val fileName = "${timeFormat.format(now)}.mp4"
            currentFile = File(dateFolder, fileName)

            Log.d(TAG, "녹화 시작: ${currentFile?.absolutePath}, 크기: ${width}x${height}")

            // 인코더 설정
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // Muxer 설정
            currentFile?.let {
                muxer = MediaMuxer(it.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            }

            encoderReady = true
            recordingStartMs = System.currentTimeMillis()
            startEncoderThread()

            currentFile?.let { onRecordingStarted?.invoke(it) }
        } catch (e: Exception) {
            Log.e(TAG, "인코더 초기화 실패: ${e.message}")
            e.printStackTrace()
            stopRecording()
        }
    }

    @Synchronized
    fun stopRecording() {
        if (!isRecording && !encoderReady) return

        Log.d(TAG, "녹화 중지 중...")
        isRecording = false
        encoderReady = false

        try {
            // 인코더 스레드 종료 대기
            encoderThread?.interrupt()
            encoderThread?.join(2000)
            encoderThread = null

            // 남은 프레임 처리
            drainEncoder(true)

            encoder?.stop()
            encoder?.release()
            encoder = null

            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    Log.e(TAG, "Muxer stop 에러: ${e.message}")
                }
                muxerStarted = false
            }
            muxer?.release()
            muxer = null
            trackIndex = -1

            frameQueue.clear()

            currentFile?.let {
                if (it.exists() && it.length() > 0) {
                    Log.d(TAG, "녹화 완료: ${it.absolutePath}, 크기: ${it.length() / 1024}KB")
                    onRecordingStopped?.invoke(it)
                } else {
                    Log.w(TAG, "녹화 파일이 비어있음, 삭제")
                    it.delete()
                }
            }
            currentFile = null
        } catch (e: Exception) {
            Log.e(TAG, "녹화 중지 에러: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun startEncoderThread() {
        encoderThread = thread(start = true, name = "VideoEncoder") {
            Log.d(TAG, "인코더 스레드 시작")
            while (isRecording && encoderReady && !Thread.interrupted()) {
                try {
                    val yuvData = frameQueue.poll()
                    if (yuvData != null) {
                        encodeFrame(yuvData)
                    } else {
                        Thread.sleep(10)
                    }
                    drainEncoder(false)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "인코딩 에러: ${e.message}")
                }
            }
            Log.d(TAG, "인코더 스레드 종료")
        }
    }

    override fun onFrame(frame: VideoFrame) {
        if (!isRecording) return

        val buffer = frame.buffer
        val width = buffer.width
        val height = buffer.height

        // 첫 프레임에서 인코더 초기화
        if (!encoderReady && isRecording) {
            initializeEncoder(width, height)
            if (!encoderReady) return
        }

        // 5분 초과 체크
        if (encoderReady && System.currentTimeMillis() - recordingStartMs > MAX_RECORDING_MS) {
            Log.d(TAG, "최대 녹화 시간(5분) 초과, 자동 분할")
            onMaxDurationReached?.invoke()
            return
        }

        // 크기가 다르면 무시
        if (width != videoWidth || height != videoHeight) {
            return
        }

        try {
            val i420Buffer = buffer.toI420() ?: return

            // stride 고려한 YUV 데이터 추출
            val ySize = width * height
            val uvSize = (width / 2) * (height / 2)
            val yuvData = ByteArray(ySize + uvSize * 2)

            val yStride = i420Buffer.strideY
            val uStride = i420Buffer.strideU
            val vStride = i420Buffer.strideV

            val yBuffer = i420Buffer.dataY
            val uBuffer = i420Buffer.dataU
            val vBuffer = i420Buffer.dataV

            // Y plane (stride 고려)
            if (yStride == width) {
                yBuffer.position(0)
                yBuffer.get(yuvData, 0, ySize)
            } else {
                for (row in 0 until height) {
                    yBuffer.position(row * yStride)
                    yBuffer.get(yuvData, row * width, width)
                }
            }

            // U plane (stride 고려)
            val uvWidth = width / 2
            val uvHeight = height / 2
            if (uStride == uvWidth) {
                uBuffer.position(0)
                uBuffer.get(yuvData, ySize, uvSize)
            } else {
                for (row in 0 until uvHeight) {
                    uBuffer.position(row * uStride)
                    uBuffer.get(yuvData, ySize + row * uvWidth, uvWidth)
                }
            }

            // V plane (stride 고려)
            if (vStride == uvWidth) {
                vBuffer.position(0)
                vBuffer.get(yuvData, ySize + uvSize, uvSize)
            } else {
                for (row in 0 until uvHeight) {
                    vBuffer.position(row * vStride)
                    vBuffer.get(yuvData, ySize + uvSize + row * uvWidth, uvWidth)
                }
            }

            i420Buffer.release()

            // 큐에 추가 (가득 차면 오래된 프레임 버림)
            if (!frameQueue.offer(yuvData)) {
                frameQueue.poll()
                frameQueue.offer(yuvData)
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 처리 에러: ${e.message}")
        }
    }

    private fun encodeFrame(yuvData: ByteArray) {
        val encoder = this.encoder ?: return

        try {
            val inputBufferIndex = encoder.dequeueInputBuffer(10000)
            if (inputBufferIndex >= 0) {
                val inputBuffer = encoder.getInputBuffer(inputBufferIndex) ?: return

                inputBuffer.clear()
                inputBuffer.put(yuvData)

                val presentationTimeUs = (System.nanoTime() - startTimeNs) / 1000
                encoder.queueInputBuffer(inputBufferIndex, 0, yuvData.size, presentationTimeUs, 0)
                frameCount++
            }
        } catch (e: Exception) {
            Log.e(TAG, "프레임 인코딩 에러: ${e.message}")
        }
    }

    private fun drainEncoder(endOfStream: Boolean) {
        val encoder = this.encoder ?: return
        val muxer = this.muxer ?: return

        if (endOfStream) {
            try {
                encoder.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.e(TAG, "signalEndOfInputStream 에러: ${e.message}")
            }
        }

        val bufferInfo = MediaCodec.BufferInfo()
        var retryCount = 0
        val maxRetries = if (endOfStream) 100 else 10

        while (retryCount < maxRetries) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                    retryCount++
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        Log.d(TAG, "Muxer 시작됨, trackIndex: $trackIndex")
                    }
                }
                outputBufferIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outputBufferIndex)

                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            try {
                                muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                            } catch (e: Exception) {
                                Log.e(TAG, "writeSampleData 에러: ${e.message}")
                            }
                        }
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        Log.d(TAG, "EOS 수신")
                        break
                    }
                }
            }
        }
    }

    fun release() {
        stopRecording()
    }
}
