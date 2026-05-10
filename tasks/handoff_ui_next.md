# 引継ぎメモ：VoiceReader UI開発（次スレッド用）

## プロジェクト概要
- **リポジトリ**: `5_VoiceReader_UI`
- **ブランチ**: `feat/ui-improvements-portrait-lock`
- **ファイル**: `app/src/main/java/com/example/voicereader/MainActivity.kt`（シングルファイル構成）
- **技術**: Jetpack Compose + Material3 + Android TTS

---

## これまでのセッションでやったこと（確定済み・push済み）

### コミット: 99d88bd
- ダークモード時のテキストフィールド文字色を黒固定に修正
- Dictionary / Prompts リスト画面に削除確認ダイアログ追加・ボタン拡大
- 編集画面の重複SAVEボタン削除（ヘッダーのディスクボタンに一本化）

### コミット: e5c3cf1
- `screenOrientation="portrait"` → `configChanges="orientation|screenSize|keyboardHidden"` に変更
  - 理由: ファイル選択後に戻ったときアプリが再起動してテキストが消えるバグを解消
- 横向き対応：キーボード表示中にDictionary/Prompt編集画面ヘッダーを自動非表示
- MainScreen：横向き時に編集ボタンを右上→右下（BottomEnd）に移動

### コミット: 238ac6e（最新）
- DictionaryEditScreen / PromptEditScreen：横向き時にラベルテキスト（"Title"/"Content"/"Word to replace"等）を非表示
- **DictionaryEditScreen / PromptEditScreen：画面表示中のみ縦固定**
  - `DisposableEffect` + `ActivityInfo.SCREEN_ORIENTATION_PORTRAIT` を使用
  - 画面を離れたら `SCREEN_ORIENTATION_UNSPECIFIED` で自由回転に戻す

---

## 現在の画面構成

| 画面 | 横向き可否 | 備考 |
|---|---|---|
| MainScreen | 可 | 横向き時に編集ボタン右下移動・進行バー非表示 |
| PromptListScreen | 可 | 特別な横向き処理なし |
| DictionaryListScreen | 可 | 特別な横向き処理なし |
| PromptEditScreen | **縦固定** | DisposableEffectで制御 |
| DictionaryEditScreen | **縦固定** | DisposableEffectで制御 |

---

## 重要な実装パターン（コード上の決め事）

```kotlin
// 画面ごとの縦固定パターン
val activity = LocalContext.current as? Activity
DisposableEffect(Unit) {
    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    onDispose {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}

// キーボード・向き検知（@OptIn(ExperimentalLayoutApi::class) 必須）
val isKeyboardVisible = WindowInsets.isImeVisible
val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
```

---

## 未確認事項（実機テスト待ち）

- [ ] MainScreen 横向き編集：キーボードを出した状態でテキストが1行以上見えるか
- [ ] MainScreen 横向き：キーボードを閉じたとき保存ボタンが右下に正しく出現するか
- [ ] DictionaryEditScreen / PromptEditScreen：縦固定が実機で正しく動くか
- [ ] ファイル選択（縦向き操作）：戻ってきてもテキストが消えないか

---

## 今後議論したいUI改善候補（優先度未定）

- メイン画面のデザイン全体的な見直し
- その他ユーザーから要望があれば追加

---

## PR情報
- PR: https://github.com/kyt533287-creator/voice-reader-app-for-perplexity-user/pull/1
- マージ先: `main`
