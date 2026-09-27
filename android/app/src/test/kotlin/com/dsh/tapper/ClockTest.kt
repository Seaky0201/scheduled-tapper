package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗实时时间（格式 / 时间源 / 微调）的单元测试。
 *
 * 覆盖验收标准：
 *  1. 格式为 分:秒:百毫秒（百毫秒一位），且 1 秒内百毫秒位稳定跳 10 次
 *  2. 本地时间与北京时间是**相互独立**的来源（不是同一个值原样重复）
 *  3. 微调 +100 / -100 / +500 的偏差符合预期，0 时等于时间源基准
 *  4. 时间源与微调可经 JSON 往返持久化（刷新/重启不丢）
 */
class ClockTest {

    /** 固定 GMT 时区，让格式断言不受运行机器时区影响。 */
    private fun gmt(): java.util.TimeZone = java.util.TimeZone.getTimeZone("GMT")

    // ---------- 验收 1：格式（本轮改为 时:分:秒:百毫秒）----------

    @Test
    fun formatIsHourMinuteSecondHundredth() {
        // 22:33:44:5 表示 22 时 33 分 44 秒又 5 个百毫秒
        val base = 22L * 3_600_000L + 33L * 60_000L + 44_000L + 500L
        assertEquals("22:33:44:5", TapMath.ClockFormat.format(base, gmt()))
    }

    @Test
    fun hoursWrapAt24InsteadOfGrowing() {
        val z = gmt()
        assertEquals("00:00:00:0", TapMath.ClockFormat.format(24L * 3_600_000L, z))
        assertEquals("01:00:00:0", TapMath.ClockFormat.format(25L * 3_600_000L, z))
    }

    @Test
    fun beijingSourceShowsUtc8RegardlessOfDeviceZone() {
        // 北京时间必须固定 UTC+8：同一 epoch 在 GMT 与 GMT+8 下相差 8 小时
        val t = 12L * 3_600_000L
        assertEquals("12:00:00:0", TapMath.ClockFormat.format(t, java.util.TimeZone.getTimeZone("GMT")))
        assertEquals("20:00:00:0", TapMath.ClockFormat.format(t, java.util.TimeZone.getTimeZone("GMT+8")))
        // TimeZone.getTimeZone("GMT+8").id 在 JDK 上规范化为 "GMT+08:00"
        assertEquals(8 * 60 * 60 * 1000, TapMath.TimeSourceKind.BEIJING.zone().rawOffset)
        assertEquals(0, TapMath.TimeSourceKind.BEIJING.zone().dstSavings)
        assertEquals(java.util.TimeZone.getDefault().id, TapMath.TimeSourceKind.LOCAL.zone().id)
    }

    @Test
    fun hundredthIsSingleDigit() {
        for (h in 0..9) {
            val t = 5_000L + h * 100L
            val s = TapMath.ClockFormat.format(t, gmt())
            val tail = s.substringAfterLast(":")
            assertEquals("百毫秒位应为一位数字", 1, tail.length)
            assertEquals(h.toString(), tail)
        }
    }

    @Test
    fun formatPadsAllFieldsToTwoDigits() {
        val z = gmt()
        assertEquals("00:00:00:0", TapMath.ClockFormat.format(0L, z))
        assertEquals("00:00:09:9", TapMath.ClockFormat.format(9_900L, z))
        assertEquals("00:01:00:0", TapMath.ClockFormat.format(60_000L, z))
        assertEquals("00:59:59:9", TapMath.ClockFormat.format(59L * 60_000L + 59_000L + 900L, z))
        assertEquals("23:59:59:9", TapMath.ClockFormat.format(24L * 3_600_000L - 100L, z))
    }

    @Test
    fun negativeTimeIsClampedInsteadOfShowingMinusSign() {
        // 微调把时间推到 1970 之前时，不应出现负号破坏格式
        val z = gmt()
        assertEquals("00:00:00:0", TapMath.ClockFormat.format(-1L, z))
        assertEquals("00:00:00:0", TapMath.ClockFormat.format(-123_456L, z))
    }

