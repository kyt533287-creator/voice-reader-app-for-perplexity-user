# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# クラッシュ時のスタックトレースで行番号を表示する（デバッグに必須）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------
# PDFBox（com.tom-roush:pdfbox-android）
# リフレクションでクラスを動的に呼び出すため、パッケージごと保護する
# ---------------------------------------------------------------
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-dontwarn com.tom_roush.fontbox.**

# ---------------------------------------------------------------
# Jsoup（HTMLパーサー）
# ---------------------------------------------------------------
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# ---------------------------------------------------------------
# アプリ本体のコードをすべて保護する
# 理由：MainActivity・TtsService内の匿名クラス（ServiceConnection・
# TtsListener・UtteranceProgressListenerなど）は
# Android システムがリフレクション経由で呼び出すため、
# 名前変更・削除されると動作しなくなる
# ---------------------------------------------------------------
-keep class com.example.voicereader.** { *; }

# ---------------------------------------------------------------
# WorkManager（AdMobがSDK内部で使用）
# R8がクラスを削除すると「Failed to create an instance of
# androidx.work.impl.WorkDatabase」でクラッシュする
# ---------------------------------------------------------------
-keep class androidx.work.** { *; }
-keep class androidx.work.impl.WorkDatabase
-keep class androidx.work.impl.WorkDatabase_Impl
-dontwarn androidx.work.**