package com.example.voicereader

import android.Manifest
import android.app.Activity
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import android.content.pm.ActivityInfo
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition  // ★無限ループアニメーションの入れ物
import androidx.compose.animation.core.animateFloat                // ★InfiniteTransitionのFloat拡張関数（明示的インポート必須）
import androidx.compose.animation.core.infiniteRepeatable          // ★繰り返し設定
import androidx.compose.animation.core.RepeatMode                  // ★往復(Reverse)か一方向(Restart)か
import androidx.compose.animation.core.FastOutSlowInEasing         // ★加速→減速のイージング
import androidx.compose.ui.draw.scale                              // ★拡縮モディファイア
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip                         // ★clip()：リップルを丸にクリップするために必要
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.content.res.Configuration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.voicereader.ui.theme.VoiceReaderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.Locale
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Assistant
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.window.Dialog                     // カスタムダイアログ用
import androidx.compose.animation.AnimatedContent           // 画面遷移アニメーション用
import androidx.compose.animation.slideInHorizontally       // 右/左からスライドイン
import androidx.compose.animation.slideOutHorizontally      // 右/左へスライドアウト
import androidx.compose.animation.togetherWith              // 入りと出を組み合わせる演算子
import androidx.compose.foundation.isSystemInDarkTheme     // ダークモード検知
import androidx.compose.ui.graphics.Brush                  // グラデーション描画
import androidx.compose.ui.geometry.Offset                 // グラデーションの方向指定
import androidx.compose.ui.draw.shadow                     // クレイシャドウ
import androidx.compose.material3.SheetValue               // ★ボトムシートの展開状態判定用
import androidx.compose.foundation.interaction.MutableInteractionSource  // ★タップエフェクト（リップル）除去用
import androidx.compose.foundation.interaction.collectIsPressedAsState   // ★ボタン押下状態の検知用
import androidx.compose.ui.text.TextStyle                  // テキストフィールドのスタイル
import androidx.compose.ui.text.style.TextAlign            // テキスト右寄せ等
import com.example.voicereader.BuildConfig

// 画面定義
enum class Screen {
    Main,
    PromptList,
    PromptEdit,
    DictionaryList,
    DictionaryEdit,

}

// プロンプトデータモデル
data class PromptItem(
    val title: String,
    val content: String
)
data class DictionaryEntry(
    val original: String,    // 元の単語（例: "URL"）
    val replacement: String, // 置き換え後（例: ""）空なら削除
    val isEnabled: Boolean = true // ON/OFFスイッチ
)
class MainActivity : ComponentActivity() {

    private var ttsService: TtsService? = null
    private var isBound = false

    // インタースティシャル広告
    private var interstitialAd: InterstitialAd? = null
    private var adPlayCount = 0          // 再生終了カウンター
    private val AD_INTERVAL = 3          // 何回に1回広告を出すか

    // 権限リクエスト
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // TTSサービス接続
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TtsService.TtsBinder
            ttsService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 通知権限（Android 13+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // AdMob 初期化（広告ロードより前に必須）
        MobileAds.initialize(this)
        loadInterstitialAd()

        // PDFBox初期化
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Service開始
        // startService() が必要な理由：
        // bindService() だけでは Service が「開始済み」状態にならないため
        // speakList() 内で startForeground() を呼べない（Android 8.0 制約）
        Intent(this, TtsService::class.java).also { intent ->
            startService(intent)                                   // 起動（通知はまだ出ない）
            bindService(intent, connection, Context.BIND_AUTO_CREATE) // UIとの接続
        }

