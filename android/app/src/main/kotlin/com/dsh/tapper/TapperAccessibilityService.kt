package com.dsh.tapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * 点击注入：使用系统无障碍手势（dispatchGesture）。无需 root。
 * 坐标以「当前屏幕」为准，超出当前屏幕则拒绝执行而不是乱点。
 */
class TapperAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        LogBus.add(applicationContext, "OK", "无障碍服务已连接，可注入点击")
        TapperPlugin.emitState(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 本工具按系统时间触发，不依赖无障碍事件
    }

    override fun onInterrupt() {
        LogBus.add(applicationContext, "WARN", "无障碍服务 onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        LogBus.add(applicationContext, "WARN", "无障碍服务已断开")
        TapperPlugin.emitState(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** 在当前屏幕上派发一次点击，返回是否成功派发。 */
    fun tap(x: Int, y: Int, durationMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            LogBus.add(applicationContext, "ERROR", "系统版本低于 API 24，不支持 dispatchGesture")
            return false
        }
        val dm = resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        if (x < 0 || y < 0 || x >= w || y >= h) {
            LogBus.add(applicationContext, "ERROR", ("坐标(" + x + "," + y + ") 超出当前屏幕 " + w + "x" + h + "，已拒绝执行（防止乱点）"))
            return false
        }
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(1L, 2000L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return try {
            val ok = dispatchGesture(gesture, null, null)
            if (!ok) LogBus.add(applicationContext, "ERROR", ("dispatchGesture 返回 false：点击(" + x + "," + y + ") 未派发"))
            ok
        } catch (t: Throwable) {
            LogBus.add(applicationContext, "ERROR", "dispatchGesture 异常: " + t)
            false
        }
    }

    companion object {
        @Volatile
        var instance: TapperAccessibilityService? = null
            private set

        fun isReady(): Boolean = instance != null

        /** 是否已在系统设置中勾选本无障碍服务（进程尚未连接时也能判定）。 */
        fun isEnabledInSettings(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(
                ctx.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val full = ctx.packageName + "/" + TapperAccessibilityService::class.java.name
            val short = ctx.packageName + "/." + TapperAccessibilityService::class.java.simpleName
            return flat.split(':').any { it.equals(full, true) || it.equals(short, true) }
        }
    }
}
