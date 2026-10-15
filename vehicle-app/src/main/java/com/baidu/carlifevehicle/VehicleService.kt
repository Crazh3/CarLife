package com.baidu.carlifevehicle

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.util.Logger

class VehicleService : Service(), TransportListener {
    override fun onBind(intent: Intent?): IBinder? {
        Logger.d("mtg_carlife", "VehicleService onBind")
        return VehicleBind()
    }

    override fun onCreate() {
        super.onCreate()
        CarLife.receiver().registerTransportListener(this)
        Logger.d("mtg_carlife", "VehicleService onCreate")
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        super.onConnectionEstablished(context)

        Logger.d("VideoRender", "onConnectionEstablished start CarlifeActivity")
        // 如需在连接成功后把 CarlifeActivity 拉到前台，请使用 ActivityManager.AppTask 等非废弃 API 实现。
    }

    class VehicleBind : Binder() {
        @Suppress("UNUSED_PARAMETER")
        fun serviceStatus(status: Int) {

        }
    }

}