        setContent {
            VoiceReaderTheme {
                AppNavigation()
            }
        }
    }

    @Composable
    fun AppNavigation() {
        // 現在の画面管理
        var currentScreen by remember { mutableStateOf(Screen.Main) }

        // メイン画面の状態
        var mainText by remember { mutableStateOf("") }
        var sentences by remember { mutableStateOf<List<String>>(emptyList()) }

        // 編集対象のプロンプトインデックス (-1は新規)
        var editingPromptIndex by remember { mutableIntStateOf(-1) }

        // ★辞書用の状態変数を追加（プロンプトリストの下）
        var editingDictionaryIndex by remember { mutableIntStateOf(-1) }

        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences("prompts_prefs", Context.MODE_PRIVATE) }


        // データ読み込み関数
        fun loadPrompts(): MutableList<PromptItem> {
            val list = mutableListOf<PromptItem>()
            val count = prefs.getInt("prompt_count", 0)
            for (i in 0 until count) {
                val title = prefs.getString("prompt_title_$i", "無題") ?: "無題"
                val content = prefs.getString("prompt_content_$i", "") ?: ""
                list.add(PromptItem(title, content))
            }
            return list
        }

        // ★辞書読み込み関数を追加
        fun loadDictionary(): MutableList<DictionaryEntry> {
            val list = mutableListOf<DictionaryEntry>()
            val count = prefs.getInt("dictionary_count", 0)
            for (i in 0 until count) {
                val original = prefs.getString("dict_original_$i", "") ?: ""
                val replacement = prefs.getString("dict_replacement_$i", "") ?: ""
                val isEnabled = prefs.getBoolean("dict_enabled_$i", true)
                if (original.isNotEmpty()) {
                    list.add(DictionaryEntry(original, replacement, isEnabled))
                }
            }
            return list
        }

        // プロンプトリスト初期化
        val promptList = remember { mutableStateListOf<PromptItem>().apply { addAll(loadPrompts()) } }

        // ★辞書リスト初期化（新規追加）
        val dictionaryList = remember { mutableStateListOf<DictionaryEntry>().apply { addAll(loadDictionary()) } }

        // データ保存関数
        fun savePrompts() {
            val editor = prefs.edit()
            editor.putInt("prompt_count", promptList.size)
            promptList.forEachIndexed { index, item ->
                editor.putString("prompt_title_$index", item.title)
                editor.putString("prompt_content_$index", item.content)
            }
            editor.apply()
        }

        // ★辞書保存関数（新規追加）
        fun saveDictionary() {
            val editor = prefs.edit()
            editor.putInt("dictionary_count", dictionaryList.size)
            dictionaryList.forEachIndexed { index, entry ->
                editor.putString("dict_original_$index", entry.original)
                editor.putString("dict_replacement_$index", entry.replacement)
                editor.putBoolean("dict_enabled_$index", entry.isEnabled)
            }
            editor.apply()
        }

        fun updateMainText(newText: String) {
            // プロンプトリストから文字列リストを作成
            val promptContents = promptList.map { it.content }

            // ★処理順序が重要：プロンプト除去 → Perplexity整形 → 文分割
            val cleaned = TextProcessor.removePrompts(newText, promptContents)
            // ★表示用テキスト：辞書を適用しない（画面には元の単語をそのまま表示）
            val perplexityCleaned = TextProcessor.cleanPerplexityText(cleaned)
            mainText = perplexityCleaned
            // ★TTS用テキスト：辞書を適用（読み上げ時だけ単語を変換）
            val dictionaryApplied = TextProcessor.applyDictionary(perplexityCleaned, dictionaryList)
            sentences = TextProcessor.splitSentences(dictionaryApplied)
        }

        // ★画面の「深さ」を定義（進む方向 vs 戻る方向を判定するために使う）
        // 例：Main(0) → PromptList(1) は前進 → 右からスライドイン
        //     PromptList(1) → Main(0) は後退 → 左からスライドイン
        val screenDepth = mapOf(
            Screen.Main to 0,
            Screen.PromptList to 1,
            Screen.DictionaryList to 1,
            Screen.PromptEdit to 2,
            Screen.DictionaryEdit to 2
        )

        // ★AnimatedContent：currentScreenが変わるたびにスライドアニメーションを実行
        AnimatedContent(
            modifier = Modifier.fillMaxSize(),  // ★スライドが全画面で動くように必須
            targetState = currentScreen,
            transitionSpec = {
                val forward = (screenDepth[targetState] ?: 0) >= (screenDepth[initialState] ?: 0)
                if (forward) {
                    // 前進（深い画面へ）：右からスライドイン / 左へスライドアウト
                    slideInHorizontally(animationSpec = tween(300)) { it } togetherWith
                    slideOutHorizontally(animationSpec = tween(300)) { -it }
                } else {
                    // 後退（浅い画面へ）：左からスライドイン / 右へスライドアウト
                    slideInHorizontally(animationSpec = tween(300)) { -it } togetherWith
                    slideOutHorizontally(animationSpec = tween(300)) { it }
                }
            },
            label = "screenTransition"
        ) { screen ->
            when (screen) {
                Screen.Main -> {
                    MainScreen(
                        text = mainText,
                        sentences = sentences,
                        onTextChange = { mainText = it },
                        onNavigateToPrompts = { currentScreen = Screen.PromptList },
                        onNavigateToDictionary = { currentScreen = Screen.DictionaryList },
                        onUpdateText = { updateMainText(it) }
                    )
                }
                Screen.PromptList -> {
                    PromptListScreen(
                        prompts = promptList,
                        onNavigateBack = { currentScreen = Screen.Main },
                        onEditPrompt = { index: Int ->
                            editingPromptIndex = index
                            currentScreen = Screen.PromptEdit
                        },
                        onCreatePrompt = {
                            editingPromptIndex = -1
                            currentScreen = Screen.PromptEdit
                        },
                        onDeletePrompt = { index: Int ->
                            promptList.removeAt(index)
                            savePrompts()
                        },
                        onSelectPrompt = { prompt: PromptItem ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Prompt", prompt.content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Prompt copied", Toast.LENGTH_SHORT).show()
                            currentScreen = Screen.Main
                        }
                    )
                }
                Screen.PromptEdit -> {
                    PromptEditScreen(
                        initialPrompt = if (editingPromptIndex >= 0) promptList[editingPromptIndex] else null,
                        onSave = { title, content ->
                            if (editingPromptIndex >= 0) {
                                promptList[editingPromptIndex] = PromptItem(title, content)
                            } else {
                                promptList.add(PromptItem(title, content))
                            }
                            savePrompts()
                            currentScreen = Screen.PromptList
                        },
                        onCancel = { currentScreen = Screen.PromptList }
                    )
                }
                Screen.DictionaryList -> {
                    DictionaryListScreen(
                        dictionary = dictionaryList,
                        onNavigateBack = { currentScreen = Screen.Main },
                        onEditEntry = { index ->
                            editingDictionaryIndex = index
                            currentScreen = Screen.DictionaryEdit
                        },
                        onCreateEntry = {
                            editingDictionaryIndex = -1
                            currentScreen = Screen.DictionaryEdit
                        },
                        onDeleteEntry = { index ->
                            dictionaryList.removeAt(index)
                            saveDictionary()
                        },
                        onToggleEntry = { index ->
                            val entry = dictionaryList[index]
                            dictionaryList[index] = entry.copy(isEnabled = !entry.isEnabled)
                            saveDictionary()
                        }
                    )
                }
                Screen.DictionaryEdit -> {
                    DictionaryEditScreen(
                        initialEntry = if (editingDictionaryIndex >= 0) dictionaryList[editingDictionaryIndex] else null,
                        onSave = { original, replacement, isEnabled ->
                            if (editingDictionaryIndex >= 0) {
                                dictionaryList[editingDictionaryIndex] = DictionaryEntry(original, replacement, isEnabled)
                            } else {
                                dictionaryList.add(DictionaryEntry(original, replacement, isEnabled))
                            }
                            saveDictionary()
                            currentScreen = Screen.DictionaryList
                        },
                        onCancel = { currentScreen = Screen.DictionaryList }
                    )
                }
            }
        }
    }



    @Composable
    fun DictionaryListScreen(
        dictionary: List<DictionaryEntry>,
        onNavigateBack: () -> Unit,
        onEditEntry: (Int) -> Unit,
        onCreateEntry: () -> Unit,
        onDeleteEntry: (Int) -> Unit,
        onToggleEntry: (Int) -> Unit
    ) {
        BackHandler { onNavigateBack() }

        // ★この画面の間だけ縦固定。出たら回転を元に戻す
        val activity = LocalContext.current as? Activity
        DisposableEffect(Unit) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // ★削除確認ダイアログ用状態
        var showDeleteDialog by remember { mutableStateOf(false) }
        var deleteTargetIndex by remember { mutableIntStateOf(-1) }

        // ★クレイモーフィズム カラーパレット（再生画面と統一）
        val isDark       = isSystemInDarkTheme()
        val bgColor      = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F4FF)
        val paperColor   = if (isDark) Color(0xFF16213E) else Color(0xFFFFFFFF)
        val primaryColor = Color(0xFF6366F1)
        val textPrimary  = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
        val textMuted    = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        val pinkColor    = if (isDark) Color(0xFFFF2E97) else Color(0xFFEC4899)
        val greenColor   = if (isDark) Color(0xFF34D399) else Color(0xFF10B981)
        val gradient     = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899)))

        // ★削除確認ダイアログ（クレイモーフィズム）
        if (showDeleteDialog) {
            Dialog(onDismissRequest = { showDeleteDialog = false }) {
                Box(
                    modifier = Modifier
                        .shadow(16.dp, RoundedCornerShape(20.dp))
                        .background(paperColor, RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Delete this entry?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textPrimary)
                        Text("This action cannot be undone.", fontSize = 14.sp, color = textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(2.dp, RoundedCornerShape(12.dp))
                                .background(paperColor, RoundedCornerShape(12.dp))
                                .clickable { showDeleteDialog = false; onDeleteEntry(deleteTargetIndex) },
                                contentAlignment = Alignment.Center) {
                                Text("YES", fontWeight = FontWeight.Bold, color = textMuted, fontSize = 14.sp)
                            }
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(4.dp, RoundedCornerShape(12.dp))
                                .background(gradient, RoundedCornerShape(12.dp))
                                .clickable { showDeleteDialog = false },
                                contentAlignment = Alignment.Center) {
                                Text("NO", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // ★クレイモーフィズム レイアウト
        Column(modifier = Modifier.fillMaxSize().background(bgColor).statusBarsPadding()) {
            // ヘッダー
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(48.dp)
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(paperColor, RoundedCornerShape(16.dp))
                    .clickable { onNavigateBack() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = primaryColor, modifier = Modifier.size(22.dp))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Dict", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                    Text(".", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = primaryColor)
                }
                Box(modifier = Modifier.size(48.dp)
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(gradient, RoundedCornerShape(16.dp))
                    .clickable { onCreateEntry() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, "Add", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }

            // リスト / 空状態
            if (dictionary.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        "Tap + to add an entry\n\nExample:\nURL → pronounced spelling\nhttps:// → (leave blank to skip)",
                        color = textMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(dictionary) { index, entry ->
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .shadow(4.dp, RoundedCornerShape(16.dp))
                                .background(paperColor, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.original, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary)
                                    Text(
                                        text = if (entry.replacement.isEmpty()) "→ (skip)" else "→ ${entry.replacement}",
                                        fontSize = 13.sp,
                                        color = if (entry.replacement.isEmpty()) pinkColor else greenColor
                                    )
                                }
                                // ON/OFFスイッチ（クレイ配色）
                                Switch(
                                    checked = entry.isEnabled,
                                    onCheckedChange = { onToggleEntry(index) },
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor   = Color.White,
                                        checkedTrackColor   = primaryColor,
                                        uncheckedThumbColor = paperColor,
                                        uncheckedTrackColor = textMuted.copy(alpha = 0.3f)
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                // 編集ボタン（clay白背景 + インジゴアイコン）
                                Box(modifier = Modifier.size(44.dp)
                                    .shadow(3.dp, RoundedCornerShape(12.dp))
                                    .background(paperColor, RoundedCornerShape(12.dp))
                                    .clickable { onEditEntry(index) },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(22.dp), tint = primaryColor)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                // 削除ボタン（clay白背景 + ピンクアイコン・確認ダイアログ付き）
                                Box(modifier = Modifier.size(44.dp)
                                    .shadow(3.dp, RoundedCornerShape(12.dp))
                                    .background(paperColor, RoundedCornerShape(12.dp))
                                    .clickable { deleteTargetIndex = index; showDeleteDialog = true },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(22.dp), tint = pinkColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun DictionaryEditScreen(
        initialEntry: DictionaryEntry?,
        onSave: (String, String, Boolean) -> Unit,
        onCancel: () -> Unit
    ) {
        var original by remember { mutableStateOf(initialEntry?.original ?: "") }
        var replacement by remember { mutableStateOf(initialEntry?.replacement ?: "") }
        var isEnabled by remember { mutableStateOf(initialEntry?.isEnabled ?: true) }
        var showDialog by remember { mutableStateOf(false) }

        val hasChanges = original != (initialEntry?.original ?: "") ||
                replacement != (initialEntry?.replacement ?: "") ||
                isEnabled != (initialEntry?.isEnabled ?: true)

        // ★この画面の間だけ縦固定。出たら回転を元に戻す
        val activity = LocalContext.current as? Activity
        DisposableEffect(Unit) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        BackHandler {
            if (hasChanges) showDialog = true else onCancel()
        }

        // ★クレイモーフィズム カラーパレット（再生画面と統一）
        val isDark       = isSystemInDarkTheme()
        val bgColor      = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F4FF)
        val paperColor   = if (isDark) Color(0xFF16213E) else Color(0xFFFFFFFF)
        val primaryColor = Color(0xFF6366F1)
        val textPrimary  = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
        val textMuted    = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        val gradient     = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899)))

        // ★クレイモーフィズム 未保存ダイアログ
        if (showDialog) {
            Dialog(onDismissRequest = { showDialog = false }) {
                Box(
                    modifier = Modifier
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(paperColor, RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Leave without saving?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textPrimary)
                        Text("Your changes will be lost.", fontSize = 14.sp, color = textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // NO = 目立つグラデーションボタン（押し間違え防止）
                            Box(
                                modifier = Modifier.weight(1f)
                                    .shadow(4.dp, RoundedCornerShape(12.dp))
                                    .background(gradient, RoundedCornerShape(12.dp))
                                    .clickable { showDialog = false }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("NO", fontWeight = FontWeight.Bold, color = Color.White)
                            }
                            // YES = 控えめなボタン
                            Box(
                                modifier = Modifier.weight(1f)
                                    .shadow(4.dp, RoundedCornerShape(12.dp))
                                    .background(textMuted.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .clickable { showDialog = false; onCancel() }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("YES", fontWeight = FontWeight.Bold, color = textMuted)
                            }
                        }
                    }
                }
            }
        }

        // ★横向き + キーボード表示中の検知
        val isKeyboardVisible = WindowInsets.isImeVisible
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // ★クレイモーフィズム編集画面
        Column(modifier = Modifier.fillMaxSize().background(bgColor).statusBarsPadding()) {
            // ★横向き + キーボード表示中はヘッダーを丸ごと非表示（テキスト入力スペース確保）
            if (!isLandscape || !isKeyboardVisible) {
                Row(
                    modifier = Modifier.fillMaxWidth().background(paperColor).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 戻るボタン（クレイ円形）
                    Box(
                        modifier = Modifier.size(40.dp)
                            .shadow(4.dp, CircleShape)
                            .background(paperColor, CircleShape)
                            .clickable { if (hasChanges) showDialog = true else onCancel() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp), tint = primaryColor)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        if (initialEntry == null) "New Entry" else "Edit Entry",
                        fontWeight = FontWeight.Bold, fontSize = 20.sp,
                        modifier = Modifier.weight(1f), color = textPrimary
                    )
                    // 保存ボタン（グラデーション）
                    Box(
                        modifier = Modifier
                            .shadow(4.dp, RoundedCornerShape(12.dp))
                            .background(gradient, RoundedCornerShape(12.dp))
                            .clickable { if (original.isNotBlank()) onSave(original, replacement, isEnabled) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 15.sp)
                    }
                }
            }

            // ★キーボード回避付き入力フィールド群
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 置換元フィールド
                if (!isLandscape) Text("Word to replace", fontWeight = FontWeight.SemiBold, color = textMuted, fontSize = 13.sp)
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .background(paperColor, RoundedCornerShape(16.dp))
                ) {
                    OutlinedTextField(
                        value = original, onValueChange = { original = it },
                        placeholder = { Text("e.g. URL, https://", color = textMuted) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = textPrimary, unfocusedTextColor = textPrimary,
                            cursorColor = primaryColor
                        )
                    )
                }

                // 置換先フィールド
                if (!isLandscape) Text("Replacement (blank = skip)", fontWeight = FontWeight.SemiBold, color = textMuted, fontSize = 13.sp)
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .background(paperColor, RoundedCornerShape(16.dp))
                ) {
                    OutlinedTextField(
                        value = replacement, onValueChange = { replacement = it },
                        placeholder = { Text("e.g. pronounced spelling (or blank)", color = textMuted) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = textPrimary, unfocusedTextColor = textPrimary,
                            cursorColor = primaryColor
                        )
                    )
                }

                // ON/OFFスイッチ行
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .background(paperColor, RoundedCornerShape(16.dp))
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable this entry", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = textPrimary)
                        Switch(
                            checked = isEnabled, onCheckedChange = { isEnabled = it },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = primaryColor, checkedThumbColor = Color.White,
                                uncheckedTrackColor = textMuted.copy(alpha = 0.3f), uncheckedThumbColor = paperColor
                            )
                        )
                    }
                }
            }
        }
    }


    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    fun MainScreen(
        text: String,
        sentences: List<String>,
        onTextChange: (String) -> Unit,
        onNavigateToPrompts: () -> Unit,
        onNavigateToDictionary: () -> Unit,
        onUpdateText: (String) -> Unit
    ) {
        var isPlaying by remember { mutableStateOf(false) }
        var speechRate by remember { mutableFloatStateOf(1.0f) }
        var pitch by remember { mutableFloatStateOf(1.0f) }
        var currentSentenceIndex by remember { mutableIntStateOf(0) }
        var isEditMode by remember { mutableStateOf(false) }
        // ★③ フォントサイズ：13(Small) / 16(Medium) / 20(Large)
        var fontSize by remember { mutableFloatStateOf(16f) }

        // ★編集中のテキストを保持する変数
        var editingText by remember { mutableStateOf(text) }

        // ★表示専用の文分割リスト（辞書未適用・元の単語をそのまま表示する）
        // sentences（辞書適用済み）はTTS読み上げ専用、このリストは画面表示専用
        val displaySentences = remember(text) { TextProcessor.splitSentences(text) }

        // ★isEditMode時のバック確認ダイアログ表示フラグ
        var showUnsavedDialog by remember { mutableStateOf(false) }

        // ★非対応ファイル形式ダイアログ用：タップされたファイルのURIを保持
        var unsupportedFileUri by remember { mutableStateOf<Uri?>(null) }

        // ★textが変更されたら編集用テキストも更新
        LaunchedEffect(text) {
            if (!isEditMode) {
                editingText = text
            }
        }

        // ★バックジェスチャーのインターセプト：編集中のみ有効
        // ホームジェスチャー（下スワイプでアプリ退出）はここに届かないので許容される
        BackHandler(enabled = isEditMode) {
            if (editingText != text) {
                // 変更あり → ダイアログを出す
                showUnsavedDialog = true
            } else {
                // 変更なし → そのまま編集モード解除
                isEditMode = false
            }
        }

        // ★⑤ネオブルータリスト未保存ダイアログ
        if (showUnsavedDialog) {
            Dialog(onDismissRequest = { showUnsavedDialog = false }) {
                Box(modifier = Modifier.wrapContentHeight().padding(end = 4.dp, bottom = 4.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 4.dp, y = 4.dp).background(Color.Black, RoundedCornerShape(16.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(4.dp, Color.Black)) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Leave without saving?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Your changes will be lost.", fontSize = 14.sp, color = Color.Gray)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // YES（控えめ：白背景 + 黒枠）
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.White, RoundedCornerShape(8.dp)).clickable { showUnsavedDialog = false; editingText = text; isEditMode = false }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("YES", fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                                // NO（目立つ：黒背景 + 白文字）
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color(0xFF444444), RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.Black, RoundedCornerShape(8.dp)).clickable { showUnsavedDialog = false }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("NO", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val listState = rememberLazyListState()
        val context = LocalContext.current
        val activity = context as? MainActivity

        // ★非対応ファイル形式ダイアログ
        unsupportedFileUri?.let { fileUri ->
            Dialog(onDismissRequest = { unsupportedFileUri = null }) {
                val isDarkDlg = isSystemInDarkTheme()
                val dlgPaper = if (isDarkDlg) Color(0xFF16213E) else Color(0xFFFFFFFF)
                val dlgText  = if (isDarkDlg) Color(0xFFE2E8F0) else Color(0xFF1E293B)
                val dlgMuted = if (isDarkDlg) Color(0xFF64748B) else Color(0xFF94A3B8)
                val dlgGrad  = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899)))
                Box(
                    modifier = Modifier
                        .shadow(8.dp, RoundedCornerShape(20.dp))
                        .background(dlgPaper, RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(
                            "This format isn't supported. Open it, copy the text, then tap Paste.",
                            fontSize = 15.sp, color = dlgText, lineHeight = 22.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // CANCEL（控えめ）
                            Box(
                                modifier = Modifier.weight(1f)
                                    .shadow(4.dp, RoundedCornerShape(12.dp))
                                    .background(dlgMuted.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
                                    .clickable { unsupportedFileUri = null }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("CANCEL", fontWeight = FontWeight.Bold, color = dlgMuted, fontSize = 13.sp)
                            }
                            // OPEN FILE（グラデーション）
                            Box(
                                modifier = Modifier.weight(1f)
                                    .shadow(4.dp, RoundedCornerShape(12.dp))
                                    .background(dlgGrad, RoundedCornerShape(12.dp))
                                    .clickable {
                                        unsupportedFileUri = null
                                        // ファイルに対応したアプリをシステムが自動選択して起動
                                        try {
                                            val openIntent = Intent(Intent.ACTION_VIEW, fileUri).apply {
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(openIntent)
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "No app found to open this file.", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("OPEN FILE", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        val scope = rememberCoroutineScope()

        // ★編集モード中だけ縦固定。isEditModeが変わるたびに再実行される
        // DisposableEffect(key)：keyが変わるたびにonDisposeで後片付け→再実行される仕組み
        // ファイルピッカーを開くのは再生モード（isEditMode=false）なので横向きのまま使える
        DisposableEffect(isEditMode) {
            if (isEditMode) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            onDispose {
                // 編集モードを抜けるとき（保存・キャンセル・バックジェスチャー）に解除
                if (isEditMode) {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }

        // ★修正：読み上げ中の段落が画面の中央付近に来るようにスクロール
        // animateScrollToItemは指定アイテムを「画面上端」に持ってくるので、
        // 画面に表示されているアイテム数の半分だけ手前にずらすことで中央に寄せる
        LaunchedEffect(currentSentenceIndex) {
            if (currentSentenceIndex >= 0 && currentSentenceIndex < sentences.size) {
                // LazyColumnの描画完了を待ってからスクロール実行
                kotlinx.coroutines.delay(100)
                // 現在画面に見えているアイテム数を取得し、半分手前にスクロール
                val visibleCount = listState.layoutInfo.visibleItemsInfo.size
                val targetIndex = maxOf(0, currentSentenceIndex - visibleCount / 2)
                listState.animateScrollToItem(targetIndex)
            }
        }

        // Intent処理
        LaunchedEffect(Unit) {
            activity?.intent?.let { intent ->
                if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { sharedText ->
                        val urlRegex = Regex("https?://[\\w!?/+\\-_~=;.,*&@#$%()\'\\[\\]]+")
                        val match = urlRegex.find(sharedText)

                        if (match != null) {
                            val url = match.value
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val doc = Jsoup.connect(url).userAgent("Mozilla/5.0").timeout(10000).get()
                                    val bodyText = doc.body().text()
                                    withContext(Dispatchers.Main) { onUpdateText(bodyText) }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { onUpdateText("エラー: ${e.message}") }
                                }
                            }
                        } else {
                            onUpdateText(sharedText)
                        }
                        activity.intent.removeExtra(Intent.EXTRA_TEXT)
                    }
                }
            }
        }

        // PDF / Word / Google Document ファイルピッカー（複数形式に対応）
        val docPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    // MIMEタイプを確認してファイル形式を自動判定
                    val mimeType = context.contentResolver.getType(it) ?: ""
                    val text = when {
                        mimeType.contains("pdf") ->
                            extractTextFromPdf(context, it)
                        mimeType.contains("wordprocessingml") || mimeType.contains("msword") ->
                            extractTextFromDocx(context, it)
                        // ★追加：Google DocumentやHTMLファイルの処理
                        // Google DriveはGoogle DocをSAF経由でHTMLとして提供することがある
                        mimeType.contains("google-apps.document") || mimeType.contains("text/html") ->
                            extractTextFromHtml(context, it)
                        // ★追加：プレーンテキストファイルの処理
                        mimeType.startsWith("text/plain") ->
                            context.contentResolver.openInputStream(it)
                                ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
                        else -> ""
                    }
                    withContext(Dispatchers.Main) {
                        if (text.isNotEmpty()) {
                            onUpdateText(text)
                        } else if (mimeType.contains("google-apps.document")) {
                            // ★改善：content://URIは他アプリへの受け渡しが不安定なため
                            // Google DriveのファイルIDを取り出してウェブURLで開く方式に変更
                            try {
                                // ★改善：ファイルIDの解析をやめて、content://URIを
                                // Google Docsアプリ（パッケージ名で指定）に直接渡す
                                // 「自分のストレージのURIを自分で開く」形にすることで確実に動作する
                                val viewIntent = Intent(Intent.ACTION_VIEW, it)
                                viewIntent.setPackage("com.google.android.apps.docs")
                                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(viewIntent)
                                Toast.makeText(
                                    context,
                                    "Select & copy text, then tap Paste to return",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                // フォールバック：ファイルID取得に失敗したらGoogleドライブを開く
                                try {
                                    val driveIntent = Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://drive.google.com"))
                                    context.startActivity(driveIntent)
                                    Toast.makeText(
                                        context,
                                        "Open the Doc in Drive, copy text, then tap Paste",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } catch (e2: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Google Drive app not found",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        } else {
                            // ★非対応形式：ダイアログを表示してファイルを手動で開くよう促す
                            unsupportedFileUri = it
                        }
                    }
                }
            }
        }

        // リスナー設定（再生ボタンとタップ再生で共通化）
        fun setupTtsListener() {
            ttsService?.setListener(object : TtsService.TtsListener {
                override fun onProgress(current: Int, total: Int) {
                    currentSentenceIndex = current
                    isPlaying = true  // 通知 PLAY ボタンから再開したときも UI を同期
                }
                override fun onComplete() {
                    isPlaying = false
                    // ★修正：読了後も先頭をグレーハイライトで示す（-1だとハイライトが消えてしまうため0に変更）
                    currentSentenceIndex = 0
                    // 再生終了のたびにカウントし、3回に1回インタースティシャル広告を表示
                    activity?.showInterstitialAdIfReady()
                }
                override fun onError(msg: String) {
                    isPlaying = false
                }
                // ★追加：通知の PAUSE ボタンが押されたとき
                // onComplete() と違い currentSentenceIndex はリセットしない（位置を覚えたまま止まる）
                override fun onPaused() {
                    isPlaying = false
                }
            })
        }

        // ★キーボード（IME）が表示中かどうか検知（横向き編集時のスペース確保のため）
        val isKeyboardVisible = WindowInsets.isImeVisible
        // ★横向き判定（シートのpeek高さ調整に使用）
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        // ★BottomSheetScaffoldの状態（編集モード中も常に保持するためif-elseの外で定義）
        val scaffoldState = rememberBottomSheetScaffoldState()

        // ★カラーシステム（ライト/ダークモード切替）
        val isDark = isSystemInDarkTheme()
        val bgColor         = if (isDark) Color(0xFF0A0A0B) else Color(0xFFF3F4F6)
        val paperColor      = if (isDark) Color(0xFF1E1E21) else Color.White
        val primaryColor    = if (isDark) Color(0xFF818CF8) else Color(0xFF6366F1)
        val textPrimary     = if (isDark) Color.White       else Color(0xFF1E293B)
        val textSecondary   = if (isDark) Color(0xFFCBD5E1) else Color(0xFF475569)
        val textMuted       = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        val handleColor     = if (isDark) Color(0xFF2D2D30) else Color(0xFFE2E8F0)
        val sliderBg        = if (isDark) Color(0xFF0A0A0B) else Color(0xFFE2E8F0)
        val speedGradient = Brush.horizontalGradient(colors = listOf(
            if (isDark) Color(0xFFFF2E97) else Color(0xFFEC4899),
            if (isDark) Color(0xFF818CF8) else Color(0xFF6366F1)
        ))
        val pitchGradient = Brush.horizontalGradient(colors = listOf(
            if (isDark) Color(0xFF00F2FF) else Color(0xFF06B6D4),
            if (isDark) Color(0xFF818CF8) else Color(0xFF6366F1)
        ))
        val playGradient = Brush.linearGradient(
            colors = listOf(
                if (isDark) Color(0xFF818CF8) else Color(0xFF6366F1),
                if (isDark) Color(0xFFFF2E97) else Color(0xFFEC4899)
            ),
            start = Offset(0f, 0f),
            end   = Offset(200f, 200f)
        )

        // ★パルスアニメーション：再生中にボタンとTrackバーがふわふわ光る
        // rememberInfiniteTransition = 永遠に繰り返すアニメーションの入れ物
        val pulseTransition = rememberInfiniteTransition()
        // 0f→1f→0f→1f... と900ms周期で往復する生の値
        @Suppress("UnusedTransitionTargetStateParameter")
        val rawPulse by pulseTransition.animateFloat(
            initialValue  = 0f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(900, easing = FastOutSlowInEasing), // 900ms：速すぎず遅すぎず
                repeatMode = RepeatMode.Reverse                         // 往復（行って戻る）
            )
        )
        // 再生中のみ適用。停止中は値を固定して静止させる
        val pulseScale = if (isPlaying) 1f + rawPulse * 0.12f else 1f      // 1.0 ↔ 1.12（ふわっと大きめ拡縮）
        val pulseGlow  = if (isPlaying) 0.3f + rawPulse * 0.5f else 0.35f  // グロー強度：再生中0.3↔0.8、停止中0.35固定

        Column(modifier = Modifier.fillMaxSize().background(bgColor).statusBarsPadding().imePadding()) {  // ★statusBarsPadding：ステータスバー（充電・時刻表示エリア）に被らないよう余白確保
            // ★ヘッダー（編集モード時は保存ボタン、通常は編集ボタン）
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Reader", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                    Text(".", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = primaryColor)
                }
                // ★ヘッダーボタン：押したときにふわっと縮む（0.90倍）プレスエフェクト
                val headerSrc = remember { MutableInteractionSource() }
                val headerPressed by headerSrc.collectIsPressedAsState()
                val headerScale by animateFloatAsState(if (headerPressed) 0.90f else 1f, tween(80), label = "hdr")
                Box(
                    modifier = Modifier.size(48.dp)
                        .scale(headerScale)
                        .shadow(elevation = 4.dp, shape = RoundedCornerShape(16.dp))
                        .background(paperColor, RoundedCornerShape(16.dp))
                        .clickable(interactionSource = headerSrc, indication = null) {
                            if (isEditMode) {
                                onUpdateText(editingText); isEditMode = false; currentSentenceIndex = 0
                            } else {
                                if (isPlaying) { ttsService?.stop(); isPlaying = false }
                                editingText = text; isEditMode = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isEditMode) Icons.Default.Save else Icons.Default.Edit,
                        contentDescription = if (isEditMode) "Save" else "Edit",
                        tint = primaryColor, modifier = Modifier.size(22.dp)
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                if (isEditMode) {
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = { editingText = it },
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text("Edit text here...", color = textMuted) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor     = textPrimary,
                            unfocusedTextColor   = textPrimary
                        ),
                        textStyle = TextStyle(fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp)
                    )
                } else {
                    BottomSheetScaffold(
                        modifier             = Modifier.fillMaxSize(),
                        scaffoldState        = scaffoldState,
                        sheetPeekHeight      = if (isLandscape) 125.dp else 205.dp,  // ★縦205dp（170+35=ADオーバーレイ分を加算。実質見える量は170dp相当）横125dp（Transportのみ）
                        sheetContainerColor  = paperColor,
                        sheetShadowElevation = 16.dp,
                        containerColor       = bgColor,
                        sheetShape           = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                        sheetDragHandle = {
                            // ★ヒットエリア：シート上辺から30dpの全幅。タップで展開/収納トグル
                            // indication = null でタップ時のグレーリップルエフェクトを非表示
                            Box(modifier = Modifier.fillMaxWidth().height(30.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null  // ★リップルエフェクト（グレー波紋）を出さない
                                ) {
                                    scope.launch {
                                        if (scaffoldState.bottomSheetState.currentValue == SheetValue.Expanded) {
                                            scaffoldState.bottomSheetState.partialExpand()  // 収納
                                        } else {
                                            scaffoldState.bottomSheetState.expand()          // 展開
                                        }
                                    }
                                },
                                contentAlignment = Alignment.Center) {
                                // 視覚的なハンドルバー（幅56dp・高さ5dp）
                                Box(modifier = Modifier.width(56.dp).height(5.dp)
                                    .background(handleColor, RoundedCornerShape(50)))
                            }
                        },
                        sheetContent = {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                // ★Transport用プレスエフェクト変数（◄ 再生 ► それぞれ独立）
                                val prevSrc  = remember { MutableInteractionSource() }
                                val prevPressed  by prevSrc.collectIsPressedAsState()
                                val prevScale    by animateFloatAsState(if (prevPressed)  0.90f else 1f, tween(80), label = "prev")
                                val playSrc  = remember { MutableInteractionSource() }
                                val playPressed  by playSrc.collectIsPressedAsState()
                                val playPressScale by animateFloatAsState(if (playPressed) 0.92f else 1f, tween(80), label = "play")
                                val nextSrc  = remember { MutableInteractionSource() }
                                val nextPressed  by nextSrc.collectIsPressedAsState()
                                val nextScale    by animateFloatAsState(if (nextPressed)  0.90f else 1f, tween(80), label = "next")

                                // Transport controls（◄| 再生/停止 |►）
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(horizontal = 32.dp, vertical = 6.dp),  // ★vertical 8→6dp（3/4縮小）
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(56.dp)
                                        .scale(prevScale)  // ★プレス時に縮む
                                        .shadow(4.dp, CircleShape)
                                        .background(bgColor, CircleShape)
                                        .clickable(interactionSource = prevSrc, indication = null) {
                                            val prev = maxOf(0, currentSentenceIndex - 1)
                                            currentSentenceIndex = prev
                                            if (isPlaying) {
                                                ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                                setupTtsListener(); ttsService?.speakList(sentences, prev)
                                            }
                                        },
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.SkipPrevious, "Previous",
                                            modifier = Modifier.size(28.dp), tint = textMuted)
                                    }
                                    Spacer(modifier = Modifier.width(24.dp))
                                    Box(modifier = Modifier.size(80.dp)
                                        .scale(pulseScale * playPressScale)  // ★呼吸アニメ × プレス縮小を合成
                                        .shadow(8.dp, CircleShape,
                                            ambientColor = primaryColor.copy(alpha = pulseGlow * 0.5f),  // ★グローが呼吸する
                                            spotColor    = primaryColor.copy(alpha = pulseGlow * 0.6f))
                                        .background(playGradient, CircleShape)
                                        .border(3.dp, paperColor, CircleShape)
                                        .clickable(interactionSource = playSrc, indication = null) {
                                            if (isPlaying) {
                                                ttsService?.stop(); isPlaying = false
                                            } else {
                                                if (sentences.isNotEmpty()) {
                                                    val s = if (currentSentenceIndex in sentences.indices) currentSentenceIndex else 0
                                                    ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                                    setupTtsListener(); ttsService?.speakList(sentences, s)
                                                    currentSentenceIndex = s; isPlaying = true
                                                }
                                            }
                                        },
                                        contentAlignment = Alignment.Center) {
                                        Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            if (isPlaying) "Stop" else "Play",
                                            modifier = Modifier.size(40.dp)
                                                .then(if (!isPlaying) Modifier.offset(x = 2.dp) else Modifier),
                                            tint = Color.White)
                                    }
                                    Spacer(modifier = Modifier.width(24.dp))
                                    Box(modifier = Modifier.size(56.dp)
                                        .scale(nextScale)  // ★プレス時に縮む
                                        .shadow(4.dp, CircleShape)
                                        .background(bgColor, CircleShape)
                                        .clickable(interactionSource = nextSrc, indication = null) {
                                            val next = minOf(sentences.size - 1, currentSentenceIndex + 1)
                                            currentSentenceIndex = next
                                            if (isPlaying) {
                                                ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                                setupTtsListener(); ttsService?.speakList(sentences, next)
                                            }
                                        },
                                        contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.SkipNext, "Next",
                                            modifier = Modifier.size(28.dp), tint = textMuted)
                                    }
                                }
                                // SPEED スライダー
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("SPEED", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.5.sp, color = textMuted, modifier = Modifier.width(48.dp))
                                    Slider(value = speechRate,
                                        onValueChange = { speechRate = it; ttsService?.setSpeechRate(it) },
                                        valueRange = 0.5f..3.0f, modifier = Modifier.weight(1f),
                                        thumb = { Box(modifier = Modifier.size(24.dp).shadow(4.dp, CircleShape)
                                            .background(Color.White, CircleShape).border(2.dp, primaryColor, CircleShape)) },
                                        track = { ss ->
                                            val f = ((ss.value - 0.5f) / 2.5f).coerceIn(0f, 1f)
                                            Box(modifier = Modifier.fillMaxWidth().height(10.dp)
                                                .background(sliderBg, RoundedCornerShape(50))) {
                                                Box(modifier = Modifier.fillMaxWidth(f).fillMaxHeight()
                                                    .background(speedGradient, RoundedCornerShape(50)))
                                            }
                                        })
                                    Text("${String.format(Locale.US, "%.1f", speechRate)}x",
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textPrimary,
                                        modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                                }
                                // Track 進捗（小さく薄く）
                                if (sentences.size > 1) {
                                    val rp = (currentSentenceIndex.toFloat() / (sentences.size - 1).toFloat()).coerceIn(0f, 1f)
                                    val ap by animateFloatAsState(rp, tween(300), label = "tp")
                                    Row(modifier = Modifier.fillMaxWidth()
                                        .padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Box(modifier = Modifier.weight(1f).height(3.dp)
                                            .background(sliderBg.copy(alpha = 0.5f), RoundedCornerShape(50))) {
                                            Box(modifier = Modifier.fillMaxWidth(ap).fillMaxHeight()
                                                .background(textMuted.copy(alpha = if (isPlaying) 0.3f + rawPulse * 0.35f else 0.4f), RoundedCornerShape(50)))  // ★再生中にTrackバーもふわふわ
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("${(ap * 100).toInt()}%", fontSize = 9.sp,
                                            color = textMuted.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)
                                    }
                                }
                                // ──── 展開時のみ表示 ────
                                Spacer(modifier = Modifier.height(6.dp))  // ★8→6dp
                                // PITCH スライダー
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("PITCH", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.5.sp, color = textMuted, modifier = Modifier.width(48.dp))
                                    Slider(value = pitch,
                                        onValueChange = { pitch = it; ttsService?.setPitch(it) },
                                        valueRange = 0.5f..2.0f, modifier = Modifier.weight(1f),
                                        thumb = { Box(modifier = Modifier.size(24.dp).shadow(4.dp, CircleShape)
                                            .background(Color.White, CircleShape).border(2.dp, primaryColor, CircleShape)) },
                                        track = { ss ->
                                            val f = ((ss.value - 0.5f) / 1.5f).coerceIn(0f, 1f)
                                            Box(modifier = Modifier.fillMaxWidth().height(10.dp)
                                                .background(sliderBg, RoundedCornerShape(50))) {
                                                Box(modifier = Modifier.fillMaxWidth(f).fillMaxHeight()
                                                    .background(pitchGradient, RoundedCornerShape(50)))
                                            }
                                        })
                                    Text(when { pitch < 0.85f -> "Low"; pitch < 1.3f -> "Mid"; else -> "High" },
                                        fontSize = 13.sp, fontWeight = FontWeight.Bold, color = textPrimary,
                                        modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                                }
                                Spacer(modifier = Modifier.height(15.dp))  // ★20→15dp
                                // 4つのアクションボタン
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    listOf(
                                        Triple(Icons.Default.ContentPaste, "Paste",
                                            if (isDark) Color(0xFFFF2E97) else Color(0xFFEC4899)),
                                        Triple(Icons.Default.FolderOpen, "Files",
                                            if (isDark) Color(0xFF00F2FF) else Color(0xFF06B6D4)),
                                        Triple(Icons.Default.Assistant, "Prompts",
                                            if (isDark) Color(0xFFFF8C00) else Color(0xFFF97316)),
                                        Triple(Icons.Default.MenuBook, "Dict.",
                                            if (isDark) Color(0xFF00FF9D) else Color(0xFF10B981))
                                    ).forEachIndexed { idx, (icon, label, tint) ->
                                        // ★key(idx)でラップ：ループ内でremember/stateを安全に使うためのCompose必須パターン
                                        key(idx) {
                                            val actionSrc = remember { MutableInteractionSource() }
                                            val actionPressed by actionSrc.collectIsPressedAsState()
                                            val actionScale  by animateFloatAsState(if (actionPressed) 0.88f else 1f, tween(80), label = "act$idx")
                                            Column(modifier = Modifier.weight(1f)
                                                .clickable(interactionSource = actionSrc, indication = null) {
                                                    when (idx) {
                                                        0 -> { val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                            val cd = cb.primaryClip
                                                            if (cd != null && cd.itemCount > 0) onUpdateText(cd.getItemAt(0).text.toString()) }
                                                        1 -> docPickerLauncher.launch(arrayOf("application/pdf",
                                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                            "application/vnd.google-apps.document", "text/html", "text/plain"))
                                                        2 -> { if (isPlaying) { ttsService?.stop(); isPlaying = false }; onNavigateToPrompts() }
                                                        3 -> { if (isPlaying) { ttsService?.stop(); isPlaying = false }; onNavigateToDictionary() }
                                                    }
                                                }, horizontalAlignment = Alignment.CenterHorizontally) {
                                                Box(modifier = Modifier.size(56.dp)
                                                    .scale(actionScale)  // ★プレス時にぎゅっと縮む
                                                    .shadow(4.dp, RoundedCornerShape(16.dp))
                                                    .background(paperColor, RoundedCornerShape(16.dp)),
                                                    contentAlignment = Alignment.Center) {
                                                    Icon(icon, label, modifier = Modifier.size(28.dp), tint = tint)
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = textMuted)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(15.dp))  // ★20→15dp
                                // フォントサイズ S/M/L
                                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp)
                                    .background(bgColor, RoundedCornerShape(16.dp)).padding(6.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        listOf(Pair(13f, "S"), Pair(16f, "M"), Pair(20f, "L")).forEachIndexed { idx, (size, label) ->
                                            if (idx > 0) Box(modifier = Modifier.width(1.dp).height(20.dp).background(handleColor))
                                            Box(modifier = Modifier.weight(1f).height(40.dp)
                                                .then(if (fontSize == size) Modifier.shadow(2.dp, RoundedCornerShape(12.dp))
                                                    .background(paperColor, RoundedCornerShape(12.dp)) else Modifier)
                                                .clickable { fontSize = size },
                                                contentAlignment = Alignment.Center) {
                                                Text(label, fontSize = (10 + idx * 3).sp,
                                                    fontWeight = if (fontSize == size) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (fontSize == size) primaryColor else textMuted)
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(35.dp))  // ★広告スペース分の余白（35dp）
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp)) {
                            if (text.isEmpty()) {
                                Text("Copy a prompt with the Prompts button,\nthen paste the Perplexity result here.",
                                    modifier = Modifier.padding(16.dp), color = textMuted,
                                    fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp)
                            } else {
                                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(displaySentences) { index, sentence ->
                                        Text(sentence,
                                            modifier = Modifier.fillMaxWidth()
                                                .background(when {
                                                    index == currentSentenceIndex && isPlaying ->
                                                        if (isDark) Color(0x33FFFF99) else Color(0xFFFFF59D)
                                                    index == currentSentenceIndex && !isPlaying ->
                                                        if (isDark) Color(0x1AFFFFFF) else Color(0xFFE2E8F0)
                                                    else -> Color.Transparent
                                                }, RoundedCornerShape(8.dp))
                                                .clickable {
                                                    ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                                    setupTtsListener(); ttsService?.speakList(sentences, index)
                                                    currentSentenceIndex = index; isPlaying = true
                                                }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            fontSize = fontSize.sp, lineHeight = (fontSize * 1.6f).sp,
                                            color = if (index == currentSentenceIndex && isPlaying) textPrimary else textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }

                // ★ADオーバーレイ：Scaffoldの後に描画することで最上レイヤーになる
                // 非ポップアップ時にSPEEDバー以下（Track・PITCH等）を視覚的に隠すバリア
                // 編集モード・キーボード表示中は不要なので非表示
                if (!isEditMode && !isKeyboardVisible) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(35.dp)
                            .background(bgColor)
                    )
                }
            }
        }
    }


    @Composable
    fun PromptListScreen(
        prompts: List<PromptItem>,
        onNavigateBack: () -> Unit,
        onEditPrompt: (Int) -> Unit,
        onCreatePrompt: () -> Unit,
        onDeletePrompt: (Int) -> Unit,
        onSelectPrompt: (PromptItem) -> Unit
    ) {
        BackHandler { onNavigateBack() }

        // ★この画面の間だけ縦固定。出たら回転を元に戻す
        val activity = LocalContext.current as? Activity
        DisposableEffect(Unit) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // ★削除確認ダイアログ用状態
        var showDeleteDialog by remember { mutableStateOf(false) }
        var deleteTargetIndex by remember { mutableIntStateOf(-1) }

        // ★クレイモーフィズム カラーパレット（再生画面と統一）
        val isDark       = isSystemInDarkTheme()
        val bgColor      = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F4FF)
        val paperColor   = if (isDark) Color(0xFF16213E) else Color(0xFFFFFFFF)
        val primaryColor = Color(0xFF6366F1)
        val textPrimary  = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
        val textMuted    = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        val pinkColor    = if (isDark) Color(0xFFFF2E97) else Color(0xFFEC4899)
        // ★horizontalGradient：ボタンの幅に自動フィットするため小さいボタンでも確実にグラデが出る
        val gradient     = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899)))

        // ★削除確認ダイアログ（クレイモーフィズム）
        if (showDeleteDialog) {
            Dialog(onDismissRequest = { showDeleteDialog = false }) {
                Box(
                    modifier = Modifier
                        .shadow(16.dp, RoundedCornerShape(20.dp))
                        .background(paperColor, RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Delete this prompt?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textPrimary)
                        Text("This action cannot be undone.", fontSize = 14.sp, color = textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // YES（控えめ：アウトライン）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(2.dp, RoundedCornerShape(12.dp))
                                .background(paperColor, RoundedCornerShape(12.dp))
                                .clickable { showDeleteDialog = false; onDeletePrompt(deleteTargetIndex) },
                                contentAlignment = Alignment.Center) {
                                Text("YES", fontWeight = FontWeight.Bold, color = textMuted, fontSize = 14.sp)
                            }
                            // NO（目立つ：グラデーション）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(4.dp, RoundedCornerShape(12.dp))
                                .background(gradient, RoundedCornerShape(12.dp))
                                .clickable { showDeleteDialog = false },
                                contentAlignment = Alignment.Center) {
                                Text("NO", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // ★クレイモーフィズム レイアウト
        Column(modifier = Modifier.fillMaxSize().background(bgColor).statusBarsPadding()) {
            // ヘッダー
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 戻るボタン
                Box(modifier = Modifier.size(48.dp)
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(paperColor, RoundedCornerShape(16.dp))
                    .clickable { onNavigateBack() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.ArrowBack, "Back", tint = primaryColor, modifier = Modifier.size(22.dp))
                }
                // タイトル
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Prompts", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary)
                    Text(".", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = primaryColor)
                }
                // 追加ボタン（グラデーション）
                Box(modifier = Modifier.size(48.dp)
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(gradient, RoundedCornerShape(16.dp))
                    .clickable { onCreatePrompt() },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Add, "Add", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }

            // リスト / 空状態
            // ★weight(1f)：Columnの中でLazyColumnにfillMaxSizeを使うとレイアウト競合で
            //   先頭カードの高さがズレる。weight(1f)で「残り全部」を正しく渡す
            if (prompts.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("Tap + to add a prompt", color = textMuted)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    // ★top=20dp：先頭カードのshadowがLazyColumnの上端でクリップされないよう余白確保
                    contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(prompts) { index, prompt ->
                        // ★クレイカード（黒枠なし・シャドウ + 角丸）
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .shadow(4.dp, RoundedCornerShape(16.dp))
                                .background(paperColor, RoundedCornerShape(16.dp))
                                .clickable { onSelectPrompt(prompt) }
                                .padding(16.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(prompt.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary)
                                    Text(
                                        text = if (prompt.content.length > 40) prompt.content.take(40) + "..." else prompt.content,
                                        fontSize = 12.sp, color = textMuted
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // 編集ボタン（clay白背景 + インジゴアイコン）
                                Box(modifier = Modifier.size(44.dp)
                                    .shadow(3.dp, RoundedCornerShape(12.dp))
                                    .background(paperColor, RoundedCornerShape(12.dp))
                                    .clickable { onEditPrompt(index) },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Edit, "Edit", modifier = Modifier.size(22.dp), tint = primaryColor)
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                                // 削除ボタン（clay白背景 + ピンクアイコン・確認ダイアログ付き）
                                Box(modifier = Modifier.size(44.dp)
                                    .shadow(3.dp, RoundedCornerShape(12.dp))
                                    .background(paperColor, RoundedCornerShape(12.dp))
                                    .clickable { deleteTargetIndex = index; showDeleteDialog = true },
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Delete, "Delete", modifier = Modifier.size(22.dp), tint = pinkColor)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun extractTextFromPdf(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val document = com.tom_roush.pdfbox.pdmodel.PDDocument.load(inputStream)
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            val text = stripper.getText(document)
            document.close()
            inputStream?.close()

            // ★TextProcessorを使って改行を整形
            TextProcessor.cleanPdfText(text)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // .docxファイル（Word文書）からテキストを抽出する
    // .docxの正体はZIPファイル。中のword/document.xmlにテキストが入っている
    private fun extractTextFromDocx(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return ""
            val zip = java.util.zip.ZipInputStream(inputStream)

            // ZIPの中からword/document.xmlを探す
            var docXml = ""
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    docXml = zip.readBytes().decodeToString()
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            zip.close()
            inputStream.close()

            if (docXml.isEmpty()) return ""

            // 段落の終わり(</w:p>)を改行マーカーに置換しておく
            val normalized = docXml.replace("</w:p>", "\n___PARA___\n")

            // <w:t>タグのテキストと段落マーカーを順番に処理してテキストを組み立てる
            val sb = StringBuilder()
            var pos = 0
            while (pos < normalized.length) {
                val paraIdx = normalized.indexOf("___PARA___", pos)
                val wtStart = normalized.indexOf("<w:t", pos)

                when {
                    // 段落マーカーがテキストタグより先 → 改行を挿入
                    paraIdx != -1 && (wtStart == -1 || paraIdx < wtStart) -> {
                        sb.append("\n")
                        pos = paraIdx + "___PARA___".length
                    }
                    // <w:t>タグを発見 → 中のテキストを抽出
                    wtStart != -1 -> {
                        val tagClose = normalized.indexOf(">", wtStart)
                        val textEnd = normalized.indexOf("</w:t>", tagClose)
                        if (tagClose == -1 || textEnd == -1) break
                        sb.append(normalized.substring(tagClose + 1, textEnd))
                        pos = textEnd + 6 // "</w:t>".length = 6
                    }
                    // どちらも見つからなければ終了
                    else -> break
                }
            }

            // XMLエンティティ（特殊文字）をデコード
            val decoded = sb.toString()
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&apos;", "'")
                .replace("&quot;", "\"")

            // PDF用の改行整形処理を流用して仕上げる
            TextProcessor.cleanPdfText(decoded.trim())
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // Google DocumentやHTMLファイルからテキストを抽出する
    // Google DriveはSAF経由でGoogle DocをHTMLとして提供することがある
    // 既存のJsoupライブラリ（URLスクレイピング用に導入済み）を流用
    private fun extractTextFromHtml(context: Context, uri: Uri): String {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return ""
            val htmlContent = inputStream.bufferedReader(Charsets.UTF_8).readText()
            inputStream.close()
            // JsoupでHTMLタグを除去してプレーンテキストを抽出
            val bodyText = Jsoup.parse(htmlContent).body().text()
            // PDF用の改行整形処理を流用して仕上げる
            TextProcessor.cleanPdfText(bodyText)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    // 広告を事前ロードする（表示の直前ではなく、起動時・表示後に呼んで常に準備しておく）
    private fun loadInterstitialAd() {
        InterstitialAd.load(
            this,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                }
                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null  // 失敗しても次回また試みる
                }
            }
        )
    }

    // 再生終了時に呼ぶ：3回に1回広告を表示し、表示後に次回分を事前ロード
    // runOnUiThread：TTS コールバックはバックグラウンドスレッドなので必ずメインスレッドに戻す
    fun showInterstitialAdIfReady() {
        adPlayCount++
        if (adPlayCount < AD_INTERVAL) return
        adPlayCount = 0

        runOnUiThread {
            val ad = interstitialAd
            if (ad != null) {
                ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                    override fun onAdDismissedFullScreenContent() {
                        // 広告を閉じたら次回のために即ロード開始
                        interstitialAd = null
                        loadInterstitialAd()
                    }
                    override fun onAdFailedToShowFullScreenContent(error: AdError) {
                        interstitialAd = null
                        loadInterstitialAd()
                    }
                }
                ad.show(this)
            } else {
                // 広告が準備できていなければロードだけして今回はスキップ
                loadInterstitialAd()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun PromptEditScreen(
        initialPrompt: PromptItem?,
        onSave: (String, String) -> Unit,
        onCancel: () -> Unit
    ) {
        var title by remember { mutableStateOf(initialPrompt?.title ?: "") }
        var content by remember { mutableStateOf(initialPrompt?.content ?: "") }
        var showDialog by remember { mutableStateOf(false) }

        val hasChanges = title != (initialPrompt?.title ?: "") || content != (initialPrompt?.content ?: "")

        // ★この画面の間だけ縦固定。出たら回転を元に戻す
        val activity = LocalContext.current as? Activity
        DisposableEffect(Unit) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        BackHandler {
            if (hasChanges) showDialog = true else onCancel()
        }

        // ★クレイモーフィズム カラーパレット（再生画面と統一）
        val isDark       = isSystemInDarkTheme()
        val bgColor      = if (isDark) Color(0xFF1A1A2E) else Color(0xFFF0F4FF)
        val paperColor   = if (isDark) Color(0xFF16213E) else Color(0xFFFFFFFF)
        val primaryColor = Color(0xFF6366F1)
        val textPrimary  = if (isDark) Color(0xFFE2E8F0) else Color(0xFF1E293B)
        val textMuted    = if (isDark) Color(0xFF64748B) else Color(0xFF94A3B8)
        val gradient     = Brush.horizontalGradient(listOf(Color(0xFF6366F1), Color(0xFFEC4899)))

        // ★未保存ダイアログ（クレイモーフィズム）
        if (showDialog) {
            Dialog(onDismissRequest = { showDialog = false }) {
                Box(
                    modifier = Modifier
                        .shadow(16.dp, RoundedCornerShape(20.dp))
                        .background(paperColor, RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Leave without saving?", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = textPrimary)
                        Text("Your changes will be lost.", fontSize = 14.sp, color = textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // YES（控えめ：アウトライン）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(2.dp, RoundedCornerShape(12.dp))
                                .background(paperColor, RoundedCornerShape(12.dp))
                                .clickable { showDialog = false; onCancel() },
                                contentAlignment = Alignment.Center) {
                                Text("YES", fontWeight = FontWeight.Bold, color = textMuted, fontSize = 14.sp)
                            }
                            // NO（目立つ：グラデーション）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(4.dp, RoundedCornerShape(12.dp))
                                .background(gradient, RoundedCornerShape(12.dp))
                                .clickable { showDialog = false },
                                contentAlignment = Alignment.Center) {
                                Text("NO", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        // ★横向き + キーボード表示中の検知
        val isKeyboardVisible = WindowInsets.isImeVisible
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // ★クレイモーフィズム編集画面
        Column(modifier = Modifier.fillMaxSize().background(bgColor).statusBarsPadding()) {
            // ★横向き + キーボード表示中はヘッダーを丸ごと非表示（テキスト入力スペース確保）
            if (!isLandscape || !isKeyboardVisible) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 戻るボタン
                    Box(modifier = Modifier.size(48.dp)
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .background(paperColor, RoundedCornerShape(16.dp))
                        .clickable { if (hasChanges) showDialog = true else onCancel() },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = primaryColor, modifier = Modifier.size(22.dp))
                    }
                    // タイトル
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            if (initialPrompt == null) "New" else "Edit",
                            fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = textPrimary
                        )
                        Text(".", fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, color = primaryColor)
                    }
                    // 保存ボタン（グラデーション）
                    Box(modifier = Modifier.size(48.dp)
                        .shadow(4.dp, RoundedCornerShape(16.dp))
                        .background(gradient, RoundedCornerShape(16.dp))
                        .clickable { onSave(title, content) },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Save, "Save", tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }

            // 入力フィールド群（キーボード回避付き）
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ★Titleラベル：横向き時は非表示（スペース節約）
                if (!isLandscape) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Title", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textMuted, letterSpacing = 0.5.sp)
                }
                // タイトル入力
                Box(modifier = Modifier.fillMaxWidth()
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(paperColor, RoundedCornerShape(16.dp))
                ) {
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        placeholder = { Text("Enter title here...", color = textMuted) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor     = textPrimary,
                            unfocusedTextColor   = textPrimary
                        )
                    )
                }
                // ★Contentラベル：横向き時は非表示（スペース節約）
                if (!isLandscape) Text("Content", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = textMuted, letterSpacing = 0.5.sp)
                // コンテンツ入力（残りスペースを占有）
                Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 16.dp)
                    .shadow(4.dp, RoundedCornerShape(16.dp))
                    .background(paperColor, RoundedCornerShape(16.dp))
                ) {
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        placeholder = { Text("Enter prompt content here...", color = textMuted) },
                        modifier = Modifier.fillMaxSize(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor     = textPrimary,
                            unfocusedTextColor   = textPrimary
                        )
                    )
                }
            }
        }
    }
}
