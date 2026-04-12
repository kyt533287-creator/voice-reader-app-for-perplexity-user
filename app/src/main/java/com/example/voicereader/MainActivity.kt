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
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.layout.onGloballyPositioned     // 描画後の実サイズ・位置を取得
import androidx.compose.ui.layout.boundsInParent            // 親要素内での座標取得
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
    // デバッグビルド時は1分、リリースビルド時は45分で広告を表示
    private val AD_TIME_THRESHOLD_MS = if (BuildConfig.DEBUG) 1L * 60 * 1000 else 45L * 60 * 1000
    private var playStartTime = 0L                        // 再生を開始した時刻（ミリ秒）

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

        // UMP → AdMob の順に初期化（GDPR対応。EU圏ユーザーには同意画面を表示）
        // EU圏以外（日本など）は即スルーしてAdMobが初期化される
        val consentInformation = UserMessagingPlatform.getConsentInformation(this)

        val params = ConsentRequestParameters.Builder().build()

        consentInformation.requestConsentInfoUpdate(this, params,
            {
                // 同意情報の更新に成功 → フォームが必要なら表示（EU圏のみ）
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(this) { _ ->
                    // フォーム完了（または不要）→ 広告を出せる状態か確認してAdMob初期化
                    if (consentInformation.canRequestAds()) {
                        initMobileAds()
                    }
                }
            },
            { _ ->
                // 更新失敗（オフライン等）→ キャッシュ済み同意状態で判断
                if (consentInformation.canRequestAds()) {
                    initMobileAds()
                }
            }
        )

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
        // context・sessionFile は mainText の初期化より先に宣言する必要がある
        val context = LocalContext.current
        // 前回セッションのテキストを保存するファイル（内部ストレージ：テキストが巨大になるためファイルを使用）
        val sessionFile = remember { java.io.File(context.filesDir, "last_session.txt") }

        // 現在の画面管理
        var currentScreen by remember { mutableStateOf(Screen.Main) }

        // メイン画面の状態（前回セッションのテキストがあれば復元）
        var mainText by remember {
            mutableStateOf(if (sessionFile.exists()) sessionFile.readText() else "")
        }
        var sentences by remember { mutableStateOf<List<String>>(emptyList()) }

        // 編集対象のプロンプトインデックス (-1は新規)
        var editingPromptIndex by remember { mutableIntStateOf(-1) }

        // ★辞書用の状態変数を追加（プロンプトリストの下）
        var editingDictionaryIndex by remember { mutableIntStateOf(-1) }

        val prefs = remember { context.getSharedPreferences("prompts_prefs", Context.MODE_PRIVATE) }
        val scope = rememberCoroutineScope()
        // テキスト処理中フラグ（巨大ファイルの場合、処理に数秒かかるためUI側でローディング表示に使う）
        var isTextProcessing by remember { mutableStateOf(false) }

        // 初回起動同意ダイアログ：未同意なら true（ダイアログ表示）、同意済みなら false（スルー）
        val ttsPrefs = remember { context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE) }
        var showConsentDialog by remember {
            mutableStateOf(!ttsPrefs.getBoolean("first_launch_agreed", false))
        }

        // デフォルトプロンプトのバージョン番号
        // ★デフォルト内容を変えたらこの数値を +1 すること
        // 理由：バージョンアップ・バックアップ復元後も [Example] プロンプトを最新に保つため
        val PROMPT_DEFAULTS_VERSION = 2

        // データ読み込み関数
        // count=0 のとき無条件でデフォルトプロンプトを登録する（初回起動・既存ユーザー両対応）
        // ※ユーザーが意図的に全プロンプト削除した場合もデフォルトが復元される仕様（初心者向け）
        fun loadPrompts(): MutableList<PromptItem> {
            // ★デフォルトプロンプト文字列はここで一度だけ定義（初回登録・バージョンアップ更新の両方で使い回す）
            // ★次回デフォルト内容を変えるときはここを編集し、上の PROMPT_DEFAULTS_VERSION を +1 すること
            val defaultJpContent =
                "「○○」の最新動向を、以下の構成で教えてください。\n\n" +
                "【対象知識レベル】その分野について何も知らない大人\n" +
                "（専門用語を使う場合は必ず平易な言葉で言い換えること）\n\n" +
                "【出力構成】\n" +
                "1. 30秒サマリー（3行以内）\n" +
                "2. 言葉の背景\n" +
                "   - この言葉はいつ、どんな文脈で生まれたか\n" +
                "   - 類似する概念・言葉との違い\n" +
                "   - 対になる概念・言葉があれば併記\n" +
                "3. 主要な動向（時期付きで箇条書き）\n" +
                "4. 不確かな点・議論中の点\n" +
                "5. さらに調べるなら：推奨キーワード 2〜3個\n\n" +
                "【品質ルール】\n" +
                "- 学術資料など良質で信頼性の高い資料を使用する\n" +
                "- 事実と推測を明確に区別する\n" +
                "- 情報の時期・出典を可能な範囲で示す\n" +
                "- 「〜と言われている」だけでなく根拠を示す\n" +
                "- 知識カットオフより後の可能性があれば必ず明記する\n" +
                "- わからないことは「わからない」と言う。ハルシネーションより正直さを優先する"
            val defaultEnContent =
                "Please explain the latest developments around \"[TOPIC]\" using the structure below.\n\n" +
                "[Target level] An adult with no prior knowledge of the field\n" +
                "(Always rephrase technical terms in plain language)\n\n" +
                "[Structure]\n" +
                "1. 30-second summary (3 lines max)\n" +
                "2. Background of the term\n" +
                "   - When and in what context did this term originate?\n" +
                "   - How does it differ from similar concepts or terms?\n" +
                "   - Include any opposing concepts if applicable\n" +
                "3. Key developments (bulleted, with dates where possible)\n" +
                "4. Uncertain or debated points\n" +
                "5. If you want to dig deeper: 2-3 recommended search keywords\n\n" +
                "[Quality rules]\n" +
                "- Draw from high-quality, reliable sources such as academic literature\n" +
                "- Clearly distinguish facts from assumptions\n" +
                "- Cite the time period and source of information where possible\n" +
                "- Provide evidence, not just \"it is said that...\"\n" +
                "- If the topic may have developed beyond your knowledge cutoff, say so explicitly\n" +
                "- Say \"I don't know\" when you don't. Prioritize honesty over hallucination"
            val defaultPrompts = listOf(
                PromptItem("[Example] 初心者に向けた詳細な用語解説（JA）", defaultJpContent),
                PromptItem("[Example] Beginner-Friendly Term Explainer (EN)", defaultEnContent),
            )

            val count = prefs.getInt("prompt_count", 0)

            if (count == 0) {
                // プロンプトが0件 → デフォルトを登録
                val editor = prefs.edit()
                editor.putInt("prompt_count", defaultPrompts.size)
                // ★バージョンも同時に保存（次回起動時の不要な差し替えを防ぐ）
                editor.putInt("prompt_defaults_version", PROMPT_DEFAULTS_VERSION)
                defaultPrompts.forEachIndexed { i, item ->
                    editor.putString("prompt_title_$i", item.title)
                    editor.putString("prompt_content_$i", item.content)
                }
                editor.apply()
                return defaultPrompts.toMutableList()
            }

            // count > 0 → 通常ロード
            val list = mutableListOf<PromptItem>()
            for (i in 0 until count) {
                val title = prefs.getString("prompt_title_$i", "無題") ?: "無題"
                val content = prefs.getString("prompt_content_$i", "") ?: ""
                list.add(PromptItem(title, content))
            }

            // ★デフォルトプロンプトのバージョンチェック
            // バージョンアップ時・バックアップ復元時に [Example] プロンプトを最新に差し替える
            val savedDefaultsVersion = prefs.getInt("prompt_defaults_version", 0)
            if (savedDefaultsVersion < PROMPT_DEFAULTS_VERSION) {
                // [Example] タイトルのプロンプトだけ削除し、新しいデフォルトを先頭に追加
                val userPrompts = list.filter { !it.title.startsWith("[Example]") }
                val updated = (defaultPrompts + userPrompts).toMutableList()
                // SharedPreferences に書き直す
                val editor = prefs.edit()
                editor.putInt("prompt_count", updated.size)
                editor.putInt("prompt_defaults_version", PROMPT_DEFAULTS_VERSION)
                updated.forEachIndexed { i, item ->
                    editor.putString("prompt_title_$i", item.title)
                    editor.putString("prompt_content_$i", item.content)
                }
                editor.apply()
                return updated
            }

            return list
        }

        // ★辞書読み込み関数
        // dictionary_count が -1（未設定）のときは初回起動と判断し、デフォルトエントリーを登録する
        fun loadDictionary(): MutableList<DictionaryEntry> {
            val count = prefs.getInt("dictionary_count", -1)

            // 初回起動（一度も辞書を保存したことがない）→ デフォルトエントリーを登録
            if (count == -1) {
                val defaults = listOf(
                    // ★ # ## ### / * ** *** / --- / | / ` などのマークダウン記号は
                    //   cleanPerplexityText() が自動除去するため辞書には不要
                    //   辞書には「cleanPerplexityText が対応しないもの」だけを登録する

                    // 引用ブロック（> ）→ cleanPerplexityText 未対応のためここで対応
                    DictionaryEntry("> ", "", true),

                    // 括弧系ノイズ削除サンプル：$$$ワイルドカード（OFF状態：使いたい人だけONにする）
                    // "【$$$】" は "【見出し】" "【PR】" のような括弧全体を削除する
                    DictionaryEntry("【\$\$\$】", "", false),   // 【括弧】を丸ごと削除

                    // URL削除サンプル：cleanPerplexityTextのURL削除をOFFにしている場合の手動代替
                    // （OFF状態：通常は cleanPerplexityText が自動で処理するため不要）
                    DictionaryEntry("https://\$\$\$", "", false),  // HTTPS URL を丸ごと削除
                    DictionaryEntry("http://\$\$\$",  "", false),  // HTTP  URL を丸ごと削除
                )
                val editor = prefs.edit()
                editor.putInt("dictionary_count", defaults.size)
                defaults.forEachIndexed { i, entry ->
                    editor.putString("dict_original_$i", entry.original)
                    editor.putString("dict_replacement_$i", entry.replacement)
                    editor.putBoolean("dict_enabled_$i", entry.isEnabled)
                }
                editor.apply()
                return defaults.toMutableList()
            }

            val list = mutableListOf<DictionaryEntry>()
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

        // skipClean=true のとき Perplexity整形をスキップする（編集保存時に使う）
        // Perplexity整形は「連続空白・改行を1つに潰す」処理を含むため、
        // ユーザーが手動で入れた改行が消えてしまうのを防ぐ
        fun updateMainText(newText: String, skipClean: Boolean = false) {
            // 重い処理をバックグラウンドスレッドで実行（巨大ファイルでもUIが固まらない）
            scope.launch {
                isTextProcessing = true
                val (cleanedText, splitSentences) = withContext(Dispatchers.Default) {
                    // ★処理順序が重要：プロンプト除去 → Perplexity整形（任意）→ 文分割
                    val promptContents = promptList.map { it.content }
                    val cleaned = TextProcessor.removePrompts(newText, promptContents)
                    // ★編集保存時（skipClean=true）はPerplexity整形をスキップ
                    // 　 ペースト・ファイル読込時は整形を適用（引用番号・URL・連続空白を除去）
                    val perplexityCleaned = if (skipClean) cleaned
                                           else TextProcessor.cleanPerplexityText(cleaned)
                    // ★TTS用テキスト：辞書を適用（読み上げ時だけ単語を変換）
                    val dictionaryApplied = TextProcessor.applyDictionary(perplexityCleaned, dictionaryList)
                    val newSentences = TextProcessor.splitSentences(dictionaryApplied)
                    Pair(perplexityCleaned, newSentences)
                }
                // バックグラウンド処理完了 → メインスレッドでUIを更新
                mainText = cleanedText
                sentences = splitSentences
                isTextProcessing = false
                // テキストをファイルに保存（次回起動時に復元する）
                withContext(Dispatchers.IO) {
                    sessionFile.writeText(cleanedText)
                }
                // 次回起動時の復元位置もリセット
                context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("lastSentenceIndex", 0).apply()
            }
        }

        // 起動時：保存されたテキストがあれば sentences を生成（読み位置復元のため）
        LaunchedEffect(Unit) {
            if (mainText.isNotEmpty()) {
                updateMainText(mainText)
            }
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
                        onUpdateText = { text, skipClean -> updateMainText(text, skipClean) },
                        isTextProcessing = isTextProcessing
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
                        onNavigateBack = {
                            // 辞書変更を読み上げに反映するため sentences だけ再作成する
                            // mainText（表示テキスト）は一切変更しない（辞書はTTSにのみ影響）
                            if (mainText.isNotEmpty()) {
                                scope.launch {
                                    val applied = withContext(Dispatchers.Default) {
                                        TextProcessor.applyDictionary(mainText, dictionaryList)
                                    }
                                    sentences = TextProcessor.splitSentences(applied)
                                }
                            }
                            currentScreen = Screen.Main
                        },
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

        // 初回起動同意ダイアログ（AnimatedContent の上に重なる形で表示）
        // 同意ボタンを押すまで消えない → アプリの利用規約を確認したことを担保する
        if (showConsentDialog) {
            FirstLaunchConsentDialog(
                onAgree = {
                    ttsPrefs.edit().putBoolean("first_launch_agreed", true).apply()
                    showConsentDialog = false
                }
            )
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

        // ★クレイモーフィズム カラーパレット（サブ画面共通 → UIComponents.kt）
        val c            = subScreenColors()
        val bgColor      = c.bgColor
        val paperColor   = c.paperColor
        val primaryColor = c.primaryColor
        val textPrimary  = c.textPrimary
        val textMuted    = c.textMuted
        val pinkColor    = c.pinkColor
        val greenColor   = c.greenColor
        val gradient     = c.gradient

        // ★削除確認ダイアログ（共通コンポーネント → UIComponents.kt）
        if (showDeleteDialog) {
            ConfirmDialog(
                title      = "Delete this entry?",
                message    = "This action cannot be undone.",
                gradient   = gradient,
                paperColor = paperColor,
                textPrimary = textPrimary,
                textMuted  = textMuted,
                onYes = { showDeleteDialog = false; onDeleteEntry(deleteTargetIndex) },
                onNo  = { showDeleteDialog = false },
            )
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
        // ★クレイモーフィズム カラーパレット（サブ画面共通 → UIComponents.kt）
        val c            = subScreenColors()
        val bgColor      = c.bgColor
        val paperColor   = c.paperColor
        val primaryColor = c.primaryColor
        val textPrimary  = c.textPrimary
        val textMuted    = c.textMuted
        val gradient     = c.gradient

        // ★未保存ダイアログ（共通コンポーネント → UIComponents.kt）
        if (showDialog) {
            ConfirmDialog(
                title      = "Leave without saving?",
                message    = "Your changes will be lost.",
                gradient   = gradient,
                paperColor = paperColor,
                textPrimary = textPrimary,
                textMuted  = textMuted,
                onYes = { showDialog = false; onCancel() },
                onNo  = { showDialog = false },
            )
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
                modifier = Modifier
                    .weight(1f)             // 残り高さを占有（ヘッダーと合わせてfillMaxSizeと同等）
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())  // ヘルプカードがはみ出ても縦スクロールで見られる
                    .padding(20.dp)
                    .imePadding(),
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

                // ★ヘルプカード：置換ルールの書き方を常時表示
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(primaryColor.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("How it works", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = primaryColor)
                        Text(
                            "• Replacement blank  →  word is skipped (deleted from reading)\n" +
                            "• Enter text  →  read as that text instead",
                            fontSize = 12.sp, color = textMuted, lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text("Examples", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = textMuted)
                        // (blank) = replacement is empty → text is deleted and NOT read aloud
                        Text("(blank) = deleted from reading — not pronounced",
                            fontSize = 10.sp, color = textMuted.copy(alpha = 0.7f), lineHeight = 14.sp)
                        Spacer(modifier = Modifier.height(2.dp))
                        // 例一覧（置換元 → 置換先 説明）
                        listOf(
                            Triple("# ",             "(blank)",  "heading marker — not read aloud"),
                            Triple("## ",            "(blank)",  "subheading marker — not read aloud"),
                            Triple("---",            "(blank)",  "divider line — not read aloud"),
                            Triple("Dr.",            "Doctor",   "expand abbreviations"),
                            Triple("【\$\$\$】",     "(blank)",  "removes 【bracketed】 groups"),
                            Triple("https://\$\$\$", "(blank)",  "removes entire URLs"),
                        ).forEach { (orig, repl, desc) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("\"$orig\"", fontSize = 11.sp, color = textPrimary, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.widthIn(min = 72.dp))
                                Text("→", fontSize = 11.sp, color = textMuted,
                                    modifier = Modifier.padding(horizontal = 4.dp))
                                Text("\"$repl\"", fontSize = 11.sp, color = primaryColor, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f))
                                Text(desc, fontSize = 10.sp, color = textMuted)
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        // $$$ワイルドカードの説明
                        Text(
                            "★ \$\$\$ wildcard: add \$\$\$ at the end to match\n" +
                            "  everything until the next space.\n" +
                            "  e.g. \"https://\$\$\$\" removes the full URL.\n\n" +
                            "Tip: Paste or File open already removes all URLs\n" +
                            "  automatically — no entry needed for those.",
                            fontSize = 11.sp, color = textMuted.copy(alpha = 0.85f), lineHeight = 16.sp
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
        onUpdateText: (String, Boolean) -> Unit,
        isTextProcessing: Boolean = false   // テキスト処理中フラグ（デフォルトfalse）
    ) {
        var isPlaying by remember { mutableStateOf(false) }
        // 再生ボタンタップ〜TTS実際に音が出るまでの間「起動中」スピナーを表示するフラグ
        var isTtsStarting by remember { mutableStateOf(false) }

        // ★スピード・ピッチを SharedPreferences から復元（前回の設定を引き継ぐ）
        val context = LocalContext.current
        val ttsPrefs = remember { context.getSharedPreferences("tts_prefs", Context.MODE_PRIVATE) }
        var speechRate by remember { mutableFloatStateOf(ttsPrefs.getFloat("speechRate", 1.0f)) }
        var pitch by remember { mutableFloatStateOf(ttsPrefs.getFloat("pitch", 1.0f)) }
        // 言語設定：テキスト変更時に全体を自動判定し初期値を決める。ユーザーが手動で上書き可能
        // "JA"/"EN"/"DE"/"FR"/"ZH" の5種類を順番にトグルできる
        // 前回セッションで保存された読み位置（sentences が読み込まれたら一度だけ復元）
        val savedSessionIndex = remember { ttsPrefs.getInt("lastSentenceIndex", 0) }
        var hasRestoredSession by remember { mutableStateOf(false) }
        var currentSentenceIndex by remember { mutableIntStateOf(0) }
        // シークスライダー用：ドラッグ中は再生位置と切り離して動かすための変数
        var seekSliderValue by remember { mutableFloatStateOf(0f) }
        var isDraggingSeek by remember { mutableStateOf(false) }
        var isEditMode by remember { mutableStateOf(false) }
        // ★③ フォントサイズ：13(Small) / 16(Medium) / 20(Large)
        var fontSize by remember { mutableFloatStateOf(16f) }
        // PDFなどのファイル読み込み中フラグ（IOスレッドでの抽出フェーズ）
        var isLoadingFile by remember { mutableStateOf(false) }

        // ★編集中のテキストを保持する変数
        var editingText by remember { mutableStateOf(text) }

        // ★表示専用の文分割リスト（辞書未適用・元の単語をそのまま表示する）
        // sentences（辞書適用済み）はTTS読み上げ専用、このリストは画面表示専用
        val displaySentences = remember(text) { TextProcessor.splitSentences(text) }

        // ★isEditMode時のバック確認ダイアログ表示フラグ
        var showUnsavedDialog by remember { mutableStateOf(false) }
        // ★音声パック未インストール警告ダイアログ：不足言語の表示名リストを保持
        // 空リストのとき非表示、1つ以上のときダイアログ表示
        var missingVoicePackNames by remember { mutableStateOf<List<String>>(emptyList()) }
        // 「Play Anyway」用：ダイアログを閉じた後に実行する再生処理を一時保存
        var pendingPlayAction by remember { mutableStateOf<(() -> Unit)?>(null) }

        // ★非対応ファイル形式ダイアログ用：タップされたファイルのURIを保持
        var unsupportedFileUri by remember { mutableStateOf<Uri?>(null) }


        // ★textが変更されたら編集用テキストも更新、かつ言語を全体で自動判定
        LaunchedEffect(text) {
            if (!isEditMode) {
                editingText = text
            }
            if (text.isNotEmpty()) {
                // ドキュメント全体の特徴から基底言語を推定する（センテンス判定のフォールバック用）
                ttsService?.setBaseLanguage(TextProcessor.detectLanguage(text))
            }
        }

        // sentences が読み込まれたとき、前回の読み位置を一度だけ復元する
        LaunchedEffect(sentences.size) {
            if (!hasRestoredSession && sentences.isNotEmpty() && savedSessionIndex > 0) {
                currentSentenceIndex = if (savedSessionIndex < sentences.size) savedSessionIndex else 0
                hasRestoredSession = true
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

        // ★カラーシステム（ライト/ダークモード切替）
        // ダイアログより前に定義する必要があるため、ここに置く
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

        // ★音声パック未インストール警告ダイアログ
        if (missingVoicePackNames.isNotEmpty()) {
            Dialog(onDismissRequest = { missingVoicePackNames = emptyList(); pendingPlayAction = null }) {
                Box(
                    modifier = Modifier
                        .shadow(16.dp, RoundedCornerShape(20.dp))
                        .background(paperColor, RoundedCornerShape(20.dp))
                        .padding(24.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Voice Packs Missing", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = textPrimary)
                        Text("The following languages were detected but voice packs are not installed:",
                            fontSize = 14.sp, color = textMuted)
                        // 不足している言語を箇条書きで列挙
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            missingVoicePackNames.forEach { name ->
                                Text("• $name", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = textPrimary)
                            }
                        }
                        Text("These sections will be read in English instead.",
                            fontSize = 13.sp, color = textMuted)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // Play Anyway（控えめ）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(2.dp, RoundedCornerShape(12.dp))
                                .background(paperColor, RoundedCornerShape(12.dp))
                                .clickable {
                                    val action = pendingPlayAction
                                    missingVoicePackNames = emptyList()
                                    pendingPlayAction = null
                                    action?.invoke()
                                }, contentAlignment = Alignment.Center) {
                                Text("Play Anyway", fontWeight = FontWeight.Bold, color = textMuted, fontSize = 13.sp)
                            }
                            // Go to Settings（目立つ：グラデーション）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(4.dp, RoundedCornerShape(12.dp))
                                .background(playGradient, RoundedCornerShape(12.dp))
                                .clickable {
                                    missingVoicePackNames = emptyList()
                                    pendingPlayAction = null
                                    context.startActivity(Intent("com.android.settings.TTS_SETTINGS").apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                }, contentAlignment = Alignment.Center) {
                                Text("Go to Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        if (showUnsavedDialog) {
            Dialog(onDismissRequest = { showUnsavedDialog = false }) {
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
                            // YES（控えめ）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(2.dp, RoundedCornerShape(12.dp))
                                .background(paperColor, RoundedCornerShape(12.dp))
                                .clickable { showUnsavedDialog = false; editingText = text; isEditMode = false },
                                contentAlignment = Alignment.Center) {
                                Text("YES", fontWeight = FontWeight.Bold, color = textMuted, fontSize = 14.sp)
                            }
                            // NO（目立つ：グラデーション）
                            Box(modifier = Modifier.weight(1f).height(44.dp)
                                .shadow(4.dp, RoundedCornerShape(12.dp))
                                .background(playGradient, RoundedCornerShape(12.dp))
                                .clickable { showUnsavedDialog = false },
                                contentAlignment = Alignment.Center) {
                                Text("NO", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        val listState = rememberLazyListState()
        val activity = context as? MainActivity
        val density = LocalDensity.current  // dp→px変換用（LaunchedEffect内で使う）

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

        // ★MainScreen を常時縦固定（コードベースのportraitロック。Manifest設定との挙動差を確認するテスト）
        // 他の画面と同じDisposableEffect(Unit)方式で統一。画面を離れたら自動解除
        DisposableEffect(Unit) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            onDispose {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }

        // 再生位置が変わったらスライダーを同期（ドラッグ中は無視）
        LaunchedEffect(currentSentenceIndex) {
            if (!isDraggingSeek && sentences.size > 1) {
                seekSliderValue = currentSentenceIndex.toFloat() / (sentences.size - 1).toFloat()
            }
        }

        LaunchedEffect(currentSentenceIndex) {
            if (currentSentenceIndex >= 0 && currentSentenceIndex < sentences.size) {
                // LazyColumnの描画完了を待ってからスクロール実行
                kotlinx.coroutines.delay(100)
                // ハイライト中のセンテンスを上端に表示しつつ、
                // 前のセンテンスの末尾を80dp分だけ上に見せて「読んでいた場所の錨」を作る
                // currentSentenceIndex == 0 のときは余白なし（上に何もない）
                val offsetPx = if (currentSentenceIndex > 0) {
                    with(density) { -80.dp.roundToPx() }
                } else {
                    0
                }
                listState.animateScrollToItem(
                    index = currentSentenceIndex,
                    scrollOffset = offsetPx
                )
            }
        }

        // PDF / Word / Google Document ファイルピッカー（複数形式に対応）
        val docPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) {
                // ファイルが選択されなかった（ピッカーがキャンセルされた or 認識失敗）
                Toast.makeText(context, "File not selected. Please try again.", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            uri.let {
                isLoadingFile = true  // ファイル読み込み開始
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
                        isLoadingFile = false  // ファイル読み込み完了
                        if (text.isNotEmpty()) {
                            // 新しいファイルに切り替えるので再生を止めてリセット
                            if (isPlaying) { ttsService?.stop(); isPlaying = false }
                            currentSentenceIndex = 0
                            onUpdateText(text, false)
                        } else if (mimeType.contains("pdf")) {
                            // PDF読み込み失敗（画像だけのPDF・パスワード付き等）
                            Toast.makeText(context, "Could not read PDF. It may be image-only or password-protected.", Toast.LENGTH_LONG).show()
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
                    // 読み位置を保存（次回起動時に復元するため）
                    ttsPrefs.edit().putInt("lastSentenceIndex", current).apply()
                }
                // TTSが実際に音を出し始めた瞬間（再生ボタンタップから数秒後）
                override fun onStarted() {
                    isTtsStarting = false  // 起動中スピナーを消す
                    activity?.recordPlayStart()  // 再生時間の計測を開始
                }
                override fun onComplete() {
                    isPlaying = false
                    isTtsStarting = false
                    currentSentenceIndex = 0
                    // 読了したので位置を先頭にリセット（次回起動は最初から）
                    ttsPrefs.edit().putInt("lastSentenceIndex", 0).apply()
                    // 再生終了のたびにカウントし、3回に1回インタースティシャル広告を表示
                    activity?.showInterstitialAdIfReady()
                }
                override fun onError(msg: String) {
                    isPlaying = false
                    isTtsStarting = false
                }
                // ★追加：通知の PAUSE ボタンが押されたとき
                // onComplete() と違い currentSentenceIndex はリセットしない（位置を覚えたまま止まる）
                override fun onPaused() {
                    isPlaying = false
                    isTtsStarting = false
                }
            })
        }

        // ★キーボード（IME）が表示中かどうか検知（横向き編集時のスペース確保のため）
        val isKeyboardVisible = WindowInsets.isImeVisible
        // ★横向き判定（シートのpeek高さ調整に使用）
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        // ★peekHeight：折りたたみ時に見せたいコンテンツの実際の高さをリアルタイムで測る
        // 固定dp値を使わず onGloballyPositioned で測定するため、どの画面サイズでも正確に合う
        // ★navBarPaddingは加算しない：BottomSheetScaffold(Material3)がナビバーインセットを
        //   自動処理するため、自分で加算すると二重になり3ボタンナビ端末でpeekHeightが大きくなりすぎる
        val hasSentences = sentences.size > 1
        // 折りたたみコンテンツの実測高さ（px → dp変換）。初期値は従来値で表示崩れを防ぐ
        var collapsedContentHeightDp by remember { mutableStateOf(if (hasSentences) 225.dp else 218.dp) }
        val peekHeight = when {
            isLandscape  -> 125.dp
            // 実測値(Transport+SPEED+Progress/PITCH) + DragHandle(30dp)
            // テキストあり時はオーバーレイ(35dp)分も加算してPITCHを隠す
            hasSentences -> collapsedContentHeightDp + 30.dp  // ← PITCHが見えない高さに設定。オーバーレイはあくまではみ出し防止
            else         -> collapsedContentHeightDp + 30.dp
        }
        // ★BottomSheetScaffoldの状態（編集モード中も常に保持するためif-elseの外で定義）
        val scaffoldState = rememberBottomSheetScaffoldState()

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
                                onUpdateText(editingText, true); isEditMode = false; currentSentenceIndex = 0
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
                        sheetPeekHeight      = peekHeight,  // ★画面高さの35%（最大260dp）で小画面端末でも切れない
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
                                // ★再生前チェック：音声パックが不足していればダイアログを出し、問題なければ再生する
                                // action に実際の speakList 呼び出しを渡す（「Play Anyway」でも同じ処理を再利用）
                                fun playWithVoiceCheck(action: () -> Unit) {
                                    val missing = ttsService?.checkMissingVoicePacks(sentences) ?: emptyList()
                                    if (missing.isEmpty()) {
                                        action()  // 問題なし → そのまま再生
                                    } else {
                                        // 不足言語の表示名（例: "German", "French"）をリストにして保持
                                        missingVoicePackNames = missing.map { it.getDisplayLanguage(java.util.Locale.ENGLISH) }
                                        pendingPlayAction = action  // Play Anyway 用に保存
                                    }
                                }

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
                                                // 再生中のみ：リスナーは再生開始時に設定済みのため不要
                                                ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                                ttsService?.speakList(sentences, prev)
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
                                            if (isTextProcessing) return@clickable // 処理中はタップ無効
                                            if (isPlaying) {
                                                ttsService?.stop(); isPlaying = false; isTtsStarting = false
                                                activity?.showInterstitialAdIfReady()  // 停止時に再生時間をチェック
                                            } else {
                                                if (sentences.isNotEmpty()) {
                                                    val s = if (currentSentenceIndex in sentences.indices) currentSentenceIndex else 0
                                                    playWithVoiceCheck {
                                                        ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                                        setupTtsListener(); ttsService?.speakList(sentences, s)
                                                        currentSentenceIndex = s; isPlaying = true
                                                        isTtsStarting = true
                                                    }
                                                }
                                            }
                                        },
                                        contentAlignment = Alignment.Center) {
                                        // テキスト処理中 or TTS起動中はスピナーを表示、それ以外は再生/停止アイコン
                                        if (isTextProcessing || isTtsStarting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(36.dp),
                                                color = Color.White,
                                                strokeWidth = 3.dp
                                            )
                                        } else {
                                            Icon(if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                if (isPlaying) "Stop" else "Play",
                                                modifier = Modifier.size(40.dp)
                                                    .then(if (!isPlaying) Modifier.offset(x = 2.dp) else Modifier),
                                                tint = Color.White)
                                        }
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
                                                // 再生中のみ：リスナーは再生開始時に設定済みのため不要
                                                ttsService?.setSpeechRate(speechRate); ttsService?.setPitch(pitch)
                                                ttsService?.speakList(sentences, next)
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
                                        onValueChange = { speechRate = it },  // ドラッグ中は数値表示だけ更新
                                        onValueChangeFinished = {
                                            // 指を離した瞬間：新スピードを反映し現在センテンスを先頭から読み直す
                                            ttsService?.setSpeechRate(speechRate)
                                            if (isPlaying) ttsService?.seekTo(currentSentenceIndex)
                                            // 設定を保存（次回起動時に復元）
                                            ttsPrefs.edit().putFloat("speechRate", speechRate).apply()
                                        },
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
                                // Track 進捗（ドラッグで位置移動できるスライダー）＋経過/全体時間表示
                                if (sentences.size > 1) {
                                    // 時間計算（スライダー右端に表示）
                                    val baseCharsPerSec = 5.0f
                                    val effectiveRate = (baseCharsPerSec * speechRate).coerceAtLeast(0.1f)
                                    val totalSecs = (sentences.sumOf { it.length } / effectiveRate).toInt()
                                    val elapsedSecs = (sentences.take(currentSentenceIndex).sumOf { it.length } / effectiveRate).toInt()
                                    // 60分未満: "53:42"  /  60分以上: "1:32:54"
                                    fun Int.toMmSs() = if (this < 3600)
                                        "%d:%02d".format(this / 60, this % 60)
                                    else
                                        "%d:%02d:%02d".format(this / 3600, (this % 3600) / 60, this % 60)
                                    Row(modifier = Modifier.fillMaxWidth()
                                        .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Slider(
                                            value = seekSliderValue,
                                            onValueChange = { v ->
                                                isDraggingSeek = true
                                                seekSliderValue = v
                                            },
                                            onValueChangeFinished = {
                                                // 指を離したら、その位置のセンテンスにジャンプ
                                                val targetIndex = (seekSliderValue * (sentences.size - 1)).toInt()
                                                    .coerceIn(0, sentences.size - 1)
                                                currentSentenceIndex = targetIndex
                                                ttsService?.seekTo(targetIndex)
                                                isDraggingSeek = false
                                            },
                                            modifier = Modifier.weight(1f),
                                            // サムネイル（つまみ）：指で掴みやすい大きさ
                                            thumb = {
                                                Box(modifier = Modifier.size(22.dp)
                                                    .shadow(3.dp, CircleShape)
                                                    .background(Color.White, CircleShape)
                                                    .border(2.dp, textMuted, CircleShape))
                                            },
                                            // トラック：再生済み部分をハイライト
                                            track = {
                                                Box(modifier = Modifier.fillMaxWidth().height(6.dp)
                                                    .background(sliderBg.copy(alpha = 0.5f), RoundedCornerShape(50))) {
                                                    Box(modifier = Modifier.fillMaxWidth(seekSliderValue).fillMaxHeight()
                                                        .background(
                                                            textMuted.copy(alpha = if (isPlaying) minOf(1f, (0.3f + rawPulse * 0.35f) * 1.5f) else 0.6f),
                                                            RoundedCornerShape(50)))
                                                }
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // 経過時間 / 全体時間をスライダー右端に表示（同一Row内で視覚的に一体化）
                                        Text("(${elapsedSecs.toMmSs()} / ${totalSecs.toMmSs()})",
                                            fontSize = 11.sp, color = textMuted.copy(alpha = 0.9f))
                                    }
                                }
                                // ★折りたたみコンテンツの底辺にゼロ高さBoxを置いて高さを計測
                                // boundsInParent().bottom = 親Column上端からこのBoxまでの距離
                                // = Transport + SPEED + Progress(or PITCH) の合計高さ
                                // DragHandle(30dp) は Column の外にあるので peekHeight 計算時に加算する
                                Box(modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coords ->
                                        val measured = with(density) {
                                            coords.boundsInParent().bottom.toDp()
                                        }
                                        // ★初回レンダリング時に0が返ることがあるのでガード
                                        // 50dp未満は「まだ正しく計測できていない」と判断してスキップ
                                        if (measured > 50.dp) collapsedContentHeightDp = measured
                                    }
                                )
                                // ──── 展開時のみ表示 ────
                                Spacer(modifier = Modifier.height(6.dp))
                                // PITCH スライダー
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("PITCH", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
                                        letterSpacing = 1.5.sp, color = textMuted, modifier = Modifier.width(48.dp))
                                    Slider(value = pitch,
                                        onValueChange = { pitch = it },  // ドラッグ中は数値表示だけ更新
                                        onValueChangeFinished = {
                                            // 指を離した瞬間：新ピッチを反映＋設定を保存
                                            ttsService?.setPitch(pitch)
                                            ttsPrefs.edit().putFloat("pitch", pitch).apply()
                                        },
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
                                // 4つのアクションボタン（Paste + Files + Prompts + Dict.）
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                                            if (cd != null && cd.itemCount > 0) { if (isPlaying) { ttsService?.stop(); isPlaying = false }; currentSentenceIndex = 0; onUpdateText(cd.getItemAt(0).text.toString(), false) } }
                                                        1 -> { if (isPlaying) { ttsService?.stop(); isPlaying = false }
                                                            docPickerLauncher.launch(arrayOf("application/pdf",
                                                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                                            "application/vnd.google-apps.document", "text/html", "text/plain")) }
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
                            // ファイル読み込み中 / テキスト処理中 オーバーレイ
                            if (isLoadingFile || isTextProcessing) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                        CircularProgressIndicator(color = primaryColor)
                                        Text(
                                            if (isLoadingFile) "Reading file..." else "Processing text...",
                                            color = textMuted, fontSize = 14.sp
                                        )
                                    }
                                }
                            } else if (text.isEmpty()) {
                                Text("Tap Paste to add text, or use the file button\nto open a PDF, Word, or text file.",
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
                                                    currentSentenceIndex = index; isPlaying = true; isTtsStarting = true
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
                // ★navigationBarsPadding()を追加：3ボタンナビゲーション搭載端末（AQUOS等）で
                //   ナビゲーションバーの裏にオーバーレイが潜り込んでピッチバーが透けるバグを修正
                // ★オーバーレイはテキストあり（Progress表示）のときだけ出す
                // テキストなし時はPITCHを見せたいので表示しない
                if (!isEditMode && !isKeyboardVisible && hasSentences) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(16.dp)  // PITCHのthumb(円)上端がはみ出す分だけ隠す最小サイズ
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
        // ★クレイモーフィズム カラーパレット（サブ画面共通 → UIComponents.kt）
        val c            = subScreenColors()
        val bgColor      = c.bgColor
        val paperColor   = c.paperColor
        val primaryColor = c.primaryColor
        val textPrimary  = c.textPrimary
        val textMuted    = c.textMuted
        val pinkColor    = c.pinkColor
        // ★horizontalGradient：ボタンの幅に自動フィットするため小さいボタンでも確実にグラデが出る
        val gradient     = c.gradient

        // ★削除確認ダイアログ（共通コンポーネント → UIComponents.kt）
        if (showDeleteDialog) {
            ConfirmDialog(
                title      = "Delete this prompt?",
                message    = "This action cannot be undone.",
                gradient   = gradient,
                paperColor = paperColor,
                textPrimary = textPrimary,
                textMuted  = textMuted,
                onYes = { showDeleteDialog = false; onDeletePrompt(deleteTargetIndex) },
                onNo  = { showDeleteDialog = false },
            )
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

            // ★常にLazyColumn。空状態も内部アイテムで表現する
            // weight(1f)：Columnの中でLazyColumnが「残り全部」を正しく占有するために必須
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 空状態メッセージ（プロンプトが0のとき）
                if (prompts.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center) {
                            Text("Tap + to add your first prompt.",
                                color = textMuted, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // プロンプトカード一覧
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
                                Text(prompt.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = textPrimary,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(
                                    text = prompt.content,
                                    fontSize = 12.sp, color = textMuted,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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

                // ★ヘルプボックス：プロンプトが5個未満のとき、リストの末尾に表示
                // 5個以上になったら自動的に消える（慣れたユーザーには不要なため）
                if (prompts.size < 5) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth()
                                .background(primaryColor.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("How Prompts work",
                                    fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, color = primaryColor)
                                Text(
                                    "Save instructions you give to AI assistants (ChatGPT, Perplexity, etc.) " +
                                    "to generate your reading scripts.\n" +
                                    "Tap a prompt to copy it → paste to AI → paste the result back to Reader.",
                                    fontSize = 12.sp, color = textMuted, lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("What happens at playback",
                                    fontWeight = FontWeight.Bold, fontSize = 12.sp, color = textMuted)
                                Text(
                                    "Saved prompts are automatically stripped from your text before reading.\n" +
                                    "This keeps the AI's instructions out of the audio.",
                                    fontSize = 12.sp, color = textMuted, lineHeight = 18.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    "This guide disappears after you have 5 or more prompts saved.",
                                    fontSize = 11.sp, color = textMuted.copy(alpha = 0.7f), lineHeight = 15.sp
                                )
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

            // ★NFKC正規化：PDFの特殊エンコーディング文字を標準Unicode文字に統一
            // （これがないと辞書の「粒子」と抽出された「粒子」が内部コード上別物になる）
            val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)

            // ★TextProcessorを使って改行を整形
            TextProcessor.cleanPdfText(normalized)
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

    // AdMob を初期化して広告をロードする
    // UMP の同意フローが完了してから呼ぶ（直接呼び出し禁止）
    private fun initMobileAds() {
        MobileAds.initialize(this)
        loadInterstitialAd()
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

    // 再生が実際に始まった瞬間に呼ぶ：タイマースタート
    // onStarted()はセンテンスごとに呼ばれるため、計測中（playStartTime != 0）は上書きしない
    fun recordPlayStart() {
        if (playStartTime == 0L) {
            playStartTime = System.currentTimeMillis()
            if (BuildConfig.DEBUG) Log.d("AdDebug", "▶ recordPlayStart: タイマースタート")
        }
    }

    // 再生停止時に呼ぶ：累計再生時間を計算し、45分超えたら広告を表示
    // runOnUiThread：TTS コールバックはバックグラウンドスレッドなので必ずメインスレッドに戻す
    fun showInterstitialAdIfReady() {
        if (BuildConfig.DEBUG) Log.d("AdDebug", "⏹ showInterstitialAdIfReady: 呼ばれた / playStartTime=$playStartTime")
        // 再生していなかった（playStartTime未設定）なら何もしない
        if (playStartTime == 0L) {
            if (BuildConfig.DEBUG) Log.d("AdDebug", "⏹ playStartTime=0 のためスキップ")
            return
        }

        // 今回の再生時間を累計に加算
        val elapsed = System.currentTimeMillis() - playStartTime
        playStartTime = 0L  // タイマーをリセット

        // ttsPrefsはComposable内のローカル変数のため、ここでは直接取得する
        val adPrefs = getSharedPreferences("tts_prefs", Context.MODE_PRIVATE)
        val prev = adPrefs.getLong("accumulatedPlayMs", 0L)
        val total = prev + elapsed
        if (BuildConfig.DEBUG) Log.d("AdDebug", "⏹ elapsed=${elapsed/1000}秒 / prev=${prev/1000}秒 / total=${total/1000}秒 / 閾値=${AD_TIME_THRESHOLD_MS/1000}秒")

        if (total < AD_TIME_THRESHOLD_MS) {
            // まだ45分未満 → 累計を保存して今回は広告なし（翌日に持ち越し）
            adPrefs.edit().putLong("accumulatedPlayMs", total).apply()
            if (BuildConfig.DEBUG) Log.d("AdDebug", "⏹ 閾値未達のためスキップ・累計を保存")
            return
        }

        // 45分以上 → 累計リセットして広告を表示
        adPrefs.edit().putLong("accumulatedPlayMs", 0L).apply()

        // 広告表示前にTTSを確実に停止（バッファ残りの音声を防ぐ）
        ttsService?.stop()

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
        // ★クレイモーフィズム カラーパレット（サブ画面共通 → UIComponents.kt）
        val c            = subScreenColors()
        val bgColor      = c.bgColor
        val paperColor   = c.paperColor
        val primaryColor = c.primaryColor
        val textPrimary  = c.textPrimary
        val textMuted    = c.textMuted
        val gradient     = c.gradient

        // ★未保存ダイアログ（共通コンポーネント → UIComponents.kt）
        if (showDialog) {
            ConfirmDialog(
                title      = "Leave without saving?",
                message    = "Your changes will be lost.",
                gradient   = gradient,
                paperColor = paperColor,
                textPrimary = textPrimary,
                textMuted  = textMuted,
                onYes = { showDialog = false; onCancel() },
                onNo  = { showDialog = false },
            )
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
