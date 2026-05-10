# BridgeTTS プロジェクト固有ルール

## 会話の開始時にやること
`tasks/` フォルダ内の **最新日付の `handoff_*.md`** を必ず読んで現在の状況を把握すること。
不明点があれば、その前の日付のファイルも読むこと。

Obsidianに関する対話・知識探究のセッションの場合は、以下も必ず読むこと：
`D:\Obsidian\Obsidian_Documents\SYSTEM\Claude_対話指針.md`
`D:\Obsidian\Obsidian_Documents\THINK\` フォルダ内の最新ノート（存在する場合）

## 会話の終了時にやること
セッションを閉じる前に **`tasks/handoff_YYYY-MM-DD.md`**（今日の日付）を新規作成すること。
内容：このセッションで完成したこと・修正したバグ・次のセッションでやること・現在のブランチ状態。

## アプリ概要

- **アプリ名**: BridgeTTS（旧名 VoiceReader。パッケージ名は変更していない）
- **パッケージ名**: `com.example.voicereader`
- **技術スタック**: Jetpack Compose + Material3 + Android TTS + AdMob（インタースティシャルのみ）
- **メイン実装ファイル**: `app/src/main/java/com/example/voicereader/MainActivity.kt`
- **再生サービス**: `app/src/main/java/com/example/voicereader/TtsService.kt`
- **現在のブランチ**: `feat/ui-improvements-portrait-lock`

## このアプリ固有のUI方針

- 画面遷移はすべて `MainActivity.kt` 内の `currentScreen` 変数で管理（シングルファイル構成）
- ヘッダーのボタンで保存・編集を完結させる（画面下部に同じ機能のボタンを置かない）
- フォントサイズはS/M/Lの3段階切り替え（13f / 16f / 20f）
- BottomSheetの `sheetPeekHeight = 260.dp`（縦）/ `125.dp`（横）
  - 260dp = Transport(92) + SPEED(48) + Progress(54) + DragHandle(30) + ADオーバーレイ補正(35) + 余白
  - プログレスバーは折りたたみ状態でも常時表示
- ダイアログボタン配置：**YES（実行）= 左・控えめ、NO（キャンセル）= 右・目立つ** で全ダイアログ統一

## 広告（AdMob）設定

- **インタースティシャルのみ**（バナーなし）
- 再生終了3回に1回表示
- 本番IDは `local.properties` に設定済み（審査承認済み ✅）
- `BuildConfig.ADMOB_INTERSTITIAL_ID` でコードから参照
- importは必ず `import com.example.voicereader.BuildConfig` のみ
  - Android Studioが `com.tom_roush.pdfbox.BuildConfig` を自動追加することがあるので要注意・即削除

## TtsService の重要な仕様

- `seekTo(index)`: 指定センテンスにジャンプ（再生中なら即再生、停止中は位置移動のみ）
- `requestAudioFocus()`: 再生前に必ず呼ぶ。電話着信など割り込み時に自動停止・復帰
- `onDone()` の先頭に `if (!isCurrentlyPlaying) return` が必須（フォーカス喪失後の誤作動防止）

## リリース前チェックリスト

- [ ] アプリアイコン作成（512×512px）
- [ ] Keystoreの生成と安全な保管
- [ ] 署名付きAABのビルド（Build → Generate Signed Bundle）
- [ ] プライバシーポリシーの作成・公開URL取得
- [ ] Google Play Developer アカウント登録（$25）
- [ ] Play Console申請（スクリーンショット・説明文・アイコン）

## 実装パターン集

詳細なコード例は memory/patterns.md を参照すること。
