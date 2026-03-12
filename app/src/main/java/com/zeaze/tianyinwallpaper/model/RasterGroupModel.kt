package com.zeaze.tianyinwallpaper.model

data class RasterGroupModel(
    var id: String = "",
    var type: Int = TYPE_STATIC,
    var imageUris: List<String> = emptyList(),
    var videoUri: String? = null,
    var createdAt: Long = 0L,
    val sensorWidth: Float = 1.5f
) {
    companion object {
        const val TYPE_STATIC = 0
        const val TYPE_DYNAMIC = 1
    }
}

