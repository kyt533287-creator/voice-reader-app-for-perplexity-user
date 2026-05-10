package com.example.voicereader

import java.util.Locale

object TextProcessor {

    // ★5言語対応：テキストから使用言語を推定する
    // 優先順：① ひらがな/カタカナ → JA  ② CJK文字のみ → ZH
    //         ③ 独仏特殊文字 → DE/FR  ④ ストップワード多数決 → EN/DE/FR
    //         ⑤ すべて曖昧 → fallback（デフォルトはLocale.US）
    fun detectLanguage(text: String, fallback: Locale = Locale.US): Locale {
        // ① ひらがな・カタカナがあれば日本語（ほぼ確実）
        val hasHiragana = text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        if (hasHiragana) return Locale.JAPAN

        // ② CJK漢字があり、かつひらがなゼロ → 中国語
        val hasCjk = text.any { it in '\u4E00'..'\u9FFF' || it in '\u3400'..'\u4DBF' }
        if (hasCjk) return Locale.CHINA

        // ③ ストップワード多数決を最優先（EN/DE/FR それぞれのよく使う単語を点数化）
        // ★修正理由：固有名詞の é（Évora, café等）でフランス語誤判定を防ぐため
        //             英語テキストなら "the","and" 等が多数ヒットするので特殊文字より優先すべき
        val lower = text.lowercase()
        var scoreEn = 0; var scoreDe = 0; var scoreFr = 0
        // ★英語の超頻出単語を幅広く登録（"was","a","of" 等を追加）
        // 理由：Auto-da-fé, Évora など固有名詞含む文に "the","is" が無くても
        //      "was","a","of","in" 等でスコアが立ち、フランス語誤判定を防ぐ
        listOf("the","is","are","and","that","this","with","for","not","from","have",
               "was","were","had","has","been","it","of","in","a","an","at","by",
               "or","but","be","all","more","than","their","they","he","she","we").forEach {
            if (Regex("\\b$it\\b").containsMatchIn(lower)) scoreEn++
        }
        listOf("der","die","das","und","ist","nicht","mit","für","von","ein","eine","wird").forEach {
            if (Regex("\\b$it\\b").containsMatchIn(lower)) scoreDe++
        }
        listOf("le","la","les","est","une","des","avec","pour","dans","sur","qui","que").forEach {
            if (Regex("\\b$it\\b").containsMatchIn(lower)) scoreFr++
        }

        val maxScore = maxOf(scoreEn, scoreDe, scoreFr)
        if (maxScore > 0) {
            return when {
                scoreEn >= scoreDe && scoreEn >= scoreFr -> Locale.US
                scoreDe >= scoreFr -> Locale.GERMANY
                else -> Locale.FRANCE
            }
        }

        // ④ ストップワードが0点のとき（固有名詞だけのテキスト等）のみ特殊文字で判定
        val hasGerman = text.any { it in "äöüßÄÖÜ" }
        val hasFrench = text.any { it in "àâçèéêëîïôùûœæÀÂÇÈÉÊËÎÏÔÙÛŒÆ" } ||
                        Regex("\\b[ld]'", RegexOption.IGNORE_CASE).containsMatchIn(text)

        if (hasGerman && !hasFrench) return Locale.GERMANY
        if (hasFrench && !hasGerman) return Locale.FRANCE

        // ⑤ 何も判定できなければ fallback にフォールバック
        return fallback
    }
    // PDF専用の改行処理
    fun cleanPdfText(text: String): String {
        // 句読点の後の改行は保持、それ以外の改行は削除
        return text
            .replace(Regex("(?<![。！?\\n])\\n(?![。!?\\n])"), "") // 文中の改行を削除
            .replace(Regex("\\n{3,}"), "\n\n") // 3個以上連続する改行は2個に
    }

    // プロンプト除去
    fun removePrompts(text: String, prompts: List<String>): String {
        var cleanedText = text
        prompts.forEach { prompt ->
            if (prompt.isNotBlank() && cleanedText.contains(prompt)) {
                cleanedText = cleanedText.replace(prompt, "")
            }
        }
        return cleanedText.trim()
    }

