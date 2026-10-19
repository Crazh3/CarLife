package com.baidu.carlifevehicle.util

import android.content.Context
import com.baidu.carlife.sdk.internal.audio.AudioChannelConfig
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 设置页新增的三项音频设置：
 *  - 音频传输通道（互联通道 / 蓝牙通道）；
 *  - 媒体音频通道（音频焦点 / 版本 新版|旧版 / 具体音频通道）；
 *  - 导航音频通道（同上）。
 *
 * 配置项名与官方车机端 App（`com.baidu.carlifevehicle.beta`）**完全一致**，
 * 存在同一个 SP（vehicle_settings）里，方便与官方对照：
 * ```
 * AUDIO_TRANSMISSION_MODE   0=互联通道(走 CarLife 通道传输音频) 1=蓝牙通道(走蓝牙 A2DP)
 * AUDIO_MEDIA_REQUEST_FOCUS 媒体：播放时是否请求音频焦点
 * AUDIO_MEDIA_LEGACY_API    媒体：版本（true=旧版接口 false=新版接口）
 * AUDIO_MEDIA_CHANNEL       媒体：具体音频通道（旧版=streamType 新版=usage）
 * AUDIO_TTS_REQUEST_FOCUS   导航：同上
 * AUDIO_TTS_LEGACY_API      导航：同上
 * AUDIO_TTS_CHANNEL         导航：同上
 * ```
 *
 * 其中导航这一组的**出厂默认值**被改成了「旧版 + 通道 14」（见 [NAVI_DEFAULT_CHANNEL]），
 * 不是官方默认的 3 / 12 —— 车机上导航播报要走这一路才对。
 *
 * 前两项设置只在「音频传输通道 = 互联通道」时才有意义（蓝牙通道时音频由手机直接
 * 走蓝牙播放，车机不接管），设置页对这两项会置灰，见 [channelSettingsEnabled]。
 *
 * 值保存后通过 [apply] 灌入 SDK 的 [AudioChannelConfig]，播放侧（AudioPlayTask、
 * AudioFocusManager）运行时读取，不需要重启。
 */
object AudioChannelPrefs {

    private const val TAG = "AudioChannelPrefs"

    /** 与「连接方式 / 显示方式 / 主题同步」同用一个 SP 文件 */
    private const val PREFS_SETTINGS = "vehicle_settings"

    private const val KEY_TRANSMISSION_MODE = "AUDIO_TRANSMISSION_MODE"
    private const val KEY_MEDIA_REQUEST_FOCUS = "AUDIO_MEDIA_REQUEST_FOCUS"
    private const val KEY_MEDIA_LEGACY_API = "AUDIO_MEDIA_LEGACY_API"
    private const val KEY_MEDIA_CHANNEL = "AUDIO_MEDIA_CHANNEL"
    private const val KEY_TTS_REQUEST_FOCUS = "AUDIO_TTS_REQUEST_FOCUS"
    private const val KEY_TTS_LEGACY_API = "AUDIO_TTS_LEGACY_API"
    private const val KEY_TTS_CHANNEL = "AUDIO_TTS_CHANNEL"

    /** 互联通道：手机把音频经 CarLife 通道传给车机，由车机播放 */
    const val TRANSMISSION_CARLIFE = 0

    /** 蓝牙通道：手机走蓝牙 A2DP 播放，车机不接管音频 */
    const val TRANSMISSION_BLUETOOTH = 1

    /**
     * 音频传输通道。
     *
     * 默认「互联通道」（[TRANSMISSION_CARLIFE]）：音频经 CarLife 通道传给车机播放，
     * 车机侧才能接管媒体 / 导航音频（也才会用到下面的媒体/导航音频通道设置）。
     * 该值同时作为 `FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE` 下发给手机端
     * （见 VehicleApplication.initReceiver 的 features），需要重新连接手机后生效。
     *
     * 注意：默认值只在首次安装（或清除应用数据）时生效，之后读到的是用户上次的选择。
     */
    fun transmissionMode(context: Context): Int =
        prefs(context).getInt(KEY_TRANSMISSION_MODE, TRANSMISSION_CARLIFE)

