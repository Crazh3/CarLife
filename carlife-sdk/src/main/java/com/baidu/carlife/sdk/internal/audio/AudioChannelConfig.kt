package com.baidu.carlife.sdk.internal.audio

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

/**
 * 音频通道参数（媒体 / 导航 / 语音助手）：车机端本地播放（AudioTrack 构造、音频焦点请求）
 * 用到的三组参数，与官方车机端 App（`com.baidu.carlifevehicle.beta`）设置页里的
 * 「音频通道」「版本（新版/旧版）」「音频焦点」一一对应。
 *
 * 官方配置项与默认值（反编译得到，`y7.g.<clinit>`）：
 * ```
 * AUDIO_MEDIA_REQUEST_FOCUS   boolean  默认 true
 * AUDIO_MEDIA_LEGACY_API      boolean  默认 SDK_INT < 23
 * AUDIO_MEDIA_CHANNEL         int      默认 legacy ? 3 : 1    3=STREAM_MUSIC, 1=USAGE_MEDIA
 * AUDIO_TTS_REQUEST_FOCUS     boolean  默认 true
 * AUDIO_TTS_LEGACY_API        boolean  默认 SDK_INT < 23
 * AUDIO_TTS_CHANNEL           int      默认 legacy ? 3 : 12   3=STREAM_MUSIC,
 *                                                             12=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
 * ```
 * 「版本」= 用旧版接口（`AudioTrack(streamType, ...)` /
 * `AudioManager.requestAudioFocus(listener, streamType, hint)`）还是新版接口
 * （`AudioTrack.Builder` + `AudioAttributes` / `AudioFocusRequest`）；
 * 「音频通道」= 旧版时的 streamType、新版时的 usage。
 *
 * 值由设置页写入（见 vehicle-app 的 AudioChannelPrefs），这里只保存当前生效值，
 * 供 [AudioFocusManager] 与 AudioPlayTask 读取。
 */
@DoNotStrip
object AudioChannelConfig {

    /** 与 [AudioFocusManager] 的 stream 标识保持一致 */
    const val CHANNEL_MEDIA = AudioFocusManager.STREAM_MUSIC
    const val CHANNEL_NAVI = AudioFocusManager.STREAM_NAVI
    const val CHANNEL_VR = AudioFocusManager.STREAM_VR

    /** 官方默认：Android 6.0 (M, API 23) 以下走旧版音频接口 */
    val DEFAULT_LEGACY_API: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.M

    /** 官方默认：媒体通道 旧版=STREAM_MUSIC(3) / 新版=USAGE_MEDIA(1) */
    fun defaultMediaChannel(legacy: Boolean): Int =
        if (legacy) AudioManager.STREAM_MUSIC else AudioAttributes.USAGE_MEDIA

    /** 官方默认：导航通道 旧版=STREAM_MUSIC(3) / 新版=USAGE_ASSISTANCE_NAVIGATION_GUIDANCE(12) */
    fun defaultNaviChannel(legacy: Boolean): Int =
        if (legacy) AudioManager.STREAM_MUSIC else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE

    /** 媒体音频：是否请求音频焦点 */
    @Volatile
    var mediaRequestFocus: Boolean = true

    /** 媒体音频：是否使用旧版接口 */
    @Volatile
    var mediaLegacyApi: Boolean = DEFAULT_LEGACY_API

    /** 媒体音频：具体音频通道 */
    @Volatile
    var mediaChannel: Int = defaultMediaChannel(DEFAULT_LEGACY_API)

    /** 导航音频：是否请求音频焦点 */
    @Volatile
    var naviRequestFocus: Boolean = true

    /** 导航音频：是否使用旧版接口 */
    @Volatile
    var naviLegacyApi: Boolean = DEFAULT_LEGACY_API

    /** 导航音频：具体音频通道 */
    @Volatile
    var naviChannel: Int = defaultNaviChannel(DEFAULT_LEGACY_API)

