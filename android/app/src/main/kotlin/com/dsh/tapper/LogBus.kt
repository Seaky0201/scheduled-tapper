package com.dsh.tapper

import android.content.Context
import android.content.Intent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 可观测证据：内存环形日志 + 广播给 Flutter UI。 */
object LogBus {
    const val ACTION_LOG = "com.dsh.tapper.LOG"
    const val ACTION_STATE = "com.dsh.tapper.STATE"
    const val ACTION_PICK = "com.dsh.tapper.PICK"
    const val ACTION_PICK_MODE = "com.dsh.tapper.PICK_MODE"
    const val ACTION_CMD = "com.dsh.tapper.CMD"
    const val ACTION_PANEL = "com.dsh.tapper.PANEL"
    /** 悬浮窗直接改写了配置（选点导入），需要主界面重新读取。 */
    const val ACTION_CONFIG = "com.dsh.tapper.CONFIG"
    const val EXTRA_LINE = "line"

    private const val MAX = 500
    private val buf = ArrayDeque<String>()

    /**
     * 格式化时间戳。
     *
     * 注意：行首前缀**始终**由 [add] 用未微调的系统时间生成（见其实现），
     * 这里的 [zone] 只用于日志正文里展示「时间源原轴」的计划/实际时刻，
     * 使北京时间在任何设备上都按 UTC+8 显示。
     */
    fun stamp(at: Long = System.currentTimeMillis(), zone: java.util.TimeZone? = null): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        if (zone != null) fmt.timeZone = zone
        return fmt.format(Date(at))
    }

    @Synchronized
    fun add(ctx: Context?, level: String, msg: String) {
        val line = stamp() + " [" + level + "] " + msg
        buf.addLast(line)
        while (buf.size > MAX) buf.removeFirst()
        if (ctx != null) {
            try {
                ctx.sendBroadcast(Intent(ACTION_LOG).setPackage(ctx.packageName).putExtra(EXTRA_LINE, line))
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized fun text(): String = buf.joinToString("\n")
    @Synchronized fun clear() = buf.clear()
}
