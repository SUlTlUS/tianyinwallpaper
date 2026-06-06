package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.BufferedInputStream
import java.nio.FloatBuffer
import java.util.LinkedHashMap
import java.util.Locale

object GaussianSceneLoader {
    private const val TAG = "GaussianSceneLoader"
    private const val MAX_SCENE_CACHE_ENTRIES = 1
    private val gaussianLoadLock = Any()
    private val sceneCache = object : LinkedHashMap<CacheKey, CachedScene>(MAX_SCENE_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CachedScene>?): Boolean {
            return size > MAX_SCENE_CACHE_ENTRIES
        }
    }

    data class SceneLoadResult(
        val scene: GaussianPlyLoader.GaussianScene?,
        val error: String? = null
    )

    private data class CacheKey(
        val uriString: String,
        val maxSplats: Int,
        val viewportAspectBucket: Int
    )

    private data class CachedScene(
        val scene: GaussianPlyLoader.GaussianScene,
        val createdAtMs: Long
    )

    fun loadScene(
        context: Context,
        uriString: String,
        maxSplats: Int = GaussianSogLoader.DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): GaussianPlyLoader.GaussianScene? = loadSceneDetailed(
        context = context,
        uriString = uriString,
        maxSplats = maxSplats,
        viewportAspect = viewportAspect
    ).scene

    fun loadSceneDetailed(
        context: Context,
        uriString: String,
        maxSplats: Int = GaussianSogLoader.DEFAULT_MAX_SPLATS,
        viewportAspect: Float? = null
    ): SceneLoadResult {
        if (!prefersSog(context, uriString)) {
            return SceneLoadResult(scene = null, error = "Gaussian PLY 已移除，请先转换为 SOG")
        }
        return runCatching {
            synchronized(gaussianLoadLock) {
                val key = CacheKey(uriString, maxSplats, bucketForCache(viewportAspect))
                sceneCache[key]?.let { cached ->
                    Log.d(TAG, "scene cache hit ageMs=${SystemClock.elapsedRealtime() - cached.createdAtMs} uri=$uriString")
                    return@runCatching duplicateForConsumer(cached.scene)
                }
                val scene = GaussianSogLoader.loadSceneOrThrow(context, uriString, maxSplats, viewportAspect)
                sceneCache[key] = CachedScene(scene, SystemClock.elapsedRealtime())
                duplicateForConsumer(scene)
            }
        }.fold(
            onSuccess = { SceneLoadResult(scene = it) },
            onFailure = { SceneLoadResult(scene = null, error = "SOG: ${it.message ?: it.javaClass.simpleName}") }
        )
    }

    fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            clearSceneCache()
        }
    }

    fun clearSceneCache() {
        synchronized(gaussianLoadLock) {
            sceneCache.clear()
        }
    }

    private fun prefersSog(context: Context, uriString: String): Boolean {
        val byName = uriString
            .substringBefore('?')
            .lowercase(Locale.US)
            .endsWith(".sog")
        if (byName) return true
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                val buffered = BufferedInputStream(input)
                buffered.read() == 'P'.code && buffered.read() == 'K'.code
            } == true
        }.getOrDefault(false)
    }

    private fun bucketForCache(viewportAspect: Float?): Int {
        val aspect = viewportAspect?.takeIf { it.isFinite() && it > 0f } ?: 0f
        return (aspect * 1000f).toInt()
    }

    private fun duplicateForConsumer(scene: GaussianPlyLoader.GaussianScene): GaussianPlyLoader.GaussianScene {
        return scene.copy(
            positions = duplicateForRead(scene.positions),
            colors = duplicateForRead(scene.colors),
            scales = duplicateForRead(scene.scales),
            rotations = scene.rotations?.let(::duplicateForRead)
        )
    }

    private fun duplicateForRead(buffer: FloatBuffer): FloatBuffer {
        return buffer.duplicate().apply { position(0) }
    }

}
