/**
 * 录音服务:将音乐转录成PCM流，发往手机侧
 */
package com.baidu.carlifevehicle.audio.recorder

import android.media.AudioManager
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.internal.audio.AudioFocusManager
import com.baidu.carlife.sdk.util.Logger

/**
 * 录音工具
 *
 * @author wenhuan
 */
object VoiceRecordUtil {
    private const val TAG = "CarLifeVoice"
    const val VR_STATUS_RECOGNITION = 1
    const val VR_STATUS_WAKEUP = 2
    private var mPcmRecorder: PcmRecorder? = null
    private var mContext: CarLifeContext? = null

    fun init(context: CarLifeContext) {
        mContext = context
    }

    fun unInit() {
        mPcmRecorder = null
    }

    /**
     * 申请音频焦点
     */
    fun requestAudioFocus() {
        Logger.e(TAG, "-----requestAudioFocus-----")
        // VehiclePCMPlayer.getInstance().requestVRAudioFocus()
        val result = mContext?.requestAudioFocus(
            vrListener, AudioFocusManager.STREAM_VR, AudioManager.AUDIOFOCUS_GAIN)

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Logger.d(TAG, "VR audio focus grant")
        } else {
            Logger.d(TAG, "VR audio request focus failed")
        }

    }

    /**
     * 释放音频焦点
     */
    fun abandonAudioFocus() {
        Logger.e(TAG, "-----abandonAudioFocus-----")
        mContext?.abandonAudioFocus(
            vrListener
        )

    }

    private val vrListener = AudioManager.OnAudioFocusChangeListener {
        Logger.d("VoiceRecordUtil", "VR audio focus change to ", it)
    }

    fun onWakeUpStart() {
        Logger.e(TAG, "-----MSG_CMD_MIC_RECORD_WAKEUP_START-----")
        if (mPcmRecorder == null || !mPcmRecorder!!.isAlive()) {
            mPcmRecorder = PcmRecorder(
                mContext
            ).apply { start() }
        }
        mPcmRecorder?.let {
            it.setRecording(true)
            it.setDownSampleStatus(false)
        }
    }

    fun onVRStart() {
        Logger.e(TAG, "-----MSG_CMD_MIC_RECORD_RECOG_START-----")
        if (mPcmRecorder == null || !mPcmRecorder!!.isAlive()) {
            mPcmRecorder = PcmRecorder(
                mContext
            ).apply { start() }
        }
        mPcmRecorder?.let {
            it.setRecording(true)
            /** 关闭降采样  */
            it.setDownSampleStatus(false)
        }
    }

    fun onRecordEnd() {
        Logger.e(TAG, "-----MSG_CMD_MIC_RECORD_END-----")
        mPcmRecorder?.setRecording(false)
    }

    fun onUsbDisconnected() {
        // 释放音频焦点
        abandonAudioFocus()
        mPcmRecorder?.setRecording(false)
    }
}