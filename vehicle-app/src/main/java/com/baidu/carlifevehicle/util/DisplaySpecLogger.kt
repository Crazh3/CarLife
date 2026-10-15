package com.baidu.carlifevehicle.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import com.baidu.carlife.sdk.internal.DisplaySpec
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.util.DisplayModeConfig

/**
 * 投屏分辨率协商诊断日志。
 *
 * 把所有与"车机 ↔ 手机端分辨率协商"相关的信息统一到一个 TAG 下打印，
 * 方便用户抓一份日志就能完整复现协商过程：
 * - 车机物理屏分辨率（realMetrics）
 * - application window 尺寸（getMetrics）
 * - 当前显示模式（系统默认/沉浸式全屏）
 * - 下发给手机端的 DisplaySpec 尺寸
 * - GLView 实际测量尺寸
 * - 手机端最终回传的编码尺寸（MSG_CMD_VIDEO_ENCODER_INIT_DONE）
 *
 * 抓日志方式：`adb logcat -s DisplaySpec:V` 或 `-s DisplaySpecConfig:V`
 */
object DisplaySpecLogger {

    const val TAG = "DisplaySpec"

    /** 车机物理屏分辨率（realMetrics，含系统栏） */
    fun logPhysicalScreen(context: Context, scene: String) {
        val metrics = realMetrics(context)
        Logger.w(
            TAG,
            "[$scene] physicalScreen=${metrics.widthPixels}x${metrics.heightPixels} " +
                    "densityDpi=${metrics.densityDpi} density=${metrics.density} " +
                    "ratio=${ratio(metrics.widthPixels, metrics.heightPixels)} " +
                    "mode=${modeName(context)}"
        )
    }

    /** application window 尺寸（getMetrics，去掉系统栏） */
    fun logAppWindow(context: Context, scene: String) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.getMetrics(metrics)
        Logger.w(
            TAG,
            "[$scene] appWindow=${metrics.widthPixels}x${metrics.heightPixels} " +
                    "ratio=${ratio(metrics.widthPixels, metrics.heightPixels)}"
        )
    }

    /** GLView 实际测量出的尺寸（物理像素） */
    fun logGlviewSize(view: View, scene: String) {
        Logger.w(
            TAG,
            "[$scene] glview=${view.width}x${view.height} " +
                    "layoutParams=${view.layoutParams?.width}x${view.layoutParams?.height} " +
                    "ratio=${ratio(view.width, view.height)}"
        )
    }

    /** DisplaySpec 下发结果 */
    fun logDisplaySpec(spec: DisplaySpec, scene: String, source: String) {
        Logger.w(
            TAG,
            "[$scene] displaySpec source=$source " +
                    "width=${spec.width} height=${spec.height} fps=${spec.frameRate} " +
                    "densityDpi=${spec.densityDpi} ratio=${"%.4f".format(spec.ratio)}"
        )
    }

    /** 手机端最终编码尺寸（来自 MSG_CMD_VIDEO_ENCODER_INIT_DONE） */
    fun logPhoneEncodedSize(width: Int, height: Int, scene: String) {
        Logger.w(
            TAG,
            "[$scene] phoneEncoded=${width}x${height} " +
                    "ratio=${ratio(width, height)}"
        )
    }

    /** 屏幕真实尺寸（含状态栏/导航栏） */
    private fun realMetrics(context: Context): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return context.resources.displayMetrics
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun ratio(w: Int, h: Int): String {
        if (h == 0) return "?"
        val r = w.toFloat() / h
        return "%.4f".format(r)
    }

    private fun modeName(context: Context): String =
        if (DisplayModeConfig.getMode(context) == DisplayModeConfig.MODE_IMMERSIVE)
            "immersive" else "system"
}
