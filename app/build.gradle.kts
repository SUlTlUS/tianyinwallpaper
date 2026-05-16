import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.JavaVersion
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.zeaze.tianyinwallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zeaze.tianyinwallpaper"
        minSdk = 24
        targetSdk = 36
        versionCode = 38
        versionName = "3.6.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // 支持环境变量配置签名（CI 环境）
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "tianyin123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "tianyin"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "tianyin123"
            } else {
                // 本地使用项目根目录下的 release keystore
                val keystoreFile = rootProject.file("app/tianyin.jks") 
                if (keystoreFile.exists()) {
                    storeFile = keystoreFile
                    storePassword = "tianyin123"
                    keyAlias = "tianyin"
                    keyPassword = "tianyin123"
                } else {
                    val fallbackFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                    if (fallbackFile.exists()) {
                        storeFile = fallbackFile
                        storePassword = "android"
                        keyAlias = "androiddebugkey"
                        keyPassword = "android"
                    }
                }
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        noCompress += listOf("tflite", "lite")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.freeCompilerArgs.add("-Xcontext-parameters")
}

dependencies {
    implementation(libs.kyant.shapes)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material.ripple)

    implementation(libs.reorderable)

    implementation(libs.kyant.shapes)

    // UI 与 架构
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.compose.material)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.fragment)

    // 单元测试与安卓测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    // 工具类库
    implementation(libs.androidx.documentfile)
    implementation(libs.glide)
    implementation(libs.fastjson)
    implementation(libs.android.picker.common)
    implementation(libs.android.picker.wheel)

    // 响应式与网络
    implementation(libs.rxjava)
    implementation(libs.rxandroid)
    implementation(libs.okhttp)
    implementation(libs.backdrop)
    implementation(libs.xpopup)
    
    // SplashScreen
    implementation("androidx.core:core-splashscreen:1.0.1")

    // MediaPlayer-Extended - 用于视频光栅的帧精确seek
    implementation(libs.mediaplayer.extended)
    implementation(libs.tensorflow.lite)

    // 其他
    debugImplementation(libs.androidx.compose.ui.tooling)
}
