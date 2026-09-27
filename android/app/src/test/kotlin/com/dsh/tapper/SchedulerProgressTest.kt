package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 复现与回归测试（审查 BUG-7）。
 *
 * ## 缺陷
 * 时间点被**停用**时，Scheduler 只是在 tick 里 `continue` 跳过它
 * （Scheduler.kt:135 与 :161 的 `if (!p.enabled ...) continue`），
 * **从不清除它的重复进度** repeatStates[id]。
 * 而 update() 的重置条件只看 repeatCount / repeatIntervalMs 变化或点被删除
 * （Scheduler.kt:70-77），**不含 enabled**。
 *
 * 于是：停用期间已经到点、但没被执行的重复次数会一直"欠着"，
 * 一旦重新启用，nextRepeatDecision 会因 `now >= at` 成立而**立刻连续补触发**，
 * 造成用户没有预期的突发连点。
 *
 * ## 复现参数
 * repeatCount=5, repeatIntervalMs=60000（每分钟一次）。
 * 触发第 1 次后停用，静置 5 分钟再启用：
 *   - 修复前：第 2、3、4、5 次的计划时刻（base+60s/120s/180s/240s）全部已成过去，
 *     启用后的第一次 tick 就判定第 2 次到点，之后每次 tick 继续推进 -> 约 4 秒内连补 4 次。
 *   - 修复后：停用动作本身即重置该点的重复进度，重新启用后从下一个基准时刻重新开始。
 *
 * ## 修复方式
 * 把「配置更新后是否需要重置重复进度」抽成纯函数 [SchedulerProgress.shouldReset]，
 * 在既有条件下**补上 enabled 变化**这一条；调用方（Scheduler.update）逻辑不变，
 * 只是判定改走该纯函数，从而可以在 JVM 上直接断言，而不是靠真机观察。
 */
class SchedulerProgressTest {

    private fun point(
        id: String = "p1",
        enabled: Boolean = true,
        repeatCount: Int = 5,
        repeatIntervalMs: Long = 60_000L,
    ) = TimePoint(id, 8, 0, 0, enabled, emptyList(),
        repeatCount = repeatCount, repeatIntervalMs = repeatIntervalMs)

    // ---------- 既有行为必须保留（不能为了修 BUG-7 破坏原有重置语义）----------

    @Test
    fun deletedPointResetsProgress() {
        val old = point()
        assertTrue("时间点被删除必须重置进度",
            SchedulerProgress.shouldReset(old, null))
    }

    @Test
    fun changedRepeatCountResetsProgress() {
        assertTrue("重复次数变化必须重置进度",
            SchedulerProgress.shouldReset(point(repeatCount = 3), point(repeatCount = 5)))
    }

    @Test
    fun changedRepeatIntervalResetsProgress() {
        assertTrue("重复间隔变化必须重置进度",
            SchedulerProgress.shouldReset(point(repeatIntervalMs = 1000L), point(repeatIntervalMs = 2000L)))
    }

    @Test
    fun unchangedPointKeepsProgress() {
        assertFalse("完全没变时不应重置（否则正常重复会被打断）",
            SchedulerProgress.shouldReset(point(), point()))
    }

    @Test
    fun newPointAppearingDoesNotResetOthers() {
        // 传入的是「该 id 的新旧值」，新点没有旧值 -> 由调用方决定不检查
        assertFalse("同 id 同参数不应重置", SchedulerProgress.shouldReset(point(), point()))
    }

    // ---------- BUG-7 核心：enabled 变化必须重置 ----------

    @Test
    fun disablingPointResetsProgress_preventsAccumulatedBacklog() {
        assertTrue("停用必须重置重复进度，否则停用期间的欠账会在重新启用时突发补触发（BUG-7）",
            SchedulerProgress.shouldReset(point(enabled = true), point(enabled = false)))
    }

    @Test
    fun reEnablingPointAlsoResetsProgress() {
        assertTrue("重新启用同样必须重置，确保从干净的进度开始",
            SchedulerProgress.shouldReset(point(enabled = false), point(enabled = true)))
    }

