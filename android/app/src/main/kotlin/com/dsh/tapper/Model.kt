package com.dsh.tapper

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一步点击：坐标 + 到下一步的延时（毫秒）。
 * sw/sh = 取点时的屏幕宽高，用于执行时按当前屏幕重新换算（硬约束 1）。
 */
data class Step(
    val x: Int,
    val y: Int,
    val delayMs: Long,
    val sw: Int = 0,
    val sh: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("x", x); put("y", y); put("delayMs", delayMs); put("sw", sw); put("sh", sh)
    }

    companion object {
        fun fromJson(o: JSONObject) = Step(
            x = o.optInt("x", 0),
            y = o.optInt("y", 0),
            delayMs = o.optLong("delayMs", 200L),
            sw = o.optInt("sw", 0),
            sh = o.optInt("sh", 0),
        )
    }
}

/**
 * 一个时间点：hour = -1 表示「每小时」重复。
 *
 * 重复参数（本轮新增）：
 * @param repeatCount      总触发次数（含第一次）。1 = 只触发一次，即旧版行为。
 * @param repeatIntervalMs 相邻两次触发之间的间隔（毫秒）。0 = 首次触发后立即连续触发。
 */
data class TimePoint(
    val id: String,
    val hour: Int,
    val minute: Int,
    val second: Int,
    val enabled: Boolean,
    val steps: List<Step>,
    val repeatCount: Int = 1,
    val repeatIntervalMs: Long = 0L,
    /** 所属分组 id。空字符串 = 未分组（展示时归入默认组）。 */
    val groupId: String = "",
    /**
     * 多选时刻集合（第 3 项）。空列表表示「沿用单值字段」——
     * 这样旧数据/旧调用方完全不受影响，新数据才带集合。
     */
    val hours: List<Int> = emptyList(),
    val minutes: List<Int> = emptyList(),
    val seconds: List<Int> = emptyList(),
) {
    val hourly: Boolean get() = hour < 0

    /** 有效小时集合：集合为空时回落到单值（hour<0 表示每小时 -> 空集）。 */
    fun effectiveHours(): List<Int> =
        if (hours.isNotEmpty()) hours else if (hourly) emptyList() else listOf(hour)

    fun effectiveMinutes(): List<Int> = if (minutes.isNotEmpty()) minutes else listOf(minute)

    fun effectiveSeconds(): List<Int> = if (seconds.isNotEmpty()) seconds else listOf(second)

    /** 本时间点展开后的全部触发时刻。 */
    fun combos(): List<Triple<Int, Int, Int>> =
        TapMath.TimeSets.expand(effectiveHours(), effectiveMinutes(), effectiveSeconds())

    /** 完整可读标签（展示全部信息）。 */
    fun label(): String =
        TapMath.TimeSets.label(effectiveHours(), effectiveMinutes(), effectiveSeconds())

    /** 重复设置的文字描述，用于日志与界面回显。 */
    fun repeatLabel(): String =
        if (repeatCount <= 1) "单次"
        else String.format(java.util.Locale.US, "%d 次 · 间隔 %dms", repeatCount, repeatIntervalMs)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("hour", hour); put("minute", minute); put("second", second)
        put("enabled", enabled)
        put("repeatCount", repeatCount)
        put("repeatIntervalMs", repeatIntervalMs)
        put("groupId", groupId)
        // 多选集合：仅在非空时写入，保持旧数据的 JSON 形态不变
        if (hours.isNotEmpty()) put("hours", JSONArray(hours))
        if (minutes.isNotEmpty()) put("minutes", JSONArray(minutes))
        if (seconds.isNotEmpty()) put("seconds", JSONArray(seconds))
        put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })
    }

    companion object {
        /** 触发次数上限，防止误输入造成长时间占用调度。 */
        const val MAX_REPEAT_COUNT = 999

        /** 重复间隔上限：24 小时。 */
        const val MAX_REPEAT_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** 读取可选的整数数组字段；缺失/非法返回空列表（= 沿用单值字段）。 */
        private fun readIntArray(o: JSONObject, key: String): List<Int> {
            val a = o.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<Int>(a.length())
            for (i in 0 until a.length()) out.add(a.optInt(i))
            return out
        }

        fun fromJson(o: JSONObject): TimePoint {
            val arr = o.optJSONArray("steps") ?: JSONArray()
            val steps = ArrayList<Step>(arr.length())
            for (i in 0 until arr.length()) steps.add(Step.fromJson(arr.getJSONObject(i)))
            return TimePoint(
                id = o.optString("id", UUID.randomUUID().toString()),
                hour = o.optInt("hour", -1),
                minute = o.optInt("minute", 0).coerceIn(0, 59),
                second = o.optInt("second", 0).coerceIn(0, 59),
                enabled = o.optBoolean("enabled", true),
                steps = steps,
                // 兼容旧数据：旧 JSON 没有这两个键，opt* 会返回默认值 -> 等价于旧行为（只触发一次）
                repeatCount = TapMath.normalizeRepeatCount(o.optInt("repeatCount", 1)),
                repeatIntervalMs = TapMath.normalizeRepeatInterval(o.optLong("repeatIntervalMs", 0L)),
                // 兼容旧数据：旧 JSON 没有 groupId -> 空字符串 = 未分组
                groupId = o.optString("groupId", ""),
                // 兼容旧数据：旧 JSON 没有集合字段 -> 空集 -> 沿用单值字段
                hours = readIntArray(o, "hours"),
                minutes = readIntArray(o, "minutes"),
                seconds = readIntArray(o, "seconds"),
            )
        }
    }
}

