package com.baidu.carlifevehicle.audio

import android.media.AudioAttributes
import android.media.AudioManager

/**
 * 声音通道取值表（媒体 / 导航共用）。
 *
 * 版本：
 *  - 旧版（MODE_LEGACY）：AudioTrack 旧构造函数的 streamType，0~19
 *    （0~10 为系统定义，11 以上为厂商自定义通道，直接透传数字）
 *  - 新版（MODE_NEW）   ：AudioAttributes 的 usage，0~19
 *
 * 说明：与 apollo-DuerOS1 的 NavAudioChannelConfig 保持一致，仅把取值表抽出来
 * 供"媒体声音通道 / 导航声音通道"共用。
 */
object AudioChannelTables {

    /** 旧版：AudioTrack(streamType, ...) */
    const val MODE_LEGACY = 0

    /** 新版：AudioAttributes usage */
    const val MODE_NEW = 1

    /** 旧版 streamType 列表（0~19） */
    val legacyChannels: List<Pair<Int, String>> = listOf(
        AudioManager.STREAM_VOICE_CALL to "通话",
        AudioManager.STREAM_SYSTEM to "系统",
        AudioManager.STREAM_RING to "铃声",
        AudioManager.STREAM_MUSIC to "媒体",
        AudioManager.STREAM_ALARM to "闹钟",
        AudioManager.STREAM_NOTIFICATION to "通知",
        6 to "蓝牙SCO",
        7 to "通道7",
        AudioManager.STREAM_DTMF to "拨号音",
        9 to "导航",
        AudioManager.STREAM_ACCESSIBILITY to "无障碍",
        11 to "通道11",
        12 to "通道12",
        13 to "通道13",
        14 to "通道14",
        15 to "通道15",
        16 to "通道16",
        17 to "通道17",
        18 to "通道18",
        19 to "通道19"
    )

    /** 新版 AudioAttributes usage 列表（0~19） */
    val newChannels: List<Pair<Int, String>> = listOf(
        AudioAttributes.USAGE_UNKNOWN to "通道0",
        AudioAttributes.USAGE_MEDIA to "媒体",
        AudioAttributes.USAGE_VOICE_COMMUNICATION to "通话",
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING to "拨号音",
        AudioAttributes.USAGE_ALARM to "闹钟",
        AudioAttributes.USAGE_NOTIFICATION to "通知",
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE to "铃声",
        7 to "通道7",
        8 to "通道8",
        9 to "通道9",
        10 to "通道10",
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY to "通道11",
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE to "导航",
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION to "系统",
        AudioAttributes.USAGE_GAME to "游戏",
        15 to "通道15",
        AudioAttributes.USAGE_ASSISTANT to "通道16",
        17 to "通道17",
        18 to "通道18",
        19 to "通道19"
    )

    /** 取指定版本下的通道列表 */
    fun channels(mode: Int): List<Pair<Int, String>> =
        if (mode == MODE_LEGACY) legacyChannels else newChannels

    /** 版本中文名 */
    fun modeName(mode: Int): String = if (mode == MODE_LEGACY) "旧版" else "新版"

    /** 通道数字对应的名称，找不到返回 null */
    fun channelName(mode: Int, value: Int): String? =
        channels(mode).firstOrNull { it.first == value }?.second
}
