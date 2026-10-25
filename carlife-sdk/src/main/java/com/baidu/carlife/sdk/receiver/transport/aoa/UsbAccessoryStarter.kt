package com.baidu.carlife.sdk.receiver.transport.aoa

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger

class UsbAccessoryStarter(private val usbDevice: UsbDevice,
                          private val usbConnection: UsbDeviceConnection) {

    /**
     * 本次 openAccessory 是否触发了强制重枚举（force re-init 的 AOA_START_ACCESSORY）。
     *
     * 实测：设备**已经**处于 AOA 模式时再发 AOA_START_ACCESSORY，手机不一定真的重新枚举
     * （VID/PID 不变、系统不发 ACTION_USB_DEVICE_ATTACHED），此时 usbConnection 仍然有效。
     * 所以调用方**不能**据此放弃本次连接去等"重连事件"——那个事件可能永远不会来。
     * 这里仅用于诊断输出。
     */
    var triggeredForceReInit: Boolean = false
        private set

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

        /**
         * 两次"强制 AOA_START_ACCESSORY"（重新向 phone HAL 注册身份、唤醒 phone 端 CarLife app）
         * 之间的最小间隔。
         *
         * 实测（车机 1280x720 + 手机已在 AOA 模式）：设备已经在 AOA 模式时再发
         * AOA_START_ACCESSORY **不会**让手机重新枚举（VID/PID 不变，系统不发
         * ACTION_USB_DEVICE_ATTACHED），所以它不会造成 USB 反复断开，只是重新注册身份。
         *
         * 之所以要"允许再来一次"而不是"一个进程只做一次"：手机端 CarLife app 常常不会在
         * 第一次注册时就把 bulk 端点打开监听（app 没起来 / 锁屏 / 被别的 AOA 客户端占用）。
         * 那时车机会一直 bulkTransfer ret=-1，每 ~14s 空转重试一轮；如果整个进程只允许
         * 唤醒一次，这次没唤醒成功就再也唤不醒了，表现为"启动后隔很久才连上"甚至一直连不上。
         *
         * 用最小间隔（而不是次数）限制，既能周期性重试唤醒，又不会密集地反复注册。
         */
        private const val FORCE_REINIT_MIN_INTERVAL_MS = 12_000L

        // A/B 诊断开关（实测已验证：关掉它 bulkTransfer 照样 ret=-1，说明重注册身份不是
        // 连接失败的原因）。默认开启：能周期性把身份重新注册给 phone HAL，
        // 手机端 CarLife app 晚启动时仍有机会被唤醒。
        private const val FORCE_REINIT_ENABLED = true

        // key = vid:pid:serial，value = 上次强制注册的时间戳
        private val forceReInitTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /**
         * controlTransfer 超时（毫秒）。
         *
         * 原来是 0（无限等待）。端点 NAK / STALL 时虽有 libusb 自己的超时兜底，
         * 但在部分车机 + 手机组合上（锁屏、USB 配置没切、别的 AOA 客户端占着）
         * 这一发会**永久挂住**：调用线程再也出不来，后面的 USB 事件
         * （切 AOA 后的重枚举）全都排不上队，授权流程卡在半路 ——
         * 表现就是"点了第一个授权框之后再没动静，只能重启 App"。
         * 改成有限超时后，失败会正常返回负值交给上层重试。
         */
        private const val CONTROL_TRANSFER_TIMEOUT_MS = 3000

        fun resetForceReInitState() {
            forceReInitTimes.clear()
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
            val last = forceReInitTimes[key]
            val now = android.os.SystemClock.elapsedRealtime()
            val ago = if (last == null) -1L else now - last
            if (FORCE_REINIT_ENABLED && (last == null || ago >= FORCE_REINIT_MIN_INTERVAL_MS)) {
                val startRet = startAccessory()
                forceReInitTimes[key] = now
                triggeredForceReInit = true
                Log.w(Constants.TAG, "UsbAccessoryStarter AOA_START_ACCESSORY (force re-init, key=$key, ago=${if (ago < 0) "first" else ago.toString() + "ms"}) ret=$startRet")
            } else {
                Log.w(Constants.TAG, "UsbAccessoryStarter skip AOA_START_ACCESSORY (enabled=$FORCE_REINIT_ENABLED, ago=${ago}ms, min=${FORCE_REINIT_MIN_INTERVAL_MS}ms, key=$key)")
            }
            return true
        }

        // 还没进 AOA：GET_PROTOCOL → SEND_IDENT → START_ACCESSORY，
        // 之后手机会**重新枚举**成 AOA 设备（VID=0x18D1 / PID=0x2D0x），
        // 当前这个 usbConnection 随即失效，所以这里**必须**返回 false，
        // 让调用方（UsbAccessoryCommunicator）释放连接、等重枚举后的新设备。
        // 重枚举后的新 VID/PID 需要**第二次系统授权弹窗**，由 UsbAccessoryScanner 负责请求。
        var switched = false
        if (getProtocolVersion()) {
            if (sendIdentities()) {
                switched = startAccessory()
            }
        }
        Log.w(
            Constants.TAG,
            "UsbAccessoryStarter switch to AOA ret=$switched (waiting for re-enumeration, " +
                    "2nd permission dialog needed)"
        )
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
            // 间隔不能为 0：部分 phone AOA HAL 处理一串身份字符串不是原子操作，连发 6 串在
            // 1ms 内完成可能丢。原来隔 50ms（6 串共 300ms），实测 25ms 串间隔在车机/这台
            // 手机上都能稳定收全（SEND_IDENT 全部 ret>0），一轮省 150ms、一轮连接要发两轮。
            try { Thread.sleep(25) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
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
            CONTROL_TRANSFER_TIMEOUT_MS)
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
            CONTROL_TRANSFER_TIMEOUT_MS)
    }
}