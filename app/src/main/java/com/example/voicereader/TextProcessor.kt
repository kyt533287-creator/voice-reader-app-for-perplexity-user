package com.example.voicereader

object TextProcessor {
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

    // ★新規追加：Perplexity専用の整形処理
    fun cleanPerplexityText(text: String): String {
        return text
            // 1. 引用番号を削除 [12] → 削除
            .replace(Regex("\\[\\d+\\]"), "")
            // 2. マークダウンの強調記号を削除 **重要語** → 重要語
            .replace(Regex("\\*\\*([^*]+?)\\*\\*"), "$1")
            // 3. URLを削除（http:// または https:// で始まる文字列）
            .replace(Regex("https?://\\S+"), "")
            // 4. 連続する空白を1つに
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun applyDictionary(text: String, dictionary: List<DictionaryEntry>): String {
        var processedText = text

        // 有効な辞書エントリだけ処理
        dictionary.filter { it.isEnabled }.forEach { entry ->
            if (processedText.contains(entry.original, ignoreCase = true)) {
                processedText = processedText.replace(
                    entry.original,
                    entry.replacement,
                    ignoreCase = true
                )
            }
        }

        return processedText
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
