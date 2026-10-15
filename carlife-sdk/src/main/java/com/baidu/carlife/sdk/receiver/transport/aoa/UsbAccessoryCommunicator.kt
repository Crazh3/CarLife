package com.baidu.carlife.sdk.receiver.transport.aoa

import android.content.Context
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import com.baidu.carlife.sdk.*
import com.baidu.carlife.sdk.Constants.AOA_MESSAGE_MAX_SIZE
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_UPDATE
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.receiver.transport.aoa.async.UsbRequestPool
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.toInt
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class UsbAccessoryCommunicator(
    private val context: CarLifeContext,
    private val usbDevice: UsbDevice
) :
    Communicator {
    private val usbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null

    private val isTerminated = AtomicBoolean(false)
    private val headerBuffer = ByteArray(CarLifeMessage.HEADER_SIZE)

    companion object {
        /**
         * AOA 模式就绪后等待手机端 bulk 端点完全可用的延迟（毫秒）。
         *
         * 部分手机从 accessory 模式枚举完成到 bulk OUT/IN endpoint 可读写之间
         * 有 100~500ms 延迟；没等就绪就直接发首条协议消息时，bulkTransfer 会被
         * NAK 卡住约 libusb 默认超时（~4s），然后才返回负值错误。
         * 现象：UI 卡在 0% 4 秒后 resetDevice 循环。
         *
         * 如果手机 AOA_START_ACCESSORY 触发了 CarLife app 重新打开 bulk 端点，
         * 也需要 1~2s 让 app 从事件回调里把 endpoint 拿到手再监听；
         * 300ms 在很多 ROM 上太短。3s 是宽松值，配合下面的 bulkTransfer timeout
         * （5s 缩短到 2s）总重试周期能压在 ~5s 以内。
         */
        private const val AOA_ENDPOINT_READY_DELAY_MS = 3000L

        /**
         * bulkTransfer 调用的 libusb 超时（毫秒）。0 表示无限等（但 libusb 内部
         * 对 stall/NAK 端点有自己的默认 ~4~5s 强制超时，表现为 -1 IO）。
         *
         * 改成 2000ms 让 OUT 端点 NAK 时 2s 内就返回负值，触发上层快速重试，
         * 整个 reconnect 周期从 ~8s 压到 ~5s，单位时间内能试更多次，
         * 抓住 phone CarLife app 在中途开始监听的那个窗口。
         */
        private const val AOA_BULK_TRANSFER_TIMEOUT_MS = 2000

        /**
         * bulkTransfer 单次返回 -1/-7 等错误码时，在本层就重试 N 次（每两次间
         * 隔 500ms 给 phone app 一点时间开始监听）。重试完后才抛 IOException
         * 给 MessageDispatcher。这一层重试是"短重试"，比上层 reconnect 周期
         * 短得多；目的是抓 phone CarLife 延迟打开端点的那几百毫秒窗口。
         */
        private const val AOA_BULK_TRANSFER_RETRY_TIMES = 3
        private const val AOA_BULK_TRANSFER_RETRY_DELAY_MS = 500L
    }

    fun openAccessory(): Boolean {
        try {
            Logger.d(Constants.TAG, "UsbAccessoryCommunicator openAccessory")
            usbInterface = usbDevice.getInterface(0)
            usbConnection = usbManager.openDevice(usbDevice)
            usbConnection!!.claimInterface(usbInterface, true)
            val starterOk = UsbAccessoryStarter(usbDevice, usbConnection!!).openAccessory()
            if (!starterOk) {
                // 切换AOA模式成功之后，USB会重新连接一次，以新的VID，PID重新连接
                // 返回true的条件必须是检测处于AOA mode才行
                terminate(false)
                Logger.d(Constants.TAG, "UsbAccessoryCommunicator openAccessory switch to aoa (starter returned false)")
                return false
            }
            // 等待手机端 AOA bulk 端点就绪后再返回。
            // claimInterface 成功 ≠ 端点可写：端点 ready 比 interface claim 慢一截。
            try {
                Thread.sleep(AOA_ENDPOINT_READY_DELAY_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // 用 android.util.Log 直接打：SDK 内置 Logger 的 level 默认是 Log.WARN，D 级会丢。
            Log.w(Constants.TAG, "UsbAccessoryCommunicator openAccessory ready (after ${AOA_ENDPOINT_READY_DELAY_MS}ms wait)")
            return true
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "openAccessory exception: ", e)
            terminate()
            return false
        }
    }

    override fun write(message: CarLifeMessage) {
        Logger.v(Constants.TAG, "UsbAccessoryCommunicator write ")
        usbConnection?.let {
            val endpoint = usbInterface!!.getEndpoint(UsbRequestPool.BULK_ENDPOINT_OUT_ADDRESS)
            // 先发送消息头（带短重试，抓 phone 延迟打开端点的窗口）
            val headerTransfered = bulkTransferWithRetry(endpoint, message.header(),
                CarLifeMessage.HEADER_SIZE, "header")
            if (headerTransfered != CarLifeMessage.HEADER_SIZE) {
                // 重试用尽后还是没写出去
                throw IOException("UsbAccessoryCommunicator bulkTransfer header write failed, final ret=$headerTransfered")
            }
            // 再发送消息体（带短重试）
            val bodyTransfered = bulkTransferWithRetry(endpoint, message.body,
                message.size, "body")
            if (bodyTransfered != message.size) {
                throw IOException("UsbAccessoryCommunicatorSync bulkTransfer body failed, final ret=$bodyTransfered")
            }
            return
        }
        throw IOException("UsbAccessoryCommunicatorSync is terminated")
    }

    /**
     * 调 bulkTransfer 直到成功 / 真的写完。
     * - 返回 0（zero-length）时按原来的语义重试（NAK 当时端点没人读，等一会再发）
     * - 返回 < 0 时：先短重试 [AOA_BULK_TRANSFER_RETRY_TIMES] 次，每次之间等 [AOA_BULK_TRANSFER_RETRY_DELAY_MS]，
     *   给 phone CarLife app 时间把 bulk 端点打开监听；重试用尽后才返回最后一次的负值。
     */
    private fun bulkTransferWithRetry(endpoint: android.hardware.usb.UsbEndpoint,
                                      buffer: ByteArray, length: Int, label: String): Int {
        var transfered = 0
        while (transfered == 0) {
            transfered = usbConnection!!.bulkTransfer(endpoint, buffer, 0, length, AOA_BULK_TRANSFER_TIMEOUT_MS)
            if (transfered == 0) {
                Logger.e(Constants.TAG, "bulkTransfer write $label zero length (NAK but no error, retry immediately)")
            }
        }
        if (transfered < 0) {
            // 短重试：抓住 phone CarLife app 刚被唤醒 / 重新打开 bulk 端点的那几百 ms
            var attempt = 0
            while (attempt < AOA_BULK_TRANSFER_RETRY_TIMES) {
                try { Thread.sleep(AOA_BULK_TRANSFER_RETRY_DELAY_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                val retry = usbConnection!!.bulkTransfer(endpoint, buffer, 0, length, AOA_BULK_TRANSFER_TIMEOUT_MS)
                Log.w(Constants.TAG, "bulkTransfer write $label retry #$attempt ret=$retry (was=$transfered)")
                if (retry > 0) {
                    return retry
                }
                if (retry < 0) {
                    transfered = retry
                }
                attempt++
            }
        }
        return transfered
    }

    override fun read(): CarLifeMessage {
        usbConnection?.let {
            // 先读取8字节协议头（带短重试，给 phone CarLife app 时间把 IN 端点打开）
            val endpoint = usbInterface!!.getEndpoint(UsbRequestPool.BULK_ENDPOINT_IN_ADDRESS)
            val headerRead = bulkTransferReadWithRetry(endpoint, headerBuffer, CarLifeMessage.HEADER_SIZE, "header")
            if (headerRead != CarLifeMessage.HEADER_SIZE) {
                throw IOException("UsbAccessoryCommunicatorSync bulkTransfer header read failed, final ret=$headerRead")
            }

            val channel = headerBuffer.toInt()
            val messageSize = headerBuffer.toInt(4)
            if (channel < MSG_CHANNEL_CMD || channel > MSG_CHANNEL_UPDATE ||
                messageSize < 0 || messageSize > AOA_MESSAGE_MAX_SIZE
            ) {
                throw IOException("invalid message channel $channel or message size $messageSize")
            }

            val message = CarLifeMessage.obtain(channel, length = messageSize)
            var remaining = messageSize
            while (remaining > 0) {
                val offset = messageSize - remaining
                val bodyRead = bulkTransferReadWithRetry(endpoint, message.body, remaining, "body offset=$offset")
                if (bodyRead > 0) {
                    remaining -= bodyRead
                } else {
                    if (bodyRead < 0) {
                        message.recycle()
                        throw IOException("UsbAccessoryCommunicator bulkTransfer body read failed, final ret=$bodyRead")
                    }
                }
            }
            return message
        }
        throw IOException("UsbAccessoryCommunicator read from terminated communicator")
    }

    /**
     * read 端 bulkTransfer 短重试：
     * - 返回 0（NAK 没数据）继续读（保持原语义）
     * - 返回 < 0：短重试 [AOA_BULK_TRANSFER_RETRY_TIMES] 次，每次间 [AOA_BULK_TRANSFER_RETRY_DELAY_MS]，
     *   抓 phone CarLife app 把 IN bulk 端点打开监听的那几百 ms 窗口
     * - 重试用尽后返回最后一次结果（一般是负值），让 caller 决定是否 throw
     */
    private fun bulkTransferReadWithRetry(endpoint: android.hardware.usb.UsbEndpoint,
                                          buffer: ByteArray, length: Int, label: String): Int {
        var transfered = 0
        while (transfered == 0) {
            transfered = usbConnection!!.bulkTransfer(endpoint, buffer, 0, length, AOA_BULK_TRANSFER_TIMEOUT_MS)
            if (transfered == 0) {
                Logger.e(Constants.TAG, "bulkTransfer read $label zero length (NAK, retry immediately)")
            }
        }
        if (transfered < 0) {
            var attempt = 0
            while (attempt < AOA_BULK_TRANSFER_RETRY_TIMES) {
                try { Thread.sleep(AOA_BULK_TRANSFER_RETRY_DELAY_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                val retry = usbConnection!!.bulkTransfer(endpoint, buffer, 0, length, AOA_BULK_TRANSFER_TIMEOUT_MS)
                Log.w(Constants.TAG, "bulkTransfer read $label retry #$attempt ret=$retry (was=$transfered)")
                if (retry > 0) {
                    return retry
                }
                if (retry < 0) {
                    transfered = retry
                }
                attempt++
            }
        }
        return transfered
    }

    override fun terminate() {
        terminate(true)
    }

    fun terminate(resetUsbDevice: Boolean) {
        if (!isTerminated.getAndSet(true)) {
            Logger.d(Constants.TAG, "UsbAccessoryCommunicator terminate")
            usbConnection?.releaseInterface(usbInterface)
            if (resetUsbDevice) {
                resetUsbDevice()
            }
            usbConnection?.close()
            usbConnection = null
        }
    }

    private fun resetUsbDevice() {
        try {
            val resetDevice = UsbDeviceConnection::class.java.getMethod("resetDevice")
            val result = resetDevice.invoke(usbConnection) //调用借钱方法，得到返回值
            Logger.d(Constants.TAG, "USB Device resetDevice result: $result")
        } catch (e: Exception) {
            e.printStackTrace()
            Logger.e(Constants.TAG, "USB Device resetDevice error", e)
        }
    }
}