package com.baidu.carlifevehicle.util

import android.view.LayoutInflater
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 「音频传输通道」弹窗：互联通道 / 蓝牙通道（见 [AudioChannelPrefs]）。
 *
 *  - 互联通道：手机把音频通过 CarLife 通道传给车机，由车机播放；
 *  - 蓝牙通道：手机走蓝牙（A2DP）直接播放，车机不接管音频。
 *
 * 该值作为 `FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE` 下发给手机端，
 * **需要重新连接手机后才生效**（弹窗里已说明）。
 */
object AudioTransmissionDialog {

    private const val TAG = "AudioTransmissionDialog"

    /**
     * 显示弹窗。
     *
     * @param onChanged 设置变化后回调（用于刷新设置页列表的值）
     */
    fun show(activity: AppCompatActivity, onChanged: Runnable) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_audio_transmission, null, false)
        val rbCarlife = view.findViewById<RadioButton>(R.id.rb_audio_transmission_carlife)
        val rbBluetooth = view.findViewById<RadioButton>(R.id.rb_audio_transmission_bluetooth)

        val currentMode = AudioChannelPrefs.transmissionMode(activity)
        if (currentMode == AudioChannelPrefs.TRANSMISSION_BLUETOOTH) {
            rbBluetooth.isChecked = true
        } else {
            rbCarlife.isChecked = true
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.audio_transmission_title)
            .setView(view)
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 与其它设置弹窗统一按钮样式：确定 = 绿色，取消 = 灰色
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.teal_200))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.cl_text_c_btn_dialog))

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mode = if (rbBluetooth.isChecked) {
                    AudioChannelPrefs.TRANSMISSION_BLUETOOTH
                } else {
                    AudioChannelPrefs.TRANSMISSION_CARLIFE
                }
                dialog.dismiss()
                if (mode == currentMode) {
                    return@setOnClickListener
                }
                AudioChannelPrefs.setTransmissionMode(activity, mode)
                Logger.d(TAG, "audio transmission mode -> $mode")
                onChanged.run()
            }
        }
        dialog.show()
    }
}
