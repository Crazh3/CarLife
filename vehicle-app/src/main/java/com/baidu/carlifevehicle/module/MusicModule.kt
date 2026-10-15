package com.baidu.carlifevehicle.module

import android.media.AudioManager
import com.baidu.carlife.protobuf.CarlifeModuleStatusProto
import com.baidu.carlife.protobuf.CarlifeMusicInitProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.CarLifeModule
import com.baidu.carlife.sdk.Configs
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MUSIC_STATUS_IDLE
import com.baidu.carlife.sdk.Constants.MUSIC_STATUS_RUNNING
import com.baidu.carlife.sdk.internal.CarLifeContextImpl
import com.baidu.carlife.sdk.internal.audio.AudioFocusManager
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.audio.CarLifeStreamSource
import com.baidu.carlifevehicle.audio.player.AudioPlayer
import com.baidu.carlifevehicle.audio.player.source.AudioSource

class MusicModule(private val context: CarLifeContext): CarLifeModule(),
    AudioManager.OnAudioFocusChangeListener,
    AudioPlayer.Callbacks {

    private val player = AudioPlayer(context, Constants.MSG_CHANNEL_AUDIO, true, this)
    private var focusDelayed = false

    private var source: CarLifeStreamSource? = null

    override val id: Int = Constants.MUSIC_MODULE_ID

    override fun onModuleStateChanged(newState: Int, oldState: Int) {
        Logger.d(Constants.TAG, "MusicModule onModuleStateChanged ", oldState, "->", newState)
        // receiver 端不需要把状态变化分发给CarLifeContext，直接自己处理
        when (newState) {
            Constants.MUSIC_STATUS_RUNNING -> {
                // 请求音频焦点
                focusDelayed = (context as CarLifeContextImpl).audioFocusManager.requestAudioFocus(
                    this,
                    AudioFocusManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN,
                    focusGrantDelayed = true
                ) == AudioFocusManager.AUDIOFOCUS_REQUEST_DELAYED
            }
            Constants.MUSIC_STATUS_IDLE -> {
                (context as CarLifeContextImpl).audioFocusManager.abandonAudioFocus(this)
            }
        }

    }

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        // 只处理音乐通道的消息
        if (message.channel == Constants.MSG_CHANNEL_AUDIO) {
            when (message.serviceType) {
                ServiceTypes.MSG_MEDIA_INIT -> {
                    handleMediaInit(message)
                    state = MUSIC_STATUS_RUNNING
                }
                ServiceTypes.MSG_MEDIA_PAUSE -> {
                    player.pause()
                    state = MUSIC_STATUS_IDLE
                }
                ServiceTypes.MSG_MEDIA_RESUME_PLAY -> {
                    player.resume()
                    state = MUSIC_STATUS_RUNNING
                }
                ServiceTypes.MSG_MEDIA_STOP -> {
                    end()
                    state = MUSIC_STATUS_IDLE
                }
                ServiceTypes.MSG_MEDIA_DATA -> source?.feed(message)
                ServiceTypes.MSG_MEDIA_DATA_ENCODER -> source?.feed(message, true)
            }
        }
        return false
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        // 连接断开之后，需要释放焦点
        context.abandonAudioFocus(this)
        // stop current source
        stop()
    }

    override fun onConnectionReattached(context: CarLifeContext) {
        stop()
    }

    // AudioManager.OnAudioFocusChangeListener
    override fun onAudioFocusChange(focusChange: Int) {
        // 如果焦点发生变化，清空delay标记
        focusDelayed = false
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                player.unduck()
                player.resume()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                player.pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                player.duck()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 应该不会走到这里，除非车机的其他应用抢占焦点
                player.stop()
                sendModuleControl(Constants.MUSIC_MODULE_ID, Constants.MUSIC_STATUS_IDLE)
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

    private fun handleMediaInit(message: CarLifeMessage) {
        source?.close()
        val payload = message.protoPayload as CarlifeMusicInitProto.CarlifeMusicInit
        source = CarLifeStreamSource.from(context, payload)
        if (focusDelayed) {
            // 如果是delay状态，比如VR交互未完成的情况下，音乐是不允许播放的
            player.prepare(source!!)
        }
        else {
            player.play(source!!)
        }
    }

    // AudioPlayer.Callbacks
    override fun onAudioChanged(source: AudioSource) {
    }

    override fun onStateChanged(source: AudioSource, state: Int) {
    }

    override fun onProgress(source: AudioSource, position: Long, duration: Long) {
    }

    override fun onFinish(source: AudioSource, completed: Boolean) {
    }

    override fun onError(source: AudioSource, errorCode: Int, errorMessage: String) {
    }

    private fun sendModuleControl(moduleId: Int, status: Int) {
        val message = CarLifeMessage.obtain(Constants.MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_MODULE_CONTROL)
        message.payload(
            CarlifeModuleStatusProto.CarlifeModuleStatus
                .newBuilder()
                .setModuleID(moduleId)
                .setStatusID(status)
                .build()
        )
        CarLife.receiver().postMessage(message)
    }
}