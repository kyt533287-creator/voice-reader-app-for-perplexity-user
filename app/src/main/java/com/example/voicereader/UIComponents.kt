package com.example.voicereader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
