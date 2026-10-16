package com.baidu.carlifevehicle

import android.Manifest
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Message
import android.text.TextUtils
import android.view.Surface
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.content.ContextCompat
import com.baidu.carlife.protobuf.CarlifeBTHfpCallStatusCoverProto.CarlifeBTHfpCallStatusCover
import com.baidu.carlife.protobuf.CarlifeConnectExceptionProto.CarlifeConnectException
import com.baidu.carlife.sdk.*
import com.baidu.carlife.sdk.Constants.TAG
import com.baidu.carlife.sdk.Constants.VALUE_PROGRESS_100
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.receiver.CarLife
import com.baidu.carlife.sdk.receiver.ConnectProgressListener
import com.baidu.carlife.sdk.receiver.FileTransferListener
import com.baidu.carlife.sdk.receiver.OnPhoneStateChangeListener
import com.baidu.carlife.sdk.receiver.view.RemoteDisplayGLView
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.audio.recorder.VoiceManager
import com.baidu.carlifevehicle.fragment.*
import com.baidu.carlifevehicle.message.MsgBaseHandler
import com.baidu.carlifevehicle.message.MsgHandlerCenter
import com.baidu.carlifevehicle.module.MusicModule
import com.baidu.carlifevehicle.module.NavModule
import com.baidu.carlifevehicle.module.PhoneModule
import com.baidu.carlifevehicle.module.VRModule
import com.baidu.carlifevehicle.usb.UsbAttachPolicy
import com.baidu.carlifevehicle.util.CarlifeConfUtil
import com.baidu.carlifevehicle.util.CarlifeUtil
import com.baidu.carlifevehicle.util.CommonParams
import com.baidu.carlifevehicle.util.DisplayModeApplier
import com.baidu.carlifevehicle.util.DisplayModePrefs
import com.baidu.carlifevehicle.util.PreferenceUtil
import com.baidu.carlifevehicle.util.ServiceSwitchPrefs
import com.baidu.carlifevehicle.util.ThemeModeReporter
import com.baidu.carlifevehicle.view.CarlifeMessageDialog
import com.permissionx.guolindev.PermissionX

