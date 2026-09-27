package com.dsh.tapper

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 多步点击序列执行器。
 * 每步：先按「当前屏幕」重新换算坐标 -> 注入点击 -> 等待该步到下一步的延时 -> 进入下一步。
 */
object SequenceRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val busy = AtomicBoolean(false)
    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())

    fun isRunning(): Boolean = busy.get()

    /** 中止当前序列（不影响定时调度）。 */
    fun abort(ctx: Context, reason: String) {
        if (job?.isActive == true) {
            job?.cancel()
            LogBus.add(ctx, "WARN", "序列已中止：" + reason)
            TapperPlugin.emitState(ctx)
        }
    }

    /**
     * 按序执行整个序列。
     * @param scheduledAt 计划触发时刻（epoch ms），用于计算定时偏差
     */
    fun run(
        ctx: Context,
        label: String,
        steps: List<Step>,
        tapDurationMs: Long,
        scheduledAt: Long,
        // 日志口径（用户确认）：行首时间戳始终是未微调的系统时间；
        // 计划/实际时刻按**所选时间源**展示，偏差按「时间源、不含微调」计算并注明。
        sourceKind: TapMath.TimeSourceKind = TapMath.TimeSourceKind.LOCAL,
        offsetMs: Long = 0L,
    ) {
        // 说明：本函数只执行「一轮」序列；重复次数由 Scheduler 按绝对时刻多次调用本函数实现。
        if (steps.isEmpty()) {
            LogBus.add(ctx, "WARN", ("时间点 " + label + " 没有配置任何步骤，跳过"))
            return
        }
        if (!busy.compareAndSet(false, true)) {
            LogBus.add(ctx, "WARN", ("已有序列在执行中，跳过本次触发：" + label))
            return
        }
        if (!TapperAccessibilityService.isReady()) {
            busy.set(false)
            LogBus.add(ctx, "ERROR", ("无障碍服务未连接，无法执行 " + label + "；请到系统设置中开启本应用的无障碍服务"))
            return
        }
        TapperPlugin.emitState(ctx)

        job = scope.launch {
            try {
                val deviceStart = System.currentTimeMillis()
                // 时间源相对设备时钟的偏移（本地时间恒为 0；北京时间取自对时结果）
                val srcOffset = TimeSources.now(sourceKind)?.let { it - System.currentTimeMillis() } ?: 0L
                // 计划/实际都换算到**时间源原轴**（不含微调）再展示与求差
                val plannedSource = scheduledAt - offsetMs
                val actualSource = deviceStart + srcOffset
                val drift = TapMath.TimeContext.sourceDrift(
                    scheduledEffective = scheduledAt,
                    offsetMs = offsetMs,
                    deviceNow = deviceStart,
                    sourceOffsetMs = srcOffset,
                )
                val zone = sourceKind.zone()
                LogBus.add(
                    ctx, "RUN",
                    ("触发 " + label + " ｜ 计划 " + LogBus.stamp(plannedSource, zone) +
                        " 实际 " + LogBus.stamp(actualSource, zone) + " 偏差 " + drift + "ms " +
                        TapMath.TimeContext.driftNote(sourceKind, offsetMs) +
                        " ｜ 来源 " + TapMath.TimeContext.describe(sourceKind, offsetMs) +
                        " ｜ 共 " + steps.size + " 步")
                )
                for ((i, s) in steps.withIndex()) {
                    val dm = ctx.resources.displayMetrics
                    val p = TapMath.mapToCurrentScreen(s.x, s.y, s.sw, s.sh, dm.widthPixels, dm.heightPixels)
                    val note = if (s.sw > 0 && s.sh > 0 && (s.sw != dm.widthPixels || s.sh != dm.heightPixels))
                        ("（取点时 " + s.sw + "x" + s.sh + " -> 当前 " + dm.widthPixels + "x" + dm.heightPixels + "，已换算）")
                    else ""
                    val ok = TapperAccessibilityService.instance?.tap(p[0], p[1], tapDurationMs) ?: false
                    LogBus.add(
                        ctx,
                        if (ok) "TAP" else "ERROR",
                        ("步骤 " + (i + 1) + "/" + steps.size + " 点击(" + p[0] + "," + p[1] + ") " +
                            (if (ok) "成功" else "失败") + note)
                    )
                    if (!ok) {
                        LogBus.add(ctx, "ERROR", "该步点击未派发，序列中止")
                        break
                    }
                    val d = s.delayMs.coerceIn(0L, 600000L)
                    if (d > 0) {
                        LogBus.add(ctx, "WAIT", ("等待 " + d + "ms 后执行下一步"))
                        delay(d)
                    }
                }
                LogBus.add(
                    ctx, "DONE",
                    ("序列结束：" + label + " ｜ 耗时 " + (System.currentTimeMillis() - deviceStart) +
                        "ms ｜ 来源 " + TapMath.TimeContext.describe(sourceKind, offsetMs) + "，回到待机")
                )
            } catch (c: kotlinx.coroutines.CancellationException) {
                LogBus.add(ctx, "WARN", ("序列被取消：" + label))
            } catch (t: Throwable) {
                LogBus.add(ctx, "ERROR", "序列异常: " + t)
            } finally {
                busy.set(false)
                TapperPlugin.emitState(ctx)
            }
        }
    }

    fun postToMain(block: () -> Unit) {
        main.post(block)
    }
}
