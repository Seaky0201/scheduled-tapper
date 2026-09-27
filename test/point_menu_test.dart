import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/main.dart';
import 'package:scheduled_tapper/models.dart';

/// 本轮改动（长按菜单 + 复制 + 三处默认值）的可观测验证。
///
/// 覆盖验收标准：
///  1. 长按时间点弹出菜单，含修改/分组/上移/下移/删除/复制 共六项
///  2. 复制后同分组下出现一条内容一致的新时间点（enabled 按确认强制关闭）
///  3. 新建时间点后其状态为关闭
///  4. 分组展开后，组内时间点默认收起（且分组自身也默认收起）
///  5. 无异常抛出、原有五操作与分组/新建流程仍可用

const _methodChannel = MethodChannel('scheduled_tapper/control');
const _logsChannel = EventChannel('scheduled_tapper/logs');
const _stateChannel = EventChannel('scheduled_tapper/state');
const _pickChannel = EventChannel('scheduled_tapper/pick');

void _setPhoneSurface(WidgetTester tester, {double w = 360, double h = 900}) {
  tester.view.physicalSize = Size(w * 3, h * 3);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
}

void _mockNative(String Function() configJson, {List<String>? saved}) {
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
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
  for (final ch in [_logsChannel, _stateChannel, _pickChannel]) {
    messenger.setMockStreamHandler(ch, MockStreamHandler.inline(onListen: (args, sink) {}));
  }
}

