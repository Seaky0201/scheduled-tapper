package com.dsh.tapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * 时间点分组的单元测试（Kotlin 端）。
 *
 * 关键背景：Kotlin 的 Config 是持久化的拥有者（saveConfig -> Config.fromJson -> Prefs.save）。
 * 如果 Kotlin 端不认识 groups/groupId，Flutter 写进去的分组会在**下一次保存时被静默丢弃**。
 * 因此这里覆盖：分组字段往返、默认组保证、旧数据兼容、以及指向已删除分组的回落。
 */
class GroupsTest {

    private fun point(id: String, groupId: String = "") =
        TimePoint(id = id, hour = 8, minute = 0, second = 0, enabled = true, steps = emptyList(), groupId = groupId)

    @Test
    fun defaultGroupsAlwaysContainsDefaultGroup() {
        val gs = Config.defaultGroups()
        assertEquals(1, gs.size)
        assertEquals(Config.DEFAULT_GROUP_ID, gs[0].id)
        assertEquals(Config.DEFAULT_GROUP_NAME, gs[0].name)
    }

    @Test
    fun groupsSurviveJsonRoundTrip() {
        val cfg = Config(
            points = listOf(point("p1", "g1"), point("p2")),
            groups = listOf(PointGroup("g1", "早班")),
        )
        val restored = Config.fromJson(cfg.toJson().toString())
        assertEquals(2, restored.groups.size)
        assertEquals(Config.DEFAULT_GROUP_ID, restored.groups[0].id)
        assertEquals("早班", restored.groups[1].name)
        assertEquals("g1", restored.points[0].groupId)
        assertEquals(Config.DEFAULT_GROUP_ID, restored.points[1].groupId)
    }

    @Test
    fun jsonContainsGroupsAndGroupId() {
        val cfg = Config(points = listOf(point("p1", "g1")), groups = listOf(PointGroup("g1", "早班")))
        val o = JSONObject(cfg.toJson().toString())
        assertEquals(2, o.getJSONArray("groups").length())
        assertEquals("g1", o.getJSONArray("points").getJSONObject(0).getString("groupId"))
    }

    @Test
    fun legacyJsonWithoutGroupsFallsBackToDefaultGroup() {
        // 旧数据：没有 groups 也没有 groupId
        val legacy = "{\"version\":1,\"tapDurationMs\":30,\"points\":[{\"id\":\"p1\",\"hour\":8," +
            "\"minute\":0,\"second\":0,\"enabled\":true,\"repeatCount\":1,\"repeatIntervalMs\":0,\"steps\":[]}]}"
        val cfg = Config.fromJson(legacy)
        assertEquals(1, cfg.points.size)
        assertEquals(Config.DEFAULT_GROUP_ID, cfg.points[0].groupId)
        assertEquals(1, cfg.groups.size)
        assertEquals(Config.DEFAULT_GROUP_NAME, cfg.groups[0].name)
    }

    @Test
    fun pointPointingToMissingGroupFallsBackToDefault() {
        // 分组被删除后，其时间点不应变成「孤儿」
        val json = "{\"points\":[{\"id\":\"p1\",\"groupId\":\"已删除\"}],\"groups\":[{\"id\":\"g1\",\"name\":\"早班\"}]}"
        val cfg = Config.fromJson(json)
        assertEquals(Config.DEFAULT_GROUP_ID, cfg.points[0].groupId)
    }

    @Test
    fun defaultGroupIsHoistedFirstEvenIfListedLater() {
        val cfg = Config(
            points = emptyList(),
            groups = listOf(PointGroup("g1", "早班"), PointGroup(Config.DEFAULT_GROUP_ID, "未分组")),
        )
        val restored = Config.fromJson(cfg.toJson().toString())
        assertEquals(Config.DEFAULT_GROUP_ID, restored.groups[0].id)
        assertEquals("早班", restored.groups[1].name)
    }

    @Test
    fun renamedGroupKeepsIdAndIsPersisted() {
        val cfg = Config(points = listOf(point("p1", "g1")), groups = listOf(PointGroup("g1", "清晨班")))
        val restored = Config.fromJson(cfg.toJson().toString())
        assertEquals("g1", restored.groups[1].id)
        assertEquals("清晨班", restored.groups[1].name)
        assertEquals("g1", restored.points[0].groupId)
    }

    @Test
    fun brokenJsonDoesNotCrash() {
        val cfg = Config.fromJson("{不是json")
        assertTrue(cfg.points.isEmpty())
        assertEquals(1, cfg.groups.size)
    }
}
