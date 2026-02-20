package com.zeaze.tianyinwallpaper.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainRouteScreenTest {
    @Test
    fun wallpaperTypeByMimeOrName_detectsImageAndVideo() {
        assertEquals(0, wallpaperTypeByMimeOrName("image/jpeg", null))
        assertEquals(1, wallpaperTypeByMimeOrName("video/mp4", null))
        assertEquals(0, wallpaperTypeByMimeOrName(null, "cover.PNG"))
        assertEquals(1, wallpaperTypeByMimeOrName(null, "clip.MOV"))
        assertNull(wallpaperTypeByMimeOrName("text/plain", "readme.txt"))
    }
}
