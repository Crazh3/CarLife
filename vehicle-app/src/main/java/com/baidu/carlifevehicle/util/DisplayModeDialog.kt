package com.baidu.carlifevehicle.util

import android.view.LayoutInflater
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 「显示方式」弹窗：系统默认 / 沉浸式全屏 二选一。
 *
 * - 默认「系统默认」，也就是本项设置出现之前的显示效果（见 [DisplayModePrefs]）。
 * - 点「确定」才落盘，并立即应用到当前窗口（设置页所在的 Activity 就是 CarLife 主界面），
 *   不需要重启应用。
 */
object DisplayModeDialog {

    private const val TAG = "DisplayModeDialog"

    /**
     * 显示弹窗。
     *
     * @param onChanged 显示方式变化后回调（用于刷新设置页列表的值）
     */
    fun show(activity: AppCompatActivity, onChanged: Runnable) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_display_mode, null, false)
        val rbSystem = view.findViewById<RadioButton>(R.id.rb_display_system)
        val rbImmersive = view.findViewById<RadioButton>(R.id.rb_display_immersive)

        val currentMode = DisplayModePrefs.currentMode(activity)
        if (currentMode == DisplayModePrefs.MODE_IMMERSIVE_FULLSCREEN) {
            rbImmersive.isChecked = true
        } else {
            rbSystem.isChecked = true
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.display_mode_title)
            .setView(view)
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 与连接方式弹窗统一按钮样式：确定 = 绿色，取消 = 灰色
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.teal_200))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.cl_text_c_btn_dialog))

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newMode = if (rbImmersive.isChecked) {
                    DisplayModePrefs.MODE_IMMERSIVE_FULLSCREEN
                } else {
                    DisplayModePrefs.MODE_SYSTEM
                }
                if (newMode == currentMode) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                DisplayModePrefs.setMode(activity, newMode)
                Logger.d(TAG, "display mode changed to $newMode")
                // 先关弹窗再改系统栏，避免弹窗自身窗口还处于旧的全屏状态下重绘
                dialog.dismiss()
                DisplayModeApplier.apply(
                    activity,
                    newMode == DisplayModePrefs.MODE_IMMERSIVE_FULLSCREEN
                )
                // 投屏基准尺寸随显示模式变化（沉浸式用屏幕真实宽高、系统默认用去掉系统栏的可用区），
                // 一起重新下发给手机端，否则比例不一致会出现黑边（见 DisplaySpecConfig）
                CarLife.receiver().setDisplaySpec(DisplaySpecConfig.build(activity))
                onChanged.run()
            }
        }
        dialog.show()
    }
}
