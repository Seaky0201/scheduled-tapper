package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 悬浮窗「测试时间点」的单元测试。
 *
 * Android 的 WindowManager 悬浮窗与 SequenceRunner（依赖 Handler(Looper)）都无法在 JVM 中运行，
 * 因此按项目既有的「判定 / 执行分离」做法，把面板的**选择与可执行判定**抽成纯函数
 * [TapMath.TestTarget] 在此覆盖；实际点击派发仍走既有入口 [SequenceRunner.run]（未改动）。
 *
 * 对应验收标准：
 *  2. 可选择到具体时间点（单选、可取消）
 *  3. 确认后执行的是**所选时间点自己的**步骤（由 resolve 返回该点，steps 即该点 steps）
 *  4. 未确认 / 取消时不触发任何模拟点击（blockReason 非空 -> 调用方不执行）
 */
class TestTargetTest {

    private fun point(id: String, h: Int, m: Int, s: Int, steps: List<Step> = emptyList()) =
        TimePoint(id = id, hour = h, minute = m, second = s, enabled = true, steps = steps)

    private val stepA = Step(100, 200, 200L, 1080, 2400)
    private val stepB = Step(300, 400, 200L, 1080, 2400)

    private fun cfg() = Config(points = listOf(
        point("p1", 8, 0, 0, listOf(stepA)),
        point("p2", 12, 30, 15, listOf(stepB, stepA)),
        point("p3", -1, 5, 0),                       // 每小时，且没有步骤
    ))

    // ---------- 验收 2：可选择到具体时间点 ----------

    @Test
    fun initiallyNothingIsSelected() {
        // 面板打开时未选任何时间点 -> 不可执行
        assertNull("初始应无选中", TapMath.TestTarget.resolve(cfg(), null))
        assertNotNull(TapMath.TestTarget.blockReason(cfg(), null))
    }

    @Test
    fun tapSelectsThatExactTimePoint() {
        val sel = TapMath.TestTarget.select(null, "p2")
        assertEquals("p2", sel)
        val p = TapMath.TestTarget.resolve(cfg(), sel)
        assertNotNull(p)
        assertEquals("12时 30分 15秒", p!!.label())
        assertEquals("p2", p.id)
    }

    @Test
    fun tappingAnotherRowSwitchesSelection() {
        val first = TapMath.TestTarget.select(null, "p1")
        val second = TapMath.TestTarget.select(first, "p2")
        assertEquals("p2", second)
    }

    @Test
    fun tappingSelectedRowDeselects() {
        val first = TapMath.TestTarget.select(null, "p1")
        assertNull("再点一次应取消选择", TapMath.TestTarget.select(first, "p1"))
    }

    @Test
    fun hourlyPointIsSelectableAndLabelled() {
        val p = TapMath.TestTarget.resolve(cfg(), "p3")
        assertNotNull(p)
        assertEquals("每小时 05分 00秒", p!!.label())
    }

    // ---------- 验收 3：执行的是所选时间点自己的步骤 ----------

    @Test
    fun resolvedPointCarriesItsOwnSteps() {
        val c = cfg()
        // 选 p2 -> 必须拿到 p2 自己的步骤（2 步），而不是第一个时间点的步骤
        val p2 = TapMath.TestTarget.resolve(c, TapMath.TestTarget.select(null, "p2"))!!
        assertEquals(2, p2.steps.size)
        assertEquals(300, p2.steps[0].x)
        assertEquals(400, p2.steps[0].y)
        // 与「立即执行一次」固定取 points.first() 的行为形成对比：p1 只有 1 步
        assertEquals(1, c.points.first().steps.size)
    }

    @Test
    fun eachTimePointResolvesToItsOwnSteps() {
        val c = cfg()
        assertEquals(100, TapMath.TestTarget.resolve(c, "p1")!!.steps[0].x)
        assertEquals(300, TapMath.TestTarget.resolve(c, "p2")!!.steps[0].x)
    }

    @Test
    fun runLabelKeepsManualStyle() {
        val p = TapMath.TestTarget.resolve(cfg(), "p2")!!
        assertEquals("12时 30分 15秒(测试)", TapMath.TestTarget.runLabel(p))
        // 与主界面「立即执行一次」的 p.label + "(手动)" 同风格
        assertEquals("08时 00分 00秒(手动)", cfg().points[0].label() + "(手动)")
    }

