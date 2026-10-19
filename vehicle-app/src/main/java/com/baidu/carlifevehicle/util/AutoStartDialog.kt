package com.baidu.carlifevehicle.util

import android.view.LayoutInflater
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 「开机启动」弹窗：一个开关（默认关闭，见 [AutoStartPrefs]）。
 *
 * 打开后车机开机完成（ACTION_BOOT_COMPLETED）时会拉起 CarLife 主界面
 * （见 [com.baidu.carlifevehicle.BootReceiver]）；关闭后开机不自动打开，
 * 需要用户手动进入。开关立即生效（同步的是组件启用状态），无需重启应用。
 */
object AutoStartDialog {

    private const val TAG = "AutoStartDialog"

    /**
     * 显示弹窗。
     *
     * @param onChanged 开关变化后回调（用于刷新设置页列表的值）
     */
    fun show(activity: AppCompatActivity, onChanged: Runnable) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_auto_start, null, false)
        val cbAutoStart = view.findViewById<CheckBox>(R.id.cb_auto_start)

        val wasEnabled = AutoStartPrefs.isEnabled(activity)
        cbAutoStart.isChecked = wasEnabled

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.auto_start_title)
            .setView(view)
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 与其他设置弹窗统一按钮样式：确定 = 绿色，取消 = 灰色
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.teal_200))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.cl_text_c_btn_dialog))

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enabled = cbAutoStart.isChecked
                dialog.dismiss()
                if (enabled == wasEnabled) {
                    return@setOnClickListener
                }
                AutoStartPrefs.setEnabled(activity, enabled)
                Logger.d(TAG, "auto start enabled=$enabled")
                onChanged.run()
            }
        }
        dialog.show()
    }
}
