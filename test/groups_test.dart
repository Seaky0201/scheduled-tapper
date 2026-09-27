import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/models.dart';

/// 阶段 2（时间点分组）的可观测验证：模型层。
///
/// 覆盖：默认组、创建/重命名、持久化往返、旧数据兼容、删除分组的连带语义、
/// 以及「引用处显示同步」所依赖的同一个数据源（groups 字段）。
void main() {
  group('阶段2 · 分组模型', () {
    test('新配置始终包含默认组「未分组」，且排在最前', () {
      final cfg = TapperConfig();
      expect(cfg.groups.length, 1);
      expect(cfg.groups.first.id, TapperConfig.defaultGroupId);
      expect(cfg.groups.first.name, '未分组');
    });

    test('未分组的时间点归入默认组', () {
      final cfg = TapperConfig(points: [TimePoint(hour: 8), TimePoint(hour: 9)]);
      final grouped = cfg.grouped();
      expect(grouped.length, 1);
      expect(grouped.first.key.id, TapperConfig.defaultGroupId);
      expect(grouped.first.value.length, 2, reason: '未分组时间点应全部归入默认组');
    });

    test('可创建分组并把时间点归类', () {
      final a = PointGroup(name: '早班');
      final b = PointGroup(name: '晚班');
      final p1 = TimePoint(hour: 8, groupId: a.id);
      final p2 = TimePoint(hour: 20, groupId: b.id);
      final p3 = TimePoint(hour: 12);
      final cfg = TapperConfig(points: [p1, p2, p3], groups: [a, b]);

      final grouped = cfg.grouped();
      expect(grouped.length, 3, reason: '默认组 + 早班 + 晚班');
      expect(grouped[0].key.name, '未分组');
      expect(grouped[0].value.length, 1);
      expect(grouped[1].key.name, '早班');
      expect(grouped[1].value.single.id, p1.id);
      expect(grouped[2].key.name, '晚班');
      expect(grouped[2].value.single.id, p2.id);
    });

    test('重命名分组后，所有引用处读到的是同一个新名称', () {
      final g = PointGroup(name: '早班');
      final cfg = TapperConfig(points: [TimePoint(hour: 8, groupId: g.id)], groups: [g]);
      g.name = '清晨班';
      // grouped() 每次实时读取 groups，因此主页面/悬浮窗/选择器（同一数据源）都会看到新名字
      expect(cfg.grouped()[1].key.name, '清晨班');
      expect(cfg.groupById(g.id)!.name, '清晨班');
    });

    test('允许空分组（存在但没有成员）', () {
      final empty = PointGroup(name: '空组');
      final cfg = TapperConfig(points: [TimePoint(hour: 8)], groups: [empty]);
      final grouped = cfg.grouped();
      final e = grouped.firstWhere((x) => x.key.id == empty.id);
      expect(e.value, isEmpty);
    });

    test('删除分组会连同组内时间点一起删除（模型层语义）', () {
      final g = PointGroup(name: '早班');
      final keep = TimePoint(hour: 9);
      final cfg = TapperConfig(points: [TimePoint(hour: 8, groupId: g.id), keep], groups: [g]);
      // 与 main.dart _deleteGroup 相同的操作
      cfg.points.removeWhere((p) => p.groupId == g.id);
      cfg.groups.removeWhere((x) => x.id == g.id);
      expect(cfg.points.length, 1);
      expect(cfg.points.single.id, keep.id);
      expect(cfg.groups.length, 1);
    });

    test('指向不存在分组的时间点回落到默认组（不产生孤儿）', () {
      final cfg = TapperConfig(points: [TimePoint(hour: 8, groupId: '已删除的组')]);
      cfg.normalizePointGroups();
      expect(cfg.points.single.groupId, TapperConfig.defaultGroupId);
      expect(cfg.grouped().first.value.length, 1);
    });

    test('默认组可改名但 id 不变', () {
      final cfg = TapperConfig();
      cfg.groups.first.name = '我的默认组';
      expect(cfg.groupById(TapperConfig.defaultGroupId)!.name, '我的默认组');
      expect(cfg.isDefaultGroup(TapperConfig.defaultGroupId), isTrue);
    });
  });

  group('阶段2 · 持久化往返（刷新/重进不丢失）', () {
    test('分组、名称与时间点归属经 JSON 往返后保持不变', () {
      final g = PointGroup(name: '早班');
      final cfg = TapperConfig(
        points: [TimePoint(hour: 8, groupId: g.id), TimePoint(hour: 9)],
        groups: [g],
      );
      final restored = TapperConfig.fromJsonString(cfg.toJsonString());

      expect(restored.groups.length, 2);
      expect(restored.groups[1].name, '早班');
      expect(restored.groups[1].id, g.id);
      expect(restored.points[0].groupId, g.id, reason: '时间点归属必须持久化');
      expect(restored.points[1].groupId, TapperConfig.defaultGroupId);
    });

    test('JSON 里确实写入了 groups 与 groupId 字段', () {
      final g = PointGroup(name: '早班');
      final cfg = TapperConfig(points: [TimePoint(hour: 8, groupId: g.id)], groups: [g]);
      final o = jsonDecode(cfg.toJsonString()) as Map<String, dynamic>;
      expect(o['groups'], isA<List>());
      expect((o['groups'] as List).length, 2);
      expect((o['points'] as List).first['groupId'], g.id);
    });

    test('旧数据（没有 groups/groupId）自动归入默认组，不报错', () {
      const legacy = '{"version":1,"tapDurationMs":30,"points":['
          '{"id":"p1","hour":8,"minute":0,"second":0,"enabled":true,'
          '"repeatCount":1,"repeatIntervalMs":0,"steps":[]}]}';
      final cfg = TapperConfig.fromJsonString(legacy);
      expect(cfg.points.single.id, 'p1');
      expect(cfg.points.single.groupId, TapperConfig.defaultGroupId);
      expect(cfg.groups.length, 1);
      expect(cfg.groups.single.name, '未分组');
    });

    test('损坏的 JSON 不抛异常', () {
      expect(TapperConfig.fromJsonString('{不是json').points, isEmpty);
      expect(TapperConfig.fromJsonString('{不是json').groups.length, 1);
    });
  });

  group('阶段2 · 跨语言键名一致（防止 Kotlin 端丢弃分组）', () {
    test('TimePoint JSON 包含 groupId', () {
      final o = TimePoint(hour: 8, groupId: 'g1').toJson();
      expect(o['groupId'], 'g1');
    });

    test('PointGroup JSON 键名为 id/name', () {
      final o = PointGroup(id: 'g1', name: '早班').toJson();
      expect(o.keys.toSet(), {'id', 'name'});
    });
  });
}
