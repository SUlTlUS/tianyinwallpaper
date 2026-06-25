package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import com.zeaze.tianyinwallpaper.App
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * 通过 Gradio Queue API 调用远端 SHARP 推理服务，在线生成 SOG 3D 模型。
 *
 * 为什么要用 Gradio 原生 API 而不是 MCP：
 * - ModelScope 的 api-inference 代理对所有 HTTP 请求强制鉴权
 * - MCP tools/call 内部会把 image 参数当 URL 下载，但服务端回下自己的文件时
 *   不会带 auth header → 403 Forbidden
 * - Gradio 原生 /call API 接受服务端本地路径（FileData），不走 URL 下载，完全绕开此问题
 *
 * 流程：
 * 1. 唤醒服务（处理 ModelScope 冷启动）
 * 2. 上传图片到 /gradio_api/upload → 获得服务端本地路径
 * 3. POST /gradio_api/call/generate_sog 提交任务 → 获得 event_id
 * 4. 轮询 /gradio_api/call/generate_sog/{event_id} 等待完成
 * 5. 解析 SSE 结果，提取 SOG 文件下载 URL
 * 6. 下载 SOG 到本地
 * 7. 记录生成历史
 */
object GradioMcpSogGenerator {
    private const val TAG = "GradioSogGen"

    const val DEFAULT_ONLINE_GENERATION_BASE_URL = "https://studio-mi0hn0-ml-sharp.api-inference.modelscope.net"
    const val DEFAULT_LOCAL_GENERATION_BASE_URL = "http://192.168.1.177:7860"
    const val PREF_ONLINE_GENERATION_BASE_URL = "online_generation_base_url"
    const val PREF_LOCAL_GENERATION_BASE_URL = "local_generation_base_url"
    const val PREF_GENERATION_SERVICE_TYPE = "generation_service_type"
    const val SERVICE_TYPE_ONLINE = 0
    const val SERVICE_TYPE_LOCAL = 1
    private const val TASK_TIMEOUT_MINUTES = 15L
    /** 轮询重试总窗口（含网络中断恢复时间），远大于生成超时 */
    private const val POLL_RETRY_WINDOW_MINUTES = 60L
    /** task_id HTTP 查询间隔 */
    private const val TASK_ID_POLL_INTERVAL_SEC = 30L
    private const val LAN_TASK_ID_POLL_INTERVAL_SEC = 3L
    private const val TASK_QUERY_RATE_LIMIT_MIN_SEC = 60L
    private const val TASK_QUERY_RATE_LIMIT_MAX_SEC = 300L
    private const val PREFS_NAME = "sog_generation_history"
    private const val KEY_RECORDS = "records"
    private const val KEY_MODELSCOPE_TOKEN = "modelscope_sdk_token"
    private const val WARMUP_TIMEOUT_SEC = 180L
    private const val WARMUP_RETRY_COUNT = 3
    private const val LOG_DIR_NAME = "sog_generation_logs"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val logDateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val taskQueryLock = Any()
    private val activeRecordPollLock = Any()
    private val activeRecordPollIds = mutableSetOf<String>()
    @Volatile private var nextTaskQueryAtMs = 0L
    @Volatile private var consecutiveTaskQueryRateLimits = 0
    @Volatile private var taskQueryBaseUrl: String? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 短超时客户端，用于唤醒和快速请求 */
    private val quickClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    /** SSE 轮询客户端，超时较长 */
    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    data class Progress(
        val status: String,
        val percent: Float = -1f,
        val elapsedSec: Float = 0f
    )

    interface ProgressCallback {
        fun onProgress(progress: Progress)
    }

    // ─── ModelScope SDK Token ──────────────────────────────────────

