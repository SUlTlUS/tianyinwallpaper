package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/**
 * 缩略图加载工具类
 * 提供图片和视频缩略图的加载、缓存功能
 */
object ThumbnailUtils {

    private const val TAG = "ThumbnailUtils"
    private const val MEMORY_DIVISOR = 8L
    private const val VIDEO_THUMBNAIL_WIDTH = 360
    private const val VIDEO_THUMBNAIL_HEIGHT = 640

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
        val imgPath: String?    // 本地图片路径（旧版兼容）
    ) {
        val cacheKey: String get() = "$uuid|$type|$imgUri|$videoUri|$imgPath"
    }

    /**
     * 从内存缓存获取缩略图
     */
    fun getFromCache(request: Request): Bitmap? = cache.get(request.cacheKey)

    /**
     * 加载缩略图（带缓存）
     */
    fun loadThumbnail(context: Context, request: Request): Bitmap? {
        // 先查缓存
        getFromCache(request)?.let { return it }

        // 加载缩略图
        val bitmap = loadThumbnailInternal(context, request) ?: return null

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
        Thread {
            val bitmap = loadThumbnail(context, request)
            android.os.Handler(context.mainLooper).post { onResult(bitmap) }
        }.start()
    }

    /**
     * 预加载缩略图到缓存
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
     * 清除缓存
     */
    fun clearCache() {
        cache.evictAll()
    }

    /**
     * 从缓存移除指定项
     */
    fun removeFromCache(request: Request) {
        cache.remove(request.cacheKey)
    }

    // ── 内部方法 ──

    private fun loadThumbnailInternal(context: Context, request: Request): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }

        return runCatching {
            when {
                // 图片 URI
                request.type == 0 && !request.imgUri.isNullOrEmpty() -> {
                    context.contentResolver.openInputStream(Uri.parse(request.imgUri))?.use {
                        BitmapFactory.decodeStream(it, null, options)
                    }
                }
                // 视频 URI
                request.type == 1 && !request.videoUri.isNullOrEmpty() -> {
                    loadVideoThumbnail(context, request)
                }
                // 本地图片路径（旧版兼容）
                !request.imgPath.isNullOrEmpty() -> {
                    BitmapFactory.decodeFile(request.imgPath, options)
                }
                else -> null
            }
        }.getOrNull()
    }

    private fun loadVideoThumbnail(context: Context, request: Request): Bitmap? {
        // 先尝试从缓存文件读取
        val thumbnailFile = getVideoThumbnailFile(context, request.uuid)
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

    private fun getVideoThumbnailFile(context: Context, uuid: String): File? {
        val root = context.getExternalFilesDir(null) ?: return null
        val thumbnailDir = File(root, "thumbnail_cache")
        if (!thumbnailDir.mkdirs() && !thumbnailDir.exists()) {
            return null
        }
        return File(thumbnailDir, "$uuid.jpg")
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
}