class CarlifeActivity : AppCompatActivity(), ConnectProgressListener,
    TransportListener, OnPhoneStateChangeListener, WirlessStatusListener {
    public var mIsConnectException = false
    private lateinit var mSurfaceView: RemoteDisplayGLView
    private var mSurface: Surface? = null
    private lateinit var mRootView: ViewGroup
    private lateinit var mVehicleControlHandler: TransportListener
    private lateinit var mPhoneModule: CarLifeModule
    private lateinit var mMusicModule: CarLifeModule
    private lateinit var mNavModule: CarLifeModule
    private lateinit var mVRModule: CarLifeModule
    private var mCarLifeFragmentManager: CarLifeFragmentManager? = null
    private var mExitAppDialog: CarlifeMessageDialog? = null

    private var mHasEverConnect = false
    private var mMainActivityHandler: MsgBaseHandler? = null
    private var mIsCallCoverShowed = false
    private var callStatus = 0
    private var phoneNum = ""
    private var phoneName = ""
    private var mIsCalling: Boolean = false
    private var mIsCallComing: Boolean = false
    private var mIsInitConfig: Boolean = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 「记住授权」开启时，插线（App 未启动也一样）会由 AndroidManifest 里的
        // UsbAutoLaunchAlias + device_filter(全匹配) 把本界面直接拉起；U 盘/鼠标这类
        // 非手机设备在这里判掉并立即退出，避免把 CarLife 界面弹到前台。
        // 必须放在 setContentView 之前，避免白闪。
        if (UsbAttachPolicy.shouldIgnoreAttach(intent)) {
            finish()
            return
        }
        setContentView(R.layout.activity_main)
        // 「显示方式」：系统默认 / 沉浸式全屏（见 DisplayModePrefs）。
        // 必须在启动时应用一次，否则上次设置的沉浸式在进程重启后不生效。
        DisplayModeApplier.apply(this, DisplayModePrefs.isImmersiveFullscreen(this))
        mSurfaceView = findViewById(R.id.video_surface_view)

        mRootView = findViewById(R.id.root_view)

        mCarLifeFragmentManager = CarLifeFragmentManager(this)
        // initialize basefragment, must be called before using it's subclass
        BaseFragment.initBeforeAll(this)
        mCarLifeFragmentManager!!.showFragment(LaunchFragment.getInstance())

        mPhoneModule = PhoneModule(CarLife.receiver(), this)
        mMusicModule = MusicModule(CarLife.receiver())
        mNavModule = NavModule(CarLife.receiver())
        mVRModule = VRModule(CarLife.receiver())
        CarLife.receiver().addModule(mPhoneModule)
        CarLife.receiver().addModule(mMusicModule)
        CarLife.receiver().addModule(mNavModule)
        CarLife.receiver().addModule(mVRModule)
        CarLife.receiver().addConnectProgressListener(this)
        CarLife.receiver().registerTransportListener(this)
        CarLife.receiver().registerWirlessStatusListeners(this)
        mVehicleControlHandler = VehicleControlHandler()
        CarLife.receiver().registerTransportListener(mVehicleControlHandler)
        CarLife.receiver().setFileTransferListener(FileTransferListener { file ->
            Logger.d("zwh", "file>>>>>", file.absolutePath)
            ApkInstall.installApk(this@CarlifeActivity, file.path)
        })

        requestPermission()

        mMainActivityHandler = MsgMainActivityHandler()
        MsgHandlerCenter.registerMessageHandler(mMainActivityHandler)
        (mMainActivityHandler as MsgMainActivityHandler).sendEmptyMessageDelayed(
            CommonParams.MSG_CONNECT_INIT,
            500
        )

    }

    override fun onResume() {
        super.onResume()
        // 「显示方式」在别处（设置页弹窗、系统栏被用户滑动唤出）可能变过，回到前台重新应用一次
        DisplayModeApplier.apply(this, DisplayModePrefs.isImmersiveFullscreen(this))
    }

    fun getCarLifeVehicleFragmentManager(): CarLifeFragmentManager? {
        return mCarLifeFragmentManager
    }

    private fun requestPermission() {
        PermissionX.init(this)
            .permissions(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            .onExplainRequestReason { scope, deniedList ->
                scope.showRequestReasonDialog(
                    deniedList,
                    "打开权限",
                    "确定",
                    "取消"
                )
            }
            .request { allGranted, grantedList, deniedList ->
                if (!allGranted) {
                    Toast.makeText(
                        this,
                        "权限未打开: $deniedList",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
    }

    // 配置语音录音的请求权限，暂时现这样处理，后面封装成统一的工具类
    private fun requestRecordPermission(permissionString: String?): Boolean {

        val result = ContextCompat.checkSelfPermission(this@CarlifeActivity, permissionString!!)
        Logger.e(Constants.TAG, "requestPermission>>>", result)
        var hasPermisson = false
        hasPermisson = if (result == PackageManager.PERMISSION_GRANTED) { // 没有获得权限
            true
        } else {
            Logger.e(Constants.TAG, "requestPermission>>>", permissionString)
            requestPermissions(this, arrayOf<String>(permissionString), 100)
            false
        }
        return hasPermisson
    }

    override fun onDestroy() {
        super.onDestroy()
        mSurfaceView.onDestroy()
        Logger.d(Constants.TAG, "MainActivity onDestroy")
        CarLife.receiver().removeConnectProgressListener(this)
        CarLife.receiver().unregisterTransportListener(this)
        CarLife.receiver().unregisterTransportListener(mVehicleControlHandler)
        CarLife.receiver().setFileTransferListener(null)
        CarLife.receiver().removeModule(mPhoneModule)
        CarLife.receiver().removeModule(mMusicModule)
        CarLife.receiver().removeModule(mNavModule)
        CarLife.receiver().removeModule(mVRModule)

        CarLife.receiver().unregisterWirlessStatusListeners(this)
        MsgHandlerCenter.unRegisterMessageHandler(mMainActivityHandler)
    }

    override fun onProgress(progress: Int) {
        MsgHandlerCenter.dispatchMessage(
            CommonParams.MSG_CONNECT_CHANGE_PROGRESS_NUMBER,
            progress,
            0,
            null
        )
        if (progress == VALUE_PROGRESS_100) {
            MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CMD_VIDEO_ENCODER_START)
        }
    }

    override fun onStart() {
        super.onStart()
        VoiceManager.onActivityStart()
        CarLife.receiver().onActivityStarted()
    }

    override fun onStop() {
        super.onStop()
        VoiceManager.onActivityStop()
        CarLife.receiver().onActivityStopped()
    }

    override fun onConnectionAuthenFailed(context: CarLifeContext) {
        MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_FAIL_AUTHEN_FAILED)
    }

    override fun onConnectionVersionNotSupprt(context: CarLifeContext) {
        MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_FAIL_NOT_SURPPORT)
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_STATUS_ESTABLISHED)
        // 连接刚建立必须推一次当前深浅色：手机端只能被动接收，不推就不知道车机当前主题
        ThemeModeReporter.onConnectionEstablished()
        ThemeModeReporter.syncIfNeeded(this, force = true)
    }

    override fun onConnectionAttached(context: CarLifeContext) {
        MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_STATUS_CONNECTED)
    }

    override fun onConnectionReattached(context: CarLifeContext) {
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_STATUS_DISCONNECTED)
        // 断开后复位，下次连上重新推一次
        ThemeModeReporter.onConnectionDetached()
    }

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            ServiceTypes.MSG_CMD_CONNECT_EXCEPTION -> {
                val response = message.protoPayload as CarlifeConnectException
                handleConnectException(response)
            }
        }
        return false
    }

    private fun openExitAppDialogOnReadConfFail() {
        mExitAppDialog = CarlifeMessageDialog(this).setTitleText(R.string.alert_quit)
            .setMessage(R.string.conf_init_fail)
            .setOnFirstBtnClickListener { exitApp() }
            .setFirstBtnText(R.string.alert_confirm)
        mExitAppDialog?.setOnDismissListener(DialogInterface.OnDismissListener {
            mExitAppDialog = null
        })

        if (!mExitAppDialog!!.isShowing) {
            try {
                mExitAppDialog?.show()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun exitApp() {
        CarLife.receiver().disconnect()
        finish()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun openExitAppDialog() {
        mExitAppDialog = CarlifeMessageDialog(this).setTitleText(R.string.alert_quit)
            .setMessage(R.string.alert_quit_app_content).setFirstBtnText(R.string.alert_confirm)
            .setFirstBtnTextColorHighLight().setOnFirstBtnClickListener { exitApp() }
            .setSecondBtnText(R.string.alert_cancel)
        mExitAppDialog?.setOnDismissListener(DialogInterface.OnDismissListener {
            mExitAppDialog = null
        })
        if (!mExitAppDialog!!.isShowing) {
            try {
                mExitAppDialog?.show()
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            }
        }
    }

    private inner class MsgMainActivityHandler : MsgBaseHandler() {

        override fun handleMessage(msg: Message) {
            try {
                Logger.d(
                    TAG,
                    "MsgMainActivityHandler handleMessage get msg: " + CommonParams.getMsgName(msg.what)
                )
                when (msg.what) {
                    CommonParams.MSG_CONNECT_INIT -> {
                        if (CarlifeConfUtil.getInstance().readConfStatus) {
                            init()
                        } else {
                            if (!CarlifeConfUtil.getInstance().isReadMaxTime) {
                                Logger.d(TAG, "read conf again")
                                CarlifeConfUtil.getInstance().init()
                                sendEmptyMessageDelayed(CommonParams.MSG_CONNECT_INIT, 500)
                            } else {
                                openExitAppDialogOnReadConfFail()
                            }
                        }
                    }

                    CommonParams.MSG_CONNECT_STATUS_CONNECTED -> {
                        saveConnectStatus(true)
                    }

                    CommonParams.MSG_MAIN_DISPLAY_USER_GUIDE_FRAGMENT -> {
                        if (!mHasEverConnect) {
                            mCarLifeFragmentManager?.showFragment(NewUserGuideFragment.getInstance())
                        }
                    }

                    CommonParams.MSG_MAIN_DISPLAY_MAIN_FRAGMENT -> {
                        if (!mIsInitConfig) {
                            // 如果配置加载失败，则延时500再回来执行。保证配置加载成功再去连接手机端
                            Logger.d(TAG, "mIsInitConfig is $mIsInitConfig, so delay 500")
                            MsgHandlerCenter.dispatchMessageDelay(
                                CommonParams.MSG_MAIN_DISPLAY_MAIN_FRAGMENT,
                                500
                            )
                            return
                        }

                        if (CarLife.receiver().isAttached()) {
                            MsgHandlerCenter.dispatchMessage(CommonParams.MSG_MAIN_DISPLAY_TOUCH_FRAGMENT)
                        } else {
                            if (mCarLifeFragmentManager != null) {
                                mCarLifeFragmentManager!!.showFragment(MainFragment.getInstance())
                            }
                        }
                    }

                    CommonParams.MSG_MAIN_DISPLAY_EXCEPTION_FRAGMENT -> {
                        if (mCarLifeFragmentManager == null) {
                            return
                        }
                        mCarLifeFragmentManager!!.showFragment(ExceptionFragment.getInstance())
                        Logger.d(TAG, "mIsCalling=$mIsCalling")
                        if (mIsCalling) {
                            if (mIsCallComing) {
                                ExceptionFragment.getInstance()
                                    .changeTipsCallback(resources.getString(R.string.line_is_coming))
                                ExceptionFragment.getInstance()
                                    .changeDrawableCallback(R.drawable.car_ic_incoming)
                                ExceptionFragment.getInstance().setStartAppBtnHide()
                            } else {
                                ExceptionFragment.getInstance()
                                    .changeTipsCallback(resources.getString(R.string.line_is_busy))
                                ExceptionFragment.getInstance()
                                    .changeDrawableCallback(R.drawable.car_ic_calling)
                                ExceptionFragment.getInstance().setStartAppBtnHide()
                            }
                        } else {
                            ExceptionFragment.getInstance()
                                .changeTipsCallback(resources.getString(R.string.connect_screenoff_hint))
                            ExceptionFragment.getInstance().setStartAppBtnVisible()
                            ExceptionFragment.getInstance()
                                .changeDrawableCallback(R.drawable.car_ic_click)
                        }
                    }

                    CommonParams.MSG_MAIN_DISPLAY_TOUCH_FRAGMENT -> {
                        // Recover call status cover page only when in ringing
                        if (!CarLife.receiver().isAttached()) return
                        if (mIsCallCoverShowed && callStatus == 1) {
                            val builder = CarlifeBTHfpCallStatusCover.newBuilder()
                            if (builder != null) {
                                Logger.d(
                                    "Bt",
                                    "Recover callstatus cover on reception of foreground message"
                                )
                                builder.state = callStatus
                                if (TextUtils.isEmpty(phoneNum)) {
                                    builder.phoneNum = ""
                                } else {
                                    builder.phoneNum = phoneNum
                                }
                                if (TextUtils.isEmpty(phoneName)) {
                                    builder.name = ""
                                } else {
                                    builder.name = phoneName
                                }

                                var message = CarLifeMessage.obtain(
                                    Constants.MSG_CHANNEL_CMD,
                                    ServiceTypes.MSG_CMD_BT_HFP_CALL_STATUS_COVER
                                )
                                message.payload(builder.build())

                                CarLife.receiver().postMessage(message)
                            }
                        } else {
                            if (mCarLifeFragmentManager != null) {
                                mCarLifeFragmentManager!!.showFragment(TouchFragment.getInstance())
                            }
                        }
                    }

                    CommonParams.MSG_CONNECT_STATUS_DISCONNECTED -> {
                        mIsConnectException = false
                        if (mCarLifeFragmentManager != null) {
                            mCarLifeFragmentManager!!.showFragment(MainFragment.getInstance())
                        }
                    }
                }
            } catch (ex: java.lang.Exception) {
                Logger.e(TAG, "handle message exception", ex)
                ex.printStackTrace()
            }
        }

        override fun careAbout() {
            addMsg(CommonParams.MSG_CONNECT_INIT)
            addMsg(CommonParams.MSG_CONNECT_STATUS_CONNECTED)
            addMsg(CommonParams.MSG_CONNECT_STATUS_DISCONNECTED)
            addMsg(CommonParams.MSG_MAIN_DISPLAY_USER_GUIDE_FRAGMENT)
            addMsg(CommonParams.MSG_MAIN_DISPLAY_MAIN_FRAGMENT)
            addMsg(CommonParams.MSG_MAIN_DISPLAY_TOUCH_FRAGMENT)
            addMsg(CommonParams.MSG_MAIN_DISPLAY_EXCEPTION_FRAGMENT)
        }

    }

    fun init() {
        Logger.e(TAG, "++++++++++++++++++++Baidu Carlife Begin++++++++++++++++++++")

        // 根据配置文件设置相应的配置
        CarLife.receiver().run {
            setFeature(
                Configs.FEATURE_CONFIG_VOICE_WAKEUP,
                CarlifeConfUtil.getInstance().getIntProperty(Configs.FEATURE_CONFIG_VOICE_WAKEUP)
            )
            setFeature(
                Configs.FEATURE_CONFIG_VOICE_MIC,
                CarlifeConfUtil.getInstance().getIntProperty(Configs.FEATURE_CONFIG_VOICE_MIC)
            )
            setFeature(
                Configs.FEATURE_CONFIG_BLUETOOTH_INTERNAL_UI,
                CarlifeConfUtil.getInstance()
                    .getIntProperty(Configs.FEATURE_CONFIG_BLUETOOTH_INTERNAL_UI)
            )
            setFeature(
                Configs.FEATURE_CONFIG_FOCUS_UI,
                CarlifeConfUtil.getInstance().getIntProperty(Configs.FEATURE_CONFIG_FOCUS_UI)
            )
        }

        if (!TextUtils.isEmpty(
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_WIFI_DIRECT_NAME)
            )
        ) {
            CarLife.receiver().setConfig(
                Configs.CONFIG_WIFI_DIRECT_NAME,
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_WIFI_DIRECT_NAME)
            )
        }

        if (!TextUtils.isEmpty(
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_TARGET_BLUETOOTH_NAME)
            )
        ) {
            CarLife.receiver().setConfig(
                Configs.CONFIG_TARGET_BLUETOOTH_NAME,
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_TARGET_BLUETOOTH_NAME)
            )
        }

        if (!TextUtils.isEmpty(
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_HU_BT_NAME)
            )
        ) {
            CarLife.receiver().setConfig(
                Configs.CONFIG_HU_BT_NAME,
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_HU_BT_NAME)
            )
        }

        if (!TextUtils.isEmpty(
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_HU_BT_MAC)
            )
        ) {
            CarLife.receiver().setConfig(
                Configs.CONFIG_HU_BT_MAC,
                CarlifeConfUtil.getInstance().getStringFromMap(Configs.CONFIG_HU_BT_MAC)
            )
        }

        CarLife.receiver().setConfig(
            Configs.CONFIG_LOG_LEVEL,
            CarlifeConfUtil.getInstance().getIntProperty(Configs.CONFIG_LOG_LEVEL)
        )
        CarLife.receiver().setConfig(
            Configs.CONFIG_WIRLESS_TYPE,
            CarlifeConfUtil.getInstance().getIntProperty(Configs.CONFIG_WIRLESS_TYPE)
        )
        CarLife.receiver().setConfig(
            Configs.CONFIG_WIRLESS_FREQUENCY,
            CarlifeConfUtil.getInstance().getIntProperty(Configs.CONFIG_WIRLESS_FREQUENCY)
        )
        CarLife.receiver().setConfig(
            Configs.CONFIG_SAVE_AUDIO_FILE,
            CarlifeConfUtil.getInstance().getBooleanProperty(Configs.CONFIG_SAVE_AUDIO_FILE)
        )

        // 根据渠道号构建static info 信息
        CarLife.receiver().initStatisticsInfo(CommonParams.vehicleChannel, "12345678")
        mIsInitConfig = true;

        // 主页「服务」总开关关闭时不连接手机（用户改设置时临时停掉），见 ServiceSwitchPrefs。
        // SDK 完成初始化后会自己开始扫描/连接，所以要在配置下发之后按开关状态停一次。
        if (!ServiceSwitchPrefs.isEnabled(this)) {
            CarLife.receiver().stopConnect()
        }
    }

    fun saveConnectStatus(status: Boolean) {
        try {
            PreferenceUtil.getInstance().putBoolean(
                CommonParams.CONNECT_STATUS_SHARED_PREFERENCES,
                CommonParams.CONNECT_STATUS, status
            )
        } catch (ex: java.lang.Exception) {
            Logger.e(TAG, "save connect status error")
            ex.printStackTrace()
        }
    }

    override fun onStateChanged(isCalling: Boolean, isCallComing: Boolean) {
        mIsCalling = isCalling
        mIsCallComing = isCallComing
    }

    private fun handleConnectException(exception: CarlifeConnectException) {
        var hintResStr: String? = null
        when (exception.exceptionType) {
            Constants.VIDEO_PERMISSION_DENIED -> {
                hintResStr = resources.getString(R.string.carlife_video_permission_denied_hint)
                mIsConnectException = true
            }

            Constants.VIDEO_ENCODER_EROOR -> {
                hintResStr = resources.getString(R.string.carlife_phone_not_support_hint)
                mIsConnectException = true
            }

            Constants.VIDEO_PAUSE_BY_SCREENSHARE_REQUEST -> {
                hintResStr = resources.getString(R.string.carlife_video_permission_hint)
            }
        }

        if (hintResStr == null) {
            return
        }

        if (mCarLifeFragmentManager != null) {
            mCarLifeFragmentManager!!.showFragment(MainFragment.getInstance())
        }

        MainFragment.getInstance().updateExceptionTips(hintResStr)
    }

    override fun onDeviceWirlessStatus(status: Int) {
        when (status) {
            Constants.VALUE_LOW_POWER -> {
                CarlifeUtil.showToastInUIThread(R.string.carlife_toast_low_power)
            }

            Constants.VALUE_NO_WIFI -> {
                CarlifeUtil.showToastInUIThread(R.string.carlife_toast_no_wifi)
            }
        }
    }
}