    /**
     * 保存 ModelScope SDK Token，用于 API Inference 端点鉴权。
     * 获取方式：ModelScope 个人设置 → API-SDK Token → 创建（格式 ms-xxxxxx）
     */
    fun setModelScopeToken(context: Context, token: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODELSCOPE_TOKEN, token).apply()
    }

    fun getModelScopeToken(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MODELSCOPE_TOKEN, null)
    }

    fun getGenerationServiceType(context: Context): Int {
        ensureServiceConfigMigrated(context)
        return context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
            .getInt(PREF_GENERATION_SERVICE_TYPE, SERVICE_TYPE_ONLINE)
            .takeIf { it == SERVICE_TYPE_LOCAL } ?: SERVICE_TYPE_ONLINE
    }

    fun setGenerationServiceType(context: Context, serviceType: Int) {
        ensureServiceConfigMigrated(context)
        val normalized = if (serviceType == SERVICE_TYPE_LOCAL) SERVICE_TYPE_LOCAL else SERVICE_TYPE_ONLINE
        context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
            .edit()
            .putInt(PREF_GENERATION_SERVICE_TYPE, normalized)
            .apply()
    }

    fun getOnlineGenerationBaseUrl(context: Context): String {
        return getGenerationBaseUrl(context, getGenerationServiceType(context))
    }

    fun getGenerationBaseUrl(context: Context, serviceType: Int): String {
        ensureServiceConfigMigrated(context)
        val prefs = context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
        val isLocal = serviceType == SERVICE_TYPE_LOCAL
        val key = if (isLocal) PREF_LOCAL_GENERATION_BASE_URL else PREF_ONLINE_GENERATION_BASE_URL
        val defaultUrl = if (isLocal) DEFAULT_LOCAL_GENERATION_BASE_URL else DEFAULT_ONLINE_GENERATION_BASE_URL
        return normalizeConfiguredBaseUrl(
            prefs.getString(key, defaultUrl)
        ) ?: defaultUrl
    }

    fun setGenerationBaseUrl(context: Context, serviceType: Int, value: String): String {
        val normalized = normalizeConfiguredBaseUrl(value)
            ?: error("生成地址无效")
        val key = if (serviceType == SERVICE_TYPE_LOCAL) {
            PREF_LOCAL_GENERATION_BASE_URL
        } else {
            PREF_ONLINE_GENERATION_BASE_URL
        }
        context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
            .edit()
            .putString(key, normalized)
            .apply()
        return normalized
    }

    private fun ensureServiceConfigMigrated(context: Context) {
        val prefs = context.getSharedPreferences(App.TIANYIN, Context.MODE_PRIVATE)
        if (prefs.contains(PREF_GENERATION_SERVICE_TYPE)) return

        val legacyUrl = normalizeConfiguredBaseUrl(
            prefs.getString(PREF_ONLINE_GENERATION_BASE_URL, DEFAULT_ONLINE_GENERATION_BASE_URL)
        ) ?: DEFAULT_ONLINE_GENERATION_BASE_URL
        val legacyWasLocal = isLanBaseUrl(legacyUrl)
        prefs.edit()
            .putInt(
                PREF_GENERATION_SERVICE_TYPE,
                if (legacyWasLocal) SERVICE_TYPE_LOCAL else SERVICE_TYPE_ONLINE
            )
            .putString(
                PREF_ONLINE_GENERATION_BASE_URL,
                if (legacyWasLocal) DEFAULT_ONLINE_GENERATION_BASE_URL else legacyUrl
            )
            .putString(
                PREF_LOCAL_GENERATION_BASE_URL,
                if (legacyWasLocal) legacyUrl else DEFAULT_LOCAL_GENERATION_BASE_URL
            )
            .apply()
    }

    private fun normalizeConfiguredBaseUrl(value: String?): String? {
        val raw = value?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank()) return null
        return normalized
    }

    private fun baseUrl(context: Context) = getOnlineGenerationBaseUrl(context)
    private fun uploadEndpoint(context: Context) = "${baseUrl(context)}/gradio_api/upload"
    private fun callEndpoint(context: Context) = "${baseUrl(context)}/gradio_api/call/generate_sog"
    private fun checkEndpoint(context: Context) = "${baseUrl(context)}/api/check_sog_result"
    private fun downloadEndpoint(context: Context) = "${baseUrl(context)}/api/download_sog"

    private fun taskIdPollIntervalSec(context: Context): Long {
        return if (isLanBaseUrl(baseUrl(context))) {
            LAN_TASK_ID_POLL_INTERVAL_SEC
        } else {
            TASK_ID_POLL_INTERVAL_SEC
        }
    }

    private fun isLanBaseUrl(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost" || host == "::1" || host.endsWith(".local")) return true

        val parts = host.split('.')
        if (parts.size != 4) {
            return host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80:")
        }
        val octets = parts.map { it.toIntOrNull() ?: return false }
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    // ─── 生成历史记录 ─────────────────────────────────────────────

    data class SogGenerationRecord(
        val id: String = UUID.randomUUID().toString(),
        val inputImageName: String? = null,
        val inputImageUrl: String? = null,
        /** 本地图片 URI（content:// 或 file://），用于在列表中显示缩略图 */
        val inputImageLocalUri: String? = null,
        /** 服务端 Gradio SSE event_id，用于轮询任务状态 */
        val eventId: String? = null,
        /** 客户端生成的任务 ID，传给服务端 generate_sog，用于 check_sog_result 查询 */
        val taskId: String? = null,
        val sogServerUrl: String? = null,
        val sogLocalPath: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        /** "pending" | "generating" | "completed" | "failed" */
        val status: String = "pending",
        val errorMessage: String? = null
    ) {
        /** SOG 是否已下载到本地 */
        val sogDownloaded: Boolean get() = !sogLocalPath.isNullOrBlank()
    }

    fun addRecord(context: Context, record: SogGenerationRecord) {
        val records = getHistory(context).toMutableList()
        records.add(0, record)
        // 最多保留 50 条；被裁掉的记录同步清理缓存缩略图
        if (records.size > 50) {
            val removed = records.subList(50, records.size).toList()
            removed.forEach { deleteRecordThumbnail(it) }
            records.subList(50, records.size).clear()
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDS, JSON.toJSONString(records)).apply()
    }

    fun getHistory(context: Context): List<SogGenerationRecord> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSON.parseArray(json) ?: return emptyList()
            (0 until arr.size).mapNotNull { i ->
                val obj = arr.getJSONObject(i) ?: return@mapNotNull null
                SogGenerationRecord(
                    id = obj.getString("id") ?: UUID.randomUUID().toString(),
                    inputImageName = obj.getString("inputImageName"),
                    inputImageUrl = obj.getString("inputImageUrl"),
                    inputImageLocalUri = obj.getString("inputImageLocalUri"),
                    eventId = obj.getString("eventId"),
                    taskId = obj.getString("taskId"),
                    sogServerUrl = obj.getString("sogServerUrl"),
                    sogLocalPath = obj.getString("sogLocalPath"),
                    createdAt = obj.getLongValue("createdAt").let { if (it == 0L) System.currentTimeMillis() else it },
                    status = obj.getString("status") ?: "pending",
                    errorMessage = obj.getString("errorMessage")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun exportHistoryJson(context: Context): String {
        return JSON.toJSONString(getHistory(context))
    }

    fun restoreHistoryJson(context: Context, json: String) {
        val records = JSON.parseArray(json) ?: error("Invalid SOG generation history")
        val limitedRecords = records.subList(0, records.size.coerceAtMost(50))
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECORDS, JSON.toJSONString(limitedRecords))
            .apply()
    }

    fun updateRecord(context: Context, id: String, transform: (SogGenerationRecord) -> SogGenerationRecord) {
        val records = getHistory(context).toMutableList()
        val idx = records.indexOfFirst { it.id == id }
        if (idx >= 0) {
            records[idx] = transform(records[idx])
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_RECORDS, JSON.toJSONString(records)).apply()
        }
    }

    fun deleteRecord(context: Context, id: String) {
        val oldRecords = getHistory(context)
        oldRecords.firstOrNull { it.id == id }?.let { record ->
            deleteRecordThumbnail(record)
            deleteRecordSogFiles(context, record)
        }
        val records = oldRecords.filter { it.id != id }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_RECORDS, JSON.toJSONString(records)).apply()
    }

    fun clearHistory(context: Context) {
        getHistory(context).forEach { record ->
            deleteRecordThumbnail(record)
            deleteRecordSogFiles(context, record)
        }
        getThumbnailDir(context).listFiles()?.forEach { it.delete() }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_RECORDS).apply()
    }

    /** 通过服务端 URL 重新下载 SOG 到本地 */
    fun redownloadSog(context: Context, serverUrl: String): Uri {
        return downloadSog(context, serverUrl)
    }

    // ─── 缩略图持久化 ──────────────────────────────────────────

    private const val THUMB_DIR_NAME = "sog_thumbnails"
    private const val INPUT_DIR_NAME = "sog_inputs"
    private const val THUMB_MAX_SIDE_PX = 720
    private const val THUMB_JPEG_QUALITY = 82

    fun copyInputImageToCache(context: Context, sourceUri: Uri, displayName: String? = null): Uri {
        val dir = File(context.cacheDir, INPUT_DIR_NAME).apply { mkdirs() }
        val extension = displayName
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.takeIf { it.length in 1..8 && it.all { ch -> ch.isLetterOrDigit() } }
            ?: "jpg"
        val outFile = File(dir, "input_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.$extension")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot read selected image")
        if (outFile.length() <= 0L) {
            outFile.delete()
            error("Selected image is empty")
        }
        return Uri.fromFile(outFile)
    }

    /**
     * 将在线生成记录的输入图压缩成小缩略图后保存到 app 缓存。
     *
     * 不再直接复制原图，避免把相册大图塞进应用私有目录。返回的是缓存内 JPEG 文件路径。
     */
    fun saveThumbnail(context: Context, sourceUri: Uri): String? {
        return try {
            val dir = getThumbnailDir(context)
            val outFile = File(dir, "thumb_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.jpg")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "保存缩略图失败: 无法读取图片尺寸")
                return null
            }

            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, THUMB_MAX_SIDE_PX * 2, THUMB_MAX_SIDE_PX * 2)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decoded = context.contentResolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            val thumb = scaleBitmapInsideMaxSide(decoded, THUMB_MAX_SIDE_PX)
            if (thumb !== decoded) decoded.recycle()

            outFile.outputStream().use { output ->
                thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, output)
            }
            thumb.recycle()

            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "保存缩略图失败: ${e.message}")
            null
        }
    }

    private fun getThumbnailDir(context: Context): File {
        return File(context.cacheDir, THUMB_DIR_NAME).apply { mkdirs() }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * 只压缩尺寸，不裁剪画面。
     *
     * 缩略图文件保留完整构图；不同 UI 场景需要什么比例，就在 Compose 容器上用
     * width/height/aspectRatio 决定。生成记录列表目前用方形容器 + ContentScale.Crop 显示。
     */
    private fun scaleBitmapInsideMaxSide(source: Bitmap, maxSide: Int): Bitmap {
        val longestSide = maxOf(source.width, source.height)
        if (longestSide <= maxSide) return source

        val scale = maxSide.toFloat() / longestSide.toFloat()
        val targetWidth = (source.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    private fun deleteRecordThumbnail(record: SogGenerationRecord) {
        val path = record.inputImageLocalUri ?: return
        runCatching {
            val file = File(path)
            val thumbDirName = THUMB_DIR_NAME + File.separator
            if (file.exists() && file.absolutePath.contains(thumbDirName)) {
                file.delete()
            }
        }
    }

    /**
     * 删除生成记录时同步清理本地 SOG 和未完成的断点续传 .part 文件。
     *
     * 只删除在线生成器自己的 generated_sog 目录中的文件，避免误删用户手动选择的外部文件。
     * 如果这条记录已经导入为壁纸，壁纸那边已经通过 DepthPrefs.copySogToAppDir 复制了一份独立文件，
     * 删除生成记录不会影响已导入的壁纸文件。
     */
    private fun deleteRecordSogFiles(context: Context, record: SogGenerationRecord) {
        runCatching {
            val generatedDir = File(context.filesDir, GENERATED_SOG_DIR_NAME).canonicalFile
            val localPath = record.sogLocalPath
            if (!localPath.isNullOrBlank()) {
                val file = File(localPath).canonicalFile
                if (file.exists() && file.path.startsWith(generatedDir.path + File.separator)) {
                    file.delete()
                }
            }

            // 下载失败时会保留稳定文件名的 .part，删除记录时一起清理。
            val stableBaseName = "generated_${safeFileName(record.id)}.sog"
            File(generatedDir, stableBaseName).takeIf { it.exists() }?.delete()
            File(generatedDir, "$stableBaseName.part").takeIf { it.exists() }?.delete()
        }.onFailure { e ->
            Log.w(TAG, "删除记录 SOG 失败: ${e.message}")
        }
    }

    // ─── 生成入口（完整流程，保留向后兼容）─────────────────────────

    fun generateFromLocalImage(context: Context, localUri: Uri, onProgress: ProgressCallback? = null): Uri {
        closeLog()
        logMsg(context, "=== 开始在线生成 SOG ===")
        logMsg(context, "输入图片 URI: $localUri")

        onProgress?.onProgress(Progress("唤醒服务…"))
        warmUpServer(context)
        logMsg(context, "服务唤醒完成")

        onProgress?.onProgress(Progress("上传图片…"))
        val uploadResult = uploadToGradio(context, localUri)
        logMsg(context, "上传完成: serverPath=${uploadResult.serverPath}, origName=${uploadResult.origName}")

        onProgress?.onProgress(Progress("提交生成任务…"))
        val startTime = System.currentTimeMillis()
        val eventId = submitGenerateTask(context, uploadResult.serverPath)
        logMsg(context, "任务已提交: event_id=$eventId")

        onProgress?.onProgress(Progress("生成中…"))
        val sogUrl = pollForResult(context, eventId, null) { status, pct ->
            val elapsed = (System.currentTimeMillis() - startTime) / 1000f
            onProgress?.onProgress(Progress(status, pct, elapsed))
        }
        logMsg(context, "生成完成: sogUrl=$sogUrl")

        onProgress?.onProgress(Progress("下载 SOG…"))
        val localSogUri = downloadSog(context, sogUrl)
        logMsg(context, "下载完成: localPath=${localSogUri.path}")

        addRecord(context, SogGenerationRecord(
            inputImageName = uploadResult.origName,
            inputImageUrl = uploadResult.serverUrl,
            eventId = eventId,
            sogServerUrl = sogUrl,
            sogLocalPath = localSogUri.path,
            status = "completed"
        ))

        logMsg(context, "=== 生成成功 ===")
        closeLog()
        return localSogUri
    }

    // ─── 分步 API（供 UI 异步调用）─────────────────────────────────

    /**
     * 步骤0：立即创建一条 pending 记录并保存缩略图，返回记录 ID。
     * 在用户选择图片后立即调用，让 UI 有即时反馈。
     */
    fun createPendingRecord(context: Context, localUri: Uri, fileName: String? = null): String {
        val thumbPath = saveThumbnail(context, localUri)
        val name = fileName ?: localUri.toString().substringAfterLast('/')
        val record = SogGenerationRecord(
            inputImageName = name,
            inputImageLocalUri = thumbPath ?: localUri.toString(),
            status = "pending"
        )
        addRecord(context, record)
        return record.id
    }

    /**
     * 步骤1：上传图片并提交生成任务，更新已有的 pending 记录为 generating。
     * @param recordId 由 createPendingRecord 返回的记录 ID
     */
    fun submitTaskForRecord(context: Context, recordId: String, localUri: Uri): String {
        closeLog()
        logMsg(context, "=== 提交生成任务 ===")
        logMsg(context, "输入图片 URI: $localUri")

        // 生成 taskId，用于服务端结果查询
        val taskId = UUID.randomUUID().toString().replace("-", "")
        logMsg(context, "task_id=$taskId")

        logMsg(context, "唤醒服务…")
        warmUpServer(context)
        logMsg(context, "服务唤醒完成")

        logMsg(context, "上传图片…")
        val uploadResult = uploadToGradio(context, localUri)
        logMsg(context, "上传完成: serverPath=${uploadResult.serverPath}, origName=${uploadResult.origName}")

        logMsg(context, "提交生成任务…")
        val eventId = submitGenerateTask(context, uploadResult.serverPath, taskId)
        logMsg(context, "任务已提交: event_id=$eventId")

        // 更新已有记录：pending → generating
        updateRecord(context, recordId) { it.copy(
            inputImageName = it.inputImageName ?: uploadResult.origName,
            inputImageUrl = uploadResult.serverUrl,
            eventId = eventId,
            taskId = taskId,
            status = "generating"
        )}

        return recordId
    }

    /**
     * 从失败处继续任务，而不是盲目重新上传/重新生成。
     *
     * 恢复策略：
     * - 已有本地 SOG：直接标记 completed；
     * - 已有 sogServerUrl：只继续下载，保留 .part 临时文件做断点续传；
     * - 已有 taskId/eventId：继续查询远端任务结果；
     * - 只有本地图片且还没提交成功：才重新上传并提交。
     *
     * @return 更新后的记录
     */
    fun retryTask(context: Context, recordId: String): SogGenerationRecord {
        val record = getHistory(context).firstOrNull { it.id == recordId }
            ?: return SogGenerationRecord(id = recordId, status = "failed", errorMessage = "记录不存在")

        val localPath = record.sogLocalPath
        if (!localPath.isNullOrBlank() && File(localPath).exists() && File(localPath).length() > 0L) {
            val updated = record.copy(status = "completed", errorMessage = null)
            updateRecord(context, recordId) { updated }
            return updated
        }

        // 任务已经生成出服务端文件，只需要从上次下载失败的位置继续下载。
        if (!record.sogServerUrl.isNullOrBlank()) {
            val updated = record.copy(status = "downloading", errorMessage = null)
            updateRecord(context, recordId) { updated }
            logMsg(context, "从下载阶段继续: record_id=$recordId")
            return updated
        }

        // 任务已经提交到服务端，只继续通过 task_id/event_id 查询，不重新上传。
        if (!record.taskId.isNullOrBlank() || !record.eventId.isNullOrBlank()) {
            val updated = record.copy(status = "generating", errorMessage = null)
            updateRecord(context, recordId) { updated }
            logMsg(context, "从生成查询阶段继续: record_id=$recordId, task_id=${record.taskId}, event_id=${record.eventId}")
            return updated
        }

        // 只有“还没提交成功”的 pending/failed 记录才从上传阶段重来。
        val localUriStr = record.inputImageLocalUri
            ?: return record.copy(status = "failed", errorMessage = "无本地图片，无法重试")

        updateRecord(context, recordId) { it.copy(
            status = "pending",
            errorMessage = null,
            sogServerUrl = null,
            sogLocalPath = null,
            eventId = null,
            taskId = null
        )}

        val localUri = savedLocalUriFromString(localUriStr)
        submitTaskForRecord(context, recordId, localUri)

        return getHistory(context).firstOrNull { it.id == recordId }
            ?: record.copy(status = "failed", errorMessage = "重试后记录丢失")
    }

    /**
     * 步骤2：轮询指定记录的任务状态。如果完成则自动下载 SOG 并更新记录。
     *
     * 关键点：
     * - record 有 taskId 时，优先走 /api/check_sog_result 轮询；
     * - 不再依赖 Gradio SSE event_id 恢复任务；
     * - App 关闭重开后，只要服务端任务还在/已完成，就能通过 taskId 找回结果。
     *
     * @return 更新后的记录
     */
    fun pollAndUpdateRecord(context: Context, recordId: String): SogGenerationRecord {
        val startRecord = getHistory(context).firstOrNull { it.id == recordId }
            ?: return SogGenerationRecord(id = recordId, status = "failed", errorMessage = "记录不存在")

        val acquired = synchronized(activeRecordPollLock) {
            activeRecordPollIds.add(recordId)
        }
        if (!acquired) {
            logMsg(context, "记录已有轮询任务，跳过重复启动: record_id=$recordId")
            return getHistory(context).firstOrNull { it.id == recordId } ?: startRecord
        }

        return try {
            try {
            val existingLocal = startRecord.sogLocalPath
            if (!existingLocal.isNullOrBlank() && File(existingLocal).exists() && File(existingLocal).length() > 0L) {
                val updated = startRecord.copy(status = "completed", errorMessage = null)
                updateRecord(context, recordId) { updated }
                closeLog()
                return updated
            }

            val sogUrl = when {
                // 已经拿到服务端文件地址，说明生成阶段完成。失败后重试只继续下载。
                !startRecord.sogServerUrl.isNullOrBlank() -> {
                    logMsg(context, "已有服务端 SOG 地址，直接继续下载: ${startRecord.sogServerUrl}")
                    normalizeServerUrl(context, startRecord.sogServerUrl) ?: startRecord.sogServerUrl
                }

                !startRecord.taskId.isNullOrBlank() -> {
                    updateRecord(context, recordId) { it.copy(status = "generating", errorMessage = null) }
                    logMsg(context, "使用 task_id 轮询任务结果: ${startRecord.taskId}")
                    pollTaskIdUntilResult(context, recordId) { _, _ -> }
                }

                !startRecord.eventId.isNullOrBlank() -> {
                    updateRecord(context, recordId) { it.copy(status = "generating", errorMessage = null) }
                    logMsg(context, "无 task_id，退回 SSE event_id 轮询: ${startRecord.eventId}")
                    pollForResult(context, startRecord.eventId, recordId) { _, _ -> }
                }

                else -> {
                    val updated = startRecord.copy(status = "failed", errorMessage = "无 task_id / event_id / sog_url，无法恢复任务")
                    updateRecord(context, recordId) { updated }
                    closeLog()
                    return updated
                }
            }

            logMsg(context, "任务完成: sogUrl=$sogUrl")

            // 下载 SOG。recordId 会让 .part 文件名稳定，失败后再次重试可继续续传。
            updateRecord(context, recordId) { it.copy(
                sogServerUrl = sogUrl,
                status = "downloading",
                errorMessage = null
            ) }
            logMsg(context, "下载 SOG…")
            val localUri = downloadSog(context, sogUrl, recordId)
            logMsg(context, "下载完成: ${localUri.path}")

            val latest = getHistory(context).firstOrNull { it.id == recordId } ?: startRecord
            val updated = latest.copy(
                sogServerUrl = sogUrl,
                sogLocalPath = localUri.path,
                status = "completed",
                errorMessage = null
            )
            updateRecord(context, recordId) { updated }
            logMsg(context, "=== 生成成功 ===")
            closeLog()
            updated
            } catch (e: Exception) {
            val fullError = buildString {
                append(e.message ?: "未知错误")
                e.cause?.message?.let { append(" | Cause: $it") }
            }
            logMsg(context, "任务失败: $fullError")
            val latest = getHistory(context).firstOrNull { it.id == recordId } ?: startRecord
            val updated = latest.copy(
                status = "failed",
                errorMessage = fullError.take(200)
            )
            updateRecord(context, recordId) { updated }
            closeLog()
            updated
            }
        } finally {
            synchronized(activeRecordPollLock) {
                activeRecordPollIds.remove(recordId)
            }
        }
    }

    /**
     * 步骤2b：仅查询任务状态（不下载），用于 UI 轮询显示进度。
     * @return Pair(状态字符串, 进度百分比或null)
     */
    fun peekTaskStatus(context: Context, recordId: String): Pair<String, Float?> {
        val record = getHistory(context).firstOrNull { it.id == recordId }
            ?: return "unknown" to null
        if (record.status != "generating" || record.eventId == null) {
            return record.status to null
        }
        // 用短超时轮询 SSE，只读当前事件不阻塞
        return try {
            val eventId = record.eventId
            val resultUrl = "${callEndpoint(context)}/$eventId"
            val reqBuilder = Request.Builder().url(resultUrl).get()
                .header("Accept", "text/event-stream")
            addAuthHeader(context, reqBuilder)

            val peekClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .build()

            val resp = peekClient.newCall(reqBuilder.build()).execute()
            if (!resp.isSuccessful) {
                return "generating" to null
            }

            val reader = BufferedReader(resp.body?.byteStream()?.reader() ?: return "generating" to null)
            var currentEvent = ""
            var currentData = StringBuilder()
            var lastStatus = "generating"
            var lastPercent: Float? = null

            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> {
                            currentEvent = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            if (data.isNotBlank() && data != "null") {
                                currentData.append(data)
                            }
                        }
                        line.isBlank() -> {
                            if (currentData.isNotEmpty()) {
                                val jsonStr = currentData.toString()
                                currentData = StringBuilder()
                                when (currentEvent) {
                                    "heartbeat" -> { /* keep waiting */ }
                                    "generating" -> {
                                        lastStatus = "generating"
                                        try {
                                            val json = JSON.parseObject(jsonStr)
                                            val msg = json?.getJSONArray("output")?.getJSONObject(0)?.getString("message")
                                            if (msg != null) lastStatus = msg
                                        } catch (_: Exception) {}
                                    }
                                    "complete" -> return "completed" to 1f
                                    "error" -> return "failed" to null
                                }
                            }
                            currentEvent = ""
                        }
                        else -> {
                            if (currentData.isNotEmpty()) currentData.append(line.trim())
                        }
                    }
                }
            } finally {
                runCatching { reader.close() }
            }
            lastStatus to lastPercent
        } catch (_: Exception) {
            "generating" to null
        }
    }


    /**
     * 服务端可能返回相对地址 /gradio_api/file=...，OkHttp 只能下载完整 http(s) URL。
     */
    private fun normalizeServerUrl(context: Context, rawUrl: String?): String? {
        val url = rawUrl?.trim() ?: return null
        if (url.isBlank()) return null
        val root = baseUrl(context)
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url.replace(
                Regex("^https?://[^/]+\\.ms\\.show/"),
                "$root/"
            )
            url.startsWith("/") -> "$root$url"
            else -> "$root/$url"
        }
    }

    /**
     * 通过纯 HTTP /api/check_sog_result 查询服务端任务结果。
     *
     * 注意：这里不是 Gradio Queue API，不会返回 event_id，也不需要再 GET SSE。
     * 请求体必须是 {"task_id": "..."}。
     *
     * @return SOG 下载 URL；任务未完成/网络暂时失败返回 null；服务端失败会抛异常。
     */
    fun checkSogResult(context: Context, recordId: String): String? {
        val record = getHistory(context).firstOrNull { it.id == recordId }
            ?: return null
        val taskId = record.taskId ?: return null

        val requestBody = JSONObject().apply {
            put("task_id", taskId)
        }

        val reqBuilder = Request.Builder()
            .url(checkEndpoint(context))
            .post(requestBody.toJSONString().toRequestBody(jsonType))
            .header("Content-Type", "application/json")
        addAuthHeader(context, reqBuilder)

        return withSerializedTaskQuery(context) {
            try {
            logMsg(context, "发起服务端结果查询: task_id=$taskId")
            quickClient.newCall(reqBuilder.build()).execute().use { resp ->
                val responseText = resp.body?.string() ?: ""
                logMsg(context, "查询结果响应: HTTP ${resp.code} ${responseText.take(500)}")

                if (resp.code == 429) {
                    val delaySec = scheduleTaskQueryRateLimit(resp.header("Retry-After"))
                    logMsg(context, "查询接口限流，${delaySec}秒后再查询")
                    return@use null
                }
                consecutiveTaskQueryRateLimits = 0

                if (!resp.isSuccessful) {
                    if (resp.code == 401 || resp.code == 403 || responseText.contains("login", ignoreCase = true)) {
                        error("查询任务需要鉴权，请在设置中填写 ModelScope SDK Token")
                    }
                    return@use null
                }

                val json = JSON.parseObject(responseText) ?: return@use null
                when (val status = json.getString("status") ?: "") {
                    "completed" -> {
                        val sogDownloadUrl = json.getString("sog_download_url")
                        val sogUrl = json.getString("sog_url")
                        val sogPath = json.getString("sog_path")
                        val fixedUrl = when {
                            !sogDownloadUrl.isNullOrBlank() -> normalizeServerUrl(context, sogDownloadUrl)
                            !sogUrl.isNullOrBlank() -> normalizeServerUrl(context, sogUrl)
                            !sogPath.isNullOrBlank() -> "${baseUrl(context)}/gradio_api/file=$sogPath"
                            else -> null
                        }

                        if (!fixedUrl.isNullOrBlank()) {
                            logMsg(context, "服务端结果已完成: sog_url=$fixedUrl")
                            updateRecord(context, recordId) { it.copy(
                                sogServerUrl = fixedUrl,
                                status = "completed",
                                errorMessage = null
                            )}
                            fixedUrl
                        } else {
                            logMsg(context, "服务端 completed 但没有 sog_download_url/sog_url/sog_path")
                            null
                        }
                    }

                    "failed" -> {
                        val error = json.getString("error") ?: "服务端生成失败"
                        logMsg(context, "服务端结果失败: $error")
                        updateRecord(context, recordId) { it.copy(
                            status = "failed",
                            errorMessage = error.take(200)
                        )}
                        error("服务端生成失败: $error")
                    }

                    "running", "generating", "pending", "not_found" -> {
                        logMsg(context, "服务端结果状态: $status")
                        null
                    }

                    else -> {
                        logMsg(context, "服务端未知状态: $status")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            // 业务失败继续抛出；网络/DNS 临时错误返回 null，让外层继续轮询。
            if (e.message?.startsWith("服务端生成失败:") == true ||
                e.message?.contains("鉴权") == true ||
                e.message?.contains("SDK Token") == true
            ) {
                throw e
            }
            logMsg(context, "查询结果异常: ${e.javaClass.simpleName}: ${e.message}")
            null
            }
        }
    }

    /**
     * 使用 task_id 持续轮询直到拿到 SOG URL。
     * 这是 App 关闭/重开后的主要恢复路径。
     */
    private fun <T> withSerializedTaskQuery(context: Context, action: () -> T): T {
        return synchronized(taskQueryLock) {
            val currentBaseUrl = baseUrl(context)
            if (taskQueryBaseUrl != currentBaseUrl) {
                taskQueryBaseUrl = currentBaseUrl
                nextTaskQueryAtMs = 0L
                consecutiveTaskQueryRateLimits = 0
            }
            val waitMs = nextTaskQueryAtMs - System.currentTimeMillis()
            if (waitMs > 0L) {
                logMsg(context, "全局查询等待 ${((waitMs + 999L) / 1000L)} 秒")
                Thread.sleep(waitMs)
            }
            try {
                action()
            } finally {
                nextTaskQueryAtMs = maxOf(
                    nextTaskQueryAtMs,
                    System.currentTimeMillis() + taskIdPollIntervalSec(context) * 1000L
                )
            }
        }
    }

    private fun scheduleTaskQueryRateLimit(retryAfterHeader: String?): Long {
        consecutiveTaskQueryRateLimits++
        val retryAfterSec = parseRetryAfterSeconds(retryAfterHeader)
        val exponentialSec = (TASK_QUERY_RATE_LIMIT_MIN_SEC *
            (1L shl (consecutiveTaskQueryRateLimits - 1).coerceIn(0, 3)))
            .coerceAtMost(TASK_QUERY_RATE_LIMIT_MAX_SEC)
        val delaySec = retryAfterSec
            ?.coerceIn(TASK_ID_POLL_INTERVAL_SEC, TASK_QUERY_RATE_LIMIT_MAX_SEC)
            ?: exponentialSec
        nextTaskQueryAtMs = maxOf(
            nextTaskQueryAtMs,
            System.currentTimeMillis() + delaySec * 1000L
        )
        return delaySec
    }

    private fun parseRetryAfterSeconds(value: String?): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        raw.toLongOrNull()?.let { return it.coerceAtLeast(0L) }
        return runCatching {
            val parser = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }
            ((parser.parse(raw)?.time ?: return null) - System.currentTimeMillis())
                .coerceAtLeast(0L) / 1000L
        }.getOrNull()
    }

    private fun pollTaskIdUntilResult(
        context: Context,
        recordId: String,
        onStatus: (status: String, percent: Float) -> Unit
    ): String {
        val startTime = System.currentTimeMillis()
        val maxMs = POLL_RETRY_WINDOW_MINUTES * 60_000L

        while (true) {
            if (System.currentTimeMillis() - startTime > maxMs) {
                error("通过 task_id 查询超时（${POLL_RETRY_WINDOW_MINUTES}分钟），请稍后重试")
            }

            val latest = getHistory(context).firstOrNull { it.id == recordId }
                ?: error("记录不存在")
            if (latest.status == "failed") {
                error(latest.errorMessage ?: "服务端生成失败")
            }

            val url = checkSogResult(context, recordId)
            if (!url.isNullOrBlank()) {
                return url
            }

            onStatus("后台生成中，正在通过 task_id 查询结果…", -1f)
            Thread.sleep(taskIdPollIntervalSec(context) * 1000L)
        }
    }

    /**
     * 下载已完成的记录的 SOG 文件到本地，并更新记录。
     */
    fun downloadRecordSog(context: Context, recordId: String): SogGenerationRecord {
        val record = getHistory(context).firstOrNull { it.id == recordId }
            ?: return SogGenerationRecord(id = recordId, status = "failed", errorMessage = "记录不存在")

        val candidateUrls = mutableListOf<String>().apply {
            if (!record.taskId.isNullOrBlank()) {
                add("${downloadEndpoint(context)}?task_id=${Uri.encode(record.taskId)}")
            }
            if (!record.sogServerUrl.isNullOrBlank()) {
                add(record.sogServerUrl)
            }
        }.distinct()

        if (candidateUrls.isEmpty()) {
            val updated = record.copy(status = "failed", errorMessage = "无下载 URL")
            updateRecord(context, recordId) { updated }
            return updated
        }

        var lastError: Throwable? = null
        for (candidate in candidateUrls) {
            try {
                updateRecord(context, recordId) { it.copy(status = "downloading", errorMessage = null) }
                val localUri = downloadSog(context, candidate, recordId)
                val latest = getHistory(context).firstOrNull { it.id == recordId } ?: record
                val updated = latest.copy(
                    sogServerUrl = normalizeServerUrl(context, candidate) ?: candidate,
                    sogLocalPath = localUri.path,
                    status = "completed",
                    errorMessage = null
                )
                updateRecord(context, recordId) { updated }
                return updated
            } catch (t: Throwable) {
                lastError = t
                logMsg(context, "下载候选地址失败，尝试下一个: ${t.message ?: t.javaClass.simpleName}")
            }
        }

        val updated = (getHistory(context).firstOrNull { it.id == recordId } ?: record).copy(
            status = "failed",
            errorMessage = "下载 SOG 失败: ${lastError?.message ?: "未知错误"}".take(200)
        )
        updateRecord(context, recordId) { updated }
        error(updated.errorMessage ?: "下载 SOG 失败")
    }

    // ─── Gradio 文件上传 ──────────────────────────────────────────

    private data class UploadResult(
        val serverPath: String,
        val serverUrl: String,
        val origName: String
    )

    /**
     * 上传图片到 Gradio 服务端的 /gradio_api/upload 端点。
     * 返回服务端文件路径和可访问的 URL。
     */
    private fun uploadToGradio(context: Context, localUri: Uri): UploadResult {
        // 推断文件名和 MIME 类型
        var fileName = "image.jpg"
        var mimeType = "image/jpeg"
        context.contentResolver.query(localUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) cursor.getString(nameIdx)?.let { fn ->
                    fileName = fn
                    mimeType = when (fileName.substringAfterLast(".").lowercase()) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "heic", "heif" -> "image/heic"
                        else -> "image/jpeg"
                    }
                }
            }
        }

        val tempFile = File(context.cacheDir, "upload_${UUID.randomUUID()}_$fileName")
        try {
            context.contentResolver.openInputStream(localUri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Cannot read local image: $localUri")

            val reqBuilder = Request.Builder().url(uploadEndpoint(context))
            addAuthHeader(context, reqBuilder)

            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("files", fileName,
                    tempFile.readBytes().toRequestBody(mimeType.toMediaType()))
                .build()

            val resp = client.newCall(reqBuilder.post(body).build()).execute()

            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(200) ?: ""
                logMsg(context, "上传失败: HTTP ${resp.code} $errBody")
                if (resp.code == 401 || resp.code == 403 || errBody.contains("login", ignoreCase = true)) {
                    error("上传需要鉴权，请在设置中填写 ModelScope SDK Token")
                }
                error("Gradio upload failed: HTTP ${resp.code} $errBody")
            }

            val responseText = resp.body?.string() ?: error("Empty upload response")
            logMsg(context, "上传响应: $responseText")

            // 检查是否返回了 ModelScope 登录 JSON 而非 Gradio 响应
            if (responseText.contains("\"Please login first\"") || responseText.contains("\"Code\"")) {
                error("上传需要鉴权，请在设置中填写 ModelScope SDK Token")
            }

            // Gradio upload 返回格式: ["/tmp/gradio/xxx/image.jpg"]
            val paths = JSON.parseArray(responseText, String::class.java)
            if (paths.isNullOrEmpty()) error("Upload returned no file paths")

            val path = paths[0]
            val serverUrl = "${baseUrl(context)}/gradio_api/file=$path"

            return UploadResult(
                serverPath = path,
                serverUrl = serverUrl,
                origName = fileName
            )
        } finally {
            tempFile.delete()
        }
    }

    // ─── 冷启动唤醒 ──────────────────────────────────────────────

    /**
     * 唤醒 Gradio 服务（ModelScope 空闲后可能休眠）。
     * 重试多次，每次等待更长时间。如果服务端返回鉴权错误，提前报错。
     */
    private fun warmUpServer(context: Context) {
        var lastError: String? = null

        for (attempt in 1..WARMUP_RETRY_COUNT) {
            try {
                val warmupClient = quickClient.newBuilder()
                    .connectTimeout(WARMUP_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .readTimeout(WARMUP_TIMEOUT_SEC, TimeUnit.SECONDS)
                    .build()

                val reqBuilder = Request.Builder().url(baseUrl(context)).get()
                addAuthHeader(context, reqBuilder)

                val resp = warmupClient.newCall(reqBuilder.build()).execute()

                // 检查是否被 ModelScope 鉴权拦截
                if (resp.code == 401 || resp.code == 403) {
                    val body = resp.body?.string()?.take(300) ?: ""
                    resp.close()
                    if (body.contains("login", ignoreCase = true) || body.contains("Please login")) {
                        error("服务需要鉴权，请在设置中填写 ModelScope SDK Token")
                    }
                }

                val code = resp.code
                resp.close()

                if (code in 200..399) {
                    Log.d(TAG, "Warmup succeeded on attempt $attempt (HTTP $code)")
                    return
                }

                lastError = "HTTP $code"

                // 如果是服务端错误 (5xx)，等待后重试
                if (code in 500..599) {
                    Log.d(TAG, "Warmup attempt $attempt got $code, retrying...")
                    Thread.sleep(10_000L * attempt)
                    continue
                }

                // 其他非成功状态码，可能是鉴权问题
                if (code == 401 || code == 403) {
                    error("服务需要鉴权，请在设置中填写 ModelScope SDK Token (HTTP $code)")
                }
            } catch (e: Exception) {
                if (e.message?.contains("鉴权") == true || e.message?.contains("SDK Token") == true) {
                    throw e
                }
                lastError = e.message
                Log.w(TAG, "Warmup attempt $attempt failed: ${e.message}")
                Thread.sleep(10_000L * attempt)
            }
        }

        // 唤醒失败不阻止后续尝试——服务可能已在运行
        Log.w(TAG, "Warmup failed after $WARMUP_RETRY_COUNT attempts ($lastError), proceeding anyway")
    }

    // ─── Gradio Queue API ─────────────────────────────────────────

    /**
     * 提交生成任务到 Gradio Queue API。
     * POST /gradio_api/call/generate_sog
     * Body: {"data": [{"path": "/tmp/gradio/xxx/image.jpg", "meta": {"_type": "gradio.FileData"}}, "auto", 0]}
     * 返回: {"event_id": "xxx"}
     */
    private fun submitGenerateTask(context: Context, serverFilePath: String, taskId: String = ""): String {
        val fileData = JSONObject().apply {
            put("path", serverFilePath)
            put("meta", JSONObject().apply {
                put("_type", "gradio.FileData")
            })
        }

        val requestBody = JSONObject().apply {
            put("data", com.alibaba.fastjson.JSONArray().apply {
                add(fileData)   // image: FileData
                add(taskId)     // task_id: String
                add("auto")     // device
                add(0)          // focal_length_35mm
            })
        }

        val reqBuilder = Request.Builder()
            .url(callEndpoint(context))
            .post(requestBody.toJSONString().toRequestBody(jsonType))
            .header("Content-Type", "application/json")
        addAuthHeader(context, reqBuilder)

        val resp = client.newCall(reqBuilder.build()).execute()

        if (!resp.isSuccessful) {
            val errBody = resp.body?.string()?.take(300) ?: ""
            logMsg(context, "提交任务失败: HTTP ${resp.code} $errBody")
            if (resp.code == 401 || resp.code == 403 || errBody.contains("login", ignoreCase = true)) {
                error("提交任务需要鉴权，请在设置中填写 ModelScope SDK Token")
            }
            error("提交生成任务失败: HTTP ${resp.code} $errBody")
        }

        val responseText = resp.body?.string() ?: error("Empty submit response")
        logMsg(context, "提交响应: $responseText")
        val json = JSON.parseObject(responseText)
        val eventId = json?.getString("event_id")
            ?: error("提交任务未返回 event_id: $responseText")

        Log.d(TAG, "Task submitted, event_id: $eventId")
        return eventId
    }

    /**
     * 轮询 Gradio Queue API 等待生成结果。
     * GET /gradio_api/call/generate_sog/{event_id}
     *
     * SSE 事件类型：
     * - heartbeat: 心跳，忽略
     * - generating: 生成中，可能包含进度
     * - complete: 完成，包含结果数据
     * - error: 错误
     *
     * 返回 SOG 文件的下载 URL。
     */
    /**
     * 长连接 SSE 轮询 + 断线重连。
     * 保持一个长连接读 SSE 事件，只在真正断线时才重连。
     * 每次重连创建新的 OkHttpClient，避免连接池/缓存导致 DNS 失败。
     */
    private fun pollForResult(
        context: Context,
        eventId: String,
        recordId: String? = null,
        onStatus: (status: String, percent: Float) -> Unit
    ): String {
        val MAX_TOTAL_MS = POLL_RETRY_WINDOW_MINUTES * 60_000L
        val startTime = System.currentTimeMillis()
        var reconnectAttempt = 0

        while (true) {
            // 检查总窗口
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > MAX_TOTAL_MS) {
                error("轮询超时（${POLL_RETRY_WINDOW_MINUTES}分钟），请检查网络后重试")
            }

            // 每次重连创建新客户端，清空连接池和 DNS 缓存
            val pollClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)   // 长连接，靠 SSE heartbeat 保活
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()

            val resultUrl = "${callEndpoint(context)}/$eventId"
            val reqBuilder = Request.Builder().url(resultUrl).get()
                .header("Accept", "text/event-stream")
            addAuthHeader(context, reqBuilder)

            val resp = try {
                pollClient.newCall(reqBuilder.build()).execute()
            } catch (e: java.net.UnknownHostException) {
                reconnectAttempt++
                // DNS 失败后等久一点再试，给 Android 网络栈恢复时间
                val delaySec = (reconnectAttempt * 15L).coerceAtMost(60L)
                logMsg(context, "DNS 解析失败 (reconnect #$reconnectAttempt)，${delaySec}秒后重试: ${e.message}")
                onStatus("网络恢复中…", -1f)
                Thread.sleep(delaySec * 1000L)
                continue
            } catch (e: java.io.IOException) {
                reconnectAttempt++
                val delaySec = (reconnectAttempt * 10L).coerceAtMost(60L)
                logMsg(context, "连接失败 (reconnect #$reconnectAttempt)，${delaySec}秒后重试: ${e.message}")
                onStatus("网络恢复中…", -1f)
                Thread.sleep(delaySec * 1000L)
                continue
            }

            // HTTP 错误处理
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string()?.take(300) ?: ""
                resp.close()
                logMsg(context, "SSE HTTP ${resp.code}: $errBody")
                when {
                    resp.code == 401 || resp.code == 403 -> {
                        error("查询任务需要鉴权，请在设置中填写 ModelScope SDK Token")
                    }
                    resp.code == 404 -> {
                        // event_id 过期，先尝试通过 taskId 查询结果
                        if (recordId != null) {
                            logMsg(context, "SSE 404，改用 taskId 持续轮询结果…")
                            return pollTaskIdUntilResult(context, recordId, onStatus)
                        }
                        error("任务结果已过期（服务端已清理），请重新生成")
                    }
                    else -> {
                        reconnectAttempt++
                        val delaySec = (reconnectAttempt * 10L).coerceAtMost(60L)
                        logMsg(context, "HTTP ${resp.code}，${delaySec}秒后重试")
                        Thread.sleep(delaySec * 1000L)
                        continue
                    }
                }
            }

            // 重连成功，重置计数
            reconnectAttempt = 0
            logMsg(context, "SSE 连接成功，等待结果…")

            // 读取 SSE 事件
            val reader = BufferedReader(resp.body?.byteStream()?.reader()
                ?: run { resp.close(); continue })
            var currentEvent = ""
            var currentData = StringBuilder()
            var sogUrl: String? = null

            try {
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (e: java.io.IOException) {
                        // 连接中断（Software caused connection abort 等），需要重连
                        logMsg(context, "SSE 读取中断: ${e.javaClass.simpleName}: ${e.message}")
                        break
                    }

                    if (line == null) {
                        // 服务端正常关闭连接 → 如果没拿到结果，也重连试试
                        logMsg(context, "SSE 连接被服务端关闭")
                        break
                    }

                    when {
                        line.startsWith("event:") -> {
                            currentEvent = line.removePrefix("event:").trim()
                        }
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            if (data.isNotBlank() && data != "null") {
                                currentData.append(data)
                            }
                        }
                        line.isBlank() -> {
                            // 空行 = 事件结束，处理累积数据
                            if (currentData.isNotEmpty()) {
                                val jsonStr = currentData.toString()
                                currentData = StringBuilder()

                                when (currentEvent) {
                                    "heartbeat" -> { /* 心跳，连接保活 */ }

                                    "generating" -> {
                                        logMsg(context, "SSE generating: $jsonStr")
                                        try {
                                            val json = JSON.parseObject(jsonStr)
                                            val outputData = json?.getJSONArray("output")?.getJSONObject(0)
                                            val msg = outputData?.getString("message") ?: ""
                                            if (msg.isNotBlank()) {
                                                onStatus(msg, -1f)
                                            }
                                        } catch (_: Exception) {}
                                        onStatus("生成中…", -1f)
                                    }

                                    "complete" -> {
                                        logMsg(context, "SSE complete raw: $jsonStr")
                                        try {
                                            val fileData: JSONObject? = if (jsonStr.trimStart().startsWith("[")) {
                                                val arr = JSON.parseArray(jsonStr)
                                                arr?.getJSONObject(0)
                                            } else {
                                                val obj = JSON.parseObject(jsonStr)
                                                obj?.getJSONArray("data")?.getJSONObject(0)
                                            }

                                            if (fileData != null) {
                                                val path = fileData.getString("path") ?: ""
                                                val url = fileData.getString("url") ?: ""

                                                if (url.isNotBlank()) {
                                                    val fixedUrl = url.replace(
                                                        Regex("^https?://[^/]+\\.ms\\.show/"),
                                                        "${baseUrl(context)}/"
                                                    )
                                                    sogUrl = fixedUrl
                                                } else if (path.isNotBlank()) {
                                                    sogUrl = "${baseUrl(context)}/gradio_api/file=$path"
                                                }
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Failed to parse complete event: $jsonStr", e)
                                        }

                                        if (sogUrl != null) {
                                            resp.close()
                                            return sogUrl
                                        }
                                    }

                                    "error" -> {
                                        logMsg(context, "SSE error raw: $jsonStr")
                                        // 如果是 404 错误（event_id 过期），尝试通过 taskId 查询结果
                                        if (jsonStr.contains("404") || jsonStr.contains("Not Found")) {
                                            if (recordId != null) {
                                                logMsg(context, "SSE error 404，改用 taskId 持续轮询结果…")
                                                resp.close()
                                                return pollTaskIdUntilResult(context, recordId, onStatus)
                                            }
                                            error("任务结果已过期（服务端已清理），请重新生成")
                                        }
                                        try {
                                            val json = JSON.parseObject(jsonStr)
                                            val parts = mutableListOf<String>()
                                            json?.getString("error")?.let { parts.add(it) }
                                            json?.getString("message")?.let { parts.add(it) }
                                            val detail = if (parts.isNotEmpty()) parts.joinToString(" | ") else "生成失败"
                                            logMsg(context, "SSE error detail: $detail")
                                            error("服务端错误: $detail")
                                        } catch (e: Exception) {
                                            if (e.message?.startsWith("服务端错误:") == true) throw e
                                            error("生成失败: $jsonStr")
                                        }
                                    }
                                }
                            }
                            currentEvent = ""
                        }
                        else -> {
                            if (currentData.isNotEmpty()) {
                                currentData.append(line.trim())
                            }
                        }
                    }
                }
            } finally {
                runCatching { reader.close() }
                runCatching { resp.close() }
                // 清空连接池，避免旧连接影响下次重连
                pollClient.connectionPool.evictAll()
            }

            // 连接中断但没拿到结果 → 等待后重连
            logMsg(context, "SSE 连接中断，15秒后重连…")
            onStatus("重连中…", -1f)
            Thread.sleep(15_000L)
        }
    }

    // ─── 鉴权 ────────────────────────────────────────────────────

    /**
     * 为请求添加 ModelScope SDK Token 鉴权头。
     * api-inference 端点支持两种方式：
     * - Authorization: Bearer <token>  (标准 OAuth)
     * - X-Studio-Token: <token>       (ModelScope 专用)
     * 两者同时发送以最大化兼容性。
     */
    private fun addAuthHeader(context: Context, builder: Request.Builder) {
        val token = getModelScopeToken(context)
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
            builder.header("X-Studio-Token", token)
        }
    }

    // ─── SOG 下载 ────────────────────────────────────────────────

    private fun downloadSog(context: Context, url: String, recordId: String? = null): Uri {
        val downloadUrl = normalizeServerUrl(context, url) ?: error("下载地址为空")
        logMsg(context, "开始下载 SOG: $downloadUrl")

        val dir = ensureGeneratedSogDir(context)
        val stableName = recordId?.let { "generated_${safeFileName(it)}.sog" } ?: "generated_${UUID.randomUUID()}.sog"
        val file = File(dir, stableName)
        val partFile = File(file.absolutePath + ".part")

        if (file.exists() && file.length() > 0L) {
            logMsg(context, "本地 SOG 已存在，跳过下载: ${file.absolutePath} (${file.length()} bytes)")
            return Uri.fromFile(file)
        }

        var lastError: Throwable? = null
        val maxAttempts = 5

        for (attempt in 1..maxAttempts) {
            try {
                val existedBytes = if (partFile.exists()) partFile.length() else 0L
                val reqBuilder = Request.Builder().url(downloadUrl).get()
                addAuthHeader(context, reqBuilder)

                if (existedBytes > 0L) {
                    reqBuilder.header("Range", "bytes=$existedBytes-")
                    logMsg(context, "继续下载 SOG: attempt=$attempt, 已有 $existedBytes bytes")
                } else {
                    logMsg(context, "下载 SOG: attempt=$attempt")
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.code == 416) {
                        // Range 超出通常表示临时文件已经完整，直接尝试收尾。
                        if (partFile.length() > 0L) {
                            if (file.exists()) file.delete()
                            if (!partFile.renameTo(file)) {
                                partFile.copyTo(file, overwrite = true)
                                partFile.delete()
                            }
                            logMsg(context, "SOG 已保存: ${file.absolutePath} (${file.length()} bytes)")
                            return Uri.fromFile(file)
                        }
                    }

                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string()?.take(300) ?: ""
                        logMsg(context, "下载失败: HTTP ${resp.code} $errBody")
                        if (resp.code == 401 || resp.code == 403) {
                            error("下载 SOG 需要鉴权，请在设置中填写 ModelScope SDK Token")
                        }
                        error("Download failed: HTTP ${resp.code} $errBody")
                    }

                    // 如果请求了 Range，但服务端没有返回 206，而是 200，说明不支持续传；删除临时文件从头开始。
                    val append = existedBytes > 0L && resp.code == 206
                    if (existedBytes > 0L && resp.code == 200) {
                        logMsg(context, "服务端未按 Range 续传，重新从头下载")
                        partFile.delete()
                    }

                    val body = resp.body ?: error("下载响应为空")
                    val expectedLen = body.contentLength()
                    logMsg(context, "下载响应: HTTP ${resp.code}, contentLength=$expectedLen, append=$append")

                    RandomAccessFile(partFile, "rw").use { raf ->
                        if (append) raf.seek(partFile.length()) else raf.setLength(0L)
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            var total = partFile.length()
                            var lastLogAt = System.currentTimeMillis()
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                raf.write(buffer, 0, read)
                                total += read.toLong()

                                val now = System.currentTimeMillis()
                                if (now - lastLogAt > 5000L) {
                                    logMsg(context, "下载中: $total bytes")
                                    lastLogAt = now
                                }
                            }
                        }
                    }

                    if (partFile.length() <= 0L) error("下载到空文件")
                    if (file.exists()) file.delete()
                    if (!partFile.renameTo(file)) {
                        partFile.copyTo(file, overwrite = true)
                        partFile.delete()
                    }
                    logMsg(context, "SOG 已保存: ${file.absolutePath} (${file.length()} bytes)")
                    return Uri.fromFile(file)
                }
            } catch (t: Throwable) {
                lastError = t
                val msg = t.message ?: t.javaClass.simpleName
                logMsg(context, "下载异常 attempt=$attempt/$maxAttempts: ${t.javaClass.simpleName}: $msg")

                // 鉴权和明确 HTTP 错误不要盲目重试。
                if (msg.contains("鉴权") || msg.contains("SDK Token") || msg.startsWith("Download failed:")) {
                    throw t
                }

                if (attempt < maxAttempts) {
                    Thread.sleep((attempt * 3000L).coerceAtMost(15000L))
                }
            }
        }

        error("下载 SOG 失败，已重试 ${maxAttempts} 次: ${lastError?.message ?: "未知错误"}")
    }

    private fun ensureGeneratedSogDir(context: Context): File {
        val dir = File(context.filesDir, GENERATED_SOG_DIR_NAME)
        if (dir.isDirectory) return dir

        var legacyFile: File? = null
        if (dir.exists()) {
            val backup = File(
                context.filesDir,
                "${GENERATED_SOG_DIR_NAME}_legacy_${System.currentTimeMillis()}.sog"
            )
            if (!dir.renameTo(backup)) {
                error("无法迁移旧的 SOG 下载文件: ${dir.absolutePath}")
            }
            legacyFile = backup
            Log.w(TAG, "Migrating generated_sog file to directory: ${backup.absolutePath}")
        }

        if (!dir.mkdirs() && !dir.isDirectory) {
            legacyFile?.renameTo(dir)
            error("无法创建 SOG 下载目录: ${dir.absolutePath}")
        }

        legacyFile?.let { source ->
            val target = File(dir, source.name)
            runCatching {
                if (!source.renameTo(target)) {
                    source.copyTo(target, overwrite = false)
                    if (!source.delete()) {
                        Log.w(TAG, "Legacy SOG source was copied but not deleted: ${source.absolutePath}")
                    }
                }
                Log.i(TAG, "Legacy SOG preserved at ${target.absolutePath}")
            }.onFailure {
                Log.w(TAG, "Failed to move legacy SOG into generated_sog directory", it)
            }
        }
        return dir
    }

    private fun safeFileName(raw: String): String {
        return raw.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { UUID.randomUUID().toString() }
    }

    private fun savedLocalUriFromString(value: String): Uri {
        val parsed = Uri.parse(value)
        return if (parsed.scheme.isNullOrBlank() && File(value).exists()) {
            Uri.fromFile(File(value))
        } else {
            parsed
        }
    }

    /**
     * 重置缓存状态（如切换服务器或鉴权失败后调用）。
     */
    fun resetMcpState() {
        // 保留此方法名以兼容 UI 调用，实际无 MCP 状态需重置
    }

    // ─── 日志 ──────────────────────────────────────────────────────

    /** 当前生成任务的日志写入器，每次生成开始时创建，结束时关闭 */
    private var logWriter: PrintWriter? = null

    /** 获取日志目录 */
    fun getLogDir(context: Context): File {
        return File(context.cacheDir, LOG_DIR_NAME).apply { mkdirs() }
    }

    /** 获取所有日志文件，按修改时间倒序 */
    fun getLogFiles(context: Context): List<File> {
        val dir = getLogDir(context)
        return dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** 删除所有日志文件 */
    fun clearLogs(context: Context) {
        getLogDir(context).listFiles()?.forEach { it.delete() }
    }

    private fun logMsg(context: Context, msg: String) {
        val timestamp = logDateFormat.format(Date())
        val line = "[$timestamp] $msg"
        Log.d(TAG, line)

        // 写入文件
        try {
            if (logWriter == null) {
                val dir = getLogDir(context)
                val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val logFile = File(dir, "sog_${dateStr}.log")
                logWriter = PrintWriter(FileWriter(logFile, true), true)
            }
            logWriter?.println(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log file: ${e.message}")
        }
    }

    private fun closeLog() {
        try {
            logWriter?.flush()
            logWriter?.close()
        } catch (_: Exception) {}
        logWriter = null
    }

    private const val GENERATED_SOG_DIR_NAME = "generated_sog"
}
