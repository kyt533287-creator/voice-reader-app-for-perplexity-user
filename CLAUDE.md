# VoiceReader UI プロジェクト固有ルール

## アプリ概要

- **アプリ名**: VoiceReader
- **パッケージ名**: `com.example.voicereader`
- **技術スタック**: Jetpack Compose + Material3 + Android TTS
- **メイン実装ファイル**: `app/src/main/java/com/example/voicereader/MainActivity.kt`

## このアプリ固有のUI方針

- 画面遷移はすべて `MainActivity.kt` 内の `currentScreen` 変数で管理（シングルファイル構成）
- ヘッダーのボタンで保存・編集を完結させる（画面下部に同じ機能のボタンを置かない）
- フォントサイズはS/M/Lの3段階切り替え（13f / 16f / 20f）
- 進行度バーはスクロール可能なときのみ表示する

## 広告（AdMob）ルール

- **現在このブランチ（5_VoiceReader_UI）は広告なし**
- 広告実装バージョンは `8_VoiceReader_Ad_2` ブランチを参照
- 広告を追加する場合は必ず `local.properties` にIDを設定してからビルドする
  ```
  ADMOB_APP_ID=ca-app-pub-XXXXXXXX~XXXXXXXXXX
  ADMOB_BANNER_ID=ca-app-pub-XXXXXXXX/XXXXXXXXXX
  ADMOB_INTERSTITIAL_ID=ca-app-pub-XXXXXXXX/XXXXXXXXXX
  ```
- バナー広告は画面下部に固定配置し、コンテンツ領域を必ず押しのける（重ねない）

## 実装パターン集

詳細なコード例は memory/patterns.md を参照すること。