    @Test
    fun titleFollowsSingleSelection() {
        assertEquals("测试时间点（已选 0/3）", TapMath.TestTarget.title(0, 3))
        assertEquals("测试时间点（已选 1/3）", TapMath.TestTarget.title(1, 3))
    }

    // ---------- 验收 4：未确认 / 取消不触发任何点击 ----------

    @Test
    fun noSelectionIsBlocked() {
        assertEquals("还没有选择时间点", TapMath.TestTarget.blockReason(cfg(), null))
    }

    @Test
    fun pointWithoutStepsIsBlocked() {
        // 选中了但没有步骤 -> 不执行，并给出原因
        val r = TapMath.TestTarget.blockReason(cfg(), "p3")
        assertEquals("该时间点还没有配置步骤", r)
    }

    @Test
    fun deletedPointIsBlocked() {
        // 面板打开后该时间点被删除（主界面删除 / 选点导入改写配置）
        assertEquals("所选时间点已不存在（可能已被删除）", TapMath.TestTarget.blockReason(cfg(), "已删除的id"))
        assertNull(TapMath.TestTarget.resolve(cfg(), "已删除的id"))
    }

    @Test
    fun validSelectionIsNotBlocked() {
        // 只有这一种情况允许执行
        assertNull(TapMath.TestTarget.blockReason(cfg(), "p1"))
        assertNull(TapMath.TestTarget.blockReason(cfg(), "p2"))
    }

    @Test
    fun deselectingMakesItUnrunnableAgain() {
        // 选择 -> 取消 -> 回到不可执行（对应「取消不产生点击」）
        val sel = TapMath.TestTarget.select(null, "p1")
        assertNull(TapMath.TestTarget.blockReason(cfg(), sel))
        val cleared = TapMath.TestTarget.select(sel, "p1")
        assertNull(cleared)
        assertNotNull("取消后必须重新变为不可执行", TapMath.TestTarget.blockReason(cfg(), cleared))
    }

    // ---------- 验收 3+4：确认后实际传给执行入口的参数 ----------

    @Test
    fun planCarriesSelectedPointsOwnSteps() {
        val c = cfg()
        val plan = TapMath.TestTarget.plan(c, "p2")
        assertNotNull(plan)
        // 传给 SequenceRunner.run 的就是 p2 自己的步骤与全局 tapDurationMs
        assertEquals(2, plan!!.steps.size)
        assertEquals(300, plan.steps[0].x)
        assertEquals(400, plan.steps[0].y)
        assertEquals(100, plan.steps[1].x)
        assertEquals("12时 30分 15秒(测试)", plan.label)
        assertEquals(c.tapDurationMs, plan.tapDurationMs)
    }

    @Test
    fun planIsNullWhenNotConfirmed() {
        // 未选择（= 未确认）时没有执行计划 -> 调用方不会调用 SequenceRunner.run
        assertNull(TapMath.TestTarget.plan(cfg(), null))
    }

    @Test
    fun planIsNullForPointWithoutSteps() {
        assertNull("没有步骤的时间点不应产生执行计划", TapMath.TestTarget.plan(cfg(), "p3"))
    }

    @Test
    fun planIsNullForDeletedPoint() {
        assertNull(TapMath.TestTarget.plan(cfg(), "已删除的id"))
    }

    @Test
    fun planDoesNotCrossMergeOtherPoints() {
        // p1 与 p2 都有步骤；测试 p1 时不得把 p2 的步骤带进来
        val c = cfg()
        val plan = TapMath.TestTarget.plan(c, "p1")!!
        assertEquals(1, plan.steps.size)
        assertEquals(100, plan.steps[0].x)
        assertEquals("08时 00分 00秒(测试)", plan.label)
    }

    @Test
    fun planIsNullForEmptyConfig() {
        assertNull(TapMath.TestTarget.plan(Config(), null))
        assertNull(TapMath.TestTarget.plan(Config(), "p1"))
    }

    // ---------- 边界：不改既有默认行为 ----------

    @Test
    fun emptyConfigIsBlockedNotCrashing() {
        val empty = Config()
        assertNull(TapMath.TestTarget.resolve(empty, "p1"))
        assertEquals("所选时间点已不存在（可能已被删除）", TapMath.TestTarget.blockReason(empty, "p1"))
    }

    @Test
    fun resolveDoesNotMutateConfig() {
        val c = cfg()
        val before = c.toJson().toString()
        TapMath.TestTarget.resolve(c, "p2")
        TapMath.TestTarget.blockReason(c, "p1")
        assertEquals("判定过程不得改动配置", before, c.toJson().toString())
    }
}
