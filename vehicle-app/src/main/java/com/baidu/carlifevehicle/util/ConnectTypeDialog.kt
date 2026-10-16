package com.baidu.carlifevehicle.util

import android.Manifest
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
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
 *
 * 两者在系统层有耦合（activity-alias 是 canBeDefault 主载体，开启"USB拉起APP"
 * 后授权弹窗也会带勾选），详细说明见 [ConnectTypePrefs] 中 USB_ALIAS_CLASS 的注释。
 */
object ConnectTypeDialog {

    private const val TAG = "ConnectTypeDialog"

    /** 置灰时的透明度（Material 规范的 disabled alpha） */
    private const val DISABLED_ALPHA = 0.38f

    /**
     * 显示弹窗。
     *
     * @param onChanged 连接方式 / 记住授权 任一发生变化后回调（用于刷新设置页列表）
     */
    fun show(activity: AppCompatActivity, onChanged: Runnable) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_connect_type, null, false)
        val rgType = view.findViewById<RadioGroup>(R.id.rg_connect_type)
        val rbUsb = view.findViewById<RadioButton>(R.id.rb_usb)
        val rbWifi = view.findViewById<RadioButton>(R.id.rb_wifi)
        val rbDirect = view.findViewById<RadioButton>(R.id.rb_direct)
        val llUsbAutoLaunch = view.findViewById<LinearLayout>(R.id.ll_usb_auto_launch)
        val cbUsbAutoLaunch = view.findViewById<AppCompatCheckBox>(R.id.cb_usb_auto_launch)
        val llRemember = view.findViewById<LinearLayout>(R.id.ll_remember_grant)
        val cbRemember = view.findViewById<AppCompatCheckBox>(R.id.cb_remember_grant)

        val currentType = ConnectTypePrefs.currentType()
        val currentUsbAutoLaunch = ConnectTypePrefs.isUsbAutoLaunch(activity)
        val currentRemember = ConnectTypePrefs.isRememberGrant(activity)
        when (currentType) {
            CarLifeContext.CONNECTION_TYPE_HOTSPOT -> rbWifi.isChecked = true
            CarLifeContext.CONNECTION_TYPE_WIFIDIRECT -> rbDirect.isChecked = true
            else -> rbUsb.isChecked = true
        }
        cbRemember.isChecked = currentRemember
        cbUsbAutoLaunch.isChecked = currentUsbAutoLaunch
        updateUsbOptionsVisibility(rbUsb, llUsbAutoLaunch, llRemember)
        updateUsbLaunchEnabled(cbRemember, cbUsbAutoLaunch)

        rgType.setOnCheckedChangeListener { _, _ ->
            updateUsbOptionsVisibility(rbUsb, llUsbAutoLaunch, llRemember)
        }
        // 「USB拉起APP」跟随「记住授权」的勾选状态启用/置灰
        cbRemember.setOnCheckedChangeListener { _, _ ->
            updateUsbLaunchEnabled(cbRemember, cbUsbAutoLaunch)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.connect_type_title)
            .setView(view)
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

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newType = when {
                    rbUsb.isChecked -> CarLifeContext.CONNECTION_TYPE_AOA
                    rbWifi.isChecked -> CarLifeContext.CONNECTION_TYPE_HOTSPOT
                    rbDirect.isChecked -> CarLifeContext.CONNECTION_TYPE_WIFIDIRECT
                    else -> currentType
                }
                val newUsbAutoLaunch = cbUsbAutoLaunch.isChecked
                val newRemember = cbRemember.isChecked
                val typeChanged = newType != currentType
                val usbLaunchChanged = newUsbAutoLaunch != currentUsbAutoLaunch
                val rememberChanged = newRemember != currentRemember

                if (!typeChanged && !usbLaunchChanged && !rememberChanged) {
                    dialog.dismiss()
                    return@setOnClickListener
                }

                // 直连需要定位权限，授权通过后再落盘
                if (typeChanged && newType == CarLifeContext.CONNECTION_TYPE_WIFIDIRECT) {
                    dialog.dismiss()
                    requestDirectPermissionAndApply(
                        activity, newType, newUsbAutoLaunch, newRemember, onChanged
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

    /** 两项 USB 相关的勾选只在选中 USB 时显示（WIFI / 直连没有插线这个概念） */
    private fun updateUsbOptionsVisibility(
        rbUsb: RadioButton,
        llUsbAutoLaunch: LinearLayout,
        llRemember: LinearLayout
    ) {
        val visibility = if (rbUsb.isChecked) View.VISIBLE else View.GONE
        llUsbAutoLaunch.visibility = visibility
        llRemember.visibility = visibility
    }

    /**
     * 直连权限申请：通过后再应用「连接方式」「USB拉起APP」「记住授权」三个待生效的值。
     */
    private fun requestDirectPermissionAndApply(
        activity: AppCompatActivity,
        type: Int,
        usbAutoLaunch: Boolean,
        remember: Boolean,
        onChanged: Runnable
    ) {
        val currentType = ConnectTypePrefs.currentType()
        val currentUsbAutoLaunch = ConnectTypePrefs.isUsbAutoLaunch(activity)
        val currentRemember = ConnectTypePrefs.isRememberGrant(activity)
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
                    onChanged.run()
                } else {
                    Toast.makeText(activity, "权限未打开: $deniedList", Toast.LENGTH_LONG).show()
                }
            }
    }
}
