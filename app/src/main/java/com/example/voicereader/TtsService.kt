// TtsService.kt
package com.example.voicereader

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsService : Service(), TextToSpeech.OnInitListener {

    // ★通知ボタンのアクション名（PendingIntent でやり取りする識別子）
    companion object {
        const val ACTION_PREV       = "com.example.voicereader.PREV"
        const val ACTION_PLAY_PAUSE = "com.example.voicereader.PLAY_PAUSE"
        const val ACTION_NEXT       = "com.example.voicereader.NEXT"
        const val ACTION_STOP       = "com.example.voicereader.STOP"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID     = "tts_playback"
    }

    private lateinit var tts: TextToSpeech
    private val binder = TtsBinder()
    private var listener: TtsListener? = null

    // 再生用データ
    private var sentences: List<String> = emptyList()
    private var currentIndex = 0

    // 設定値
    private var currentRate = 1.0f
    private var currentPitch = 1.0f

    // ★追加：通知・MediaSession 管理用フィールド
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    // 通知の ▶/⏸ ボタンを切り替えるための再生状態フラグ
    private var isCurrentlyPlaying = false

    // ★オーディオフォーカス管理
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    // 電話など一時的なフォーカス喪失前に再生中だったか記憶するフラグ
    private var wasPlayingBeforeFocusLoss = false

    // ★フォーカス変化リスナー：電話・他アプリの割り込みを検知する
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 完全喪失（Spotifyなどが長時間フォーカスを取得）→ 停止・再開しない
                wasPlayingBeforeFocusLoss = false
                stop()
                listener?.onPaused()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 一時的喪失（電話・ナビ案内など）→ 一時停止して位置を記憶
                if (isCurrentlyPlaying) {
                    wasPlayingBeforeFocusLoss = true
                    tts.stop()
                    isCurrentlyPlaying = false
                    updateNotification(false)
                    updateMediaSessionState(false)
                    listener?.onPaused()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // フォーカス回復（電話終了など）→ 一時停止していた場合のみ再開
                if (wasPlayingBeforeFocusLoss && sentences.isNotEmpty()) {
                    wasPlayingBeforeFocusLoss = false
                    speakCurrentSentence()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)

        // 通知チャンネルを作成（Android 8.0+ 必須。何度呼んでも安全）
        createNotificationChannel()
        notificationManager = getSystemService(NotificationManager::class.java)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // MediaSession を初期化（ロック画面コントロール・ヘッドセットボタン対応）
        mediaSession = MediaSession(this, "BridgeTTSSession")
        mediaSession.setCallback(mediaSessionCallback)
        mediaSession.isActive = true
        updateMediaSessionState(false)
    }

    // ★MediaSession コールバック
    // ロック画面のボタンやヘッドセットのメディアキーからの操作を受け取る
    private val mediaSessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            if (!isCurrentlyPlaying && sentences.isNotEmpty()) {
                speakCurrentSentence()
            }
        }
        override fun onPause() {
            if (isCurrentlyPlaying) {
                tts.stop()
                isCurrentlyPlaying = false
                updateNotification(false)
                updateMediaSessionState(false)
                listener?.onPaused()
            }
        }
        override fun onSkipToPrevious() {
            if (sentences.isNotEmpty()) {
                currentIndex = maxOf(0, currentIndex - 1)
                speakCurrentSentence()
            }
        }
        override fun onSkipToNext() {
            if (sentences.isNotEmpty()) {
                currentIndex = minOf(sentences.size - 1, currentIndex + 1)
                speakCurrentSentence()
            }
        }
        override fun onStop() { stop() }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            // 初期言語は日本語（後で文ごとに切り替える）
            tts.language = Locale.JAPAN
            tts.setSpeechRate(currentRate)
            tts.setPitch(currentPitch)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // 何もしない
                }

                override fun onDone(utteranceId: String?) {
                    // ★外部から stop() が呼ばれていた場合はここで打ち切る
                    // （フォーカス喪失 → stop() → onDone() の順で来ても次文に進まない）
                    if (!isCurrentlyPlaying) return

                    // 1文読み終わったら次へ
                    currentIndex++
                    if (currentIndex < sentences.size) {
                        speakCurrentSentence()
                    } else {
                        // 全文終了 → UIに通知し、フォアグラウンドを解除して通知を削除
                        listener?.onComplete()
                        isCurrentlyPlaying = false
                        updateMediaSessionState(false)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    }
                }

                override fun onError(utteranceId: String?) {
                    listener?.onError("再生エラーが発生しました")
                }
            })
        }
    }

    // ★onStartCommand：通知ボタンの PendingIntent を受け取って処理する
    // startForegroundService() 経由で呼ばれた場合は 5 秒以内に startForeground() が必須
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action != null) {
            // アクション付き = 通知ボタンからの操作
            // 5 秒ルール違反を防ぐため、まず startForeground() を呼ぶ
            startForeground(NOTIFICATION_ID, buildNotification(isCurrentlyPlaying))

            when (action) {
                ACTION_PREV -> {
                    if (sentences.isNotEmpty()) {
                        currentIndex = maxOf(0, currentIndex - 1)
                        speakCurrentSentence()
                    }
                }
                ACTION_PLAY_PAUSE -> {
                    if (isCurrentlyPlaying) {
                        // 再生中 → 一時停止（currentIndex は保持したまま止まる）
                        tts.stop()
                        isCurrentlyPlaying = false
                        updateNotification(false)
                        updateMediaSessionState(false)
                        listener?.onPaused()
                    } else {
                        // 停止中 → 覚えていた位置から再開
                        if (sentences.isNotEmpty()) {
                            speakCurrentSentence()
                        }
                    }
                }
                ACTION_NEXT -> {
                    if (sentences.isNotEmpty()) {
                        currentIndex = minOf(sentences.size - 1, currentIndex + 1)
                        speakCurrentSentence()
                    }
                }
                ACTION_STOP -> stop()
            }
        }
        // START_NOT_STICKY：意図しない再起動をしない（音楽アプリと違い常駐不要）
        return START_NOT_STICKY
    }

    // ★通知チャンネルを作成（Android 8.0+ 必須）
    // IMPORTANCE_LOW = 通知音・バイブなし。メディア操作ボタンには十分
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "BridgeTTS playback controls"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    // ★通知を組み立てる
    private fun buildNotification(isPlaying: Boolean): Notification {
        // ボタンごとに PendingIntent を作るヘルパー
        fun makePendingIntent(action: String, requestCode: Int) =
            PendingIntent.getService(
                this, requestCode,
                Intent(this, TtsService::class.java).apply { this.action = action },
                // FLAG_IMMUTABLE = Android 12+ 必須
                // FLAG_UPDATE_CURRENT = 同じ requestCode の既存 Intent を更新
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val prevIntent      = makePendingIntent(ACTION_PREV, 0)
        val playPauseIntent = makePendingIntent(ACTION_PLAY_PAUSE, 1)
        val nextIntent      = makePendingIntent(ACTION_NEXT, 2)
        val stopIntent      = makePendingIntent(ACTION_STOP, 3)

        // 通知タップ → アプリを前面に戻す
        val openAppIntent = PendingIntent.getActivity(
            this, 4,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 現在読んでいる文を通知に表示（60文字を超えたら省略）
        val currentText = if (currentIndex in sentences.indices) sentences[currentIndex] else ""
        val subtitle = if (currentText.length > 60) currentText.take(60) + "…" else currentText

        // アイコン生成ヘルパー（Android 標準の drawable を使用。追加リソース不要）
        fun icon(res: Int) = android.graphics.drawable.Icon.createWithResource(this, res)

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("BridgeTTS")
            .setContentText(subtitle)
            .setContentIntent(openAppIntent)
            .setOngoing(isPlaying)       // 再生中は通知をスワイプで消せない
            .setDeleteIntent(stopIntent) // 一時停止中に通知をスワイプしたら STOP
            .addAction(Notification.Action.Builder(
                icon(android.R.drawable.ic_media_rew), "Prev", prevIntent).build())
            .addAction(Notification.Action.Builder(
                icon(if (isPlaying) android.R.drawable.ic_media_pause
                     else android.R.drawable.ic_media_play),
                if (isPlaying) "Pause" else "Play", playPauseIntent).build())
            .addAction(Notification.Action.Builder(
                icon(android.R.drawable.ic_media_ff), "Next", nextIntent).build())
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    // 0=Prev, 1=Play/Pause, 2=Next をコンパクト表示（折りたたみ状態）に含める
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    // ★通知の ▶/⏸ を更新する（再生状態が変わるたびに呼ぶ）
    private fun updateNotification(isPlaying: Boolean) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(isPlaying))
    }

    // ★MediaSession の PlaybackState を更新する（ロック画面への反映）
    private fun updateMediaSessionState(isPlaying: Boolean) {
        val state = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_STOP
            )
            .setState(
                if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                currentIndex.toLong(),
                currentRate
            )
            .build()
        mediaSession.setPlaybackState(state)
    }

    // ★新機能：テキストが主に英語かどうかを判定する（変更なし）
    // 理由：英文は英語TTS、日本文は日本語TTSで読み分けるため
    private fun detectLanguage(text: String): Locale {
        // アルファベット（a-z, A-Z）の文字数をカウント
        val latinChars = text.count { it in 'a'..'z' || it in 'A'..'Z' }
        // 日本語文字（ひらがな、カタカナ、漢字）の文字数をカウント
        val japaneseChars = text.count {
            it in '\u3040'..'\u309F' || // ひらがな
            it in '\u30A0'..'\u30FF' || // カタカナ
            it in '\u4E00'..'\u9FFF'    // 漢字
        }

        // 判定ロジック：
        // 1. 英字が20文字以上 かつ 日本語より多い → 英語
        // 2. それ以外 → 日本語
        return if (latinChars >= 20 && latinChars > japaneseChars) {
            Locale.US  // 英語
        } else {
            Locale.JAPAN  // 日本語
        }
    }

    // ★オーディオフォーカスを要求する（再生開始前に必ず呼ぶ）
    // 戻り値：true = フォーカス取得成功、false = 取得失敗（別アプリが占有中）
    private fun requestAudioFocus(): Boolean {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        audioFocusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    // ★オーディオフォーカスを解放する（停止・終了時に呼ぶ）
    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // 文のリストを受け取って、指定位置から再生する
    fun speakList(list: List<String>, startIndex: Int) {
        // フォーカスを要求（取得できなければ再生しない）
        if (!requestAudioFocus()) return

        // フォアグラウンドサービスを開始して通知を表示する
        isCurrentlyPlaying = true
        startForeground(NOTIFICATION_ID, buildNotification(true))
        updateMediaSessionState(true)

        sentences = list
        currentIndex = startIndex.coerceIn(0, list.size - 1)
        speakCurrentSentence()
    }

    // 内部処理：現在のインデックスの文を話す（ロジック変更なし + 通知フック追加）
    private fun speakCurrentSentence() {
        if (currentIndex in sentences.indices) {
            val text = sentences[currentIndex]

            // 文ごとに言語を判定して切り替える（既存ロジック変更なし）
            val detectedLocale = detectLanguage(text)
            tts.language = detectedLocale

            // UIに通知（ここでハイライト位置が決まる）
            listener?.onProgress(currentIndex, sentences.size)

            // TTSエンジンに設定を適用
            tts.setSpeechRate(currentRate)
            tts.setPitch(currentPitch)

            // IDにインデックスを紐づけて再生
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ID_$currentIndex")

            // 通知・MediaSession の状態を再生中に更新
            isCurrentlyPlaying = true
            updateNotification(true)
            updateMediaSessionState(true)
        }
    }

    // 外部から呼ばれる：停止
    fun stop() {
        if (::tts.isInitialized) {
            tts.stop()
        }
        wasPlayingBeforeFocusLoss = false  // 停止したので復帰不要
        abandonAudioFocus()                // フォーカスを解放
        isCurrentlyPlaying = false
        updateMediaSessionState(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // 外部から呼ばれる：速度設定（変更なし）
    fun setSpeechRate(rate: Float) {
        currentRate = rate
        if (::tts.isInitialized) {
            tts.setSpeechRate(rate)
        }
    }

    // 外部から呼ばれる：ピッチ設定（変更なし）
    fun setPitch(pitch: Float) {
        currentPitch = pitch
        if (::tts.isInitialized) {
            tts.setPitch(pitch)
        }
    }

    // リスナー登録
    fun setListener(listener: TtsListener) {
        this.listener = listener
    }

    // バインダー
    inner class TtsBinder : Binder() {
        fun getService(): TtsService = this@TtsService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        // オーディオフォーカスを解放（他のアプリが音声を再開できるよう）
        abandonAudioFocus()
        // MediaSession を解放（リソースリーク防止）
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    // インターフェース（onPaused を追加）
    interface TtsListener {
        fun onProgress(current: Int, total: Int)
        fun onComplete()
        fun onError(message: String)
        // 通知の PAUSE ボタン押下時に呼ばれる
        // onComplete() と違い currentSentenceIndex をリセットしない
        fun onPaused() {}
    }
}
