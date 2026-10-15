package com.baidu.carlifevehicle.protocol

import android.content.Intent
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_GO_TO_DESKTOP
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.Logger


class ControllerHandler : TransportListener {

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            MSG_CMD_GO_TO_DESKTOP -> {
                Logger.d(Constants.TAG, "back to home ", message)
                val intent = Intent(Intent.ACTION_MAIN)
                intent.addCategory(Intent.CATEGORY_HOME)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.applicationContext.startActivity(intent)
            }
            else -> {

            }
        }
        return false
    }

}