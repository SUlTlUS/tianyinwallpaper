package com.zeaze.tianyinwallpaper.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import com.alibaba.fastjson.JSON
import com.zeaze.tianyinwallpaper.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 应用更新管理器
 * 负责检查更新、下载 APK、安装 APK
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    const val PREF_CHECK_UPDATE_ON_START = "pref_check_update_on_start"
    
    // 更新信息 JSON 文件的远程地址列表 (会依次尝试)
    // 主地址: GitHub raw (带时间戳防缓存)
    // 备用地址: jsDelivr CDN (国内访问稳定)
    private val updateInfoUrls = listOf(
        "https://raw.githubusercontent.com/SUlTlUS/tianyinwallpaper/master/update/update_info.json",
        "https://cdn.jsdelivr.net/gh/SUlTlUS/tianyinwallpaper@master/update/update_info.json"
    )

    private val changelogUrls = listOf(
        "https://raw.githubusercontent.com/SUlTlUS/tianyinwallpaper/master/update/changelog.md",
        "https://cdn.jsdelivr.net/gh/SUlTlUS/tianyinwallpaper@master/update/changelog.md"
    )
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    
    private var downloadId: Long = -1
    private var downloadedApkPath: String? = null
    
    /**
     * 检查更新结果
     */
    sealed class CheckResult {
        // 有新版本
        data class HasUpdate(val updateInfo: UpdateInfo) : CheckResult()
        // 已是最新版本
        object NoUpdate : CheckResult()
        // 检查失败
        data class Error(val message: String) : CheckResult()
    }
    
    /**
     * 检查更新
     * @return 检查结果
     */
    suspend fun checkUpdate(): CheckResult = withContext(Dispatchers.IO) {
        var lastError: String = "网络请求失败"
        
        // 依次尝试多个地址
        for (url in updateInfoUrls) {
            try {
                // 如果是 GitHub raw 地址，加上时间戳防缓存
                val targetUrl = if (url.contains("raw.githubusercontent.com")) {
                    "$url?t=${System.currentTimeMillis()}"
                } else {
                    url
                }
                
                Log.d(TAG, "尝试从 $targetUrl 获取更新信息")
                
                val request = Request.Builder()
                    .url(targetUrl)
                    .get()
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    lastError = "网络请求失败: ${response.code}"
                    Log.w(TAG, "$url 请求失败: ${response.code}")
                    continue
                }
                
                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    lastError = "响应内容为空"
                    Log.w(TAG, "$url 响应内容为空")
                    continue
                }
                
                val updateInfo = JSON.parseObject(body, UpdateInfo::class.java)
                Log.d(TAG, "解析到的远端更新配置: code=${updateInfo.code}, name=${updateInfo.name}, 当前应用版本号: ${BuildConfig.VERSION_CODE}")
                
                // 比较版本号
                return@withContext if (updateInfo.code > BuildConfig.VERSION_CODE) {
                    Log.d(TAG, "发现新版本！远端(${updateInfo.code}) > 当前(${BuildConfig.VERSION_CODE})")
                    updateInfo.des = buildAccumulatedChangelog(
                        latestVersionName = updateInfo.name,
                        fallbackDescription = updateInfo.des
                    )
                    CheckResult.HasUpdate(updateInfo)
                } else {
                    Log.d(TAG, "当前已是最新版本或开发版。远端(${updateInfo.code}) <= 当前(${BuildConfig.VERSION_CODE})")
                    CheckResult.NoUpdate
                }
                
            } catch (e: Exception) {
                Log.w(TAG, "$url 请求异常: ${e.message}")
                lastError = "检查更新失败: ${e.message}"
            }
        }
        
        CheckResult.Error(lastError)
    }

    private fun buildAccumulatedChangelog(
        latestVersionName: String,
        fallbackDescription: String
    ): String {
        val changelog = fetchFirstAvailableText(changelogUrls) ?: return fallbackDescription
        val installedVersionName = BuildConfig.VERSION_NAME
        val matchedSections = parseChangelogSections(changelog).filter { section ->
            compareVersionNames(section.version, installedVersionName) > 0 &&
                compareVersionNames(section.version, latestVersionName) <= 0
        }

        if (matchedSections.isEmpty()) return fallbackDescription

        return matchedSections.joinToString("\n\n") { section ->
            buildString {
                append(section.version)
                if (section.content.isNotBlank()) {
                    append('\n')
                    append(section.content)
                }
            }
        }
    }

    private fun fetchFirstAvailableText(urls: List<String>): String? {
        for (url in urls) {
            try {
                val targetUrl = if (url.contains("raw.githubusercontent.com")) {
                    "$url?t=${System.currentTimeMillis()}"
                } else {
                    url
                }
                val request = Request.Builder()
                    .url(targetUrl)
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "$url request failed: ${response.code}")
                        return@use
                    }

                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        return body
                    }
                    Log.w(TAG, "$url response body is empty")
                }
            } catch (e: Exception) {
                Log.w(TAG, "$url request error: ${e.message}")
            }
        }
        return null
    }

    private fun parseChangelogSections(changelog: String): List<ChangelogSection> {
        val headingRegex = Regex("""^##\s+(.+?)\s*$""")
        val sections = mutableListOf<ChangelogSection>()
        var currentVersion: String? = null
        val currentContent = StringBuilder()

        fun flushCurrentSection() {
            val version = currentVersion ?: return
            sections += ChangelogSection(
                version = version,
                content = currentContent.toString().trim()
            )
            currentContent.clear()
        }

        changelog.lineSequence().forEach { line ->
            val heading = headingRegex.matchEntire(line.trim())
            if (heading != null) {
                flushCurrentSection()
                currentVersion = heading.groupValues[1].trim()
            } else if (currentVersion != null) {
                currentContent.appendLine(line)
            }
        }
        flushCurrentSection()

        return sections
    }

    private fun compareVersionNames(left: String, right: String): Int {
        val leftParts = parseVersionParts(left)
        val rightParts = parseVersionParts(right)
        val maxSize = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until maxSize) {
            val leftPart = leftParts.getOrElse(index) { 0 }
            val rightPart = rightParts.getOrElse(index) { 0 }
            if (leftPart != rightPart) {
                return leftPart.compareTo(rightPart)
            }
        }
        return 0
    }

    private fun parseVersionParts(version: String): List<Int> {
        return version
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.')
            .mapNotNull { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull()
            }
    }

    private data class ChangelogSection(
        val version: String,
        val content: String
    )
    
    /**
     * 下载进度回调
     */
    interface DownloadCallback {
        fun onProgress(progress: Int) // 进度 0-100
        fun onSuccess(file: File)
        fun onError(message: String)
    }
    
    /**
     * 使用系统 DownloadManager 下载 APK
     */
    fun downloadApk(
        context: Context,
        updateInfo: UpdateInfo,
        callback: DownloadCallback
    ): Long {
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val targetFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), updateInfo.filename)
        if (targetFile.exists() && !targetFile.delete()) {
            Log.w(TAG, "Failed to delete existing apk before download: ${targetFile.absolutePath}")
        }
        
        // 创建下载请求
        val request = DownloadManager.Request(Uri.parse(updateInfo.url))
            .setTitle("天音壁纸更新")
            .setDescription("正在下载 ${updateInfo.name}")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                updateInfo.filename
            )
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        
        // 开始下载
        downloadId = downloadManager.enqueue(request)
        
        // 监听下载进度
        val handler = android.os.Handler(context.mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                val query = DownloadManager.Query().setFilterById(downloadId)
                val cursor: Cursor = downloadManager.query(query)
                
                if (cursor.moveToFirst()) {
                    val bytesDownloadedIndex = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    val bytesTotalIndex = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    
                    if (bytesDownloadedIndex != -1 && bytesTotalIndex != -1 && statusIndex != -1) {
                        val bytesDownloaded = cursor.getLong(bytesDownloadedIndex)
                        val bytesTotal = cursor.getLong(bytesTotalIndex)
                        val status = cursor.getInt(statusIndex)
                        
                        if (bytesTotal > 0) {
                            val progress = ((bytesDownloaded * 100) / bytesTotal).toInt()
                            callback.onProgress(progress)
                        }
                        
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            // 获取下载文件路径
                            val localUriIndex = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                            if (localUriIndex != -1) {
                                val localUri = cursor.getString(localUriIndex)
                                downloadedApkPath = localUri?.let { Uri.parse(it).path }
                                downloadedApkPath?.let {
                                    val file = File(it)
                                    if (file.exists()) {
                                        callback.onSuccess(file)
                                    } else {
                                        callback.onError("下载成功但文件不存在")
                                    }
                                } ?: run {
                                    callback.onError("无法解析下载路径")
                                }
                            } else {
                                callback.onError("获取下载路径失败")
                            }
                            cursor.close()
                            return
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            val reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                            val reason = if (reasonIndex != -1) cursor.getInt(reasonIndex) else -1
                            cursor.close()
                            callback.onError("下载失败，错误码: $reason")
                            return
                        }
                    }
                }
                cursor.close()
                handler.postDelayed(this, 500)
            }
        }
        handler.post(runnable)
        
        return downloadId
    }
    
    /**
     * 获取下载完成的 APK 文件
     */
    fun getDownloadedApkFile(context: Context, filename: String): File? {
        val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), filename)
        return if (file.exists()) file else null
    }

    /**
     * 启动时清理下载目录中已过期的 APK：
     * 仅删除包名匹配当前应用且版本号小于等于已安装版本的文件。
     */
    fun clearOutdatedDownloadedApks(context: Context) {
        val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val installedVersionCode = getInstalledVersionCode(context)

        downloadDir.listFiles { file ->
            file.isFile && file.extension.equals("apk", ignoreCase = true)
        }?.forEach { apkFile ->
            val archiveInfo = runCatching {
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            }.getOrNull() ?: return@forEach

            if (archiveInfo.packageName != context.packageName) return@forEach

            val archiveVersionCode = getArchiveVersionCode(archiveInfo)
            if (archiveVersionCode <= installedVersionCode) {
                if (!apkFile.delete()) {
                    Log.w(TAG, "Failed to delete outdated apk: ${apkFile.absolutePath}")
                } else {
                    Log.d(TAG, "Deleted outdated apk: ${apkFile.absolutePath}, version=$archiveVersionCode, installed=$installedVersionCode")
                }
            }
        }
    }

    private fun getInstalledVersionCode(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return getArchiveVersionCode(packageInfo)
    }

    private fun getArchiveVersionCode(packageInfo: android.content.pm.PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    }

    fun getApkVersionCode(context: Context, file: File): Long? {
        if (!file.exists() || !file.isFile) return null
        val packageInfo = runCatching {
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        }.getOrNull() ?: return null
        return getArchiveVersionCode(packageInfo)
    }

    /**
     * 计算文件的 MD5 值
     */
    fun calculateMD5(file: File): String? {
        if (!file.exists() || !file.isFile) {
            return null
        }
        
        return try {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } > 0) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.e(TAG, "计算 MD5 失败", e)
            null
        }
    }
    
    /**
     * 安装 APK
     */
    fun installApk(context: Context, file: File): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW)
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ 使用 FileProvider
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }
            
            intent.setDataAndType(uri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "安装 APK 失败", e)
            false
        }
    }
    
    /**
     * 格式化文件大小
     */
    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
            else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
