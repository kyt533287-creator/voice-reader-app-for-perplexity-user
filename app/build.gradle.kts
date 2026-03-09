import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// local.properties から AdMob ID を読み込む
// local.properties は .gitignore 対象なので Git に上がらず安全
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

// local.properties に本番IDがなければ Google 公式テスト用IDを使う
val admobAppId = localProperties["ADMOB_APP_ID"] as String?
    ?: "ca-app-pub-3940256099942544~3347511713"
val admobInterstitialId = localProperties["ADMOB_INTERSTITIAL_ID"] as String?
    ?: "ca-app-pub-3940256099942544/1033173712"

android {
    namespace = "com.example.voicereader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.voicereader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // AndroidManifest.xml の ${ADMOB_APP_ID} を置換する
        manifestPlaceholders["ADMOB_APP_ID"] = admobAppId
        // コード内で BuildConfig.ADMOB_INTERSTITIAL_ID として参照できる
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"$admobInterstitialId\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true  // BuildConfig クラスを生成する（AdMob ID 参照に必要）
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // コルーチン（非同期処理用）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // HTML解析（Webページのテキスト取得用）
    implementation("org.jsoup:jsoup:1.17.2")

    implementation("androidx.compose.material:material-icons-extended")

    // PDF読み込み（BouncyCastleの競合を回避）
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }

    // AdMob（Google モバイル広告 SDK）
    implementation("com.google.android.gms:play-services-ads:23.3.0")
}
