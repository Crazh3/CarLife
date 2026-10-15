package com.baidu.carlifevehicle.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioTrack.PLAYSTATE_PLAYING
import com.baidu.carlife.protobuf.CarlifeMusicInitProto
import com.baidu.carlife.protobuf.CarlifeTTSInitProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.CircularByteBuffer
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.audio.player.source.AudioParams
import com.baidu.carlifevehicle.audio.player.source.AudioSource
import com.baidu.carlifevehicle.audio.player.source.ResizableArray
import java.util.*

class CarLifeStreamSource(
    private val context: CarLifeContext,
    val params: AudioParams,
    storageSize: Int
) : AudioSource {
    companion object {
        fun from(
            context: CarLifeContext,
            payload: CarlifeMusicInitProto.CarlifeMusicInit
        ): CarLifeStreamSource {
            val params =
                AudioParams(payload.sampleRate, payload.channelConfig, payload.sampleFormat)
            // 音乐通道数据量大，100K缓存
            return CarLifeStreamSource(context, params, 100 * 1024)
        }

        fun from(
            context: CarLifeContext,
            payload: CarlifeTTSInitProto.CarlifeTTSInit
        ): CarLifeStreamSource {
            val params =
                AudioParams(payload.sampleRate, payload.channelConfig, payload.sampleFormat)
            return CarLifeStreamSource(context, params, 32 * 1024)
        }
    }

    private val data = CircularByteBuffer(storageSize, false)
    private val messageDelayQueue = LinkedList<CarLifeMessage>()

    private var position: Long = 0
    private var isClosed = false

    // 车机端AudioSource不用处理焦点逻辑，由对应的module来解决
    override val streamType: Int = AudioManager.STREAM_MUSIC
    override val focusType: Int = AudioManager.AUDIOFOCUS_NONE
    override val allowFocusDelayed: Boolean = false
    override val forceGrantFocus: Boolean = false

    override var tag: Any? = null

    private val mPlayer: AudioTrack = AudioTrack(
        AudioManager.STREAM_MUSIC,
        params.sampleRate,
        params.channelConfig,
        AudioFormat.ENCODING_PCM_16BIT,
        AudioTrack.getMinBufferSize(
            params.sampleRate,
            params.channelConfig,
            params.audioFormat
        ),
        AudioTrack.MODE_STREAM
    )

    private var aacAudioDecoder: AACDecoder = AACDecoder(context, params) {
        if (!isClosed) {

            while (!isClosed) {
                if (data.avaliable() < it.size) {
                    Thread.sleep(10)
                    continue
                }
                data.write(it, 0, it.size)
                break
            }
            if (mPlayer.playState != PLAYSTATE_PLAYING) {
                mPlayer.play()
            }
        }
    }

    @Synchronized
    fun feed(message: CarLifeMessage, encoded: Boolean = false) {

        if (message.payloadSize == 0) {
            return
        }

        if (encoded) {
            if (!aacAudioDecoder.isAlive) {
                try {
                    aacAudioDecoder.start()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            aacAudioDecoder.feedFrame(message)
        } else if (messageDelayQueue.isEmpty() && data.avaliable() >= message.payloadSize) {
            data.write(message.body, message.commandSize, message.payloadSize)
        } else {
            // 增加引用，防止被回收，对于TTS才有可能进入到这里，因为手机端发数据根本没有做帧延迟，一股脑丢过来了
            // 编码过的message 直接放入messageDelayQueue
            message.acquire()
            messageDelayQueue.offer(message)
        }
    }

    @Synchronized
    private fun feedDelayed() {
        Logger.d(Constants.TAG, "feedDelayed start")
        while (true) {
            val message = messageDelayQueue.peek()
            if (message == null || data.avaliable() < message.payloadSize) {
                break
            }
            // Logger.d(Constants.TAG, "CarLifeStreamSource write delayed message ", message.payloadSize)
            data.write(message.body, message.commandSize, message.payloadSize)
            messageDelayQueue.poll()
            message.recycle()
        }

    }

    fun end() {
        data.end()
    }

    override fun params(): AudioParams {
        // 缓存一半之后开始播放，避免播放卡顿
        data.blockUntil(data.capacity / 2)
        return params
    }

    override fun currentPosition(): Long {
        return position
    }

    /**
     * 关闭数据流，与end不同，关闭之后就读不到任何数据了
     */
    @Synchronized
    override fun close() {
        if (!isClosed) {
            isClosed = true
            aacAudioDecoder.release()
            // 必须要释放这些消息
            messageDelayQueue.forEach { it.recycle() }
            messageDelayQueue.clear()

        }
    }

    override fun isClosed(): Boolean {
        return isClosed
    }

    override fun drainTo(buffer: ResizableArray, timeout: Long): Int {
        throw UnsupportedOperationException("CarLifeStreamSource don't support drainTo")
    }

    override fun readFrame(buffer: ResizableArray, offset: Int, timeout: Long): Int {
        if (isClosed) {
            return -1
        }

        if (messageDelayQueue.isNotEmpty() && data.size < params.frameSize) {
            context.compute().execute { feedDelayed() }
        }

        if (data.empty()) {
            // 有数据才读，没有的话，等待有数据
            data.blockUntil(1, timeout)
        }

        buffer.fill(data, timeout, false)
        return buffer.size
    }
}