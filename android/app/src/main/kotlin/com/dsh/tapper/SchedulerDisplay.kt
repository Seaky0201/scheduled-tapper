package com.dsh.tapper

import java.util.TimeZone

/**
 * 「下一次触发」展示信息与去重簿记的**纯逻辑**（P2 修复，覆盖审查 BUG-4 / BUG-5 / BUG-3 / BUG-6）。
 *
 * 抽出来的原因：这四段判定原先内联在依赖 Context 与协程的 [Scheduler.tick] 里，
 * 无法在 JVM 上断言，缺陷因此长期存在。抽成纯函数后每个缺陷都可被直接复现与锁定，
 * 沿用项目既有的「判定/执行分离」范式（与 [TapMath.BubbleStatus]、[TapMath.TestTarget] 一致）。
 */
object SchedulerDisplay {

    /**
     * 一条「下一次触发」展示信息。
     * @param pointId  时间点 id（便于测试与追溯到底展示的是谁）
     * @param label    展示文案（含重复次数后缀）
     * @param atMs     该次触发的绝对时刻（有效轴）
     * @param overdue  该时刻是否已经过去（逾期未执行，例如上一条序列仍在跑）
     */
    data class NextFire(
        val pointId: String,
        val label: String,
        val atMs: Long,
        val overdue: Boolean,
    )

    /**
     * 计算「下一次触发」。
     *
     * 两处修复：
     * - BUG-5：候选点必须**同时**满足 enabled 与「有步骤」。
     *   修复前只过滤了 enabled，于是没有步骤的点会占着这一行，
     *   但它永远不会触发（tick 的命中分支会 `p.steps.isEmpty()` 跳过）。
     * - BUG-4：同时标记该时刻是否已逾期，交给 [render] 决定文案，
     *   避免直接算出负秒数展示给用户。
     *
     * @param repeatStates 各点当前一轮的重复进度（与 Scheduler 的 repeatStates 同构）
     */
    fun compute(
        cfg: Config,
        repeatStates: Map<String, RepeatState>,
        nowMs: Long,
        zone: TimeZone,
    ): NextFire? {
        var best: NextFire? = null
        for (p in cfg.points) {
            // BUG-5：与 tick 的命中条件保持一致（enabled + 有步骤），否则展示与执行会不一致
            if (!p.enabled || p.steps.isEmpty()) continue

            val st = repeatStates[p.id]
            val total = TapMath.normalizeRepeatCount(p.repeatCount)
            val t: Long
            val suffix: String
            if (st != null && st.firedCount in 1 until total) {
                t = TapMath.repeatFireAt(st.baseAt, p.repeatIntervalMs, st.firedCount + 1)
                suffix = " 第" + (st.firedCount + 1) + "/" + total + "次"
            } else {
                t = TapMath.nextTriggerAt(p, nowMs, zone)
                suffix = ""
            }
            if (best == null || t < best.atMs) {
                best = NextFire(p.id, p.label() + suffix, t, t <= nowMs)
            }
        }
        return best
    }

    /**
     * 渲染展示文案。
     *
     * BUG-4：逾期时**绝不**输出负数秒。修复前是 `(bestAt - now) / 1000L`，
     * 当重复轮次因上一条序列未结束而等待时，bestAt 已是过去时刻 -> 显示 `(in -1s)`。
     */
    fun render(next: NextFire?, nowMs: Long): String {
        if (next == null) return "-"
        if (next.overdue) return next.label + " (已到点，逾期未执行)"
        return next.label + " (in " + ((next.atMs - nowMs) / 1000L) + "s)"
    }

    /**
     * 面板「显示 hh:mm:ss:h」那一行（BUG-3）。
     *
     * 修复前该值只在**打开面板那一刻**由 timeInfoText() 取一次快照，之后静止不走；
     * 现在它是「基准时刻」的纯函数，100ms 的时间刷新会重新求值，因此会持续走动。
     *
     * @param baseEpochMs 时间源基准时刻；null 表示时间源不可用（如北京时间尚未对时）
     */
    fun panelClockLine(axis: TimeAxis, baseEpochMs: Long?): String =
        if (baseEpochMs == null) "--:--:--:-" else axis.format(baseEpochMs)
}

/**
 * 触发去重键集合（P2 修复，审查 BUG-6）。
 *
 * 修复前的写法是：
 * ```
 * fired.add(key)
 * if (fired.size > 4096) fired.clear()   // 刚加入的 key 也被清掉
 * ```
 * 清空之后，**同一秒的下一个 tick** 会重新 `dueKey` 命中且集合已空，
 * 若此时上一条序列已结束（例如单步、delay 0），就会在同一个整秒内二次触发。
 *
 * 现改为**先进先出淘汰**：容量满时只移除最老的键，刚加入的键一定保留。
 * 同时把「是否首次加入」作为返回值交给调用方，避免 `contains` + `add` 两步产生歧义。
 */
class FiredKeys(private val max: Int = DEFAULT_MAX) {

    private val order = ArrayDeque<String>()
    private val set = HashSet<String>()

    val size: Int get() = set.size

    /** @return true 表示这是首次加入；false 表示该键已存在（调用方应跳过本次触发）。 */
    @Synchronized
    fun add(key: String): Boolean {
        if (!set.add(key)) return false
        order.addLast(key)
        // 容量控制：淘汰最老的键，绝不整体清空（BUG-6）
        while (order.size > max) {
            val oldest = order.removeFirst()
            set.remove(oldest)
        }
        return true
    }

    @Synchronized
    fun contains(key: String): Boolean = set.contains(key)

    @Synchronized
    fun clear() {
        order.clear()
        set.clear()
    }

    companion object {
        /** 容量上限：沿用修复前的 4096（每秒每点最多 1 个键，足够覆盖数天）。 */
        const val DEFAULT_MAX = 4096
    }
}
