package com.zeaze.tianyinwallpaper.update

/**
 * 版本更新信息数据类
 * 用于解析 GitHub 上存放的版本信息 JSON
 */
data class UpdateInfo(
    val code: Int = 0,           // 版本号 (versionCode)
    val name: String = "",       // 版本名称 (versionName)
    val filename: String = "",   // APK 文件名
    val url: String = "",        // APK 下载地址
    val time: Long = 0,          // 更新时间戳
    val des: String = "",        // 更新说明
    val size: Long = 0,          // APK 文件大小 (字节)
    val md5: String = ""         // APK 文件 MD5 值
)
