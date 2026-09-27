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

    /** 已触发过的基准时刻去重集合，防止同一秒重复开始一轮。 */
    private val fired = HashSet<String>()

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
        var changed = false
        for (id in repeatStates.keys.toList()) {
            val o = oldById[id]
            val n = newById[id]
            if (o == null || n == null || o.repeatCount != n.repeatCount || o.repeatIntervalMs != n.repeatIntervalMs) {
                changed = true
                break
            }
        }
        if (changed) {
            repeatStates.clear()
            waitingForBusy.clear()
            LogBus.add(ctx, "OK", "重复参数已变更，相关重复进度已重置")
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
        val kind = TapMath.TimeSourceKind.fromId(cfg.timeSource)
        val base = TimeSources.now(kind)
        if (base == null) {
            if (!fallbackWarned) {
                fallbackWarned = true
                LogBus.add(
                    ctx, "WARN",
                    kind.label + "未就绪，本次调度回落到本地时间（对时成功后自动恢复）"
                )
            }
            return System.currentTimeMillis() + cfg.timeOffsetMs
        }
        if (fallbackWarned) {
            fallbackWarned = false
            LogBus.add(ctx, "OK", kind.label + "已就绪，调度恢复使用该时间源")
        }
        return base + cfg.timeOffsetMs
    }

    /** 所选时间源的时区（本地=设备时区；北京时间=UTC+8）。 */
    private fun sourceZone(cfg: Config): TimeZone =
        TapMath.TimeSourceKind.fromId(cfg.timeSource).zone()

    private fun tick() {
        val cfg = config
        val now = effectiveNow(cfg)

        // 1) 基准时刻命中 -> 开始新一轮（第 1 次）
        for (p in cfg.points) {
            if (!p.enabled || p.steps.isEmpty()) continue
            // 时区必须跟随所选时间源：北京时间下的 08:30 应指北京时间 08:30，
            // 而不是设备本地时区的 08:30（否则时间源对「几点触发」不生效）
            val key = TapMath.dueKey(p, now, sourceZone(cfg)) ?: continue
            if (fired.contains(key)) continue
            fired.add(key)
            if (fired.size > 4096) fired.clear()
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
                TapMath.TimeSourceKind.fromId(cfg.timeSource), cfg.timeOffsetMs,
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
                TapMath.TimeSourceKind.fromId(cfg.timeSource), cfg.timeOffsetMs,
            )
        }

        // 3) 维护「下一次触发」展示信息（含进行中的重复）
        var bestAt = Long.MAX_VALUE
        var bestLabel = "-"
        for (p in cfg.points) {
            if (!p.enabled) continue
            val st = repeatStates[p.id]
            val total = TapMath.normalizeRepeatCount(p.repeatCount)
            val t: Long
            val suffix: String
            if (st != null && st.firedCount in 1 until total) {
                t = TapMath.repeatFireAt(st.baseAt, p.repeatIntervalMs, st.firedCount + 1)
                suffix = " 第" + (st.firedCount + 1) + "/" + total + "次"
            } else {
                t = TapMath.nextTriggerAt(p, now, sourceZone(cfg))
                suffix = ""
            }
            if (t < bestAt) {
                bestAt = t
                bestLabel = p.label() + suffix
            }
        }
        nextFireLabel = if (bestAt == Long.MAX_VALUE) "-"
        else bestLabel + " (in " + ((bestAt - now) / 1000L) + "s)"
    }

    companion object {
        fun tzName(): String = TimeZone.getDefault().displayName ?: TimeZone.getDefault().id
    }
}
