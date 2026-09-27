import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/main.dart';
import 'package:scheduled_tapper/models.dart';

/// 阶段 2/3 的可观测验证（Flutter 侧）：分组 UI、改名同步、持久化写入。
///
/// 说明：悬浮窗内的分组列表由 Android 原生 OverlayService 渲染（WindowManager），
/// 无法在 Flutter/JVM 测试环境渲染，因此这里验证：
///  1. 主页面按分组归类展示、可新建分组、可重命名（改名后界面立即同步）
///  2. 分组与归属确实写进了发给原生的 JSON（Kotlin 端 GroupsTest 验证其往返不丢失）
///  3. 原生写入（悬浮窗改名）后主界面通过 configChanged 同步到同一份数据

const _methodChannel = MethodChannel('scheduled_tapper/control');
const _logsChannel = EventChannel('scheduled_tapper/logs');
const _stateChannel = EventChannel('scheduled_tapper/state');
const _pickChannel = EventChannel('scheduled_tapper/pick');

void _setPhoneSurface(WidgetTester tester, {double w = 360, double h = 900}) {
  tester.view.physicalSize = Size(w * 3, h * 3);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
}

class _PickEvents {
  MockStreamHandlerEventSink? sink;
  void success(Object event) {
    final s = sink;
    if (s == null) throw StateError('HomePage 尚未订阅 pick 事件流');
    s.success(event);
  }
}

_PickEvents _mockNative(String Function() configJson, {List<String>? saved}) {
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final events = _PickEvents();
  messenger.setMockMethodCallHandler(_methodChannel, (call) async {
    switch (call.method) {
      case 'getConfig':
        return configJson();
      case 'saveConfig':
        saved?.add((call.arguments as Map)['json'] as String);
        return true;
      case 'getState':
        return <String, dynamic>{
          'a11y': true, 'a11ySettings': true, 'overlay': true,
          'running': false, 'next': '-', 'tz': 'GMT+8',
        };
      case 'getLogs':
        return '';
      default:
        return true;
    }
  });
  for (final ch in [_logsChannel, _stateChannel]) {
    messenger.setMockStreamHandler(ch, MockStreamHandler.inline(onListen: (args, sink) {}));
  }
  messenger.setMockStreamHandler(_pickChannel, MockStreamHandler.inline(onListen: (args, sink) {
    events.sink = sink;
  }));
  return events;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('阶段2 · 主页面分组', () {
    testWidgets('时间点按分组归类展示，默认组在最前', (tester) async {
      _setPhoneSurface(tester);
      final g = PointGroup(id: 'g1', name: '早班');
      final cfg = TapperConfig(
        points: [
          TimePoint(hour: 8, minute: 0, second: 0, groupId: 'g1'),
          TimePoint(hour: 9, minute: 0, second: 0),
        ],
        groups: [g],
      );
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(find.text('未分组'), findsOneWidget, reason: '默认组应显示');
      expect(find.text('早班'), findsOneWidget, reason: '自定义分组应显示');
      // 分组标题带成员计数（收起状态也可见）
      expect(find.text('(1)'), findsNWidgets(2), reason: '两个分组各 1 个时间点');

      // 本轮改动：分组默认收起，展开后才看得到组内时间点
      expect(find.text('08时 00分 00秒'), findsNothing, reason: '分组默认收起，组内时间点不直接可见');
      await tester.tap(find.text('早班'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();
      expect(find.text('08时 00分 00秒'), findsOneWidget);
      expect(find.text('09时 00分 00秒'), findsOneWidget);
    });

    testWidgets('空分组也会显示', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [], groups: [PointGroup(id: 'g1', name: '空组')]);
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(find.text('空组'), findsOneWidget);
      // 展开「空组」后才显示空态提示（分组默认收起）
      await tester.tap(find.text('空组'));
      await tester.pumpAndSettle();
      expect(find.text('（该分组暂无时间点）'), findsOneWidget);
    });

    testWidgets('可新建分组并持久化到发给原生的 JSON', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(points: [TimePoint(hour: 8)]);
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('新建分组'));
      await tester.pumpAndSettle();
      expect(find.text('新建分组'), findsWidgets);
      await tester.enterText(find.byType(TextField), '夜班');
      await tester.tap(find.text('创建'));
      await tester.pumpAndSettle();

      expect(find.text('夜班'), findsOneWidget, reason: '新分组应立即出现在页面');
      expect(saved, isNotEmpty, reason: '应已保存');
      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final groups = (o['groups'] as List).cast<Map<String, dynamic>>();
      expect(groups.length, 2, reason: '默认组 + 夜班');
      expect(groups[1]['name'], '夜班');
    });

    testWidgets('重命名分组后页面立即显示新名称', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(
        points: [TimePoint(hour: 8, groupId: 'g1')],
        groups: [PointGroup(id: 'g1', name: '早班')],
      );
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(find.text('早班'), findsOneWidget);

      // 打开分组「更多」菜单 -> 重命名分组
      await tester.tap(find.byTooltip('分组操作').at(1));
      await tester.pumpAndSettle();
      await tester.tap(find.text('重命名分组'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField), '清晨班');
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      expect(find.text('清晨班'), findsOneWidget, reason: '改名后主页面立即同步');
      expect(find.text('早班'), findsNothing, reason: '旧名称不应残留');

      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final groups = (o['groups'] as List).cast<Map<String, dynamic>>();
      final renamed = groups.firstWhere((g) => g['id'] == 'g1');
      expect(renamed['name'], '清晨班', reason: '新名称必须持久化');
    });

    testWidgets('默认组不可删除（菜单项禁用）', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [TimePoint(hour: 8)]);
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      await tester.tap(find.byTooltip('分组操作').first);
      await tester.pumpAndSettle();
      expect(find.text('默认分组（不可删除）'), findsOneWidget);
      final item = tester.widget<PopupMenuItem<String>>(
          find.widgetWithText(PopupMenuItem<String>, '默认分组（不可删除）'));
      expect(item.enabled, isFalse, reason: '默认组删除项应禁用');
    });
  });

  group('阶段3 · 原生改分组后主界面同步（同一数据源）', () {
    testWidgets('收到 configChanged 后重新读取分组与名称', (tester) async {
      _setPhoneSurface(tester);
      var json = TapperConfig(
        points: [TimePoint(hour: 8, groupId: 'g1')],
        groups: [PointGroup(id: 'g1', name: '早班')],
      ).toJsonString();
      final pick = _mockNative(() => json);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      expect(find.text('早班'), findsOneWidget);

      // 模拟：悬浮窗侧改名并写回同一份配置，然后广播 configChanged
      json = TapperConfig(
        points: [TimePoint(hour: 8, groupId: 'g1')],
        groups: [PointGroup(id: 'g1', name: '悬浮窗改的名')],
      ).toJsonString();
      pick.success(<String, dynamic>{'configChanged': true});
      await tester.pumpAndSettle();

      expect(find.text('悬浮窗改的名'), findsOneWidget, reason: '主界面应与原生同一份数据');
      expect(find.text('早班'), findsNothing);
    });
  });
}