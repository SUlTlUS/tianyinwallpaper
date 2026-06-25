package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 缩略图加载工具类
 * 提供图片和视频缩略图的加载、缓存功能
 * 
 * 增强：
 * - 智能预加载机制
 * - 优先级队列
 * - 取消任务支持
 * - 线程池管理
 */
object ThumbnailUtils {

    private const val TAG = "ThumbnailUtils"
    private const val MEMORY_DIVISOR = 8L
    private const val VIDEO_THUMBNAIL_WIDTH = 540
    private const val VIDEO_THUMBNAIL_HEIGHT = 960
    
    // 预加载线程池
    private val preloadExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    
    // 正在加载的任务
    private val pendingTasks = ConcurrentHashMap<String, Future<*>>()
    
    // 预加载状态
    private val isPreloading = AtomicBoolean(false)

    // 内存缓存
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / MEMORY_DIVISOR / 1024L).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    /**
     * 缩略图请求参数
     */
    data class Request(
        val uuid: String,
        val type: Int,          // 0 = 静态图片, 1 = 视频
        val imgUri: String?,    // 图片 URI
        val videoUri: String?,  // 视频 URI
        val imgPath: String?,   // 本地图片路径（旧版兼容）
        val priority: Int = PRIORITY_NORMAL  // 优先级
    ) {
        val cacheKey: String get() = "$uuid|$type|$imgUri|$videoUri|$imgPath"
        
        companion object {
            const val PRIORITY_LOW = 0
            const val PRIORITY_NORMAL = 1
            const val PRIORITY_HIGH = 2
        }
    }

    /**
     * 预加载结果
     */
    data class PreloadResult(
        val total: Int,
        val loaded: Int,
        val failed: Int,
        val cached: Int
    )

    fun requestForWallpaper(
        model: TianYinWallpaperModel,
        priority: Int = Request.PRIORITY_NORMAL
    ): Request {
        val fallbackId = model.imgUri ?: model.videoUri ?: model.imgPath ?: model.videoPath ?: "unknown"
        return Request(
            uuid = model.uuid ?: fallbackId,
            type = model.type,
            imgUri = model.imgUri,
            videoUri = model.videoUri,
            imgPath = model.imgPath,
            priority = priority
        )
    }

    fun requestForRasterGroup(
        group: RasterGroupModel,
        priority: Int = Request.PRIORITY_NORMAL
    ): Request? {
        return if (group.type == RasterGroupModel.TYPE_STATIC) {
            val firstImageUri = group.imageUris.firstOrNull() ?: return null
            Request(
                uuid = group.id,
                type = RasterGroupModel.TYPE_STATIC,
                imgUri = firstImageUri,
                videoUri = null,
                imgPath = null,
                priority = priority
            )
        } else {
            val dynamicVideoUri = group.videoUri ?: return null
            Request(
                uuid = group.id,
                type = RasterGroupModel.TYPE_DYNAMIC,
                imgUri = null,
                videoUri = dynamicVideoUri,
                imgPath = null,
                priority = priority
            )
        }
    }

    /**
     * 从内存缓存获取缩略图
     */
    fun getFromCache(request: Request): Bitmap? = cache.get(request.cacheKey)

    /**
     * 从内存缓存获取缩略图（通过 cacheKey）
     */
    fun getFromCache(cacheKey: String): Bitmap? = cache.get(cacheKey)

    fun getFromCacheOrDisk(context: Context, request: Request): Bitmap? {
        getFromCache(request)?.let { return it }
        return loadPersistedThumbnail(context, request)?.also { bitmap ->
            cache.put(request.cacheKey, bitmap)
        }
    }

    /**
     * 加载缩略图（带缓存）
     */
    fun loadThumbnail(context: Context, request: Request): Bitmap? {
        // 先查缓存
        getFromCache(request)?.let { return it }
        loadPersistedThumbnail(context, request)?.let { bitmap ->
            cache.put(request.cacheKey, bitmap)
            return bitmap
        }

        // 加载缩略图
        val bitmap = loadThumbnailInternal(context, request) ?: return null
        persistThumbnail(context, request, bitmap)

        // 存入缓存
        cache.put(request.cacheKey, bitmap)
        return bitmap
    }

    /**
     * 异步加载缩略图
     */
    fun loadThumbnailAsync(
        context: Context,
        request: Request,
        onResult: (Bitmap?) -> Unit
    ) {
        // 先检查缓存
        getFromCache(request)?.let {
            onResult(it)
            return
        }
        
        // 取消已有的相同任务
        cancelTask(request.cacheKey)
        
        // 提交新任务
        val future = preloadExecutor.submit {
            val bitmap = loadThumbnail(context, request)
            pendingTasks.remove(request.cacheKey)
            android.os.Handler(context.mainLooper).post { onResult(bitmap) }
        }
        pendingTasks[request.cacheKey] = future
    }

    /**
     * 预加载缩略图到缓存（基础版本）
     */
    fun preload(context: Context, requests: List<Request>) {
        Thread {
            requests.forEach { request ->
                if (getFromCache(request) == null) {
                    loadThumbnail(context, request)
                }
            }
        }.start()
    }
    
    /**
     * 智能预加载缩略图
     * 支持优先级、进度回调和取消
     */
    fun preloadSmart(
        context: Context,
        requests: List<Request>,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null,
        onComplete: ((PreloadResult) -> Unit)? = null
    ) {
        if (isPreloading.getAndSet(true)) {
            Log.w(TAG, "Preload already in progress")
            return
        }
        
        preloadExecutor.submit {
            var loaded = 0
            var failed = 0
            var cached = 0
            
            // 按优先级排序
            val sortedRequests = requests.sortedByDescending { it.priority }
            
            for (request in sortedRequests) {
                if (!isPreloading.get()) {
                    // 被取消
                    break
                }
                
                if (getFromCache(request) != null) {
                    cached++
                    loaded++
                    onProgress?.invoke(loaded, requests.size)
                    continue
                }
                
                try {
                    loadThumbnail(context, request)
                    loaded++
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to preload: ${request.cacheKey}", e)
                    failed++
                }
                
                onProgress?.invoke(loaded, requests.size)
            }
            
            isPreloading.set(false)
            onComplete?.invoke(PreloadResult(
                total = requests.size,
                loaded = loaded,
                failed = failed,
                cached = cached
            ))
        }
    }
    
    /**
     * 预加载可见区域附近的缩略图
     * 用于优化滚动流畅度
     */
    fun preloadVisibleRange(
        context: Context,
        requests: List<Request>,
        visibleStart: Int,
        visibleEnd: Int,
        preloadOffset: Int = 5
    ) {
        val startIndex = maxOf(0, visibleStart - preloadOffset)
        val endIndex = minOf(requests.size - 1, visibleEnd + preloadOffset)
        
        val rangeRequests = requests.subList(startIndex, endIndex + 1)
            .filter { getFromCache(it) == null }
        
        if (rangeRequests.isEmpty()) return
        
        // 高优先级预加载
        preloadExecutor.submit {
            rangeRequests.forEach { request ->
                if (getFromCache(request) == null) {
                    try {
                        loadThumbnail(context, request)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to preload visible range: ${request.cacheKey}")
                    }
                }
            }
        }
    }
    
    /**
     * 取消预加载任务
     */
    fun cancelPreload() {
        isPreloading.set(false)
        pendingTasks.values.forEach { it.cancel(true) }
        pendingTasks.clear()
    }
    
    /**
     * 取消特定任务
     */
    fun cancelTask(cacheKey: String) {
        pendingTasks[cacheKey]?.cancel(true)
        pendingTasks.remove(cacheKey)
    }

    /**
     * 清除缓存
     */
    fun clearCache() {
        cache.evictAll()
    }

    /**
     * 从缓存移除指定项（内存 + 任务）
     */
    fun removeFromCache(request: Request) {
        cancelTask(request.cacheKey)
        cache.remove(request.cacheKey)
    }
    
    /**
     * 从缓存移除指定项（通过 cacheKey，内存 + 任务）
     */
    fun removeFromCache(cacheKey: String) {
        cancelTask(cacheKey)
        cache.remove(cacheKey)
    }

    /**
     * 删除某个壁纸条目的全部缩略图缓存（内存 + 视频磁盘缓存）
     */
    fun removeWallpaperCache(context: Context, request: Request) {
        removeFromCache(request)
        removeVideoThumbnailFile(context, request.cacheKey)
        if (request.uuid != request.cacheKey) {
            removeVideoThumbnailFile(context, request.uuid)
        }
    }

    /**
     * 按 uuid 删除视频缩略图磁盘缓存
     */
    fun removeVideoThumbnailFile(context: Context, cacheKey: String) {
        if (cacheKey.isBlank()) return
        val thumbnailDir = getVideoThumbnailDir(context) ?: return
        val candidates = listOf(
            File(thumbnailDir, "${videoThumbnailFileStem(cacheKey)}.jpg"),
            File(thumbnailDir, "$cacheKey.jpg")
        ).distinctBy { it.absolutePath }
        candidates.forEach { thumbnailFile ->
            if (thumbnailFile.exists() && !thumbnailFile.delete()) {
                Log.w(TAG, "Failed to delete video thumbnail: ${thumbnailFile.absolutePath}")
            }
        }
    }

    /**
     * 获取缓存统计
     */
    fun getCacheStats(): CacheStats {
        return CacheStats(
            hitCount = cache.hitCount(),
            missCount = cache.missCount(),
            putCount = cache.putCount(),
            evictCount = cache.evictionCount(),
            size = cache.size()
        )
    }
    
    /**
     * 缓存统计信息
     */
    data class CacheStats(
        val hitCount: Int,
        val missCount: Int,
        val putCount: Int,
        val evictCount: Int,
        val size: Int
    )

    // ── 内部方法 ──

    private fun loadThumbnailInternal(context: Context, request: Request): Bitmap? {
        return runCatching {
            when {
                // 图片 URI
                request.type == 0 && !request.imgUri.isNullOrEmpty() -> {
                    decodeSampledBitmap(context, Uri.parse(request.imgUri))
                }
                // 视频 URI
                request.type == 1 && !request.videoUri.isNullOrEmpty() -> {
                    loadVideoThumbnail(context, request)
                }
                // 本地图片路径（旧版兼容）
                !request.imgPath.isNullOrEmpty() -> {
                    decodeSampledBitmap(request.imgPath)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun decodeSampledBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calculateInSampleSize(bounds, VIDEO_THUMBNAIL_WIDTH, VIDEO_THUMBNAIL_HEIGHT)
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun decodeSampledBitmap(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = calculateInSampleSize(bounds, VIDEO_THUMBNAIL_WIDTH, VIDEO_THUMBNAIL_HEIGHT)
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2

            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize.coerceAtLeast(1)
    }

    private fun loadVideoThumbnail(context: Context, request: Request): Bitmap? {
        // 先尝试从缓存文件读取
        val thumbnailFile = getVideoThumbnailFile(context, request.cacheKey)
        if (thumbnailFile != null && thumbnailFile.exists()) {
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeFile(thumbnailFile.absolutePath, options)
        }

        // 从视频提取帧
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(request.videoUri))
            val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    VIDEO_THUMBNAIL_WIDTH,
                    VIDEO_THUMBNAIL_HEIGHT
                )
            } else {
                retriever.getFrameAtTime(0)
            }

            // 保存到缓存文件
            if (frame != null && thumbnailFile != null) {
                saveVideoThumbnail(frame, thumbnailFile)
            }
            frame
        } finally {
            retriever.release()
        }
    }

    private fun loadPersistedThumbnail(context: Context, request: Request): Bitmap? {
        val thumbnailFile = getVideoThumbnailFile(context, request.cacheKey) ?: return null
        if (!thumbnailFile.exists() || thumbnailFile.length() <= 0L) return null
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(thumbnailFile.absolutePath, options)
    }

    private fun persistThumbnail(context: Context, request: Request, bitmap: Bitmap) {
        val thumbnailFile = getVideoThumbnailFile(context, request.cacheKey) ?: return
        if (thumbnailFile.exists() && thumbnailFile.length() > 0L) return
        saveVideoThumbnail(bitmap, thumbnailFile)
    }

    private fun getVideoThumbnailFile(context: Context, cacheKey: String): File? {
        val thumbnailDir = getVideoThumbnailDir(context) ?: return null
        return File(thumbnailDir, "${videoThumbnailFileStem(cacheKey)}.jpg")
    }

    private fun getVideoThumbnailDir(context: Context): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val thumbnailDir = File(root, "thumbnail_cache")
        if (!thumbnailDir.mkdirs() && !thumbnailDir.exists()) {
            return null
        }
        return thumbnailDir
    }

    private fun videoThumbnailFileStem(cacheKey: String): String {
        return Integer.toHexString(cacheKey.hashCode())
    }

    private fun saveVideoThumbnail(bitmap: Bitmap, file: File) {
        runCatching {
            FileOutputStream(file).use {
                val saved = bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it)
                if (!saved) {
                    Log.w(TAG, "Failed to persist video thumbnail: ${file.absolutePath}")
                }
            }
        }.onFailure {
            Log.e(TAG, "Failed to save video thumbnail: ${file.absolutePath}", it)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 公开的视频帧提取方法
    // ─────────────────────────────────────────────────────────────

    /**
     * 从视频中提取指定时间点的帧
     * @param context 上下文
     * @param videoUri 视频 URI
     * @param timeUs 时间点（微秒），默认为 0
     * @param option 帧提取选项，默认为 OPTION_CLOSEST_SYNC
     * @param targetWidth 目标宽度（仅 API 27+），默认为 VIDEO_THUMBNAIL_WIDTH
     * @param targetHeight 目标高度（仅 API 27+），默认为 VIDEO_THUMBNAIL_HEIGHT
     * @return 提取的 Bitmap，失败返回 null
     */
    fun getVideoFrame(
        context: Context,
        videoUri: Uri,
        timeUs: Long = 0L,
        option: Int = MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        targetWidth: Int = VIDEO_THUMBNAIL_WIDTH,
        targetHeight: Int = VIDEO_THUMBNAIL_HEIGHT
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, videoUri)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(timeUs, option, targetWidth, targetHeight)
            } else {
                retriever.getFrameAtTime(timeUs, option)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract video frame: $videoUri", e)
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * 从视频 URI 字符串中提取帧（便捷方法）
     */
    fun getVideoFrame(
        context: Context,
        videoUriString: String,
        timeUs: Long = 0L,
        option: Int = MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
        targetWidth: Int = VIDEO_THUMBNAIL_WIDTH,
        targetHeight: Int = VIDEO_THUMBNAIL_HEIGHT
    ): Bitmap? {
        return getVideoFrame(context, Uri.parse(videoUriString), timeUs, option, targetWidth, targetHeight)
    }

}
