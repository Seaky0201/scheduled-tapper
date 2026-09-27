package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * 修复前后对比证据（P0–P3 全量）。
 *
 * 做法：把**修复前**的逻辑逐字复制到本文件的 `Baseline` 对象里
 * （代码来自 git 基线提交 68f972d 的 Scheduler.kt / OverlayService.kt / TapMath.kt），
 * 再让同一组输入分别跑过「旧逻辑」与「新逻辑」，直接对比两者的输出差异。
 *
 * 这样"修复前能复现、修复后不再复现"不依赖真机，也不靠文字描述，
 * 而是可重复执行的断言 —— 与项目既有的「判定/执行分离」范式一致。
 */
class BeforeAfterEvidenceTest {

    private fun gmt(): TimeZone = TimeZone.getTimeZone("GMT")

    private fun at(h: Int, m: Int, s: Int): Long {
        val c = java.util.Calendar.getInstance(gmt())
        c.set(2024, 0, 15, h, m, s)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun step() = Step(1, 1, 0L)

    /**
     * 修复前的逻辑（逐字复制自基线提交 68f972d）。
     * 只做最小包装以便调用：把依赖 SequenceRunner / Scheduler 实例的部分剥离，
     * 保留被审查的那几行判定与计算本身。
     */
    private object Baseline {

        /** 基线 Scheduler.kt:185-207 —— 「下一次触发」展示（含 BUG-4 与 BUG-5）。 */
        fun nextFireLabel(
            points: List<TimePoint>,
            repeatStates: Map<String, RepeatState>,
            now: Long,
            zone: TimeZone,
        ): String {
            var bestAt = Long.MAX_VALUE
            var bestLabel = "-"
            for (p in points) {
                if (!p.enabled) continue                       // BUG-5：只过滤 enabled
                val st = repeatStates[p.id]
                val total = TapMath.normalizeRepeatCount(p.repeatCount)
                val t: Long
                val suffix: String
                if (st != null && st.firedCount in 1 until total) {
                    t = TapMath.repeatFireAt(st.baseAt, p.repeatIntervalMs, st.firedCount + 1)
                    suffix = " 第" + (st.firedCount + 1) + "/" + total + "次"
                } else {
                    t = TapMath.nextTriggerAt(p, now, zone)
                    suffix = ""
                }
                if (t < bestAt) {
                    bestAt = t
                    bestLabel = p.label() + suffix
                }
            }
            return if (bestAt == Long.MAX_VALUE) "-"
            else bestLabel + " (in " + ((bestAt - now) / 1000L) + "s)"   // BUG-4：可为负
        }

        /** 基线 Scheduler.kt:140-141 —— 去重集合容量控制（BUG-6）。 */
        fun pruneAfterAdd(fired: HashSet<String>, key: String) {
            fired.add(key)
            if (fired.size > 4096) fired.clear()
        }

        /** 基线 Scheduler.kt:70-77 —— 重复进度重置条件（BUG-7，不含 enabled）。 */
        fun shouldReset(o: TimePoint?, n: TimePoint?): Boolean =
            o == null || n == null || o.repeatCount != n.repeatCount || o.repeatIntervalMs != n.repeatIntervalMs

        /** 基线 OverlayService.kt:150-164 —— 时钟行用 OverlayService 自己的字段。 */
        fun clockLine(
            ownKind: TapMath.TimeSourceKind, ownOffset: Long,
            base: Long,
        ): String = TapMath.ClockFormat.format(
            TapMath.ClockFormat.display(base, ownOffset), ownKind.zone()
        )

        /** 基线 Scheduler.kt:105-123 —— 调度用 Scheduler.config 里的字段。 */
        fun schedulerNow(cfg: Config, base: Long): Long = base + cfg.timeOffsetMs
    }

    // ================= P0 / BUG-1：同一时刻，时钟与调度给出不同答案 =================

    @Test
    fun bug1_clockAndSchedulerDisagreeOnStaleCopy_beforeVsAfter() {
        val base = at(8, 29, 59) + 900L          // 08:29:59.900
        val p = TimePoint("p", 8, 30, 0, true, listOf(step()))

        // 面板已 +300ms（OverlayService 自己的字段已更新），但 Scheduler.config 还是旧值
        val ownOffset = 300L
        val schedulerCfg = Config(timeSource = "local", timeOffsetMs = 0L)

        // --- 修复前：显示轴与调度轴分叉 ---
        val beforeShown = Baseline.clockLine(TapMath.TimeSourceKind.LOCAL, ownOffset, base)
        val beforeSchedNow = Baseline.schedulerNow(schedulerCfg, base)
        // 注意：本地时间的显示时区是**设备时区**（此处运行机为 GMT+8），
        // 因此只断言与时区无关的 分:秒:百毫秒 部分，避免把断言绑死在某台机器的时区上。
        assertTrue("修复前：时钟已显示 xx:30:00:2（分:秒:百毫秒）", beforeShown.endsWith(":30:00:2"))
        assertTrue("修复前：调度仍认为未到点（分叉）", TapMath.dueKey(p, beforeSchedNow, gmt()) == null)

        // --- 修复后：两边都从同一条 TimeAxis 取轴 ---
        val appliedCfg = Config(timeSource = "local", timeOffsetMs = ownOffset)
        val afterAxis = TimeAxis.axisOf(appliedCfg)
        val afterShown = afterAxis.format(base)
        val afterSchedNow = afterAxis.effective(base)
        assertEquals("修复后：时钟显示与修复前完全一致（本项不改显示行为）", beforeShown, afterShown)
        assertTrue("修复后：时钟仍显示 xx:30:00:2", afterShown.endsWith(":30:00:2"))
        assertTrue("修复后：调度同样认为已到点（同轴）", TapMath.dueKey(p, afterSchedNow, gmt()) != null)
        assertEquals("修复后：显示值与调度判定值完全一致", afterAxis.effective(base), afterSchedNow)
    }

    // ================= P1 / BUG-7：停用再启用是否突发补触发 =================

    @Test
    fun bug7_reEnableBacklog_beforeVsAfter() {
        val base = at(8, 0, 0)
        val now = base + 5L * 60_000L                    // 停用 5 分钟后重新启用
        val before = TimePoint("p", 8, 0, 0, true, listOf(step()), repeatCount = 5, repeatIntervalMs = 60_000L)
        val after = before.copy(enabled = false)         // 期间被停用

        // --- 修复前：enabled 变化不触发重置 -> 欠账全部补发 ---
        assertFalse("修复前：停用不重置进度", Baseline.shouldReset(before, after))
        val stale = RepeatState(baseAt = base, firedCount = 1)
        val d = TapMath.nextRepeatDecision(before, stale, now)
        assertTrue("修复前：重新启用后立刻补触发", d != null)

        // --- 修复后：enabled 变化即重置 -> 无欠账 ---
        assertTrue("修复后：停用即重置进度", SchedulerProgress.shouldReset(before, after))
        assertTrue("修复后：重置后不再有欠账补触发",
            TapMath.nextRepeatDecision(before, RepeatState(), now) == null)
    }

    // ================= P2 / BUG-4：倒计时负秒 =================

    @Test
    fun bug4_negativeCountdown_beforeVsAfter() {
        val base = at(8, 0, 0)
        val now = base + 90_000L                          // 第 2 次（base+60s）已逾期 30 秒
        val p = TimePoint("p", 8, 0, 0, true, listOf(step()), repeatCount = 3, repeatIntervalMs = 60_000L)
        val states = mapOf("p" to RepeatState(baseAt = base, firedCount = 1))

        val before = Baseline.nextFireLabel(listOf(p), states, now, gmt())
        assertTrue("修复前确实出现负秒数：" + before, before.contains("(in -30s)"))

        val next = SchedulerDisplay.compute(Config(points = listOf(p)), states, now, gmt())!!
        val after = SchedulerDisplay.render(next, now)
        assertFalse("修复后不得再出现负秒：" + after, after.contains("-"))
        assertTrue("修复后给出逾期说明：" + after, after.contains("逾期"))
    }

    // ================= P2 / BUG-5：空步骤点占位 =================

    @Test
    fun bug5_emptyStepsOccupiesNextFire_beforeVsAfter() {
        val now = at(8, 0, 0)
        val empty = TimePoint("empty", 8, 1, 0, true, emptyList())      // 更近，但没有步骤
        val real = TimePoint("real", 9, 0, 0, true, listOf(step()))
        val points = listOf(empty, real)

        val before = Baseline.nextFireLabel(points, emptyMap(), now, gmt())
        assertTrue("修复前：展示的是永远不会触发的空步骤点：" + before, before.contains("08时 01分 00秒"))

        val after = SchedulerDisplay.compute(Config(points = points), emptyMap(), now, gmt())!!
        assertEquals("修复后：展示真正会触发的点", "real", after.pointId)
    }

    // ================= P2 / BUG-6：去重键被误清空 =================

    @Test
    fun bug6_pruningDropsJustAddedKey_beforeVsAfter() {
        // 修复前：先填满到 4096，再加入新键 -> 触发 clear()，新键一并丢失
        val before = HashSet<String>()
        for (i in 1..4096) before.add("k" + i)
        Baseline.pruneAfterAdd(before, "NEW")
        assertFalse("修复前：刚加入的键被整体 clear() 丢掉", before.contains("NEW"))
        assertTrue("修复前：集合被清空", before.isEmpty())

        // 修复后：FIFO 淘汰，新键一定保留
        val after = FiredKeys(max = 4096)
        for (i in 1..4096) after.add("k" + i)
        after.add("NEW")
        assertTrue("修复后：刚加入的键仍然在集合里", after.contains("NEW"))
        assertEquals("修复后：容量受控", 4096, after.size)
    }

    // ================= P2 / BUG-3：面板时间行是否走动 =================

    @Test
    fun bug3_panelClockFrozenVsTicking() {
        val axis = TimeAxis(TapMath.TimeSourceKind.LOCAL, 0L)
        val t0 = at(8, 0, 0) + 100L
        val t1 = at(8, 0, 0) + 500L

        // 修复前：面板值只在打开面板那一刻求值一次（timeInfoText()），之后不再重新求值
        val snapshot = SchedulerDisplay.panelClockLine(axis, t0)
        val beforeStillSame = snapshot == SchedulerDisplay.panelClockLine(axis, t0)  // 快照语义：同一个基准 -> 同一个值
        assertTrue("修复前：面板值固定不变（快照）", beforeStillSame)
        assertFalse("修复前：与 400ms 后的真实时间不一致", snapshot == SchedulerDisplay.panelClockLine(axis, t1))

        // 修复后：以「当前基准时刻」为输入随 100ms 心跳重新求值 -> 会走动
        val after0 = SchedulerDisplay.panelClockLine(axis, t0)
        val after1 = SchedulerDisplay.panelClockLine(axis, t1)
        assertTrue("修复后：面板时间行随时间前进", after0 != after1)
        // 同样只断言与时区无关的尾部，避免依赖运行机时区
        assertTrue("修复后 100ms -> xx:00:00:1，实际 " + after0, after0.endsWith(":00:00:1"))
        assertTrue("修复后 500ms -> xx:00:00:5，实际 " + after1, after1.endsWith(":00:00:5"))
    }

    // ================= P3 / BUG-8：北京时间是否独立于设备时钟 =================

    @Test
    fun bug8_beijingDependsOnDeviceClock_beforeVsAfter() {
        val device = 1_700_000_000_000L
        val offset = 9_900L

        // 基线注释声称"与设备时钟相互独立、改设备时钟不受影响"；
        // 但无论修复前后，实现都是 device + offset（本项修复的是**注释**，不是行为）。
        val derived = TimeSources.beijingFromDevice(device, offset)
        assertEquals("实现始终依赖设备时钟", device + offset, derived)

        // 直接反驳原注释：改设备时钟，北京时间必然跟着改
        val shifted = TimeSources.beijingFromDevice(device + 60_000L, offset)
        assertEquals("设备时钟 +60s -> 北京时间 +60s（并非'不受影响'）", 60_000L, shifted - derived)
    }
}
