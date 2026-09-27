package com.dsh.tapper

import java.util.TimeZone

/**
 * 时间轴：**显示与调度共用**的同一条时间轴。
 *
 * 审查结论（P0 / BUG-1）的根因是同一个 (timeSource, timeOffsetMs) 在原生层存在两份副本：
 *   - OverlayService.timeSourceKind / timeOffsetMs  -> 只喂时钟显示
 *   - Scheduler.config                              -> 喂命中判定 / 倒计时 / 下次触发
 * 面板改时间只更新前者，于是出现「时钟已按新轴走、倒计时与点击时刻仍按旧轴走」。
 *
 * 本类型把两者收敛成**唯一表示**：任何一处要「现在几点」或「用哪个时区」，
 * 都必须先从这里取轴，不允许各自再读一份字段。
 *
 * @param kind     时间源（本地 / 北京时间）
 * @param offsetMs 手动微调（毫秒，可正可负，已夹取）
 */
data class TimeAxis(
    val kind: TapMath.TimeSourceKind,
    val offsetMs: Long,
) {
    /** 该时间源对应的时区（本地=设备时区；北京时间=固定 UTC+8）。 */
    val zone: TimeZone get() = kind.zone()

    /** 基准时刻 -> 有效时刻（显示值 = 调度判定值 = 基准 + 微调）。 */
    fun effective(baseEpochMs: Long): Long = baseEpochMs + offsetMs

    /** 基准时刻 -> 悬浮窗显示的文本。显示与调度共用同一条轴，因此不可能各算各的。 */
    fun format(baseEpochMs: Long): String = TapMath.ClockFormat.format(effective(baseEpochMs), zone)

    companion object {
        /**
         * 从配置解析时间轴。
         * 微调在这里统一夹取：即便盘上数据越界（旧数据/被手改的 JSON），
         * 显示与调度拿到的也一定是同一个合法值。
         */
        fun axisOf(cfg: Config): TimeAxis = TimeAxis(
            kind = TapMath.TimeSourceKind.fromId(cfg.timeSource),
            offsetMs = TapMath.ClockFormat.normalizeOffset(cfg.timeOffsetMs),
        )
    }
}

/**
 * 时间轴变更的**唯一入口**（P0 修复核心）。
 *
 * 审查结论（BUG-1 + BUG-2）：
 * - BUG-1：面板 setTimeSource/setTimeOffset 只改 OverlayService 自己的字段并写盘，
 *   不通知 Scheduler，导致时钟与倒计时/点击时刻分叉，且无时间上限。
 * - BUG-2：面板改完不发 ACTION_CONFIG 广播，Flutter 的 _cfg 仍是旧值，
 *   之后任意一次保存都会把面板的改动静默覆盖掉。
 *
 * 修复思路：把「一次时间设置变更必须发生的全部副作用」收敛到本类，
 * 副作用以函数注入（便于 JVM 单测逐条断言，而不是靠人眼推断接线）。
 * 调用方（OverlayService）只需提供真实的 load/persist/applyToScheduler/notifyUi/requestSync/log。
 *
 * 顺序固定为：读最新配置 -> 写盘 -> 应用到调度 -> 通知 UI -> 按需对时 -> 记日志。
 * 「先写盘再应用」保证任何时刻重启后读到的一定是最新值；
 * 「基于最新持久化配置」保证连续微调是累加而不是互相覆盖。
 */
class TimeAxisController(
    private val load: () -> Config,
    private val persist: (Config) -> Unit,
    private val applyToScheduler: (Config) -> Unit,
    private val notifyUi: () -> Unit,
    private val requestSync: (String) -> Unit,
    private val log: (String) -> Unit,
) {

    /** 切换时间源：写盘 -> 应用到调度 -> 通知 UI -> 切到北京时间时立即对时。 */
    fun setSource(kind: TapMath.TimeSourceKind): Config {
        val cfg = load().copy(timeSource = kind.id)
        persist(cfg)
        applyToScheduler(cfg)
        notifyUi()
        if (kind == TapMath.TimeSourceKind.BEIJING) requestSync("切换时间源")
        log("时间源已切换为：" + kind.label + "（已同步到调度与界面）")
        return cfg
    }

    /**
     * 调整微调值（界面按 100ms 步长增减）。
     * 传入的是**目标值**（调用方自己算 ± STEP_MS），这里统一夹取。
     */
    fun setOffset(targetMs: Long): Config {
        val cfg = load().copy(timeOffsetMs = TapMath.ClockFormat.normalizeOffset(targetMs))
        persist(cfg)
        applyToScheduler(cfg)
        notifyUi()
        log(
            "微调已设为 " + (if (cfg.timeOffsetMs >= 0) "+" else "") + cfg.timeOffsetMs + "ms（相对 " +
                TapMath.TimeSourceKind.fromId(cfg.timeSource).label + "，已同步到调度与界面）"
        )
        return cfg
    }
}
