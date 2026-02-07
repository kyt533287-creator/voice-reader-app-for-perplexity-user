package com.example.voicereader

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class TtsService : Service(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private val binder = TtsBinder()
    private var listener: TtsListener? = null

    // 再生用データ
    private var sentences: List<String> = emptyList()
    private var currentIndex = 0

    // 設定値
    private var currentRate = 1.0f
    private var currentPitch = 1.0f

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.JAPAN
            tts.setSpeechRate(currentRate)
            tts.setPitch(currentPitch)

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    // 何もしない
                }

                override fun onDone(utteranceId: String?) {
                    // 1文読み終わったら次へ
                    currentIndex++
                    if (currentIndex < sentences.size) {
                        speakCurrentSentence()
                    } else {
                        // 全文終了
                        listener?.onComplete()
                    }
                }

                override fun onError(utteranceId: String?) {
                    listener?.onError("再生エラーが発生しました")
                }
            })
        }
    }

    // 文のリストを受け取って、指定位置から再生する
    fun speakList(list: List<String>, startIndex: Int) {
        sentences = list
        currentIndex = startIndex.coerceIn(0, list.size - 1)
        speakCurrentSentence()
    }

    // 内部処理：現在のインデックスの文を話す
    private fun speakCurrentSentence() {
        if (currentIndex in sentences.indices) {
            val text = sentences[currentIndex]
            // UIに通知（ここでハイライト位置が決まる）
            listener?.onProgress(currentIndex, sentences.size)

            // TTSエンジンに設定を適用
            tts.setSpeechRate(currentRate)
            tts.setPitch(currentPitch)

            // IDにインデックスを紐づけて再生
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ID_$currentIndex")
        }
    }

    // 外部から呼ばれる：停止
    fun stop() {
        if (::tts.isInitialized) {
            tts.stop()
        }
    }

    // 外部から呼ばれる：速度設定
    fun setSpeechRate(rate: Float) {
        currentRate = rate
        if (::tts.isInitialized) {
            tts.setSpeechRate(rate)
        }
    }

    // 外部から呼ばれる：ピッチ設定
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
        super.onDestroy()
    }

    // インターフェース
    interface TtsListener {
        fun onProgress(current: Int, total: Int)
        fun onComplete()
        fun onError(message: String)
    }
}