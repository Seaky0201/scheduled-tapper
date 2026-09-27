package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 针对两条硬约束的单元测试：
 *  1) 坐标必须按当前屏幕重新换算（缩放 / 旋转 / 分辨率变化）
 *  2) 触发必须按系统日历时间对齐（不随反复计算漂移）
 */
class TapMathTest {

    // ---------- 硬约束 1：坐标换算 ----------

    @Test
    fun sameScreenReturnsSamePoint() {
        val r = TapMath.mapToCurrentScreen(540, 1200, 1080, 2400, 1080, 2400)
        assertEquals(540, r[0])
        assertEquals(1200, r[1])
    }

    @Test
    fun scalingToLargerScreenKeepsRelativePosition() {
        // 1080x2400 上取的中心点，映射到 1440x3200 仍应是中心
        val r = TapMath.mapToCurrentScreen(540, 1200, 1080, 2400, 1440, 3200)
        assertEquals(720, r[0])
        assertEquals(1600, r[1])
    }

    @Test
    fun scalingToSmallerScreenClampsInside() {
        // 取点在最右下角，映射到更小屏幕后必须仍在屏幕内（不能越界导致点击被拒）
        val r = TapMath.mapToCurrentScreen(1079, 2399, 1080, 2400, 720, 1280)
        assertTrue("x=" + r[0], r[0] in 0..719)
        assertTrue("y=" + r[1], r[1] in 0..1279)
    }

    @Test
    fun rotationMapsByClockwiseTransform() {
        // 竖屏 1080x2400 左上角附近取点 -> 横屏 2400x1080 应落在右上区域
        val r = TapMath.mapToCurrentScreen(0, 0, 1080, 2400, 2400, 1080)
        assertEquals(2400 - 1, r[0])
        assertEquals(0, r[1])
    }

    @Test
    fun rotationThenBackIsStable() {
        // 竖 -> 横 -> 竖 往返后应回到原始位置附近（允许 1px 舍入）
        val a = TapMath.mapToCurrentScreen(540, 1200, 1080, 2400, 2400, 1080)
        val b = TapMath.mapToCurrentScreen(a[0], a[1], 2400, 1080, 1080, 2400)
        assertTrue("got " + b[0] + "," + b[1], kotlin.math.abs(b[0] - 540) <= 1)
        assertTrue("got " + b[0] + "," + b[1], kotlin.math.abs(b[1] - 1200) <= 1)
    }

    @Test
    fun unknownSourceScreenOnlyClamps() {
        val r = TapMath.mapToCurrentScreen(99999, -5, 0, 0, 1080, 2400)
        assertEquals(1079, r[0])
        assertEquals(0, r[1])
    }

