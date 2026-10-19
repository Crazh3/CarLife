package com.baidu.carlifevehicle.util

import android.content.Context
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 「显示方式」偏好：
 *  - 系统默认（[MODE_SYSTEM]）：沿用主题行为 —— Theme.CarLifeSDK 里的
 *    `android:windowFullscreen` 已经隐藏了状态栏，应用不再做任何额外的系统栏处理。
 *  - 沉浸式全屏（[MODE_IMMERSIVE_FULLSCREEN]，**默认值**）：在系统默认的基础上把导航栏也隐藏，
 *    并进入 sticky 沉浸（系统栏被边缘滑动临时唤出后自动收回），界面铺满整个屏幕。
 *    默认沉浸式是产品要求的出厂效果，车机界面应当铺满整屏。
 *
 * 生效逻辑见 [DisplayModeApplier]，由 CarlifeActivity 在 onCreate / onResume 应用。
 */
object DisplayModePrefs {

    private const val TAG = "DisplayModePrefs"

    /** 「显示方式」与「记住授权 / USB 拉起 APP」同用一个 SP 文件 */
    private const val PREFS_SETTINGS = "vehicle_settings"
    private const val KEY_DISPLAY_MODE = "display_mode"

    /** 系统默认 */
    const val MODE_SYSTEM = 0

    /** 沉浸式全屏 */
    const val MODE_IMMERSIVE_FULLSCREEN = 1

    /** 默认「沉浸式全屏」（首次安装 / 清数据后）：产品要求出厂即全屏铺满 */
    fun currentMode(context: Context): Int =
        settingsPrefs(context).getInt(KEY_DISPLAY_MODE, MODE_IMMERSIVE_FULLSCREEN)

    fun setMode(context: Context, mode: Int) {
        settingsPrefs(context).edit().putInt(KEY_DISPLAY_MODE, mode).apply()
        Logger.d(TAG, "setMode $mode")
    }

    fun isImmersiveFullscreen(context: Context): Boolean =
        currentMode(context) == MODE_IMMERSIVE_FULLSCREEN

    /** 显示名的字符串资源（设置列表的值、弹窗选项共用） */
    fun modeNameRes(mode: Int): Int =
        if (mode == MODE_IMMERSIVE_FULLSCREEN) {
            R.string.display_mode_immersive
        } else {
            R.string.display_mode_system
        }

    private fun settingsPrefs(context: Context) =
        context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
}
