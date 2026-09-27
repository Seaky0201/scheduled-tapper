import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/models.dart';

/// 时间源与微调的**持久化**验证（Flutter 侧）。
///
/// 说明：显示与刷新逻辑在 Kotlin 侧（ClockTest 27 项覆盖），
/// 这里只验证 Flutter 这一端不会把这两个字段在 JSON 往返中丢掉——
/// 因为 Kotlin 的 saveConfig 会用 Config.fromJson 重新解析 Flutter 发来的 JSON，
/// 任一端漏字段都会导致「重启后微调/时间源丢失」。
void main() {
  group('时间源与微调 · 持久化往返', () {
    test('JSON 里确实写入 timeSource 与 timeOffsetMs', () {
      final cfg = TapperConfig(timeSource: 'beijing', timeOffsetMs: -300);
      final o = jsonDecode(cfg.toJsonString()) as Map<String, dynamic>;
      expect(o['timeSource'], 'beijing');
      expect(o['timeOffsetMs'], -300);
    });

    test('往返后保持不丢（切换时间源 + 微调）', () {
      for (final src in ['local', 'beijing']) {
        for (final off in [0, 100, -100, 500, -500]) {
          final cfg = TapperConfig(timeSource: src, timeOffsetMs: off);
          final back = TapperConfig.fromJsonString(cfg.toJsonString());
          expect(back.timeSource, src, reason: '时间源应保持');
          expect(back.timeOffsetMs, off, reason: '微调应保持');
        }
      }
    });

    test('默认值为 本地时间 + 微调 0', () {
      final cfg = TapperConfig();
      expect(cfg.timeSource, 'local');
      expect(cfg.timeOffsetMs, 0);
    });

    test('旧数据（无这两个字段）回落为 本地时间 + 0，不报错', () {
      const legacy = '{"version":1,"tapDurationMs":30,"points":[]}';
      final cfg = TapperConfig.fromJsonString(legacy);
      expect(cfg.timeSource, 'local');
      expect(cfg.timeOffsetMs, 0);
    });

    test('与其他设置项共存时不互相影响', () {
      final cfg = TapperConfig(
        points: [TimePoint(hour: 8, minute: 5, second: 30)],
        groups: [PointGroup(id: 'g1', name: '早班')],
        timeSource: 'beijing',
        timeOffsetMs: 200,
      );
      final back = TapperConfig.fromJsonString(cfg.toJsonString());
      expect(back.points.single.hour, 8);
      expect(back.groups.length, 2);
      expect(back.groups[1].name, '早班');
      expect(back.timeSource, 'beijing');
      expect(back.timeOffsetMs, 200);
    });
  });
}
