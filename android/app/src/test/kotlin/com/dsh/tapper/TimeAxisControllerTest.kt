package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0 复现与回归测试（审查 BUG-1 + BUG-2）。
 *
 * ## BUG-1（切时间源/改微调后调度轴不跟随）
 * 修复前 (timeSource, timeOffsetMs) 在原生层有**两份**副本：
 *   - OverlayService.timeSourceKind / timeOffsetMs  -> 时钟显示用
 *   - Scheduler.config                              -> 命中判定 / 倒计时 / 下次触发用
 * 面板的 setTimeSource / setTimeOffset 只更新前者并写盘，**不通知 Scheduler**，
 * 于是「时钟已按新轴走、倒计时与点击时刻仍按旧轴走」。
 *
 * ## BUG-2（Flutter 过期副本回写覆盖）
 * 面板改完只写 Prefs，**不发 ACTION_CONFIG 广播**，Flutter 的 _cfg 仍是旧值；
 * 之后任意一次 _save() 整包回写就把面板的改动静默覆盖掉。
 *
 * ## 修复方式
 * 把「一次时间设置变更需要发生的全部副作用」收敛到 [TimeAxisController] 单一入口，
 * 副作用以函数注入 -> 可以在 JVM 单测里断言**每个副作用都被触发、且用的是同一份新配置**，
 * 而不是靠人眼推断接线是否正确。
 *
 * 说明（如实标注）：OverlayService 本身是 Android Service，其真实悬浮窗行为需真机验证；
 * 本测试覆盖的是**判定与接线逻辑**，与项目既有的「判定/执行分离」范式一致。
 */
class TimeAxisControllerTest {

    /** 记录全部副作用的假实现，用于断言「改一处是否真的同时改了显示与调度」。 */
    private class Effects(initial: Config) {
        var persisted: Config? = null
        var schedulerCfg: Config? = null
        var uiNotified = 0
        val syncReasons = ArrayList<String>()
        val logs = ArrayList<String>()
        var loadCount = 0
        /** 模拟 SharedPreferences：改一次就读回最新值，连续微调应累加而非互相覆盖。 */
        var store = initial

        fun controller() = TimeAxisController(
            load = { loadCount++; store },
            persist = { persisted = it },
            applyToScheduler = { schedulerCfg = it },
            notifyUi = { uiNotified++ },
            requestSync = { syncReasons.add(it) },
            log = { logs.add(it) },
        )
    }

    private fun gmt() = java.util.TimeZone.getTimeZone("GMT")

    // ---------- BUG-1：调度轴必须跟随面板改动 ----------

    @Test
    fun changingOffsetUpdatesSchedulerAxisNotJustTheClock() {
        val e = Effects(Config(timeSource = "local", timeOffsetMs = 0L))
        e.controller().setOffset(300L)

        assertEquals("微调必须写入持久化", 300L, e.persisted?.timeOffsetMs)
        assertEquals("微调必须同时作用到调度轴（BUG-1 核心）", 300L, e.schedulerCfg?.timeOffsetMs)
    }

    @Test
    fun changingSourceUpdatesSchedulerAxisNotJustTheClock() {
        val e = Effects(Config(timeSource = "local", timeOffsetMs = 0L))
        e.controller().setSource(TapMath.TimeSourceKind.BEIJING)

        assertEquals("时间源必须写入持久化", "beijing", e.persisted?.timeSource)
        assertEquals("时间源必须同时作用到调度轴（BUG-1 核心）", "beijing", e.schedulerCfg?.timeSource)
    }

    @Test
    fun schedulerReceivesExactlyTheSameConfigThatWasPersisted() {
        val e = Effects(Config(timeSource = "local", timeOffsetMs = 0L))
        e.controller().setOffset(-500L)

        assertEquals("写盘与调度必须是同一份配置，不能各算各的", e.persisted, e.schedulerCfg)
    }

    @Test
    fun displayAxisAndSchedulingAxisAreTheSameAxisAfterChange() {
        val e = Effects(Config(timeSource = "beijing", timeOffsetMs = 0L))
        val applied = e.controller().setOffset(200L)

        // 显示用 TimeAxis.axisOf，调度也必须用同一条轴
        val displayAxis = TimeAxis.axisOf(applied)
        val schedAxis = TimeAxis.axisOf(e.schedulerCfg!!)
        assertEquals("显示轴与调度轴必须一致", displayAxis, schedAxis)
        assertEquals(200L, displayAxis.offsetMs)
        assertEquals(TapMath.TimeSourceKind.BEIJING, displayAxis.kind)
    }

    @Test
    fun switchingSourceAlsoSwitchesTheZoneUsedByBothSides() {
        val e = Effects(Config(timeSource = "local", timeOffsetMs = 0L))
        val applied = e.controller().setSource(TapMath.TimeSourceKind.BEIJING)

        // 北京时间必须固定 UTC+8，且显示与调度用的是同一个时区
        assertEquals(8 * 60 * 60 * 1000, TimeAxis.axisOf(applied).zone.rawOffset)
        assertEquals(TimeAxis.axisOf(applied).zone, TimeAxis.axisOf(e.schedulerCfg!!).zone)
    }

    @Test
    fun changeIsBasedOnTheLatestPersistedConfigNotAStaleInMemoryCopy() {
        val e = Effects(Config(timeSource = "local", timeOffsetMs = 0L))
        val c = e.controller()
        c.setOffset(100L)
        // 第二次改动必须基于第一次的结果（否则会互相覆盖）
        e.store = e.persisted!!
        c.setOffset(200L)

        assertEquals("连续微调必须累加而不是互相覆盖", 200L, e.schedulerCfg?.timeOffsetMs)
        assertEquals("每次都应以最新持久化配置为基准", 2, e.loadCount)
    }

