package com.baidu.carlifevehicle.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs.CONFIG_SAVE_VIDEO_FILE
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.TAG
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.formatISO8601
import com.baidu.carlifevehicle.audio.player.source.AudioParams
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean


/**
 * 语音解码并播放的类。
 */
class AACDecoder(
    val context: CarLifeContext,
    val params: AudioParams,
    private val onPcmDecoded: (data: ByteArray) -> Unit
) : Thread() {

    companion object {
        const val WAIT_DECODE_TIMEOUT = 10000L
        const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        const val KEY_AAC_PROFILE = MediaCodecInfo.CodecProfileLevel.AACObjectLC
        const val WAIT_TIME = 10000
        const val FREQ_IDX = 4
    }

    private val messageQueue = LinkedBlockingQueue<CarLifeMessage>()

    private val mDecoder: MediaCodec

    private var isStopped = AtomicBoolean(false)

    // for debug
    private var pcmOutput: OutputStream? = null

    private val mBufferInfo = MediaCodec.BufferInfo()

    init {
        name = "AACDecoder"
        mDecoder = createDecoder()

        if (context.getConfig(CONFIG_SAVE_VIDEO_FILE, false)) {
            val dir = File(context.cacheDir, "AACDecoder")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            pcmOutput = FileOutputStream(File(dir, Date().formatISO8601() + ".pcm"))
        }
    }

    fun feedFrame(message: CarLifeMessage) {
        if (!isStopped.get()) {
            message.acquire()
            messageQueue.offer(message)
//            Logger.v(Constants.TAG, "AACDecoder messageQueue size ${messageQueue.size}")
        }
    }

    fun release() {
        isStopped.set(true)
    }

    var presentationTimeUs: Long = 0
    override fun run() {
        Logger.d(Constants.TAG, "AACDecoder started ", this)
        var frameCount = 0L
        val bufferInfo = MediaCodec.BufferInfo()

        mDecoder.start()
        while (!isStopped.get()) {
            val frame = messageQueue.poll(200, TimeUnit.MILLISECONDS) ?: continue

            if (frame.payloadSize < 20) continue
            var remaining = frame.payloadSize
            var consumed = 0

            while (!isStopped.get() && remaining > 0) {
                var index: Int = mDecoder.dequeueInputBuffer(WAIT_DECODE_TIMEOUT)
                if (index < 0) continue
                while (!isStopped.get() && index < 0) {
                    // should not be here
                    flushBufferedQueue(bufferInfo)
                    index = mDecoder.dequeueInputBuffer(WAIT_DECODE_TIMEOUT)
                }

                val inputBuffer = mDecoder.getInputBuffer(index)!!

                inputBuffer.clear()

                val offset = inputBuffer!!.position()
                val inputSize = inputBuffer.remaining().coerceAtMost(remaining)
                inputBuffer.put(frame.body, frame.commandSize + consumed, inputSize)
                mDecoder.queueInputBuffer(index, offset, inputSize,System.nanoTime() / 1000, 0)

                presentationTimeUs += 1

                remaining -= inputSize
                consumed += inputSize

                var outputIndex =
                    mDecoder.dequeueOutputBuffer(mBufferInfo, WAIT_TIME.toLong())

                var outputBuffer: ByteBuffer?
                //每次解码完成的数据不一定能一次吐出 所以用while循环，保证解码器吐出所有数据
                while (outputIndex >= 0) {

                    if ((mBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        Logger.d(TAG, "audio encoder: codec config buffer")
                        mDecoder.releaseOutputBuffer(outputIndex, false)
                        continue
                    }

                    if (mBufferInfo.size != 0) {
                        outputBuffer = mDecoder.getOutputBuffer(outputIndex)
                        val mPcmData = ByteArray(mBufferInfo.size)
                        //提取数据到mPcmData
                        outputBuffer!![mPcmData, 0, mBufferInfo.size]
                        outputBuffer.clear() //数据取出后一定记得清空此Buffer MediaCodec是循环使用这些Buffer的，不清空下次会得到同样的数据
                        //播放音乐
                        onPcmDecoded.invoke(mPcmData.copyOfRange(0, mBufferInfo.size))
                    }

                    mDecoder.releaseOutputBuffer(
                        outputIndex,
                        false
                    )

                    // 此操作一定要做，不然MediaCodec用完所有的Buffer后 将不能向外输出数据
                    outputIndex = mDecoder.dequeueOutputBuffer(
                        mBufferInfo,
                        WAIT_TIME.toLong()
                    )
                    //再次获取数据，如果没有数据输出则outputIndex=-1 循环结束
                }

            }

            frame.recycle()

            flushBufferedQueue(bufferInfo)

            ++frameCount
        }

        mDecoder.release()
        pcmOutput?.let {
            it.flush()
            it.close()
        }
        while (messageQueue.isNotEmpty()) {
            messageQueue.poll()?.recycle()
        }
        Logger.d(Constants.TAG, "AACDecoder released ", this)
    }

    private fun computePresentationTime(frameIndex: Long): Long {
//            return 0;
        return frameIndex * 90000 * 1024 / params.sampleRate
    }

    private fun flushBufferedQueue(bufferInfo: MediaCodec.BufferInfo) {
        var index: Int = mDecoder.dequeueOutputBuffer(bufferInfo, 0)
        while (index >= 0) {
            mDecoder.releaseOutputBuffer(index, true)
            index = mDecoder.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun createDecoder(): MediaCodec {
        var mDecoder: MediaCodec? = null
        try {
            mDecoder = MediaCodec.createDecoderByType(MIME_TYPE)
            val format = MediaFormat()
            //解码配置
            format.setString(MediaFormat.KEY_MIME, MIME_TYPE)
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, params.channelCount)
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, params.sampleRate)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 4 * params.sampleRate)
            format.setInteger(MediaFormat.KEY_IS_ADTS, 1)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, KEY_AAC_PROFILE)

            val csd = ByteBuffer.allocate(2)
            csd.put(
                0,
                ((KEY_AAC_PROFILE shl 3) or (FREQ_IDX shr 1)).toByte()
            )
            csd.put(
                1,
                (((FREQ_IDX and 0x01) shl 7) or (params.channelCount shl 3)).toByte()
            )
            format.setByteBuffer("csd-0", csd)
            mDecoder.configure(format, null, null, 0)
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "AACDecoder createEncoder exception: $e")
            mDecoder?.release()
            throw e
        }
        return mDecoder
    }
}