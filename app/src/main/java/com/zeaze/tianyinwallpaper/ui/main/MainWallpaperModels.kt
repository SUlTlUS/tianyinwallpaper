package com.zeaze.tianyinwallpaper.ui.main

import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel

enum class MainWallpaperFilter(val label: String) {
    All("全部"),
    Wallpaper("壁纸"),
    Raster("光栅"),
    Depth("景深")
}

sealed class MainUnifiedWallpaperItem {
    data class Wallpaper(val index: Int, val model: TianYinWallpaperModel) : MainUnifiedWallpaperItem()
    data class Raster(val group: RasterGroupModel) : MainUnifiedWallpaperItem()
    data class Depth(val model: DepthWallpaperModel) : MainUnifiedWallpaperItem()
}

fun buildMainUnifiedWallpaperItems(
    wallpapers: List<TianYinWallpaperModel>,
    rasterGroups: List<RasterGroupModel>,
    depthWallpapers: List<DepthWallpaperModel>,
    filter: MainWallpaperFilter
): List<MainUnifiedWallpaperItem> {
    return buildList {
        if (filter == MainWallpaperFilter.All || filter == MainWallpaperFilter.Wallpaper) {
            wallpapers.forEachIndexed { index, model ->
                add(MainUnifiedWallpaperItem.Wallpaper(index, model))
            }
        }
        if (filter == MainWallpaperFilter.All || filter == MainWallpaperFilter.Raster) {
            rasterGroups.forEach { add(MainUnifiedWallpaperItem.Raster(it)) }
        }
        if (filter == MainWallpaperFilter.All || filter == MainWallpaperFilter.Depth) {
            depthWallpapers.forEach { add(MainUnifiedWallpaperItem.Depth(it)) }
        }
    }
}