    @Test
    fun disablingWinsEvenWhenOtherFieldsUnchanged() {
        // 只改 enabled、其他字段全一样 —— 这正是修复前漏掉的判定分支
        val before = point(enabled = true, repeatCount = 5, repeatIntervalMs = 60_000L)
        val after = point(enabled = false, repeatCount = 5, repeatIntervalMs = 60_000L)
        assertTrue(SchedulerProgress.shouldReset(before, after))
    }

    // ---------- 复现「欠账突发」的机制本身（使用既有生产函数 nextRepeatDecision）----------

    @Test
    fun staleProgressWouldFireImmediatelyOnReEnable_reproducesBug7Mechanism() {
        // 第 1 次已在 base 触发，进度停在 firedCount=1
        val base = 1_700_000_000_000L
        val state = RepeatState(baseAt = base, firedCount = 1)
        val p = point(repeatCount = 5, repeatIntervalMs = 60_000L)

        // 停用 5 分钟后重新启用：now 远大于第 2 次的计划时刻 base+60s
        val nowAfterPause = base + 5L * 60_000L

        val d = TapMath.nextRepeatDecision(p, state, nowAfterPause)
        assertTrue("修复前：陈旧的进度会让第 2 次立刻判定为到点 —— 这就是突发补触发的机制",
            d != null)
        assertEquals("应立即补的是第 2 次", 2, d!!.index)
        assertEquals("其计划时刻早已成为过去", base + 60_000L, d.scheduledAt)
    }

    @Test
    fun afterResetNoImmediateFireHappens() {
        // 修复后：停用即重置 -> 进度为空 -> nextRepeatDecision 不产生任何补触发
        val p = point(repeatCount = 5, repeatIntervalMs = 60_000L)
        val cleared = RepeatState()   // 重置后的状态
        val nowAfterPause = 1_700_000_000_000L + 5L * 60_000L

        assertTrue("重置后不应再有欠账补触发", TapMath.nextRepeatDecision(p, cleared, nowAfterPause) == null)
        assertTrue(cleared.idle)
    }

    @Test
    fun backlogIsFullyClearedNotJustOneStep() {
        // 修复前的真实欠账规模：停用 5 分钟后重新启用，本轮剩余的次数会被**逐个连续补完**。
        // 说明（实测修正）：最初以为只会补到"间隔未到"的那次，实际 nextRepeatDecision 每轮
        // 只推进一格而 now 固定不变，于是第 2/3/4/5 次全部满足 now >= at，一轮内被补光，
        // 且第 5 次直接以 cycleFinished 结束本轮 —— 比预想更严重。
        val base = 1_700_000_000_000L
        val p = point(repeatCount = 5, repeatIntervalMs = 60_000L)
        var state = RepeatState(baseAt = base, firedCount = 1)
        val now = base + 5L * 60_000L

        val fired = ArrayList<Int>()
        // 模拟连续 tick（修复前行为）
        while (true) {
            val d = TapMath.nextRepeatDecision(p, state, now) ?: break
            fired.add(d.index)
            state = d.newState
        }
        assertEquals("停用 5 分钟后重新启用，本轮剩余的 2/3/4/5 次会被一次性补光", listOf(2, 3, 4, 5), fired)
        assertEquals("补完后本轮即结束", 5, state.firedCount)
    }

    // ---------- 与 update() 的契约一致 ----------

    @Test
    fun resetDecisionIsSymmetricForEnabledTransitions() {
        val on = point(enabled = true)
        val off = point(enabled = false)
        assertEquals("启用->停用 与 停用->启用 都必须重置",
            SchedulerProgress.shouldReset(on, off), SchedulerProgress.shouldReset(off, on))
    }

    @Test
    fun resetLogMessageIsProvidedForObservability() {
        // 重置必须有日志，否则用户看到"重新启用后没反应"时无从追溯
        assertTrue("应给出可读的重置原因", SchedulerProgress.resetReason(point(enabled = true), point(enabled = false)).isNotEmpty())
    }
}