/// 展开默认组，使组内时间点可见
Future<void> _expandDefaultGroup(WidgetTester tester) async {
  await tester.tap(find.text('未分组'));
  await tester.pumpAndSettle();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('阶段2 · 长按菜单（验收 1）', () {
    testWidgets('长按时间点弹出六项菜单', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 5, second: 30),
        TimePoint(hour: 9, minute: 0, second: 0),
      ]);
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      await _expandDefaultGroup(tester);

      // 未长按时菜单不存在
      expect(find.text('复制'), findsNothing);

      await tester.longPress(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();

      for (final item in ['修改', '分组', '上移', '下移', '复制', '删除']) {
        expect(find.text(item), findsOneWidget, reason: '长按菜单应含「' + item + '」');
      }
    });

    testWidgets('卡片内已无五个直接按钮（不保留新旧两套入口）', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [TimePoint(hour: 8, minute: 5, second: 30)]);
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await _expandDefaultGroup(tester);
      await tester.tap(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();

      // 展开卡片后也不应出现这五个按钮
      for (final item in ['修改', '分组', '上移', '下移', '删除']) {
        expect(find.text(item), findsNothing,
            reason: '「' + item + '」应只在长按菜单里，不再有直接按钮');
      }
      // 但步骤区与执行入口仍在
      expect(find.text('悬浮窗取点添加步骤'), findsOneWidget);
      expect(find.text('立即执行一次'), findsOneWidget);
    });

    testWidgets('首项的上移、末项的下移在菜单中禁用', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 0, second: 0),
        TimePoint(hour: 9, minute: 0, second: 0),
      ]);
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await _expandDefaultGroup(tester);

      // 首项：上移禁用
      await tester.longPress(find.text('08时 00分 00秒'));
      await tester.pumpAndSettle();
      var up = tester.widget<PopupMenuItem<String>>(
          find.widgetWithText(PopupMenuItem<String>, '上移'));
      var down = tester.widget<PopupMenuItem<String>>(
          find.widgetWithText(PopupMenuItem<String>, '下移'));
      expect(up.enabled, isFalse, reason: '首项上移应禁用');
      expect(down.enabled, isTrue);
      await tester.tapAt(const Offset(5, 5));
      await tester.pumpAndSettle();

      // 末项：下移禁用
      await tester.longPress(find.text('09时 00分 00秒'));
      await tester.pumpAndSettle();
      up = tester.widget<PopupMenuItem<String>>(find.widgetWithText(PopupMenuItem<String>, '上移'));
      down = tester.widget<PopupMenuItem<String>>(
          find.widgetWithText(PopupMenuItem<String>, '下移'));
      expect(up.enabled, isTrue);
      expect(down.enabled, isFalse, reason: '末项下移应禁用');
    });

    testWidgets('菜单「上移」沿用原有交换逻辑', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 0, second: 0),
        TimePoint(hour: 9, minute: 0, second: 0),
      ]);
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await _expandDefaultGroup(tester);

      await tester.longPress(find.text('09时 00分 00秒'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('上移'));
      await tester.pumpAndSettle();

      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final pts = (o['points'] as List).cast<Map<String, dynamic>>();
      expect(pts[0]['hour'], 9, reason: '上移后 9 点应在最前');
      expect(pts[1]['hour'], 8);
    });

    testWidgets('菜单「删除」沿用原有删除逻辑', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 0, second: 0),
        TimePoint(hour: 9, minute: 0, second: 0),
      ]);
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await _expandDefaultGroup(tester);

      await tester.longPress(find.text('08时 00分 00秒'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('删除'));
      await tester.pumpAndSettle();

      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final pts = (o['points'] as List).cast<Map<String, dynamic>>();
      expect(pts.length, 1);
      expect(pts[0]['hour'], 9, reason: '8 点应被删除');
    });
  });

  group('阶段3 · 复制（验收 2）', () {
    testWidgets('复制后同分组出现内容一致的新时间点', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(
        points: [
          TimePoint(
            hour: 8, minute: 5, second: 30,
            enabled: true,
            repeatCount: 4,
            repeatIntervalMs: 500,
            groupId: 'g1',
            steps: [TapStep(x: 540, y: 1200, delayMs: 300, sw: 1080, sh: 2400)],
          ),
        ],
        groups: [PointGroup(id: 'g1', name: '早班')],
      );
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await tester.tap(find.text('早班'));
      await tester.pumpAndSettle();

      await tester.longPress(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('复制'));
      await tester.pumpAndSettle();

      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final pts = (o['points'] as List).cast<Map<String, dynamic>>();
      expect(pts.length, 2, reason: '应新增一条');

      final orig = pts[0];
      final copy = pts[1];
      // 内容一致（按确认：enabled 强制为关闭，其余字段一致）
      expect(copy['hour'], orig['hour']);
      expect(copy['minute'], orig['minute']);
      expect(copy['second'], orig['second']);
      expect(copy['repeatCount'], orig['repeatCount']);
      expect(copy['repeatIntervalMs'], orig['repeatIntervalMs']);
      expect(copy['groupId'], 'g1', reason: '不跨分组，应与原项同组');
      expect(copy['enabled'], isFalse, reason: '按确认：复制项默认关闭');
      expect(orig['enabled'], isTrue, reason: '原项不应被改动');
      expect((copy['steps'] as List).length, 1, reason: '步骤应一并复制');
      expect((copy['steps'] as List).first['x'], 540);
      expect((copy['steps'] as List).first['delayMs'], 300);
      expect(copy['id'], isNot(orig['id']), reason: '新项 id 应不同');
    });

    testWidgets('复制项插入在原项之后（同分组内紧邻）', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 0, second: 0),
        TimePoint(hour: 9, minute: 0, second: 0),
        TimePoint(hour: 10, minute: 0, second: 0),
      ]);
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await _expandDefaultGroup(tester);

      await tester.longPress(find.text('09时 00分 00秒'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('复制'));
      await tester.pumpAndSettle();

      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final pts = (o['points'] as List).cast<Map<String, dynamic>>();
      expect(pts.length, 4);
      expect(pts[0]['hour'], 8);
      expect(pts[1]['hour'], 9, reason: '原项仍在第 2 位');
      expect(pts[2]['hour'], 9, reason: '复制项紧跟在原项之后');
      expect(pts[3]['hour'], 10);
    });

    testWidgets('复制不改动原项（原项 enabled 与步骤保持）', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 0, second: 0, enabled: true,
            steps: [TapStep(x: 1, y: 2, delayMs: 3, sw: 4, sh: 5)]),
      ]);
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await _expandDefaultGroup(tester);

      await tester.longPress(find.text('08时 00分 00秒'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('复制'));
      await tester.pumpAndSettle();

      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final pts = (o['points'] as List).cast<Map<String, dynamic>>();
      expect(pts[0]['enabled'], isTrue, reason: '原项 enabled 不应被改动');
      expect((pts[0]['steps'] as List).first['sw'], 4, reason: '原项步骤不应被改动');
    });
  });

  group('阶段4 · 默认值（验收 3、4）', () {
    testWidgets('新建时间点后其状态为关闭（验收 3）', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final cfg = TapperConfig(points: []);
      _mockNative(() => cfg.toJsonString(), saved: saved);
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      await tester.tap(find.text('新增时间点'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('添加'));
      await tester.pumpAndSettle();

      final o = jsonDecode(saved.last) as Map<String, dynamic>;
      final pts = (o['points'] as List).cast<Map<String, dynamic>>();
      expect(pts.length, 1);
      expect(pts.first['enabled'], isFalse, reason: '新建时间点应默认关闭');
    });

    testWidgets('模型层：TimePoint 默认 enabled=false，但旧数据缺字段仍回落 true', (tester) async {
      expect(TimePoint(hour: 8).enabled, isFalse, reason: '新建默认关闭');
      // 旧数据兼容路径不应被这次默认值改动影响
      final legacy = TimePoint.fromJson(<String, dynamic>{'id': 'x', 'hour': 8});
      expect(legacy.enabled, isTrue, reason: '旧数据缺 enabled 字段时应回落为 true（既有兼容行为）');
    });

    testWidgets('分组默认收起（验收 4 前置）', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [TimePoint(hour: 8, minute: 0, second: 0)]);
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(find.text('未分组'), findsOneWidget, reason: '分组标题可见');
      expect(find.text('08时 00分 00秒'), findsNothing, reason: '分组默认收起，组内时间点不可见');
    });

    testWidgets('展开分组后组内时间点为收起状态（验收 4）', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 0, second: 0, steps: [
          TapStep(x: 540, y: 1200, delayMs: 300, sw: 1080, sh: 2400),
        ]),
      ]);
      _mockNative(() => cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      await _expandDefaultGroup(tester);

      // 时间点卡片可见，但展开区内容（步骤行 / 执行按钮）默认不显示
      expect(find.text('08时 00分 00秒'), findsOneWidget, reason: '展开分组后应看到时间点');
      expect(find.textContaining('到下一步延时'), findsNothing,
          reason: '组内时间点应默认收起，步骤行不可见');
      expect(find.text('悬浮窗取点添加步骤'), findsNothing,
          reason: '组内时间点应默认收起，操作区不可见');

      // 点开后内容才出现（确认不是被移除，只是默认收起）
      await tester.tap(find.text('08时 00分 00秒'));
      await tester.pumpAndSettle();
      expect(find.textContaining('到下一步延时'), findsOneWidget);
      expect(find.text('悬浮窗取点添加步骤'), findsOneWidget);
    });
  });
}