    fun setTransmissionMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_TRANSMISSION_MODE, mode).apply()
        Logger.d(TAG, "setTransmissionMode $mode (0=carlife 1=bluetooth)")
    }

    /** 是否「互联通道」——媒体/导航音频通道设置只有此时才可用。 */
    fun channelSettingsEnabled(context: Context): Boolean =
        transmissionMode(context) == TRANSMISSION_CARLIFE

    // ---------------- 媒体音频通道 ----------------

    fun mediaRequestFocus(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEDIA_REQUEST_FOCUS, true)

    fun mediaLegacyApi(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEDIA_LEGACY_API, AudioChannelConfig.DEFAULT_LEGACY_API)

    fun mediaChannel(context: Context): Int =
        prefs(context).getInt(KEY_MEDIA_CHANNEL, AudioChannelConfig.defaultMediaChannel(mediaLegacyApi(context)))

    fun setMedia(context: Context, requestFocus: Boolean, legacyApi: Boolean, channel: Int) {
        prefs(context).edit()
            .putBoolean(KEY_MEDIA_REQUEST_FOCUS, requestFocus)
            .putBoolean(KEY_MEDIA_LEGACY_API, legacyApi)
            .putInt(KEY_MEDIA_CHANNEL, channel)
            .apply()
        Logger.d(TAG, "setMedia focus=$requestFocus legacy=$legacyApi channel=$channel")
        apply(context)
    }

    // ---------------- 导航音频通道 ----------------

    /**
     * 导航音频通道的**出厂默认值**：旧版接口（[AudioChannelConfig] 里 legacy=true）+ 通道值 14。
     *
     * 车机上导航播报要落到方向盘/仪表的那一路音源，实测 14 才是有效值，
     * 所以这里不再沿用官方默认（旧版 STREAM_MUSIC(3) / 新版 USAGE_NAVIGATION(12)）。
     *
     * 注意：14 不在官方的 0~23 名称对照表里（见 [CHANNELS]），
     * 设置页会显示成「14通道」，这是正常的 —— 官方也是"解释不了就显示 N通道"。
     */
    private const val NAVI_DEFAULT_LEGACY_API = true
    private const val NAVI_DEFAULT_CHANNEL = 14

    fun naviRequestFocus(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TTS_REQUEST_FOCUS, true)

    fun naviLegacyApi(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TTS_LEGACY_API, NAVI_DEFAULT_LEGACY_API)

    fun naviChannel(context: Context): Int =
        prefs(context).getInt(KEY_TTS_CHANNEL, NAVI_DEFAULT_CHANNEL)

    fun setNavi(context: Context, requestFocus: Boolean, legacyApi: Boolean, channel: Int) {
        prefs(context).edit()
            .putBoolean(KEY_TTS_REQUEST_FOCUS, requestFocus)
            .putBoolean(KEY_TTS_LEGACY_API, legacyApi)
            .putInt(KEY_TTS_CHANNEL, channel)
            .apply()
        Logger.d(TAG, "setNavi focus=$requestFocus legacy=$legacyApi channel=$channel")
        apply(context)
    }

    /**
     * 把设置灌入 SDK（应用启动时调用一次；每次改设置也会调用）。
     * 播放侧（AudioPlayTask / AudioFocusManager）运行时读取，立即生效。
     */
    fun apply(context: Context) {
        AudioChannelConfig.mediaRequestFocus = mediaRequestFocus(context)
        AudioChannelConfig.mediaLegacyApi = mediaLegacyApi(context)
        AudioChannelConfig.mediaChannel = mediaChannel(context)

        AudioChannelConfig.naviRequestFocus = naviRequestFocus(context)
        AudioChannelConfig.naviLegacyApi = naviLegacyApi(context)
        AudioChannelConfig.naviChannel = naviChannel(context)

        Logger.d(
            TAG, "apply transmission=", transmissionMode(context),
            " media(focus=", AudioChannelConfig.mediaRequestFocus,
            " legacy=", AudioChannelConfig.mediaLegacyApi,
            " channel=", AudioChannelConfig.mediaChannel, ")",
            " navi(focus=", AudioChannelConfig.naviRequestFocus,
            " legacy=", AudioChannelConfig.naviLegacyApi,
            " channel=", AudioChannelConfig.naviChannel, ")"
        )
    }

    /** 显示名：音频传输通道 */
    fun transmissionNameRes(mode: Int): Int =
        if (mode == TRANSMISSION_BLUETOOTH) {
            R.string.audio_transmission_bluetooth
        } else {
            R.string.audio_transmission_carlife
        }

    /** 显示名：版本（新版 / 旧版） */
    fun versionNameRes(legacyApi: Boolean): Int =
        if (legacyApi) R.string.audio_version_legacy else R.string.audio_version_new

    /**
     * 「音频通道」取值的显示名对照表（反编译官方车机端 App 的 `y7.a` 枚举得到，
     * 名称 / 顺序 / 两套取值全部照抄，对应官方 `x9.e.f(值, 是否旧版)`）。
     *
     * 它只负责把已存的**数字**翻译成名字（设置页条目的值、弹窗格子的文案，
     * 官方也是这么显示的）；弹窗里的点选是 0~23 的网格（见 [AudioChannelDialog]）——
     * 官方就是这个设计：
     * 通道存数字，名字按当前「版本」解释，解释不了就显示「N通道」。
     *
     *
     * ```
     * 名称     新版值(usage)   旧版值(streamType)
     * 通话     2               0
     * 系统     13              1
     * 铃声     6               2
     * 媒体     1               3
     * 游戏     14              -1   ← 旧版没有对应通道
     * 闹钟     4               4
     * 通知     5               5
     * 拨号音   3               8
     * 导航     12              -1   ← 旧版没有对应通道
     * ```
     *
     * @param nameRes 显示名（中文，与官方一致）
     * @param newApiValue 新版接口（AudioAttributes.usage）下的取值
     * @param legacyApiValue 旧版接口（AudioManager streamType）下的取值，-1 = 该版本下没有对应通道
     */
    data class Channel(val nameRes: Int, val newApiValue: Int, val legacyApiValue: Int)

    /** 官方通道清单（顺序与官方枚举一致，即弹窗里的点选顺序） */
    val CHANNELS = listOf(
        Channel(R.string.audio_channel_voice_call, 2, 0),
        Channel(R.string.audio_channel_system, 13, 1),
        Channel(R.string.audio_channel_ring, 6, 2),
        Channel(R.string.audio_channel_music, 1, 3),
        Channel(R.string.audio_channel_game, 14, -1),
        Channel(R.string.audio_channel_alarm, 4, 4),
        Channel(R.string.audio_channel_notification, 5, 5),
        Channel(R.string.audio_channel_dial_tone, 3, 8),
        Channel(R.string.audio_channel_navigation, 12, -1)
    )

    /** 取值 → 显示名资源（按当前「版本」解释，与官方一致）；不在清单里时返回 null */
    fun channelNameRes(value: Int, legacyApi: Boolean): Int? = CHANNELS.firstOrNull {
        (if (legacyApi) it.legacyApiValue else it.newApiValue) == value
    }?.nameRes

    /** 取值 → 显示名；不在清单里时给出「N通道」兜底（与官方 `x9.e.f` 一致） */
    fun channelName(context: Context, value: Int, legacyApi: Boolean): String =
        channelNameRes(value, legacyApi)?.let { context.getString(it) }
            ?: context.getString(R.string.audio_channel_unknown, value)

    /** 显示名：音频焦点 */
    fun focusNameRes(requestFocus: Boolean): Int =
        if (requestFocus) R.string.audio_focus_on else R.string.audio_focus_off

    private fun prefs(context: Context) = context.applicationContext
        .getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
}
