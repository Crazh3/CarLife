package com.baidu.carlifevehicle.util

import android.Manifest
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.content.ContextCompat
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R
import com.permissionx.guolindev.PermissionX

/**
 * 「连接方式」弹窗：USB / WIFI / 直连 三选一 + 两个 USB 勾选：「USB拉起APP」「记住授权」。
 *
 * - 两个勾选项都只在选中 USB 时显示（WIFI / 直连没有插线这个概念），布局见
 *   [R.layout.dialog_connect_type]。
 * - 点选只改界面状态，点「确定」时才统一落盘（见 [ConnectTypePrefs]）。
 * - 直连(WLAN P2P) 需要定位权限，权限通过后才真正切换。
 * - USB拉起APP：插线时系统是否直接拉起 CarLife 界面（App 未启动也一样）。
 * - 记住授权：USB 授权弹窗是否给出「一律允许」勾选（勾一次以后插入不再弹窗）。
 * - 超时时间：连接超时秒数，默认 10 秒，输入框 + 两侧 +/- 按钮调整；与连接方式无关
 *   （三种方式共用一个值），见 [ConnectTypePrefs.CONNECT_TIMEOUT_DEFAULT_SECONDS]。
 *
 * 两者在系统层有耦合（activity-alias 是 canBeDefault 主载体，开启"USB拉起APP"
 * 后授权弹窗也会带勾选），详细说明见 [ConnectTypePrefs] 中 USB_ALIAS_CLASS 的注释。
 */
object ConnectTypeDialog {

    private const val TAG = "ConnectTypeDialog"

    /** 置灰时的透明度（Material 规范的 disabled alpha） */
    private const val DISABLED_ALPHA = 0.38f

    /** 弹窗内容区最大高度占屏幕高度的比例，超过就限高并改为内部滚动 */
    private const val DIALOG_MAX_HEIGHT_RATIO = 0.7f

