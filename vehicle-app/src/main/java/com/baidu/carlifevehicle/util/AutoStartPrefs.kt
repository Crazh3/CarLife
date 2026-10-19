package com.baidu.carlifevehicle.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.baidu.carlife.sdk.util.Logger

/**
 * 「开机启动」偏好（设置页新增条目，**默认关闭**）。
 *
 * 开 → 车机开机（/重启）后系统在发出 ACTION_BOOT_COMPLETED 时拉起 CarLife 主界面；
 * 关 → 开机只初始化应用，不自动打开界面，需要用户手动进入。
 *
 * ## 实现方式：启用/禁用开机广播组件
 * 与「记住授权 / USB拉起APP」（见 [ConnectTypePrefs]）同一套做法：
 * [BOOT_RECEIVER_CLASS] 在 AndroidManifest 里声明为 `android:enabled="false"`，
 * 只有本开关为「开」时才在 PackageManager 中把组件置为 ENABLED，
 * 这样「关」的状态下系统根本不会在开机时唤醒本应用进程（而不是起来之后再判断跳过）。
 *
 * 进程每次启动（含覆盖安装 / 升级后）都会由 VehicleApplication 调 [apply] 同步一次
 * 组件状态与设置值，避免两者不一致。
 *
 * 注意（车机系统层限制）：部分车机有「自启动管理 / 开机自启白名单」，
 * 即便这里打开了，也可能被系统拦掉，需要在系统设置里把本应用加入白名单。
 */
object AutoStartPrefs {

    private const val TAG = "AutoStartPrefs"

    /** 与「连接方式 / 显示方式 / 记住授权 / 主题同步」同用一个 SP 文件 */
    private const val PREFS_SETTINGS = "vehicle_settings"
    private const val KEY_ENABLED = "auto_start_enabled"

    /** 与 AndroidManifest 中的开机广播接收器对应 */
    private const val BOOT_RECEIVER_CLASS = "com.baidu.carlifevehicle.BootReceiver"

    /**
     * 是否开启「开机启动」，**默认关闭**（首次安装 / 清除应用数据后为关）。
     * 清单里 BootReceiver 也声明了 android:enabled="false"，所以首次启动前同样是关。
     */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Logger.d(TAG, "setEnabled $enabled")
        apply(context, enabled)
    }

    /** 把开关同步到系统组件状态；不传值时按当前设置值同步（供 App 启动时调用）。 */
    fun apply(context: Context, enabled: Boolean = isEnabled(context)) {
        val packageManager = context.packageManager
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        val component = ComponentName(context.packageName, BOOT_RECEIVER_CLASS)
        if (packageManager.getComponentEnabledSetting(component) == state) {
            Logger.d(TAG, "BootReceiver already ${if (enabled) "ENABLED" else "DISABLED"}")
            return
        }
        try {
            packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP
            )
            Logger.d(TAG, "BootReceiver -> ${if (enabled) "ENABLED" else "DISABLED"}")
        } catch (e: Exception) {
            Logger.e(TAG, "set BootReceiver enabled=$enabled failed: ", e)
        }
    }

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
}
