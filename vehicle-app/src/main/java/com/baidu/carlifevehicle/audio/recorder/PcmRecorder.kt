package com.baidu.carlifevehicle.audio.recorder

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.util.Logger

class PcmRecorder(private var mContext: CarLifeContext?) : Thread() {
    @Volatile
    private var isRecording = false
    private val mutex = Object()
    private var mPcmSender: PcmSender? = null
    private var mRecordInstance: AudioRecord? = null

    init {
        Logger.d(TAG, "--new---PcmRecorder()--")
        mPcmSender = PcmSender(
            mContext
        ).apply { start() }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (mRecordInstance == null) {
            mRecordInstance = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                FREQUENCY,
                    AudioFormat.CHANNEL_IN_MONO,
                AUDIO_ENCODING,
                DEFAULT_BUFFER_SIZE
            )
        }
        var bufferRead = 0
        val tempBufferBytes = ByteArray(RECORD_DATA_PACKAGE_SIZE)
        var isStoped = false
        while (true) {
            if (!isRecording) {
                synchronized(mutex) {
                    if (!isRecording) {
                        try {
                            mutex.wait()
                        } catch (e: InterruptedException) {
                            throw IllegalStateException("Wait() interrupted!", e)
                        }
                    }
                }
            }
            try {
                mRecordInstance!!.startRecording()
            } catch (e: IllegalStateException) {
                Logger.e(TAG, "startRecording--error")
                // CarlifeUtil.showToastInUIThread("-启动录音失败，请检查MIC后重试-")
                break
            }
            mPcmSender!!.isRecording = true
            while (isRecording) {
                bufferRead = mRecordInstance!!.read(tempBufferBytes, 0,
                    RECORD_DATA_PACKAGE_SIZE
                )
                if (bufferRead == AudioRecord.ERROR_INVALID_OPERATION) {
                    isStoped = true
                    break
                } else if (bufferRead == AudioRecord.ERROR_BAD_VALUE) {
                    isStoped = true
                    break
                }
                mPcmSender!!.putData(tempBufferBytes, bufferRead)
            }
            mRecordInstance!!.stop()
            mPcmSender!!.isRecording = false
            if (isStoped) {
                break
            }
        }
        mRecordInstance!!.release()
        mRecordInstance = null
    }

    fun setRecording(flag: Boolean) {
        isRecording = flag
        if (isRecording) {
            synchronized(mutex) {
                if (isRecording) {
                    mutex.notify()
                }
            }
        }
    }

    fun isRecording(): Boolean {
        return isRecording
    }

    fun setDownSampleStatus(flag: Boolean) {
        mPcmSender?.setDownSampleStatus(flag)
    }

    companion object {
        private const val TAG = "CarLifeVoice"
        private const val RECORD_SAMPLE_RATE_8K = 8000
        private const val RECORD_SAMPLE_RATE_16K = 16000
        public const val RECORD_DATA_PACKAGE_SIZE = 1024
        private const val FREQUENCY =
            RECORD_SAMPLE_RATE_16K
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /**
         * DEFAULT_BUFFER_SIZE 根据语音组介绍不要采用android-API推荐的最小值，因为可能出现缓存区被冲 掉的问题，根据语音组经验，应该在64KBtyes左右为宜
         */
        private const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}