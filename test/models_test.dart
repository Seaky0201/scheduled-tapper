import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/models.dart';

void main() {
  group('阶段二 · 重复字段', () {
    test('重复次数非法输入被夹取，不抛异常', () {
      expect(TimePoint.normalizeRepeatCount(0), 1);
      expect(TimePoint.normalizeRepeatCount(-7), 1);
      expect(TimePoint.normalizeRepeatCount(1), 1);
      expect(TimePoint.normalizeRepeatCount(5), 5);
      expect(TimePoint.normalizeRepeatCount(99999), TimePoint.maxRepeatCount);
    });

    test('重复间隔非法输入被夹取', () {
      expect(TimePoint.normalizeRepeatInterval(-1), 0);
      expect(TimePoint.normalizeRepeatInterval(0), 0);
      expect(TimePoint.normalizeRepeatInterval(300), 300);
      expect(TimePoint.normalizeRepeatInterval(1 << 30), TimePoint.maxRepeatIntervalMs);
    });

    test('构造时即规范化，越界值不会进入模型', () {
      final p = TimePoint(repeatCount: -3, repeatIntervalMs: -50);
      expect(p.repeatCount, 1);
      expect(p.repeatIntervalMs, 0);
    });

    test('repeatLabel 与 totalSpanMs 正确', () {
      expect(TimePoint(repeatCount: 1).repeatLabel, '单次');
      final p = TimePoint(repeatCount: 5, repeatIntervalMs: 200);
      expect(p.repeatLabel, '5 次 · 间隔 200ms');
      expect(p.totalSpanMs, 800);
    });

    test('新字段 JSON 往返', () {
      final c = TapperConfig(points: [
        TimePoint(hour: 8, minute: 5, second: 30, repeatCount: 7, repeatIntervalMs: 1500),
      ]);
      final back = TapperConfig.fromJsonString(c.toJsonString());
      expect(back.points.first.repeatCount, 7);
      expect(back.points.first.repeatIntervalMs, 1500);
    });
  });

  group('阶段二 · 旧数据兼容', () {
    test('旧 JSON 缺少重复字段时回落到「单次」', () {
      const legacy = '{"version":1,"tapDurationMs":30,"points":['
          '{"id":"old1","hour":8,"minute":5,"second":30,"enabled":true,'
          '"steps":[{"x":10,"y":20,"delayMs":300,"sw":1080,"sh":2400}]}]}';
      final cfg = TapperConfig.fromJsonString(legacy);
      expect(cfg.points.length, 1);
      final p = cfg.points.first;
      expect(p.id, 'old1');
      expect(p.hour, 8);
      expect(p.steps.first.delayMs, 300);
      expect(p.repeatCount, 1, reason: '缺失字段应回落为旧行为：只触发一次');
      expect(p.repeatIntervalMs, 0);
      expect(p.repeatLabel, '单次');
    });

    test('旧数据读取后重新保存，新字段值仍等价于旧行为', () {
      const legacy = '{"points":[{"id":"o2","hour":-1,"minute":1,"second":2,"enabled":true,"steps":[]}]}';
      final a = TapperConfig.fromJsonString(legacy);
      final b = TapperConfig.fromJsonString(a.toJsonString());
      expect(b.points.first.repeatCount, 1);
      expect(b.points.first.repeatIntervalMs, 0);
    });

    test('旧数据里的非法重复值被夹取而不是崩溃', () {
      const bad = '{"points":[{"id":"b1","hour":-1,"minute":0,"second":0,"enabled":true,'
          '"steps":[],"repeatCount":-9,"repeatIntervalMs":-100}]}';
      final cfg = TapperConfig.fromJsonString(bad);
      expect(cfg.points.first.repeatCount, 1);
      expect(cfg.points.first.repeatIntervalMs, 0);
    });

    test('损坏的 JSON 不抛异常', () {
      expect(TapperConfig.fromJsonString('{not json').points, isEmpty);
      expect(TapperConfig.fromJsonString(null).points, isEmpty);
    });
  });

  group('阶段三 · 修改时间点', () {
    test('就地修改后序列化结果同步更新（模拟 _editPoint）', () {
      final cfg = TapperConfig(points: [TimePoint(hour: 8, minute: 5, second: 30)]);
      final p = cfg.points.first;
      final idBefore = p.id;
      // 模拟 _editPoint 的就地赋值
      p.hour = -1;
      p.minute = 10;
      p.second = 0;
      p.repeatCount = 4;
      p.repeatIntervalMs = 500;

      final json = cfg.toJsonString();
      final back = TapperConfig.fromJsonString(json);
      expect(back.points.first.id, idBefore, reason: '修改不应改变 id');
      expect(back.points.first.hour, -1);
      expect(back.points.first.minute, 10);
      expect(back.points.first.second, 0);
      expect(back.points.first.repeatCount, 4);
      expect(back.points.first.repeatIntervalMs, 500);
      expect(back.points.first.label, '每小时 10分 00秒');
    });

    test('修改保留已有步骤', () {
      final cfg = TapperConfig(points: [
        TimePoint(hour: 1, minute: 0, second: 0, steps: [TapStep(x: 11, y: 22, delayMs: 33)]),
      ]);
      final p = cfg.points.first;
      p.hour = 2;
      p.repeatCount = 3;
      final back = TapperConfig.fromJsonString(cfg.toJsonString());
      expect(back.points.first.steps.length, 1);
      expect(back.points.first.steps.first.x, 11);
      expect(back.points.first.steps.first.delayMs, 33);
    });
  });

  group('JSON 键名与 Kotlin 端一致（防止跨语言失配）', () {
    test('TimePoint 序列化包含 repeatCount / repeatIntervalMs', () {
      final p = TimePoint(hour: 1, minute: 2, second: 3, repeatCount: 2, repeatIntervalMs: 400);
      final m = jsonDecode(jsonEncode(p.toJson())) as Map<String, dynamic>;
      expect(m.containsKey('repeatCount'), isTrue);
      expect(m.containsKey('repeatIntervalMs'), isTrue);
      expect(m['repeatCount'], 2);
      expect(m['repeatIntervalMs'], 400);
      // 旧字段仍在，保证原生端 Step.fromJson 可读
      expect(m.containsKey('steps'), isTrue);
      expect(m.containsKey('enabled'), isTrue);
    });
  });
}