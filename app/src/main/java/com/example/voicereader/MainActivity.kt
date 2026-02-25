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
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Book      // ★追加
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

        // 画面遷移ロジック
        when (currentScreen) {
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
                    onEditPrompt = { index: Int -> // 型を明示
                        editingPromptIndex = index
                        currentScreen = Screen.PromptEdit
                    },
                    onCreatePrompt = {
                        editingPromptIndex = -1
                        currentScreen = Screen.PromptEdit
                    },
                    onDeletePrompt = { index: Int -> // 型を明示
                        promptList.removeAt(index)
                        savePrompts()
                    },
                    onSelectPrompt = { prompt: PromptItem -> // 型を明示
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Prompt", prompt.content)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "プロンプトをコピーしました", Toast.LENGTH_SHORT).show()
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

            // ★辞書編集画面（新規追加）
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



    @OptIn(ExperimentalMaterial3Api::class)
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("読み上げ辞書") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    actions = {
                        IconButton(onClick = onCreateEntry) {
                            Icon(Icons.Default.Add, contentDescription = "新規作成")
                        }
                    }
                )
            }
        ) { padding ->
            if (dictionary.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "右上の＋ボタンから辞書を追加してください\n\n例:\nURL → ユーアールエル\nhttps:// → (削除)",
                        color = Color.Gray,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(dictionary) { index, entry ->
                        Card(
                            elevation = CardDefaults.cardElevation(4.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = entry.original,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                    Text(
                                        text = if (entry.replacement.isEmpty())
                                            "→ (読み上げない)"
                                        else
                                            "→ ${entry.replacement}",
                                        fontSize = 14.sp,
                                        color = if (entry.replacement.isEmpty())
                                            Color.Red
                                        else
                                            Color(0xFF4CAF50)
                                    )
                                }

                                // ON/OFFスイッチ
                                Switch(
                                    checked = entry.isEnabled,
                                    onCheckedChange = { onToggleEntry(index) },
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )

                                // 編集ボタン
                                IconButton(onClick = { onEditEntry(index) }) {
                                    Icon(Icons.Default.Edit, contentDescription = "編集", tint = Color.Blue)
                                }

                                // 削除ボタン
                                IconButton(onClick = { onDeleteEntry(index) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "削除", tint = Color.Red)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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
        Log.d("DictionaryEditScreen", "hasChanges: $hasChanges")

        BackHandler {
            if (hasChanges) {
                showDialog = true
                Log.d("DictionaryEditScreen", "BackHandler: showDialog = true")
            } else {
                onCancel()
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = {
                    showDialog = false
                    Log.d("DictionaryEditScreen", "AlertDialog dismiss: showDialog = false")
                },
                title = { Text("確認") },
                text = { Text("保存せずに終了しますか？") },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        Log.d("DictionaryEditScreen", "AlertDialog confirm: showDialog = false")
                        onCancel()
                    }) {
                        Text("はい")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDialog = false
                        Log.d("DictionaryEditScreen", "AlertDialog dismissButton: showDialog = false")
                    }) {
                        Text("いいえ")
                    }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (initialEntry == null) "新規辞書" else "辞書編集") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (hasChanges) {
                                showDialog = true
                                Log.d("DictionaryEditScreen", "navigationIcon: showDialog = true")
                            } else {
                                onCancel()
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onSave(original, replacement, isEnabled) }) {
                            Icon(Icons.Default.Save, contentDescription = "保存")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("元の単語（読み上げたくない単語）", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = original,
                    onValueChange = { original = it },
                    label = { Text("例: URL, https://") },
                    modifier = Modifier.fillMaxWidth().background(Color.Transparent),
                    singleLine = true,
                    colors = TextFieldDefaults.colors()
                )

                Text("置き換え後（空欄なら削除）", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text("例: ユーアールエル（空欄でも可）") },
                    modifier = Modifier.fillMaxWidth().background(Color.Transparent),
                    singleLine = true,
                    colors = TextFieldDefaults.colors()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("この辞書を有効にする", modifier = Modifier.weight(1f))
                    Switch(checked = isEnabled, onCheckedChange = { isEnabled = it })
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { onSave(original, replacement, isEnabled) },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    enabled = original.isNotBlank()
                ) {
                    Text("保存", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }


    @OptIn(ExperimentalMaterial3Api::class)
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

        // ★編集中のテキストを保持する変数（新規追加）
        var editingText by remember { mutableStateOf(text) }

        // ★textが変更されたら編集用テキストも更新
        LaunchedEffect(text) {
            if (!isEditMode) {
                editingText = text
            }
        }

        val listState = rememberLazyListState()
        val context = LocalContext.current
        val activity = context as? MainActivity
        val scope = rememberCoroutineScope()

        // 自動スクロール処理: 再生位置が変わったらスクロールする
        // ★修正：スクロール復帰の安定化
        // 画面遷移後にLazyColumnの準備が間に合わずスクロールが失敗する問題を
        // 少し遅延を入れることで解消する
        LaunchedEffect(currentSentenceIndex) {
            if (currentSentenceIndex >= 0 && currentSentenceIndex < sentences.size) {
                // LazyColumnの描画完了を待ってからスクロール実行
                kotlinx.coroutines.delay(100)
                listState.animateScrollToItem(currentSentenceIndex)
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

        // PDF / Word ファイルピッカー（両形式に対応）
        val docPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            uri?.let {
                scope.launch(Dispatchers.IO) {
                    // MIMEタイプを確認してPDFかWordか自動判定
                    val mimeType = context.contentResolver.getType(it) ?: ""
                    val text = when {
                        mimeType.contains("pdf") ->
                            extractTextFromPdf(context, it)
                        mimeType.contains("wordprocessingml") || mimeType.contains("msword") ->
                            extractTextFromDocx(context, it)
                        else -> ""
                    }
                    withContext(Dispatchers.Main) { if (text.isNotEmpty()) onUpdateText(text) }
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
                    currentSentenceIndex = -1
                }
                override fun onError(msg: String) {
                    isPlaying = false
                }
            })
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Voice Reader", fontWeight = FontWeight.Bold) },
                    actions = {
                        // ★編集/保存ボタンの切り替え
                        IconButton(
                            onClick = {
                                if (isEditMode) {
                                    // 保存処理：編集されたテキストを反映
                                    onUpdateText(editingText)
                                    isEditMode = false
                                    // ★修正：テキストが変わった可能性があるので先頭にリセット
                                    // 古いインデックスだと違う段落を指す危険があるため
                                    currentSentenceIndex = 0
                                } else {
                                    // ★修正：編集モードに入る前に再生を停止する
                                    // 編集中に読み上げが続くとユーザーが混乱するため
                                    if (isPlaying) {
                                        ttsService?.stop()
                                        isPlaying = false
                                    }
                                    // 編集モードに切り替え
                                    editingText = text
                                    isEditMode = true
                                }
                            }
                        ) {
                            Icon(
                                if (isEditMode) Icons.Default.Save else Icons.Default.Edit,
                                contentDescription = if (isEditMode) "保存" else "編集",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                // メインテキストエリア
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(500.dp) // 新しい高さ
                        .padding(16.dp)
                        .background(Color.White)
                        .border(1.dp, Color.LightGray)
                ) {
                    if (isEditMode) {
                        // ★編集モード：editingTextを編集
                        OutlinedTextField(
                            value = editingText,
                            onValueChange = { editingText = it },
                            modifier = Modifier.fillMaxWidth(), // fillMaxSize() から変更
                            minLines = 15, // 最小行数を15に設定
                            maxLines = 15, // 最大行数を15に設定
                            placeholder = { Text("テキストを編集...") }
                        )
                    } else {
                        // 表示モード（既存のコードそのまま）
                        if (text.isEmpty()) {
                            Text(
                                text = "「プロンプト」ボタンで指示をコピーし、\nPerplexityの結果をここに貼付してください。",
                                modifier = Modifier.padding(16.dp),
                                color = Color.Gray
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(16.dp)
                            ) {
                                itemsIndexed(sentences) { index, sentence ->
                                    Text(
                                        text = sentence,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                if (index == currentSentenceIndex && isPlaying)
                                                    Color(0xFFFFEB3B)
                                                else
                                                    Color.Transparent
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
                                        fontSize = 16.sp,
                                        lineHeight = 24.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // コントロール類
                if (!isEditMode) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        // 速度
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("速度 ${String.format(Locale.US, "%.1f", speechRate)}x", modifier = Modifier.weight(1f))
                            Slider(
                                value = speechRate,
                                onValueChange = {
                                    speechRate = it
                                    ttsService?.setSpeechRate(it)
                                },
                                valueRange = 0.5f..3.0f,
                                modifier = Modifier.weight(2f)
                            )
                        }
                        // ピッチ
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("ピッチ ${String.format(Locale.US, "%.1f", pitch)}x", modifier = Modifier.weight(1f))
                            Slider(
                                value = pitch,
                                onValueChange = {
                                    pitch = it
                                    ttsService?.setPitch(it)
                                },
                                valueRange = 0.5f..2.0f,
                                modifier = Modifier.weight(2f)
                            )
                        }
                    }

                    // ボタンエリア
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 再生ボタン
                        Button(
                            onClick = {
                                if (isPlaying) {
                                    ttsService?.stop()
                                    isPlaying = false
                                } else {
                                    if (sentences.isNotEmpty()) {
                                        // ★修正：停止した位置から再開する
                                        // currentSentenceIndexが範囲外（読了後など）なら先頭に戻す
                                        val startFrom = if (currentSentenceIndex in sentences.indices) {
                                            currentSentenceIndex
                                        } else {
                                            0
                                        }
                                        ttsService?.setSpeechRate(speechRate)
                                        ttsService?.setPitch(pitch)
                                        setupTtsListener()
                                        ttsService?.speakList(sentences, startFrom)

                                        currentSentenceIndex = startFrom
                                        isPlaying = true
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isPlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        ) {
                            Text(if (isPlaying) "停止" else "再生")
                        }

                        // PDF / Word ボタン（両形式のファイルピッカーを起動）
                        Button(
                            onClick = {
                                docPickerLauncher.launch(arrayOf(
                                    "application/pdf",
                                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                                ))
                            },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("PDF/Word", fontSize = 11.sp)
                        }

                        // 貼付ボタン
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clipData = clipboard.primaryClip
                                if (clipData != null && clipData.itemCount > 0) {
                                    val pasted = clipData.getItemAt(0).text.toString()
                                    onUpdateText(pasted)
                                }
                            },
                            modifier = Modifier.weight(1f).height(56.dp)
                        ) {
                            Text("貼付", fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // プロンプト画面へ遷移ボタン
                        Button(
                            // ★修正：画面遷移前に再生を停止する
                            onClick = {
                                if (isPlaying) {
                                    ttsService?.stop()
                                    isPlaying = false
                                }
                                onNavigateToPrompts()
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text("Ｐ管理", fontSize = 10.sp)
                            }
                        }

                        // ★辞書ボタン（ここに追加）
                        Button(
                            // ★修正：画面遷移前に再生を停止する
                            onClick = {
                                if (isPlaying) {
                                    ttsService?.stop()
                                    isPlaying = false
                                }
                                onNavigateToDictionary()
                            },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Book, contentDescription = null, modifier = Modifier.size(20.dp))
                                Text("辞書", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("プロンプト一覧") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    actions = {
                        IconButton(onClick = onCreatePrompt) {
                            Icon(Icons.Default.Add, contentDescription = "新規作成")
                        }
                    }
                )
            }
        ) { padding ->
            if (prompts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("右上の＋ボタンからプロンプトを追加してください", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(prompts) { index, prompt ->
                        Card(
                            elevation = CardDefaults.cardElevation(4.dp),
                            modifier = Modifier.fillMaxWidth().clickable { onSelectPrompt(prompt) }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(prompt.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Text(
                                        text = if (prompt.content.length > 30) prompt.content.take(30) + "..." else prompt.content,
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                                Row {
                                    IconButton(onClick = { onEditPrompt(index) }) {
                                        Icon(Icons.Default.Edit, contentDescription = "編集", tint = Color.Blue)
                                    }
                                    IconButton(onClick = { onDeletePrompt(index) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "削除", tint = Color.Red)
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

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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
        Log.d("PromptEditScreen", "hasChanges: $hasChanges")

        BackHandler {
            if (hasChanges) {
                showDialog = true
                Log.d("PromptEditScreen", "BackHandler: showDialog = true")
            } else {
                onCancel()
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = {
                    showDialog = false
                    Log.d("PromptEditScreen", "AlertDialog dismiss: showDialog = false")
                },
                title = { Text("確認") },
                text = { Text("保存せずに終了しますか？") },
                confirmButton = {
                    TextButton(onClick = {
                        showDialog = false
                        Log.d("PromptEditScreen", "AlertDialog confirm: showDialog = false")
                        onCancel()
                    }) {
                        Text("はい")
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDialog = false
                        Log.d("PromptEditScreen", "AlertDialog dismissButton: showDialog = false")
                    }) {
                        Text("いいえ")
                    }
                }
            )
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (initialPrompt == null) "新規プロンプト" else "プロンプト編集") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (hasChanges) {
                                showDialog = true
                                Log.d("PromptEditScreen", "navigationIcon: showDialog = true")
                            } else {
                                onCancel()
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "戻る")
                        }
                    },
                    actions = {
                        IconButton(onClick = { onSave(title, content) }) {
                            Icon(Icons.Default.Save, contentDescription = "保存")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("タイトル") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Text("プロンプト内容", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).background(Color.Transparent), // ここを修正
                    placeholder = { Text("ここに長いプロンプトを入力...") },
                    colors = TextFieldDefaults.colors()
                )

                Button(
                    onClick = { onSave(title, content) },
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                ) {
                    Text("保存", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}