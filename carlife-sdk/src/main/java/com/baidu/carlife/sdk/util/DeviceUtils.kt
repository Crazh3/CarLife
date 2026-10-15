package com.baidu.carlife.sdk.util

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager

val availableProcessors by lazy {
    return@lazy Runtime.getRuntime().availableProcessors()
}

fun Context.screenSize(): Pair<Int, Int> {
    val point = Point()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // API 30+ 推荐使用 Context.getDisplay()
        display?.getRealSize(point)
    } else {
        // API 29- 仍需通过 WindowManager.defaultDisplay 获取
        @Suppress("DEPRECATION")
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealSize(point)
    }
    return Pair(point.x, point.y)
}

fun Context.screenSizePortrait(): Pair<Int, Int> {
    val size = screenSize()
    return Pair(
        size.first.coerceAtMost(size.second),
        size.first.coerceAtLeast(size.second)
    )
}

fun Context.screenSizeHorizontal(): Pair<Int, Int> {
    val size = screenSize()
    return Pair(
        size.first.coerceAtLeast(size.second),
        size.first.coerceAtMost(size.second)
    )
}