package com.baidu.carlifevehicle.util

import android.content.Context
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.preference.PreferenceManager
import com.baidu.carlife.sdk.internal.DisplaySpec
import kotlin.math.abs

/**
 * 投屏（视频）分辨率配置。
 *
 * 该分辨率会通过协议下发给手机端，手机端按此分辨率编码视频帧，车机端再解码渲染，
 * 因此「下发分辨率的宽高比」必须与车机屏幕宽高比一致，否则一定会出现黑边。
 *
 * - 默认「自动」：跟随显示模式取基准尺寸 —— 沉浸式全屏按屏幕真实宽高，系统默认按去掉系统栏后的可用区
 * - 也可手动指定 1080P / 720P / 576P / W480P
 * - 手选分辨率与基准比例不一致、或大于基准尺寸（会被放大反而更糊）时，自动回退为「自动」
 */
object DisplaySpecConfig {

    private const val TAG = "DisplaySpecConfig"

    /** 与 res/xml/settings.xml 中的 key 保持一致 */
    const val KEY_RESOLUTION = "resolutions"

    /** 跟随车机屏幕（默认） */
    const val VALUE_AUTO = "auto"

    /** 视频帧率 */
    const val FRAME_RATE = 30

    /**
     * 下发分辨率的长边上限（按屏幕宽高比等比缩放到不超过此值）。
     * 1080P 屏的 1920 已经是大多数手机 MediaCodec 编码器最舒服的尺寸，再大初始化会变慢；
     * 缩到 1920 以内后，编码器启动、首帧出图都明显更快。
     */
    private const val MAX_LONG_EDGE = 1920

    /** 宽高比容差，超出则视为与屏幕比例不一致 */
    private const val ASPECT_TOLERANCE = 0.02f

    /** 读取设置项并按当前车机屏幕计算 DisplaySpec */
    fun build(context: Context): DisplaySpec {
        val saved = PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_RESOLUTION, VALUE_AUTO)
        return build(context, saved)
    }

    /**
     * 按指定配置值计算 DisplaySpec。
     * 设置页 onPreferenceChange 回调时新值尚未写入 SharedPreferences，需要显式传入。
     */
    fun build(context: Context, value: String?): DisplaySpec {
        val auto = autoSpec(context)
        if (value == null || value == VALUE_AUTO) {
            return auto
        }

        val parts = value.split("x")
        if (parts.size != 2) {
            Log.w(TAG, "invalid resolution value: $value, fallback to auto")
            return auto
        }
        val width = parts[0].toIntOrNull()
        val height = parts[1].toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) {
            Log.w(TAG, "invalid resolution value: $value, fallback to auto")
            return auto
        }

        val targetLong = maxOf(width, height)
        val targetShort = minOf(width, height)

        // 比例不一致会出现黑边
        val screenRatio = auto.width.toFloat() / auto.height
        val targetRatio = targetLong.toFloat() / targetShort
        if (abs(screenRatio - targetRatio) > ASPECT_TOLERANCE) {
            Log.w(
                TAG,
                "resolution $value ratio($targetRatio) != screen ratio($screenRatio), fallback to auto"
            )
            return auto
        }

        // 大于车机屏幕只会被放大显示，反而更糊
        if (targetLong > auto.width || targetShort > auto.height) {
            Log.w(
                TAG,
                "resolution $value is larger than screen ${auto.width}x${auto.height}, fallback to auto"
            )
            return auto
        }

        Log.d(TAG, "use resolution $value")
        return DisplaySpec(context, targetLong, targetShort, FRAME_RATE)
    }

    /**
     * 按显示模式取基准尺寸：
     * 沉浸式全屏 —— 屏幕真实尺寸，状态栏/导航栏不占位，视频可以点对点铺满整屏
     * 系统默认 —— 去掉状态栏/导航栏后的可用区，避免下发超出可用区被压缩
     *
     * 注意：这里用 [Context.getResources] 的 displayMetrics（应用声明了
     * screenOrientation=landscape，它即窗口实际方向），而不是 applicationContext 的
     * （application 的资源方向可能仍是竖屏，取它会算错比例，投屏四周就会出现黑边）。
     */
    private fun autoSpec(context: Context): DisplaySpec {
        val immersive = DisplayModePrefs.isImmersiveFullscreen(context.applicationContext)
        val usable = context.resources.displayMetrics
        val real = realMetrics(context)
        val base = if (immersive) real else usable
        // 按屏幕宽高等比缩放到 [MAX_LONG_EDGE] 以内，再下发给手机端。
        // 不缩放的话，超大屏（2680x1200 等）会让手机 MediaCodec 编码器按大分辨率初始化虚拟显示
        // + 配置编码参数，整体耗时明显变长，表现为"启动 App 后连接很慢"。
        // 缩放后分辨率变小了，但宽高比与屏幕一致 → 投屏依然铺满整屏、无黑边。
        val longEdge = maxOf(base.widthPixels, base.heightPixels)
        val shortEdge = minOf(base.widthPixels, base.heightPixels)
        val targetLong: Int
        val targetShort: Int
        if (longEdge > MAX_LONG_EDGE) {
            val scale = MAX_LONG_EDGE.toFloat() / longEdge
            targetLong = MAX_LONG_EDGE
            targetShort = (shortEdge * scale).toInt()
        } else {
            targetLong = longEdge
            targetShort = shortEdge
        }
        Log.i(
            TAG,
            "autoSpec immersive=$immersive usable=${usable.widthPixels}x${usable.heightPixels}" +
                    " real=${real.widthPixels}x${real.heightPixels}" +
                    " use=${base.widthPixels}x${base.heightPixels}" +
                    " target=${targetLong}x${targetShort}"
        )
        return DisplaySpec(context, targetLong, targetShort, FRAME_RATE)
    }

    private fun realMetrics(context: Context): DisplayMetrics {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return context.resources.displayMetrics
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }
}
