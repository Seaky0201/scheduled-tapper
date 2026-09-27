package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 悬浮窗「选点导入」的单元测试。
 *
 * Android 的 WindowManager 悬浮窗无法在 JVM 中真实渲染，因此把面板的**判定与写入逻辑**
 * 抽成纯函数 [TapMath.PickerModel]，在此覆盖：单选落位 / 多选批量落位 / 取消不写入 /
 * 追加语义 / 写入结构与 [Config] 契约一致（JSON 往返）。
 */
class PickerModelTest {

    private fun point(id: String, h: Int, m: Int, s: Int, steps: List<Step> = emptyList()) =
        TimePoint(id = id, hour = h, minute = m, second = s, enabled = true, steps = steps)

    private fun cfg(vararg ids: String): Config =
        Config(points = ids.mapIndexed { i, id -> point(id, i + 1, 0, 0) })

    // ---------- 面板文案与勾选状态 ----------

    @Test
    fun titleShowsSelectedAndTotal() {
        assertEquals("选择时间点（已选 0/3）", TapMath.PickerModel.title(0, 3))
        assertEquals("选择时间点（已选 2/3）", TapMath.PickerModel.title(2, 3))
    }

    @Test
    fun toggleAllLabelSwitchesAtFullSelection() {
        assertEquals("全选", TapMath.PickerModel.toggleAllLabel(3, 0))
        assertEquals("全选", TapMath.PickerModel.toggleAllLabel(3, 2))
        assertEquals("取消选择", TapMath.PickerModel.toggleAllLabel(3, 3))
        // 没有时间点时不应显示「取消选择」
        assertEquals("全选", TapMath.PickerModel.toggleAllLabel(0, 0))
    }

    @Test
    fun selectAllAndClearSelection() {
        val all = TapMath.PickerModel.selectAll(listOf("a", "b", "c"))
        assertEquals(3, all.size)
        assertTrue(all.contains("b"))
        assertEquals(0, TapMath.PickerModel.clearSelection().size)
    }

    // ---------- 单选落位 ----------

    @Test
    fun singleSelectionLandsOnlyOnItsOwnPoint() {
        val c = cfg("p1", "p2", "p3")
        val step = Step(540, 1200, 200L, 1080, 2400)
        val next = TapMath.PickerModel.importTo(c, setOf("p2"), listOf(step))

        assertEquals(0, next.points[0].steps.size)
        assertEquals(1, next.points[1].steps.size)
        assertEquals(0, next.points[2].steps.size)
        // 落位内容与所选一致
        assertEquals(540, next.points[1].steps[0].x)
        assertEquals(1200, next.points[1].steps[0].y)
        assertEquals(1080, next.points[1].steps[0].sw)
        assertEquals(2400, next.points[1].steps[0].sh)
    }

    // ---------- 多选批量落位 ----------

    @Test
    fun multiSelectionLandsOnEverySelectedPoint() {
        val c = cfg("p1", "p2", "p3")
        val step = Step(100, 200, 200L, 1080, 2400)
        val next = TapMath.PickerModel.importTo(c, setOf("p1", "p3"), listOf(step))

        assertEquals(1, next.points[0].steps.size)
        assertEquals(0, next.points[1].steps.size)
        assertEquals(1, next.points[2].steps.size)
        assertEquals(100, next.points[0].steps[0].x)
        assertEquals(100, next.points[2].steps[0].x)
    }

    @Test
    fun multipleStepsAllLandInOrder() {
        val c = cfg("p1")
        val s1 = Step(1, 1, 200L, 1080, 2400)
        val s2 = Step(2, 2, 200L, 1080, 2400)
        val next = TapMath.PickerModel.importTo(c, setOf("p1"), listOf(s1, s2))
        assertEquals(2, next.points[0].steps.size)
        assertEquals(1, next.points[0].steps[0].x)
        assertEquals(2, next.points[0].steps[1].x)
    }

    // ---------- 追加语义（不覆盖、不去重） ----------

    @Test
    fun importAppendsToExistingSteps() {
        val existing = Step(7, 7, 200L, 1080, 2400)
        val c = Config(points = listOf(point("p1", 1, 0, 0, listOf(existing))))
        val next = TapMath.PickerModel.importTo(c, setOf("p1"), listOf(Step(8, 8, 200L, 1080, 2400)))

        assertEquals(2, next.points[0].steps.size)
        assertEquals(7, next.points[0].steps[0].x)   // 原有步骤保留在前
        assertEquals(8, next.points[0].steps[1].x)   // 新步骤追加到末尾
    }

    @Test
    fun repeatedImportAppendsAgainWithoutDedup() {
        val step = Step(5, 5, 200L, 1080, 2400)
        val once = TapMath.PickerModel.importTo(cfg("p1"), setOf("p1"), listOf(step))
        val twice = TapMath.PickerModel.importTo(once, setOf("p1"), listOf(step))
        // 与主界面现有行为一致：纯追加、不去重
        assertEquals(2, twice.points[0].steps.size)
    }

    // ---------- 取消 / 空选择不产生写入 ----------

