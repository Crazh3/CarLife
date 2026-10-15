package com.baidu.carlifevehicle.audio.player.source

import android.media.AudioFormat
import android.media.MediaFormat
import com.baidu.carlife.sdk.util.annotations.DoNotStrip

@DoNotStrip
data class AudioParams(
    // 采样率
    var sampleRate: Int,
    // 通道数
    var channelCount: Int,
    // 采样精度
    val bitDepth: Int,

    // 总时长，-1表示没有时长
    var duration: Long = -1
) {

    companion object {
        fun from(format: MediaFormat): AudioParams {
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            // 微妙，百万分之一秒，转换成毫秒
            val duration = format.getLong(MediaFormat.KEY_DURATION) / 1000
            // 精度现在一般都是16位的，默认写死
            return AudioParams(sampleRate, channelCount, 16, duration)
        }

        fun from(sampleRate: Int, channelCount: Int, bitDepth: Int): AudioParams {
            return AudioParams(sampleRate, channelCount, bitDepth)
        }
    }

    val useStaticMode: Boolean get() {
        return duration in 1..1000
    }

    // 1s音频数据大小
    val frameSize: Int get() = sampleRate * channelCount * bitDepth / 8

    val channelConfig: Int
        get() =
            if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO
            else AudioFormat.CHANNEL_OUT_STEREO

    val audioFormat: Int get() =
        if (bitDepth == 16) AudioFormat.ENCODING_PCM_16BIT
        else AudioFormat.ENCODING_PCM_8BIT

    // 一共有多少字节的pcm，粗略值，可用于创建buffer使用
    val totalBytes: Int get() {
        return (duration * frameSize / 1000f).toInt() + frameSize
    }

    fun update(format: MediaFormat) {
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    }

    fun durationToBytes(duration: Long): Long {
        return (duration * frameSize / 1000f).toLong()
    }

    fun bytesToDuration(bytes: Long): Long {
        return ((bytes / frameSize.toFloat()) * 1000).toLong()
    }
}