package com.zeaze.tianyinwallpaper.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Locale

data class SuperSplatWebParams(
    val parallaxStrength: Float,
    val cameraZoom: Float,
    val cameraDefaultDistance: Float = 0f,
    val cameraDefaultFov: Float = 0f,
    val cameraCalibrationVersion: Int = 0,
    val centerOffsetX: Float,
    val centerOffsetY: Float,
    val focusDepth: Float,
    val cameraFov: Float,
    val performanceMode: Boolean,
    val backgroundRed: Float = 0f,
    val backgroundGreen: Float = 0f,
    val backgroundBlue: Float = 0f
) {
    fun toJs(resetCamera: Boolean): String {
        return "window.tianyinSetParams && window.tianyinSetParams(" +
            "${parallaxStrength.toJsFloat()}, " +
            "${cameraZoom.toJsFloat()}, " +
            "${cameraDefaultDistance.toJsFloat()}, " +
            "${cameraDefaultFov.toJsFloat()}, " +
            "$cameraCalibrationVersion, " +
            "${centerOffsetX.toJsFloat()}, " +
            "${centerOffsetY.toJsFloat()}, " +
            "${focusDepth.toJsFloat()}, " +
            "${cameraFov.toJsFloat()}, " +
            "${if (performanceMode) "true" else "false"}, " +
            "${backgroundRed.toJsFloat()}, " +
            "${backgroundGreen.toJsFloat()}, " +
            "${backgroundBlue.toJsFloat()}, " +
            "${if (resetCamera) "true" else "false"}" +
            ");"
    }
}

