package com.nakazato.airadio

import android.util.Log
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class NotifWebSocketClient(
    private val onReceiveFilename: (String) -> Unit
) : WebSocketListener() {

    private lateinit var webSocket: WebSocket

    private var pUrl = ""

    fun connect(url: String) {
        pUrl = url
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
//            .connectTimeout(10, TimeUnit.SECONDS) // クライアントとサーバーが接続する時、相手から応答があるまで何ミリ秒待つのか？を設定できます。設定した時間内に応答がない場合は接続が終了します。
            .pingInterval(15, TimeUnit.SECONDS) // 15秒ごとにping送信
            .build()

        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, this)
        client.dispatcher.executorService.shutdown()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        Log.d("NotifWebSocket", "✅ WebSocket connect OK")
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        Log.d("NotifWebSocket", "📥 msg receive: $text")

        try {
            val json = JSONObject(text)
            val action = json.getString("action")
            val value = json.getString("value")

//            if (action == "download") {
            if (action == "fileCreate") {
                Log.d("NotifWebSocket", "🎧 FileName: $value")
                onReceiveFilename(value)
            }
        } catch (e: Exception) {
            Log.e("NotifWebSocket", "JSON conv error: ${e.message}")
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        Log.e("NotifWebSocket", "onFailure: ${t.message}")
        // 必要に応じて再接続処理を書く
        connect(pUrl)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d("NotifWebSocket", "🔌 connection OK: $reason")
    }
}