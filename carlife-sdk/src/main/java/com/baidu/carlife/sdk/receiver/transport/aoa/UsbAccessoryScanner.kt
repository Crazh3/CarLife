package com.baidu.carlife.sdk.receiver.transport.aoa

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.transport.communicator.Communicator
import com.baidu.carlife.sdk.receiver.transport.aoa.async.UsbAccessoryCommunicatorAsync
import com.baidu.carlife.sdk.util.Logger
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB 设备扫描 + 授权请求。
 *
 * ## 首次连接（用户视角"要弹两次授权框"）的完整链路
 * 1) 手机以**普通模式**（MTP/PTP，VID=手机厂商）插入 → 本类发现它没权限 →
 *    requestPermission → 系统弹**第 1 个授权框**；
 * 2) 用户同意 → openAccessory → [UsbAccessoryStarter] 走
 *    AOA_GET_PROTOCOL → AOA_SEND_IDENT → AOA_START_ACCESSORY →
 *    **手机重新枚举为 AOA 设备（VID=0x18D1 / PID=0x2D0x）**，此时旧的
 *    UsbDeviceConnection 失效，openAccessory 返回 false（"等重枚举"）；
 * 3) 重枚举后是**另一个 VID/PID**，系统里没有它的授权记录 →
 *    必须再弹**第 2 个授权框**，拿到权限后才能 claimInterface + bulkTransfer。
 *
 * 也就是说"首次连接弹两次授权框"是 AOA 协议的正常行为，不是 bug。
 *
 * ## 实测踩过的两个坑（本类针对性地加了兜底）
 *
 * ### 坑 1：第 2 个授权框会被系统的 UsbResolverActivity 顶掉
 * 车机日志（2026-10-18 11:35:55）里，第 2 个 UsbPermissionActivity 刚 START，
 * 同一秒系统又 START 了 UsbResolverActivity（有多个 Activity 匹配该 USB 设备时的
 * "选择应用"框），随后两个系统 Activity 都被 "Duplicate finish request" 结束
 * —— **用户根本没机会点第 2 个授权框**，结果 App 一直卡在 0%。
 *
 * 对策：**兜底重扫**（见 [RESCAN_INTERVAL_MS]）——只要还没拿到可用 communicator，
 * 就周期性重扫设备列表；发现"没权限的设备"就重新 requestPermission，
 * 被顶掉的授权框会再弹出来，用户点一次即可继续。
 *
 * ### 坑 2：重复注册 receiver / 重复请求授权
 * 以前 [scan] 每次都 registerReceiver 且不判重，重连一次就多注册一个，
 * 同一个 ACTION_USB_DEVICE_ATTACHED 会被处理 N 遍 → 对同一台设备连发 N 次
 * requestPermission → 系统弹框被后来的请求挤掉（同样是 "Duplicate finish request"）。
 * 对策：[registered] 判重，且同一时刻只允许一个"未返回结果"的授权请求（[requestingDevice]）。
 */
