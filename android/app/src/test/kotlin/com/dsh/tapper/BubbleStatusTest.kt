package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮球状态文字的单元测试。
 *
 * 背景：修复前这两行只在 OverlayService.stateReceiver 里渲染，且读的是 Intent extra
 * （a11y/running/next），而**没有任何发送方写入过这些 extra**，因此恒为
 * 「待机(无无障碍) / 下次 -」。修复后判定抽成纯函数 [TapMath.BubbleStatus]，
 * 由调用方传入**实时读取**的真实状态，本测试即覆盖该判定的全部取值组合。
 *
 * 对应验收标准 1（关闭时显示「无无障碍」+ 对应下次值）、2（开启时不再显示「无无障碍」）、
 * 4（反复切换不错乱、不残留旧值）。
 */
class BubbleStatusTest {

    // ---------- 验收 1：无障碍关闭 ----------

    @Test
    fun a11yOffShowsNoAccessibilityLine() {
        val t = TapMath.BubbleStatus.text(pickMode = false, running = false, a11y = false, next = "08:00:00 (in 30s)")
        assertTrue(t, t.startsWith("待机(无无障碍)"))
        assertTrue(t, t.contains("下次 08:00:00 (in 30s)"))
    }

    @Test
    fun a11yOffWithNoScheduleShowsRealEmptyState() {
        val t = TapMath.BubbleStatus.text(pickMode = false, running = false, a11y = false, next = TapMath.BubbleStatus.NONE)
        assertEquals("待机(无无障碍)\n下次 " + TapMath.BubbleStatus.NO_NEXT, t)
    }

    // ---------- 验收 2：无障碍开启 ----------

    @Test
    fun a11yOnDoesNotShowNoAccessibility() {
        val t = TapMath.BubbleStatus.text(pickMode = false, running = false, a11y = true, next = "12:30:15 (in 5s)")
        assertFalse("开启无障碍后不得再出现「无无障碍」", t.contains("无无障碍"))
        assertTrue(t, t.startsWith("待机\n"))
        assertTrue(t, t.contains("下次 12:30:15 (in 5s)"))
    }

    @Test
    fun idleLineDependsOnlyOnA11y() {
        assertEquals("待机", TapMath.BubbleStatus.idleLine(true))
        assertEquals("待机(无无障碍)", TapMath.BubbleStatus.idleLine(false))
    }

    // ---------- 「下次」接真实数据源，空值显示真实语义 ----------

    @Test
    fun nextUsesRealSchedulerValue() {
        assertEquals("08:00:00 (in 12s)", TapMath.BubbleStatus.nextLine("08:00:00 (in 12s)"))
        assertEquals("每小时:05:00 (in 3s)", TapMath.BubbleStatus.nextLine("每小时:05:00 (in 3s)"))
    }

    @Test
    fun nextEmptySentinelBecomesRealEmptyState() {
        // 调度器的空值哨兵是 "-"，不能原样显示成「下次 -」（修复前的表现）
        assertEquals(TapMath.BubbleStatus.NO_NEXT, TapMath.BubbleStatus.nextLine(TapMath.BubbleStatus.NONE))
        assertEquals(TapMath.BubbleStatus.NO_NEXT, TapMath.BubbleStatus.nextLine(null))
        assertEquals(TapMath.BubbleStatus.NO_NEXT, TapMath.BubbleStatus.nextLine(""))
        assertEquals(TapMath.BubbleStatus.NO_NEXT, TapMath.BubbleStatus.nextLine("   "))
    }

    // ---------- 优先级：取点 > 执行中 > 待机 ----------

    @Test
    fun pickModeWinsOverEverything() {
        val t = TapMath.BubbleStatus.text(pickMode = true, running = true, a11y = true, next = "08:00:00 (in 1s)")
        assertEquals(TapMath.BubbleStatus.PICKING, t)
    }

    @Test
    fun runningWinsOverIdle() {
        val t = TapMath.BubbleStatus.text(pickMode = false, running = true, a11y = false, next = "08:00:00 (in 1s)")
        assertEquals(TapMath.BubbleStatus.RUNNING, t)
    }

    // ---------- 验收 4：反复切换不错乱、不残留旧值 ----------

    @Test
    fun repeatedTogglingIsDeterministicWithoutResidue() {
        val off = TapMath.BubbleStatus.text(false, false, a11y = false, next = "-")
        val on = TapMath.BubbleStatus.text(false, false, a11y = true, next = "08:00:00 (in 5s)")
        // 连续切换 50 次，每次结果都必须与首次一致（纯函数、无内部状态可残留）
        repeat(50) {
            assertEquals(off, TapMath.BubbleStatus.text(false, false, a11y = false, next = "-"))
            assertEquals(on, TapMath.BubbleStatus.text(false, false, a11y = true, next = "08:00:00 (in 5s)"))
        }
        assertFalse(off.contains("无无障碍").not())
        assertFalse("关闭态不应出现开启态文案", off.contains("下次 08:00:00"))
    }

    @Test
    fun switchingA11yNeverLeavesStaleLine() {
        // 模拟：关 -> 开 -> 关 -> 开，每步只取最后一次调用的结果（不累积、不残留）
        val seq = listOf(false, true, false, true)
        for (a11y in seq) {
            val t = TapMath.BubbleStatus.text(false, false, a11y, "-")
            assertEquals(TapMath.BubbleStatus.idleLine(a11y) + "\n下次 " + TapMath.BubbleStatus.NO_NEXT, t)
            assertEquals(a11y, !t.contains("无无障碍"))
        }
    }

    // ---------- 与真实数据源的契约 ----------

    @Test
    fun noneSentinelMatchesSchedulerContract() {
        // Scheduler.nextFireLabel 初始值与「无启用时间点」时的值都是 "-"
        assertEquals("-", TapMath.BubbleStatus.NONE)
    }
}
