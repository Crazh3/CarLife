package com.baidu.carlife.sdk.receiver.display

import android.view.Surface
import com.baidu.carlife.protobuf.CarlifeVideoEncoderInfoProto.CarlifeVideoEncoderInfo
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.Constants.MSG_CHANNEL_CMD
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage
import com.baidu.carlife.sdk.internal.protocol.CarLifeMessage.Companion.obtain
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT_DONE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_VIDEO_ENCODER_PAUSE
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_CMD_VIDEO_ENCODER_START
import com.baidu.carlife.sdk.internal.protocol.ServiceTypes.MSG_VIDEO_DATA
import com.baidu.carlife.sdk.receiver.OnVideoSizeChangedListener
import com.baidu.carlife.sdk.internal.DisplaySpec
import com.baidu.carlife.sdk.internal.transport.TransportListener
import com.baidu.carlife.sdk.receiver.SurfaceRequestCallback
import com.baidu.carlife.sdk.util.Logger

class RemoteDisplayRenderer(private val context: CarLifeContext,
                            private val listener: OnVideoSizeChangedListener
): TransportListener {
    var encoderInfo: CarlifeVideoEncoderInfo? = null
        private set
    private var frameDecoder: FrameDecoder? = null
    private var surface: Surface? = null

    /**
     * 最近一次下发给手机端的编码参数（MSG_CMD_VIDEO_ENCODER_INIT）。
     * 用于判断"已发出 INIT、还在等手机端 INIT_DONE"以及"等待期间 displaySpec 是否又变了"，
     * 变了就再协商一次（每次只多协商一轮，不会无限循环，见 onReceiveMessage）。
     */
    private var lastInitRequestSpec: DisplaySpec? = null

    /** 已发出 MSG_CMD_VIDEO_ENCODER_INIT、尚未收到 INIT_DONE */
    private var initPending = false

    var displaySpec: DisplaySpec

    var surfaceRequester: SurfaceRequestCallback? = null


    init {
        val screenWidth = context.applicationContext.resources.displayMetrics.widthPixels
        val screenHeight = context.applicationContext.resources.displayMetrics.heightPixels
        displaySpec = DisplaySpec(context.applicationContext, screenWidth, screenHeight, 30)
    }

    @Synchronized
    fun setSurface(surface: Surface?) {
        this.surface = surface
        if (surface == null) {
            Logger.d(Constants.TAG, "RemoteDisplayRenderer setSurface to null")
            frameDecoder?.let {
                it.release()
                frameDecoder = null
                context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_PAUSE)
            }
            return
        }

        if (encoderInfo != null && frameDecoder == null) {
            // 说明收到MSG_CMD_VIDEO_ENCODER_INIT_DONE的时候，还没有surface
            // 此时需要创建decoder，并发送MSG_CMD_VIDEO_ENCODER_START
            frameDecoder = FrameDecoder(context, surface, encoderInfo!!)
            context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_START)
            Logger.d(Constants.TAG, "RemoteDisplayRenderer create decoder delayed")
        }
        else {
            frameDecoder?.let {
                Logger.d(Constants.TAG, "RemoteDisplayRenderer decoder set new surface")
                it.setSurface(surface)
            }
        }
    }

    private fun stop() {
        frameDecoder?.let {
            it.release()
            frameDecoder = null
        }
        encoderInfo = null
        lastInitRequestSpec = null
        initPending = false
    }

    override fun onReceiveMessage(context: CarLifeContext, message: CarLifeMessage): Boolean {
        when (message.serviceType) {
            MSG_CMD_VIDEO_ENCODER_INIT_DONE -> {
                synchronized(this) {
                    initPending = false
                    // 尺寸重协商（requestVideoReinit）时旧解码器还在解码上一路流，
                    // 换尺寸前必须先释放，否则 MediaCodec 与解码线程都会泄漏。
                    frameDecoder?.let {
                        Logger.d(Constants.TAG, "RemoteDisplayRenderer release old decoder for reinit")
                        it.release()
                        frameDecoder = null
                    }
                    encoderInfo = message.protoPayload as CarlifeVideoEncoderInfo
                    listener.onVideoSizeChanged(encoderInfo!!.width, encoderInfo!!.height)
                    if (surface != null) {
                        frameDecoder = FrameDecoder(context, surface!!, encoderInfo!!)
                        var messgae = obtain(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_START)
                        context.postMessage(messgae)
                        Logger.d(Constants.TAG, "RemoteDisplayRenderer postMessage MSG_CMD_VIDEO_ENCODER_START 1")
                        // 等 INIT_DONE 期间 displaySpec 又变了（用户连续切换显示模式等）
                        // → 按最新尺寸再协商一次，直到手机端编码尺寸与期望一致。
                        if (isInitRequestOutdated()) {
                            Logger.d(Constants.TAG, "RemoteDisplayRenderer displaySpec changed while waiting INIT_DONE, reinit again")
                            requestVideoReinit()
                        }
                        return true
                    }
                }
                encoderInfo?.let {
                    surfaceRequester?.requestSurface(it.width, it.height)
                    Logger.d(Constants.TAG, "RemoteDisplayRenderer decoder surface null")
                }
                return true
            }
            MSG_VIDEO_DATA -> {
                if (message.payloadSize != 0) {
                    frameDecoder?.feedFrame(message)
                }
                return true
            }
        }
        return false
    }

    fun onActivityStarted() {
        if (frameDecoder != null) {
            context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_START)
        }
    }

    fun onActivityStopped() {
        if (frameDecoder != null) {
            context.postMessage(MSG_CHANNEL_CMD, MSG_CMD_VIDEO_ENCODER_PAUSE)
        }
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        stop()
    }

    override fun onConnectionReattached(context: CarLifeContext) {
        stop()
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        handleSendVideoInit()
    }

    private fun handleSendVideoInit() {
        sendVideoInit(displaySpec)
    }

    /**
     * 已连接（视频已初始化）时，按当前 [displaySpec] 重新与手机端协商视频尺寸。
     *
     * 场景：运行期渲染区尺寸变化（切换「系统默认 / 沉浸式全屏」导致系统栏显隐、
     * 窗口尺寸变化等）。实现是重发 MSG_CMD_VIDEO_ENCODER_INIT，手机端回
     * MSG_CMD_VIDEO_ENCODER_INIT_DONE 后由 [onReceiveMessage] 重建解码器并重新 START。
     *
     * 约定：
     * - 未连接（还没收到过 INIT_DONE）、没有 surface、或尺寸本来就一致：
     *   什么都不做 —— 连接建立时 onConnectionEstablished 会按最新的 displaySpec 发 INIT；
     * - 旧解码器等 INIT_DONE 到达后才释放，所以手机端若不响应这次重协商，
     *   旧画面会继续解码显示（只是尺寸与渲染区不匹配），不会黑屏；
     * - 上一轮重协商还没收到 INIT_DONE 时不重复发；万一期间 displaySpec 又变了，
     *   INIT_DONE 里会比对最新值再补一次，最多多协商一轮，不会循环。
     *
     * @return 是否发起了重协商
     */
    @Synchronized
    fun requestVideoReinit(): Boolean {
        val info = encoderInfo
        if (info == null) {
            Logger.d(Constants.TAG, "requestVideoReinit skipped: video not initialized")
            return false
        }
        if (surface == null) {
            Logger.d(Constants.TAG, "requestVideoReinit skipped: no surface")
            return false
        }
        if (info.width == displaySpec.width
            && info.height == displaySpec.height
            && info.frameRate == displaySpec.frameRate
        ) {
            Logger.d(
                Constants.TAG,
                "requestVideoReinit skipped: phone already encodes ${info.width}x${info.height}"
            )
            return false
        }
        if (initPending) {
            Logger.d(Constants.TAG, "requestVideoReinit skipped: last INIT still pending")
            return false
        }
        sendVideoInit(displaySpec)
        return true
    }

    /** 最近一次 INIT 请求的尺寸是否已过期（等待期间 displaySpec 又变了） */
    private fun isInitRequestOutdated(): Boolean {
        val requested = lastInitRequestSpec ?: return false
        return requested.width != displaySpec.width
                || requested.height != displaySpec.height
                || requested.frameRate != displaySpec.frameRate
    }

    /**
     * 下发编码参数请求（MSG_CMD_VIDEO_ENCODER_INIT），手机端按此尺寸编码并回 INIT_DONE。
     */
    private fun sendVideoInit(spec: DisplaySpec) {
        val message = obtain(MSG_CHANNEL_CMD, ServiceTypes.MSG_CMD_VIDEO_ENCODER_INIT, 0)
        message.payload(CarlifeVideoEncoderInfo.newBuilder()
            .setWidth(spec.width)
            .setHeight(spec.height)
            .setFrameRate(spec.frameRate)
            .build())
        context.postMessage(message)
        lastInitRequestSpec = spec
        initPending = true
        Logger.d(
            Constants.TAG,
            "RemoteDisplayRenderer send MSG_CMD_VIDEO_ENCODER_INIT " +
                    "${spec.width}x${spec.height}@${spec.frameRate}"
        )
    }
}