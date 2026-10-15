package com.baidu.carlifevehicle

import android.Manifest
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.audio.AudioChannelTables
import com.baidu.carlifevehicle.audio.MediaAudioChannelConfig
import com.baidu.carlifevehicle.audio.NavAudioChannelConfig
import com.baidu.carlifevehicle.util.AudioTransmissionPrefs
import com.baidu.carlifevehicle.util.ConnectTypePrefs
import com.baidu.carlifevehicle.util.DisplayModeConfig
import com.baidu.carlifevehicle.util.DisplaySpecLogger
import com.permissionx.guolindev.PermissionX

/**
 * 设置页面。
 *
 * 弹窗策略：所有设置弹窗统一为「点选项只改 UI，点保存才应用」模式。
 *  - 连接方式：弹窗内含连接方式（USB/WIFI/直连）+ 记住授权（仅 USB 选中时显示）。
 *  - 显示模式：系统默认 / 沉浸式全屏。
 */
class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SettingsActivity"

        // 设置项类型
        private const val ITEM_CONNECT_TYPE = 0
        private const val ITEM_AUDIO_TRANSMISSION = 1
        private const val ITEM_MEDIA_CHANNEL = 2
        private const val ITEM_NAVI_CHANNEL = 3
        private const val ITEM_DISPLAY_MODE = 4
    }

    private lateinit var mBackBtn: ImageButton
    private lateinit var mTitle: TextView
    private lateinit var mSettingsList: ListView
    private lateinit var mAdapter: SettingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题不强制 windowFullscreen，由 DisplayModeConfig.apply 统一控制：
        // - 沉浸式全屏：状态栏 + 导航栏都隐藏
        // - 系统默认：状态栏 + 导航栏都显示
        // 与 CarlifeActivity 行为一致。
        DisplayModeConfig.apply(this)
        setContentView(R.layout.activity_settings)

        mBackBtn = findViewById(R.id.ib_left)
        mBackBtn.setOnClickListener { finish() }

        mTitle = findViewById(R.id.tv_title)
        mTitle.setText(R.string.title_activity_settings)

        mAdapter = SettingsAdapter()
        mSettingsList = findViewById(R.id.settings_list)
        mSettingsList.adapter = mAdapter
        mSettingsList.setOnItemClickListener { _, _, position, _ ->
            val item = mAdapter.getItem(position)!!
            if (!isItemVisible(item.id)) return@setOnItemClickListener
            when (item.id) {
                ITEM_CONNECT_TYPE -> showConnectTypeDialog()
                ITEM_AUDIO_TRANSMISSION -> showAudioTransmissionDialog()
                ITEM_MEDIA_CHANNEL -> showChannelSelectDialog(
                    R.string.media_channel_title,
                    isMedia = true
                )
                ITEM_NAVI_CHANNEL -> showChannelSelectDialog(
                    R.string.navi_channel_title,
                    isMedia = false
                )
                ITEM_DISPLAY_MODE -> showDisplayModeDialog()
            }
        }

        Logger.d(TAG, "onCreate done")
    }

    override fun onResume() {
        super.onResume()
        // 切换设置项后回到此页面要刷新"当前值"和"可见项"
        mAdapter.notifyDataSetChanged()
    }

    /**
     * 声音传输通道弹窗：单选「点选项只改 UI 状态，点保存才应用」。
     * - 互联（默认）：声音走 carlife TCP 通道
     * - 蓝牙：声音走蓝牙 A2DP 通道
     * 保存后立即通过 CarLife.receiver().setFeature(...) 让 SDK 重建音频通道。
     */
    private fun showAudioTransmissionDialog() {
        val labels = arrayOf(
            getString(R.string.audio_transmission_interconnect),
            getString(R.string.audio_transmission_bluetooth)
        )
        val current = AudioTransmissionPrefs.currentMode(this)
        val currentIndex = if (current == AudioTransmissionPrefs.MODE_BLUETOOTH) 1 else 0
        // pending：弹窗内累计用户选中的值，仅在点"保存"时落盘
        var pending = currentIndex

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.audio_transmission_title)
            .setSingleChoiceItems(labels, currentIndex) { _, which ->
                // 仅更新 pending，不立即应用
                pending = which
            }
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()
        dialog.setOnShowListener {
            // 统一按钮样式：确定 = 绿色，取消 = 灰色
            applyCarlifeDialogStyle(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newMode = if (pending == 1) {
                    AudioTransmissionPrefs.MODE_BLUETOOTH
                } else {
                    AudioTransmissionPrefs.MODE_INTERCONNECT
                }
                if (newMode != current) {
                    // setMode 内部会调 CarLife.receiver().setFeature(FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE, newMode)
                    // 让 SDK 运行时立即切换声音通道。
                    AudioTransmissionPrefs.setMode(this, newMode)
                }
                mAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 设置项是否可见：
     * - 媒体 / 导航声音通道：仅「声音传输通道 = 互联」时可见（总通道走蓝牙时无需细分）
     * - 其他项恒可见
     */
    private fun isItemVisible(id: Int): Boolean = when (id) {
        ITEM_MEDIA_CHANNEL, ITEM_NAVI_CHANNEL ->
            AudioTransmissionPrefs.currentMode(this) == AudioTransmissionPrefs.MODE_INTERCONNECT
        else -> true
    }

    /**
     * 媒体 / 导航声音通道弹窗（对齐 apollo-DuerOS1 的导航声音通道设置页）。
     * - 导航：额外提供"导航音频焦点"开关（播放时是否请求音频焦点）
     * - 版本：旧版（AudioTrack streamType）/ 新版（AudioAttributes usage）
     * - 通道：0~19 具名通道网格（4 列）
     * 点选只改 UI（pending），点"保存"才落盘；每个版本各存一份选中值。
     */
    private fun showChannelSelectDialog(titleRes: Int, isMedia: Boolean) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_channel_select, null, false)
        val llFocusRow = view.findViewById<LinearLayout>(R.id.ll_focus_row)
        val divFocus = view.findViewById<View>(R.id.div_focus)
        val switchFocus = view.findViewById<Switch>(R.id.switch_focus)
        val tvVersionLegacy = view.findViewById<TextView>(R.id.tv_version_legacy)
        val tvVersionNew = view.findViewById<TextView>(R.id.tv_version_new)
        val tvHint = view.findViewById<TextView>(R.id.tv_channel_hint)
        val grid = view.findViewById<LinearLayout>(R.id.ll_channel_grid)

        fun currentMode(): Int =
            if (isMedia) MediaAudioChannelConfig.getMode(this) else NavAudioChannelConfig.getMode(this)

        fun valueOf(mode: Int): Int =
            if (isMedia) MediaAudioChannelConfig.getValue(this, mode)
            else NavAudioChannelConfig.getValue(this, mode)

        fun channelsOf(mode: Int): List<Pair<Int, String>> =
            if (isMedia) MediaAudioChannelConfig.channels(mode)
            else NavAudioChannelConfig.channels(mode)

        // 初始值（"保存"时用来判断是否有改动）
        val initialMode = currentMode()
        val initialValue = valueOf(initialMode)
        val initialFocus = !isMedia && NavAudioChannelConfig.isFocusEnabled(this)

        // pending：弹窗内累计用户选择，仅在点"保存"时落盘
        var pendingMode = initialMode
        var pendingValue = initialValue
        var pendingFocus = initialFocus

        val density = resources.displayMetrics.density
        val radius = 8f * density
        val tealColor = ContextCompat.getColor(this, R.color.teal_200)
        // 未选中格子的底色：半透明灰，深色/浅色弹窗下都能看出方块
        val cellColor = Color.parseColor("#33808080")
        val selectedTextColor = Color.BLACK
        val normalTextColor = tvVersionNew.currentTextColor

        fun cellBackground(color: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }

        // ---- 导航音频焦点开关（仅导航通道显示） ----
        if (isMedia) {
            llFocusRow.visibility = View.GONE
            divFocus.visibility = View.GONE
        } else {
            switchFocus.isChecked = pendingFocus
            switchFocus.setOnCheckedChangeListener { _, checked -> pendingFocus = checked }
        }

        // ---- 版本 ----
        fun refreshVersion() {
            val legacySelected = pendingMode == AudioChannelTables.MODE_LEGACY
            tvVersionLegacy.background = cellBackground(if (legacySelected) tealColor else cellColor)
            tvVersionLegacy.setTextColor(if (legacySelected) selectedTextColor else normalTextColor)
            tvVersionNew.background = cellBackground(if (legacySelected) cellColor else tealColor)
            tvVersionNew.setTextColor(if (legacySelected) normalTextColor else selectedTextColor)
            tvHint.setText(
                if (legacySelected) R.string.channel_legacy_hint else R.string.channel_new_hint
            )
        }

        // ---- 通道网格 ----
        var cells: ArrayList<Pair<Int, TextView>> = ArrayList()
        fun refreshChannelSelection() {
            cells.forEach { (value, cell) ->
                val selected = value == pendingValue
                cell.background = cellBackground(if (selected) tealColor else cellColor)
                cell.setTextColor(if (selected) selectedTextColor else normalTextColor)
            }
        }

        fun rebuildGrid() {
            grid.removeAllViews()
            cells = ArrayList()
            val columns = 4
            val cellHeight = (48 * density).toInt()
            val cellMargin = (4 * density).toInt()
            var rowLayout: LinearLayout? = null
            channelsOf(pendingMode).forEachIndexed { index, entry ->
                if (index % columns == 0) {
                    val row = LinearLayout(this)
                    row.orientation = LinearLayout.HORIZONTAL
                    row.layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    grid.addView(row)
                    rowLayout = row
                }
                val parentRow = rowLayout!!
                val cell = LayoutInflater.from(this)
                    .inflate(R.layout.item_channel_name, parentRow, false) as TextView
                cell.text = entry.second
                cell.layoutParams = LinearLayout.LayoutParams(0, cellHeight, 1f).apply {
                    setMargins(cellMargin, cellMargin, cellMargin, cellMargin)
                }
                cell.setOnClickListener {
                    pendingValue = entry.first
                    refreshChannelSelection()
                }
                parentRow.addView(cell)
                cells.add(entry.first to cell)
            }
            refreshChannelSelection()
        }

        fun switchVersion(mode: Int) {
            if (pendingMode == mode) return
            pendingMode = mode
            // 切版本后，选中值取该版本已存的值
            pendingValue = valueOf(mode)
            refreshVersion()
            rebuildGrid()
        }

        tvVersionLegacy.setOnClickListener { switchVersion(AudioChannelTables.MODE_LEGACY) }
        tvVersionNew.setOnClickListener { switchVersion(AudioChannelTables.MODE_NEW) }

        refreshVersion()
        rebuildGrid()

        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(view)
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()
        dialog.setOnShowListener {
            // 统一按钮样式：确定 = 绿色，取消 = 灰色
            applyCarlifeDialogStyle(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val modeChanged = pendingMode != initialMode
                val valueChanged = pendingValue != initialValue
                val focusChanged = !isMedia && pendingFocus != initialFocus
                if (modeChanged || valueChanged || focusChanged) {
                    // 注意顺序：先切版本，再写值（setValue 写入"当前版本"对应的槽位）
                    if (isMedia) {
                        MediaAudioChannelConfig.setMode(this, pendingMode)
                        MediaAudioChannelConfig.setValue(this, pendingValue)
                    } else {
                        NavAudioChannelConfig.setMode(this, pendingMode)
                        NavAudioChannelConfig.setValue(this, pendingValue)
                        NavAudioChannelConfig.setFocusEnabled(this, pendingFocus)
                    }
                }
                mAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 显示模式弹窗：单选「点选项只改 UI 状态，点保存才应用」。
     */
    private fun showDisplayModeDialog() {
        val labels = arrayOf(
            getString(R.string.settings_display_mode_system),
            getString(R.string.settings_display_mode_immersive)
        )
        val current = DisplayModeConfig.getMode(this)
        // pending：弹窗内累计用户选中的值，仅在点"保存"时落盘
        var pending = current

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_display_mode)
            .setSingleChoiceItems(labels, current) { _, which ->
                // 仅更新 pending，不立即应用
                pending = which
            }
            .setPositiveButton(R.string.alert_confirm, null)
            .setNegativeButton(R.string.alert_cancel, null)
            .create()
        dialog.setOnShowListener {
            // 统一按钮样式：确定 = 绿色，取消 = 灰色
            applyCarlifeDialogStyle(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pending != current) {
                    DisplayModeConfig.setMode(this, pending)
                    Logger.d(TAG, "display mode changed to $pending")
                    // 立即应用到当前 Activity
                    DisplayModeConfig.apply(this)
                    // 诊断日志：切换显示模式后的屏幕/窗口尺寸对比
                    DisplaySpecLogger.logPhysicalScreen(this, "SettingsActivity.changeMode")
                    DisplaySpecLogger.logAppWindow(this, "SettingsActivity.changeMode")
                    // 注意：displaySpec 在 SDK 中只在 onConnectionEstablished 时读一次，
                    // 运行时切换显示模式 SDK 不会重发。DisplayModeConfig.apply 用的是
                    // LAYOUT_FULLSCREEN/LAYOUT_HIDE_NAVIGATION 这类 flag，隐藏/显示系统栏
                    // 不会改变 RemoteDisplayGLView 的实际像素尺寸（content view 仍占满整屏），
                    // 因此切换显示模式不需要重发 displaySpec，GLView 渲染区尺寸不变，
                    // 手机端继续按原尺寸编码即可，无需重新连接。
                }
                mAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    /**
     * 连接方式弹窗：内嵌"连接方式 3 选 1"+"记住授权 2 选 1（仅 USB 选中时显示）"。
     * - 点选项只改 UI（不应用），由 positive 按钮"保存"时统一落盘。
     * - 直连需要定位权限，权限申请通过后才真正切换。
     */
    private fun showConnectTypeDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_connect_type, null, false)
        val rgType = view.findViewById<RadioGroup>(R.id.rg_connect_type)
        val rbUsb = view.findViewById<RadioButton>(R.id.rb_usb)
        val rbWifi = view.findViewById<RadioButton>(R.id.rb_wifi)
        val rbDirect = view.findViewById<RadioButton>(R.id.rb_direct)
        val llRemember = view.findViewById<LinearLayout>(R.id.ll_remember_grant)
        val cbRemember = view.findViewById<AppCompatCheckBox>(R.id.cb_remember_grant)

        // 初始化 UI 为当前值
        val currentType = ConnectTypePrefs.currentType()
        val currentRemember = ConnectTypePrefs.isRememberGrant(this)
        when (currentType) {
            CarLifeContext.CONNECTION_TYPE_HOTSPOT -> rbWifi.isChecked = true
            CarLifeContext.CONNECTION_TYPE_WIFIDIRECT -> rbDirect.isChecked = true
            else -> rbUsb.isChecked = true
        }
        cbRemember.isChecked = currentRemember
        updateRememberVisibility(rbUsb, llRemember)

        // RadioGroup 选中变更：仅更新 UI 状态，保存时再落盘
        rgType.setOnCheckedChangeListener { _, _ ->
            updateRememberVisibility(rbUsb, llRemember)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.connect_type_title)
            .setView(view)
            .setPositiveButton(R.string.alert_confirm, null) // 自定义点击处理，避免自动 dismiss
            .setNegativeButton(R.string.alert_cancel, null)
            .create()
        dialog.setOnShowListener {
            // 统一按钮样式：确定 = 绿色，取消 = 灰色
            applyCarlifeDialogStyle(dialog)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                // 1. 收集弹窗内最终值
                val newType = when {
                    rbUsb.isChecked -> CarLifeContext.CONNECTION_TYPE_AOA
                    rbWifi.isChecked -> CarLifeContext.CONNECTION_TYPE_HOTSPOT
                    rbDirect.isChecked -> CarLifeContext.CONNECTION_TYPE_WIFIDIRECT
                    else -> currentType
                }
                val newRemember = cbRemember.isChecked

                val typeChanged = newType != currentType
                val rememberChanged = newRemember != currentRemember
                if (!typeChanged && !rememberChanged) {
                    // 没改任何东西，直接关闭
                    dialog.dismiss()
                    return@setOnClickListener
                }

                // 2. 直连需要定位权限
                if (typeChanged && newType == CarLifeContext.CONNECTION_TYPE_WIFIDIRECT) {
                    dialog.dismiss()
                    requestDirectPermissionAndApply(newType, newRemember)
                    return@setOnClickListener
                }

                // 3. 应用修改
                if (typeChanged) {
                    ConnectTypePrefs.setType(this, newType)
                    Logger.d(TAG, "connect type changed to $newType")
                }
                if (rememberChanged) {
                    ConnectTypePrefs.setRememberGrant(this, newRemember)
                    Logger.d(TAG, "remember grant changed to $newRemember")
                }
                mAdapter.notifyDataSetChanged()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateRememberVisibility(rbUsb: RadioButton, llRemember: LinearLayout) {
        llRemember.visibility = if (rbUsb.isChecked) View.VISIBLE else View.GONE
    }

    /**
     * 统一弹窗按钮样式：
     * - positive（确定）= teal_200 绿色（与显示模式弹窗中"确定"按钮颜色一致）
     * - negative（取消）= cl_text_c_btn_dialog 灰色
     *
     * 注意：必须在 dialog.show() 之后调用（即 setOnShowListener 内部），因为 getButton
     * 在 show 之前返回 null。
     */
    private fun applyCarlifeDialogStyle(dialog: AlertDialog) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(this, R.color.teal_200)
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            ContextCompat.getColor(this, R.color.cl_text_c_btn_dialog)
        )
    }

    /**
     * 直连权限申请：通过后再应用"连接方式"和"记住授权"两个 pending 值。
     */
    private fun requestDirectPermissionAndApply(type: Int, remember: Boolean) {
        val currentType = ConnectTypePrefs.currentType()
        val currentRemember = ConnectTypePrefs.isRememberGrant(this)
        PermissionX.init(this)
            .permissions(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            .request { allGranted, _, deniedList ->
                if (allGranted) {
                    if (type != currentType) {
                        ConnectTypePrefs.setType(this, type)
                        Logger.d(TAG, "connect type changed to $type (after permission)")
                    }
                    if (remember != currentRemember) {
                        ConnectTypePrefs.setRememberGrant(this, remember)
                        Logger.d(TAG, "remember grant changed to $remember (with direct)")
                    }
                    mAdapter.notifyDataSetChanged()
                } else {
                    Toast.makeText(this, "权限未打开: $deniedList", Toast.LENGTH_LONG).show()
                }
            }
    }

    /**
     * 设置列表数据。
     * - titleRes / id 固定
     * - 可见性按 isItemVisible 在 adapter 层过滤（避免 GONE item 仍占 56dp 空白条）
     * - value 在 getView 中按 id 实时计算（dialog 改完返回后能反映新值）
     */
    private data class Item(
        val id: Int,
        val titleRes: Int
    )

    private inner class SettingsAdapter : ArrayAdapter<Item>(this, 0) {
        // 全部设置项；按 isItemVisible 过滤后只把可见的交给 ListView，
        // 这样 ListView 不会持有 GONE item，也就不会留下 minHeight 高度的空白条。
        private val allItems = listOf(
            Item(ITEM_CONNECT_TYPE, R.string.settings_connect_type),
            Item(ITEM_AUDIO_TRANSMISSION, R.string.settings_audio_transmission),
            // 媒体/导航声音通道：仅"声音传输通道 = 互联"时显示
            Item(ITEM_MEDIA_CHANNEL, R.string.settings_media_channel),
            Item(ITEM_NAVI_CHANNEL, R.string.settings_navi_channel),
            // "记住授权"已并入连接方式弹窗，不再作为独立设置项。
            Item(ITEM_DISPLAY_MODE, R.string.settings_display_mode)
        )

        private fun visibleItems(): List<Item> =
            allItems.filter { isItemVisible(it.id) }

        override fun getCount(): Int = visibleItems().size

        override fun getItem(position: Int): Item? = visibleItems().getOrNull(position)

        override fun getItemId(position: Int): Long =
            getItem(position)?.id?.toLong() ?: 0L

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings, parent, false)
            val item = getItem(position)!!
            // 这里的所有 item 都已通过 visibleItems 过滤，恒可见；
            // 仍显式置 VISIBLE 是为了 convertView 复用时清掉之前的状态。
            view.visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tv_settings_title).setText(item.titleRes)
            val valueText = when (item.id) {
                ITEM_CONNECT_TYPE -> ConnectTypePrefs.typeName(this@SettingsActivity)
                ITEM_AUDIO_TRANSMISSION -> AudioTransmissionPrefs.modeName(this@SettingsActivity)
                ITEM_MEDIA_CHANNEL -> MediaAudioChannelConfig.summary(this@SettingsActivity)
                ITEM_NAVI_CHANNEL -> NavAudioChannelConfig.summary(this@SettingsActivity)
                ITEM_DISPLAY_MODE -> DisplayModeConfig.modeName(this@SettingsActivity)
                else -> ""
            }
            view.findViewById<TextView>(R.id.tv_settings_value).text = valueText
            return view
        }
    }
}
