package com.zeaze.tianyinwallpaper.model

data class MixedWallpaperPlaylistItem(
    var kind: String = KIND_WALLPAPER,
    var wallpaper: TianYinWallpaperModel? = null,
    var rasterGroupId: String? = null,
    var depthWallpaperId: String? = null
) {
    companion object {
        const val KIND_WALLPAPER = "wallpaper"
        const val KIND_RASTER_STATIC = "raster_static"
        const val KIND_RASTER_DYNAMIC = "raster_dynamic"
        const val KIND_DEPTH = "depth"

        fun wallpaper(model: TianYinWallpaperModel): MixedWallpaperPlaylistItem {
            return MixedWallpaperPlaylistItem(kind = KIND_WALLPAPER, wallpaper = model)
        }

        fun raster(group: RasterGroupModel): MixedWallpaperPlaylistItem {
            return MixedWallpaperPlaylistItem(
                kind = if (group.type == RasterGroupModel.TYPE_DYNAMIC) KIND_RASTER_DYNAMIC else KIND_RASTER_STATIC,
                rasterGroupId = group.id
            )
        }

        fun depth(model: DepthWallpaperModel): MixedWallpaperPlaylistItem {
            return MixedWallpaperPlaylistItem(kind = KIND_DEPTH, depthWallpaperId = model.id)
        }
    }
}
