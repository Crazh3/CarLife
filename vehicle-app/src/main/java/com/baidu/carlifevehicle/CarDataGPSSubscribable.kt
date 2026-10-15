package com.baidu.carlifevehicle

import com.baidu.carlife.protobuf.CarlifeCarGpsProto
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.CarLifeSubscribable
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.util.TimerUtils

class CarDataGPSSubscribable(private val context: CarLifeContext): CarLifeSubscribable {
    override val id: Int = 0
    override var SupportFlag: Boolean = true

    // 根据实际的gps数据填入，不同坐标系经纬度请自行转化。通过Location对象可以获取到对应数据
    private val latitude = 0
    private val longitude = 0
    private val speed = 0
    private val bearing = 0
    private val altitude = 0
    private val satellites = 0
    private val accuracy = 0

    override fun subscribe() {
        //开始给手机端发送GPS信息
        TimerUtils.schedule(subscribeGpsData, 1 * 1000, 2 * 1000)
    }

    override fun unsubscribe() {
        //停止给手机端发送GPS信息
        TimerUtils.stop(subscribeGpsData)
    }

    // 定时器发送gps数据可以修改成gps manager变更发送gps数据
    private val subscribeGpsData = Runnable {
        //开始给手机端发送当前汽车位置信息，默认值为0的为预留参数，可以不修改。
        var message = CarLifeMessage.obtain(Constants.MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_CAR_GPS)
        message.payload(
            CarlifeCarGpsProto.CarlifeCarGps.newBuilder()
                .setLatitude(latitude * 1000000)
                .setLongitude(longitude * 1000000)
                .setSpeed(speed * 100)
                .setPdop(accuracy * 10)
                .setSatsUsed(satellites)
                .setSatsVisible(satellites)
                .setHeading(bearing * 10)
                .setAntennaState(0)
                .setSignalQuality(0)
                .setHeight(0)
                .setYear(0)
                .setMonth(0)
                .setDay(0)
                .setHrs(0)
                .setMin(0)
                .setSec(0)
                .setFix(0)
                .setHdop(0)
                .setVdop(0)
                .setHorPosError(0)
                .setVertPosError(0)
                .setNorthSpeed(0)
                .setEastSpeed(0)
                .setVertSpeed(0)
                .setTimeStamp(System.currentTimeMillis())
                .build())
        context.postMessage(message)
    }
}