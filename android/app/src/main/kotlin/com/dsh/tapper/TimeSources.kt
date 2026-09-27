package com.dsh.tapper

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 时间源提供者：把「所选时间源」解析为可用的基准时间。
 *
 * 各源的获取方式与同步策略（**互不相同，不静默降级**）：
 *
 * 1. 本地时间 LOCAL
 *    直接读设备系统时钟 System.currentTimeMillis()。
 *    精度取决于系统自身与运营商/厂商的自动对时（通常 ±1s 内）。
 *    同步策略：无（系统负责）。
 *
 * 2. 北京时间 BEIJING
 *    经 SNTP 向授时服务器（ntp.aliyun.com 等）对时，取得**独立于设备时钟**的偏移量，
 *    之后用 offset + 设备单调经过的毫秒数推算当前北京时间。
 *    精度：四时间戳算法抵消网络单程延迟，典型 ±10~50ms。
 *    同步策略：进入前台服务时对时一次 + 之后每小时自动重新对时（避免长期漂移）。
 *    失败处理：保留上一次成功的偏移并标记「过期」；从未成功过则标记为不可用，
 *    **绝不回落成设备时钟**（否则与「本地时间」变成同一个源）。
 *
 * 注意：这里的偏移是「服务器时间 - 设备时间」，因此即便用户改了设备时钟，
 * 只要重新对时就能纠正；而两次对时之间我们用的是设备时钟的**增量**，
 * 所以期间修改设备时钟会带来偏差——这是 SNTP 的固有限制，已如实记录。
 */
object TimeSources {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sntp-sync").apply { isDaemon = true }
    }

    /** 最近一次成功的「服务器时间 - 设备时间」偏移（毫秒）。0 表示从未成功。 */
    private val offsetMs = AtomicLong(0L)

    /** 最近一次对时的往返延迟（毫秒），-1 表示未知。 */
    private val lastRttMs = AtomicLong(-1L)

    /** 最近一次对时成功的时刻（设备时钟），0 表示从未成功。 */
    private val lastSyncAt = AtomicLong(0L)

    /** 对时是否正在进行。 */
    @Volatile private var syncing = false

    /** 最近一次失败原因（用于界面/日志呈现，不隐藏失败）。 */
    @Volatile private var lastError: String? = null

    fun hasSynced(): Boolean = lastSyncAt.get() > 0L
    fun isSyncing(): Boolean = syncing
    fun offset(): Long = offsetMs.get()
    fun rtt(): Long = lastRttMs.get()
    fun lastSyncAtMs(): Long = lastSyncAt.get()
    fun error(): String? = lastError

    /**
     * 取当前时间源对应的基准时间（epoch 毫秒）。
     *
     * @param kind 所选时间源
     * @return 基准时间；当时间源不可用（如北京时间尚未对时成功）时返回 null，
     *         由调用方显示「不可用」而不是伪造一个值。
     */
    fun now(kind: TapMath.TimeSourceKind): Long? = when (kind) {
        TapMath.TimeSourceKind.LOCAL -> System.currentTimeMillis()
        TapMath.TimeSourceKind.BEIJING ->
            if (hasSynced()) beijingFromDevice(System.currentTimeMillis(), offsetMs.get()) else null
    }

    /**
     * 由「设备时钟 + 对时偏移」推算北京时间（纯函数，便于单测锁定语义）。
     *
     * **注意（P3）**：这个式子明确依赖设备时钟，两次对时之间若改动设备时钟会带来偏差
     * —— 这正是 [TapMath.TimeSourceKind] 注释原先写错的地方（曾声称"与设备时钟相互独立"）。
     * 抽成具名纯函数是为了让该语义可被测试直接锁定，而不是只靠注释描述。
     */
    fun beijingFromDevice(deviceMs: Long, offset: Long): Long = deviceMs + offset

    /**
     * 触发一次异步对时（不阻塞调用线程）。
     * @param ctx 用于写日志；可为 null
     * @param reason 本次对时的原因（写进日志，便于追溯同步策略）
     */
    fun syncAsync(ctx: Context?, reason: String) {
        if (syncing) return
        syncing = true
        executor.execute {
            try {
                val (serverMs, rtt) = SntpClient.fetch()
                val deviceMs = System.currentTimeMillis()
                val off = serverMs - deviceMs
                offsetMs.set(off)
                lastRttMs.set(rtt)
                lastSyncAt.set(deviceMs)
                lastError = null
                LogBus.add(
                    ctx, "SYNC",
                    "北京时间已对时（" + reason + "）：偏差 " + off + "ms ｜ 往返 " + rtt + "ms"
                )
            } catch (t: Throwable) {
                lastError = t.message ?: t.toString()
                LogBus.add(ctx, "ERROR", "北京时间对时失败（" + reason + "）：" + lastError)
            } finally {
                syncing = false
            }
        }
    }

    /** 距离上次成功对时是否已超过 [intervalMs]（用于小时级重新对时）。 */
    fun needsResync(nowMs: Long, intervalMs: Long): Boolean {
        val last = lastSyncAt.get()
        return last <= 0L || (nowMs - last) >= intervalMs
    }

    /** 供日志/界面显示的状态摘要。 */
    fun statusText(): String = when {
        syncing -> "对时中…"
        hasSynced() -> "已对时（偏差 " + offsetMs.get() + "ms，往返 " + lastRttMs.get() + "ms）"
        lastError != null -> "对时失败：" + lastError
        else -> "尚未对时"
    }
}