    // ---------- 硬约束 3：定时对齐 ----------

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int, ms: Int = 0): Long {
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.set(y, mo - 1, d, h, mi, s)
        c.set(Calendar.MILLISECOND, ms)
        return c.timeInMillis
    }

    @Test
    fun hourlyPointFiresLaterSameHour() {
        val p = TimePoint(id = "a", hour = -1, minute = 10, second = 0, enabled = true, steps = listOf(Step(1, 1, 0)))
        val now = at(2026, 3, 1, 9, 5, 0)
        val next = TapMath.nextTriggerAt(p, now)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(10, c.get(Calendar.MINUTE))
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(9, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun hourlyPointRollsToNextHourWhenPassed() {
        val p = TimePoint(id = "a", hour = -1, minute = 10, second = 0, enabled = true, steps = listOf(Step(1, 1, 0)))
        val now = at(2026, 3, 1, 9, 10, 0) // 恰好等于触发时刻
        val next = TapMath.nextTriggerAt(p, now)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(10, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(10, c.get(Calendar.MINUTE))
    }

    @Test
    fun absolutePointRollsToTomorrowWhenPassed() {
        val p = TimePoint(id = "b", hour = 0, minute = 5, second = 30, enabled = true, steps = listOf(Step(1, 1, 0)))
        val now = at(2026, 3, 1, 8, 0, 0)
        val next = TapMath.nextTriggerAt(p, now)
        val c = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(2, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY))
        assertEquals(5, c.get(Calendar.MINUTE))
        assertEquals(30, c.get(Calendar.SECOND))
    }

    @Test
    fun nextTriggerIsStableAgainstRepeatedCallsNoDrift() {
        // 反复计算同一时间点的下一次触发，结果必须完全一致（不累积漂移）
        val p = TimePoint(id = "c", hour = -1, minute = 30, second = 0, enabled = true, steps = listOf(Step(1, 1, 0)))
        var cursor = at(2026, 3, 1, 0, 0, 1)
        val first = TapMath.nextTriggerAt(p, cursor)
        val second = TapMath.nextTriggerAt(p, cursor)
        val third = TapMath.nextTriggerAt(p, cursor + 12345L) // 抖动 12 秒仍指向同一绝对时刻
        assertEquals(first, second)
        assertEquals(first, third)
    }

    @Test
    fun simulateManyHoursWithoutDrift() {
        // 模拟连续 200 小时：每次都从「上一次触发时刻」重新计算，误差不得累积
        val p = TimePoint(id = "d", hour = -1, minute = 10, second = 0, enabled = true, steps = listOf(Step(1, 1, 0)))
        var cursor = at(2026, 3, 1, 0, 0, 0)
        var last = 0L
        repeat(200) {
            val n = TapMath.nextTriggerAt(p, cursor)
            last = n
            cursor = n
        }
        val c = Calendar.getInstance().apply { timeInMillis = last }
        assertEquals(0, c.get(Calendar.SECOND))
        assertEquals(10, c.get(Calendar.MINUTE))
        // 200 小时后应精确落在整点+10分，无秒级漂移
        assertTrue(c.get(Calendar.MILLISECOND) == 0)
    }

    // ---------- 命中判定与去重 ----------

    @Test
    fun dueKeyMatchesOnlyOnExactSecond() {
        val p = TimePoint(id = "e", hour = -1, minute = 10, second = 0, enabled = true, steps = listOf(Step(1, 1, 0)))
        assertNotNull(TapMath.dueKey(p, at(2026, 3, 1, 7, 10, 0)))
        assertNull(TapMath.dueKey(p, at(2026, 3, 1, 7, 10, 1)))
        assertNull(TapMath.dueKey(p, at(2026, 3, 1, 7, 9, 0)))
    }

    @Test
    fun dueKeyHonoursAbsoluteHour() {
        val p = TimePoint(id = "f", hour = 3, minute = 30, second = 0, enabled = true, steps = listOf(Step(1, 1, 0)))
        assertNotNull(TapMath.dueKey(p, at(2026, 3, 1, 3, 30, 0)))
        assertNull(TapMath.dueKey(p, at(2026, 3, 1, 4, 30, 0)))
    }

    @Test
    fun dueKeyDiffersPerDayToAllowDailyRefire() {
        val p = TimePoint(id = "g", hour = 0, minute = 5, second = 30, enabled = true, steps = listOf(Step(1, 1, 0)))
        val k1 = TapMath.dueKey(p, at(2026, 3, 1, 0, 5, 30))
        val k2 = TapMath.dueKey(p, at(2026, 3, 2, 0, 5, 30))
        assertNotNull(k1)
        assertNotNull(k2)
        assertTrue("同一天内应唯一、跨天应不同", k1 != k2)
    }

    @Test
    fun driftComputation() {
        assertEquals(123L, TapMath.driftMs(1000L, 1123L))
        assertEquals(-5L, TapMath.driftMs(1000L, 995L))
    }

    // ---------- 配置持久化（JSON 往返） ----------

    @Test
    fun configJsonRoundTrip() {
        val cfg = Config(
            points = listOf(
                TimePoint("id1", -1, 10, 0, true, listOf(Step(100, 200, 300, 1080, 2400), Step(400, 500, 0, 1080, 2400))),
                TimePoint("id2", 0, 5, 30, false, emptyList()),
            ),
            tapDurationMs = 40,
        )
        val back = Config.fromJson(cfg.toJson().toString())
        assertEquals(2, back.points.size)
        assertEquals(40L, back.tapDurationMs)
        assertEquals(-1, back.points[0].hour)
        assertEquals(2, back.points[0].steps.size)
        assertEquals(300L, back.points[0].steps[0].delayMs)
        assertEquals(1080, back.points[0].steps[0].sw)
        assertEquals(false, back.points[1].enabled)
        assertEquals(30, back.points[1].second)
    }

    @Test
    fun configFromBrokenJsonFallsBackToEmpty() {
        assertEquals(0, Config.fromJson("{not json").points.size)
        assertEquals(0, Config.fromJson(null).points.size)
    }

    // ---------- 阶段二：重复次数与间隔 ----------

    @Test
    fun normalizeRepeatCountClampsIllegalInput() {
        assertEquals(1, TapMath.normalizeRepeatCount(0))
        assertEquals(1, TapMath.normalizeRepeatCount(-5))
        assertEquals(1, TapMath.normalizeRepeatCount(1))
        assertEquals(5, TapMath.normalizeRepeatCount(5))
        assertEquals(TimePoint.MAX_REPEAT_COUNT, TapMath.normalizeRepeatCount(99999))
    }

    @Test
    fun normalizeRepeatIntervalClampsIllegalInput() {
        assertEquals(0L, TapMath.normalizeRepeatInterval(-1L))
        assertEquals(0L, TapMath.normalizeRepeatInterval(0L))
        assertEquals(300L, TapMath.normalizeRepeatInterval(300L))
        assertEquals(TimePoint.MAX_REPEAT_INTERVAL_MS, TapMath.normalizeRepeatInterval(Long.MAX_VALUE))
    }

    @Test
    fun repeatFireAtUsesAbsoluteOffsets() {
        val base = 1_000_000L
        assertEquals(base, TapMath.repeatFireAt(base, 500L, 1))
        assertEquals(base + 500L, TapMath.repeatFireAt(base, 500L, 2))
        assertEquals(base + 2000L, TapMath.repeatFireAt(base, 500L, 5))
        // 间隔 0 -> 全部落在同一时刻（表示「连续执行」）
        assertEquals(base, TapMath.repeatFireAt(base, 0L, 4))
    }

    @Test
    fun repeatDecisionDoesNotFireBeforeDueTime() {
        val p = TimePoint("r1", -1, 0, 0, true, listOf(Step(1, 1, 0)), repeatCount = 3, repeatIntervalMs = 1000L)
        val st = RepeatState(baseAt = 10_000L, firedCount = 1)
        assertNull(TapMath.nextRepeatDecision(p, st, 10_999L))
        val d = TapMath.nextRepeatDecision(p, st, 11_000L)
        assertNotNull(d)
        assertEquals(2, d!!.index)
        assertEquals(11_000L, d.scheduledAt)
        assertTrue(!d.cycleFinished)
    }

    @Test
    fun repeatDecisionStopsAtCountLimit() {
        val p = TimePoint("r2", -1, 0, 0, true, listOf(Step(1, 1, 0)), repeatCount = 3, repeatIntervalMs = 1000L)
        // 已触发到上限 -> 不再触发
        assertNull(TapMath.nextRepeatDecision(p, RepeatState(10_000L, 3), 99_999L))
        // 超出上限也不会触发
        assertNull(TapMath.nextRepeatDecision(p, RepeatState(10_000L, 9), 99_999L))
    }

    @Test
    fun repeatDecisionSingleShotNeverRepeats() {
        // repeatCount = 1（旧数据默认值）-> 只有第 1 次，永不产生后续触发
        val p = TimePoint("r3", -1, 0, 0, true, listOf(Step(1, 1, 0)), repeatCount = 1, repeatIntervalMs = 500L)
        assertNull(TapMath.nextRepeatDecision(p, RepeatState(10_000L, 1), 999_999L))
    }

    @Test
    fun repeatDecisionZeroIntervalFiresImmediately() {
        // 间隔 0 -> 到点后下一次立刻可执行
        val p = TimePoint("r4", -1, 0, 0, true, listOf(Step(1, 1, 0)), repeatCount = 4, repeatIntervalMs = 0L)
        val d = TapMath.nextRepeatDecision(p, RepeatState(10_000L, 1), 10_000L)
        assertNotNull(d)
        assertEquals(2, d!!.index)
        assertEquals(10_000L, d.scheduledAt)
    }

    @Test
    fun repeatSequenceProducesExactCountThenStops() {
        // 完整模拟一轮：重复 5 次、间隔 200ms，按绝对时刻推进
        val p = TimePoint("r5", -1, 0, 0, true, listOf(Step(1, 1, 0)), repeatCount = 5, repeatIntervalMs = 200L)
        val base = 1_000_000L
        var st = RepeatState(base, 1)
        val firedAt = ArrayList<Long>()
        var now = base
        var guard = 0
        while (guard++ < 100) {
            val d = TapMath.nextRepeatDecision(p, st, now) ?: run { now += 50L; continue }
            firedAt.add(d.scheduledAt)
            st = d.newState
            if (d.cycleFinished) break
        }
        // 第 1 次由基准命中，后续 4 次由本逻辑产生
        assertEquals(listOf(base + 200L, base + 400L, base + 600L, base + 800L), firedAt)
        assertEquals(5, st.firedCount)
        assertNull(TapMath.nextRepeatDecision(p, st, now + 100_000L))
    }

    @Test
    fun repeatLabelIsHumanReadable() {
        assertEquals("单次", TimePoint("a", -1, 0, 0, true, emptyList(), repeatCount = 1).repeatLabel())
        val s = TimePoint("a", -1, 0, 0, true, emptyList(), repeatCount = 3, repeatIntervalMs = 500L).repeatLabel()
        assertTrue(s, s.contains("3") && s.contains("500"))
    }

    // ---------- 阶段二：旧数据兼容 ----------

    @Test
    fun oldJsonWithoutRepeatFieldsStillLoads() {
        // 模拟升级前写入的旧配置（没有 repeatCount / repeatIntervalMs）
        val legacy = """
            {"version":1,"tapDurationMs":30,"points":[
              {"id":"old1","hour":8,"minute":5,"second":30,"enabled":true,
               "steps":[{"x":10,"y":20,"delayMs":300,"sw":1080,"sh":2400}]}
            ]}
        """.trimIndent()
        val cfg = Config.fromJson(legacy)
        assertEquals(1, cfg.points.size)
        val p = cfg.points[0]
        assertEquals("old1", p.id)
        assertEquals(8, p.hour)
        assertEquals(300L, p.steps[0].delayMs)
        // 关键：缺失字段回落到「单次」，即旧行为
        assertEquals(1, p.repeatCount)
        assertEquals(0L, p.repeatIntervalMs)
        assertEquals("单次", p.repeatLabel())
    }

    @Test
    fun oldJsonRoundTripPreservesNewDefaults() {
        val legacy = """{"version":1,"points":[{"id":"old2","hour":-1,"minute":1,"second":2,"enabled":true,"steps":[]}]}"""
        val cfg = Config.fromJson(legacy)
        // 重新序列化后应带上新字段，且值仍等价于旧行为
        val again = Config.fromJson(cfg.toJson().toString())
        assertEquals(1, again.points.size)
        assertEquals(1, again.points[0].repeatCount)
        assertEquals(0L, again.points[0].repeatIntervalMs)
    }

    @Test
    fun newFieldsRoundTrip() {
        val cfg = Config(
            points = listOf(
                TimePoint("n1", -1, 10, 0, true, listOf(Step(1, 2, 3, 4, 5)), repeatCount = 7, repeatIntervalMs = 1500L)
            )
        )
        val back = Config.fromJson(cfg.toJson().toString())
        assertEquals(7, back.points[0].repeatCount)
        assertEquals(1500L, back.points[0].repeatIntervalMs)
    }

    // ---------- 阶段五：悬浮窗手势判定 ----------

    @Test
    fun bubbleSingleTapOpensPanelWhenClosed() {
        assertEquals(
            TapMath.BubbleGesture.ACTION_OPEN_PANEL,
            TapMath.BubbleGesture.decide(moved = false, longPressed = false, panelOpen = false)
        )
    }

    @Test
    fun bubbleSingleTapClosesPanelWhenOpen() {
        assertEquals(
            TapMath.BubbleGesture.ACTION_CLOSE_PANEL,
            TapMath.BubbleGesture.decide(moved = false, longPressed = false, panelOpen = true)
        )
    }

    @Test
    fun bubbleDragDoesNotOpenPanel() {
        // 拖动后抬手不应误触展开/收起面板
        assertEquals(
            TapMath.BubbleGesture.ACTION_DRAG,
            TapMath.BubbleGesture.decide(moved = true, longPressed = false, panelOpen = false)
        )
        assertEquals(
            TapMath.BubbleGesture.ACTION_DRAG,
            TapMath.BubbleGesture.decide(moved = true, longPressed = false, panelOpen = true)
        )
    }

    @Test
    fun bubbleLongPressHidesOverlay() {
        assertEquals(
            TapMath.BubbleGesture.ACTION_HIDE,
            TapMath.BubbleGesture.decide(moved = false, longPressed = true, panelOpen = false)
        )
    }

    @Test
    fun bubbleLongPressWinsOverDrag() {
        assertEquals(
            TapMath.BubbleGesture.ACTION_HIDE,
            TapMath.BubbleGesture.decide(moved = true, longPressed = true, panelOpen = true)
        )
    }

    @Test
    fun illegalRepeatValuesInJsonAreClampedNotCrash() {
        val bad = """{"points":[{"id":"b1","hour":-1,"minute":0,"second":0,"enabled":true,"steps":[],"repeatCount":-9,"repeatIntervalMs":-100}]}"""
        val cfg = Config.fromJson(bad)
        assertEquals(1, cfg.points[0].repeatCount)
        assertEquals(0L, cfg.points[0].repeatIntervalMs)
    }
}
