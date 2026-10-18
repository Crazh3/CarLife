package com.baidu.carlifevehicle.util

import android.content.Context
import android.media.MediaPlayer
import android.widget.Toast
import com.baidu.carlife.sdk.internal.audio.AudioChannelConfig
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.R

/**
 * 「音频通道」试听：点一下通道，就用这个通道播一段音频，用来判断它在你的车机上出不出声。
 *
 * 与官方车机端 App 一致（反编译 `w7.g`「【音频测试】launchPlayback」与 `d9.m.a`）：
 *  - 音频源就是官方包里那段试听音 `assets/audio_test.aac`（原样打包进 `res/raw/audio_test.aac`）；
 *    官方是 `AssetManager.openFd("audio_test.aac")` + MediaExtractor 解码后用 AudioTrack 播，
 *    这里交给 MediaPlayer 解码播放（同样是指定通道出声，省掉手写解码）；
 *  - 通道：旧版 `setAudioStreamType(通道值)`；新版（SDK >= 23）`AudioAttributes.usage = 通道值`；
 *  - 播完自动 release；通道值在这个车机上不被接受时提示「音频通道 N 无效」。
 */
object AudioChannelPreview {

    private const val TAG = "AudioChannelPreview"

    private var player: MediaPlayer? = null

    /**
     * 用「当前版本 + 指定通道」播一段试听音。
     *
     * @param legacyApi true = 旧版接口（streamType），false = 新版接口（usage）
     * @param channel   「音频通道」的取值（0~23）
     */
    fun play(context: Context, legacyApi: Boolean, channel: Int, navi: Boolean) {
        stop()
        val mp = MediaPlayer()
        player = mp
        mp.setOnCompletionListener { stop() }
        mp.setOnErrorListener { _, what, extra ->
            Logger.e(TAG, "play error what=", what, " extra=", extra,
                " channel=", channel, " legacy=", legacyApi)
            toastInvalid(context, channel)
            stop()
            true
        }
        try {
            val afd = context.resources.openRawResourceFd(R.raw.audio_test)
            afd.use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            // 与真正播放（AudioPlayTask）/ 焦点请求（AudioFocusManager）同一份解析结果，
            // 保证「试听听到的通道」就是「导航播报实际走的通道」
            val stream =
                if (navi) AudioChannelConfig.CHANNEL_NAVI
                else AudioChannelConfig.CHANNEL_MEDIA
            mp.setAudioAttributes(AudioChannelConfig.attributes(legacyApi, channel, stream))
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
            Logger.d(TAG, "play channel=", channel, " legacy=", legacyApi)
        } catch (e: Throwable) {
            Logger.e(TAG, "play failed channel=", channel, " legacy=", legacyApi, " : ", e.message)
            toastInvalid(context, channel)
            stop()
        }
    }

    /** 停止并释放（切通道、关闭弹窗时调用，避免还在响） */
    fun stop() {
        val p = player ?: return
        player = null
        try {
            if (p.isPlaying) {
                p.stop()
            }
        } catch (ignored: Throwable) {
            // 已经处于异常状态时 stop 会抛，忽略
        }
        try {
            p.release()
        } catch (ignored: Throwable) {
        }
    }

    private fun toastInvalid(context: Context, channel: Int) {
        Toast.makeText(
            context,
            context.getString(R.string.audio_channel_invalid, channel),
            Toast.LENGTH_SHORT
        ).show()
    }
}
