package com.baidu.carlifevehicle.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.util.Logger

/**
 * 连接方式偏好：
 * 1. 连接方式：有线(USB) / WIFI(热点) / 直连(WLAN P2P)
 *    复用 PreferenceUtil 的持久化，与 VehicleApplication 启动时读取的
 *    CONNECT_TYPE_SHARED_PREFERENCES 一致。
 * 2. 记住授权（仅 USB）：控制 USB 匹配组件的启用状态，从而同时决定两件事：
 *    ① 系统授权弹窗里有没有「一律允许」勾选；② 插线时系统会不会自动拉起 CarLife。
 *
 *    ## 原理（为什么是"启用/禁用组件"）
 *    弹窗本身由系统绘制，应用能影响它的只有一件事：本应用是不是"当前插入设备的默认处理者"。
 *    系统的判断依据 = AndroidManifest 里有没有声明
 *    「USB_DEVICE_ATTACHED + device_filter 且匹配该设备」的组件。
 *      - 有匹配 → 弹窗带「一律允许」勾选，勾一次后系统记住该设备授权，之后插入不再弹窗；
 *                 同时插线时系统会直接启动匹配到的 Activity（app 没启动也能拉起界面）；
 *      - 无匹配 → 弹窗没有勾选，本次授权只在本次连接有效，每次插入都要重新授权；
 *                 插线也不会拉起本应用（需要用户手动打开 CarLife）。
 *    所以这里把"匹配组件"的启用/禁用与设置项绑定，实现"记住/不记住"。
 *
 *    注意 filter 必须是**全匹配**（@xml/device_filter 用 <usb-device />）：
 *    CarLife 第一次授权弹窗针对的是"手机普通模式设备"（VID 是手机厂商的），
 *    只匹配 AOA(0x18D1) 会导致第一次弹窗永远没有勾选。
 */
object ConnectTypePrefs {

    private const val TAG = "ConnectTypePrefs"

    /**
     * 与 AndroidManifest 中的 activity-alias 对应：指向 CarlifeActivity，
     * 既是"插线自动拉起界面"的入口（系统发起的启动，不受后台启动限制），
     * 也是系统 canBeDefault 判定所依赖的那个 activity 匹配项。
     */
    private const val USB_ALIAS_CLASS = "com.baidu.carlifevehicle.UsbAutoLaunchAlias"

    /** 与 AndroidManifest 中的空实现 Receiver 对应（receiver 侧的匹配声明） */
    private const val USB_MATCH_RECEIVER_CLASS =
        "com.baidu.carlifevehicle.usb.UsbDeviceMatchReceiver"

    /**
     * 所有会让系统认为"本应用是该 USB 设备默认处理者"的组件，必须**同开同关**：
     * 只要还留着一个启用中的匹配组件，授权弹窗就会继续带「一律允许」勾选，
     * "记住授权 = 关" 就不生效。
     * 带 device_filter 的 Activity 只能有一个（多个匹配会让系统走"默认应用/选择框"分支，
     * 插线不再自动拉起界面），所以这里只有 alias 一个 Activity 载体。
     */
    private val USB_MATCH_COMPONENTS = arrayOf(USB_ALIAS_CLASS, USB_MATCH_RECEIVER_CLASS)

    /** 中文显示名 */
    fun typeName(type: Int): String = when (type) {
        CarLifeContext.CONNECTION_TYPE_HOTSPOT -> "WIFI"
        CarLifeContext.CONNECTION_TYPE_WIFIDIRECT -> "直连"
        else -> "USB"
    }

    fun currentType(): Int =
        PreferenceUtil.getInstance()
            .getInt(CommonParams.CONNECT_TYPE_SHARED_PREFERENCES, CarLifeContext.CONNECTION_TYPE_AOA)

    @Suppress("UNUSED_PARAMETER") // context 暂未使用（沿用 API 形态便于 SettingsActivity 调用）
    fun typeName(context: Context): String = typeName(currentType())

