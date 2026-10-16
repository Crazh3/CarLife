package com.baidu.carlifevehicle.util

import android.content.Context
import com.baidu.carlife.sdk.util.Logger

/**
 * 「主题同步」（设置页新增条目，默认开启）。
 *
 * 开启后：车机把当前深浅色（昼夜）状态通过 CarLife 协议同步给手机端
 * （`0x00018078` + `CarlifeThemeChange.themeModes`，0=白天 / 1=夜间），
 * 手机端的「深色模式跟随车机」才会随之切换并重绘投屏画面。
 *
 * 两个 key 与官方车机端 App（`com.baidu.carlifevehicle.beta`）的实现对齐：
 *  - [KEY_ENABLED]    本项开关；
 *  - [KEY_LAST_STATE] 上一次同步出去的主题值（官方 key 名为 `TASKER_THEME_STATE`，
 *    官方在发送成功后写入，并在系统 `uiMode` 未定义时用它兜底）。
 *
 * 生效逻辑见 [ThemeModeReporter]。
 */
object ThemeSyncPrefs {

    private const val TAG = "ThemeSyncPrefs"

    /** 与「连接方式 / 显示方式 / 记住授权」同用一个 SP 文件 */
    private const val PREFS_SETTINGS = "vehicle_settings"
    private const val KEY_ENABLED = "theme_sync_enabled"
    private const val KEY_LAST_STATE = "theme_state"

    /** 默认开启：手机端「深色模式跟随车机」依赖它，关掉即手机端不再跟随。 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Logger.d(TAG, "setEnabled $enabled")
    }

    /** 上一次同步出去的主题值；没有记录时按「白天」算（与官方默认值一致）。 */
    fun lastState(context: Context): Int =
        prefs(context).getInt(KEY_LAST_STATE, ThemeModeReporter.MODE_DAY)

    fun setLastState(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_LAST_STATE, mode).apply()
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
}
