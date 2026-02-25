// TtsService.kt
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
            // 初期言語は日本語（後で文ごとに切り替える）
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

    // ★新機能：テキストが主に英語かどうかを判定する
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
        // なぜ20文字以上：「LLM」などの短い略語は日本語読みでOKという要望のため
        return if (latinChars >= 20 && latinChars > japaneseChars) {
            Locale.US  // 英語
        } else {
            Locale.JAPAN  // 日本語
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
            
            // ★修正点：文ごとに言語を判定して切り替える
            val detectedLocale = detectLanguage(text)
            tts.language = detectedLocale
            
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
