package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.net.Uri
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object GradioMcpSogGenerator {
    private const val MCP_ENDPOINT = "https://mi0hn0-ml-sharp.ms.show/gradio_api/mcp/"
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun generateFromImageUrl(
        context: Context,
        imageUrl: String,
        focalLength35mm: Float = 0f
    ): Uri {
        require(imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            "Image URL must start with http:// or https://"
        }
        val arguments = JSONObject().apply {
            put("image_path", imageUrl)
            put("device", "auto")
            put("focal_length_35mm", focalLength35mm)
        }
        val params = JSONObject().apply {
            put("name", "run_generation")
            put("arguments", arguments)
        }
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", "tools/call")
            put("params", params)
        }
        val responseText = client.newCall(
            Request.Builder()
                .url(MCP_ENDPOINT)
                .header("Accept", "application/json, text/event-stream")
                .post(payload.toJSONString().toRequestBody(jsonType))
                .build()
        ).execute().use { response ->
            if (!response.isSuccessful) error("MCP generation failed: HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
        val sogUrl = extractFirstSogUrl(responseText)
            ?: error("MCP generation did not return a SOG URL")
        return downloadSog(context, sogUrl)
    }

    private fun extractFirstSogUrl(responseText: String): String? {
        val payloads = responseText
            .lineSequence()
            .mapNotNull { line ->
                line.trim()
                    .takeIf { it.startsWith("data:") }
                    ?.removePrefix("data:")
                    ?.trim()
            }
            .toList()
            .ifEmpty { listOf(responseText) }
        payloads.forEach { payload ->
            findSogUrlInAny(runCatching { JSON.parse(payload) }.getOrNull())?.let { return it }
        }
        return Regex("""https?://[^\s"'<>]+?\.sog(?:\?[^\s"'<>]*)?""")
            .find(responseText)
            ?.value
    }

    private fun findSogUrlInAny(value: Any?): String? {
        return when (value) {
            is String -> value.takeIf { it.contains(".sog", ignoreCase = true) && it.startsWith("http") }
            is JSONObject -> {
                value.values.asSequence().mapNotNull(::findSogUrlInAny).firstOrNull()
            }
            is JSONArray -> {
                value.asSequence().mapNotNull(::findSogUrlInAny).firstOrNull()
            }
            is Iterable<*> -> {
                value.asSequence().mapNotNull(::findSogUrlInAny).firstOrNull()
            }
            else -> null
        }
    }

    private fun downloadSog(context: Context, url: String): Uri {
        val response = client.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            if (!it.isSuccessful) error("SOG download failed: HTTP ${it.code}")
            val dir = File(context.filesDir, "generated_sog").apply { mkdirs() }
            val file = File(dir, "generated_${UUID.randomUUID()}.sog")
            file.outputStream().use { output ->
                it.body?.byteStream()?.use { input -> input.copyTo(output) }
                    ?: error("SOG download returned empty body")
            }
            return Uri.fromFile(file)
        }
    }
}
