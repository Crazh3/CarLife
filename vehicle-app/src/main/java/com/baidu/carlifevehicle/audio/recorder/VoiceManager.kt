package com.baidu.carlifevehicle.audio.recorder

import com.baidu.carlife.sdk.CarLifeContext

object VoiceManager {
    private const val TAG = "CarLifeVoice"
    private const val VR_STATUS_IDLE = 0
    private const val VR_STATUS_WAKEUP = 1
    private const val VR_STATUS_RECOG = 2
    private var mContext: CarLifeContext? = null

    /** 记录语音状态  */
    private var mVoiceStatus =
        VR_STATUS_IDLE
    private var isPaused = false

    fun init(context: CarLifeContext) {
        mContext = context
        /** 启动录音线程 */
        VoiceRecordUtil.init(context)
    }

    fun onVoiceRecogRunning() {
        mVoiceStatus =
            VR_STATUS_RECOG
        VoiceRecordUtil.requestAudioFocus()
    }

    fun onVoiceRecogIDLE() {
        if (mVoiceStatus == VR_STATUS_RECOG) {
            mVoiceStatus =
                VR_STATUS_IDLE
        }
        VoiceRecordUtil.abandonAudioFocus()
    }

    fun onActivityStop() {
        isPaused = true
        when (mVoiceStatus) {
            // 车机端在识别时，关闭 ui 显示
            VR_STATUS_RECOG ->{
                // mContext?.sendMessage(CARLIFE_VR_MODULE_ID, 0)
                // 退到后台车机就不录音了
                VoiceRecordUtil.onRecordEnd()
            }
            VR_STATUS_WAKEUP -> VoiceRecordUtil.onRecordEnd()
            VR_STATUS_IDLE -> {
            }
        }
    }

    fun onActivityStart() {
        isPaused = false
        when (mVoiceStatus) {
            VR_STATUS_RECOG -> VoiceRecordUtil.onVRStart()
            VR_STATUS_WAKEUP -> VoiceRecordUtil.onWakeUpStart()
            VR_STATUS_IDLE -> {
            }
        }
    }

    fun getActivityPauseState(): Boolean {
        return isPaused
    }

    fun setVoiceStatus(voiceStatus: Int) {
        mVoiceStatus = voiceStatus;
    }
}