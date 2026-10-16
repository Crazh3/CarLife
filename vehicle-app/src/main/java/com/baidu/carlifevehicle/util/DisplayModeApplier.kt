package com.baidu.carlifevehicle.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import com.baidu.carlife.sdk.util.Logger

/**
 * 把「显示方式」落到窗口上（见 [DisplayModePrefs]）。
 *
 * - 系统默认：撤销沉浸式留下的痕迹（只把导航栏显示回来、恢复默认系统栏行为）；
 *   状态栏仍然交给主题的 `android:windowFullscreen` 控制，与改动前的表现一致。
 * - 沉浸式全屏：隐藏状态栏 + 导航栏，并设为 sticky —— 系统栏被边缘滑动临时唤出后自动收回。
 *
 * minSdk 21，所以分两条分支：API 30+ 用 [WindowInsetsController]，
 * 更低版本用已废弃但仍生效的 `systemUiVisibility`。
 */
object DisplayModeApplier {

    private const val TAG = "DisplayModeApplier"

    fun apply(activity: Activity, immersive: Boolean) {
        val window = activity.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (immersive) {
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                // 只恢复导航栏：状态栏由主题的 windowFullscreen 决定，不去动它
                // （systemBarsBehavior 不再修改，默认就是"滑动唤出且不自动收回"）
                controller.show(WindowInsets.Type.navigationBars())
            }
            Logger.d(TAG, "apply immersive=$immersive (insetsController)")
        } else {
            @Suppress("DEPRECATION")
            val flags = if (immersive) {
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            } else {
                // 0 = 不做额外处理，等同本项设置出现之前的默认状态
                0
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
            Logger.d(
                TAG,
                "apply immersive=$immersive (systemUiVisibility=0x${Integer.toHexString(flags)})"
            )
        }
    }
}
