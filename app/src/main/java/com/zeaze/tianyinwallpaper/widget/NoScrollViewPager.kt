package com.zeaze.tianyinwallpaper.widget

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager

class NoScrollViewPager : ViewPager {
    var isNoScroll = false

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)
    constructor(context: Context) : super(context)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        return if (isNoScroll) {
            false
        } else {
            super.onTouchEvent(ev)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return if (isNoScroll) {
            false
        } else {
            super.onInterceptTouchEvent(ev)
        }
    }
}
