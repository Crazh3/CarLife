package com.baidu.carlifevehicle

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.IBinder
import android.util.Log
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Configs.*
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.TAG
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlifevehicle.audio.recorder.VoiceManager
import com.baidu.carlifevehicle.audio.recorder.VoiceMessageHandler
import com.baidu.carlifevehicle.protocol.ControllerHandler
import com.baidu.carlifevehicle.util.CarlifeConfUtil
import com.baidu.carlifevehicle.util.CommonParams.CONNECT_TYPE_SHARED_PREFERENCES
import com.baidu.carlifevehicle.util.ConnectTypePrefs
import com.baidu.carlifevehicle.util.AudioTransmissionPrefs
import com.baidu.carlifevehicle.util.PreferenceUtil

class VehicleApplication : Application() {

    var vehicleBind: VehicleService.VehicleBind? = null

    companion object {
        lateinit var app: Application

        const val VID_ACCESSORY = 0x18D1
        const val PID_ACCESSORY_ONLY = 0x2D00
        const val PID_ACCESSORY_AUDIO_ADB_BULK = 0x2D05

    }


    override fun onCreate() {
        super.onCreate()
        app = this

        PreferenceUtil.getInstance().init(this)
        // 把设置项「USB-记住授权」落到 USB 匹配组件
        // （UsbAutoLaunchAlias + UsbDeviceMatchReceiver）的启用/禁用上。
        // 首次安装默认值是 true（见 ConnectTypePrefs.isRememberGrant），
        // 即默认启用匹配组件，系统授权弹窗会出现「一律允许」勾选，勾选后该 USB 设备授权一次即可；
        // 设置为"关"时禁用匹配组件，弹窗不再有勾选，每次插入都要重新授权。
        ConnectTypePrefs.applyRememberGrant(this, ConnectTypePrefs.isRememberGrant(this))
        resetUsbDeviceIfNecessary()
        CarlifeConfUtil.getInstance().init()
        initReceiver()
        bindVehicleService()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initReceiver() {

        /**
         * 连接方式默认AOA连接
         */
        val type = PreferenceUtil.getInstance()
            .getInt(CONNECT_TYPE_SHARED_PREFERENCES, CarLifeContext.CONNECTION_TYPE_AOA)

        /**
         * 车机端的支持的Feature，会通过协议传给手机端
         */
        val features = mapOf(
            FEATURE_CONFIG_USB_MTU to 16 * 1024,
            FEATURE_CONFIG_I_FRAME_INTERVAL to 300,
            FEATURE_CONFIG_CONNECT_TYPE to type,
            FEATURE_CONFIG_AAC_SUPPORT to 1,
            // 声音传输通道：互联（0）= 走 carlife TCP 通道；蓝牙（1）= 走蓝牙 A2DP 通道。
            // 默认值由 AudioTransmissionPrefs.currentMode(this) 提供（首次安装默认互联）。
            // 用户在 SettingsActivity 切换后，AudioTransmissionPrefs.setMode 会调
            // CarLife.receiver().setFeature(...) 运行时更新。
            FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE to AudioTransmissionPrefs.currentMode(this),
            FEATURE_CONFIG_MUSIC_HUD to 1
        )

        /**
         * 车机端本地的一些配置
         */
        val configs = mapOf(
            CONFIG_LOG_LEVEL to Log.DEBUG,
            CONFIG_USE_ASYNC_USB_MODE to false,
            CONFIG_PROTOCOL_VERSION to 4
        )

        /**
         * 初始化CarLifeReceiver,需要在主线程调用
         */
        CarLife.init(
            this,
            "20029999",
            "12345678",
            features,
            CarlifeActivity::class.java,
            configs
        )

        // 车机录音初始化
        VoiceManager.init(CarLife.receiver())

        // 注意：displaySpec 下发的时机点很重要。SDK 只在 onConnectionEstablished
        // 时读取一次 displaySpec 发送 MSG_CMD_VIDEO_ENCODER_INIT 给手机端，
        // 之后 setDisplaySpec 不会再触发重发。如果在 Application 阶段下发，
        // 一来尺寸是按 application metrics 推算的（可能与 CarlifeActivity 中
        // RemoteDisplayGLView 的实际尺寸有偏差，导致视频拉伸变糊）；
        // 二来时机过早，连接可能立刻建立，把错误尺寸发给手机端。
        // 因此这里不再 setDisplaySpec，交给 CarlifeActivity 在 GLView
        // 第一次拿到真实测量尺寸后再下发，保证点对点不拉伸。

        // 注册接受车机收音消息逻辑
        CarLife.receiver().registerTransportListener(VoiceMessageHandler())

        // 注册other消息处理
        CarLife.receiver().registerTransportListener(ControllerHandler())



        /**
         * 下面这段代码用于示范消息订阅相关实例
         * 比如车机订阅手机的GPS信息等
        CarLife.receiver().addSubscriber(AssistantGuideSubscriber(CarLife.receiver()))
        CarLife.receiver().addSubscriber(TurnByTurnSubscriber(CarLife.receiver()))
        CarLife.receiver().addSubscribable(CarDataGPSSubscribable(CarLife.receiver()))
        CarLife.receiver().addSubscribable(CarDataVelocitySubscribable(CarLife.receiver()))
        CarLife.receiver().addSubscribable(CarDataGyroscopeSubscribable(CarLife.receiver()))
        CarLife.receiver().addSubscribable(CarDataAccelerationSubscribable(CarLife.receiver()))
        CarLife.receiver().addSubscribable(CarDataGearSubscribable(CarLife.receiver()))
        CarLife.receiver().addSubscribable(CarDataOilSubscribable(CarLife.receiver()))
         */
    }

    val vehicleConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            TODO("Not yet implemented")
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            vehicleBind = service as VehicleService.VehicleBind
        }
    }

    /**
     * CarLife起来时，拉起VehicleService服务，用于CarLife后台连接成功时可以自动被拉到前台显示，
     * 车厂可根据需求选择是否需要此服务.
     */
    fun bindVehicleService() {
        val intent = Intent(this, VehicleService::class.java)
        bindService(intent, vehicleConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * 1、重置一下USB设备端口，防止又脏数据；
     * 2、如车厂有自己的重置USB方式，此方法可以不调用。
     */
    private fun resetUsbDeviceIfNecessary() {

        val usbManager = applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
        for (device in usbManager.deviceList.values) {
            if (isAccessory(device)) {
                try {
                    val usbConnection = usbManager.openDevice(device)
                    usbConnection.releaseInterface(device.getInterface(0))
                    val resetDevice = UsbDeviceConnection::class.java.getMethod("resetDevice")
                    val result = resetDevice.invoke(usbConnection) // 调用反射方法，得到返回值
                    Log.e(TAG, "USB Device resetDevice result: $result")
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /**
     * 判断当前设备是否处于AOA配件模式；
     * 此判断用于重置usb时使用，如车厂有其他方式重置，可忽略此方法.
     * 详情可参考：https://source.android.com/devices/accessories/aoa
     */
    private fun isAccessory(usbDevice: UsbDevice): Boolean {
        return usbDevice.vendorId == VID_ACCESSORY
                && usbDevice.productId >= PID_ACCESSORY_ONLY
                && usbDevice.productId <= PID_ACCESSORY_AUDIO_ADB_BULK
    }
}