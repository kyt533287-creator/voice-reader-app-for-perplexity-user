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
        return text.split(Regex("[。！?\\n]")).filter { it.isNotBlank() }
    }
}