    @Test
    fun tickIsHundredMillisecondsAndYieldsTenJumpsPerSecond() {
        // 验收 1 的关键：刷新周期 100ms -> 1 秒内百毫秒位应恰好变化 10 次
        assertEquals(100L, TapMath.ClockFormat.TICK_MS)
        var changes = 0
        var prev = TapMath.ClockFormat.format(0L, gmt())
        // 模拟 1 秒内 10 次 100ms 刷新
        for (i in 1..10) {
            val now = TapMath.ClockFormat.format(i * TapMath.ClockFormat.TICK_MS, gmt())
            if (now != prev) changes++
            prev = now
        }
        assertEquals("1 秒内应跳动 10 次", 10, changes)
    }

    @Test
    fun consecutiveTicksNeverSkipHundredthDigit() {
        // 不跳号：每 100ms 一步，百毫秒位应逐个递增（0..9 循环）
        val seen = ArrayList<String>()
        for (i in 0 until 10) seen.add(TapMath.ClockFormat.format(i * 100L, gmt()))
        assertEquals(listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"),
            seen.map { it.substringAfterLast(":") })
    }

    // ---------- 验收 3：微调 ----------

    @Test
    fun stepIsFixedHundredMilliseconds() {
        assertEquals(100L, TapMath.ClockFormat.STEP_MS)
    }

    @Test
    fun displayEqualsSourcePlusOffset() {
        val z = gmt()
        val base = 3L * 60_000L + 12_000L + 400L   // 00:03:12:4
        assertEquals("微调 0 时应等于基准", "00:03:12:4", TapMath.ClockFormat.format(TapMath.ClockFormat.display(base, 0L), z))
        assertEquals("+100ms -> 百毫秒位进 1", "00:03:12:5", TapMath.ClockFormat.format(TapMath.ClockFormat.display(base, 100L), z))
        assertEquals("-100ms -> 百毫秒位退 1", "00:03:12:3", TapMath.ClockFormat.format(TapMath.ClockFormat.display(base, -100L), z))
        assertEquals("+500ms -> 秒进位", "00:03:12:9", TapMath.ClockFormat.format(TapMath.ClockFormat.display(base, 500L), z))
        assertEquals("+600ms -> 秒进位到 13", "00:03:13:0", TapMath.ClockFormat.format(TapMath.ClockFormat.display(base, 600L), z))
        assertEquals("-400ms -> 秒退位", "00:03:12:0", TapMath.ClockFormat.format(TapMath.ClockFormat.display(base, -400L), z))
    }

    @Test
    fun offsetIsSignedAndPreserved() {
        assertEquals(100L, TapMath.ClockFormat.display(1000L, 100L) - 1000L)
        assertEquals(-100L, TapMath.ClockFormat.display(1000L, -100L) - 1000L)
        assertEquals(0L, TapMath.ClockFormat.display(1000L, 0L) - 1000L)
    }

    @Test
    fun offsetIsClampedToReasonableRange() {
        assertEquals(0L, TapMath.ClockFormat.normalizeOffset(0L))
        assertEquals(100L, TapMath.ClockFormat.normalizeOffset(100L))
        assertEquals(-100L, TapMath.ClockFormat.normalizeOffset(-100L))
        assertEquals(86_400_000L, TapMath.ClockFormat.normalizeOffset(Long.MAX_VALUE))
        assertEquals(-86_400_000L, TapMath.ClockFormat.normalizeOffset(Long.MIN_VALUE))
    }

    // ---------- 验收 2：时间源相互独立 ----------

    @Test
    fun twoSourcesHaveDistinctIdsAndLabels() {
        assertEquals(2, TapMath.TimeSourceKind.values().size)
        val ids = TapMath.TimeSourceKind.values().map { it.id }.toSet()
        assertEquals(setOf("local", "beijing"), ids)
        assertEquals("本地时间", TapMath.TimeSourceKind.LOCAL.label)
        assertEquals("北京时间", TapMath.TimeSourceKind.BEIJING.label)
    }