    // ★Perplexity・AIテキスト向け整形処理
    // cleanPerplexityText = マークダウン記法の「ノイズ文字」を読み上げ前に除去する掃除機
    // ※ applyDictionary() の前に実行される。辞書と役割分担：
    //   cleanPerplexityText → マークダウン構文の除去（構造ノイズ）
    //   applyDictionary     → ユーザー定義の読み替え・削除（内容ノイズ）
    fun cleanPerplexityText(text: String): String {
        return text
            // 0. Markdownリンク [表示テキスト](URL) を丸ごと削除（引用リンクはすべてこの形式）
            // 例：[news.yahoo.co](https://...) → 削除。URL部分だけ消すと [news.yahoo.co]( が残り読まれてしまう
            .replace(Regex("\\[[^\\]]*\\]\\(https?://[^)]*\\)"), "")
            // 1. 引用番号を削除 [12] → 削除
            .replace(Regex("\\[\\d+\\]"), "")
            // 2. アスタリスクをすべて削除（* ** *** 何個でも。箇条書き・強調・太字イタリックに使われる）
            .replace(Regex("\\*+"), "")
            // 3. URLを削除（http:// または https:// で始まる文字列）← ステップ0で拾えなかった裸のURLの保険
            .replace(Regex("https?://\\S+"), "")
            // 4. 見出し記号を削除（行頭の # ## ### のみ。#hashtag や URL の # は巻き込まない）
            .replace(Regex("(?m)^#{1,3}\\s*"), "")
            // 5. 水平線を削除（--- や === が3文字以上続く行全体）
            .replace(Regex("(?m)^[-=]{3,}\\s*$"), "")
            // 6. テーブルのパイプ記号を半角スペースに置換（| → 空白。表の内容は読み上げる）
            .replace("|", " ")
            // 7. コードブロックフェンスを削除（```kotlin など言語ヒントつきも対応。内容は残す）
            .replace(Regex("```[a-zA-Z]*"), "")
            // 8. 残りのバッククォートを削除（インラインコード `someCode` のマーカーのみ消去。内容は残す）
            .replace("`", "")
            // 9. 連続する空白を1つに
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyDictionary(text: String, dictionary: List<DictionaryEntry>): String {
        var processedText = text

        // 有効な辞書エントリだけ処理
        dictionary.filter { it.isEnabled }.forEach { entry ->
            if (entry.original.contains("\$\$\$")) {
                if (entry.original.endsWith("\$\$\$")) {
                    // ★末尾ワイルドカード：prefix の後ろにある空白以外の文字列をまるごとマッチ
                    // 用途：URL削除。例 "https://$$$" → "https://example.com/path?q=1" を丸ごと削除
                    val prefix = Regex.escape(entry.original.dropLast(3))
                    val pattern = Regex("$prefix\\S*", RegexOption.IGNORE_CASE)
                    processedText = pattern.replace(processedText, entry.replacement)
                } else {
                    // ★中間ワイルドカード：$$$ の前後を固定し、間の内容（空白含む）をまるごとマッチ
                    // 用途：括弧内削除。例 "【$$$】" → "【見出し】" "【注釈テキスト】" を丸ごと削除
                    // *? は最小マッチ（欲張らない）→ 隣の括弧を巻き込まない
                    val parts = entry.original.split("\$\$\$")
                    val patternStr = parts.joinToString("[\\s\\S]*?") { Regex.escape(it) }
                    val pattern = Regex(patternStr, RegexOption.IGNORE_CASE)
                    processedText = pattern.replace(processedText, entry.replacement)
                }
            } else if (processedText.contains(entry.original, ignoreCase = true)) {
                // 通常の文字列置換（従来通り）
                processedText = processedText.replace(
                    entry.original,
                    entry.replacement,
                    ignoreCase = true
                )
            }
        }

        return processedText
    }

    // ★TTS速度の非線形補正
    // Android TTSの「3倍速」は実際には約2.36倍にしかならない（実機計測値から導出）
    // nominal（設定値）→ effectiveSpeed（実効倍率）の変換表：
    //   1.0x → 1.00x, 2.0x → 1.78x, 3.0x → 2.36x
    fun effectiveSpeed(nominal: Float): Float = when {
        nominal <= 1.0f -> nominal
        nominal <= 2.0f -> 1.0f + (nominal - 1.0f) * 0.78f
        else            -> 1.78f + (nominal - 2.0f) * 0.58f
    }

    // 文分割
    fun splitSentences(text: String): List<String> {
        // Android TTS の上限は約4000文字。
        // 300文字で区切るとスピード変更が最大12秒以内に反映される（1000文字だと最大40秒）
        val MAX_CHARS = 300

        return text
            // 句点・感嘆符・疑問符（全角・半角両対応）・改行で分割
            .split(Regex("[。！!?？\\n]"))
            .filter { it.isNotBlank() }
            .flatMap { sentence ->
                if (sentence.length <= MAX_CHARS) {
                    // 上限以下はそのまま
                    listOf(sentence)
                } else {
                    // 上限超え：スペース・読点を探して自然な位置で分割（TTS停止防止）
                    val chunks = mutableListOf<String>()
                    var remaining = sentence
                    while (remaining.length > MAX_CHARS) {
                        // 1000文字以内で最後のスペースか読点を探す
                        val cutPoint = remaining
                            .substring(0, MAX_CHARS)
                            .lastIndexOfAny(charArrayOf(' ', '、', ','))
                            .takeIf { it > MAX_CHARS / 2 } // あまりに前すぎたら無視
                            ?: MAX_CHARS                   // 見つからなければ強制カット
                        chunks.add(remaining.substring(0, cutPoint).trim())
                        remaining = remaining.substring(cutPoint).trim()
                    }
                    if (remaining.isNotBlank()) chunks.add(remaining)
                    chunks
                }
            }
    }
}