    @Test
    fun offsetIsClampedThroughTheFunnel() {
        val e = Effects(Config(timeOffsetMs = 0L))
        e.controller().setOffset(Long.MAX_VALUE)

        assertEquals(TapMath.ClockFormat.normalizeOffset(Long.MAX_VALUE), e.persisted?.timeOffsetMs)
        assertEquals("夹取后的值必须同样作用于调度轴",
            TapMath.ClockFormat.normalizeOffset(Long.MAX_VALUE), e.schedulerCfg?.timeOffsetMs)
    }

    // ---------- BUG-2：必须通知 Flutter，避免过期副本回写覆盖 ----------

    @Test
    fun uiIsNotifiedSoFlutterCannotOverwriteWithItsStaleCopy() {
        val e = Effects(Config(timeOffsetMs = 0L))
        e.controller().setOffset(400L)

        assertEquals("必须通知 Flutter 重新读取，否则下次保存会覆盖掉面板改动（BUG-2）", 1, e.uiNotified)
    }

    @Test
    fun sourceSwitchAlsoNotifiesUi() {
        val e = Effects(Config(timeSource = "local"))
        e.controller().setSource(TapMath.TimeSourceKind.BEIJING)

        assertEquals(1, e.uiNotified)
    }

    // ---------- 对时策略与日志 ----------

    @Test
    fun switchingToBeijingRequestsSyncButSwitchingToLocalDoesNot() {
        val e1 = Effects(Config(timeSource = "local"))
        e1.controller().setSource(TapMath.TimeSourceKind.BEIJING)
        assertEquals("切到北京时间应立即对时", 1, e1.syncReasons.size)

        val e2 = Effects(Config(timeSource = "beijing"))
        e2.controller().setSource(TapMath.TimeSourceKind.LOCAL)
        assertTrue("切回本地时间不需要对时", e2.syncReasons.isEmpty())
    }

    @Test
    fun offsetChangeNeverTriggersNetworkSync() {
        val e = Effects(Config(timeSource = "beijing", timeOffsetMs = 0L))
        e.controller().setOffset(100L)

        assertTrue("微调是纯本地操作，不应触发网络对时", e.syncReasons.isEmpty())
    }

    @Test
    fun everyChangeIsLoggedForObservability() {
        val e = Effects(Config(timeOffsetMs = 0L))
        e.controller().setOffset(100L)
        e.controller().setSource(TapMath.TimeSourceKind.BEIJING)

        assertEquals("每次变更都应留下日志", 2, e.logs.size)
    }

    // ---------- 时间轴本身的语义（显示 = 调度 = 基准 + 微调）----------

    @Test
    fun axisEffectiveTimeEqualsClockDisplayValue() {
        val cfg = Config(timeSource = "local", timeOffsetMs = 250L)
        val axis = TimeAxis.axisOf(cfg)
        val base = 3L * 60_000L + 12_000L + 400L

        assertEquals("有效时刻必须是 基准 + 微调", base + 250L, axis.effective(base))
        // 显示文本必须与「把有效时刻交给同一格式化函数」一致
        assertEquals(TapMath.ClockFormat.format(base + 250L, axis.zone), axis.format(base))
    }

    @Test
    fun axisIsStableForZeroOffset() {
        val axis = TimeAxis.axisOf(Config(timeSource = "local", timeOffsetMs = 0L))
        assertEquals(0L, axis.offsetMs)
        assertEquals(1_700_000_000_000L, axis.effective(1_700_000_000_000L))
    }

    @Test
    fun axisOfNormalizesOutOfRangeOffsetFromDisk() {
        // 旧数据/被手改的 JSON 可能带越界微调，解析时必须夹取，否则显示与调度都会异常
        val cfg = Config(timeSource = "local", timeOffsetMs = 999_999_999_999L)
        assertEquals(TapMath.ClockFormat.normalizeOffset(999_999_999_999L), TimeAxis.axisOf(cfg).offsetMs)
    }

    // ---------- 复现「两份副本」导致的真实分歧（使用既有生产函数）----------

    @Test
    fun twoStaleCopiesWouldDisagreeOnWhetherThePointIsDue_reproducesBug1Mechanism() {
        // 08:29:59.900，时间点 08:30:00
        val cal = java.util.Calendar.getInstance(gmt())
        cal.set(2024, 0, 15, 8, 29, 59)
        cal.set(java.util.Calendar.MILLISECOND, 900)
        val base = cal.timeInMillis
        val p = TimePoint("p", 8, 30, 0, true, emptyList())

        // 面板已 +300ms（显示轴）：08:30:00.200 -> 用户看到「已到点」
        val shownAxis = TimeAxis(TapMath.TimeSourceKind.LOCAL, 300L)
        // 调度仍用未刷新的旧副本（微调 0）：08:29:59.900 -> 实际「未到点」
        val staleAxis = TimeAxis(TapMath.TimeSourceKind.LOCAL, 0L)

        assertNotEquals("两份副本会产生不同的有效时刻",
            shownAxis.effective(base), staleAxis.effective(base))
        assertTrue("显示轴认为已到点", TapMath.dueKey(p, shownAxis.effective(base), gmt()) != null)
        assertTrue("旧调度副本认为未到点 —— 这正是 BUG-1 的可观测后果",
            TapMath.dueKey(p, staleAxis.effective(base), gmt()) == null)
    }
}
