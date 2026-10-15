package com.baidu.carlifevehicle.audio.player.source

import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import java.io.Closeable

@DoNotStrip
interface AudioSource: Closeable {
    //TAG
    var tag: Any?

    //媒体数据数据类型：Media、Navi、VR
    val streamType: Int

    //焦点类型:长期独占型、短期独占型、可混音型
    val focusType: Int

    //是否延迟获取焦点
    val allowFocusDelayed: Boolean

    //是否强制获取焦点
    val forceGrantFocus: Boolean

    //当前播放位置
    fun currentPosition(): Long

    //音源读取是否结束
    fun isClosed(): Boolean

    //AudioTrack的api接口参数
    fun params(): AudioParams

    //一次性读取
    fun drainTo(buffer: ResizableArray, timeout: Long): Int

    //长音分批读取
    fun readFrame(buffer: ResizableArray, offset: Int, timeout: Long): Int
}