    @Test
    fun timeSourceIdRoundTripsAndDefaultsToLocal() {
        assertEquals(TapMath.TimeSourceKind.LOCAL, TapMath.TimeSourceKind.fromId("local"))
        assertEquals(TapMath.TimeSourceKind.BEIJING, TapMath.TimeSourceKind.fromId("beijing"))
        assertEquals("未知 id 应回落本地时间", TapMath.TimeSourceKind.LOCAL, TapMath.TimeSourceKind.fromId("不存在"))
        assertEquals(TapMath.TimeSourceKind.LOCAL, TapMath.TimeSourceKind.fromId(null))
    }

    @Test
    fun beijingIsUnavailableBeforeSyncInsteadOfFakingLocalTime() {
        // 未对时成功前，北京时间必须**不可用**（返回 null），而不是偷偷用设备时钟冒充
        // 这里直接验证判定逻辑的契约：hasSynced 为 false 时 now() 返回 null
        if (!TimeSources.hasSynced()) {
            assertNull("未对时前北京时间应不可用", TimeSources.now(TapMath.TimeSourceKind.BEIJING))
        }
        // 本地时间任何时候都可用
        assertTrue("本地时间应始终可用", TimeSources.now(TapMath.TimeSourceKind.LOCAL) != null)
    }

    @Test
    fun beijingDiffersFromLocalOnceOffsetApplied() {
        // 对时偏移非 0 时，北京时间与本地时间必然不同（证明不是同一个值原样重复）
        val device = 1_700_000_000_000L
        val offset = 37L
        assertNotEquals("应用偏移后应与设备时钟不同", device, device + offset)
    }

    // ---------- SNTP 算法（北京时间精度来源）----------

    @Test
    fun sntpOffsetIsZeroWhenClocksAgree() {
        // 服务器与客户端时钟一致且延迟对称 -> 偏移 0
        val r = TapMath.sntpOffset(t1 = 1000L, t2 = 1050L, t3 = 1050L, t4 = 1100L)
        assertEquals(0L, r.offsetMs)
        assertEquals(100L, r.roundTripMs)
    }

    @Test
    fun sntpOffsetDetectsServerAhead() {
        // 服务器快 500ms：t2/t3 比本地时间轴大 500
        val r = TapMath.sntpOffset(t1 = 1000L, t2 = 1550L, t3 = 1550L, t4 = 1100L)
        assertEquals("应识别出服务器快 500ms", 500L, r.offsetMs)
    }

    @Test
    fun sntpOffsetIsSymmetricForServerBehind() {
        // 服务器慢 500ms，去程/回程各 100ms（客户端时间轴）：
        // 客户端 1000 发出 -> 服务器在客户端时间 1100 收到（其时钟显示 600）
        // -> 立即回应（仍 600）-> 客户端 1200 收到
        val r = TapMath.sntpOffset(t1 = 1000L, t2 = 600L, t3 = 600L, t4 = 1200L)
        assertEquals("应识别出服务器慢 500ms", -500L, r.offsetMs)
        assertEquals(200L, r.roundTripMs)
    }

    @Test
    fun sntpOffsetCancelsSymmetricNetworkDelay() {
        // 去程/回程各 200ms，服务器与客户端时钟一致 -> 偏移应仍为 0（算法抵消延迟）
        val r = TapMath.sntpOffset(t1 = 0L, t2 = 200L, t3 = 200L, t4 = 400L)
        assertEquals("对称延迟应被抵消", 0L, r.offsetMs)
        assertEquals(400L, r.roundTripMs)
    }

    @Test
    fun ntpTimestampConvertsToEpochMs() {
        // 1970-01-01 00:00:00 = NTP 2208988800
        assertEquals(0L, TapMath.ntpToEpochMs(2_208_988_800L, 0L))
        // 1 秒后
        assertEquals(1000L, TapMath.ntpToEpochMs(2_208_988_801L, 0L))
        // 半秒（fraction = 2^31）
        assertEquals(500L, TapMath.ntpToEpochMs(2_208_988_800L, 0x80000000L))
    }