    @Test
    fun emptySelectionWritesNothing() {
        val c = cfg("p1", "p2")
        val next = TapMath.PickerModel.importTo(c, emptySet(), listOf(Step(1, 1, 200L, 0, 0)))
        assertSame("取消/未勾选时不应产生任何改动", c, next)
    }

    @Test
    fun emptyStepsWritesNothing() {
        val c = cfg("p1")
        assertSame(c, TapMath.PickerModel.importTo(c, setOf("p1"), emptyList()))
    }

    @Test
    fun unselectedPointsAreLeftUntouched() {
        val c = cfg("p1", "p2")
        val next = TapMath.PickerModel.importTo(c, setOf("p1"), listOf(Step(1, 1, 200L, 0, 0)))
        assertSame("未勾选的时间点应原样保留（同一实例）", c.points[1], next.points[1])
    }

    @Test
    fun unknownIdDoesNotWriteOrCrash() {
        val c = cfg("p1")
        val next = TapMath.PickerModel.importTo(c, setOf("不存在的id"), listOf(Step(1, 1, 200L, 0, 0)))
        assertEquals(0, next.points[0].steps.size)
    }

    @Test
    fun duplicateIdsInSelectionWriteOnce() {
        val c = cfg("p1")
        val sel = LinkedHashSet<String>()
        sel.add("p1")
        sel.add("p1")
        val next = TapMath.PickerModel.importTo(c, sel, listOf(Step(1, 1, 200L, 0, 0)))
        assertEquals(1, next.points[0].steps.size)
    }

    // ---------- 写入结构与被导入端契约一致 ----------

    @Test
    fun importedConfigRoundTripsThroughJson() {
        val c = Config(points = listOf(point("p1", 9, 30, 15)))
        val step = Step(540, 1200, 200L, 1080, 2400)
        val next = TapMath.PickerModel.importTo(c, setOf("p1"), listOf(step))

        // 与 saveConfig 实际写入的链路一致：Config -> JSON 字符串 -> Config
        val restored = Config.fromJson(next.toJson().toString())
        assertEquals(1, restored.points.size)
        assertEquals("p1", restored.points[0].id)
        assertEquals(9, restored.points[0].hour)
        assertEquals(30, restored.points[0].minute)
        assertEquals(15, restored.points[0].second)
        assertEquals(1, restored.points[0].steps.size)
        assertEquals(540, restored.points[0].steps[0].x)
        assertEquals(1200, restored.points[0].steps[0].y)
        assertEquals(200L, restored.points[0].steps[0].delayMs)
        assertEquals(1080, restored.points[0].steps[0].sw)
        assertEquals(2400, restored.points[0].steps[0].sh)
    }

    @Test
    fun importedStepUsesSameKeysAsFlutterTapStep() {
        // Flutter TapStep.toJson 写入的键：x / y / delayMs / sw / sh —— 原生必须一致
        val json = Step(1, 2, 200L, 3, 4).toJson()
        assertEquals(1, json.getInt("x"))
        assertEquals(2, json.getInt("y"))
        assertEquals(200L, json.getLong("delayMs"))
        assertEquals(3, json.getInt("sw"))
        assertEquals(4, json.getInt("sh"))
        // 键名完全一致（顺序无关）
        val keys = json.keys().asSequence().toSet()
        assertEquals(setOf("x", "y", "delayMs", "sw", "sh"), keys)
    }

    @Test
    fun importKeepsTimePointFieldsUnchanged() {
        val c = Config(
            points = listOf(
                TimePoint("p1", 7, 5, 3, false, emptyList(), repeatCount = 6, repeatIntervalMs = 750L)
            ),
            tapDurationMs = 45L,
        )
        val next = TapMath.PickerModel.importTo(c, setOf("p1"), listOf(Step(1, 1, 200L, 0, 0)))
        val p = next.points[0]
        assertEquals(7, p.hour)
        assertEquals(5, p.minute)
        assertEquals(3, p.second)
        assertEquals(false, p.enabled)
        assertEquals(6, p.repeatCount)
        assertEquals(750L, p.repeatIntervalMs)
        assertEquals(45L, next.tapDurationMs)
    }

    @Test
    fun hourlyPointLabelIsUsedInLogDetail() {
        // 面板行文案用 label()，每小时模式必须显示为「每小时:分:秒」
        // 第 3 项后 label 改为中文分隔并展示全部信息
        assertEquals("每小时 05分 03秒", TimePoint("p", -1, 5, 3, true, emptyList()).label())
        assertEquals("07时 05分 03秒", TimePoint("p", 7, 5, 3, true, emptyList()).label())
    }

    @Test
    fun jsonImportObjectIsValidJson() {
        // 防御：导入后的配置一定能被解析成合法 JSON（写入端不产生坏数据）
        val next = TapMath.PickerModel.importTo(cfg("p1"), setOf("p1"), listOf(Step(1, 1, 200L, 0, 0)))
        val o = JSONObject(next.toJson().toString())
        assertEquals(1, o.getJSONArray("points").length())
    }
}
