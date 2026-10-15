package com.baidu.carlifevehicle.util

import android.content.Context
import com.baidu.carlife.sdk.Configs.FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.util.Logger

/**
 * 声音传输通道偏好：
 * - 互联（carlife audio path）：声音走 carlife 自己的 TCP 通道，与视频同链路
 * - 蓝牙（BT audio path）：声音走蓝牙 A2DP 通道
 *
 * SDK 注释（Configs.java）：
 *   0: carlife audio path ; 1: BT audio path
 *
 * 默认值是 MODE_INTERCONNECT（互联），与"声音跟视频同链路、用户无感切换"的预期一致。
 * 注意：默认值是首次安装时生效，已有 SharedPreferences 时会读到上次的值。
 *
 * 与 ConnectTypePrefs 不同，AudioTransmissionMode 在 CarLifeContext 里
 * 直接通过 setFeature(FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE, value) 通知 SDK 重建。
 */
object AudioTransmissionPrefs {

    private const val TAG = "AudioTransmissionPrefs"

    private const val PREFS_NAME = "vehicle_settings"
    private const val KEY_MODE = "audio_transmission_mode"

    /** 互联：声音走 carlife TCP 通道 */
    const val MODE_INTERCONNECT = 0
    /** 蓝牙：声音走蓝牙 A2DP 通道 */
    const val MODE_BLUETOOTH = 1

    /** 中文显示名 */
    fun modeName(mode: Int): String = when (mode) {
        MODE_BLUETOOTH -> "蓝牙"
        else -> "互联"
    }

    fun modeName(context: Context): String = modeName(currentMode(context))

    fun currentMode(context: Context): Int =
        getPrefs(context).getInt(KEY_MODE, MODE_INTERCONNECT)

    /**
     * 切换声音传输通道：持久化 + 立即通知 SDK。
     */
    fun setMode(context: Context, mode: Int) {
        if (mode == currentMode(context)) {
            return
        }
        getPrefs(context).edit().putInt(KEY_MODE, mode).apply()
        Logger.d(TAG, "setMode $mode (${modeName(mode)})")
        try {
            CarLife.receiver().setFeature(FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE, mode)
        } catch (e: Exception) {
            // VehicleApplication 还未 init 时（理论不会发生），忽略
            Logger.e(TAG, "setFeature AUDIO_TRANSMISSION_MODE failed: ", e)
        }
    }

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
