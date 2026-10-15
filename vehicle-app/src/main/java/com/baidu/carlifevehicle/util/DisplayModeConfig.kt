package com.baidu.carlifevehicle.util

import android.app.Activity
import android.content.Context
import android.view.View
import com.baidu.carlife.sdk.util.Logger

/**
 * 显示模式配置：
 * - 系统默认 —— 跟随系统，显示状态栏/导航栏
 * - 沉浸式全屏 —— 隐藏状态栏和导航栏（粘性沉浸，从边缘滑入可临时呼出）
 *
 * 默认值是 [MODE_SYSTEM]（系统默认）。
 */
object DisplayModeConfig {

    private const val TAG = "DisplayModeConfig"

    const val MODE_SYSTEM = 0
    const val MODE_IMMERSIVE = 1

    private const val PREFS_NAME = "vehicle_settings"
    private const val KEY_MODE = "display_mode"

    fun getMode(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_MODE, MODE_SYSTEM)

    fun setMode(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MODE, mode).apply()
    }

    fun modeName(context: Context): String =
        if (getMode(context) == MODE_IMMERSIVE) "沉浸式全屏" else "系统默认"

    /** 按当前配置应用到 Activity 窗口 */
    fun apply(activity: Activity) {
        val decor = activity.window.decorView
        if (getMode(activity) == MODE_IMMERSIVE) {
            Logger.d(TAG, "apply MODE_IMMERSIVE")
            @Suppress("DEPRECATION") // API 30 之前推荐用 systemUiVisibility；项目 compileSdk=30
            decor.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        } else {
            Logger.d(TAG, "apply MODE_SYSTEM")
            @Suppress("DEPRECATION") // API 30 之前推荐用 systemUiVisibility；项目 compileSdk=30
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}