/**
 * 时间点分组（单层，不允许嵌套）。
 * 未分组的时间点归入 [Config.DEFAULT_GROUP_ID] 对应的默认组「未分组」，该组可改名、不可删除。
 */
data class PointGroup(
    val id: String,
    val name: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
    }

    companion object {
        fun fromJson(o: JSONObject) = PointGroup(
            id = o.optString("id", ""),
            name = o.optString("name", "未命名分组"),
        )
    }
}

data class Config(
    val points: List<TimePoint> = emptyList(),
    val tapDurationMs: Long = 30L,
    /** 分组列表；始终至少包含默认组「未分组」。 */
    val groups: List<PointGroup> = defaultGroups(),
    /** 悬浮窗时间源 id（local / beijing）。默认本地时间。 */
    val timeSource: String = "local",
    /** 手动微调（毫秒），相对所选时间源，可正可负，默认 0。 */
    val timeOffsetMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("tapDurationMs", tapDurationMs)
        put("timeSource", timeSource)
        put("timeOffsetMs", timeOffsetMs)
        // 写盘前同样规范化：保证存储里**始终**含默认组「未分组」且排最前
        // （与 Flutter 端在构造时就规范化的行为对称，避免两端不变量不一致）
        put("groups", JSONArray().apply { normalizeGroups(groups).forEach { put(it.toJson()) } })
        put("points", JSONArray().apply { points.forEach { put(it.toJson()) } })
    }

    companion object {
        const val DEFAULT_GROUP_ID = "default"
        const val DEFAULT_GROUP_NAME = "未分组"

        fun defaultGroups(): List<PointGroup> = listOf(PointGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME))

        /** 保证默认组存在且排最前（与 Flutter 端 TapperConfig._normalizeGroups 行为一致）。 */
        private fun normalizeGroups(src: List<PointGroup>): List<PointGroup> {
            val out = ArrayList<PointGroup>(src.size + 1)
            out.add(src.firstOrNull { it.id == DEFAULT_GROUP_ID }
                ?: PointGroup(DEFAULT_GROUP_ID, DEFAULT_GROUP_NAME))
            for (g in src) if (g.id != DEFAULT_GROUP_ID) out.add(g)
            return out
        }

        fun fromJson(s: String?): Config {
            if (s.isNullOrBlank()) return Config()
            return try {
                val o = JSONObject(s)
                val arr = o.optJSONArray("points") ?: JSONArray()
                val pts = ArrayList<TimePoint>(arr.length())
                for (i in 0 until arr.length()) pts.add(TimePoint.fromJson(arr.getJSONObject(i)))
                // 兼容旧数据：旧 JSON 没有 groups -> 只会有默认组「未分组」
                val gArr = o.optJSONArray("groups") ?: JSONArray()
                val gs = ArrayList<PointGroup>(gArr.length())
                for (i in 0 until gArr.length()) gs.add(PointGroup.fromJson(gArr.getJSONObject(i)))
                val groups = normalizeGroups(gs)
                val ids = groups.map { it.id }.toSet()
                // 指向已删除/不存在分组的时间点回落到默认组，避免出现「孤儿时间点」
                val fixed = pts.map { if (ids.contains(it.groupId)) it else it.copy(groupId = DEFAULT_GROUP_ID) }
                // 兼容旧数据：旧 JSON 没有 timeSource/timeOffsetMs -> 本地时间 + 微调 0
                Config(
                    fixed,
                    o.optLong("tapDurationMs", 30L),
                    groups,
                    o.optString("timeSource", "local"),
                    TapMath.ClockFormat.normalizeOffset(o.optLong("timeOffsetMs", 0L)),
                )
            } catch (e: Exception) {
                Config()
            }
        }
    }
}
