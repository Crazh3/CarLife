package com.baidu.carlifevehicle.util

import android.view.LayoutInflater
import android.widget.CheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 「主题同步」弹窗：一个开关（默认开启，见 [ThemeSyncPrefs]）。
 *
 * 打开后车机在白天/夜间模式切换时会把状态告诉手机（`0x00018078` +
 * `CarlifeThemeChange`），手机端「深色模式跟随车机」才会生效（见 [ThemeModeReporter]）。
 *
 * 点「确定」才落盘；从关到开时立即补推一次当前状态，不需要重连或重启。
 */
object ThemeSyncDialog {

    private const val TAG = "ThemeSyncDialog"

    /**
     * 显示弹窗。
     *
     * @param onChanged 开关变化后回调（用于刷新设置页列表的值）
     */
    fun show(activity: AppCompatActivity, onChanged: Runnable) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_theme_sync, null, false)
        val cbSync = view.findViewById<CheckBox>(R.id.cb_theme_sync)

        val wasEnabled = ThemeSyncPrefs.isEnabled(activity)
        cbSync.isChecked = wasEnabled

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.theme_sync_title)
            .setView(view)
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 与连接方式/显示方式弹窗统一按钮样式：确定 = 绿色，取消 = 灰色
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.teal_200))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.cl_text_c_btn_dialog))

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val enabled = cbSync.isChecked
                dialog.dismiss()
                if (enabled == wasEnabled) {
                    return@setOnClickListener
                }
                ThemeSyncPrefs.setEnabled(activity, enabled)
                Logger.d(TAG, "theme sync enabled=$enabled")
                if (enabled) {
                    // 刚打开：立刻补推一次当前状态（未连接时会被安全跳过）
                    ThemeModeReporter.syncIfNeeded(activity.applicationContext, force = true)
                }
                onChanged.run()
            }
        }
        dialog.show()
    }
}
