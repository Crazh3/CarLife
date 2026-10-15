package com.baidu.carlifevehicle.util

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.baidu.carlife.sdk.internal.DisplaySpec
import com.baidu.carlife.sdk.util.Logger

/**
 * 投屏（视频）分辨率配置。
 *
 * 车机把"渲染区域"的宽高下发给手机端，手机端按此分辨率编码视频帧，
 * 车机端再解码渲染。
 *
 * ## 关键约束
 *
 * 1. **比例一致**：下发的 width/height 比例必须与车机 GL 渲染区实际比例一致，
 *    否则会出现拉伸/黑边/模糊。
 * 2. **H.264 16 对齐**：手机端 H.264 编码器要求 width/height 都是 16 的倍数，
 *    否则会被强制降级到下一个 16 对齐的标准尺寸（比如车机下发 1278x718，
 *    手机端会降到 1024x576，因为 1280x720 中 1278 不是 16 倍数）。
 *
 * ## 实现
 *
 * 接收 GLView 实际测量尺寸 (widthPx, heightPx)，保持比例，
 * 把 width 向上对齐到 16 倍数，height 按比例计算后也向上对齐到 16 倍数。
 * 最终下发尺寸可能略大于 GLView 实际像素（最多 15px），
 * 渲染时边缘会被裁掉，肉眼不可见，但能保证手机端 H.264 编码器按
 * 标准 720p/1080p 编码（不降级到 1024x576 等更小尺寸）。
 *
 * ## 例子（1280x720 屏）
 * - 系统默认：GLView=1278x658 → 下发 1280x672（16 对齐后比例 1.905，
 *   实际比例 1.942 差 1.9%）
 * - 沉浸式：GLView=1278x718 → 下发 1280x720（标准 720p，手机端按 1280x720 编码）
 *
 * ## 兜底
 * [buildFallback] 在 GLView 还没拿到真实尺寸时使用，用整屏物理像素。
 */
object DisplaySpecConfig {

    private const val TAG = "DisplaySpecConfig"

    /** 视频帧率 */
    const val FRAME_RATE = 30

    /** H.264 编码器要求的对齐基数（必须是 16 的倍数） */
    private const val H264_ALIGNMENT = 16

    /**
     * 用 GLView 实际测量尺寸生成 DisplaySpec，**保持比例**并做 16 向上对齐。
     *
     * @param widthPx GLView 实际宽度（物理像素），可能不是 16 倍数
     * @param heightPx GLView 实际高度（物理像素），可能不是 16 倍数
     * @param context 用于 DisplaySpec 取 densityDpi（不参与渲染）
     */
    fun build(widthPx: Int, heightPx: Int, context: Context): DisplaySpec {
        val actualW = maxOf(widthPx, heightPx)
        val actualH = minOf(widthPx, heightPx)
        if (actualW <= 0 || actualH <= 0) {
            Logger.w(TAG, "build invalid size ${actualW}x${actualH}, fallback to fallback")
            return buildFallback(context)
        }

        val (alignedW, alignedH) = alignToH264(actualW, actualH)

        val actualRatio = actualW.toFloat() / actualH
        val alignedRatio = alignedW.toFloat() / alignedH
        val ratioDiff = kotlin.math.abs(actualRatio - alignedRatio) / actualRatio

        Logger.w(
            TAG,
            "build actual=${actualW}x${actualH} ratio=%.4f -> " +
                    "aligned=${alignedW}x${alignedH} ratio=%.4f diff=%.2f%%".format(
                        actualRatio, alignedRatio, ratioDiff * 100
                    )
        )
        return DisplaySpec(context.applicationContext, alignedW, alignedH, FRAME_RATE)
    }

    /**
     * 把 (w, h) 调整到 16 对齐（向上取整），保持比例。
     * 公式：width 先向上对齐 16，height 按比例计算后再向上对齐 16。
     */
    private fun alignToH264(w: Int, h: Int): Pair<Int, Int> {
        val w16 = alignUp16(w)
        // 按比例计算理想 height，再向上对齐
        val hIdeal = (w16.toLong() * h / w).toInt()
        val h16 = alignUp16(hIdeal)
        // 极端情况（hIdeal 比 h 小很多，比如 0）：回退到不缩放的 h 向上对齐
        val finalH = if (h16 <= 0) alignUp16(h) else h16
        return Pair(w16, finalH)
    }

    /** 向上对齐到 16 的倍数 */
    private fun alignUp16(v: Int): Int = ((v + H264_ALIGNMENT - 1) / H264_ALIGNMENT) * H264_ALIGNMENT

    /**
     * 兜底：连接前/未拿到 GLView 尺寸时使用。取整屏物理像素（含系统栏），
     * 同样做 16 向上对齐。
     */
    fun buildFallback(context: Context): DisplaySpec {
        val real = realMetrics(context)
        val actualW = maxOf(real.widthPixels, real.heightPixels)
        val actualH = minOf(real.widthPixels, real.heightPixels)
        val (alignedW, alignedH) = alignToH264(actualW, actualH)
        Logger.w(
            TAG,
            "buildFallback real=${actualW}x${actualH} -> ${alignedW}x${alignedH}"
        )
        return DisplaySpec(context.applicationContext, alignedW, alignedH, FRAME_RATE)
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
}
