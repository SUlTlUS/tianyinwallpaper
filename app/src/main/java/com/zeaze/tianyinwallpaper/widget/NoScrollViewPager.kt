package com.zeaze.tianyinwallpaper.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

class NoScrollViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewPager(context, attrs) {
    var isNoScroll = false

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return !isNoScroll && super.onTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return !isNoScroll && super.onInterceptTouchEvent(ev)
    }
}