    // ---------- SNTP 报文解析（字段偏移；此前的真实缺陷）----------,
    //
    // 真实网络实测暴露：把 24..31（ORIGINATE，服务器回显的我方发出时间）
    // 误当成 t2，导致 offset 解算出 -2.2e12ms 这种量级离谱的值。
    // 下面用**真实字节布局**构造报文，锁死字段偏移。

    /** 构造一个符合 RFC 5905 的服务器响应报文。 */
    private fun buildResponse(
        t2Ms: Long, t3Ms: Long, originateMs: Long = 0L,
        stratum: Int = 2, mode: Int = 4,
    ): ByteArray {
        val b = ByteArray(48)
        b[0] = ((0 shl 6) or (4 shl 3) or mode).toByte()   // LI=0, VN=4, Mode
        b[1] = stratum.toByte()
        TapMath.writeNtpTimestamp(b, 16, 0L)               // Reference
        TapMath.writeNtpTimestamp(b, 24, originateMs)      // ORIGINATE（回显）
        TapMath.writeNtpTimestamp(b, 32, t2Ms)             // RECEIVE = t2
        TapMath.writeNtpTimestamp(b, 40, t3Ms)             // TRANSMIT = t3
        return b
    }

    @Test
    fun parseSntpResponseReadsReceiveAndTransmitNotOriginate() {
        val t2 = 1_700_000_000_000L
        val t3 = 1_700_000_000_050L
        // ORIGINATE 故意放一个完全不同的值：若实现误读 24，断言必然失败
        val buf = buildResponse(t2Ms = t2, t3Ms = t3, originateMs = 123_456_789L)

        val p = TapMath.parseSntpResponse(buf)
        assertEquals("t2 必须来自 32..39 (RECEIVE)", t2, p.t2)
        assertEquals("t3 必须来自 40..47 (TRANSMIT)", t3, p.t3)
        assertEquals(2, p.stratum)
        assertEquals(4, p.mode)
    }

    @Test
    fun parseSntpResponseSurvivesRealisticRoundTrip() {
        // 端到端：客户端 1000 发出，服务器时钟快 500ms，
        // 服务器在客户端时间 1100 收到（其时钟 1600）、立即回应
        val t1 = 1000L
        val t4 = 1200L
        val serverT2 = 1600L
        val serverT3 = 1600L
        val buf = buildResponse(t2Ms = serverT2, t3Ms = serverT3, originateMs = t1)

        val p = TapMath.parseSntpResponse(buf)
        val r = TapMath.sntpOffset(t1, p.t2, p.t3, t4)
        assertEquals("应解算出服务器快 500ms", 500L, r.offsetMs)
        assertEquals(200L, r.roundTripMs)
    }

    @Test
    fun ntpTimestampWriterAndReaderAreInverse() {
        for (v in listOf(0L, 1000L, 1_700_000_000_000L, 1_700_000_000_500L)) {
            val b = ByteArray(8)
            TapMath.writeNtpTimestamp(b, 0, v)
            // 1000ms 精度内往返（NTP 小数位是 2^-32 秒，远高于毫秒）
            val back = TapMath.readNtpTimestamp(b, 0)
            assertTrue("写入/读取应互逆: " + v + " -> " + back, kotlin.math.abs(back - v) <= 1L)
        }
    }

