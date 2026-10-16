package com.baidu.carlifevehicle.util

import android.content.Context
import android.content.res.Configuration
import com.baidu.carlife.protobuf.CarlifeThemeChangeProto
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.util.Logger

/**
 * 车机深浅色（昼夜）模式 → 手机端同步。
 *
 * 协议与官方车机端 App（`com.baidu.carlifevehicle.beta`）完全一致：
 * ```
 * CarLifeMessage.obtain(MSG_CHANNEL_CMD, 0x00018078)
 *     .payload(CarlifeThemeChange.newBuilder().setThemeModes(mode).build())
 * // mode: 0 = 白天（白天模式 / MODE_DAY），1 = 夜间（夜间模式 / MODE_NIGHT）
 * ```
 * 手机端「深色模式跟随车机」就是靠这条消息切换主题并重绘投屏画面；
 * 车机不发，手机端无从得知，投屏画面不会变。
 *
 * 取值来源（与官方一致）：系统 `Configuration.uiMode & UI_MODE_NIGHT_MASK`
 * （`0x10` 浅色 → 白天，`0x20` 深色 → 夜间）；未定义（`UI_MODE_NIGHT_UNDEFINED`）时
 * 回退到上次同步出去的值（见 [ThemeSyncPrefs.lastState]）。
 *
 * 推给手机的时机：
 *  1. CarLife 连接刚建立（必须推一次当前状态，手机端只能被动接收）；
 *  2. 系统深浅色变化（[com.baidu.carlifevehicle.VehicleApplication.onConfigurationChanged]）；
 *  3. 在设置页把「主题同步」重新打开时（见 [ThemeSyncDialog]）。
 *
 * 同一个值不会重复发送（断开连接后复位，下次连上重发一次）。
 */
object ThemeModeReporter {

    private const val TAG = "ThemeModeReporter"

    /** 白天模式 */
    const val MODE_DAY = 0

    /** 夜间模式 */
    const val MODE_NIGHT = 1

    /** 尚未同步过 */
    private const val UNKNOWN = -1

    /** 连接是否已建立（由 CarlifeActivity 的连接回调维护） */
    @Volatile
    private var connected = false

    /** 上一次同步出去的值，用于去重 */
    @Volatile
    private var lastSent = UNKNOWN

    fun onConnectionEstablished() {
        connected = true
    }

    fun onConnectionDetached() {
        connected = false
        // 断开后复位：下次连上要重新推一次当前状态
        lastSent = UNKNOWN
    }

    /** 当前系统的深浅色状态，映射为 [MODE_DAY] / [MODE_NIGHT]。 */
    fun currentMode(context: Context): Int =
        when (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) {
            Configuration.UI_MODE_NIGHT_YES -> MODE_NIGHT
            Configuration.UI_MODE_NIGHT_NO -> MODE_DAY
            // 车机没设置昼夜（或 UI_MODE_NIGHT_UNDEFINED）：用上次同步过的值兜底
            else -> ThemeSyncPrefs.lastState(context)
        }

    /**
     * 需要时把当前主题同步给手机端。
     *
     * @param force true 时无论是否变化都发一次（连接刚建立时用）
     */
    fun syncIfNeeded(context: Context, force: Boolean = false) {
        if (!ThemeSyncPrefs.isEnabled(context)) {
            Logger.d(TAG, "theme sync disabled, skip")
            return
        }
        if (!connected) {
            Logger.d(TAG, "not connected, skip theme sync")
            return
        }
        val mode = currentMode(context)
        if (!force && mode == lastSent) {
            return
        }
        send(context, mode)
    }

    private fun send(context: Context, mode: Int) {
        try {
            val message = CarLifeMessage.obtain(
                Constants.MSG_CHANNEL_CMD,
                ServiceTypes.MSG_CMD_HU_THEME_CHANGE
            )
            message.payload(
                CarlifeThemeChangeProto.CarlifeThemeChange.newBuilder()
                    .setThemeModes(mode)
                    .build()
            )
            CarLife.receiver().postMessage(message)
            lastSent = mode
            ThemeSyncPrefs.setLastState(context, mode)
            Logger.d(TAG, "sync theme mode=$mode (0=day 1=night)")
        } catch (e: Exception) {
            Logger.e(TAG, "sync theme mode=$mode failed: $e")
        }
    }
}
