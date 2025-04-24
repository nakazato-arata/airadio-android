package com.nakazato.airadio

import android.app.AlertDialog
import android.media.MediaPlayer
import android.os.Bundle
import android.os.PowerManager
import android.widget.*
import androidx.activity.ComponentActivity

import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import org.json.JSONObject

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedList
import java.util.Queue


class MainActivity : ComponentActivity() {

    private val MIN_BUFFER_SIZE = 2 * 1024 * 1024 // 最小バッファサイズ（1M）

    private lateinit var editTextOtayori: EditText
    private lateinit var buttonSend: Button
    private lateinit var textViewStatus: TextView
    private lateinit var buttonPlayBGM: Button

    private var exoPlayer: ExoPlayer? = null
//    private lateinit var mediaPlayer: MediaPlayer
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var client: OkHttpClient
    private lateinit var socketSend: WebSocket
    private val SERVER_HOST = "wss://bekondaisuki.zapto.org"
    private val PORT_SEND = 9540
    private val NOTIF_PORT = 9541
//    private val FILE_PORT = 9542

//    private var audioManager : AudioPlayerManager? = null

    private var penName: String = ""

    private lateinit var webSocket: WebSocket
    private var mediaPlayer: MediaPlayer? = null
    private val audioBuffer = ByteArrayOutputStream()
    private var isForeground = false

    private var tempFile: File? = null

//    /** アプリが動いているかバックグラウンドにいるか確認 */
//    object AppState {
//        var isForeground = true
//    }

    private var isBuffering = false
    private var isWebSocketConnected = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioQueue: Queue<File> = LinkedList()
    private var isPlaying = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // UI部品の参照
        editTextOtayori = findViewById(R.id.editTextOtayori)
        buttonSend = findViewById(R.id.buttonSend)
        textViewStatus = findViewById(R.id.textViewStatus)
        buttonPlayBGM = findViewById(R.id.buttonPlayBGM)

        // 起動時にペンネーム入力
        showPenNameDialog()

//        // BGMの準備
//        mediaPlayer = MediaPlayer.create(this, R.raw.clover_honey) // res/raw に Clover_Honey.mp3 を入れる
//
//        buttonPlayBGM.setOnClickListener {
//            if (!mediaPlayer.isPlaying) {
//                mediaPlayer.isLooping = true
//                mediaPlayer.start()
//            }
//        }

        // お便り送信処理
        buttonSend.setOnClickListener {
            val otayori = editTextOtayori.text.toString()
            if (penName.isNotEmpty() && otayori.isNotEmpty()) {
                val message = "ペンネーム $penName さんのお便り。$otayori"
                sendText(message) // 後でWebSocket送信処理を実装
                editTextOtayori.setText("")
            }
        }

        // 一時ファイルの準備
        tempFile = File(cacheDir, "temp_audio.mp3")

        // Wake Lockの取得
//        requestWakeLock()

        connectWebSocket()
//        notifSocket.connect("$SERVER_HOST:$NOTIF_PORT/ws/")

//        audioManager = AudioPlayerManager(this)

//        val notifSocket = NotifWebSocketClient { filename ->
//            // ここでダウンロード処理を呼び出す
//            downloadFile(this, filename)
//        }

        // アプリ起動時などに接続
//        notifSocket.connect("$SERVER_HOST:$NOTIF_PORT/ws/")


