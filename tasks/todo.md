# MediaSession + MediaStyle 通知 実装計画

## 目標
ロック画面・通知バーから 前の文 / 再生停止 / 次の文 を操作できるようにする。

---

## 変更ファイル一覧

| ファイル | 変更内容 |
|---|---|
| `TtsService.kt` | ForegroundService 化・MediaSession・通知ボタン |
| `MainActivity.kt` | startService 追加・onPaused コールバック追加 |
| `AndroidManifest.xml` | 変更不要（権限・foregroundServiceType 既存） |
| `build.gradle.kts` | 変更不要（Android 標準 API のみ使用） |

---

## チェックリスト

### STEP 1: TtsService.kt に定数・フィールドを追加
- [ ] companion object に ACTION_PREV / ACTION_PLAY_PAUSE / ACTION_NEXT / ACTION_STOP を定義
- [ ] NOTIFICATION_ID = 1001、CHANNEL_ID = "tts_playback" を定義
- [ ] `mediaSession: MediaSession` フィールドを追加
- [ ] `isCurrentlyPlaying: Boolean` フィールドを追加（通知ボタンの状態管理用）

### STEP 2: TtsService.kt の onCreate() を修正
- [ ] `createNotificationChannel()` を呼ぶ
- [ ] `MediaSession` を初期化して PlaybackState を設定する
- [ ] `startService()` からも動けるよう onStartCommand() のベース処理を追加

### STEP 3: TtsService.kt に通知ビルド系メソッドを追加
- [ ] `createNotificationChannel()` を実装（IMPORTANCE_LOW）
- [ ] `buildNotification(isPlaying: Boolean): Notification` を実装
  - MediaStyle（mediaSession.sessionToken 付き）
  - ボタン3つ：⏮ PREV / ▶ or ⏸ PLAY_PAUSE / ⏭ NEXT
  - ongoing=true（再生中は消えない）
- [ ] `updateNotification(isPlaying: Boolean)` を実装

### STEP 4: onStartCommand() でボタン操作を受け取る
- [ ] Intent の action を判定して PREV / PLAY_PAUSE / NEXT / STOP を処理
- [ ] PREV → currentIndex を1つ戻して speakCurrentSentence()
- [ ] PLAY_PAUSE → 再生中なら一時停止、停止中なら再開
- [ ] NEXT → currentIndex を1つ進めて speakCurrentSentence()
- [ ] STOP → stop() を呼ぶ
- [ ] startForeground() を呼んで 5 秒ルール違反を防ぐ

### STEP 5: 既存メソッドへの通知フック追加（ロジックは変えない）
- [ ] `speakList()` の先頭に startForeground() 呼び出しを追加
- [ ] `speakCurrentSentence()` の末尾に updateNotification(true) を追加
- [ ] `stop()` の末尾に stopForeground(STOP_FOREGROUND_REMOVE) を追加
- [ ] onDestroy() に mediaSession.release() を追加

### STEP 6: TtsListener に onPaused() を追加
- [ ] `fun onPaused() {}` をデフォルト実装で interface に追加
  - 理由：通知の PAUSE ボタン押下時、UI の isPlaying だけを false にしたい
  - onComplete() だと currentSentenceIndex が 0 にリセットされてしまうため別コールバックが必要

### STEP 7: MainActivity.kt を修正
- [ ] onCreate() で `startService(Intent(this, TtsService::class.java))` を追加
  - 理由：bindService() だけでは startForeground() を呼べない（Android 8.0 制約）
- [ ] setListener() の中に `override fun onPaused() { isPlaying = false }` を追加

---

## 設計上の注意点（落とし穴）

### 5秒ルール（Android 8.0+）
startForegroundService() で起動したサービスは 5 秒以内に startForeground() を呼ばないと ANR。
→ onStartCommand() の中で必ず startForeground() を呼ぶ。

### 通知チャンネル（Android 8.0+）
通知を出す前に必ず createNotificationChannel() を一度呼ぶ必要がある。
→ onCreate() で作成する。

### PAUSE と STOP の違い
- PAUSE = tts.stop() するが currentIndex を保持。通知は残る。
- STOP = tts.stop() して currentIndex=0。通知も消える。
→ 通知ボタンの PLAY_PAUSE は PAUSE として実装する。

### PendingIntent の FLAG（Android 12+）
PendingIntent.getService() には FLAG_IMMUTABLE が必須。
→ `PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT` を使う。

### MainActivity と TtsService の同期
- 通知 PREV/NEXT → onStartCommand で speakCurrentSentence() → onProgress() コールバック → MainActivity の currentSentenceIndex・isPlaying が更新される（既存の仕組みで動く）
- 通知 PAUSE → onStartCommand で tts.stop() + onPaused() コールバック → MainActivity の isPlaying = false のみ更新
- 通知 STOP → stop() → onComplete() コールバック → MainActivity の isPlaying = false + index = 0

---

## レビュー（完了後に記入）
- [ ] ロック画面でボタン操作できるか確認
- [ ] 通知バーのボタン操作で UI が連動するか確認
- [ ] 再生完了後に通知が消えるか確認
- [ ] アプリ終了時に通知が消えるか確認
