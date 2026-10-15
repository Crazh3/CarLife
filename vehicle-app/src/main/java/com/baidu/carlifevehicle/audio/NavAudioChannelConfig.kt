package com.baidu.carlifevehicle.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager

/**
 * 导航（TTS）声音通道配置。
 *
 * 版本：
 *  - 旧版（MODE_LEGACY）：用 AudioTrack 旧构造函数的 streamType 指定通道
 *  - 新版（MODE_NEW）   ：用 AudioAttributes 的 usage 指定通道
 *
 * 通道值：0~19（见 [AudioChannelTables]），**每个版本各存一份选中值**。
 * 另有"导航音频焦点"开关：导航音频播放时是否请求音频焦点。
 *
 * 生效点：
 *  - 通道版本/数值 → AudioPlayTask.createAudioTrack / getUsage
 *  - 焦点开关      → NavModule.handleNavTtsInit
 */
object NavAudioChannelConfig {

    /** 旧版：AudioTrack(streamType, ...) */
    const val MODE_LEGACY = AudioChannelTables.MODE_LEGACY

    /** 新版：AudioAttributes usage */
    const val MODE_NEW = AudioChannelTables.MODE_NEW

    private const val PREFS_NAME = "vehicle_settings"
    private const val KEY_MODE = "nav_audio_channel_mode"
    private const val KEY_VALUE_LEGACY = "nav_audio_channel_value_legacy"
    private const val KEY_VALUE_NEW = "nav_audio_channel_value_new"
    private const val KEY_FOCUS = "nav_audio_focus_enabled"

    /** 旧版 streamType 列表（0~19） */
    val legacyChannels: List<Pair<Int, String>> get() = AudioChannelTables.legacyChannels

    /** 新版 AudioAttributes usage 列表（0~19） */
    val newChannels: List<Pair<Int, String>> get() = AudioChannelTables.newChannels

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 当前版本：旧版 streamType / 新版 usage（默认新版） */
    fun getMode(context: Context): Int = prefs(context).getInt(KEY_MODE, MODE_NEW)

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
    }

    /** 当前版本下选中的通道数字 */
    fun getValue(context: Context): Int = getValue(context, getMode(context))

    /** 指定版本下选中的通道数字 */
    fun getValue(context: Context, mode: Int): Int =
        if (mode == MODE_LEGACY) {
            prefs(context).getInt(KEY_VALUE_LEGACY, AudioManager.STREAM_MUSIC)
        } else {
            prefs(context).getInt(
                KEY_VALUE_NEW,
                AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
            )
        }

    /** 写入当前版本下的通道数字 */
    fun setValue(context: Context, value: Int) {
        val editor = prefs(context).edit()
        if (getMode(context) == MODE_LEGACY) {
            editor.putInt(KEY_VALUE_LEGACY, value)
        } else {
            editor.putInt(KEY_VALUE_NEW, value)
        }
        editor.apply()
    }

    /** 导航音频播放时是否请求音频焦点（默认开） */
    fun isFocusEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_FOCUS, true)

    fun setFocusEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FOCUS, enabled).apply()
    }

    /** 指定版本下的通道列表 */
    fun channels(mode: Int): List<Pair<Int, String>> = AudioChannelTables.channels(mode)

    fun modeName(mode: Int): String = AudioChannelTables.modeName(mode)

    /** 设置列表摘要，例如 "新版 · 导航 (12)" */
    fun summary(context: Context): String {
        val mode = getMode(context)
        val value = getValue(context)
        val name = AudioChannelTables.channelName(mode, value) ?: "未知"
        return "${modeName(mode)} · $name ($value)"
    }
}
