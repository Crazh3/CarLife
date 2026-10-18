package com.baidu.carlifevehicle.util

import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 「媒体音频通道」/「导航音频通道」弹窗（同一个界面，[kind] 区分）。
 *
 * 三项参数与官方车机端 App 的设置项一一对应（见 [AudioChannelPrefs]）：
 *  - 播放时请求音频焦点：`AUDIO_MEDIA_REQUEST_FOCUS` / `AUDIO_TTS_REQUEST_FOCUS`；
 *  - 版本（新版 / 旧版，并排一行）：`AUDIO_MEDIA_LEGACY_API` / `AUDIO_TTS_LEGACY_API`；
 *  - 音频通道（点选）：  `AUDIO_MEDIA_CHANNEL`      / `AUDIO_TTS_CHANNEL`。
 *
 * 「音频通道」是**点选**：0~23 共 24 个格子（4 列 × 6 行），与官方车机端 App 的界面一致
 * —— 有名字的显示名字（媒体 / 导航 / 通话 …），没有名字的显示「通道N」
 * （官方 `x9.e.f` 的兜底就是这个文案）。同一个格子在「新版 / 旧版」下名字不同，
 * 因为数值的含义不同（新版 = usage、旧版 = streamType），所以切版本要重画格子。
 *
 * 布局根节点是 ScrollView，**整个弹窗内容**（焦点 / 版本 / 通道网格 / 说明）一起滚动，
 * 并按屏幕高度整体限高，车机屏矮也只会滚动，不会把通道格子裁掉、按钮推到屏幕外。
 *
 * 点格子会立刻用该通道试听一次（见 [AudioChannelPreview]，与官方一致）。
 * 保存后立即生效（播放侧运行时读取，不需要重启），见 [AudioChannelPrefs.apply]。
 */
object AudioChannelDialog {

    private const val TAG = "AudioChannelDialog"

    /** 媒体音频通道 */
    const val KIND_MEDIA = 0

    /** 导航音频通道 */
    const val KIND_NAVI = 1

    /**
     * 「音频通道」的可选值：0~23，共 24 个（4 列 × 6 行）。
     * 与官方车机端 App 的点选网格逐格一致：有名字的显示名字，没名字的显示「通道N」。
     */
    private val CHANNEL_VALUES = 0..23

    /** 网格列数（官方为 4 列，0~23 正好 6 行） */
    private const val CHANNEL_COLUMNS = 4

    /**
     * 显示弹窗。
     *
     * @param kind      [KIND_MEDIA] 或 [KIND_NAVI]
     * @param onChanged 设置变化后回调（用于刷新设置页列表的值）
     */
    fun show(activity: AppCompatActivity, kind: Int, onChanged: Runnable) {
        val isMedia = kind == KIND_MEDIA
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_audio_channel, null, false)
        val cbFocus = view.findViewById<CheckBox>(R.id.cb_audio_focus)
        val rbNew = view.findViewById<RadioButton>(R.id.rb_audio_version_new)
        val rbLegacy = view.findViewById<RadioButton>(R.id.rb_audio_version_legacy)
        val gridChannel = view.findViewById<GridLayout>(R.id.grid_audio_channel)

        val curFocus = if (isMedia) {
            AudioChannelPrefs.mediaRequestFocus(activity)
        } else {
            AudioChannelPrefs.naviRequestFocus(activity)
        }
        val curLegacy = if (isMedia) {
            AudioChannelPrefs.mediaLegacyApi(activity)
        } else {
            AudioChannelPrefs.naviLegacyApi(activity)
        }
        var selectedChannel = if (isMedia) {
            AudioChannelPrefs.mediaChannel(activity)
        } else {
            AudioChannelPrefs.naviChannel(activity)
        }

        cbFocus.isChecked = curFocus
        if (curLegacy) rbLegacy.isChecked = true else rbNew.isChecked = true

        val density = activity.resources.displayMetrics.density
        val cells = ArrayList<TextView>(CHANNEL_VALUES.count())
        val cellHeight = (density * 44).toInt()
        val cellMargin = (density * 3).toInt()

        fun renderGrid(legacyApi: Boolean) {
            cells.forEachIndexed { index, cell ->
                val value = CHANNEL_VALUES.first + index
                // 同一个数值在新版 / 旧版下含义不同（usage / streamType），名字也跟着变
                cell.text = AudioChannelPrefs.channelName(activity, value, legacyApi)
                cell.isSelected = value == selectedChannel
            }
        }

        // 0~23 的格子：点一下选中；不在同一个 RadioGroup 里，选中态自己维护
        gridChannel.columnCount = CHANNEL_COLUMNS
        for (value in CHANNEL_VALUES) {
            val cell = TextView(activity)
            cell.gravity = Gravity.CENTER
            cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            cell.setTextColor(ContextCompat.getColorStateList(activity, R.color.audio_channel_item_text))
            cell.background = ContextCompat.getDrawable(activity, R.drawable.bg_audio_channel_item)
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener {
                selectedChannel = value
                renderGrid(rbLegacy.isChecked)
                // 与官方一致：点一下就用这个通道播一段试听音，方便判断车机上哪条通道出声
                AudioChannelPreview.play(activity, rbLegacy.isChecked, value, !isMedia)
            }
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f)
            )
            lp.width = 0
            lp.height = cellHeight
            lp.setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
            gridChannel.addView(cell, lp)
            cells.add(cell)
        }
        renderGrid(curLegacy)

        // 「版本」只改这个数值的用法，但格子上显示的名字要跟着换
        rbNew.setOnClickListener { renderGrid(false) }
        rbLegacy.setOnClickListener { renderGrid(true) }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (isMedia) R.string.audio_media_channel_title else R.string.audio_navi_channel_title)
            .setView(view)
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()

        dialog.setOnShowListener {
            // 整体限高：留出标题栏和底部按钮后，把弹窗内容区限制在屏幕高度的一部分，
            // 内容更高时**整块内容一起滚动**（不是只滚通道网格那一小块）。
            val screenHeightDp = activity.resources.configuration.screenHeightDp
            val contentHeightDp = (screenHeightDp * 0.62f).coerceIn(180f, 420f)
            view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                height = (contentHeightDp * density).toInt()
            }
            view.requestLayout()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.teal_200))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                ?.setTextColor(ContextCompat.getColor(activity, R.color.cl_text_c_btn_dialog))

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val requestFocus = cbFocus.isChecked
                val legacyApi = rbLegacy.isChecked
                val channel = selectedChannel
                dialog.dismiss()
                if (isMedia) {
                    AudioChannelPrefs.setMedia(activity, requestFocus, legacyApi, channel)
                } else {
                    AudioChannelPrefs.setNavi(activity, requestFocus, legacyApi, channel)
                }
                Logger.d(
                    TAG, if (isMedia) "media" else "navi",
                    " focus=", requestFocus, " legacy=", legacyApi,
                    " channel=", channel,
                    "(", AudioChannelPrefs.channelName(activity, channel, legacyApi), ")"
                )
                onChanged.run()
            }
        }
        // 关掉弹窗就停掉试听，避免还在响
        dialog.setOnDismissListener { AudioChannelPreview.stop() }
        dialog.show()
    }
}