    /** 语音助手音频：官方也有独立设置项，本项目设置页暂未暴露，保持默认值 */
    @Volatile
    var vrRequestFocus: Boolean = true

    @Volatile
    var vrLegacyApi: Boolean = DEFAULT_LEGACY_API

    @Volatile
    var vrChannel: Int = defaultMediaChannel(DEFAULT_LEGACY_API)

    fun legacyApi(stream: Int): Boolean = when (stream) {
        CHANNEL_NAVI -> naviLegacyApi
        CHANNEL_VR -> vrLegacyApi
        else -> mediaLegacyApi
    }

    fun channelValue(stream: Int): Int = when (stream) {
        CHANNEL_NAVI -> naviChannel
        CHANNEL_VR -> vrChannel
        else -> mediaChannel
    }

    fun requestFocus(stream: Int): Boolean = when (stream) {
        CHANNEL_NAVI -> naviRequestFocus
        CHANNEL_VR -> vrRequestFocus
        else -> mediaRequestFocus
    }

    // ---------------- 通道值 → AudioAttributes ----------------

    /** 该路音频在「新版接口」下的默认 usage */
    fun defaultUsageFor(stream: Int): Int = when (stream) {
        CHANNEL_NAVI -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
        else -> AudioAttributes.USAGE_MEDIA
    }

    /** 新版接口：通道值就是 usage */
    fun attributesForUsage(usage: Int): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    /**
     * 旧版接口：通道值是 streamType —— 与 `MediaPlayer.setAudioStreamType()` /
     * 旧版 `AudioTrack(streamType, ...)` 走同一套转换（`AudioAttributes.setLegacyStreamType`）。
     *
     * 平台不认这个值（例如旧版下填了 12 / 14 这类 usage 数值）时抛
     * `IllegalArgumentException`，返回 null 让调用方兜底。
     */
    fun attributesForLegacyOrNull(streamType: Int): AudioAttributes? = try {
        AudioAttributes.Builder().setLegacyStreamType(streamType).build()
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "invalid legacy streamType $streamType: $e")
        null
    }

    /**
     * 播放 / 试听 / 焦点请求统一使用的一份 [AudioAttributes]。
     *
     * 之前三者各自解析通道值（播放侧自己 new AudioAttributes、试听用过时的
     * `MediaPlayer.setAudioStreamType`），同一个设置值解析结果不一致，
     * 于是出现「弹窗里试听是这个通道、真正导航播报走的却是另一条通道」。
     *
     * @param legacyApi 是否旧版接口
     * @param value     设置里的通道值（旧版=streamType，新版=usage）
     * @param stream    音频分组，仅用于兜底时取该路的默认 usage
     */
    fun attributes(legacyApi: Boolean, value: Int, stream: Int): AudioAttributes {
        // 旧版：channelValue 是 streamType，交给平台按 legacy 规则映射成 usage；
        // 平台不认这个值就退回该路音频的默认 usage（不能退回 STREAM_MUSIC 就完事，
        // 那样会和「试听」解析出来的结果不一致）。
        if (legacyApi) {
            attributesForLegacyOrNull(value)?.let { return it }
        } else {
            try {
                return attributesForUsage(value)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "invalid usage $value for stream $stream: $e")
            }
        }
        return attributesForUsage(defaultUsageFor(stream))
    }

    /** 按当前生效设置解析 [attributes] */
    fun attributes(stream: Int): AudioAttributes =
        attributes(legacyApi(stream), channelValue(stream), stream)

    /**
     * 旧版焦点请求用的 streamType（`AudioManager.requestAudioFocus(listener, streamType, hint)`，
     * API < 26）：能用设置值就用，否则退回 STREAM_MUSIC，避免传非法值抛异常打断 TTS 播报。
     */
    fun legacyStreamType(stream: Int): Int {
        val value = channelValue(stream)
        return if (attributesForLegacyOrNull(value) != null) value
        else AudioManager.STREAM_MUSIC
    }

    private const val TAG = "AudioChannelConfig"
}