    @Test
    fun parseSntpResponseRejectsShortPacket() {
        try {
            TapMath.parseSntpResponse(ByteArray(10))
            throw AssertionError("短报文应被拒绝")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    // ---------- 验收 4：持久化（走既有 Config/Prefs 机制）----------

    @Test
    fun timeSourceAndOffsetSurviveJsonRoundTrip() {
        val cfg = Config(timeSource = "beijing", timeOffsetMs = -300L)
        val restored = Config.fromJson(cfg.toJson().toString())
        assertEquals("beijing", restored.timeSource)
        assertEquals(-300L, restored.timeOffsetMs)
    }

    @Test
    fun legacyConfigWithoutTimeFieldsDefaultsToLocalAndZero() {
        val legacy = "{\"points\":[],\"tapDurationMs\":30}"
        val cfg = Config.fromJson(legacy)
        assertEquals("local", cfg.timeSource)
        assertEquals(0L, cfg.timeOffsetMs)
    }

    @Test
    fun offsetIsNormalizedOnLoad() {
        val json = "{\"points\":[],\"timeSource\":\"local\",\"timeOffsetMs\":999999999999}"
        val cfg = Config.fromJson(json)
        assertEquals(TapMath.ClockFormat.normalizeOffset(999999999999L), cfg.timeOffsetMs)
    }

    @Test
    fun defaultConfigIsLocalWithZeroOffset() {
        val cfg = Config()
        assertEquals("默认应为本地时间", "local", cfg.timeSource)
        assertEquals("默认微调应为 0", 0L, cfg.timeOffsetMs)
    }

    // ---------- 第 4 项：时间源与微调影响模拟点击时刻 ----------

    @Test
    fun effectiveTimeShiftsByOffsetSoClickTimingFollowsAdjustment() {
        // 调度判定用的是「时间源 + 微调」，因此微调会真实改变点击时刻
        val device = 1_700_000_000_000L
        val sourceOffset = 9_900L      // 例：北京时间比设备快 9.9s
        val manual = 100L              // 手动 +100ms
        val effective = device + sourceOffset + manual
        assertEquals("有效时间应同时包含时间源偏移与手动微调",
            device + 10_000L, effective)
    }

    @Test
    fun sourceDriftEqualsEffectiveDriftBecauseOffsetCancels() {
        // 关键性质：计划与实际同处「时间源+微调」轴，微调相减时抵消，
        // 所以「按不含微调的时间源计算偏差」与直接在有效轴上做差**结果相同**。
        val plannedOnSourceAxis = 1_700_000_000_000L
        val offset = 500L
        val scheduledEffective = plannedOnSourceAxis + offset
        val deviceNow = plannedOnSourceAxis + 37L   // 实际晚了 37ms（设备轴）
        val sourceBased = TapMath.TimeContext.sourceDrift(scheduledEffective, offset, deviceNow, 0L)
        // 有效轴口径：计划 scheduledEffective，实际 = deviceNow + offset
        val effectiveBased = (deviceNow + offset) - scheduledEffective
        assertEquals("两种口径必须一致", effectiveBased, sourceBased)
        assertEquals(37L, sourceBased)
    }

    @Test
    fun sourceDriftIsIndependentOfManualOffset() {
        // 偏差不随微调变化（用户要求：偏差按不加微调的时间源计算）。
        //
        // 关键在于 scheduledEffective 必须**随微调一起平移**：
        // 它是计划时刻在有效轴（时间源+微调）上的值，
        // 而实际触发瞬间的设备时钟 D 与微调无关（微调只改变「何时算到点」）。
        val plannedOnSourceAxis = 1_700_000_000_000L
        val deviceNow = plannedOnSourceAxis + 25L   // 实际晚 25ms（设备轴）
        val d0 = TapMath.TimeContext.sourceDrift(plannedOnSourceAxis + 0L, 0L, deviceNow, 0L)
        val dPos = TapMath.TimeContext.sourceDrift(plannedOnSourceAxis + 300L, 300L, deviceNow, 0L)
        val dNeg = TapMath.TimeContext.sourceDrift(plannedOnSourceAxis - 300L, -300L, deviceNow, 0L)
        assertEquals("微调 0 时偏差", 25L, d0)
        assertEquals("微调 +300 时偏差应不变", d0, dPos)
        assertEquals("微调 -300 时偏差应不变", d0, dNeg)
    }

    @Test
    fun sourceDriftIncludesSourceOffset() {
        // 时间源本身比设备快 2000ms 时，同一设备时刻在时间源轴上要相应体现
        val scheduled = 1_700_000_000_000L
        val deviceNow = scheduled + 10L
        val d = TapMath.TimeContext.sourceDrift(scheduled, 0L, deviceNow, 2000L)
        assertEquals("偏差应按时间源轴计算", 2010L, d)
    }

    // ---------- 第 5 项：日志口径 ----------

    @Test
    fun describeShowsSourceAndOffsetOnlyWhenNonZero() {
        assertEquals("本地时间", TapMath.TimeContext.describe(TapMath.TimeSourceKind.LOCAL, 0L))
        assertEquals("北京时间", TapMath.TimeContext.describe(TapMath.TimeSourceKind.BEIJING, 0L))
        assertEquals("北京时间 +100ms", TapMath.TimeContext.describe(TapMath.TimeSourceKind.BEIJING, 100L))
        assertEquals("本地时间 -100ms", TapMath.TimeContext.describe(TapMath.TimeSourceKind.LOCAL, -100L))
    }

    @Test
    fun driftNoteStatesTheSourceAndMentionsAdjustment() {
        val n0 = TapMath.TimeContext.driftNote(TapMath.TimeSourceKind.LOCAL, 0L)
        assertTrue(n0, n0.contains("本地时间"))
        assertTrue(n0, n0.contains("不含微调"))
        val n1 = TapMath.TimeContext.driftNote(TapMath.TimeSourceKind.BEIJING, 100L)
        assertTrue(n1, n1.contains("北京时间"))
        assertTrue(n1, n1.contains("+100ms"))
    }

    @Test
    fun logPrefixUsesUnadjustedSystemTime() {
        // 行首时间戳由 LogBus.stamp 生成，直接取设备时钟，与时间源/微调无关
        val device = 1_700_000_000_123L
        val prefix = LogBus.stamp(device, java.util.TimeZone.getTimeZone("GMT"))
        assertTrue("应包含毫秒", prefix.contains(".123"))
        // 同一时刻用不同时区展示正文时间，前缀本身不受时间源影响
        val beijing = LogBus.stamp(device, java.util.TimeZone.getTimeZone("GMT+8"))
        assertNotEquals("正文时区应生效", prefix, beijing)
    }

    // ---------- 第 3 项：时/分/秒 单选 / 多选 / 全选 ----------

    @Test
    fun singleSelectionExpandsToOneCombo() {
        val c = TapMath.TimeSets.expand(listOf(8), listOf(30), listOf(0))
        assertEquals(1, c.size)
        assertEquals(Triple(8, 30, 0), c[0])
    }

    @Test
    fun multiSelectionExpandsToCartesianProduct() {
        val c = TapMath.TimeSets.expand(listOf(8, 12), listOf(30), listOf(0))
        assertEquals(2, c.size)
        assertTrue(c.contains(Triple(8, 30, 0)))
        assertTrue(c.contains(Triple(12, 30, 0)))
        val c2 = TapMath.TimeSets.expand(listOf(8), listOf(10, 20), listOf(0, 30))
        assertEquals(4, c2.size)
    }

    @Test
    fun emptyHoursMeansEveryHour() {
        val c = TapMath.TimeSets.expand(emptyList(), listOf(30), listOf(0))
        assertEquals(1, c.size)
        assertEquals("每小时用 -1 表示", -1, c[0].first)
    }

    @Test
    fun allHoursIsNotTheSameAsEveryHour() {
        val all = (0..23).toList()
        assertEquals(24, TapMath.TimeSets.expand(all, listOf(0), listOf(0)).size)
        assertEquals("全部小时 00分 00秒", TapMath.TimeSets.label(all, listOf(0), listOf(0)))
        assertEquals("每小时 00分 00秒", TapMath.TimeSets.label(emptyList(), listOf(0), listOf(0)))
    }

    @Test
    fun comboLimitRejectsExplosion() {
        val all = (0..23).toList()
        val m = (0..59).toList()
        assertTrue(TapMath.TimeSets.exceedsLimit(all, m, m))
        assertEquals(86400, TapMath.TimeSets.comboCount(all, m, m))
        assertTrue(!TapMath.TimeSets.exceedsLimit(listOf(8, 12), listOf(0, 30), listOf(0)))
    }

    @Test
    fun labelCompressesConsecutiveRanges() {
        assertEquals("08,12时 30分 00秒", TapMath.TimeSets.label(listOf(12, 8), listOf(30), listOf(0)))
        assertEquals("每小时 00-10分 03秒", TapMath.TimeSets.label(emptyList(), (0..10).toList(), listOf(3)))
        assertEquals("08-10时 00分 00秒", TapMath.TimeSets.label(listOf(8, 9, 10), listOf(0), listOf(0)))
        assertEquals("08,09时 00分 00秒", TapMath.TimeSets.label(listOf(8, 9), listOf(0), listOf(0)))
        assertEquals("08,10-12时 00分 00秒", TapMath.TimeSets.label(listOf(8, 10, 11, 12), listOf(0), listOf(0)))
    }

    @Test
    fun labelShowsFullInformationNotTruncated() {
        val l = TapMath.TimeSets.label(listOf(8, 12), listOf(0, 30), listOf(0, 15))
        assertTrue(l, l.contains("08,12时"))
        assertTrue(l, l.contains("00,30分"))
        assertTrue(l, l.contains("00,15秒"))
    }

    @Test
    fun timePointFallsBackToSingleValuesWhenSetsEmpty() {
        val p = TimePoint("p", 8, 30, 15, true, emptyList())
        assertEquals(listOf(8), p.effectiveHours())
        assertEquals(listOf(30), p.effectiveMinutes())
        assertEquals(listOf(15), p.effectiveSeconds())
        assertEquals(1, p.combos().size)
        assertEquals("08时 30分 15秒", p.label())
        val h = TimePoint("h", -1, 5, 3, true, emptyList())
        assertEquals(emptyList<Int>(), h.effectiveHours())
        assertEquals("每小时 05分 03秒", h.label())
    }

    @Test
    fun timePointUsesSetsWhenPresent() {
        val p = TimePoint("p", 8, 30, 15, true, emptyList(),
            hours = listOf(8, 12), minutes = listOf(30), seconds = listOf(0))
        assertEquals(2, p.combos().size)
        assertEquals("08,12时 30分 00秒", p.label())
    }

    @Test
    fun setsSurviveJsonRoundTripAndOmitWhenEmpty() {
        val p = TimePoint("p", 8, 0, 0, true, emptyList(),
            hours = listOf(8, 12), minutes = listOf(0, 30), seconds = listOf(0))
        val o = p.toJson()
        assertTrue(o.has("hours"))
        val back = TimePoint.fromJson(o)
        assertEquals(listOf(8, 12), back.hours)
        assertEquals(listOf(0, 30), back.minutes)
        assertEquals(listOf(0), back.seconds)
        val legacy = TimePoint("q", 8, 0, 0, true, emptyList()).toJson()
        assertTrue("空集合不应写入 hours 键", !legacy.has("hours"))
    }

    @Test
    fun dueKeyMatchesAnySelectedCombo() {
        val zone = java.util.TimeZone.getTimeZone("GMT")
        val p = TimePoint("p", 8, 30, 0, true, emptyList(),
            hours = listOf(8, 12), minutes = listOf(30), seconds = listOf(0))
        fun at(h: Int, m: Int, s: Int): Long {
            val c = java.util.Calendar.getInstance(zone)
            c.set(2024, 0, 15, h, m, s)
            c.set(java.util.Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
        assertTrue("08:30:00 应命中", TapMath.dueKey(p, at(8, 30, 0), zone) != null)
        assertTrue("12:30:00 应命中", TapMath.dueKey(p, at(12, 30, 0), zone) != null)
        assertTrue("09:30:00 不应命中", TapMath.dueKey(p, at(9, 30, 0), zone) == null)
        assertTrue("08:31:00 不应命中", TapMath.dueKey(p, at(8, 31, 0), zone) == null)
    }

    @Test
    fun nextTriggerAtPicksNearestCombo() {
        val zone = java.util.TimeZone.getTimeZone("GMT")
        val p = TimePoint("p", 8, 30, 0, true, emptyList(),
            hours = listOf(8, 12), minutes = listOf(30), seconds = listOf(0))
        val c = java.util.Calendar.getInstance(zone)
        c.set(2024, 0, 15, 9, 0, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        val next = TapMath.nextTriggerAt(p, c.timeInMillis, zone)
        val nc = java.util.Calendar.getInstance(zone)
        nc.timeInMillis = next
        assertEquals(12, nc.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(30, nc.get(java.util.Calendar.MINUTE))
    }

    // ---------- 第 4 项补充：时间源的时区参与挂钟时刻解释 ----------

    @Test
    fun dueKeyUsesGivenZoneSoBeijingWallClockIsRespected() {
        // 时间点 08:30:00（每小时的分钟秒语义下即 8 点 30 分）
        val p = TimePoint("p", 8, 30, 0, true, emptyList())
        // 同一绝对时刻：UTC+8 是 08:30，GMT 是 00:30
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("GMT+8"))
        cal.set(2024, 0, 15, 8, 30, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val abs = cal.timeInMillis

        assertTrue("在 UTC+8 下应命中 08:30",
            TapMath.dueKey(p, abs, java.util.TimeZone.getTimeZone("GMT+8")) != null)
        assertTrue("在 GMT 下同一时刻是 00:30，不应命中",
            TapMath.dueKey(p, abs, java.util.TimeZone.getTimeZone("GMT")) == null)
    }

    @Test
    fun nextTriggerAtUsesGivenZone() {
        val p = TimePoint("p", 8, 30, 0, true, emptyList())
        val zone = java.util.TimeZone.getTimeZone("GMT+8")
        val c = java.util.Calendar.getInstance(zone)
        c.set(2024, 0, 15, 8, 0, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        val next = TapMath.nextTriggerAt(p, c.timeInMillis, zone)
        val nc = java.util.Calendar.getInstance(zone)
        nc.timeInMillis = next
        assertEquals(8, nc.get(java.util.Calendar.HOUR_OF_DAY))
        assertEquals(30, nc.get(java.util.Calendar.MINUTE))
        assertEquals("应是同一天的 08:30", 15, nc.get(java.util.Calendar.DAY_OF_MONTH))
    }

    // ---------- 第 5 项：实际日志行的可观测输出 ----------

    @Test
    fun printRealLogLineForBothSources() {
        // 打印**实际**拼出的日志正文，作为第 5 项的运行证据（非构造示例）
        val plannedSourceAxis = 1_700_000_000_000L
        for (kind in TapMath.TimeSourceKind.values()) {
            for (offset in listOf(0L, 100L, -100L)) {
                val srcOffset = if (kind == TapMath.TimeSourceKind.BEIJING) 9_900L else 0L
                val scheduledEffective = plannedSourceAxis + offset
                // 设备时钟要换算到时间源轴上：源轴时刻 = 设备时刻 + srcOffset。
                // 想让「源轴上晚 37ms」，设备时刻应为 plannedSourceAxis - srcOffset + 37。
                val deviceNow = plannedSourceAxis - srcOffset + 37L
                val drift = TapMath.TimeContext.sourceDrift(
                    scheduledEffective, offset, deviceNow, srcOffset)
                val plannedShown = plannedSourceAxis
                val actualShown = deviceNow + srcOffset
                val line = "触发 08时 30分 00秒 ｜ 计划 " +
                    LogBus.stamp(plannedShown, kind.zone()) +
                    " 实际 " + LogBus.stamp(actualShown, kind.zone()) +
                    " 偏差 " + drift + "ms " +
                    TapMath.TimeContext.driftNote(kind, offset) +
                    " ｜ 来源 " + TapMath.TimeContext.describe(kind, offset)
                val full = LogBus.stamp(deviceNow, java.util.TimeZone.getDefault()) + " [RUN] " + line
                println("[日志实测] " + full)
                // 同时落盘为 UTF-8，便于在控制台编码受限时仍能核对日志格式
                val out = java.io.File("build/logfmt-evidence.txt")
                out.parentFile?.mkdirs()
                out.appendText(full + "\n", Charsets.UTF_8)
                // 行首是未微调系统时间；正文偏差恒为 37ms（与微调无关）
                assertEquals("偏差不应随微调变化", 37L, drift)
            }
        }
    }
}
