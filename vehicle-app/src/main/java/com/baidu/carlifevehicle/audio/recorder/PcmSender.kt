package com.baidu.carlifevehicle.audio.recorder

import android.os.Process
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_AUDIO_VR
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.util.CircularByteBuffer
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.audio.recorder.PcmRecorder.Companion.RECORD_DATA_PACKAGE_SIZE

class PcmSender(private var mContext: CarLifeContext?) : Thread() {
    private val mutex = Object()
    private val storageBuffer: CircularByteBuffer

    @Volatile
    var isRecording = false
        set(isRecording) {
            field = isRecording
            if (this.isRecording) {
                synchronized(mutex) {
                    if (this.isRecording) {
                        mutex.notifyAll()
                    }
                }
            }
        }
    private var isDownSample = false


    override fun run() {
        Logger.d(TAG, "pcmwriter thread runing")
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // RECORD_DATA_PACKAGE_SIZE 1024 录音的标准buffer大小
        val tempByteArray = ByteArray(RECORD_DATA_PACKAGE_SIZE)
        while (true) {
            if (isRecording) {
//                Logger.d(TAG, "pcmwriter thread ---startSend-")
                val size = storageBuffer.read(tempByteArray, 0, RECORD_DATA_PACKAGE_SIZE, 100)
//                Logger.d(TAG,"PcmSender size>>>", size)
                if (size > 0) {
                    sendData(tempByteArray)
                } else {
//                    Logger.d(TAG, "pcmwriter thread -sleep(100)-")
                    try {
                        sleep(100)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            } else {
                synchronized(mutex) {
                    try {
                        mutex.wait(5000)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun putData(buf: ByteArray?, size: Int) {
//        Logger.d(TAG, "-putData----listLen:", buf?.size ?: 0, "---dataSize:", size)
        if (size <= 0) {
            return
        }

        buf?.let { storageBuffer.write(buf, 0, size, 100) }
    }

    fun setDownSampleStatus(flag: Boolean) {
        isDownSample = flag
    }

    private fun sendData(data: ByteArray?): Int {
        if (data == null || data.isEmpty()) {
            return 0
        }
        var downSampleBuffer: ByteArray? = null
        var ret = 0
        /** 进行降采样16K->8K  */
        if (isDownSample) {
            downSampleBuffer = downSample(data)
            ret = sendAudioVRData(downSampleBuffer, data.size / 2)
        } else {
//            Logger.d(TAG, "pcmwriter thread -sendAudioVRData-")
            ret = sendAudioVRData(data, data.size)
        }
        return ret
    }

    /**
     * 降采样16K->8K
     */
    private fun downSample(rawData: ByteArray?): ByteArray? {
        if (rawData == null || rawData.isEmpty()) {
            return null
        }
        val downSampleBuffer = ByteArray(rawData.size / 2)
        var i = 0
        var j = 0
        while (i < rawData.size) {
            downSampleBuffer[j++] = rawData[i]
            downSampleBuffer[j++] = rawData[i + 1]
            i += 4
        }
        return downSampleBuffer
    }

    /**
     * 发送录音数据
     */
    private fun sendAudioVRData(audioData: ByteArray?, length: Int): Int {
        val message = CarLifeMessage.obtain(
                MSG_CHANNEL_AUDIO_VR,
                ServiceTypes.MSG_VR_DATA
        )
        audioData?.let {
            message.payload(audioData, 0, length)
            mContext?.postMessage(message)
        }

        return 0
    }


    companion object {
        private const val TAG = "CarLifeVoice"
        private const val BUFFER_MAX_SIZE = 320
    }

    init {
        Logger.d(TAG, "-----PcmSender()---------")
        storageBuffer = CircularByteBuffer()
    }
}