package com.baidu.carlife.sdk.receiver.transport.aoa

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger

class UsbAccessoryStarter(private val usbDevice: UsbDevice,
                          private val usbConnection: UsbDeviceConnection) {
    companion object {
        const val VID_ACCESSORY = 0x18D1

        const val PID_ACCESSORY_ONLY = 0x2D00
        const val PID_ACCESSORY_ADB = 0x2D01
        const val PID_ACCESSORY_AUDIO = 0x2D02
        const val PID_ACCESSORY_AUDIO_ADB = 0x2D03
        const val PID_ACCESSORY_AUDIO_BULK = 0x2D04
        const val PID_ACCESSORY_AUDIO_ADB_BULK = 0x2D05

        const val AOA_GET_PROTOCOL = 51
        const val AOA_SEND_IDENT = 52
        const val AOA_START_ACCESSORY = 53
        const val AOA_AUDIO_SUPPORT = 58

        const val AOA_MANUFACTURER = "Baidu"
        const val AOA_MODEL_NAME = "CarLife"
        const val AOA_DESCRIPTION = "Baidu CarLife"
        const val AOA_VERSION = "1.0.0"
        const val AOA_URI = "http://carlife.baidu.com/"
        const val AOA_SERIAL_NUMBER = "0720SerialNo."

        // 已经走"强制 AOA_START_ACCESSORY"路径的设备（用 vid:pid:serial 唯一标识）。
        // AOA_START_ACCESSORY 会让手机重新枚举 → 同一台物理设备会被 scanner 当成新 UsbDevice
        // 再走一次 openAccessory；如果不记下来，第二次又调一次 AOA_START_ACCESSORY 就会无限循环。
        // 进程重启会清掉这个集合（Class 静态字段），相当于"每次 App 启动最多重初始化一次"。
        private val forceReInitDevices = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

        fun resetForceReInitState() {
            forceReInitDevices.clear()
        }
    }
    /**
     * 让手机切到 AOA accessory 模式（如果还没在）。
     *
     * 这里要分两种情况：
     * 1) 设备**已经**在 AOA 模式：什么都不发直接 return true 是错的。
     *    设备进入 AOA 模式可能是被**别的 host / app** 触发的（Android Auto、上一台车机、
     *    别的 AOA 客户端、或者上一次会话残留），那时 phone 端 AOA HAL 缓存的 host 身份
     *    不是 Baidu CarLife，phone 端 CarLife app 就**不会启动 AOA bulk 端点监听**。
     *    结果就是车机 SDK 第一条协议消息写出去 6s 内 OUT 端点持续 NAK，
     *    最后 libusb 报 -1 (LIBUSB_ERROR_IO)。
     *    修法：补一次 AOA_SEND_IDENT 把身份字符串重新发给 phone HAL，让它通知 CarLife app。
     *    **不**调 AOA_START_ACCESSORY，避免触发手机重新枚举。
     *
     * 2) 设备**没在** AOA 模式：按 AOA 协议走 GET_PROTOCOL → SEND_IDENT → START_ACCESSORY。
     *
     * @return true 表示手机 AOA 端点应当已被配置为可读/可写（car life app 应当已收到通知）。
     *         false 表示配置失败，调用方应放弃这次连接。
     */
    fun openAccessory(): Boolean {
        if (isAccessory()) {
            // 直接走 android.util.Log：SDK 内置 Logger 的 level 默认是 Log.WARN，
            // 任何 D 级日志（包括我们诊断需要的）都会被吞。改用 W 级确保可见。
            Log.w(Constants.TAG, "UsbAccessoryStarter already in AOA mode (vid=0x${Integer.toHexString(usbDevice.vendorId)}, pid=0x${Integer.toHexString(usbDevice.productId)}), re-sending identity strings to register with phone CarLife")
            val ok = sendIdentities()
            Log.w(Constants.TAG, "UsbAccessoryStarter sendIdentities (already-AOA path) = $ok")
            if (!ok) {
                return false
            }
            // 即使 sendIdentities 成功，也尝试一次"强制 AOA_START_ACCESSORY"让手机重新枚举，
            // 触发 phone 端 AOA HAL 把"host 是 Baidu CarLife"事件重新通知给 CarLife app，
            // 促使它打开 AOA bulk 端点监听（上一台车机 / 别的 AOA 客户端残留状态会被清掉）。
            //
            // AOA_START_ACCESSORY 会让手机重新枚举：当前 UsbDeviceConnection 随即失效，
            // 后续 bulkTransfer 会失败 → UsbAccessoryCommunicator.terminate(false) 释放接口，
            // scanner 看到"新设备"（同 vid:pid:serial 重新枚举）会重新走 openAccessory。
            //
            // 防无限循环：同一台物理设备在一次 App 进程内只调一次 AOA_START_ACCESSORY，
            // 用 vid:pid:serial 作 key 记录到 forceReInitDevices；进程重启自动清空。
            val key = "${usbDevice.vendorId}:${usbDevice.productId}:${usbDevice.serialNumber}"
            if (key !in forceReInitDevices) {
                val startRet = startAccessory()
                forceReInitDevices.add(key)
                Log.w(Constants.TAG, "UsbAccessoryStarter AOA_START_ACCESSORY (force re-init, key=$key) ret=$startRet")
            } else {
                Log.w(Constants.TAG, "UsbAccessoryStarter skip AOA_START_ACCESSORY (already force-re-init'd this session, key=$key)")
            }
            return true
        }

        if (getProtocolVersion()) {
            if (sendIdentities()) {
                startAccessory()
            }
        }
        return false

    }

    private fun isAccessory(): Boolean {
        return usbDevice.vendorId == VID_ACCESSORY
                && usbDevice.productId >= PID_ACCESSORY_ONLY
                && usbDevice.productId <= PID_ACCESSORY_AUDIO_ADB_BULK
    }

    private fun getProtocolVersion(): Boolean {
        val buffer = ByteArray(2)
        val ret = controlTransferIn(AOA_GET_PROTOCOL, 0, 0, buffer)
        Log.w(Constants.TAG, "UsbAccessoryStarter AOA_GET_PROTOCOL ret=$ret (version=${if (buffer.size >= 2) ((buffer[1].toInt() and 0xff) shl 8) or (buffer[0].toInt() and 0xff) else "n/a"})")
        return ret >= 0
    }

    private fun sendIdentities(): Boolean {
        val pairs = arrayOf(
            0 to AOA_MANUFACTURER,
            1 to AOA_MODEL_NAME,
            2 to AOA_DESCRIPTION,
            3 to AOA_VERSION,
            4 to AOA_URI,
            5 to AOA_SERIAL_NUMBER
        )
        for ((index, value) in pairs) {
            val bytes = value.toByteArray()
            val ret = controlTransferOut(AOA_SEND_IDENT, 0, index, bytes)
            Log.w(Constants.TAG, "UsbAccessoryStarter AOA_SEND_IDENT index=$index value='$value' ret=$ret")
            if (ret < 0) {
                return false
            }
            // 50ms 间隔：部分 phone AOA HAL 处理一串身份字符串不是原子操作，连发 6 串在 1ms 内
            // 完成可能丢；隔 50ms 让 HAL 把每一串单独通知上层 app。
            try { Thread.sleep(50) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
        return true
    }

    private fun startAccessory(): Boolean {
        val ret = controlTransferOut(AOA_START_ACCESSORY, 0, 0)
        Log.w(Constants.TAG, "UsbAccessoryStarter AOA_START_ACCESSORY ret=$ret")
        return ret >= 0
    }

    private fun controlTransferIn(request: Int, value: Int, index: Int,
                                  buffer: ByteArray? = null,
                                  length: Int = buffer?.size ?: 0): Int {
        return usbConnection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR,
            request,
            value,
            index,
            buffer,
            length,
            0)
    }

    private fun controlTransferOut(request: Int, value: Int, index: Int,
                                   buffer: ByteArray? = null,
                                   length: Int = buffer?.size ?: 0): Int {
        return usbConnection.controlTransfer(
            UsbConstants.USB_DIR_OUT or UsbConstants.USB_TYPE_VENDOR,
            request,
            value,
            index,
            buffer,
            length,
            0)
    }
}