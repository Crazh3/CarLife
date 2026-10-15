package com.baidu.carlife.sdk.util.orientation

import android.content.Context
import android.os.Build
import android.view.OrientationEventListener
import android.view.WindowManager
import com.baidu.carlife.sdk.CarLifeContext
import com.baidu.carlife.sdk.ConfigurationChangeListener
import com.baidu.carlife.sdk.ConnectionChangeListener

class UseOrientationEventListener(private val context: CarLifeContext)
    : OrientationEventListener(context.applicationContext), ConnectionChangeListener {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    private val legacyWindows: WindowManager? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        } else null

    private var rotation: Int = currentRotation

    init {
        context.registerConnectionChangeListener(this)
    }

    private val currentRotation: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appContext.display?.rotation ?: 0
        } else {
            @Suppress("DEPRECATION")
            legacyWindows?.defaultDisplay?.rotation ?: 0
        }

    override fun onOrientationChanged(degree: Int) {
        val newRotation = currentRotation
        if (newRotation != rotation && (rotation + newRotation) % 2 != 0) {
            rotation = newRotation
            context.notifyConfigurationChange(ConfigurationChangeListener.ORIENTATION)
        }
    }

    override fun onConnectionEstablished(context: CarLifeContext) {
        enable()
    }

    override fun onConnectionDetached(context: CarLifeContext) {
        disable()
    }
}