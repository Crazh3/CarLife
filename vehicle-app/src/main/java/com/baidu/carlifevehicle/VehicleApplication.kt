package com.baidu.carlifevehicle

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
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
import com.baidu.carlife.sdk.receiver.transport.aoa.UsbAccessoryStarter
import com.baidu.carlifevehicle.util.AudioChannelPrefs
import com.baidu.carlifevehicle.util.ConnectTypePrefs
import com.baidu.carlifevehicle.util.DisplaySpecConfig
import com.baidu.carlifevehicle.util.PreferenceUtil
import com.baidu.carlifevehicle.util.ThemeModeReporter

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
        // 「USB拉起APP」与「记住授权」（设置-连接方式-USB 弹窗内两个勾选）落到系统组件状态，
        // 见 ConnectTypePrefs：两者默认关闭；已有配置则沿用上次的值，
        // 保证进程启动后组件状态与设置一致（覆盖安装/升级后也以设置里的值为准）。
        ConnectTypePrefs.applyUsbAutoLaunch(this, ConnectTypePrefs.isUsbAutoLaunch(this))
        ConnectTypePrefs.applyRememberGrant(this, ConnectTypePrefs.isRememberGrant(this))
        // 进程启动时清掉"已强制 AOA_START_ACCESSORY 重注册身份"的时间记录，
        // 这样本次 App 启动对每个手机都会做一次强制重注册（修复手机端 CarLife app
        // 没监听 AOA bulk 端点的问题）。详见 UsbAccessoryStarter.forceReInitTimes。
        UsbAccessoryStarter.resetForceReInitState()
        resetUsbDeviceIfNecessary()
        CarlifeConfUtil.getInstance().init()
        // 把「音频传输通道 / 媒体·导航音频通道」的设置灌入 SDK，
        // 播放侧（AudioPlayTask / AudioFocusManager）运行时读取，立即生效
        AudioChannelPrefs.apply(this)
        initReceiver()
        bindVehicleService()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 系统深浅色（白天 / 夜间）变化 → 同步给手机端（见 ThemeModeReporter）
        ThemeModeReporter.syncIfNeeded(this)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initReceiver() {

        /**
         * 投屏分辨率：默认按车机屏幕真实宽高下发（见 DisplaySpecConfig），
         * 保证与屏幕比例一致，避免黑边；设置页可手动指定 1080P/720P/576P/W480P
         */
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val displaySpec = DisplaySpecConfig.build(this)

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
            FEATURE_CONFIG_AUDIO_TRANSMISSION_MODE to 1,
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

        Log.d(
            Constants.TAG,
            "VehicleApplication initReceiver ${displaySpec.width}x${displaySpec.height} $displaySpec"
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

        // 设置车机分辨率，这里会传递给手机端，手机端会根据此分辨率传递视频帧到车机
        CarLife.receiver().setDisplaySpec(displaySpec)

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