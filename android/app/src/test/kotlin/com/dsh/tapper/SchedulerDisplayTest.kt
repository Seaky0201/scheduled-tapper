package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 复现与回归测试（审查 BUG-4 / BUG-5 / BUG-3 / BUG-6）。
 *
 * 四个缺陷的共同点：都发生在 [Scheduler.tick] 的**展示与簿记**环节，
 * 原先内联在依赖 Context/协程的 Scheduler 里，无法在 JVM 上断言。
 * 本阶段把这三段判定抽成纯函数（沿用项目既有「判定/执行分离」范式），
 * 使每个缺陷都能被直接复现与锁定。
 *
 * - BUG-4：重复轮次逾期时倒计时显示负秒数
 * - BUG-5：没有步骤的时间点会占着「下次触发」，但它永远不会触发
 * - BUG-3：面板时间行是打开面板那一刻的快照，静止不走
 * - BUG-6：fired 去重集合清空时把刚加入的 key 一并清掉，同秒内可重复触发
 */
class SchedulerDisplayTest {

    private fun gmt() = java.util.TimeZone.getTimeZone("GMT")

    private fun point(
        id: String = "p1",
        steps: List<Step> = listOf(Step(1, 1, 0L)),
        repeatCount: Int = 1,
        repeatIntervalMs: Long = 0L,
        enabled: Boolean = true,
    ) = TimePoint(id, 8, 0, 0, enabled, steps,
        repeatCount = repeatCount, repeatIntervalMs = repeatIntervalMs)

