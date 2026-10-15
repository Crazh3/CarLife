package com.baidu.carlifevehicle

import android.Manifest
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Message
import android.text.TextUtils
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat.requestPermissions
import androidx.core.content.ContextCompat
import com.baidu.carlife.protobuf.CarlifeBTHfpCallStatusCoverProto.CarlifeBTHfpCallStatusCover
import com.baidu.carlife.protobuf.CarlifeConnectExceptionProto.CarlifeConnectException
import com.baidu.carlife.sdk.*
import com.baidu.carlife.sdk.Constants.TAG
import com.baidu.carlife.sdk.Constants.VALUE_PROGRESS_100
import com.baidu.carlife.sdk.internal.DisplaySpec
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
import com.baidu.carlifevehicle.util.DisplayModeConfig
import com.baidu.carlifevehicle.util.DisplaySpecConfig
import com.baidu.carlifevehicle.util.DisplaySpecLogger
import com.baidu.carlifevehicle.util.PreferenceUtil
import com.baidu.carlifevehicle.util.UsbPermissionRechecker
import com.baidu.carlifevehicle.view.CarlifeMessageDialog
import com.permissionx.guolindev.PermissionX

class CarlifeActivity : AppCompatActivity(), ConnectProgressListener,
    TransportListener, View.OnClickListener, OnPhoneStateChangeListener, WirlessStatusListener {
    public var mIsConnectException = false
    private lateinit var mSurfaceView: RemoteDisplayGLView
    private var mSurface: Surface? = null
    private lateinit var mRootView: ViewGroup
    private lateinit var btHardKeyCode: Button
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
        // 本 Activity 也是 USB 接入的匹配目标（见 AndroidManifest 的 UsbAutoLaunchAlias +
        // device_filter 全匹配），这样 App 没启动时插线也能被系统直接拉起（系统发起的启动，
        // 不受 Android 10+ 后台启动限制）。全匹配是为了让"手机普通模式设备"那次授权弹窗
        // 也有「一律允许」勾选，副作用是 U 盘/鼠标也会命中，所以这里先判掉并立即退出，
        // 避免把 CarLife 界面弹到前台。必须放在 setContentView 之前，避免白闪。
        if (UsbAttachPolicy.shouldIgnoreAttach(intent)) {
            finish()
            return
        }
        // 应用显示模式（系统默认/沉浸式全屏，用户在设置页选择）
        DisplayModeConfig.apply(this)
        setContentView(R.layout.activity_main)
        mSurfaceView = findViewById(R.id.video_surface_view)

        // ============================================================
        // 诊断日志：分辨率协商全流程
        // ============================================================
        // 1) 车机物理屏 + application window
        DisplaySpecLogger.logPhysicalScreen(this, "CarlifeActivity.onCreate")
        DisplaySpecLogger.logAppWindow(this, "CarlifeActivity.onCreate")
        DisplaySpecLogger.logGlviewSize(mSurfaceView, "CarlifeActivity.onCreate(before-layout)")

        // 2) 先下发 fallback（整屏物理像素），防止连接在 GLView 拿到真实尺寸前就建立
        // 导致手机端没收到编码参数；真正精准的尺寸在下面 onLayout 里按 GLView 实测值覆盖。
        try {
            applyDisplaySpec(
                DisplaySpecConfig.buildFallback(this),
                "CarlifeActivity.onCreate",
                "fallback"
            )
        } catch (e: Exception) {
            Logger.w(TAG, "setDisplaySpec(fallback) failed: ", e)
        }

        // 3) 监听 GLView 尺寸变化：首帧 layout、运行期切换显示模式（系统栏显隐）、
        // 窗口尺寸变化都会走到这里。每次都把"渲染区物理像素"作为下发给手机端的目标尺寸，
        // 尺寸真的变了才下发（见 applyDisplaySpec），并触发一次视频尺寸重新协商。
        mSurfaceView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            DisplaySpecLogger.logGlviewSize(v, "CarlifeActivity.onLayout")
            try {
                applyDisplaySpec(
                    DisplaySpecConfig.build(w, h, this),
                    "CarlifeActivity.onLayout",
                    "glview-actual"
                )
            } catch (e: Exception) {
                Logger.w(TAG, "setDisplaySpec(glview) failed: ", e)
            }
        }

        // 4) 监听手机端最终回传的编码尺寸（MSG_CMD_VIDEO_ENCODER_INIT_DONE），
        // 与我们下发的尺寸对比就能直接看出手机端是否按我们下发的尺寸编码。
        // 这是判断"模糊"的根因——比例不一致 = 拉伸。
        CarLife.receiver().addOnVideoSizeChangedListener { width, height ->
            DisplaySpecLogger.logPhoneEncodedSize(width, height, "phoneEncoderInitDone")
            val ratio = if (height > 0) width.toFloat() / height else 0f
            val target = CarLife.receiver().getDisplaySpec()
            val targetRatio = if (target.height > 0) target.width.toFloat() / target.height else 0f
            val diff = kotlin.math.abs(ratio - targetRatio)
            Logger.w(
                TAG,
                "[phoneEncoderInitDone] ratio=$ratio targetRatio=$targetRatio diff=$diff " +
                        "${if (diff < 0.02f) "OK_MATCH" else "MISMATCH_STRETCH"}"
            )
        }

        mRootView = findViewById(R.id.root_view)

        mCarLifeFragmentManager = CarLifeFragmentManager(this)
        // initialize basefragment, must be called before using it's subclass
        BaseFragment.initBeforeAll(this)
        mCarLifeFragmentManager!!.showFragment(LaunchFragment.getInstance())

        btHardKeyCode = findViewById(R.id.bt_hard)
        btHardKeyCode.setOnClickListener(this)

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

        ControlTestWindow.getInstance().init(this@CarlifeActivity, mRootView)

        mMainActivityHandler = MsgMainActivityHandler()
        MsgHandlerCenter.registerMessageHandler(mMainActivityHandler)
        (mMainActivityHandler as MsgMainActivityHandler).sendEmptyMessageDelayed(
            CommonParams.MSG_CONNECT_INIT,
            500
        )

    }

    fun getCarLifeVehicleFragmentManager(): CarLifeFragmentManager? {
        return mCarLifeFragmentManager
    }

    /**
     * 把渲染区尺寸下发给手机端（[DisplaySpecConfig] 已按比例 + H.264 16 对齐算好）。
     *
     * 与 SDK 里当前保存的 displaySpec 比较：
     * - 尺寸一致 → 什么都不做，避免每次 layout 都重复下发、重复协商；
     * - 尺寸变了 → 覆盖 displaySpec，并让 SDK 在"已连接"时按新尺寸重新协商视频
     *   （重发 MSG_CMD_VIDEO_ENCODER_INIT，见 RemoteDisplayRenderer.requestVideoReinit）；
     *   未连接时不用重协商 —— 连接建立时会用最新的 displaySpec 发 INIT。
     *
     * 这样运行期切换「系统默认 / 沉浸式全屏」（系统栏显隐导致渲染区尺寸变化）后，
     * 手机端会立即按新尺寸重新编码，不再停留在旧尺寸上，也不需要重启 App。
     */
    private fun applyDisplaySpec(spec: DisplaySpec, scene: String, source: String) {
        val current = CarLife.receiver().getDisplaySpec()
        if (current.width == spec.width
            && current.height == spec.height
            && current.frameRate == spec.frameRate
        ) {
            Logger.d(TAG, "applyDisplaySpec skip, unchanged ${spec.width}x${spec.height}")
            return
        }
        CarLife.receiver().setDisplaySpec(spec)
        DisplaySpecLogger.logDisplaySpec(spec, scene, source)
        val reinit = CarLife.receiver().requestVideoReinit()
        Logger.d(
            TAG,
            "applyDisplaySpec ${spec.width}x${spec.height} source=$source videoReinit=$reinit"
        )
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
            .request { allGranted, _, deniedList ->
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
        var hasPermisson: Boolean
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

    override fun onResume() {
        super.onResume()
        // 主动重发未授权 AOA 设备的系统授权弹窗。
        // Android 系统在用户"拒绝"USB 授权时**不发送任何广播**，
        // SDK 内部 UsbAccessoryScanner 在 pendingPermissionDevices.poll() 后
        // 永远等不到回调，导致下次弹窗只能等 USB 重新插拔（ACTION_USB_DEVICE_ATTACHED）。
        // 这里在 Activity 回到前台时主动扫描未授权设备并重发弹窗，
        // 让"再次弹窗"延迟从"拔插 USB"缩短到"用户回到 Activity"。
        try {
            UsbPermissionRechecker.recheckAoaDevices(this)
        } catch (e: Exception) {
            Logger.w(TAG, "onResume recheckAoaDevices failed: ", e)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 沉浸式模式下系统栏被临时呼出后，重新进入沉浸
        if (hasFocus) {
            DisplayModeConfig.apply(this)
        }
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
    }

    override fun onConnectionAttached(context: CarLifeContext) {
        MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_STATUS_CONNECTED)
    }

    override fun onConnectionReattached(context: CarLifeContext) {
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        MsgHandlerCenter.dispatchMessage(CommonParams.MSG_CONNECT_STATUS_DISCONNECTED)
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

    private var numClickCount = 0

    override fun onClick(view: View?) {
        when (view!!.id) {
            R.id.bt_hard -> {
                if (++numClickCount == 3) {
                    ControlTestWindow.getInstance().displayWindow()
                    numClickCount = 0
                }
            }
        }
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