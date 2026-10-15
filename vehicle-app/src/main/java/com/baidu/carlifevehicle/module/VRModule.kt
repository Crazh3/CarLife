package com.baidu.carlifevehicle.module

import android.media.AudioManager
import com.baidu.carlife.protobuf.CarlifeTTSInitProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.CarLifeModule
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.CarLifeContextImpl
import com.baidu.carlife.sdk.internal.audio.AudioFocusManager
import com.baidu.carlifevehicle.audio.player.AudioPlayer
import com.baidu.carlifevehicle.audio.player.source.AudioParams
import com.baidu.carlifevehicle.audio.player.source.AudioSource
import com.baidu.carlifevehicle.audio.player.source.impl.PcmMemorySource
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlifevehicle.audio.CarLifeStreamSource
import com.baidu.carlife.sdk.util.Logger

class VRModule(
    private val context: CarLifeContext
) : CarLifeModule(), AudioManager.OnAudioFocusChangeListener, AudioPlayer.Callbacks {
    private val player = AudioPlayer(context, Constants.MSG_CHANNEL_AUDIO_VR, true, this)

    private var source: CarLifeStreamSource? = null

    override val id: Int = Constants.VR_MODULE_ID

    private val shortSource: AudioSource by lazy {
        val fd = context.applicationContext.assets.openFd("bdspeech_recognition_start.pcm")
        val params = AudioParams.from(16000, 1, 16)
        PcmMemorySource.fromAsset(params, fd).apply {
            streamType = AudioFocusManager.STREAM_VR
            focusType = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }
    }

    override fun onModuleStateChanged(newState: Int, oldState: Int) {
        Logger.d(Constants.TAG, "VRModule onModuleStateChanged ", oldState, "->", newState)
        // receiver 端不需要把状态变化分发给CarLifeContext，直接自己处理
        when (newState) {
            Constants.VR_STATUS_RUNNING -> {
                // 请求音频焦点
                (context as CarLifeContextImpl).audioFocusManager.requestAudioFocus(
                    this,
                    AudioFocusManager.STREAM_VR,
                    // 语音使用此标记，可以禁止系统发声
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
            Constants.VR_STATUS_IDLE -> {
                (context as CarLifeContextImpl).audioFocusManager.abandonAudioFocus(this)
                // 如果CarLife通知释放焦点，则停止播放，
                // 对于MusicModule此处不能停止播放，因为音乐有暂停、继续的操作
                // 这样也可能有问题,如果播放百科这种比较长的语音，会导致未播放完成就停止了
                stop()
            }
        }
    }

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        // 只处理语音通道的消息
        if (message.channel == Constants.MSG_CHANNEL_AUDIO_VR) {
            when (message.serviceType) {
                ServiceTypes.MSG_VR_AUDIO_INIT -> {
                    handleVrInit(message)
                    state = Constants.VR_STATUS_RUNNING
                }
                ServiceTypes.MSG_VR_AUDIO_STOP -> {
                    end()
                    state = Constants.VR_STATUS_IDLE
                }
                ServiceTypes.MSG_VR_AUDIO_INTERRUPT -> {
                    stop()
                    state = Constants.VR_STATUS_IDLE
                }
                ServiceTypes.MSG_VR_AUDIO_DATA -> source?.feed(message)
            }
        }

        //增加对车机端叮一声的处理
        if (message.serviceType == ServiceTypes.MSG_CMD_MIC_RECORD_PREPARE_START) {
            state = Constants.VR_STATUS_IDLE
            player.play(shortSource)
        }

        return false
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        // 连接断开之后，需要释放焦点
        context.abandonAudioFocus(this)

        // 连接断开之后 stop掉
        stop()
    }

    // AudioManager.OnAudioFocusChangeListener
    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 应该不会走到这里，除非车机的其他应用抢占焦点，
                // 只要丢失，不管长短，直接停止播放
                player.stop()
            }
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
    private fun handleVrInit(message: CarLifeMessage) {
        source?.close()
        val payload = message.protoPayload as CarlifeTTSInitProto.CarlifeTTSInit
        source = CarLifeStreamSource.from(context, payload)
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
        Logger.d(Constants.TAG, "VRModule onFinish ", source)
        if (source === shortSource) {
            context.postMessage(
                Constants.MSG_CHANNEL_CMD,
                ServiceTypes.MSG_CMD_MIC_RECORD_PREPARE_DONE
            )
        }
    }

    override fun onError(source: AudioSource, errorCode: Int, errorMessage: String) {
        Logger.d(Constants.TAG, "VRModule onError ", source)
        if (source === shortSource) {
            context.postMessage(
                Constants.MSG_CHANNEL_CMD,
                ServiceTypes.MSG_CMD_MIC_RECORD_PREPARE_DONE
            )
        }
    }
}