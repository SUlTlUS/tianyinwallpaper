package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.util.Locale

object GaussianSceneLoader {
    private val gaussianLoadLock = Any()

    data class SceneLoadResult(
        val scene: GaussianPlyLoader.GaussianScene?,
        val error: String? = null
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
                GaussianSogLoader.loadSceneOrThrow(context, uriString, maxSplats, viewportAspect)
            }
        }.fold(
            onSuccess = { SceneLoadResult(scene = it) },
            onFailure = { SceneLoadResult(scene = null, error = "SOG: ${it.message ?: it.javaClass.simpleName}") }
        )
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

}
