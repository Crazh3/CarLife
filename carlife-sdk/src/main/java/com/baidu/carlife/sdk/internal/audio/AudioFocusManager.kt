package com.baidu.carlife.sdk.internal.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.baidu.carlife.sdk.Constants
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlife.sdk.util.annotations.DoNotStrip
import java.util.*

@DoNotStrip
class AudioFocusManager(context: Context) {

    @DoNotStrip
    companion object {
        const val STREAM_MUSIC: Int = 1
        const val STREAM_NAVI: Int   = 2
        const val STREAM_VR: Int    = 3

        const val AUDIOFOCUS_REQUEST_FAILED = 0
        const val AUDIOFOCUS_REQUEST_GRANTED = 1
        const val AUDIOFOCUS_REQUEST_DELAYED = 2
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // 当前请求过焦点的请求集合
    private val requests
            = mutableMapOf<AudioManager.OnAudioFocusChangeListener, OnAudioFocusChangeListenerWrapper>()
    // 当前占用焦点的请求
    private var grantRequest: OnAudioFocusChangeListenerWrapper? = null

    // 被delay的请求集合
    private val delayedRequests = LinkedList<OnAudioFocusChangeListenerWrapper>()

    inner class OnAudioFocusChangeListenerWrapper(
            val stream: Int,
            val focusType: Int,
            val listener: AudioManager.OnAudioFocusChangeListener)
        : AudioManager.OnAudioFocusChangeListener {
        var audioFocusRequest: AudioFocusRequest? = null

        override fun onAudioFocusChange(focusChange: Int) {
            Logger.d(Constants.TAG, "AudioFocusManager onAudioFocusChange ", listener, " ", focusChange)
            listener.onAudioFocusChange(focusChange)
            if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                grantRequest = this
            }
            else if (focusChange == AudioManager.AUDIOFOCUS_LOSS) {
                abandonAudioFocus(listener)
            }
        }

        override fun equals(other: Any?): Boolean {
            if (other == null || other !is OnAudioFocusChangeListenerWrapper) {
                return false
            }
            return listener == other.listener
        }

        override fun hashCode(): Int {
            return listener.hashCode()
        }
    }

    @Synchronized
    fun requestAudioFocus(listener: AudioManager.OnAudioFocusChangeListener,
                          streamType: Int,
                          focusType: Int,
                          focusGrantDelayed: Boolean = false,
                          focusForceGrant: Boolean = false): Int {
        grantRequest?.let {
            if (it.listener == listener) {
                // 如果是同一个，直接返回
                return AUDIOFOCUS_REQUEST_GRANTED
            }
            // 当前已经获取到焦点，并且优先级大于待请求的
            if (it.stream > streamType && !focusForceGrant) {
                if (focusGrantDelayed) {
                    val wrapper = OnAudioFocusChangeListenerWrapper(streamType, focusType, listener)
                    enqueueDelayedRequest(wrapper)
                    Logger.d(Constants.TAG,
                            "AudioFocusManager requestAudioFocus ", listener, " ", AUDIOFOCUS_REQUEST_DELAYED)
                    return AUDIOFOCUS_REQUEST_DELAYED
                }
                Logger.d(Constants.TAG,
                        "AudioFocusManager requestAudioFocus ", listener, " ", AUDIOFOCUS_REQUEST_FAILED)
                return AUDIOFOCUS_REQUEST_FAILED
            }
        }
        val wrapper = OnAudioFocusChangeListenerWrapper(streamType, focusType, listener)
        return doSystemRequestAudioFocus(wrapper)
    }

    @Synchronized
    fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) {
        Logger.d(Constants.TAG, "AudioFocusManager abandonAudioFocus")
        val request = requests.remove(listener)
        if (request != null) {
            doSystemAbandonAudioFocus(request)

            if (delayedRequests.isNotEmpty()) {
                val delayedRequest = delayedRequests.pollFirst()!!
                val result = doSystemRequestAudioFocus(delayedRequest)
                if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    delayedRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                }
                else {
                    delayedRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
                }
            }
        }
        else {
            removeDelayedRequest(listener)
        }
    }

    private fun doSystemRequestAudioFocus(listener: OnAudioFocusChangeListenerWrapper): Int {
        // 焦点、播放（AudioPlayTask）、试听（AudioChannelPreview）必须解析成同一份
        // AudioAttributes，否则「试听是这个通道、真正播报是另一条通道」，见 AudioChannelConfig.attributes
        val result = if (Build.VERSION.SDK_INT >= 26) {
            val focusRequest: AudioFocusRequest = AudioFocusRequest
                    .Builder(listener.focusType)
                    .setAudioAttributes(AudioChannelConfig.attributes(listener.stream))
                    .setOnAudioFocusChangeListener(listener)
                    .build()
            listener.audioFocusRequest = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            requestLegacyAudioFocus(listener)
        }

        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            grantRequest = listener
            // 缓存
            requests[listener.listener] = listener
        }
        Logger.d(Constants.TAG,
                "AudioFocusManager requestAudioFocus ", listener.listener, " ", result)
        return result
    }

    private fun doSystemAbandonAudioFocus(listener: OnAudioFocusChangeListenerWrapper) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                listener.audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    return
                }
            }
            audioManager.abandonAudioFocus(listener)
        }
        finally {
            if (grantRequest == listener) {
                grantRequest = null
            }
        }
    }

    private fun enqueueDelayedRequest(request: OnAudioFocusChangeListenerWrapper) {
        // 如果有相同的，直接返回
        if (delayedRequests.contains(request)) {
            return
        }

        if (delayedRequests.isEmpty()) {
            delayedRequests.add(request)
        }
        else {
            val iterator = delayedRequests.listIterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (request.stream == next.stream || !iterator.hasNext()) {
                    // 相等插入到后边
                    iterator.add(request)
                } else if (request.stream > next.stream) {
                    iterator.set(request)
                    iterator.add(next)
                }
            }
        }
    }

    private fun removeDelayedRequest(listener: AudioManager.OnAudioFocusChangeListener) {
        val iterator = delayedRequests.listIterator()
        while (iterator.hasNext()) {
            val request = iterator.next()
            if (request.listener == listener) {
                iterator.remove()
                return
            }
        }
    }

    /**
     * API < 26 的旧版焦点请求：streamType 用设置页选的通道值（非法值由
     * [AudioChannelConfig.legacyStreamType] 兜底，不会把非法值丢给系统）；
     * 请求失败只记日志，避免一个非法通道值把整个 TTS 播报流程打断。
     */
    private fun requestLegacyAudioFocus(listener: OnAudioFocusChangeListenerWrapper): Int =
        try {
            audioManager.requestAudioFocus(
                listener,
                AudioChannelConfig.legacyStreamType(listener.stream),
                listener.focusType)
        } catch (e: Exception) {
            Logger.e(Constants.TAG, "AudioFocusManager requestAudioFocus failed: $e")
            AudioManager.AUDIOFOCUS_REQUEST_FAILED
        }
}