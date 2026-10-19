package com.baidu.carlifevehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.baidu.carlife.sdk.util.Logger
import com.baidu.carlifevehicle.util.AutoStartPrefs

/**
 * 开机广播接收器：车机开机（/重启）完成后拉起 CarLife 主界面。
 *
 * ## 谁控制它
 * 设置项「开机启动」→ [AutoStartPrefs]：开 → 组件 ENABLED，系统开机广播会送到这里；
 * 关 → 组件 DISABLED（清单里也默认 disabled），系统不会在开机时唤醒本应用。
 *
 * ## 为什么只做 startActivity
 * 收到广播时本应用进程会被创建，VehicleApplication.onCreate 已把 SDK 初始化、
 * 传输通道等准备好，这里只需要把主界面拉到前台即可。
 *
 * 注意（系统限制）：
 *  - 部分车机在开机时尚未解锁 / 有自启动管理白名单，startActivity 可能被系统拦掉，
 *    这里只记日志，不弹任何东西；如被拦，需要在车机「自启动管理」里允许本应用。
 *  - 这里只处理 BOOT_COMPLETED（以及部分 MTK 车机的 QUICKBOOT_POWERON），
 *    不处理 USER_PRESENT，避免每次解锁都弹一次界面。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != ACTION_QUICKBOOT_POWERON) {
            return
        }
        if (!AutoStartPrefs.isEnabled(context)) {
            Logger.d(TAG, "auto start disabled, ignore $action")
            return
        }
        Logger.d(TAG, "boot completed, launching CarlifeActivity")
        val launch = Intent(context, CarlifeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        try {
            context.startActivity(launch)
        } catch (e: Exception) {
            // 后台启动 Activity 被系统限制（锁屏中 / 自启动被禁）等，忽略即可
            Logger.e(TAG, "start CarlifeActivity on boot failed: ", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        /** 部分 MTK 车机的"快速开机"广播，与 BOOT_COMPLETED 含义相同 */
        const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
    }
}
