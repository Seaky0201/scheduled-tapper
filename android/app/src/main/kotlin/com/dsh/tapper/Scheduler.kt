package com.dsh.tapper

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.TimeZone

/**
 * 定时调度。
 *
 * 硬约束 3：不靠固定 sleep 累加。每一轮都用「系统日历时间」重新计算下一次触发时刻，
 * 因此无论线程调度如何抖动都不会累积漂移；若某次唤醒晚了，下一轮仍按绝对时间对齐。
 *
 * 重复触发（本轮新增）：
 * - 基准时刻命中后开始一轮，第 1 次立即执行；
 * - 第 2..N 次的计划时刻 = 基准时刻 + (k-1) * 间隔，按**绝对时刻**判定，同样不依赖 sleep 累加；
 * - 到达次数上限后本轮结束，等下一个基准时刻再开始新一轮；
 * - 若上一次序列尚未执行完，则**不推进**进度，等它结束后立即补上（间隔 0 时尤为重要），
 *   因此不会因为「上一条还在跑」而漏掉重复次数。
 */
class Scheduler(private val ctx: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    @Volatile private var config: Config = Config()

    /**
     * 已触发过的基准时刻去重集合，防止同一秒重复开始一轮。
     * P2 / BUG-6：改为 FIFO 淘汰，容量满时不再整体清空（清空会把刚加入的键一起丢掉，
     * 导致同一秒的下一个 tick 重复触发）。
     */
    private val fired = FiredKeys()

    /** 每个时间点当前一轮的重复进度：id -> RepeatState。 */
    private val repeatStates = HashMap<String, RepeatState>()

    /** 因上一条序列仍在执行而等待的时间点，记录已提示到的次数，避免每 200ms 刷屏。 */
    private val waitingForBusy = HashMap<String, Int>()

    @Volatile private var nextFireLabel: String = "-"

    fun start(cfg: Config) {
        config = cfg
        if (loop?.isActive == true) return
        loop = scope.launch {
            LogBus.add(ctx, "OK", "调度线程已启动（按系统时间对齐，非 sleep 累加）")
            while (isActive) {
                try {
                    tick()
                } catch (t: Throwable) {
                    LogBus.add(ctx, "ERROR", "调度循环异常: " + t)
                }
                delay(200L)
            }
        }
    }

    /**
     * 配置更新。
     * 若某时间点的重复参数发生变化，或该时间点被删除，则清空其重复进度，
     * 避免沿用旧一轮的计数导致「多触发/漏触发」。
     */
    fun update(cfg: Config) {
        val old = config
        val oldById = old.points.associateBy { it.id }
        val newById = cfg.points.associateBy { it.id }
        var reason: String? = null
        for (id in repeatStates.keys.toList()) {
            val o = oldById[id]
            val n = newById[id]
            if (SchedulerProgress.shouldReset(o, n)) {
                reason = SchedulerProgress.resetReason(o, n)
                break
            }
        }
        if (reason != null) {
            repeatStates.clear()
            waitingForBusy.clear()
            LogBus.add(ctx, "OK", reason)
        }
        config = cfg
    }

    fun stop() {
        loop?.cancel()
        loop = null
    }

    fun nextFireInfo(): String = nextFireLabel

    /** 时间源不可用（如北京时间尚未对时成功）时的回落告警，避免每 200ms 刷屏。 */
    @Volatile private var fallbackWarned = false

    /**
     * 用于**调度判定**的当前时刻 = 所选时间源 + 手动微调。
     *
     * 这是「时间源与微调影响模拟点击时刻」的落点：命中判定、倒计时、
     * 下一次触发时刻全部基于这条时间轴，因此切换时间源或改微调都会真实改变点击时刻。
     *
     * 时间源不可用时的处理（用户确认的预案 A）：回落到本地时间继续调度，
     * 并在日志里**明确标注降级**，不静默假装是北京时间。
     */
    private fun effectiveNow(cfg: Config): Long {
        val a = TimeAxis.axisOf(cfg)
        val base = TimeSources.now(a.kind)
        if (base == null) {
            if (!fallbackWarned) {
                fallbackWarned = true
                LogBus.add(
                    ctx, "WARN",
                    a.kind.label + "未就绪，本次调度回落到本地时间（对时成功后自动恢复）"
                )
            }
            return System.currentTimeMillis() + a.offsetMs
        }
        if (fallbackWarned) {
            fallbackWarned = false
            LogBus.add(ctx, "OK", a.kind.label + "已就绪，调度恢复使用该时间源")
        }
        return a.effective(base)
    }

    /**
     * 所选时间源的时区。
     * 与显示侧共用 [TimeAxis]：本地=设备时区；北京时间=UTC+8（P0：显示与调度同轴）。
     */
    private fun sourceZone(cfg: Config): TimeZone = TimeAxis.axisOf(cfg).zone

    private fun tick() {
        val cfg = config
        // 本次 tick 使用的唯一时间轴（显示与调度同轴，P0）
        val axis = TimeAxis.axisOf(cfg)
        val now = effectiveNow(cfg)

        // 1) 基准时刻命中 -> 开始新一轮（第 1 次）
        for (p in cfg.points) {
            if (!p.enabled || p.steps.isEmpty()) continue
            // 时区必须跟随所选时间源：北京时间下的 08:30 应指北京时间 08:30，
            // 而不是设备本地时区的 08:30（否则时间源对「几点触发」不生效）
            val key = TapMath.dueKey(p, now, sourceZone(cfg)) ?: continue
            // 首次加入才继续；已存在说明本秒已处理过（去重）
            if (!fired.add(key)) continue
            if (SequenceRunner.isRunning()) {
                LogBus.add(ctx, "WARN", ("时间点 " + p.label() + " 到点，但上一条序列仍在执行，跳过"))
                continue
            }
            val scheduled = now - (now % 1000L) // 命中的整秒
            val total = TapMath.normalizeRepeatCount(p.repeatCount)
            repeatStates[p.id] = RepeatState(scheduled, 1)
            LogBus.add(
                ctx, "RUN",
                ("到点 " + p.label() + " ｜ 重复设置：" + p.repeatLabel() + " ｜ 第 1/" + total + " 次")
            )
            SequenceRunner.run(
                ctx, p.label(), p.steps, cfg.tapDurationMs, scheduled,
                axis.kind, axis.offsetMs,
            )
        }

        // 2) 进行中的重复轮次 -> 按绝对时刻推进第 2..N 次
        for (p in cfg.points) {
            if (!p.enabled || p.steps.isEmpty()) continue
            val st = repeatStates[p.id] ?: continue
            val d = TapMath.nextRepeatDecision(p, st, now) ?: continue
            if (SequenceRunner.isRunning()) {
                // 不推进进度：等上一条执行完再补，避免漏掉次数（间隔 0 时尤其关键）
                if (waitingForBusy[p.id] != d.index) {
                    waitingForBusy[p.id] = d.index
                    LogBus.add(ctx, "WAIT", ("重复第 " + d.index + "/" + d.total + " 次到点，等上一条序列结束后立即执行"))
                }
                continue
            }
            waitingForBusy.remove(p.id)
            repeatStates[p.id] = d.newState
            LogBus.add(
                ctx, "RUN",
                ("重复触发 " + p.label() + " ｜ 第 " + d.index + "/" + d.total + " 次" +
                    (if (d.cycleFinished) "（本轮最后一次，之后回到待机）" else ""))
            )
            SequenceRunner.run(
                ctx, p.label() + " 第" + d.index + "次", p.steps, cfg.tapDurationMs, d.scheduledAt,
                axis.kind, axis.offsetMs,
            )
        }

        // 3) 维护「下一次触发」展示信息（含进行中的重复）
        //    判定与渲染都走 SchedulerDisplay 纯函数：
        //    - BUG-5：候选点必须 enabled 且有步骤（与上面的命中条件一致，避免展示不会触发的点）
        //    - BUG-4：逾期时不再输出负数秒
        val next = SchedulerDisplay.compute(cfg, repeatStates, now, sourceZone(cfg))
        nextFireLabel = SchedulerDisplay.render(next, now)
    }

    companion object {
        fun tzName(): String = TimeZone.getDefault().displayName ?: TimeZone.getDefault().id
    }
}

