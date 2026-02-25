package com.zeaze.tianyinwallpaper.bitmapstomp4

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View

object YapVideoUtils {

    fun convertViewToBitmap(view: View): Bitmap {
        val width = view.measuredWidth.takeIf { it > 0 } ?: 1
        val height = view.measuredHeight.takeIf { it > 0 } ?: 1

        return Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565).apply {
            val canvas = Canvas(this)
            view.draw(canvas)
        }
    }

}