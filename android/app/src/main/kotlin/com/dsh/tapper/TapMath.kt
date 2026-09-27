package com.dsh.tapper

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * 纯计算逻辑（不依赖任何 Android API），便于在 JVM 上做单元测试。
 */
object TapMath {

    /**
     * 硬约束 1：悬浮窗取到的坐标必须按「当前屏幕」重新换算，不能直接硬套旧坐标。
     *
     * - 屏幕尺寸与取点时时完全一致 -> 原样返回
     * - 同方向（都竖屏或都横屏）但分辨率不同 -> 按归一化比例缩放
     * - 横竖屏切换 -> 按内容顺时针旋转 90° 映射
     * - 结果一律夹取到当前屏幕范围内
     */
    fun mapToCurrentScreen(x: Int, y: Int, sw: Int, sh: Int, cw: Int, ch: Int): IntArray {
        if (cw <= 0 || ch <= 0) return intArrayOf(x, y)
        if (sw <= 0 || sh <= 0) {
            // 没有取点时的屏幕信息（例如手工输入坐标）：只做边界夹取
            return intArrayOf(x.coerceIn(0, cw - 1), y.coerceIn(0, ch - 1))
        }
        if (sw == cw && sh == ch) return intArrayOf(x, y)

        val nx = x.toDouble() / sw.toDouble()
        val ny = y.toDouble() / sh.toDouble()
        val sameOrientation = (sw <= sh) == (cw <= ch)
        val mx: Double
        val my: Double
        if (sameOrientation) {
            mx = nx * cw
            my = ny * ch
        } else {
            mx = (1.0 - ny) * cw
            my = nx * ch
        }
        return intArrayOf(
            mx.roundToInt().coerceIn(0, cw - 1),
            my.roundToInt().coerceIn(0, ch - 1),
        )
    }

