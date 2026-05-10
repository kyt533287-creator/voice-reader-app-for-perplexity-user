package com.example.voicereader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

// ─────────────────────────────────────────────
// サブ画面共通カラーパレット
// 辞書リスト・辞書編集・プロンプトリスト・プロンプト編集の4画面で使用する
// ※ MainScreen は独自パレット（値が異なる）のため対象外
// ─────────────────────────────────────────────

data class AppColors(
    val bgColor: Color,
    val paperColor: Color,
    val primaryColor: Color,
    val textPrimary: Color,
    val textMuted: Color,
    val pinkColor: Color,
    val greenColor: Color,
    val gradient: Brush,
)

@Composable
fun subScreenColors(): AppColors {
    val isDark = isSystemInDarkTheme()
    return AppColors(
        bgColor      = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F4FF),
        paperColor   = if (isDark) Color(0xFF16213E) else Color(0xFFFFFFFF),
        primaryColor = Color(0xFF6366F1),
        textPrimary  = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B),
        textMuted    = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8),
        pinkColor    = if (isDark) Color(0xFFFF2E97) else Color(0xFFEC4899),
        greenColor   = if (isDark) Color(0xFF34D399) else Color(0xFF10B981),
        gradient     = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899))),
    )
}

// ─────────────────────────────────────────────
// 共通 YES/NO 確認ダイアログ（クレイモーフィズム）
//
// 使い方：
//   ConfirmDialog(
//       title = "Delete this entry?",
//       message = "This action cannot be undone.",
//       gradient = c.gradient,
//       paperColor = c.paperColor,
//       textPrimary = c.textPrimary,
//       textMuted = c.textMuted,
//       onYes = { /* 実行 */ },
//       onNo  = { /* 閉じる */ },
//   )
//
// ボタン配置ルール（全ダイアログ統一）：
//   YES（左・控えめ）= 危険な操作（削除・保存せずに離脱）
//   NO（右・グラデーション）= 安全な操作（キャンセル・留まる）
// ─────────────────────────────────────────────

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    yesLabel: String = "YES",
    noLabel: String = "NO",
    gradient: Brush,
    paperColor: Color,
    textPrimary: Color,
    textMuted: Color,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    Dialog(onDismissRequest = onNo) {
        Box(
            modifier = Modifier
                .shadow(16.dp, RoundedCornerShape(20.dp))
                .background(paperColor, RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textPrimary)
                Text(message, fontSize = 14.sp, color = textMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // YES（控えめ）：危険な操作を実行する
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(2.dp, RoundedCornerShape(12.dp))
                            .background(paperColor, RoundedCornerShape(12.dp))
                            .clickable { onYes() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(yesLabel, fontWeight = FontWeight.Bold, color = textMuted, fontSize = 14.sp)
                    }
                    // NO（グラデーション）：安全に留まる
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .shadow(4.dp, RoundedCornerShape(12.dp))
                            .background(gradient, RoundedCornerShape(12.dp))
                            .clickable { onNo() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(noLabel, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────
// 初回起動 利用規約・著作権同意ダイアログ
// キャンセル不可（Click-wrap形式）
// 機能：
//   - 日本語/英語 切り替えボタン（デフォルト：日本語）
//   - 最後までスクロールしないと同意ボタンが押せない
//   - SharedPreferences の "first_launch_agreed" フラグで表示済みを記録する
// ─────────────────────────────────────────────

@Composable
fun FirstLaunchConsentDialog(onAgree: () -> Unit) {
    val c = subScreenColors()
    val scrollState = rememberScrollState()

    // このダイアログ専用グラデーション（濃い黄色 → オレンジ）
    val consentGradient = Brush.horizontalGradient(listOf(Color(0xFFFFB300), Color(0xFFFF6200)))

    // 言語状態（日本語圏向けにデフォルトは日本語）
    var isJapanese by remember { mutableStateOf(true) }

    // 一度でも最後までスクロールしたら true（言語切替後もリセットしない）
    var hasScrolledToBottom by remember { mutableStateOf(false) }

    // スクロールが最下部に達したか監視（5px の誤差を許容）
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue > 0 && scrollState.value >= scrollState.maxValue - 5) {
            hasScrolledToBottom = true
        }
    }

    // 言語切替時：スクロールをトップに戻す（相手の言語も読んでもらうため）
    LaunchedEffect(isJapanese) {
        scrollState.scrollTo(0)
    }

    Dialog(onDismissRequest = {}) {  // 外タップ・バック操作で閉じない
        Box(
            modifier = Modifier
                .shadow(16.dp, RoundedCornerShape(10.dp))
                .background(c.paperColor, RoundedCornerShape(10.dp))
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                // タイトル行 ＋ 言語切替ボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isJapanese) "利用規約" else "Terms of Use",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = c.textPrimary
                    )
                    // 言語トグルボタン（①固定幅で等幅・②黄橙グラデ・③角丸4dp）
                    Row(
                        modifier = Modifier.width(140.dp),  // ① 合計幅を固定 → weight(1f)で2等分
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf(true to "日本語", false to "English").forEach { (jp, label) ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)             // ① 各ボタンを均等に分割
                                    .shadow(if (isJapanese == jp) 2.dp else 0.dp, RoundedCornerShape(4.dp))
                                    .background(
                                        if (isJapanese == jp) consentGradient   // ② 黄橙グラデ
                                        else Brush.horizontalGradient(listOf(c.bgColor, c.bgColor)),
                                        RoundedCornerShape(4.dp)                // ③ 角丸4dp
                                    )
                                    .clickable { isJapanese = jp }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    fontSize = 12.sp,
                                    color = if (isJapanese == jp) Color.White else c.textMuted,
                                    fontWeight = if (isJapanese == jp) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // スクロール可能な利用規約本文
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    val sections = if (isJapanese) listOf(
                        "1. 私的利用限定" to
                            "本アプリは個人の学習および非営利の私的利用を目的としています。",
                        "2. 著作権の遵守" to
                            "アプリに入力するテキストが第三者の著作権を侵害しないよう、ご自身で確認する責任があります。著作物を無断で複製・配布する目的での使用は禁止します。",
                        "3. 音声の公開配信禁止" to
                            "本アプリで生成した音声を、SNS・配信プラットフォーム・その他公開メディアにアップロードすることを禁止します。",
                        "4. 辞書機能について" to
                            "辞書・置換機能は、読み上げの精度向上のみを目的としています。他の目的での使用は禁止します。",
                        "5. 免責事項" to
                            "本アプリの使用によって生じたいかなる損害・法的問題・損失についても、開発者は一切の責任を負いません。"
                    ) else listOf(
                        "1. Personal Use Only" to
                            "This app is intended for personal learning and non-commercial private use only.",
                        "2. Copyright Compliance" to
                            "You are responsible for ensuring that any text you enter does not infringe third-party copyrights. Do not use this app to reproduce or distribute copyrighted materials without permission.",
                        "3. No Public Distribution of Audio" to
                            "Audio generated by this app must not be uploaded to SNS, streaming platforms, or any public media.",
                        "4. Dictionary Feature" to
                            "The dictionary/substitution feature is intended solely to improve text-to-speech playback accuracy. It must not be used for any other purpose.",
                        "5. Disclaimer" to
                            "The developer assumes no responsibility for any damages, legal issues, or losses arising from the use of this app."
                    )
                    sections.forEach { (heading, body) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                heading,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = c.textPrimary
                            )
                            Text(
                                body,
                                fontSize = 13.sp,
                                color = c.textMuted,
                                lineHeight = 19.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    // 同意の意思確認文（Click-wrap に必要な一文）
                    Text(
                        if (isJapanese)
                            "「同意する」をタップすることで、上記の利用規約を読み、同意したことを確認します。"
                        else
                            "By tapping \"I Agree\", you confirm that you have read and accept these terms.",
                        fontSize = 12.sp,
                        color = c.textMuted,
                        fontWeight = FontWeight.Medium
                    )
                }

                // スクロールヒント（最下部に達するまで表示）
                if (!hasScrolledToBottom) {
                    Text(
                        if (isJapanese) "↓ 最後までスクロールしてください" else "↓ Please scroll to the bottom",
                        fontSize = 11.sp,
                        color = c.pinkColor,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 同意ボタン（②黄橙グラデ・③角丸6dp・スクロール完了前はグレーアウト）
                Box(
                    modifier = if (hasScrolledToBottom) {
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .shadow(4.dp, RoundedCornerShape(6.dp))
                            .background(consentGradient, RoundedCornerShape(6.dp))  // ②③
                            .clickable { onAgree() }
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .background(c.textMuted.copy(alpha = 0.3f), RoundedCornerShape(6.dp))  // ③
                    },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (isJapanese) "同意する" else "I Agree",
                        fontWeight = FontWeight.Bold,
                        color = if (hasScrolledToBottom) Color.White else c.textMuted,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}
