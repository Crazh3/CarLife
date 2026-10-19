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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
         * 快速重扫间隔。
         *
         * 手机切 AOA 后会**重新枚举**，重枚举出来的是"另一台设备"（新 VID/PID），
         * 必须立刻再申请一次授权（第 2 个框）。这段时间里每一轮都用 3s 太慢：
         * 用户视角就是"点了第一个框，第二个框半天不弹"，而实际上只是还没到下一次扫描。
         * 所以在"刚刚发生过 USB 事件 / 刚做过 openAccessory"的一段时间里用 600ms 重扫，
         * 让第 2 个框在手机重枚举完成后（通常 <1s）立刻弹出来。
         */
        private const val FAST_RESCAN_INTERVAL_MS = 600L

        /**
         * 快速重扫的持续时长：覆盖"手机重新枚举 + 用户看清并点掉第 2 个授权框"的时间。
         * 超时后自动退回常规间隔，避免长期空转。
         */
        private const val FAST_RESCAN_WINDOW_MS = 30_000L

        /**
         * 一台设备两次 requestPermission 之间的最小间隔。
         *
         * 系统同时只画一个 USB 授权框，连续请求会把上一个框挤掉（车机日志里就是
         * "Duplicate finish request"：第 2 个框刚 START 就被结束掉，用户根本没机会点）。
         * AOAProtocolTransport.connect() 会被多次调用（MainFragment 每次进主页 / 服务开关打开），
         * 每次都会 scan() 重置状态，所以这里用"设备 + 时间"去重，跨调用也生效。
         */
        private const val REQUEST_MIN_INTERVAL_MS = 2500L

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
    @Volatile
    private var requestingDevice: UsbDevice? = null

    @Volatile
    private var requestingAt: Long = 0L

    /** 上一次 requestPermission 的设备与时间（用于跨调用去重，见 REQUEST_MIN_INTERVAL_MS） */
    @Volatile
    private var lastRequestedKey: String? = null

    @Volatile
    private var lastRequestedAt: Long = 0L

    /** 被用户明确拒绝过的设备（vid:pid:serial），本轮扫描不再反复骚扰；拔线后清空 */
    private val deniedDevices = HashSet<String>()

    /**
     * USB 操作专用**串行**线程。
     *
     * openAccessory 里有 controlTransfer（发 AOA 身份串 6 次、每次间隔 50ms）和
     * 3s 的"等待端点就绪"，单次耗时可以到秒级；以前这些直接跑在 BroadcastReceiver 的
     * **主线程**上，结果是：从第一个授权框点下去开始，主线程被 openAccessory 占着，
     * 后面"手机切 AOA 重枚举"的 USB 广播迟迟进不来 → 第 2 个授权框半天不弹、
     * 界面也点不动，只能重启 App。这里统一串行化到后台线程：
     *  - 用单线程：同一时刻只能有一个 USB 操作抓着设备，天然互斥、保持事件顺序；
     *  - 用后台线程：不再卡住主线程，USB 事件能及时进来。
     */
    private val serial: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "CarLife_UsbScanner").apply { isDaemon = true }
        }
    }

    /** 是否已经有扫描任务在执行或排队（防止 600ms 一轮时任务堆积） */
    private val scanPending: AtomicBoolean = AtomicBoolean(false)

    /** 快速重扫窗口截止时间（elapsedRealtime），见 FAST_RESCAN_INTERVAL_MS */
    private val fastRescanUntil: AtomicLong = AtomicLong(0L)

    private val rescanRunnable = Runnable {
        if (isStopped.get() || isAttached.get()) {
            return@Runnable
        }
        // 上一次授权请求一直没回结果（弹框被系统抢走 / 用户没点）→ 清掉重来
        val pending = requestingDevice
        val now = SystemClock.elapsedRealtime()
        if (pending != null && now - requestingAt >= PERMISSION_REQUEST_TIMEOUT_MS) {
            Logger.w(
                Constants.TAG,
                "UsbAccessoryScanner permission request timeout, retry: ", describe(pending)
            )
            requestingDevice = null
            requestFastRescan()
        }
        try {
            submitScanOnce()
        } finally {
            scheduleRescan()
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
        // 刚发起一轮连接（含首次连接），先在快速窗口里推进，别让用户干等 3s
        requestFastRescan(FAST_RESCAN_WINDOW_MS)
        // 执行一次scan，不能在主线程
        registerIfNeeded()
        // 强制排一次扫描：connect() 的语义就是"重新开始"，不能被上一次未跑完的扫描挡住
        scanPending.set(false)
        submitScanOnce()
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
            fastRescanUntil.set(0L)
        }
    }

    /**
     * 排一次 [scanOnce] 到串行线程。已有任务在执行/排队时直接跳过，
     * 避免快速重扫窗口里任务无限堆积（每次 scanOnce 本身可能耗时好几秒）。
     */
    private fun submitScanOnce() {
        if (!scanPending.compareAndSet(false, true)) {
            return
        }
        serial.execute {
            try {
                scanOnce()
            } catch (e: Exception) {
                Logger.e(Constants.TAG, "UsbAccessoryScanner scanOnce exception: ", e)
            } finally {
                scanPending.set(false)
            }
        }
    }

    /** 在一段时间内进入"快速重扫"，用于刚发生过 USB 事件、马上要接着往下走的阶段 */
    private fun requestFastRescan(windowMs: Long = FAST_RESCAN_WINDOW_MS) {
        fastRescanUntil.set(SystemClock.elapsedRealtime() + windowMs)
    }

    /**
     * 上一次申请授权的设备已经不在设备列表里了（切 AOA 重枚举会换 VID/PID/deviceId，
     * 老设备的授权结果永远不会再来），立刻放开占用，保证新设备能马上弹框。
     */
    private fun clearStaleRequestingDevice() {
        val pending = requestingDevice ?: return
        val devices = usbManager.deviceList.values
        val stillPresent = devices.any { it.deviceId == pending.deviceId }
        if (!stillPresent) {
            Logger.w(
                Constants.TAG,
                "UsbAccessoryScanner requesting device is gone (re-enumerated?), clear it: ",
                describe(pending)
            )
            requestingDevice = null
            requestFastRescan()
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
        // 之前请求过的设备可能已经重枚举没了，它的结果永远不会回来，先放开占位
        clearStaleRequestingDevice()
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
                // 这里返回 false 有两种含义：
                //  ① 刚把手机切成 AOA（等它重新枚举，之后要弹第 2 个授权框）；
                //  ② 打开失败，需要尽快重试。
                // 两种都要马上再扫一轮，否则 3s 的兜底间隔会让第二个框"看着像卡住了"。
                requestFastRescan()
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
            // 同一台设备短时间内不重复请求：系统一次只画一个授权框，连发会把上一个挤掉
            // （日志里的 "Duplicate finish request"，用户根本没机会点），见 REQUEST_MIN_INTERVAL_MS
            val deviceKey = key(device)
            val now = SystemClock.elapsedRealtime()
            if (lastRequestedKey == deviceKey && now - lastRequestedAt < REQUEST_MIN_INTERVAL_MS) {
                Logger.w(
                    Constants.TAG,
                    "UsbAccessoryScanner skip requestPermission, asked ${now - lastRequestedAt}ms ago: ",
                    describe(device)
                )
                return
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // Android 12+ 起不指定可变性会抛异常（回调里我们只读 EXTRA，不需要修改）
                        PendingIntent.FLAG_IMMUTABLE
                    } else {
                        0
                    }
            val permissionIntent = PendingIntent.getBroadcast(
                context.applicationContext,
                // 用 deviceId 做 requestCode：每台设备一个独立 PendingIntent，
                // 避免复用同一个 PI 时不同设备的授权结果互相覆盖
                device.deviceId,
                Intent(ACTION_USB_PERMISSION)
                    .setPackage(context.applicationContext.packageName),
                flags
            )
            lastRequestedKey = deviceKey
            lastRequestedAt = now
            requestingDevice = device
            requestingAt = now
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
        val fast = SystemClock.elapsedRealtime() < fastRescanUntil.get()
        context.main().postDelayed(
            rescanRunnable,
            if (fast) FAST_RESCAN_INTERVAL_MS else RESCAN_INTERVAL_MS
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        Logger.w(Constants.TAG, "UsbAccessoryScanner action with ${intent.action}, $isStopped")
        // 立刻返回，实际处理丢到串行线程：onReceive 跑在主线程，
        // 而 openAccessory 会做 controlTransfer + 秒级等待，卡在这里就等于卡住整个
        // 连接流程（切 AOA 后的重枚举事件进不来，第 2 个授权框也就弹不出来）。
        serial.execute {
            try {
                handleIntent(intent)
            } catch (e: Exception) {
                Logger.e(Constants.TAG, "UsbAccessoryScanner handle ${intent.action} exception: ", e)
            }
        }
    }

    private fun handleIntent(intent: Intent) {
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
                    // 走到这里说明这台设备没能直接连上：最常见的情况是"刚把它切成 AOA，
                    // 正在等它重新枚举"，重枚举出来是新 VID/PID、还要再弹一次授权框，
                    // 所以要马上接着扫，不能等 3s 的常规兜底间隔。
                    requestFastRescan()
                } else if (device != null) {
                    // 用户点了"取消"：记下来，本轮不再对这台设备弹框（拔线/重插后恢复）
                    deniedDevices.add(key(device))
                }
                requestPendingPermission()
                // 用户点完授权框后立刻再扫一轮：找重枚举出来的 AOA 设备 / 下一个没权限的设备
                submitScanOnce()
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
                    // 上一台设备已经没了（重枚举会换新设备），它那条永远回不来的授权结果
                    // 会占着"同一时刻只能请求一个"的位置，这里先放开
                    clearStaleRequestingDevice()
                    // 兜底：上一台设备**还**在设备列表里（极少数车机枚举滞后），它那条请求同样
                    // 已经用不上了。不放开的话 requestPendingPermission 会因为
                    // "已经有一个请求在等结果"直接 return，第二次授权框就弹不出来 ——
                    // 这正是"第一次弹窗点完之后再没动静"的根因之一。
                    requestingDevice?.let { pending ->
                        if (pending.deviceId != it.deviceId) {
                            Logger.w(
                                Constants.TAG,
                                "UsbAccessoryScanner new device attached, drop pending request of ",
                                describe(pending)
                            )
                            requestingDevice = null
                        }
                    }
                    // 有 USB 事件发生、又还没连上 → 进入快速重扫，把这条链路尽快走完
                    requestFastRescan()
                    if (usbManager.hasPermission(it)) {
                        if (openAccessory(it)) {
                            return
                        }
                    } else {
                        // 这台是**刚插入**的（手机切 AOA 后的重枚举也是"新设备"），必须优先给它弹框：
                        // 队列里可能还排着上一台已经拿不到结果的设备，poll 出来就是白费一轮请求。
                        // 清掉的那些设备下一次 scanOnce 会重新加回来，不会漏。
                        pendingPermissionDevices.clear()
                        pendingPermissionDevices.add(it)
                        requestPendingPermission()
                    }
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                // 拔线：清空"拒绝过"的记录，下次插上重新走完整授权流程
                deniedDevices.clear()
                requestingDevice = null
                lastRequestedKey = null
                isStopped.set(false)
                requestFastRescan()
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
