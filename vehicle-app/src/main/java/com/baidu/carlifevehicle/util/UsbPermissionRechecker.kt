package com.baidu.carlifevehicle.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.baidu.carlife.sdk.util.Logger

/**
 * 主动重发 USB 授权弹窗。
 *
 * ## 背景
 * Android 的 [UsbManager.requestPermission] 弹系统授权框。
 * 用户「拒绝」时系统**不发任何广播**（只允许时才发 ACTION_USB_PERMISSION），
 * SDK 内部 UsbAccessoryScanner.poll() 把设备取出后**永远等不到回调**，
 * 队列里这个设备被「吃掉了」，下次必须重新插拔 USB（触发 ACTION_USB_DEVICE_ATTACHED）
 * 才会再次入队 + 弹窗。
 *
 * 这导致：用户拒绝 → 弹出再次弹窗的延迟 ≈ "拔掉 USB + 重新插入"的物理时间。
 *
 * ## 解决
 * 在 [CarlifeActivity.onResume] 调用本类 [recheckAoaDevices]，
 * 主动遍历当前 [UsbManager.getDeviceList] 中的 AOA 设备，
 * 未授权的立即 [UsbManager.requestPermission] 重新弹一次。
 *
 * - 不依赖系统 ATTACHED 事件 —— 用户回到前台时可以重新弹一次
 * - 不依赖 SDK 内部状态 —— 直接调系统 API
 * - PendingIntent flags 加上 FLAG_IMMUTABLE / FLAG_UPDATE_CURRENT 保证
 *   第二次调用 PendingIntent 不会因 extras 一样被系统去重
 * - 只重发 Google AOA accessory 设备（0x18D1/0x2D00-0x2D05）的授权框；
 *   手机普通模式设备的授权框由 SDK UsbAccessoryScanner 自己负责
 * - **同一设备 [MIN_REQUEST_INTERVAL_MS] 内不重复弹框**：
 *   系统弹框一关闭 Activity 就会 onResume，如果没有节流，
 *   用户点「拒绝」→ 框立刻又弹出来（永远关不掉）。加了节流后：
 *   点「允许」→ 立刻 [UsbManager.hasPermission] 为 true，不会重弹；
 *   点「拒绝」→ 本次插入不再自动弹（用户重新插线即可再次授权，deviceName 会变、记录被清理）。
 */
object UsbPermissionRechecker {

    /** 同一设备两次主动弹框的最小间隔（毫秒），避免"拒绝→界面恢复→又弹"死循环 */
    private const val MIN_REQUEST_INTERVAL_MS = 5_000L

    private const val TAG = "UsbPermissionRechecker"

    /** 与 SDK UsbAccessoryScanner 内部 ACTION 一致 */
    private const val ACTION_USB_PERMISSION = "com.baidu.carlifevehicle.connect.USB_PERMISSION"

    /** AOA vendor id（Google） */
    private const val VID_ACCESSORY = 0x18D1
    /** AOA product id 范围：accessory / accessory+audio / accessory+adb / accessory+audio+adb / accessory+audio+adb+bulk */
    private const val PID_ACCESSORY_ONLY = 0x2D00
    private const val PID_ACCESSORY_AUDIO_ADB_BULK = 0x2D05

    /**
     * 本类主动弹过框的设备（deviceName → 上次弹框时间）。
     * deviceName 形如 /dev/bus/usb/001/058，重新插入/重新枚举都会分配新名字，
     * 所以配合"清理不在当前设备列表里的记录"，等价于"每次插入最多主动弹一次"。
     */
    private val lastRequestedAt = HashMap<String, Long>()

    /**
     * 遍历当前 USB 列表中所有 AOA accessory 设备，
     * 对未授权的设备立即调 [UsbManager.requestPermission] 弹系统授权框。
     *
     * @return 触发的弹窗数量（用于日志/统计）
     */
    fun recheckAoaDevices(context: Context): Int {
        val usbManager = context.applicationContext
            .getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: run {
                Logger.w(TAG, "recheckAoaDevices: UsbManager not available")
                return 0
            }

        val now = System.currentTimeMillis()
        val currentDeviceNames = HashSet<String>()
        var requested = 0
        for (device in usbManager.deviceList.values) {
            if (!isAoaAccessory(device)) continue
            currentDeviceNames.add(device.deviceName)
            if (usbManager.hasPermission(device)) {
                Logger.d(TAG, "recheckAoaDevices: ${describe(device)} already granted, skip")
                continue
            }
            val lastRequested = lastRequestedAt[device.deviceName]
            if (lastRequested != null && now - lastRequested < MIN_REQUEST_INTERVAL_MS) {
                Logger.d(
                    TAG,
                    "recheckAoaDevices: ${describe(device)} already requested " +
                            "${now - lastRequested}ms ago, skip"
                )
                continue
            }
            lastRequestedAt[device.deviceName] = now
            requestPermission(context.applicationContext, usbManager, device)
            requested++
        }
        // 设备已不在列表（拔掉 / AOA 重新枚举换了 deviceName）→ 清掉记录，
        // 重新插入时可以再次弹框；同时避免 map 无限增长。
        lastRequestedAt.keys.retainAll(currentDeviceNames)
        Logger.d(TAG, "recheckAoaDevices: requested=$requested")
        return requested
    }

    private fun requestPermission(
        appContext: Context,
        usbManager: UsbManager,
        device: UsbDevice
    ) {
        val intent = Intent(ACTION_USB_PERMISSION)
        val flags = (PendingIntent.FLAG_UPDATE_CURRENT
                or PendingIntent.FLAG_IMMUTABLE)
        val permissionIntent = PendingIntent.getBroadcast(
            appContext,
            /* requestCode = */ device.deviceId,
            intent,
            flags
        )
        Logger.d(TAG, "requestPermission for ${describe(device)}")
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun isAoaAccessory(device: UsbDevice): Boolean {
        return device.vendorId == VID_ACCESSORY
                && device.productId in PID_ACCESSORY_ONLY..PID_ACCESSORY_AUDIO_ADB_BULK
    }

    private fun describe(device: UsbDevice): String =
        "vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                "manufacturer=${device.manufacturerName} product=${device.productName} " +
                "deviceId=${device.deviceId}"
}
