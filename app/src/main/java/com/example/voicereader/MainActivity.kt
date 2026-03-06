package com.example.voicereader

import android.Manifest
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

        // PDFBox初期化
        try {
            PDFBoxResourceLoader.init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Service開始
        Intent(this, TtsService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
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
            // ★辞書を適用（新規追加）
            val dictionaryApplied = TextProcessor.applyDictionary(cleaned, dictionaryList)
            val perplexityCleaned = TextProcessor.cleanPerplexityText(dictionaryApplied)

            mainText = perplexityCleaned
            sentences = TextProcessor.splitSentences(perplexityCleaned)
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

        // ★削除確認ダイアログ用状態
        var showDeleteDialog by remember { mutableStateOf(false) }
        var deleteTargetIndex by remember { mutableIntStateOf(-1) }

        // ★削除確認ダイアログ（他のダイアログと同じネオブルータリストスタイル）
        if (showDeleteDialog) {
            Dialog(onDismissRequest = { showDeleteDialog = false }) {
                Box(modifier = Modifier.wrapContentHeight().padding(end = 4.dp, bottom = 4.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 4.dp, y = 4.dp).background(Color.Black, RoundedCornerShape(16.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(4.dp, Color.Black)) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Delete this entry?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("This action cannot be undone.", fontSize = 14.sp, color = Color.Gray)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // YES（控えめ：白背景 + 黒枠）
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.White, RoundedCornerShape(8.dp)).clickable { showDeleteDialog = false; onDeleteEntry(deleteTargetIndex) }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("YES", fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                                // NO（目立つ：黒背景 + 白文字）
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color(0xFF444444), RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.Black, RoundedCornerShape(8.dp)).clickable { showDeleteDialog = false }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("NO", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ★ネオブルータリストデザイン
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F0)).statusBarsPadding()) {  // ★ステータスバーの高さ分だけ上にパディング
            Column {
                Row(modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(42.dp)) {
                        Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White, CircleShape).border(3.dp, Color.Black, CircleShape).clickable { onNavigateBack() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp), tint = Color.Black)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("DICTIONARY", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f), color = Color.Black)
                    Box(modifier = Modifier.size(42.dp)) {
                        Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                        Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFFFF9C4), CircleShape).border(3.dp, Color.Black, CircleShape).clickable { onCreateEntry() }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(20.dp), tint = Color.Black)
                        }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.Black))
            }

            if (dictionary.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tap + to add an entry\n\nExample:\nURL → pronounced spelling\nhttps:// → (leave blank to skip)", color = Color.Gray, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(dictionary) { index, entry ->
                        Box(modifier = Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
                            Box(modifier = Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(3.dp, Color.Black)) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = entry.original, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(
                                            text = if (entry.replacement.isEmpty()) "→ (skip)" else "→ ${entry.replacement}",
                                            fontSize = 14.sp,
                                            color = if (entry.replacement.isEmpty()) Color.Red else Color(0xFF4CAF50)
                                        )
                                    }
                                    // ON/OFFスイッチ
                                    Switch(checked = entry.isEnabled, onCheckedChange = { onToggleEntry(index) }, modifier = Modifier.padding(horizontal = 4.dp),
                                        colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF0DF259), checkedTrackColor = Color.Black, uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    // 編集ボタン（大きく：押し間違い防止）
                                    Box(modifier = Modifier.size(52.dp)) {
                                        Box(modifier = Modifier.size(50.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                                        Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFE5F1FF), CircleShape).border(2.dp, Color.Black, CircleShape).clickable { onEditEntry(index) }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(26.dp), tint = Color.Black)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    // 削除ボタン（大きく・確認ダイアログ付き）
                                    Box(modifier = Modifier.size(52.dp)) {
                                        Box(modifier = Modifier.size(50.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                                        Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFFFE5E5), CircleShape).border(2.dp, Color.Black, CircleShape).clickable { deleteTargetIndex = index; showDeleteDialog = true }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(26.dp), tint = Color.Black)
                                        }
                                    }
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

        BackHandler {
            if (hasChanges) showDialog = true else onCancel()
        }

        // ★⑤ネオブルータリスト未保存ダイアログ
        if (showDialog) {
            Dialog(onDismissRequest = { showDialog = false }) {
                Box(modifier = Modifier.wrapContentHeight().padding(end = 4.dp, bottom = 4.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 4.dp, y = 4.dp).background(Color.Black, RoundedCornerShape(16.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(4.dp, Color.Black)) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Leave without saving?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("Your changes will be lost.", fontSize = 14.sp, color = Color.Gray)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.White, RoundedCornerShape(8.dp)).clickable { showDialog = false; onCancel() }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("YES", fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color(0xFF444444), RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.Black, RoundedCornerShape(8.dp)).clickable { showDialog = false }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("NO", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ★横向き + キーボード表示中の検知
        val isKeyboardVisible = WindowInsets.isImeVisible
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // ★③ネオブルータリスト編集画面
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F0)).statusBarsPadding()) {  // ★ステータスバーの高さ分だけ上にパディング
            // ★横向き + キーボード表示中はヘッダーを丸ごと非表示（テキスト入力スペース確保）
            if (!isLandscape || !isKeyboardVisible) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(42.dp)) {
                            Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White, CircleShape).border(3.dp, Color.Black, CircleShape).clickable { if (hasChanges) showDialog = true else onCancel() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp), tint = Color.Black)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (initialEntry == null) "NEW ENTRY" else "EDIT ENTRY", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f), color = Color.Black)
                        Box(modifier = Modifier.size(42.dp)) {
                            Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE8F5E9), CircleShape).border(3.dp, Color.Black, CircleShape).clickable { if (original.isNotBlank()) onSave(original, replacement, isEnabled) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(20.dp), tint = Color.Black)
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.Black))
                }
            }

            // ★キーボード回避付き入力フィールド群
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Word to replace", fontWeight = FontWeight.Bold, color = Color.Black)
                Box(modifier = Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(3.dp, Color.Black)) {
                        OutlinedTextField(value = original, onValueChange = { original = it }, placeholder = { Text("e.g. URL, https://") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black))
                    }
                }

                Text("Replacement (blank = skip)", fontWeight = FontWeight.Bold, color = Color.Black)
                Box(modifier = Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(3.dp, Color.Black)) {
                        OutlinedTextField(value = replacement, onValueChange = { replacement = it }, placeholder = { Text("e.g. pronounced spelling (or blank)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black))
                    }
                }

                // ON/OFFスイッチ行（ネオブルータリスト風）
                Box(modifier = Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(3.dp, Color.Black)) {
                        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Enable this entry", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            Switch(checked = isEnabled, onCheckedChange = { isEnabled = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color(0xFF0DF259), checkedTrackColor = Color.Black, uncheckedThumbColor = Color.White, uncheckedTrackColor = Color(0xFF444444))
                            )
                        }
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

        // ★isEditMode時のバック確認ダイアログ表示フラグ
        var showUnsavedDialog by remember { mutableStateOf(false) }

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
        val scope = rememberCoroutineScope()

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
                }
                override fun onComplete() {
                    isPlaying = false
                    // ★修正：読了後も先頭をグレーハイライトで示す（-1だとハイライトが消えてしまうため0に変更）
                    currentSentenceIndex = 0
                }
                override fun onError(msg: String) {
                    isPlaying = false
                }
            })
        }

        // ★キーボード（IME）が表示中かどうか検知（横向き編集時のスペース確保のため）
        val isKeyboardVisible = WindowInsets.isImeVisible
        // ★横向きかどうか検知（横向き時に一部UIを非表示にするため）
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // ★ネオブルータリストデザイン（太い黒ボーダー + ハードオフセットシャドウ）
        Scaffold(
            bottomBar = {
                // ★広告エリアのプレースホルダー（キーボード表示中は非表示にしてテキスト編集スペースを確保）
                if (!isKeyboardVisible) Column {
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.Black))
                    Box(
                        modifier = Modifier.fillMaxWidth().background(Color(0xFFF0F0F0)).padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.75f)
                                .border(2.dp, Color(0xFFBBBBBB), RoundedCornerShape(8.dp))
                                .padding(vertical = 6.dp, horizontal = 16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("ADVERTISEMENT", fontSize = 8.sp, color = Color(0xFFAAAAAA), fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        }
                    }
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {  // ★キーボードせり上がり対応
                // ★テキストカード（太い黒ボーダー + 右下4dpオフセットシャドウ）
                // オフセットシャドウの仕組み：黒いBoxを4dp右下にずらして後ろに置くことで再現
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
                ) {
                    // シャドウ層（黒、4dp右下オフセット）
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(x = 4.dp, y = 4.dp)
                            .background(Color.Black, RoundedCornerShape(16.dp))
                    )
                    // 本体カード（白地 + 4dp黒ボーダー）
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black),
                        elevation = CardDefaults.cardElevation(0.dp),
                        border = BorderStroke(4.dp, Color.Black)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            if (isEditMode) {
                                OutlinedTextField(
                                    value = editingText,
                                    onValueChange = { editingText = it },
                                    // ★横向き時はtopではなくbottomにpadding（ボタンが右下に移動するため）
                                    modifier = Modifier.fillMaxSize().padding(
                                        top = if (isLandscape) 4.dp else 56.dp,
                                        bottom = if (isLandscape && !isKeyboardVisible) 56.dp else 4.dp
                                    ),
                                    placeholder = { Text("Edit text here...") },
                                    // ★②カードに枠があるので、TextField自体の枠線は透明にして「謎の線」を消す
                                    // ★ダークモード時も文字を黒固定にする
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = Color.Transparent,
                                        unfocusedBorderColor = Color.Transparent,
                                        focusedTextColor = Color.Black,
                                        unfocusedTextColor = Color.Black
                                    )
                                )
                            } else {
                                if (text.isEmpty()) {
                                    Text(
                                        text = "Copy a prompt with the Prompts button,\nthen paste the Perplexity result here.",
                                        modifier = Modifier.padding(16.dp).padding(
                                            top = if (isLandscape) 4.dp else 56.dp
                                        ),
                                        color = Color.Gray
                                    )
                                } else {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize().padding(16.dp).padding(
                                            top = if (isLandscape) 4.dp else 40.dp,
                                            bottom = if (isLandscape && !isKeyboardVisible) 48.dp else 0.dp
                                        )
                                    ) {
                                        itemsIndexed(sentences) { index, sentence ->
                                            Text(
                                                text = sentence,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        when {
                                                            index == currentSentenceIndex && isPlaying ->
                                                                Color(0xFFFFF112) // 黄色：読み上げ中
                                                            index == currentSentenceIndex && !isPlaying &&
                                                                currentSentenceIndex in sentences.indices ->
                                                                Color(0xFFE0E0E0)
                                                            else -> Color.Transparent
                                                        }
                                                    )
                                                    .clickable {
                                                        ttsService?.setSpeechRate(speechRate)
                                                        ttsService?.setPitch(pitch)
                                                        setupTtsListener()
                                                        ttsService?.speakList(sentences, index)
                                                        currentSentenceIndex = index
                                                        isPlaying = true
                                                    }
                                                    .padding(vertical = 4.dp),
                                                fontSize = fontSize.sp,
                                                lineHeight = (fontSize * 1.5f).sp
                                            )
                                        }
                                    }
                                }
                            }
                            // ★鉛筆ボタン（縦:右上固定 / 横:キーボードなし時のみ右下に表示）
                            if (!isLandscape || !isKeyboardVisible) {
                            Box(
                                modifier = Modifier
                                    .align(if (isLandscape) Alignment.BottomEnd else Alignment.TopEnd)
                                    .padding(
                                        top = if (!isLandscape) 8.dp else 0.dp,
                                        bottom = if (isLandscape) 8.dp else 0.dp,
                                        end = 8.dp
                                    )
                            ) {
                                Box(modifier = Modifier.size(48.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                                Box(
                                    modifier = Modifier.size(48.dp)
                                        .clip(CircleShape)
                                        .background(Color.White, CircleShape)
                                        .border(2.dp, Color.Black, CircleShape)
                                        .clickable {
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
                                        tint = Color.Black, modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            } // if (!isLandscape || !isKeyboardVisible)
                        }
                    }
                }

                // コントロール類（編集モード時は非表示）
                if (!isEditMode) {
                    // 進行度バー（横向き時は非表示）
                    val needsScroll = listState.canScrollForward || listState.canScrollBackward
                    if (needsScroll && !isLandscape) {
                        val readProgress = if (sentences.size > 1) {
                            (currentSentenceIndex.toFloat() / (sentences.size - 1).toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        val animatedProgress by animateFloatAsState(
                            targetValue = readProgress, animationSpec = tween(durationMillis = 300), label = "readProgress"
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // ★カスタム進行バー（黒トラック + 緑フィル・3dp統一）
                            Box(modifier = Modifier.weight(1f).height(3.dp).background(Color(0xFF444444), RoundedCornerShape(50))) {
                                Box(modifier = Modifier.fillMaxWidth(animatedProgress).fillMaxHeight().background(Color(0xFF0DF259), RoundedCornerShape(50)))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("${(animatedProgress * 100).toInt()}%", fontSize = 12.sp, color = Color.Gray)
                        }
                    }

                    // ★SPEEDスライダー（黒トラック + 緑サム：HTMLのカスタムスライダーに近づける）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("SPEED", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), color = Color.Black)
                        // ★カスタムスライダー（3dp細トラック + 緑サム）
                        Slider(
                            value = speechRate,
                            onValueChange = { speechRate = it; ttsService?.setSpeechRate(it) },
                            valueRange = 0.5f..3.0f,
                            modifier = Modifier.weight(1f),
                            thumb = {
                                Box(modifier = Modifier.size(14.dp).background(Color(0xFF0DF259), CircleShape).border(2.dp, Color.Black, CircleShape))
                            },
                            track = { sliderState ->
                                val fraction = ((sliderState.value - 0.5f) / (3.0f - 0.5f)).coerceIn(0f, 1f)
                                Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF444444), RoundedCornerShape(50))) {
                                    Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF0DF259), RoundedCornerShape(50)))
                                }
                            }
                        )
                        Text("${String.format(Locale.US, "%.1f", speechRate)}x", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), color = Color.Black)
                    }

                    // ★PITCHスライダー（黒トラック + 緑サム）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("PITCH", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp), color = Color.Black)
                        Slider(
                            value = pitch,
                            onValueChange = { pitch = it; ttsService?.setPitch(it) },
                            valueRange = 0.5f..2.0f,
                            modifier = Modifier.weight(1f),
                            thumb = {
                                Box(modifier = Modifier.size(14.dp).background(Color(0xFF0DF259), CircleShape).border(2.dp, Color.Black, CircleShape))
                            },
                            track = { sliderState ->
                                val fraction = ((sliderState.value - 0.5f) / (2.0f - 0.5f)).coerceIn(0f, 1f)
                                Box(modifier = Modifier.fillMaxWidth().height(3.dp).background(Color(0xFF444444), RoundedCornerShape(50))) {
                                    Box(modifier = Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF0DF259), RoundedCornerShape(50)))
                                }
                            }
                        )
                        Text(
                            when { pitch < 0.85f -> "Low"; pitch < 1.3f -> "Mid"; else -> "High" },
                            fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(36.dp), color = Color.Black
                        )
                    }

                    // ★5つのアイコンボタン行（黒ボーダー + 2dpオフセットシャドウ）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 共通構造：黒シャドウBoxを後ろに置き、その上に色付きボタンBoxを重ねる
                        // 1. 貼り付け（ピンク #FFE5E5）
                        Box(modifier = Modifier.weight(1f)) {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                            Box(
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                                    .background(Color(0xFFFFE5E5), RoundedCornerShape(12.dp))
                                    .border(3.dp, Color.Black, RoundedCornerShape(12.dp))
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipData = clipboard.primaryClip
                                        if (clipData != null && clipData.itemCount > 0) onUpdateText(clipData.getItemAt(0).text.toString())
                                    },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(28.dp), tint = Color.Black) }
                        }
                        // 2. ファイル（ライトブルー #E5F1FF）
                        Box(modifier = Modifier.weight(1f)) {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                            Box(
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                                    .background(Color(0xFFE5F1FF), RoundedCornerShape(12.dp))
                                    .border(3.dp, Color.Black, RoundedCornerShape(12.dp))
                                    .clickable {
                                        docPickerLauncher.launch(arrayOf(
                                            "application/pdf",
                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                            "application/vnd.google-apps.document",
                                            "text/html", "text/plain"
                                        ))
                                    },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.FolderOpen, contentDescription = "Open File", modifier = Modifier.size(28.dp), tint = Color.Black) }
                        }
                        // 3. プロンプト（黄色 #FFF9C4）
                        Box(modifier = Modifier.weight(1f)) {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                            Box(
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                                    .background(Color(0xFFFFF9C4), RoundedCornerShape(12.dp))
                                    .border(3.dp, Color.Black, RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (isPlaying) { ttsService?.stop(); isPlaying = false }
                                        onNavigateToPrompts()
                                    },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.Assistant, contentDescription = "Prompts", modifier = Modifier.size(28.dp), tint = Color.Black) }
                        }
                        // 4. 辞書（ライトグリーン #E8F5E9）
                        Box(modifier = Modifier.weight(1f)) {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                            Box(
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                                    .background(Color(0xFFE8F5E9), RoundedCornerShape(12.dp))
                                    .border(3.dp, Color.Black, RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (isPlaying) { ttsService?.stop(); isPlaying = false }
                                        onNavigateToDictionary()
                                    },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.MenuBook, contentDescription = "Dictionary", modifier = Modifier.size(28.dp), tint = Color.Black) }
                        }
                        // 5. フォントサイズ（ライトパープル #F3E5F5）
                        Box(modifier = Modifier.weight(1f)) {
                            Box(modifier = Modifier.fillMaxWidth().height(56.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                            Box(
                                modifier = Modifier.fillMaxWidth().height(56.dp)
                                    .background(Color(0xFFF3E5F5), RoundedCornerShape(12.dp))
                                    .border(3.dp, Color.Black, RoundedCornerShape(12.dp))
                                    .clickable { fontSize = when (fontSize) { 13f -> 16f; 16f -> 20f; else -> 13f } },
                                contentAlignment = Alignment.Center
                            ) {
                                // ★lineHeightをfontSizeと揃えてText上下の余白をゼロに近づけ、センターに見えるよう修正
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Aa", fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 16.sp, color = Color.Black)
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(when (fontSize) { 13f -> "S"; 16f -> "M"; else -> "L" }, fontSize = 10.sp, lineHeight = 10.sp, color = Color.Black)
                                }
                            }
                        }
                    }

                    // ★輸送コントロール（◄| 楕円再生ボタン |►）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // ◄| 前の段落（丸・黒ボーダー + 2dpシャドウ）
                        Box(modifier = Modifier.size(50.dp)) {
                            Box(modifier = Modifier.size(48.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                            Box(
                                modifier = Modifier.size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White, CircleShape)
                                    .border(3.dp, Color.Black, CircleShape)
                                    .clickable {
                                        val prev = maxOf(0, currentSentenceIndex - 1)
                                        currentSentenceIndex = prev
                                        if (isPlaying) {
                                            ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                            setupTtsListener(); ttsService?.speakList(sentences, prev)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(28.dp), tint = Color.Black) }
                        }

                        Spacer(modifier = Modifier.width(24.dp))

                        // ★再生/停止ボタン（大きな楕円 + 黒ボーダー + 4dpシャドウ）
                        // 外側Boxで影の分のスペースを確保してからシャドウとボタンを重ねる
                        Box(modifier = Modifier.size(144.dp, 60.dp)) {
                            Box(modifier = Modifier.size(140.dp, 56.dp).offset(x = 4.dp, y = 4.dp).background(Color.Black, RoundedCornerShape(50)))
                            Button(
                                onClick = {
                                    if (isPlaying) {
                                        ttsService?.stop(); isPlaying = false
                                    } else {
                                        if (sentences.isNotEmpty()) {
                                            val startFrom = if (currentSentenceIndex in sentences.indices) currentSentenceIndex else 0
                                            ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                            setupTtsListener(); ttsService?.speakList(sentences, startFrom)
                                            currentSentenceIndex = startFrom; isPlaying = true
                                        }
                                    }
                                },
                                modifier = Modifier.size(140.dp, 56.dp),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(4.dp, Color.Black),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isPlaying) MaterialTheme.colorScheme.error else Color(0xFF3B82F6)
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Stop" else "Play",
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(24.dp))

                        // |► 次の段落（丸・黒ボーダー + 2dpシャドウ）
                        Box(modifier = Modifier.size(50.dp)) {
                            Box(modifier = Modifier.size(48.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                            Box(
                                modifier = Modifier.size(48.dp)
                                    .clip(CircleShape)
                                    .background(Color.White, CircleShape)
                                    .border(3.dp, Color.Black, CircleShape)
                                    .clickable {
                                        val next = minOf(sentences.size - 1, currentSentenceIndex + 1)
                                        currentSentenceIndex = next
                                        if (isPlaying) {
                                            ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                            setupTtsListener(); ttsService?.speakList(sentences, next)
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) { Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(28.dp), tint = Color.Black) }
                        }
                    }
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

        // ★削除確認ダイアログ用状態
        var showDeleteDialog by remember { mutableStateOf(false) }
        var deleteTargetIndex by remember { mutableIntStateOf(-1) }

        // ★削除確認ダイアログ（他のダイアログと同じネオブルータリストスタイル）
        if (showDeleteDialog) {
            Dialog(onDismissRequest = { showDeleteDialog = false }) {
                Box(modifier = Modifier.wrapContentHeight().padding(end = 4.dp, bottom = 4.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 4.dp, y = 4.dp).background(Color.Black, RoundedCornerShape(16.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(4.dp, Color.Black)) {
                        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Delete this prompt?", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("This action cannot be undone.", fontSize = 14.sp, color = Color.Gray)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                // YES（控えめ：白背景 + 黒枠）
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color.Black, RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.White, RoundedCornerShape(8.dp)).clickable { showDeleteDialog = false; onDeletePrompt(deleteTargetIndex) }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("YES", fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                                // NO（目立つ：黒背景 + 白文字）
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color(0xFF444444), RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.Black, RoundedCornerShape(8.dp)).clickable { showDeleteDialog = false }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("NO", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ★ネオブルータリストデザイン：TopAppBarを廃止し、手動でヘッダーを描く
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F0)).statusBarsPadding()) {  // ★ステータスバーの高さ分だけ上にパディング
            // ヘッダー行（黒枠 + 4dp黒ライン区切り）
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 戻るボタン（丸・黒枠 + 2dpシャドウ）
                    Box(modifier = Modifier.size(42.dp)) {
                        Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White, CircleShape).border(3.dp, Color.Black, CircleShape).clickable { onNavigateBack() },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp), tint = Color.Black) }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("PROMPTS", fontWeight = FontWeight.Bold, fontSize = 22.sp, modifier = Modifier.weight(1f), color = Color.Black)
                    // 追加ボタン（丸・黄背景 + 黒枠 + 2dpシャドウ）
                    Box(modifier = Modifier.size(42.dp)) {
                        Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFFFF9C4), CircleShape).border(3.dp, Color.Black, CircleShape).clickable { onCreatePrompt() },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(20.dp), tint = Color.Black) }
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.Black))
            }

            // コンテンツ
            if (prompts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Tap + to add a prompt", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(prompts) { index, prompt ->
                        // ★各カード：黒枠 + 右下3dpシャドウ
                        Box(modifier = Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
                            Box(modifier = Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { onSelectPrompt(prompt) },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black),
                                elevation = CardDefaults.cardElevation(0.dp),
                                border = BorderStroke(3.dp, Color.Black)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(prompt.title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        Text(
                                            text = if (prompt.content.length > 40) prompt.content.take(40) + "..." else prompt.content,
                                            fontSize = 12.sp, color = Color.Gray
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    // 編集ボタン（青背景・大きく：押し間違い防止）
                                    Box(modifier = Modifier.size(52.dp)) {
                                        Box(modifier = Modifier.size(50.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                                        Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFE5F1FF), CircleShape).border(2.dp, Color.Black, CircleShape).clickable { onEditPrompt(index) }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", modifier = Modifier.size(26.dp), tint = Color.Black)
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    // 削除ボタン（ピンク背景・大きく・確認ダイアログ付き）
                                    Box(modifier = Modifier.size(52.dp)) {
                                        Box(modifier = Modifier.size(50.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                                        Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Color(0xFFFFE5E5), CircleShape).border(2.dp, Color.Black, CircleShape).clickable { deleteTargetIndex = index; showDeleteDialog = true }, contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(26.dp), tint = Color.Black)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun cleanPdfLineBreaks(text: String): String {
        // 句読点の後の改行は保持、それ以外の改行は削除
        return text
            .replace(Regex("(?<![。！?\\n])\\n(?![。!?\\n])"), "") // 文中の改行を削除
            .replace(Regex("\\n{3,}"), "\n\n") // 3個以上連続する改行は2個に
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

        BackHandler {
            if (hasChanges) showDialog = true else onCancel()
        }

        // ★⑤ネオブルータリスト未保存ダイアログ
        if (showDialog) {
            Dialog(onDismissRequest = { showDialog = false }) {
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
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.White, RoundedCornerShape(8.dp)).clickable { showDialog = false; onCancel() }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("YES", fontWeight = FontWeight.Bold, color = Color.Black)
                                    }
                                }
                                // NO（目立つ：黒背景 + 白文字）
                                Box(modifier = Modifier.weight(1f).padding(end = 2.dp, bottom = 2.dp)) {
                                    Box(modifier = Modifier.matchParentSize().offset(x = 2.dp, y = 2.dp).background(Color(0xFF444444), RoundedCornerShape(8.dp)))
                                    Box(modifier = Modifier.fillMaxWidth().border(2.dp, Color.Black, RoundedCornerShape(8.dp)).background(Color.Black, RoundedCornerShape(8.dp)).clickable { showDialog = false }.padding(12.dp), contentAlignment = Alignment.Center) {
                                        Text("NO", fontWeight = FontWeight.Bold, color = Color.White)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ★横向き + キーボード表示中の検知
        val isKeyboardVisible = WindowInsets.isImeVisible
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

        // ★③ネオブルータリスト編集画面（TopAppBar廃止）
        Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F0)).statusBarsPadding()) {  // ★ステータスバーの高さ分だけ上にパディング
            // ★横向き + キーボード表示中はヘッダーを丸ごと非表示（テキスト入力スペース確保）
            if (!isLandscape || !isKeyboardVisible) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(42.dp)) {
                            Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color.White, CircleShape).border(3.dp, Color.Black, CircleShape).clickable { if (hasChanges) showDialog = true else onCancel() }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp), tint = Color.Black)
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(if (initialPrompt == null) "NEW PROMPT" else "EDIT PROMPT", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f), color = Color.Black)
                        Box(modifier = Modifier.size(42.dp)) {
                            Box(modifier = Modifier.size(40.dp).offset(x = 2.dp, y = 2.dp).background(Color.Black, CircleShape))
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFE8F5E9), CircleShape).border(3.dp, Color.Black, CircleShape).clickable { onSave(title, content) }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Save, contentDescription = "Save", modifier = Modifier.size(20.dp), tint = Color.Black)
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.Black))
                }
            } // if (!isLandscape || !isKeyboardVisible)

            // 入力フィールド群（キーボード回避付き）
            Column(modifier = Modifier.fillMaxSize().padding(16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ★②Titleラベルをカードの外（上）に配置し、枠との重なりを解消
                Text("Title", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                Box(modifier = Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(3.dp, Color.Black)) {
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            placeholder = { Text("Enter title here...") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            // ★ダークモード時も文字を黒固定にする
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black)
                        )
                    }
                }
                Text("Content", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Black)
                // コンテンツ入力（残りスペースを占有）
                Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(end = 3.dp, bottom = 3.dp)) {
                    Box(modifier = Modifier.matchParentSize().offset(x = 3.dp, y = 3.dp).background(Color.Black, RoundedCornerShape(12.dp)))
                    Card(modifier = Modifier.fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color.Black), elevation = CardDefaults.cardElevation(0.dp), border = BorderStroke(3.dp, Color.Black)) {
                        OutlinedTextField(
                            value = content, onValueChange = { content = it },
                            modifier = Modifier.fillMaxSize(), placeholder = { Text("Enter prompt content here...") },
                            // ★ダークモード時も文字を黒固定にする
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent, focusedTextColor = Color.Black, unfocusedTextColor = Color.Black)
                        )
                    }
                }
            }
        }
    }
}