    /**
     * 切换连接方式：持久化 + 立即通知 SDK 重建传输通道。
     */
    @Suppress("UNUSED_PARAMETER") // context 暂未使用（沿用 API 形态便于 SettingsActivity 调用）
    fun setType(context: Context, type: Int) {
        if (type == currentType()) {
            return
        }
        PreferenceUtil.getInstance()
            .putInt(CommonParams.CONNECT_TYPE_SHARED_PREFERENCES, type)
        Logger.d(TAG, "setType $type")
        try {
            CarLife.receiver().setConnectType(type)
        } catch (e: Exception) {
            // VehicleApplication 还未 init 时（理论不会发生），忽略
            Logger.e(TAG, "setConnectType failed: ", e)
        }
    }

    /**
     * 是否记住USB授权，**默认关闭**（首次安装 / 清除应用数据后为关）。
     *
     * 关 → 系统授权弹窗不带「一律允许」勾选，每次插入都要先授权一次；
     *      同时插线不会自动拉起 CarLife（需要手动打开本应用）。
     * 开 → 弹窗带勾选，勾一次以后系统记住该设备，再次插入不再弹窗，
     *      且插线（App 未启动也一样）系统会直接拉起 CarLife。
     *
     * 注意：默认值只在首次安装（或清除应用数据）时生效，之后读到的是用户上次的选择；
     * 清单里两个 USB 匹配组件也声明了 android:enabled="false"，因此首次启动前同样是"不记住"。
     */
    fun isRememberGrant(context: Context): Boolean =
        context.getSharedPreferences("vehicle_settings", Context.MODE_PRIVATE)
            .getBoolean("usb_grant_remember", false)

    fun setRememberGrant(context: Context, enabled: Boolean) {
        context.getSharedPreferences("vehicle_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("usb_grant_remember", enabled).apply()
        Logger.d(TAG, "setRememberGrant $enabled")
        applyRememberGrant(context, enabled)
    }

    /**
     * 把「记住授权」落到系统能识别的地方：启用/禁用所有 USB 匹配组件。
     *
     * 开 → 组件启用 → 系统认定本应用是该设备默认处理者 → 弹窗有「一律允许」，可记住授权；
     *                同时插线（app 未启动也一样）系统会直接拉起 CarlifeActivity。
     * 关 → 组件禁用 → 没有任何匹配 → 弹窗没有勾选，每次插入都要重新授权；
     *                插线也不会拉起本应用。
     *
     * setComponentEnabledSetting 会让系统重新解析本应用的组件（等价 PACKAGE_CHANGED），
     * packages/usb 服务会重算设备的 handler 列表，因此改完立即生效、无需重启应用。
     *
     * 注意（系统限制）：这里只控制"弹窗给不给勾选"。
     * 如果用户之前在"开"的状态下已经勾过「一律允许」，该授权已被系统持久化
     * （/data/system/users/0/usb_permissions.xml，AOSP 只在应用被卸载时才清除），
     * 之后把开关改成"关"并不会撤销这台手机已有的记住授权 —— 要换一台没勾过的手机，
     * 或卸载重装本应用（系统会连同该包的 USB 默认授权一起清掉）才看得到区别。
     */
    fun applyRememberGrant(context: Context, enabled: Boolean) {
        val packageManager = context.packageManager
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        for (className in USB_MATCH_COMPONENTS) {
            val component = ComponentName(context.packageName, className)
            if (packageManager.getComponentEnabledSetting(component) == state) {
                Logger.d(TAG, "applyRememberGrant $className already ${stateName(state)}")
                continue
            }
            try {
                packageManager.setComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP
                )
                Logger.d(
                    TAG,
                    "applyRememberGrant $className -> ${stateName(state)}, " +
                            "now=${packageManager.getComponentEnabledSetting(component)}"
                )
            } catch (e: Exception) {
                Logger.e(TAG, "applyRememberGrant $className failed: ", e)
            }
        }
    }

    private fun stateName(state: Int): String = when (state) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "ENABLED"
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "DISABLED"
        else -> "state($state)"
    }
}