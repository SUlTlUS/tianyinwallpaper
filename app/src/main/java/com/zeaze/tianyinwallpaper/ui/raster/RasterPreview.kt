package com.zeaze.tianyinwallpaper.ui.raster

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.utils.ThumbnailUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val RASTER_THUMBNAIL_TYPE_STATIC = 0
private const val RASTER_THUMBNAIL_TYPE_DYNAMIC = 1

internal fun RasterGroupModel.toThumbnailRequest(): ThumbnailUtils.Request? {
    return if (type == RasterGroupModel.TYPE_STATIC) {
        val firstImageUri = imageUris.firstOrNull() ?: return null
        ThumbnailUtils.Request(
            uuid = id,
            type = RASTER_THUMBNAIL_TYPE_STATIC,
            imgUri = firstImageUri,
            videoUri = null,
            imgPath = null
        )
    } else {
        val dynamicVideoUri = videoUri ?: return null
        ThumbnailUtils.Request(
            uuid = id,
            type = RASTER_THUMBNAIL_TYPE_DYNAMIC,
            imgUri = null,
            videoUri = dynamicVideoUri,
            imgPath = null
        )
    }
}

@Composable
fun RasterGroupThumbnail(group: RasterGroupModel) {
    val context = LocalContext.current
    val request = remember(
        group.id,
        group.type,
        group.videoUri,
        group.imageUris.firstOrNull()
    ) {
        group.toThumbnailRequest()
    }

    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = request?.let { ThumbnailUtils.getFromCache(it) },
        key1 = request?.cacheKey
    ) {
        val safeRequest = request ?: run {
            value = null
            return@produceState
        }
        if (value != null) return@produceState

        value = withContext(Dispatchers.IO) {
            ThumbnailUtils.loadThumbnail(context, safeRequest)
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF2A2A2A)),
            contentAlignment = Alignment.Center
        ) {
            Text("无缩略图", color = Color.White)
        }
    }
}

@Composable
fun GyroDynamicRasterPreview(
    group: RasterGroupModel,
    sensorWidth: Float = 4.5f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val videoUri = group.videoUri
    val (tilt, _) = rememberTiltState(sensorWidth)

    val cachedFrames = remember { mutableStateListOf<android.graphics.Bitmap?>() }
    var framesLoaded by remember { mutableStateOf(false) }

    LaunchedEffect(videoUri) {
        if (videoUri.isNullOrEmpty()) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(context, Uri.parse(videoUri))

                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L

                if (duration > 0) {
                    val frameCount = 10
                    val interval = duration / frameCount

                    repeat(frameCount) { index ->
                        val timeUs = (index * interval * 1000).coerceAtLeast(0L)
                        val frame = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                360,
                                640
                            )
                        } else {
                            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                        cachedFrames.add(frame)
                    }
                }
                retriever.release()
                framesLoaded = true
            } catch (e: Exception) {
                Log.w("RasterPreview", "Failed to preload video frames", e)
            }
        }
    }

    val frameIndex = if (cachedFrames.isEmpty()) -1
    else (tilt * (cachedFrames.size - 1)).roundToInt().coerceIn(0, cachedFrames.lastIndex)
    val currentFrame = cachedFrames.getOrNull(frameIndex)

    Box(modifier = modifier) {
        if (currentFrame != null) {
            Image(
                bitmap = currentFrame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (!framesLoaded) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) { Text("加载中...", color = Color.White) }
        } else {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                contentAlignment = Alignment.Center
            ) { Text("视频帧加载失败", color = Color.White) }
        }
    }
}

@Composable
fun rememberTiltState(sensorWidth: Float = 4.5f, maxAngle: Float = 30f): Pair<Float, Int> {
    val context = LocalContext.current
    var tilt by remember { mutableStateOf(0f) }
    var direction by remember { mutableStateOf(0) }
    val maxAngleRadians = Math.toRadians(maxAngle.toDouble()).toFloat()

    DisposableEffect(context, sensorWidth, maxAngleRadians) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (gyroSensor != null) {
            var lastNs = 0L
            var accumulated = 0f

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val e = event ?: return
                    if (lastNs == 0L) {
                        lastNs = e.timestamp
                        return
                    }

                    val dt = (e.timestamp - lastNs) / 1_000_000_000f
                    lastNs = e.timestamp
                    accumulated += e.values[1] * dt
                    accumulated *= 0.998f

                    val clampedAccumulated = accumulated.coerceIn(-maxAngleRadians, maxAngleRadians)
                    tilt = (abs(clampedAccumulated) / maxAngleRadians).coerceIn(0f, 1f)
                    direction = when {
                        accumulated < -0.05f -> 1
                        accumulated > 0.05f -> -1
                        else -> direction
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            sensorManager.registerListener(listener, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sensorManager.unregisterListener(listener) }
        } else {
            onDispose { }
        }
    }

    return tilt to direction
}