class UsbAccessoryScanner(
    private val context: CarLifeContext,
    private val callback: (Communicator) -> Unit
) : BroadcastReceiver(), Runnable {
    companion object {
        private const val ACTION_USB_PERMISSION = "com.baidu.carlifevehicle.connect.USB_PERMISSION"

        const val VID_IPHONE = 0x05AC
        const val STORAGE_INTERFACE_CONUT = 1
        const val STORAGE_INTERFACE_ID = 0
        const val STORAGE_INTERFACE_CLASS = 8
        const val STORAGE_INTERFACE_SUBCLASS = 6
        const val STORAGE_INTERFACE_PROTOCOL = 80

        /**
         * 兜底重扫间隔：还没连上时每隔这么久重扫一次设备列表。
         *
         * 目的有两个：① 授权框被系统顶掉/用户没点到时，再给一次弹框机会；
         * ② 手机切 AOA 后万一系统没发 ACTION_USB_DEVICE_ATTACHED，也能靠重扫发现新设备。
         *
         * 3s：比 UsbAccessoryCommunicator 里"端点就绪"等待（3s）略长，
         * 避免和正在进行的 openAccessory 抢设备。
         */
        private const val RESCAN_INTERVAL_MS = 3000L

        /**
         * 一次授权请求等多久算"没结果"（系统弹框被抢走、用户没点）。
         * 超时后重新请求，保证首次连接一定能走完。
         */
        private const val PERMISSION_REQUEST_TIMEOUT_MS = 8000L
    }

    private val usbManager =
        context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val pendingPermissionDevices = LinkedBlockingQueue<UsbDevice>()

    private var isStopped: AtomicBoolean = AtomicBoolean(false)

    /** 是否已经拿到可用 communicator（拿到后不再重扫、不再弹授权框） */
    private val isAttached: AtomicBoolean = AtomicBoolean(false)

    /** receiver 是否已注册（防止重复注册导致事件被处理多遍） */
    private val registered: AtomicBoolean = AtomicBoolean(false)

    /** 当前正在等结果的授权请求（同一时刻只允许一个） */
    private var requestingDevice: UsbDevice? = null
    private var requestingAt: Long = 0L

    /** 被用户明确拒绝过的设备（vid:pid:serial），本轮扫描不再反复骚扰；拔线后清空 */
    private val deniedDevices = HashSet<String>()

    private val rescanRunnable = Runnable {
        if (isStopped.get() || isAttached.get()) {
            return@Runnable
        }
        // 上一次授权请求一直没回结果（弹框被系统抢走 / 用户没点）→ 清掉重来
        val pending = requestingDevice
        if (pending != null &&
            SystemClock.elapsedRealtime() - requestingAt >= PERMISSION_REQUEST_TIMEOUT_MS
        ) {
            Logger.w(
                Constants.TAG,
                "UsbAccessoryScanner permission request timeout, retry: ", describe(pending)
            )
            requestingDevice = null
        }
        context.io().execute {
            try {
                scanOnce()
            } finally {
                scheduleRescan()
            }
        }
    }

    private val useAsyncMode: Boolean by lazy {
        context.getConfig(
            Configs.CONFIG_USE_ASYNC_USB_MODE,
            false
        )
    }

    private val filter by lazy {
        IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
    }


    fun scan() {
        isStopped.set(false)
        isAttached.set(false)
        pendingPermissionDevices.clear()
        deniedDevices.clear()
        requestingDevice = null
        // 执行一次scan，不能在主线程
        registerIfNeeded()
        context.io().execute(this)
        scheduleRescan()
    }

    fun ready() {
        isStopped.set(false)
    }

    fun stop() {
        if (!isStopped.getAndSet(true)) {
            unregisterIfNeeded()
            context.main().removeCallbacks(this)
            context.main().removeCallbacks(rescanRunnable)
            pendingPermissionDevices.clear()
            requestingDevice = null
            deniedDevices.clear()
        }
    }

    private fun registerIfNeeded() {
        if (registered.compareAndSet(false, true)) {
            context.applicationContext.registerReceiver(this, filter)
        }
    }

    private fun unregisterIfNeeded() {
        if (registered.compareAndSet(true, false)) {
            try {
                context.applicationContext.unregisterReceiver(this)
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
    }

    private fun isUsbStorageDevice(device: UsbDevice?): Boolean {
        if (device == null) {
            Logger.d(Constants.TAG, "this device is null")
            return false
        }

        if (STORAGE_INTERFACE_CONUT == device.interfaceCount) {
            val usbInter = device.getInterface(STORAGE_INTERFACE_ID)
            if (STORAGE_INTERFACE_CLASS == usbInter.interfaceClass
                && STORAGE_INTERFACE_SUBCLASS == usbInter.interfaceSubclass
                && STORAGE_INTERFACE_PROTOCOL == usbInter.interfaceProtocol
            ) {
                Logger.d(Constants.TAG, "this device is mass storage 2")
                return true
            }
        }
        return false
    }


    override fun run() {
        scanOnce()
    }

    /** 扫一遍当前设备列表：有权限的直接开，没权限的排队等授权。 */
    private fun scanOnce() {
        if (isStopped.get() || isAttached.get()) {
            return
        }
        pendingPermissionDevices.clear()
        Logger.w(Constants.TAG, "UsbAccessoryScanner scan deviceList=", usbManager.deviceList.values)
        for (device in usbManager.deviceList.values) {
            if (isUsbStorageDevice(device)) {
                continue
            }

            val hasPermission = usbManager.hasPermission(device)
            Logger.w(
                Constants.TAG,
                "UsbAccessoryScanner deal with ",
                describe(device),
                " has permission=",
                hasPermission
            )
            if (hasPermission) {
                if (openAccessory(device)) {
                    // 成功打开usb accessory，清空权限列表，返回设备
                    pendingPermissionDevices.clear()
                    return
                }
            } else if (device.vendorId != VID_IPHONE && !deniedDevices.contains(key(device))) {
                // 过滤掉iPhone；被用户拒绝过的设备本轮不再请求（拔线后自动恢复）
                if (!pendingPermissionDevices.contains(device)) {
                    pendingPermissionDevices.add(device)
                }
            }
        }
        requestPendingPermission()
    }

    private fun requestPendingPermission() {
        // 已经有一个请求在等结果了（用户还没点弹框），先别再弹
        if (requestingDevice != null) {
            return
        }
        // 请求USB权限，一个一个请求，直到找到AOA设备
        pendingPermissionDevices.poll()?.let { device ->
            val permissionIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                // 用 deviceId 做 requestCode：每台设备一个独立 PendingIntent，
                // 避免复用同一个 PI 时不同设备的授权结果互相覆盖
                device.deviceId,
                Intent(ACTION_USB_PERMISSION)
                    .setPackage(context.applicationContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT
            )
            requestingDevice = device
            requestingAt = SystemClock.elapsedRealtime()
            Logger.w(Constants.TAG, "UsbAccessoryScanner requestPermission ", describe(device))
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun openAccessory(device: UsbDevice): Boolean {
        Logger.w(Constants.TAG, "UsbAccessoryScanner start openAccessory isStopped: ", isStopped)
        if (!isStopped.get()) {
            if (!useAsyncMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                // 高版本优先使用同步版本
                val communicator = UsbAccessoryCommunicator(context, device)
                if (communicator.openAccessory()) {
                    Logger.w(Constants.TAG, "UsbAccessoryScanner1 openAccessory success")
                    isAttached.set(true)
                    callback(communicator)
                    return true
                }
            } else {
                val communicator = UsbAccessoryCommunicatorAsync(context, device)
                if (communicator.openAccessory()) {
                    Logger.w(Constants.TAG, "UsbAccessoryScanner2 openAccessory success")
                    isAttached.set(true)
                    callback(communicator)
                    return true
                }
            }
        }
        Logger.w(Constants.TAG, "UsbAccessoryScanner openAccessory failed")
        return false
    }

    private fun scheduleRescan() {
        context.main().removeCallbacks(rescanRunnable)
        if (isStopped.get() || isAttached.get()) {
            return
        }
        context.main().postDelayed(rescanRunnable, RESCAN_INTERVAL_MS)
    }

    override fun onReceive(context: Context, intent: Intent) {
        Logger.w(Constants.TAG, "UsbAccessoryScanner action with ${intent.action}, $isStopped")
        if (isStopped.get()) {
            // 如果暂停扫描了，不在处理usb事件
            return
        }

        when (intent.action) {
            ACTION_USB_PERMISSION -> {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Logger.w(
                    Constants.TAG,
                    "UsbAccessoryScanner permission result ",
                    describe(device),
                    " granted=",
                    granted
                )
                requestingDevice = null
                if (granted && device != null) {
                    if (openAccessory(device)) {
                        // 成功打开usb accessory，清空权限列表，返回设备
                        pendingPermissionDevices.clear()
                        return
                    }
                } else if (device != null) {
                    // 用户点了"取消"：记下来，本轮不再对这台设备弹框（拔线/重插后恢复）
                    deniedDevices.add(key(device))
                }
                requestPendingPermission()
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)?.let {
                    if (isUsbStorageDevice(it)) {
                        return
                    }

                    Logger.w(
                        Constants.TAG,
                        "UsbAccessoryScanner ACTION_USB_DEVICE_ATTACHED with ",
                        describe(it)
                    )
                    // 重新枚举出来的设备（如切 AOA 后的新 VID/PID）算新设备，
                    // 之前"拒绝过"的记录清掉，保证首次连接一定能再弹一次
                    deniedDevices.remove(key(it))
                    if (usbManager.hasPermission(it)) {
                        if (openAccessory(it)) {
                            return
                        }
                    } else {
                        if (!pendingPermissionDevices.contains(it)) {
                            pendingPermissionDevices.add(it)
                        }
                        requestPendingPermission()
                    }
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                // 拔线：清空"拒绝过"的记录，下次插上重新走完整授权流程
                deniedDevices.clear()
                requestingDevice = null
                isStopped.set(false)
            }
        }
    }

    private fun key(device: UsbDevice?): String =
        if (device == null) "null" else "${device.vendorId}:${device.productId}:${device.serialNumber}"

    private fun describe(device: UsbDevice?): String =
        if (device == null) {
            "null"
        } else {
            "vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)} " +
                    "devId=${device.deviceId} interfaceCount=${device.interfaceCount} " +
                    "name=${device.productName}"
        }
}
