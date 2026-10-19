package com.baidu.carlifevehicle.usb

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.baidu.carlife.sdk.util.Logger

/**
 * USB 接入触发的界面启动策略。
 *
 * ## 背景
 * AndroidManifest 里的 [com.baidu.carlifevehicle.UsbAutoLaunchAlias] 声明了
 * `USB_DEVICE_ATTACHED + device_filter（全匹配）`，所以**任何** USB 设备插入时
 * 系统都会把 CarlifeActivity 拉起来（这也是"App 没启动时插线能自动出界面"的实现方式，
 * 且因为启动由系统发起，不受 Android 10+ 后台启动 Activity 限制）。
 * 全匹配是为了让"手机普通模式设备"那次授权弹窗也能有「一律允许」勾选，
 * 但副作用是 U 盘、鼠标也会命中，需要在界面层判掉。
 *
 * ## 判定规则
 * 只有"所有接口都是存储类(8)/HID(3)"的设备才判定为非手机设备：
 *  - U 盘：接口 class 8（SDK 的 [com.baidu.carlife.sdk.receiver.transport.aoa.UsbAccessoryScanner]
 *    内部 isUsbStorageDevice 判的就是 class 8/6/80，本类保持同一标准）；
 *  - 鼠标/键盘：接口 class 3（HID）。
 * 其余（Android 手机 MTP/PTP/厂商接口、iPhone、AOA 设备……）一律放行，
 * 宁可多弹一次界面，也不能把真手机漏掉。
 *
 * 注意：SDK 侧只跳过存储类设备，HID 设备仍会走授权流程（可能弹一次系统授权框），
 * 这里只负责"要不要把 CarLife 界面拉出来"。
 */
object UsbAttachPolicy {

    private const val TAG = "UsbAttachPolicy"

    /** USB 接口类：大容量存储（与 SDK isUsbStorageDevice 的 STORAGE_INTERFACE_CLASS 一致） */
    private const val USB_CLASS_MASS_STORAGE = 8

    /** USB 接口类：HID（鼠标/键盘） */
    private const val USB_CLASS_HID = 3

    /**
     * 这次 Activity 启动是否由"非手机类 USB 设备"接入触发（U 盘、鼠标等），
     * 是则界面应直接退出。
     */
    fun shouldIgnoreAttach(intent: Intent?): Boolean {
        if (intent == null || intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            return false
        }
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return false
        val ignored = isNonPhoneDevice(device)
        if (ignored) {
            Logger.d(TAG, "ignore usb attach (not a phone): ${describe(device)}")
        }
        return ignored
    }

    /**
     * 该设备是否"非手机类"（U 盘 / HID），判定标准见类注释。
     * 对外暴露：主页判断"是否还在等 USB 授权"时要排除这类设备，见 MainFragment。
     */
    fun isNonPhoneDevice(device: UsbDevice): Boolean {
        val interfaceCount = device.interfaceCount
        // 拿不到接口信息时不做拦截，交给 SDK 判断，避免误杀手机
        if (interfaceCount <= 0) return false
        for (index in 0 until interfaceCount) {
            val interfaceClass = device.getInterface(index).interfaceClass
            if (interfaceClass != USB_CLASS_MASS_STORAGE && interfaceClass != USB_CLASS_HID) {
                return false
            }
        }
        return true
    }

    private fun describe(device: UsbDevice): String =
        "vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                "deviceClass=${device.deviceClass} interfaceCount=${device.interfaceCount} " +
                "product=${device.productName}"
}