class SuperSplatWebController(
    private val appContext: Context
) {
    var webView: WebView? = null
        private set
    var loadedUriString: String? = null
        private set
    var onCenterOffsetChange: ((Float, Float) -> Unit)? = null
    var onCameraDefaultsChange: ((Float, Float) -> Unit)? = null
    var onRenderRequested: (() -> Unit)? = null
    var onLoadingChanged: ((Boolean) -> Unit)? = null
    var onFirstFrameReady: (() -> Unit)? = null
    var pendingParams: SuperSplatWebParams? = null
    var pageReady: Boolean = false
        private set
    private var loadStartedMs: Long = 0L
    @Volatile
    var modelUri: Uri? = null
    @Volatile
    var resetSensorBaseline: Boolean = true

    @SuppressLint("SetJavaScriptEnabled")
    fun createWebView(context: Context): WebView {
        return WebView(context).apply {
            webView = this
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    consoleMessage?.let {
                        Log.d(TAG, "console ${it.messageLevel()}: ${it.message()}")
                    }
                    return true
                }
            }
            webViewClient = SuperSplatWebViewClient(appContext, this@SuperSplatWebController)
            addJavascriptInterface(SuperSplatBridge(this@SuperSplatWebController), "TianyinSplat")
        }
    }

    fun attachWebView(view: WebView) {
        webView = view
    }

    fun destroy() {
        webView?.stopLoading()
        webView?.destroy()
        webView = null
        pageReady = false
        onLoadingChanged?.invoke(false)
        loadedUriString = null
    }

    fun loadModelIfNeeded(uriString: String) {
        val target = webView ?: return
        if (loadedUriString == uriString && target.url == SUPER_SPLAT_URL) {
            return
        }
        pageReady = false
        loadedUriString = uriString
        loadStartedMs = SystemClock.elapsedRealtime()
        onLoadingChanged?.invoke(true)
        target.loadUrl(SUPER_SPLAT_URL)
    }

    fun onPageFinished(view: WebView?) {
        webView = view
    }

    fun onViewerReady() {
        Log.d(TAG, "viewer ready elapsedMs=${elapsedSinceLoadStart()} uri=$loadedUriString")
        pageReady = true
        onLoadingChanged?.invoke(false)
        applyParams(resetCamera = false)
        onRenderRequested?.invoke()
    }

    fun onFirstFrame() {
        Log.d(TAG, "first frame elapsedMs=${elapsedSinceLoadStart()} uri=$loadedUriString")
        onLoadingChanged?.invoke(false)
        onFirstFrameReady?.invoke()
        onRenderRequested?.invoke()
    }

    fun setParams(params: SuperSplatWebParams) {
        pendingParams = params
        applyParams()
    }

    fun applyParams(resetCamera: Boolean = false) {
        val target = webView ?: return
        val params = pendingParams ?: return
        if (!pageReady) return
        target.post {
            if (!pageReady) return@post
            target.evaluateJavascript(params.toJs(resetCamera), null)
            onRenderRequested?.invoke()
        }
    }

    fun resetCamera() {
        val target = webView ?: return
        if (!pageReady) return
        target.post {
            target.evaluateJavascript(
                "window.tianyinResetCamera && window.tianyinResetCamera();",
                null
            )
            onRenderRequested?.invoke()
        }
    }

    private fun elapsedSinceLoadStart(): Long {
        return if (loadStartedMs > 0L) {
            SystemClock.elapsedRealtime() - loadStartedMs
        } else {
            0L
        }
    }

    fun setTilt(tiltX: Float, tiltY: Float) {
        val target = webView ?: return
        if (!pageReady) return
        target.post {
            if (!pageReady) return@post
            target.evaluateJavascript(
                "window.tianyinSetTilt && window.tianyinSetTilt(${tiltX.toJsFloat()}, ${tiltY.toJsFloat()});",
                null
            )
            onRenderRequested?.invoke()
        }
    }

    private class SuperSplatBridge(
        private val controller: SuperSplatWebController
    ) {
        private val mainHandler = Handler(Looper.getMainLooper())

        @JavascriptInterface
        fun onCenterChanged(x: Double, y: Double) {
            val clampedX = x.toFloat().coerceIn(-2.5f, 2.5f)
            val clampedY = y.toFloat().coerceIn(-2.5f, 2.5f)
            mainHandler.post {
                controller.onCenterOffsetChange?.invoke(clampedX, clampedY)
            }
        }

        @JavascriptInterface
        fun onViewerReady() {
            mainHandler.post {
                controller.onViewerReady()
            }
        }

        @JavascriptInterface
        fun onCameraDefaultsCalculated(distance: Double, fov: Double) {
            val distanceValue = distance.toFloat()
            val fovValue = fov.toFloat()
            if (!distanceValue.isFinite() || distanceValue <= 0f) return
            if (!fovValue.isFinite() || fovValue <= 0f) return
            mainHandler.post {
                controller.onCameraDefaultsChange?.invoke(distanceValue, fovValue)
            }
        }

        @JavascriptInterface
        fun onFirstFrame() {
            mainHandler.post {
                controller.onFirstFrame()
            }
        }

    }

    private class SuperSplatWebViewClient(
        private val context: Context,
        private val controller: SuperSplatWebController
    ) : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            controller.onPageFinished(view)
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            Log.w(TAG, "WebView error ${request?.url}: ${error?.description}")
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url ?: return null
            if (url.host != "tianyin-supersplat.local") return null
            return runCatching {
                when (url.path.orEmpty()) {
                    "/", "/index.html" -> assetResponse("text/html", "$SUPER_SPLAT_VIEWER_PATH/index.html")
                    "/index.css" -> assetResponse("text/css", "$SUPER_SPLAT_VIEWER_PATH/index.css")
                    "/index.js" -> assetResponse("application/javascript", "$SUPER_SPLAT_VIEWER_PATH/index.js")
                    "/settings.json" -> assetResponse("application/json", "$SUPER_SPLAT_VIEWER_PATH/settings.json")
                    "/model.sog" -> modelResponse()
                    else -> notFound()
                }
            }.onFailure {
                Log.w(TAG, "Failed to serve ${url.path}", it)
            }.getOrNull()
        }

        private fun assetResponse(mimeType: String, assetPath: String): WebResourceResponse {
            return WebResourceResponse(
                mimeType,
                "UTF-8",
                200,
                "OK",
                commonHeaders(),
                context.assets.open(assetPath)
            )
        }

        private fun modelResponse(): WebResourceResponse {
            Log.d(TAG, "serve model uri=${controller.modelUri}")
            val input = context.contentResolver.openInputStream(controller.modelUri ?: return notFound())
                ?: return notFound()
            return WebResourceResponse(
                "application/octet-stream",
                null,
                200,
                "OK",
                commonHeaders(),
                input
            )
        }

        private fun notFound(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                404,
                "Not Found",
                commonHeaders(),
                ByteArrayInputStream(ByteArray(0)) as InputStream
            )
        }

        private fun commonHeaders(): Map<String, String> {
            return mapOf(
                "Access-Control-Allow-Origin" to SUPER_SPLAT_ORIGIN,
                "Cache-Control" to "no-store"
            )
        }
    }

    companion object {
        private const val TAG = "SuperSplatWeb"
        private const val SUPER_SPLAT_ORIGIN = "https://tianyin-supersplat.local"
        private const val SUPER_SPLAT_VIEWER_PATH = "supersplat-viewer"
        private const val SUPER_SPLAT_URL =
            "$SUPER_SPLAT_ORIGIN/index.html?content=/model.sog&settings=/settings.json&webgl&noui&nofx&aa&fullload&noanim"
    }
}

private fun Float.toJsFloat(): String {
    return String.format(Locale.US, "%.5f", this)
}
