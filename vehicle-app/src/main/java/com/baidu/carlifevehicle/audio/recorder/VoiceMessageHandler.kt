package com.baidu.carlifevehicle.audio.recorder

import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants.VR_STATUS_IDLE
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MIC_RECORD_END
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MIC_RECORD_RECOG_START
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_MIC_RECORD_WAKEUP_START
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlifevehicle.audio.recorder.VoiceRecordUtil.VR_STATUS_RECOGNITION
import com.baidu.carlifevehicle.audio.recorder.VoiceRecordUtil.VR_STATUS_WAKEUP

class VoiceMessageHandler : TransportListener {
    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            MSG_CMD_MIC_RECORD_WAKEUP_START -> {
                if (!VoiceManager.getActivityPauseState()) {
                    VoiceRecordUtil.onWakeUpStart()
                    VoiceManager.setVoiceStatus(VR_STATUS_WAKEUP)
                }
                return false
            }
            MSG_CMD_MIC_RECORD_END -> {
                VoiceRecordUtil.onRecordEnd()
                return false
            }
            MSG_CMD_MIC_RECORD_RECOG_START -> {
                if (!VoiceManager.getActivityPauseState()){
                    VoiceRecordUtil.onVRStart()
                    VoiceManager.setVoiceStatus(VR_STATUS_RECOGNITION)
                }
                return false
            }
        }
        return false
    }

    override fun onConnectionAttached(context: CarLifeContext) {
        // 清空加解密标记
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        // 清空加解密标记
        VoiceRecordUtil.onUsbDisconnected()
        VoiceManager.setVoiceStatus(VR_STATUS_IDLE)
    }
}