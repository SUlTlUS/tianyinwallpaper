package com.zeaze.tianyinwallpaper.utils

import android.content.Context
import android.widget.Toast

/**
 * 显示短时 Toast
 */
fun Context.showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/**
 * 显示长时 Toast
 */
fun Context.showToastLong(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
