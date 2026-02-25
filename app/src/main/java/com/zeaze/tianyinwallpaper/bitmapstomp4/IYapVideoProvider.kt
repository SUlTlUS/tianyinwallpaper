package com.zeaze.tianyinwallpaper.bitmapstomp4

/**
 * @author YaphetZhao
 * @email yaphetzhao@gmail.com
 * @data 2020-07-30
 * @wechat yaphetzhao92
 */
interface IYapVideoProvider<T> {

    /**
     * list size
     */
    fun size(): Int

    /**
     * next item
     */
    operator fun next(): T

    /**
     * progress (0..1f)
     */
    fun progress(progress: Float)

}