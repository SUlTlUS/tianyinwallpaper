package com.zeaze.tianyinwallpaper.ui.main

import com.zeaze.tianyinwallpaper.model.DepthWallpaperModel
import com.zeaze.tianyinwallpaper.model.RasterGroupModel
import com.zeaze.tianyinwallpaper.model.TianYinWallpaperModel

/** 旧的一级筛选分组保留给旧调用，新的顶部筛选菜单使用 MainWallpaperKindFilter。 */
enum class MainWallpaperFilter(val label: String) {
    All("全部"),
    Wallpaper("壁纸"),
    Raster("光栅"),
    Depth("景深")
}

enum class MainWallpaperKindFilter(val label: String) {
    ImageWallpaper("图片"),
    VideoWallpaper("视频"),
    StaticRaster("图集光栅"),
    VideoRaster("视频光栅"),
    Depth("景深")
}

enum class MainWallpaperSortMode(val label: String) {
    Custom("自定义"),
    AddedDate("添加日期"),
    Type("类型"),
    Size("大小"),
    RecentOpened("最近打开日期")
}

enum class MainWallpaperSortDirection(val label: String) {
    Ascending("递增"),
    Descending("递减")
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

fun buildMainUnifiedWallpaperItems(
    wallpapers: List<TianYinWallpaperModel>,
    rasterGroups: List<RasterGroupModel>,
    depthWallpapers: List<DepthWallpaperModel>,
    kindFilters: Set<MainWallpaperKindFilter>
): List<MainUnifiedWallpaperItem> {
    val showAll = kindFilters.isEmpty()
    return buildList {
        wallpapers.forEachIndexed { index, model ->
            val isImage = model.type == 0
            val visible = showAll ||
                (isImage && MainWallpaperKindFilter.ImageWallpaper in kindFilters) ||
                (!isImage && MainWallpaperKindFilter.VideoWallpaper in kindFilters)
            if (visible) add(MainUnifiedWallpaperItem.Wallpaper(index, model))
        }
        rasterGroups.forEach { group ->
            val visible = showAll || when (group.type) {
                RasterGroupModel.TYPE_STATIC -> MainWallpaperKindFilter.StaticRaster in kindFilters
                RasterGroupModel.TYPE_DYNAMIC -> MainWallpaperKindFilter.VideoRaster in kindFilters
                else -> false
            }
            if (visible) add(MainUnifiedWallpaperItem.Raster(group))
        }
        if (showAll || MainWallpaperKindFilter.Depth in kindFilters) {
            depthWallpapers.forEach { add(MainUnifiedWallpaperItem.Depth(it)) }
        }
    }
}