        lifecycle.addObserver(MyObserver())
    }

    /**
     * 初回ダイアログ　ペンネームの入力欄を表示、送信
     */
    private fun showPenNameDialog() {
        val editText = EditText(this)
        editText.hint = "ペンネームを入力"

        AlertDialog.Builder(this)
            .setTitle("ようこそ")
            .setMessage("AIがトークを行います。\nペンネームを入力してください。")
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ ->
                val input = editText.text.toString().trim()
                if (input.isNotEmpty()) {
                    penName = input
                    val message = "ペンネーム $penName さんが参加しました。"
                    sendText(message)
                } else {
                    Toast.makeText(this, "ペンネームを入力してください", Toast.LENGTH_SHORT).show()
                    showPenNameDialog()
                }
            }
            .show()
    }

    /**
     * メッセージ送信
     */
    private fun sendText(message: String) {
        connectSendWebSocket()
//        val json = """{"action": "otayori", "value": "$message"}"""

        val json = JSONObject()
        json.put("action", "otayori")
        json.put("value", message)

        socketSend?.send(json.toString())
        Log.d("sendText", "send: $json".toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        textViewStatus.text = "📤 送信しました"
        socketSend?.close(1000, "送信完了")
    }

//    private fun requestWakeLock() {
//        try {
//            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
//            wakeLock = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "MyApp::WakeLockTag")
//            wakeLock?.acquire()
//        } catch (e: Exception) {
//            Log.d("requestWakeLock", e.message!!)
//        }
//    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer!!.release()
        wakeLock?.release()

        disconnectWebSocket()
        stopPlayback()
    }

    /**
     * メッセージ送信　接続　☆
     */
    private fun connectSendWebSocket() {
        client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
//            .connectTimeout(10, TimeUnit.SECONDS) // クライアントとサーバーが接続する時、相手から応答があるまで何ミリ秒待つのか？を設定できます。設定した時間内に応答がない場合は接続が終了します。
            .pingInterval(15, TimeUnit.SECONDS) // 15秒ごとにping送信
            .build()

        val request = Request.Builder()
            .url("$SERVER_HOST:$PORT_SEND/ws/")
            .build()

        socketSend = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    textViewStatus.text = "✅ WebSocket 接続成功"
                }
                Log.d("connectWebSocket", " WebSocket opened")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("connectWebSocket", " receive: $text".toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d("connectWebSocket", " binary receive: $bytes".toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    textViewStatus.text = "WebSocket onFailure: ${t.message}"
                }
                Log.d("connectWebSocket", "WebSocket error: ${t.message}".toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
                connectWebSocket() // 再接続
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("connectWebSocket", " WebSocket close: $code $reason".toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            }
        })
    }




    private fun connectWebSocket() {
        if (isWebSocketConnected) {
            Log.d(TAG, "connectWebSocket: 既に接続済み、スキップ")
            return
        }

        val client = OkHttpClient.Builder()
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url("$SERVER_HOST:$NOTIF_PORT/ws/").build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "onOpen: WebSocket接続成功")
                isWebSocketConnected = true
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
//                Log.d(TAG, "onMessage: データ受信、サイズ=${bytes.size}, 合計バッファ=${audioBuffer.size()}")
//                try {
//                    audioBuffer.write(bytes.toByteArray())
//                    if (!isBuffering && audioBuffer.size() >= MIN_BUFFER_SIZE) {
//                        isBuffering = true
//                        Log.d(TAG, "onMessage: バッファサイズ十分、再生開始")
//                        mainHandler.post { startPlayback() }
//                    }
//                } catch (e: Exception) {
//                    Log.e(TAG, "onMessage: データ処理エラー", e)
//                }

                val audioFile = File.createTempFile("audio_", ".mp3", cacheDir)
                FileOutputStream(audioFile).use { it.write(bytes.toByteArray()) }

                synchronized(audioQueue) {
                    audioQueue.add(audioFile)
                    if (!isPlaying) {
                        mainHandler.post { playNextInQueue() }
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.w(TAG, "onMessage: エラーメッセージ受信: $text")
                textViewStatus.text = "onMessage: エラーメッセージ受信: $text"
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "onClosed: WebSocket切断: code=$code, reason=$reason")
                textViewStatus.text = "onClosed: WebSocket切断: code=$code, reason=$reason"
                isWebSocketConnected = false
                mainHandler.post { stopPlayback() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "onFailure: WebSocketエラー", t)
                mainHandler.post { textViewStatus.text = "onFailure: WebSocketエラー" }
                isWebSocketConnected = false
                mainHandler.post { stopPlayback() }
//                if (lifecycle.currentState.isAtLeast(LifecycleOwner::getLifecycle().currentState)) {
//                    Log.d(TAG, "onFailure: 5秒後に再接続を試行")
//                    Thread.sleep(5000)
//                    connectWebSocket()
//                }
                Thread.sleep(5000)
//                connectWebSocket()
            }
        })
    }

    @OptIn(UnstableApi::class)
    private fun playNextInQueue() {
        synchronized(audioQueue) {
            val file = audioQueue.poll() ?: return
            isPlaying = true

            exoPlayer?.release()
            exoPlayer = ExoPlayer.Builder(this).build().apply {
                val mediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this@MainActivity))
                    .createMediaSource(MediaItem.fromUri(file.toURI().toString()))
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) {
                            file.delete()
                            this@MainActivity.isPlaying = false
                            playNextInQueue() // 再帰的に次の再生へ
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "再生エラー", error)
                        textViewStatus.text = "再生エラー"
                        file.delete()
                        this@MainActivity.isPlaying = false
                        playNextInQueue()
                    }

                })
            }
        }
    }


    @OptIn(UnstableApi::class)
    private fun startPlayback() {

        try {
            tempFile?.let { file ->
                // バッファの内容を一時ファイルに書き込み
                FileOutputStream(file).use { fos ->
                    fos.write(audioBuffer.toByteArray())
                }
                Log.d(TAG, "startPlayback: 一時ファイル書き込み完了、サイズ=${file.length()}")

                // ExoPlayerを初期化（非推奨APIを回避）
                exoPlayer?.release()
                exoPlayer = ExoPlayer.Builder(this)
                    .build()
                    .apply {
                        val dataSourceFactory = DefaultDataSource.Factory(this@MainActivity)
                        val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(file.toURI().toString()))
                        setMediaSource(mediaSource)
                        prepare()
                        playWhenReady = true
                        Log.d(TAG, "startPlayback: 再生開始")
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(state: Int) {
                                when (state) {
                                    Player.STATE_ENDED -> {
                                        Log.d(TAG, "startPlayback: 再生完了")
                                        audioBuffer.reset()
                                        file.delete()
                                        exoPlayer?.release()
                                        exoPlayer = null
                                        isBuffering = false
                                    }
                                    Player.STATE_BUFFERING -> Log.d(TAG, "startPlayback: バッファリング中")
                                    Player.STATE_READY -> Log.d(TAG, "startPlayback: 再生準備完了")
                                }
                            }

                            override fun onPlayerError(error: PlaybackException) {
                                super.onPlayerError(error)

                                Log.e(TAG, "startPlayback: ExoPlayerエラー", error)
                                audioBuffer.reset()
                                file.delete()
                                exoPlayer?.release()
                                exoPlayer = null
                                isBuffering = false
                            }
                        })
                    }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startPlayback: 再生エラー", e)
            tempFile?.delete()
            isBuffering = false
        }
    }

    private fun stopPlayback() {
        try {
            exoPlayer?.stop()
            exoPlayer?.release()
            exoPlayer = null
            audioBuffer.reset()
            tempFile?.delete()
            isBuffering = false
            isPlaying = false
            Log.d(TAG, "stopPlayback: 再生停止")

            while (true) {
                val file = audioQueue.poll() ?: break
                file.delete()
            }

            textViewStatus.text = "stopPlayback"
        } catch (e: Exception) {
            Log.e(TAG, "stopPlayback: 停止エラー", e)
        }
    }

    private fun disconnectWebSocket() {
        textViewStatus.text = "disconnectWebSocket"
        try {
            if (isWebSocketConnected) {
                webSocket.close(1000, "アクティビティが一時停止")
                isWebSocketConnected = false
                Log.d(TAG, "disconnectWebSocket: WebSocket切断")
            }
        } catch (e: Exception) {
            Log.e(TAG, "disconnectWebSocket: 切断エラー", e)
        }
    }

//    override fun onDestroy() {
//        super.onDestroy()
//        disconnectWebSocket()
//        stopPlayback()
//    }　

    inner class MyObserver : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            super.onResume(owner)
            isForeground = true
            connectWebSocket()
        }

        override fun onPause(owner: LifecycleOwner) {
            super.onPause(owner)
            isForeground = false
            disconnectWebSocket()
            stopPlayback()
        }
    }
    companion object {
        private const val TAG = "MainActivity"
    }
}
