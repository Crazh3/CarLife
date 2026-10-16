package com.baidu.carlifevehicle.util

import android.content.Context
import com.baidu.carlife.sdk.util.Logger

/**
 * 主页顶部栏左侧的「服务」总开关：关闭后禁止连接手机，用于改设置时临时停掉连接。
 *
 * ## 为什么用 stopConnect 而不是 disconnect
 * CarLifeReceiver 提供两个断开接口，语义不同：
 *  - [com.baidu.carlife.sdk.receiver.CarLifeReceiver.disconnect] / terminate：
 *    断开后**会发起重连**，不适合"我要临时停掉"；
 *  - [com.baidu.carlife.sdk.receiver.CarLifeReceiver.stopConnect]：断开**并终止所有
 *    transport**（GroupedProtocolTransport.stopConnect 把 transports 清空），
 *    scanner 随之停止，USB 再插拔也不会拉起连接，直到重新调 connect()。
 *
 * 所以：
 *  - 关 → stopConnect()，彻底停掉，不会再自动连上；
 *  - 开 → connect()，若 transports 已空会自动 configConnectType() 重建，恢复连接。
 *
 * 状态持久化在 vehicle_settings，**默认开启**（不影响原有行为）。
 */
object ServiceSwitchPrefs {

    private const val TAG = "ServiceSwitchPrefs"

    private const val KEY_SERVICE_ENABLED = "service_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("vehicle_settings", Context.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("vehicle_settings", Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_ENABLED, enabled).apply()
        Logger.d(TAG, "service enabled changed to $enabled")
    }
}
