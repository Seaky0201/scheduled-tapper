package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 复现与回归测试（审查 BUG-8）。
 *
 * ## 缺陷
 * [TapMath.TimeSourceKind] 的文档注释声称北京时间
 * 「与设备时钟**相互独立**——对时后即使把设备时钟改掉，北京时间显示也不受影响」，
 * 但实现是 `System.currentTimeMillis() + offsetMs`（TimeSources.now），
 * **明确依赖设备时钟**。同一份代码里 [TimeSources] 的类注释写的才是对的：
 * 「两次对时之间我们用的是设备时钟的**增量**，所以期间修改设备时钟会带来偏差」。
 *
 * 危害：误导后续维护者按"独立时钟"的假设去改动代码（例如误以为可以省掉对时、或据此推断精度）。
 *
 * ## 修复方式
 * 1. 更正 TapMath 的注释，与 TimeSources 的既有正确描述对齐；
 * 2. 把该语义抽成具名纯函数 [TimeSources.beijingFromDevice]，
 *    使「北京时间依赖设备时钟」这一事实可以被测试直接锁定，而不是只写在注释里。
 *
 * 说明：本项**不改变任何运行时行为**（纯函数与原表达式等价），仅消除文档与实现的分歧。
 */
class BeijingClockSemanticsTest {

    // ---------- 锁定真实语义：北京时间 = 设备时钟 + 对时偏移 ----------

    @Test
    fun beijingIsDerivedFromDeviceClockPlusOffset() {
        val device = 1_700_000_000_000L
        val offset = 9_900L
        assertEquals("实现就是 设备时钟 + 偏移，并非独立时钟",
            device + offset, TimeSources.beijingFromDevice(device, offset))
    }

    @Test
    fun changingDeviceClockDoesChangeBeijing_becauseCommentWasWrong() {
        // 同一偏移下，设备时钟变了，北京时间跟着变 —— 直接反驳原注释的"不受影响"
        val offset = 9_900L
        val before = TimeSources.beijingFromDevice(1_700_000_000_000L, offset)
        val after = TimeSources.beijingFromDevice(1_700_000_060_000L, offset)

        assertNotEquals("设备时钟改变后北京时间必然改变（原注释称'不受影响'是错的）", before, after)
        assertEquals("变化量等于设备时钟的变化量", 60_000L, after - before)
    }

    @Test
    fun onlyTheOffsetIsIndependentOfTheDeviceClock() {
        // 真正"独立"的是**偏移量**本身（由 SNTP 测得），而不是最终显示值
        val device = 1_700_000_000_000L
        val a = TimeSources.beijingFromDevice(device, 1_000L)
        val b = TimeSources.beijingFromDevice(device, 2_000L)
        assertEquals("同设备时刻下，偏移不同 -> 结果不同", 1_000L, b - a)
    }

    @Test
    fun zeroOffsetMeansBeijingEqualsDeviceClock() {
        // 边界：偏移为 0 时两者完全重合（证明不是"独立的第二个时钟"）
        val device = 1_700_000_000_000L
        assertEquals(device, TimeSources.beijingFromDevice(device, 0L))
    }

    @Test
    fun negativeOffsetIsSupported() {
        val device = 1_700_000_000_000L
        assertEquals(device - 500L, TimeSources.beijingFromDevice(device, -500L))
    }

    // ---------- 与「可用性」契约保持一致（不静默伪造）----------

    @Test
    fun beijingIsUnavailableBeforeAnySuccessfulSync() {
        if (!TimeSources.hasSynced()) {
            assertEquals("未对时前必须返回 null（不可用），不得用设备时钟冒充",
                null, TimeSources.now(TapMath.TimeSourceKind.BEIJING))
        }
    }

    @Test
    fun localIsAlwaysAvailable() {
        assertTrue(TimeSources.now(TapMath.TimeSourceKind.LOCAL) != null)
    }

    // ---------- 时间源切换后仍指向同一套语义 ----------

    @Test
    fun beijingZoneStaysUtc8RegardlessOfDerivation() {
        // 派生方式依赖设备时钟，但**显示时区**固定 UTC+8 —— 两件事不要混淆
        assertEquals(8 * 60 * 60 * 1000, TapMath.TimeSourceKind.BEIJING.zone().rawOffset)
        assertEquals(0, TapMath.TimeSourceKind.BEIJING.zone().dstSavings)
    }

    @Test
    fun axisUsesTheSameDerivationPath() {
        // 时间轴（显示与调度共用）对北京时间的处理必须与 TimeSources 一致
        val device = 1_700_000_000_000L
        val offset = 9_900L
        val axis = TimeAxis(TapMath.TimeSourceKind.BEIJING, 300L)

        assertEquals("有效时刻 = 北京时间 + 微调",
            TimeSources.beijingFromDevice(device, offset) + 300L, axis.effective(device + offset))
    }
}