    /**
     * 硬约束 3：基于系统时间（日历）计算下一次触发时刻，而不是 sleep 累加，避免漂移。
     * 返回值是绝对 epoch 毫秒；同一时间点反复调用不会累积误差。
     */
    fun nextTriggerAt(p: TimePoint, fromMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val combos = p.combos()
        if (combos.isEmpty()) {
            // 没有有效组合（理论上不会发生）：回落到单值逻辑，保持既有行为
            val cal = Calendar.getInstance(zone)
            cal.timeInMillis = fromMs
            cal.set(Calendar.MILLISECOND, 0)
            cal.set(Calendar.SECOND, p.second.coerceIn(0, 59))
            cal.set(Calendar.MINUTE, p.minute.coerceIn(0, 59))
            if (!p.hourly) cal.set(Calendar.HOUR_OF_DAY, p.hour.coerceIn(0, 23))
            if (cal.timeInMillis <= fromMs) {
                if (p.hourly) cal.add(Calendar.HOUR_OF_DAY, 1) else cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }
        // 逐个组合算「下一个该时刻」，取最近的一个
        var best = Long.MAX_VALUE
        for ((h, m, s) in combos) {
            val cal = Calendar.getInstance(zone)
            cal.timeInMillis = fromMs
            cal.set(Calendar.MILLISECOND, 0)
            cal.set(Calendar.SECOND, s.coerceIn(0, 59))
            cal.set(Calendar.MINUTE, m.coerceIn(0, 59))
            if (h >= 0) cal.set(Calendar.HOUR_OF_DAY, h.coerceIn(0, 23))
            if (cal.timeInMillis <= fromMs) {
                if (h < 0) cal.add(Calendar.HOUR_OF_DAY, 1) else cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            if (cal.timeInMillis < best) best = cal.timeInMillis
        }
        return best
    }

    /**
     * 判定某个时间点此刻是否命中（时/分/秒对齐）。
     * 返回 null 表示未命中；命中时返回一个「同一天内唯一」的 key，用于防止同一秒内重复触发。
     */
    fun dueKey(p: TimePoint, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String? {
        val cal = Calendar.getInstance(zone)
        cal.timeInMillis = nowMs
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        // 多选：只要命中**任意一个**组合即算到点（组合由 时×分×秒 展开）
        val hit = p.effectiveMinutes().contains(minute) &&
            p.effectiveSeconds().contains(second) &&
            (p.effectiveHours().isEmpty() || p.effectiveHours().contains(hour))
        if (!hit) return null
        val day = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        return p.id + "@" + day + ":" + hour + ":" + minute + ":" + second
    }

    /** 计划时刻与实际时刻的偏差（毫秒，正数=晚了）。 */
    fun driftMs(scheduledAt: Long, actualAt: Long): Long = actualAt - scheduledAt

    // ---------------- 重复触发（新增） ----------------

    // ---------------- 悬浮窗手势判定（新增） ----------------

    /**
     * 悬浮窗单击/拖动/长按的行为判定。
     *
     * 抽成纯函数的原因：Android 的 View 触摸分发无法在 JVM 单测中真实渲染，
     * 因此把「判定」与「执行」分离，判定逻辑可被单测覆盖。
     *
     * 规则（阶段五：单击不再直接进入取点，而是展开设置面板）：
     * - 长按 -> 隐藏悬浮窗（保留原有行为）
     * - 拖动 -> 移动位置
     * - 单击 -> 面板已开则关闭，未开则展开设置面板
     */
    object BubbleGesture {
        const val ACTION_DRAG = "drag"
        const val ACTION_OPEN_PANEL = "open_panel"
        const val ACTION_CLOSE_PANEL = "close_panel"
        const val ACTION_HIDE = "hide"

        fun decide(moved: Boolean, longPressed: Boolean, panelOpen: Boolean): String {
            if (longPressed) return ACTION_HIDE
            if (moved) return ACTION_DRAG
            return if (panelOpen) ACTION_CLOSE_PANEL else ACTION_OPEN_PANEL
        }
    }

    // ---------------- 悬浮窗选点导入（新增） ----------------

    /**
     * 悬浮窗「选点导入」的纯状态模型（不依赖任何 Android API，可在 JVM 单测中覆盖）。
     *
     * 交互语义：
     * - 每个时间点一行，行内复选框切换选中状态
     * - 全部选中时「全选」按钮变为「取消选择」，可一键清空
     * - 取消 / 关闭面板不产生任何写入（调用方不调用 [importTo] 即可）
     */
    object PickerModel {
        /** 面板标题：已选 n / 共 N。 */
        fun title(selected: Int, total: Int): String =
            "选择时间点（已选 " + selected + "/" + total + "）"

        /** 全选按钮文案：全部已选时提示「取消选择」，否则「全选」。 */
        fun toggleAllLabel(total: Int, selected: Int): String =
            if (total > 0 && selected >= total) "取消选择" else "全选"

        /** 全部勾选（用于「全选」）。 */
        fun selectAll(all: List<String>): MutableSet<String> = LinkedHashSet(all)

        /** 全部取消勾选（用于「取消选择」）。 */
        fun clearSelection(): MutableSet<String> = LinkedHashSet()

        /**
         * 把一批步骤按勾选结果落到对应时间点：
         * - 未勾选的时间点**原样返回**，不产生任何改动
         * - 勾选的按「追加到末尾、不去重」写入（与主界面现有行为一致）
         * - 用集合语义，重复勾选同一时间点只写一次
         */
        fun importTo(cfg: Config, selectedIds: Set<String>, steps: List<Step>): Config {
            if (selectedIds.isEmpty() || steps.isEmpty()) return cfg
            return cfg.copy(points = cfg.points.map { p ->
                if (selectedIds.contains(p.id)) p.copy(steps = p.steps + steps) else p
            })
        }
    }

    // ---------------- 悬浮窗实时时间：格式 / 时间源 / 微调（新增） ----------------

    /**
     * 悬浮窗时间显示的纯计算逻辑（不依赖 Android API，可在 JVM 单测中覆盖）。
     *
     * 分工：本对象只负责**格式化与合成**；
     * 真实时间由 TimeSources 提供（本地时钟 / SNTP 对时的北京时间）。
     */
    object ClockFormat {

        /** 微调步长：固定 100ms（与需求一致）。 */
        const val STEP_MS = 100L

        /** 刷新周期：100ms —— 保证百毫秒位真实跳动、不跳号。 */
        const val TICK_MS = 100L

        /**
         * 格式化为「分:秒:百毫秒」，百毫秒一位（0..9）。
         *
         * 例：03:12:4 表示 3 分 12 秒又 4 个百毫秒。
         * 取的是百毫秒位的截断值（ms/100），配合 100ms 刷新周期正好每秒稳定跳 10 次。
         */
        /**
         * 格式化为「时:分:秒:百毫秒」，百毫秒一位（0..9）。
         *
         * 例：22:33:44:5 表示 22 时 33 分 44 秒又 5 个百毫秒。
         *
         * 必须带时区：epoch 是绝对时刻，而「北京时间」要在任何设备上都显示 UTC+8 的挂钟时间，
         * 不能跟着设备时区走（否则出国/改时区后北京时间就显示错了）。
         */
        fun format(epochMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
            val cal = java.util.Calendar.getInstance(zone)
            // 负数（例如微调把时间调到 1970 之前）按 0 处理，避免负号破坏格式
            cal.timeInMillis = if (epochMs < 0) 0L else epochMs
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val minute = cal.get(java.util.Calendar.MINUTE)
            val second = cal.get(java.util.Calendar.SECOND)
            val hundredth = cal.get(java.util.Calendar.MILLISECOND) / 100
            return pad2(hour.toLong()) + ":" + pad2(minute.toLong()) + ":" +
                pad2(second.toLong()) + ":" + hundredth.toString()
        }

        private fun pad2(v: Long): String = if (v < 10) "0" + v else v.toString()

        /**
         * 显示值 = 时间源基准时间 + 微调值。
         * 微调可正可负，单位为毫秒（界面按 100ms 步长增减）。
         */
        fun display(baseEpochMs: Long, offsetMs: Long): Long = baseEpochMs + offsetMs

        /** 微调合法范围（界面只按 100ms 步长增减，正常不会越界）。 */
        fun normalizeOffset(v: Long): Long = v.coerceIn(-86_400_000L, 86_400_000L)
    }

    /**
     * 时间源类型。**每一种都有独立、真实的获取方式**，不做静默降级：
     *
     * - LOCAL：设备系统时钟（System.currentTimeMillis）。就是「本机时间」。
     * - BEIJING：经 SNTP 与授时服务器对时得到的北京时间（UTC+8）。
     *
     *   **P3 更正（原注释有误）**：北京时间并非与设备时钟"相互独立"。
     *   实现是 `System.currentTimeMillis() + 对时偏移`（见 [TimeSources.now]），
     *   两次对时之间使用的是设备时钟的**增量**，因此期间修改设备时钟会带来偏差。
     *   它与本地时间的区别在于**基准来自独立的时间源**（SNTP 服务器，而非本机时钟），
     *   而不是"不受设备时钟影响"。这一点与 [TimeSources] 类注释保持一致。
     *
     * 说明：用户已确认「荣耀时间与本机时间相同则不需要单独的荣耀时间」，
     * 因此不提供第三个假源（避免三源退化成同一个时钟）。
     */
    enum class TimeSourceKind(val id: String, val label: String, val zoneId: String) {
        LOCAL("local", "本地时间", "device"),
        BEIJING("beijing", "北京时间", "GMT+8");

        /** 该时间源的显示时区：本地=设备时区；北京时间=固定 UTC+8。 */
        fun zone(): java.util.TimeZone =
            if (zoneId == "device") java.util.TimeZone.getDefault()
            else java.util.TimeZone.getTimeZone(zoneId)

        companion object {
            fun fromId(id: String?): TimeSourceKind =
                values().firstOrNull { it.id == id } ?: LOCAL
        }
    }

    /**
     * SNTP 对时结果的纯计算部分（收发报文由 Android 侧的 SntpClient 完成）。
     *
     * @param t1 客户端发出请求的本地时刻
     * @param t2 服务器收到请求的时刻（服务器时钟）
     * @param t3 服务器发出响应的时刻（服务器时钟）
     * @param t4 客户端收到响应的本地时刻
     */
    data class SntpResult(val offsetMs: Long, val roundTripMs: Long)

    /**
     * 标准 NTP/SNTP 四时间戳算法：
     *   offset = ((t2 - t1) + (t3 - t4)) / 2
     *   delay  = (t4 - t1) - (t3 - t2)
     * 该算法能抵消绝大部分网络单程延迟带来的误差，是 NTP 的标准做法。
     */
    fun sntpOffset(t1: Long, t2: Long, t3: Long, t4: Long): SntpResult {
        val offset = ((t2 - t1) + (t3 - t4)) / 2L
        val delay = (t4 - t1) - (t3 - t2)
        return SntpResult(offset, delay)
    }

    /** NTP 时间戳（自 1900-01-01 起的秒数）转 epoch 毫秒。 */
    fun ntpToEpochMs(seconds1900: Long, fraction: Long): Long {
        val seventyYears = 2_208_988_800L
        return (seconds1900 - seventyYears) * 1000L + (fraction * 1000L) / 0x100000000L
    }

    /**
     * 解析 SNTP 服务器响应报文（RFC 5905 布局），返回 t2 / t3 / stratum / mode。
     *
     * 报文布局（48 字节）：
     *   0      LI/VN/Mode
     *   1      Stratum
     *   24..31 ORIGINATE（客户端上次发出时间，服务器原样回显 —— 不是 t2！）
     *   32..39 RECEIVE （t2：服务器收到请求的时刻）
     *   40..47 TRANSMIT（t3：服务器发出响应的时刻）
     *
     * 抽成纯函数的原因：字段偏移写错会得到一个量级离谱的偏移量，
     * 这种错误只有靠**对着真实字节做单测**才能可靠拦住（实测曾把 24/32 当成 t2/t3）。
     */
    data class SntpPacket(val t2: Long, val t3: Long, val stratum: Int, val mode: Int)

    fun parseSntpResponse(buf: ByteArray): SntpPacket {
        require(buf.size >= 48) { "SNTP 响应至少 48 字节，实际 " + buf.size }
        return SntpPacket(
            t2 = readNtpTimestamp(buf, 32),
            t3 = readNtpTimestamp(buf, 40),
            stratum = buf[1].toInt() and 0xff,
            mode = buf[0].toInt() and 0x07,
        )
    }

    /** 读取偏移 at 处的 8 字节 NTP 时间戳并转成 epoch 毫秒。 */
    fun readNtpTimestamp(b: ByteArray, at: Int): Long {
        var seconds = 0L
        for (i in 0 until 4) seconds = (seconds shl 8) or (b[at + i].toLong() and 0xffL)
        var fraction = 0L
        for (i in 4 until 8) fraction = (fraction shl 8) or (b[at + i].toLong() and 0xffL)
        return ntpToEpochMs(seconds, fraction)
    }

    /**
     * 按 NTP 时间戳格式把 epoch 毫秒写进字节数组（用于构造测试报文）。
     *
     * 小数位用**四舍五入**而非截断：截断会让 ms=50 写成 0x0CCCCCCC，
     * 回读得到 49ms（实测差 1ms）。真实服务器不会出现这个问题，
     * 但写入侧必须与读取侧互逆，否则测试会假失败。
     */
    fun writeNtpTimestamp(b: ByteArray, at: Int, epochMs: Long) {
        val seventyYears = 2_208_988_800L
        val seconds = epochMs / 1000L + seventyYears
        val ms = epochMs % 1000L
        val fraction = (ms * 0x100000000L + 500L) / 1000L
        for (i in 0 until 4) b[at + i] = ((seconds shr (8 * (3 - i))) and 0xffL).toByte()
        for (i in 0 until 4) b[at + 4 + i] = ((fraction shr (8 * (3 - i))) and 0xffL).toByte()
    }

    /**
     * 时间源 + 微调的**日志口径**（纯函数，可单测）。
     *
     * 日志规则（用户确认）：
     * - 行首时间戳永远是**未微调的系统时间**（LogBus.stamp 直接取 System.currentTimeMillis）
     * - 所选时间源与微调以括号显示在行内
     * - 偏差按「所选时间源、**不含微调**」计算
     *
     * 为什么偏差不含微调也能自洽：
     * 触发时刻在「显示时间轴（时间源+微调）」上判定，而计划时刻与实际时刻
     * 同处这一条时间轴上，偏移量在做差时**互相抵消**，
     * 因此 drift 与「在时间源原轴上计算」完全等价。
     */
    object TimeContext {

        /** 括号里的来源描述，如「北京时间 +100ms」「本地时间」。 */
        fun describe(kind: TimeSourceKind, offsetMs: Long): String =
            if (offsetMs == 0L) kind.label
            else kind.label + (if (offsetMs > 0) " +" + offsetMs + "ms" else " " + offsetMs + "ms")

        /** 偏差说明后缀，明确偏差口径。 */
        fun driftNote(kind: TimeSourceKind, offsetMs: Long): String =
            "（偏差按" + kind.label + "、不含微调计算" +
                (if (offsetMs == 0L) "" else "；微调 " + (if (offsetMs > 0) "+" else "") + offsetMs + "ms 已用于触发时刻") + "）"

        /**
         * 按「所选时间源、不含微调」计算偏差（纯函数，可单测）。
         *
         * @param scheduledEffective 计划时刻，位于「时间源+微调」的有效时间轴（Scheduler 产出）
         * @param offsetMs           手动微调
         * @param deviceNow          当前设备时钟（未微调的系统时间）
         * @param sourceOffsetMs     时间源相对设备时钟的偏移（本地时间恒为 0）
         * @return 偏差毫秒（正数=晚了）
         *
         * 推导：把计划时刻换算回时间源原轴（减掉微调），实际时刻也取时间源原轴
         * （设备时钟 + 时间源偏移），两者相减。因为计划与实际同处一条轴，
         * 微调在相减时被抵消 —— 这正是「偏差不受微调影响」的数学原因。
         */
        fun sourceDrift(scheduledEffective: Long, offsetMs: Long, deviceNow: Long, sourceOffsetMs: Long): Long =
            (deviceNow + sourceOffsetMs) - (scheduledEffective - offsetMs)
    }

    // ---------------- 时间点的多选时刻集合（第 3 项） ----------------

    /**
     * 时间点的「时/分/秒」多选集合模型（纯逻辑，可单测）。
     *
     * 语义（用户确认 A）：一个时间点内可以包含**多个触发时刻**，
     * 由 小时集合 × 分钟集合 × 秒集合 展开成组合；每个组合各触发一次。
     *
     * - 小时用空集表示「每小时」（沿用既有 hour = -1 的语义）
     * - 组合数上限 [MAX_COMBOS]：防止 24×60×60=86400 这类爆炸
     */
    object TimeSets {

        /** 组合数上限。超过则拒绝保存并提示（避免一个时间点产生几万次触发）。 */
        const val MAX_COMBOS = 512

        /** 把整数列表规范化为升序去重的集合。 */
        fun normalize(values: List<Int>, lo: Int, hi: Int): List<Int> =
            values.filter { it in lo..hi }.distinct().sorted()

        /**
         * 展开成所有 (hour, minute, second) 组合。
         * @param hours 空列表 = 每小时（组合里的 hour 记 -1）
         */
        fun expand(hours: List<Int>, minutes: List<Int>, seconds: List<Int>): List<Triple<Int, Int, Int>> {
            val hs = if (hours.isEmpty()) listOf(-1) else normalize(hours, 0, 23)
            val ms = normalize(minutes, 0, 59)
            val ss = normalize(seconds, 0, 59)
            if (ms.isEmpty() || ss.isEmpty()) return emptyList()
            val out = ArrayList<Triple<Int, Int, Int>>(hs.size * ms.size * ss.size)
            for (h in hs) for (m in ms) for (s in ss) out.add(Triple(h, m, s))
            return out
        }

        /** 组合数（用于上限校验）。 */
        fun comboCount(hours: List<Int>, minutes: List<Int>, seconds: List<Int>): Int {
            val h = if (hours.isEmpty()) 1 else normalize(hours, 0, 23).size
            val m = normalize(minutes, 0, 59).size
            val s = normalize(seconds, 0, 59).size
            return h * m * s
        }

        /** 是否超出组合上限。 */
        fun exceedsLimit(hours: List<Int>, minutes: List<Int>, seconds: List<Int>): Boolean =
            comboCount(hours, minutes, seconds) > MAX_COMBOS

        /**
         * 把数值列表格式化成人类可读的「区间压缩」文本。
         * 连续段压成 a-b，离散值逗号分隔；空列表返回 [emptyLabel]。
         */
        fun formatValues(values: List<Int>, pad: Boolean, emptyLabel: String): String {
            if (values.isEmpty()) return emptyLabel
            val v = values.distinct().sorted()
            val parts = ArrayList<String>()
            var i = 0
            while (i < v.size) {
                var j = i
                while (j + 1 < v.size && v[j + 1] == v[j] + 1) j++
                parts.add(
                    if (j - i >= 2) fmt(v[i], pad) + "-" + fmt(v[j], pad)
                    else (i..j).joinToString(",") { fmt(v[it], pad) }
                )
                i = j + 1
            }
            return parts.joinToString(",")
        }

        private fun fmt(v: Int, pad: Boolean): String =
            if (pad) String.format(java.util.Locale.US, "%02d", v) else v.toString()

        /**
         * 时间点的完整可读标签，展示**全部信息**（用户确认 A 的中文分隔风格）。
         *
         * 例：
         *   「08,12时 30分 00秒」    多选小时
         *   「每小时 00-10分 03秒」  小时=每小时，分钟为区间
         *   「全部小时 30分 00秒」   小时全选（0..23）
         *   「08时 30分 00秒」       单选（与旧格式信息量一致）
         */
        fun label(hours: List<Int>, minutes: List<Int>, seconds: List<Int>): String {
            val hs = normalize(hours, 0, 23)
            val hourText = when {
                hs.isEmpty() -> "每小时"
                hs.size == 24 -> "全部小时"
                else -> formatValues(hs, true, "每小时") + "时"
            }
            val minText = formatValues(minutes, true, "00") + "分"
            val secText = formatValues(seconds, true, "00") + "秒"
            return hourText + " " + minText + " " + secText
        }
    }

    // ---------------- 悬浮球状态文字（新增） ----------------

    /**
     * 悬浮球两行状态文字的**唯一**渲染逻辑（纯函数，可在 JVM 单测中覆盖）。
     *
     * 修复背景：原先这段判断只存在于 OverlayService 的 stateReceiver 里，而它读的
     * Intent extra（a11y/running/next）**从来没有任何发送方写入过**，因此恒为默认值
     * （a11y=false -> 一直显示「待机(无无障碍)」、next="-" -> 一直显示「下次 -」）。
     * 现在把判定抽成纯函数，由调用方传入**实时读取**的真实状态。
     *
     * @param pickMode 取点模式进行中（优先级最高）
     * @param running  序列执行中
     * @param a11y     无障碍服务是否已连接（[TapperAccessibilityService.isReady]）
     * @param next     [Scheduler.nextFireInfo] 的原始值；"-" 表示没有启用的时间点
     */
    object BubbleStatus {
        /** 调度器在「没有启用的时间点」时返回的空值哨兵。 */
        const val NONE = "-"

        /** 无可用下一次触发时的真实语义空状态（不显示裸哨兵）。 */
        const val NO_NEXT = "无（没有启用的时间点）"

        const val PICKING = "选取位置中…\n点击屏幕任意位置"
        const val RUNNING = "执行中…"

        fun idleLine(a11y: Boolean): String = if (a11y) "待机" else "待机(无无障碍)"

        fun nextLine(next: String?): String =
            if (next.isNullOrBlank() || next == NONE) NO_NEXT else next

        /** 悬浮球完整文字（两行）。 */
        fun text(pickMode: Boolean, running: Boolean, a11y: Boolean, next: String?): String = when {
            pickMode -> PICKING
            running -> RUNNING
            else -> idleLine(a11y) + "\n下次 " + nextLine(next)
        }
    }

    // ---------------- 悬浮窗「测试时间点」（新增） ----------------

    /**
     * 悬浮窗「测试时间点」的纯状态模型（与 [PickerModel] 同样的「判定 / 执行分离」做法）。
     *
     * 语义（已与用户确认）：
     * - **单选**：选中一个时间点 -> 确认后立即执行**该时间点自己的**步骤序列
     * - 基准时刻 = 当前时刻（与主界面「立即执行一次」一致），因此日志偏差≈0
     * - 未确认 / 取消 -> [blockReason] 非空，调用方据此**不执行任何点击**
     *
     * 复用既有执行入口：确认后由 OverlayService 调用
     * [SequenceRunner.run]，步骤定义仍为 [TimePoint.steps]（[Step]），不新增执行逻辑。
     */
    object TestTarget {

        /** 面板标题：已选 n / 共 N（与 PickerModel.title 同格式，保持观感一致）。 */
        fun title(selected: Int, total: Int): String =
            "测试时间点（已选 " + selected + "/" + total + "）"

        /** 单选切换：点已选中项 = 取消选择，返回 null。 */
        fun select(current: String?, tapped: String): String? =
            if (current == tapped) null else tapped

        /** 按 id 找到时间点；未选 / id 不存在返回 null。 */
        fun resolve(cfg: Config, id: String?): TimePoint? =
            if (id == null) null else cfg.points.firstOrNull { it.id == id }

        /**
         * 是否可执行：返回 null 表示可执行，否则返回**不可执行的原因**（用于日志与界面提示）。
         * 未选择、时间点已不存在、该时间点没有步骤 —— 三种情况都不执行。
         */
        fun blockReason(cfg: Config, id: String?): String? {
            if (id == null) return "还没有选择时间点"
            val p = resolve(cfg, id) ?: return "所选时间点已不存在（可能已被删除）"
            if (p.steps.isEmpty()) return "该时间点还没有配置步骤"
            return null
        }

        /** 执行标签：与主界面「立即执行一次」的「(手动)」后缀保持同风格。 */
        fun runLabel(p: TimePoint): String = p.label() + "(测试)"

        /**
         * 确认执行时**实际传给** [SequenceRunner.run] 的参数。
         * 抽出来的目的：让「确认后到底执行了谁的步骤」成为可单测的纯函数，
         * 而不是埋在依赖 Handler(Looper) 的 Service 里无法在 JVM 上验证。
         */
        data class TestPlan(
            val label: String,
            val steps: List<Step>,
            val tapDurationMs: Long,
        )

        /**
         * 生成执行计划；不可执行时返回 null（调用方据此**不调用** SequenceRunner.run）。
         * 步骤取所选时间点自己的 [TimePoint.steps]，不拼接、不重排、不跨时间点合并。
         */
        fun plan(cfg: Config, id: String?): TestPlan? {
            if (blockReason(cfg, id) != null) return null
            val p = resolve(cfg, id) ?: return null
            return TestPlan(runLabel(p), p.steps, cfg.tapDurationMs)
        }
    }

    /** 重复次数规范化：非法/越界输入一律夹取到 [1, MAX]，绝不抛异常。 */
    fun normalizeRepeatCount(v: Int): Int = v.coerceIn(1, TimePoint.MAX_REPEAT_COUNT)

    /** 重复间隔规范化：负数按 0 处理，超上限夹取到上限。 */
    fun normalizeRepeatInterval(v: Long): Long = v.coerceIn(0L, TimePoint.MAX_REPEAT_INTERVAL_MS)

    /** 第 k 次（1-based）触发相对基准时刻的计划时刻。k=1 即基准时刻本身。 */
    fun repeatFireAt(baseAt: Long, intervalMs: Long, k: Int): Long =
        baseAt + (k - 1).toLong() * normalizeRepeatInterval(intervalMs)

    /**
     * 一次触发判定的结果。
     * @param scheduledAt 该次触发「本应」发生的绝对时刻（用于计算偏差）
     * @param index       本轮中的第几次（1-based）
     * @param total       本轮总共几次
     * @param newState    判定后应保存的新状态
     * @param cycleFinished 本次触发是否为本轮最后一次
     */
    data class RepeatDecision(
        val scheduledAt: Long,
        val index: Int,
        val total: Int,
        val newState: RepeatState,
        val cycleFinished: Boolean,
    )

    /**
     * 根据当前状态判断「此刻是否应执行下一次重复触发」。
     *
     * 约定：
     * - 第 1 次触发由基准时刻命中（dueKey）产生，调用方负责把状态重置为 (baseAt, 1)
     * - 本函数只负责第 2..total 次，按 baseAt + (k-1)*interval 的**绝对时刻**判定，
     *   不依赖 sleep 累加，因此不会随轮询抖动而漂移
     * - 返回 null 表示此刻无需触发
     */
    fun nextRepeatDecision(p: TimePoint, state: RepeatState, nowMs: Long): RepeatDecision? {
        val total = normalizeRepeatCount(p.repeatCount)
        val interval = normalizeRepeatInterval(p.repeatIntervalMs)
        // 未开始（0）或本轮已结束 -> 无事可做
        if (state.firedCount <= 0 || state.firedCount >= total) return null
        val nextIndex = state.firedCount + 1
        val at = repeatFireAt(state.baseAt, interval, nextIndex)
        if (nowMs < at) return null
        return RepeatDecision(
            scheduledAt = at,
            index = nextIndex,
            total = total,
            newState = RepeatState(state.baseAt, nextIndex),
            cycleFinished = nextIndex >= total,
        )
    }
}

/**
 * 某个时间点在「当前一轮重复」中的进度。
 * @param baseAt      本轮基准触发时刻（即第 1 次触发的计划时刻）
 * @param firedCount  本轮已触发次数
 */
data class RepeatState(
    val baseAt: Long = 0L,
    val firedCount: Int = 0,
) {
    val idle: Boolean get() = firedCount <= 0
}
