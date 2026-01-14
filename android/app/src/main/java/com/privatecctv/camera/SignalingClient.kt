package com.privatecctv.camera

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val token: String,
    private val listener: Listener
) {
    companion object {
        private const val TAG = "SignalingClient"
    }

    interface Listener {
        fun onConnected()
        fun onViewerJoined(viewerId: String)
        fun onViewerLeft(viewerId: String)
        fun onAnswer(viewerId: String, answer: String)
        fun onIceCandidate(viewerId: String, candidate: String)
        fun onDisconnected()
        fun onError(error: String)
    }

    private var webSocket: WebSocket? = null
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .hostnameVerifier { _, _ -> true } // 자체 서명 인증서 허용
        .build()

    fun connect() {
        val wsUrl = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://") + "?role=camera&token=$token"

        Log.d(TAG, "연결 시도: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 연결됨")
                listener.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "메시지 수신: $text")
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 종료 중: $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 종료: $code $reason")
                listener.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 에러: ${t.message}")
                listener.onError(t.message ?: "연결 실패")
            }
        })
    }

    private fun handleMessage(text: String) {
        try {
            val json = gson.fromJson(text, JsonObject::class.java)
            val type = json.get("type")?.asString ?: return

            when (type) {
                "viewer-joined" -> {
                    val viewerId = json.get("viewerId")?.asString ?: return
                    listener.onViewerJoined(viewerId)
                }
                "viewer-left" -> {
                    val viewerId = json.get("viewerId")?.asString ?: return
                    listener.onViewerLeft(viewerId)
                }
                "answer" -> {
                    val viewerId = json.get("viewerId")?.asString ?: return
                    val answer = json.getAsJsonObject("answer")?.get("sdp")?.asString ?: return
                    listener.onAnswer(viewerId, answer)
                }
                "ice-candidate" -> {
                    val viewerId = json.get("viewerId")?.asString ?: return
                    val candidate = json.get("candidate")?.toString() ?: return
                    listener.onIceCandidate(viewerId, candidate)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "메시지 파싱 에러: ${e.message}")
        }
    }

    fun sendOffer(viewerId: String, offer: String) {
        val message = JsonObject().apply {
            addProperty("type", "offer")
            addProperty("targetId", viewerId)
            add("offer", JsonObject().apply {
                addProperty("type", "offer")
                addProperty("sdp", offer)
            })
        }
        send(message.toString())
    }

    fun sendIceCandidate(viewerId: String, candidate: String) {
        val message = JsonObject().apply {
            addProperty("type", "ice-candidate")
            addProperty("targetId", viewerId)
            add("candidate", gson.fromJson(candidate, JsonObject::class.java))
        }
        send(message.toString())
    }

    private fun send(message: String) {
        Log.d(TAG, "메시지 전송: $message")
        webSocket?.send(message)
    }

    fun disconnect() {
        webSocket?.close(1000, "사용자 종료")
        webSocket = null
    }
}