    /**
     * 显示弹窗。
     *
     * @param onChanged 连接方式 / 记住授权 任一发生变化后回调（用于刷新设置页列表）
     */
    fun show(activity: AppCompatActivity, onChanged: Runnable) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_connect_type, null, false)
        // 外面包一层 ScrollView：AlertDialog 的自定义 view 自己是不会滚动的，内容超出屏幕时
        // 会被窗口直接裁掉 —— 底部的项（USB 模式下项最多）既看不见也滑不到。
        val scrollView = ScrollView(activity).apply { addView(view) }
        val rgType = view.findViewById<RadioGroup>(R.id.rg_connect_type)
        val rbUsb = view.findViewById<RadioButton>(R.id.rb_usb)
        val rbWifi = view.findViewById<RadioButton>(R.id.rb_wifi)
        val rbDirect = view.findViewById<RadioButton>(R.id.rb_direct)
        val llUsbAutoLaunch = view.findViewById<LinearLayout>(R.id.ll_usb_auto_launch)
        val cbUsbAutoLaunch = view.findViewById<AppCompatCheckBox>(R.id.cb_usb_auto_launch)
        val vUsbOptionsDivider = view.findViewById<View>(R.id.v_usb_options_divider)
        val llRemember = view.findViewById<LinearLayout>(R.id.ll_remember_grant)
        val cbRemember = view.findViewById<AppCompatCheckBox>(R.id.cb_remember_grant)
        val btnTimeoutMinus = view.findViewById<Button>(R.id.btn_timeout_minus)
        val btnTimeoutPlus = view.findViewById<Button>(R.id.btn_timeout_plus)
        val etTimeout = view.findViewById<EditText>(R.id.et_timeout)

        val currentType = ConnectTypePrefs.currentType()
        val currentUsbAutoLaunch = ConnectTypePrefs.isUsbAutoLaunch(activity)
        val currentRemember = ConnectTypePrefs.isRememberGrant(activity)
        val currentTimeout = ConnectTypePrefs.connectTimeoutSeconds(activity)
        when (currentType) {
            CarLifeContext.CONNECTION_TYPE_HOTSPOT -> rbWifi.isChecked = true
            CarLifeContext.CONNECTION_TYPE_WIFIDIRECT -> rbDirect.isChecked = true
            else -> rbUsb.isChecked = true
        }
        cbRemember.isChecked = currentRemember
        cbUsbAutoLaunch.isChecked = currentUsbAutoLaunch
        setTimeoutValue(etTimeout, currentTimeout)
        updateUsbOptionsVisibility(rbUsb, vUsbOptionsDivider, llUsbAutoLaunch, llRemember)
        updateUsbLaunchEnabled(cbRemember, cbUsbAutoLaunch)

        // 超时时间：两侧按钮以输入框当前值为基准 ±1（结果会限制在合法区间）
        btnTimeoutMinus.setOnClickListener {
            setTimeoutValue(etTimeout, readTimeoutValue(etTimeout) - 1)
        }
        btnTimeoutPlus.setOnClickListener {
            setTimeoutValue(etTimeout, readTimeoutValue(etTimeout) + 1)
        }

        rgType.setOnCheckedChangeListener { _, _ ->
            updateUsbOptionsVisibility(rbUsb, vUsbOptionsDivider, llUsbAutoLaunch, llRemember)
        }
        // 「USB拉起APP」跟随「记住授权」的勾选状态启用/置灰
        cbRemember.setOnCheckedChangeListener { _, _ ->
            updateUsbLaunchEnabled(cbRemember, cbUsbAutoLaunch)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.connect_type_title)
            .setView(scrollView)
            // 传 null 自行处理点击，避免弹窗在校验/申请权限前就被关掉
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 统一按钮样式：确定 = 绿色，取消 = 灰色（必须在 show 之后取 button）
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.teal_200))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.cl_text_c_btn_dialog))

            // 内容超高时压到最大高度、改为内部滚动（必须 show 之后：此时才量得到真实高度）
            limitContentHeight(activity, scrollView)

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newType = when {
                    rbUsb.isChecked -> CarLifeContext.CONNECTION_TYPE_AOA
                    rbWifi.isChecked -> CarLifeContext.CONNECTION_TYPE_HOTSPOT
                    rbDirect.isChecked -> CarLifeContext.CONNECTION_TYPE_WIFIDIRECT
                    else -> currentType
                }
                val newUsbAutoLaunch = cbUsbAutoLaunch.isChecked
                val newRemember = cbRemember.isChecked
                val newTimeout = readTimeoutValue(etTimeout)
                val typeChanged = newType != currentType
                val usbLaunchChanged = newUsbAutoLaunch != currentUsbAutoLaunch
                val rememberChanged = newRemember != currentRemember
                val timeoutChanged = newTimeout != currentTimeout

                if (!typeChanged && !usbLaunchChanged && !rememberChanged && !timeoutChanged) {
                    dialog.dismiss()
                    return@setOnClickListener
                }

                // 直连需要定位权限，授权通过后再落盘
                if (typeChanged && newType == CarLifeContext.CONNECTION_TYPE_WIFIDIRECT) {
                    dialog.dismiss()
                    requestDirectPermissionAndApply(
                        activity, newType, newUsbAutoLaunch, newRemember, newTimeout, onChanged
                    )
                    return@setOnClickListener
                }

                if (typeChanged) {
                    ConnectTypePrefs.setType(activity, newType)
                    Logger.d(TAG, "connect type changed to $newType")
                }
                if (usbLaunchChanged) {
                    ConnectTypePrefs.setUsbAutoLaunch(activity, newUsbAutoLaunch)
                    Logger.d(TAG, "usb auto launch changed to $newUsbAutoLaunch")
                }
                if (rememberChanged) {
                    ConnectTypePrefs.setRememberGrant(activity, newRemember)
                    Logger.d(TAG, "remember grant changed to $newRemember")
                }
                if (timeoutChanged) {
                    ConnectTypePrefs.setConnectTimeoutSeconds(activity, newTimeout)
                    Logger.d(TAG, "connect timeout changed to ${newTimeout}s")
                }
                onChanged.run()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 「USB拉起APP」依赖「记住授权」：后者未勾选时，前者整行（文字 + 勾选框）置灰不可点，
     * 并自动取消它的勾选 —— 避免出现「记住授权关、拉起开」这种无意义的组合
     * （拉起靠的是 activity-alias，它同时也是授权可记住的前提，见 ConnectTypePrefs）。
     */
    private fun updateUsbLaunchEnabled(
        cbRemember: AppCompatCheckBox,
        cbUsbAutoLaunch: AppCompatCheckBox
    ) {
        val enabled = cbRemember.isChecked
        cbUsbAutoLaunch.isEnabled = enabled
        // Material 规范的 disabled 透明度，让文字与勾选框一起变灰
        cbUsbAutoLaunch.alpha = if (enabled) 1f else DISABLED_ALPHA
        if (!enabled) {
            cbUsbAutoLaunch.isChecked = false
        }
    }

    /**
     * USB 专属的两项勾选，连同它们上方的分隔线，只在选中 USB 时显示
     * （WIFI / 直连没有插线这个概念）。分隔线必须一起隐藏，否则非 USB 时会留一条孤线。
     *
     * 注意：「超时时间」是全局设置、三种连接方式共用，**不受这里控制**，始终显示。
     */
    private fun updateUsbOptionsVisibility(
        rbUsb: RadioButton,
        vUsbOptionsDivider: View,
        llUsbAutoLaunch: LinearLayout,
        llRemember: LinearLayout
    ) {
        val visibility = if (rbUsb.isChecked) View.VISIBLE else View.GONE
        vUsbOptionsDivider.visibility = visibility
        llUsbAutoLaunch.visibility = visibility
        llRemember.visibility = visibility
    }

    /**
     * 限制弹窗内容区高度：内容超过屏幕高度的 [DIALOG_MAX_HEIGHT_RATIO] 时，把外层 ScrollView
     * 压到该高度，超出部分内部滚动。
     *
     * 必须在 dialog show 之后调用 —— 只有 view attach 并完成一次布局才量得到真实高度。
     * 不做这件事的话，AlertDialog 会按内容的实际高度撑开窗口，超出屏幕的部分被直接裁掉，
     * 底部的项（如「超时时间」）既看不见也点不到，而且**不会**自动出现滚动条。
     */
    private fun limitContentHeight(activity: AppCompatActivity, scrollView: ScrollView) {
        val maxHeight =
            (activity.resources.displayMetrics.heightPixels * DIALOG_MAX_HEIGHT_RATIO).toInt()
        val content = scrollView.getChildAt(0) ?: return
        content.post {
            val height = content.height
            if (height > maxHeight) {
                scrollView.layoutParams.height = maxHeight
                Logger.d(TAG, "limit dialog content height $height -> $maxHeight")
            }
        }
    }

    /** 读取输入框里的秒数：空值 / 非法输入回退默认值，并限制在合法区间内 */
    private fun readTimeoutValue(etTimeout: EditText): Int {
        val raw = etTimeout.text?.toString()?.trim().orEmpty()
        val value = raw.toIntOrNull() ?: ConnectTypePrefs.CONNECT_TIMEOUT_DEFAULT_SECONDS
        return value.coerceIn(
            ConnectTypePrefs.CONNECT_TIMEOUT_MIN_SECONDS,
            ConnectTypePrefs.CONNECT_TIMEOUT_MAX_SECONDS
        )
    }

    /** 把秒数写回输入框（已 clamp），并把光标移到末尾方便继续输入 */
    private fun setTimeoutValue(etTimeout: EditText, value: Int) {
        val clamped = value.coerceIn(
            ConnectTypePrefs.CONNECT_TIMEOUT_MIN_SECONDS,
            ConnectTypePrefs.CONNECT_TIMEOUT_MAX_SECONDS
        )
        etTimeout.setText(clamped.toString())
        etTimeout.setSelection(etTimeout.text.length)
    }

    /**
     * 直连权限申请：通过后再应用「连接方式」「USB拉起APP」「记住授权」「超时时间」四个待生效的值。
     */
    private fun requestDirectPermissionAndApply(
        activity: AppCompatActivity,
        type: Int,
        usbAutoLaunch: Boolean,
        remember: Boolean,
        timeoutSeconds: Int,
        onChanged: Runnable
    ) {
        val currentType = ConnectTypePrefs.currentType()
        val currentUsbAutoLaunch = ConnectTypePrefs.isUsbAutoLaunch(activity)
        val currentRemember = ConnectTypePrefs.isRememberGrant(activity)
        val currentTimeout = ConnectTypePrefs.connectTimeoutSeconds(activity)
        PermissionX.init(activity)
            .permissions(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            .request { allGranted, _, deniedList ->
                if (allGranted) {
                    if (type != currentType) {
                        ConnectTypePrefs.setType(activity, type)
                        Logger.d(TAG, "connect type changed to $type (after permission)")
                    }
                    if (usbAutoLaunch != currentUsbAutoLaunch) {
                        ConnectTypePrefs.setUsbAutoLaunch(activity, usbAutoLaunch)
                        Logger.d(TAG, "usb auto launch changed to $usbAutoLaunch (after permission)")
                    }
                    if (remember != currentRemember) {
                        ConnectTypePrefs.setRememberGrant(activity, remember)
                        Logger.d(TAG, "remember grant changed to $remember (after permission)")
                    }
                    if (timeoutSeconds != currentTimeout) {
                        ConnectTypePrefs.setConnectTimeoutSeconds(activity, timeoutSeconds)
                        Logger.d(
                            TAG,
                            "connect timeout changed to ${timeoutSeconds}s (after permission)"
                        )
                    }
                    onChanged.run()
                } else {
                    Toast.makeText(activity, "权限未打开: $deniedList", Toast.LENGTH_LONG).show()
                }
            }
    }
}