/**
 * 「配置更新后是否需要重置重复进度」的**判定逻辑**（纯函数，可在 JVM 单测中覆盖）。
 *
 * 抽出来的原因：这段判定原先内联在 [Scheduler.update] 里，依赖 Scheduler 实例，
 * 无法在 JVM 上直接断言；而它恰好是 P1（审查 BUG-7）的藏身处。
 *
 * 重置条件（缺一不可，任一满足即重置）：
 *  - 时间点被删除（旧有值、新值不存在）
 *  - 重复次数变化（旧有行为）
 *  - 重复间隔变化（旧有行为）
 *  - **启用状态变化（本次修复 BUG-7 新增）**
 *
 * 为什么 enabled 必须纳入：停用时 Scheduler 只是 `continue` 跳过该点，
 * 并不清除它的 repeatStates；停用期间"到点但没执行"的重复次数会一直欠着，
 * 重新启用后会因 `now >= at` 立即成立而**突发连续补触发**。
 * 停用即重置，可保证重新启用后从干净的进度开始，不会补发停用期间的欠账。
 */
object SchedulerProgress {

    /**
     * @param old 该 id 的旧时间点；null 表示当时不存在
     * @param new 该 id 的新时间点；null 表示已被删除
     */
    fun shouldReset(old: TimePoint?, new: TimePoint?): Boolean {
        if (old == null || new == null) return true
        if (old.repeatCount != new.repeatCount) return true
        if (old.repeatIntervalMs != new.repeatIntervalMs) return true
        // P1 / BUG-7：启用状态变化同样必须重置
        if (old.enabled != new.enabled) return true
        return false
    }

    /** 重置原因（写进日志，便于用户追溯"为什么重新启用后没有立刻触发"）。 */
    fun resetReason(old: TimePoint?, new: TimePoint?): String = when {
        old == null || new == null -> "时间点已删除，相关重复进度已重置"
        old.repeatCount != new.repeatCount || old.repeatIntervalMs != new.repeatIntervalMs ->
            "重复参数已变更，相关重复进度已重置"
        old.enabled != new.enabled ->
            "时间点启用状态已变更，相关重复进度已重置（避免补发停用期间的欠账）"
        else -> "重复进度已重置"
    }
}
