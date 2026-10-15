package com.baidu.carlifevehicle.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baidu.carlife.sdk.util.Logger

/**
 * USB 设备匹配组件（**空实现，不处理任何业务**）。
 *
 * ## 为什么需要它
 * 系统「USB 授权弹窗」是否给出「一律允许（默认用本应用打开该设备）」勾选，
 * 取决于本应用有没有声明"USB_DEVICE_ATTACHED + device_filter 匹配当前设备"的组件：
 *  - 参考工程 CarLife-Android-Vehicle 的 AOAAccessoryReceiver 就是这种组件
 *    （Receiver + 全匹配 device_filter <usb-device />），所以它的弹窗永远带勾选框。
 *  - 本项目之前只在 activity-alias 上写了过滤器，且只匹配 AOA(0x18D1)，
 *    而首次授权框针对的是"手机普通模式设备"（VID 是手机厂商的），匹配不到就没有勾选。
 * 所以这里按参考工程的做法补一个 Receiver，并让它用全匹配过滤器一起作为匹配载体
 * （主载体是 UsbAutoLaunchAlias，系统 canBeDefault 判定走的是 activity，
 *  插线自动拉起界面也靠它；Receiver 用于兼容把 receiver 也算作处理者的系统实现）。
 *
 * ## 谁控制它
 * 设置项「连接方式-USB-记住授权」→ [com.baidu.carlifevehicle.util.ConnectTypePrefs.applyRememberGrant]
 * 在 PackageManager 里启用/禁用本 Receiver：
 *  - 开：启用 → 系统认为本应用是该设备默认处理者 → 弹窗有「一律允许」，勾一次即可；
 *  - 关：禁用 → 弹窗没有勾选 → 每次插入都要重新授权，也不会自动拉起 CarLife。
 *
 * 注意：本类由系统在 USB 插拔时实例化（可能在 Application 初始化之前/之后），
 * 所以这里只写日志，不读写任何应用状态、不做任何跳转。
 */
class UsbDeviceMatchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        Logger.d(TAG, "onReceive action=${intent?.action}")
    }

    private companion object {
        const val TAG = "UsbDeviceMatchReceiver"
    }
}
