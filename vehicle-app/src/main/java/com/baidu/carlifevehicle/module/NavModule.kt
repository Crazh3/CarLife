package com.baidu.carlifevehicle.module

import android.media.AudioManager
import com.baidu.carlife.protobuf.CarlifeTTSInitProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.CarLifeModule
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.NAVI_STATUS_START
import com.baidu.carlife.sdk.internal.CarLifeContextImpl
import com.baidu.carlife.sdk.internal.audio.AudioFocusManager
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.audio.CarLifeStreamSource
import com.baidu.carlifevehicle.audio.player.AudioPlayer
import com.baidu.carlifevehicle.audio.player.source.AudioSource

class NavModule(private val context: CarLifeContext)
    : CarLifeModule(),
    AudioManager.OnAudioFocusChangeListener,
    AudioPlayer.Callbacks {
    private val player = AudioPlayer(context, Constants.MSG_CHANNEL_AUDIO_TTS, true, this)

    private var source: CarLifeStreamSource? = null

    override val id: Int = Constants.NAVI_MODULE_ID

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        // 只处理导航音频通道的消息
        if (message.channel == Constants.MSG_CHANNEL_AUDIO_TTS) {
            when (message.serviceType) {
                ServiceTypes.MSG_NAV_TTS_INIT -> {
                    handleNavTtsInit(message)
                    state = NAVI_STATUS_START
                }
                ServiceTypes.MSG_NAV_TTS_END -> {
                    end()
                    state = Constants.NAVI_STATUS_IDLE
                }
                ServiceTypes.MSG_NAV_TTS_DATA -> source?.feed(message)
            }
        }
        return false
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        // 连接断开之后，需要释放焦点
        (context as CarLifeContextImpl).audioFocusManager.abandonAudioFocus(this)
        // stop current source
        stop()
    }

    // AudioManager.OnAudioFocusChangeListener
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            // 只要失去焦点，就停止播放，与音乐不同的是，不用考虑恢复机制，
            // 因为导航TTS都是比较短，并且实时的
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> player.stop()
        }
    }

    private fun end() {
        source?.let {
            it.end()
            source = null
        }
    }

    private fun stop() {
        source?.let {
            it.close()
            source = null
        }
    }

    // MusicChannelProcessor.Callback
    private fun handleNavTtsInit(message: CarLifeMessage) {
        source?.close()
        val payload = message.protoPayload as CarlifeTTSInitProto.CarlifeTTSInit
        source = CarLifeStreamSource.from(context, payload)
        // 播放新内容之前，请求一下焦点, 因为不像音乐有3，1和3，0可以用来切换焦点
        (context as CarLifeContextImpl).audioFocusManager.requestAudioFocus(
            this,
            AudioFocusManager.STREAM_NAVI,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        player.play(source!!)
    }

    // AudioPlayer.Callbacks
    override fun onAudioChanged(source: AudioSource) {
    }

    override fun onStateChanged(source: AudioSource, state: Int) {
    }

    override fun onProgress(source: AudioSource, position: Long, duration: Long) {
    }

    override fun onFinish(source: AudioSource, completed: Boolean) {
        context.abandonAudioFocus(this)
    }

    override fun onError(source: AudioSource, errorCode: Int, errorMessage: String) {
        Logger.e(Constants.TAG, "NavModule onError ", errorCode, " ", errorCode)
        context.abandonAudioFocus(this)
    }
}