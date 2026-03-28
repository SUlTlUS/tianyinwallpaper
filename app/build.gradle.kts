import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.JavaVersion
import java.security.MessageDigest
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
        versionCode = 32
        versionName = "3.2"
        multiDexEnabled = true
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
            isMinifyEnabled = false
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
    
    // MediaPlayer-Extended - 用于视频光栅的帧精确seek
    implementation(libs.mediaplayer.extended)

    // 其他
    implementation(libs.androidx.multidex)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ==================== 自动生成更新信息 ====================

// 计算 MD5
fun generateMD5(file: File): String {
    if (!file.exists() || !file.isFile) {
        return ""
    }
    val digest = MessageDigest.getInstance("MD5")
    file.inputStream().use { input ->
        val buffer = ByteArray(8192)
        var read: Int
        while (input.read(buffer).also { read = it } > 0) {
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// 生成更新信息 JSON
fun generateUpdateInfo(
    apkFile: File,
    versionCode: Int,
    versionName: String,
    updateDir: File,
    updateInfoFile: File,
    baseUrl: String,
    versionDes: String
) {
    println("------------------ Generating update info ------------------")

    if (!apkFile.exists()) {
        println("APK file not found: ${apkFile.absolutePath}")
        return
    }

    val apkHash = generateMD5(apkFile)

    // 检查是否需要更新
    var writeNewFile = true
    if (updateInfoFile.exists()) {
        try {
            val oldContent = updateInfoFile.readText()
            val oldCodeRegex = """"code"\s*:\s*(\d+)""".toRegex()
            val oldMd5Regex = """"md5"\s*:\s*"([^"]+)"""".toRegex()
            val oldCode = oldCodeRegex.find(oldContent)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val oldMd5 = oldMd5Regex.find(oldContent)?.groupValues?.get(1) ?: ""

            if (versionCode <= oldCode && apkHash == oldMd5) {
                writeNewFile = false
                println("This version is already released. Skip generating update info.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    if (writeNewFile) {
        // 创建更新目录
        if (!updateDir.exists()) {
            updateDir.mkdirs()
        }

        // 复制 APK 到更新目录
        val targetApk = File(updateDir, apkFile.name)
        apkFile.copyTo(targetApk, overwrite = true)

        // 生成 JSON
        val json = """
{
    "code": $versionCode,
    "name": "$versionName",
    "filename": "${apkFile.name}",
    "url": "${baseUrl}${apkFile.name}",
    "time": ${System.currentTimeMillis()},
    "des": "${versionDes.replace("\n", "\\n")}",
    "size": ${apkFile.length()},
    "md5": "$apkHash"
}
        """.trimIndent()

        updateInfoFile.writeText(json)
        println("Generated update info:")
        println(json)
    }

    println("------------------ Finish generating update info ------------------")
}

// 创建生成更新信息的 Task
tasks.register("generateUpdateInfo") {
    group = "build"
    description = "Generate update_info.json after building release APK"

    doLast {
        val versionCode = android.defaultConfig.versionCode ?: return@doLast
        val versionName = android.defaultConfig.versionName ?: return@doLast
        val updateDir = file("${project.rootDir}/update")
        val updateInfoFile = File(updateDir, "update_info.json")
        val baseUrl = "https://raw.githubusercontent.com/SUlTlUS/tianyinwallpaper/master/update/"
        // 更新说明：可以在打包前修改这里，或通过环境变量传入
        val versionDes = System.getenv("UPDATE_DES") ?: ""

        // 查找生成的 APK 文件
        val apkDir = file("build/outputs/apk/release")
        val apkFile = apkDir.listFiles()?.firstOrNull { it.extension == "apk" }

        if (apkFile != null && apkFile.exists()) {
            generateUpdateInfo(
                apkFile = apkFile,
                versionCode = versionCode,
                versionName = versionName,
                updateDir = updateDir,
                updateInfoFile = updateInfoFile,
                baseUrl = baseUrl,
                versionDes = versionDes
            )
        } else {
            println("No APK file found in ${apkDir.absolutePath}")
        }
    }
}

// 让 assembleRelease 完成后自动执行 generateUpdateInfo
project.afterEvaluate {
    tasks.findByName("assembleRelease")?.finalizedBy("generateUpdateInfo")
}