    /** 2024-01-15 08:00:00 GMT 的绝对时刻。 */
    private fun at(h: Int, m: Int, s: Int): Long {
        val c = java.util.Calendar.getInstance(gmt())
        c.set(2024, 0, 15, h, m, s)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ================= BUG-4：逾期不得显示负秒 =================

    @Test
    fun overdueRepeatNeverShowsNegativeSeconds() {
        // 第 1 次已在 base 触发，间隔 60s；此刻 now 已超过第 2 次计划时刻 30 秒
        val base = at(8, 0, 0)
        val now = base + 90_000L
        val cfg = Config(points = listOf(point(repeatCount = 3, repeatIntervalMs = 60_000L)))
        val states = mapOf("p1" to RepeatState(baseAt = base, firedCount = 1))

        val next = SchedulerDisplay.compute(cfg, states, now, gmt())
        val text = SchedulerDisplay.render(next, now)

        assertFalse("修复前会显示 (in -30s) 这种负秒数（BUG-4）：" + text, text.contains("-"))
        assertTrue("逾期应给出可读的等待说明：" + text, text.contains("逾期") || text.contains("立即"))
    }

    @Test
    fun overdueIsFlaggedExplicitly() {
        val base = at(8, 0, 0)
        val now = base + 90_000L
        val cfg = Config(points = listOf(point(repeatCount = 3, repeatIntervalMs = 60_000L)))
        val states = mapOf("p1" to RepeatState(baseAt = base, firedCount = 1))

        val next = SchedulerDisplay.compute(cfg, states, now, gmt())!!
        assertTrue("应被标记为逾期", next.overdue)
        assertEquals(base + 60_000L, next.atMs)
    }

    @Test
    fun normalFutureCountdownStillShowsSeconds() {
        val base = at(8, 0, 0)
        val now = base + 30_000L     // 距第 2 次（base+60s）还有 30 秒
        val cfg = Config(points = listOf(point(repeatCount = 3, repeatIntervalMs = 60_000L)))
        val states = mapOf("p1" to RepeatState(baseAt = base, firedCount = 1))

        val next = SchedulerDisplay.compute(cfg, states, now, gmt())!!
        assertFalse("未逾期不应标记 overdue", next.overdue)
        assertEquals("未逾期仍应显示剩余秒数", " (in 30s)", SchedulerDisplay.render(next, now).substringAfterLast("次"))
    }

    // ================= BUG-5：没有步骤的点不得占位 =================

    @Test
    fun pointWithoutStepsIsNeverAdvertisedAsNextFire() {
        // 空步骤点排在前面且时刻更近；有步骤点在后。
        // 修复前：展示循环只过滤 !enabled，空步骤点会被选中，但到点永远不触发。
        val now = at(8, 0, 0)
        val emptyPoint = point(id = "empty", steps = emptyList())
        val realPoint = TimePoint("real", 9, 0, 0, true, listOf(Step(1, 1, 0L)))
        val cfg = Config(points = listOf(emptyPoint, realPoint))

        val next = SchedulerDisplay.compute(cfg, emptyMap(), now, gmt())!!
        assertEquals("必须展示真正会触发的那一个（BUG-5）", "real", next.pointId)
    }

    @Test
    fun allPointsWithoutStepsYieldsNoNextFire() {
        val now = at(8, 0, 0)
        val cfg = Config(points = listOf(point(id = "a", steps = emptyList())))

        assertNull("没有任何可触发的点时应为「无」而不是占位", SchedulerDisplay.compute(cfg, emptyMap(), now, gmt()))
        assertEquals("-", SchedulerDisplay.render(null, now))
    }

    @Test
    fun disabledPointsAreStillExcluded() {
        val now = at(8, 0, 0)
        val cfg = Config(points = listOf(point(id = "off", enabled = false)))
        assertNull(SchedulerDisplay.compute(cfg, emptyMap(), now, gmt()))
    }

    @Test
    fun pointWithStepsIsAdvertised() {
        val now = at(8, 0, 0)
        val cfg = Config(points = listOf(point(id = "ok")))
        assertEquals("ok", SchedulerDisplay.compute(cfg, emptyMap(), now, gmt())!!.pointId)
    }

    // ================= BUG-3：面板时间行必须随时间走动 =================

    @Test
    fun panelTimeLineChangesAsTimeAdvances() {
        // 修复前：面板那行只在打开面板/点按钮时取一次值 -> 静止不走。
        // 修复后：它是「基准时刻」的函数，100ms 刷新会重新求值，因此必须随 t 变化。
        val axis = TimeAxis(TapMath.TimeSourceKind.LOCAL, 0L)
        val t0 = at(8, 0, 0) + 100L
        val t1 = at(8, 0, 0) + 500L

        val line0 = SchedulerDisplay.panelClockLine(axis, t0)
        val line1 = SchedulerDisplay.panelClockLine(axis, t1)

        assertTrue("面板时间行必须随时刻变化（BUG-3）", line0 != line1)
        assertTrue(line0.endsWith(":1"))
        assertTrue(line1.endsWith(":5"))
    }

    @Test
    fun panelTimeLineReflectsManualOffset() {
        val base = at(8, 0, 0) + 100L
        val noOffset = SchedulerDisplay.panelClockLine(TimeAxis(TapMath.TimeSourceKind.LOCAL, 0L), base)
        val withOffset = SchedulerDisplay.panelClockLine(TimeAxis(TapMath.TimeSourceKind.LOCAL, 400L), base)

        assertTrue("微调必须体现在面板显示上", noOffset != withOffset)
        assertTrue(withOffset.endsWith(":5"))
    }

    @Test
    fun panelTimeLineUnavailableWhenSourceMissing() {
        assertEquals("--:--:--:-", SchedulerDisplay.panelClockLine(TimeAxis(TapMath.TimeSourceKind.BEIJING, 0L), null))
    }

    // ================= BUG-6：去重集合容量控制不得丢掉刚加入的 key =================

    @Test
    fun justAddedKeySurvivesCapacityPruning() {
        val keys = FiredKeys(max = 8)
        // 修复前：fired.add(key) 之后若超限就 fired.clear()，刚加入的 key 一起被清掉，
        // 同一秒的下一个 tick 会再次命中 -> 重复触发。
        for (i in 1..9) keys.add("k" + i)

        assertTrue("刚加入的 key 必须仍然在集合里（BUG-6）", keys.contains("k9"))
        assertTrue("容量应被控制在 max 以内", keys.size <= 8)
    }

    @Test
    fun oldestKeysAreEvictedFirst() {
        val keys = FiredKeys(max = 3)
        keys.add("a"); keys.add("b"); keys.add("c"); keys.add("d")

        assertFalse("最老的 key 应被淘汰", keys.contains("a"))
        assertTrue(keys.contains("b"))
        assertTrue(keys.contains("d"))
        assertEquals(3, keys.size)
    }

    @Test
    fun duplicateAddIsIdempotentAndReported() {
        val keys = FiredKeys(max = 8)
        assertTrue("首次加入应返回 true", keys.add("x"))
        assertFalse("重复加入应返回 false，调用方据此跳过", keys.add("x"))
        assertEquals(1, keys.size)
    }

    @Test
    fun containsWorksForNeverAddedKey() {
        val keys = FiredKeys(max = 8)
        assertFalse(keys.contains("nope"))
    }
}
