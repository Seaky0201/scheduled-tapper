import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/main.dart';
import 'package:scheduled_tapper/models.dart';

/// 悬浮窗「选点导入」的接入验证（Flutter 侧）。
///
/// Android 的 WindowManager 悬浮窗本身无法在 Flutter/JVM 测试环境渲染（已如实说明），
/// 因此这里验证的是**接入点与联动链路**：
///  1. 主界面存在唤起入口，点击后确实请求原生打开选点面板
///  2. 未授权悬浮窗时不唤起，而是提示并跳转系统设置
///  3. 原生写入配置后通过 configChanged 事件让主界面刷新（界面与持久化不脱节）
///  4. 取消取点后迟到的坐标事件不会误写入旧时间点（取消不产生写入）

const _methodChannel = MethodChannel('scheduled_tapper/control');
const _logsChannel = EventChannel('scheduled_tapper/logs');
const _stateChannel = EventChannel('scheduled_tapper/state');
const _pickChannel = EventChannel('scheduled_tapper/pick');

void _setPhoneSurface(WidgetTester tester, {double w = 360, double h = 800}) {
  tester.view.physicalSize = Size(w * 3, h * 3);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
}

/// pick 事件流的推送句柄：原生事件只能在 HomePage 订阅之后推送，
/// 因此用可变持有者，而不是在订阅前就读取 sink。
class _PickEvents {
  MockStreamHandlerEventSink? sink;
  void success(Object event) {
    final s = sink;
    if (s == null) throw StateError('HomePage 尚未订阅 pick 事件流');
    s.success(event);
  }
}

/// 模拟原生通道；返回 pick 事件流的推送句柄，便于测试中主动推送原生事件。
_PickEvents _mockNative(
  String Function() configJson, {
  List<String>? calls,
  List<String>? saved,
  bool overlay = true,
}) {
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final events = _PickEvents();
  messenger.setMockMethodCallHandler(_methodChannel, (call) async {
    calls?.add(call.method);
    switch (call.method) {
      case 'getConfig':
        return configJson();
      case 'saveConfig':
        saved?.add((call.arguments as Map)['json'] as String);
        return true;
      case 'getState':
        return <String, dynamic>{
          'a11y': true,
          'a11ySettings': true,
          'overlay': overlay,
          'running': false,
          'next': '-',
          'tz': 'GMT+8',
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
  messenger.setMockStreamHandler(
    _pickChannel,
    // 注意：这里必须是块体。箭头函数会把赋值结果（sink 对象）当作返回值回给框架，
    // 导致 StandardMethodCodec 无法编码而抛 PlatformException。
    MockStreamHandler.inline(onListen: (args, sink) {
      events.sink = sink;
    }),
  );
  return events;
}

String _cfgJson(List<TimePoint> points) => TapperConfig(points: points).toJsonString();

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('悬浮窗选点导入 · 唤起入口', () {
    testWidgets('状态卡片存在「选点导入」入口，点击后请求原生打开面板', (tester) async {
      _setPhoneSurface(tester);
      final calls = <String>[];
      _mockNative(() => _cfgJson([TimePoint(hour: 8, minute: 5, second: 30)]), calls: calls);

      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(find.text('选点导入'), findsOneWidget, reason: '主界面应有唤起悬浮窗选点面板的入口');

      await tester.tap(find.text('选点导入'));
      await tester.pumpAndSettle();

      expect(calls, contains('showOverlay'), reason: '应先确保悬浮窗已显示');
      expect(calls, contains('openPicker'), reason: '应请求原生展开选点面板');
    });

    testWidgets('未授权悬浮窗：提示并跳转设置，不打开选点面板', (tester) async {
      _setPhoneSurface(tester);
      final calls = <String>[];
      _mockNative(() => _cfgJson([TimePoint(hour: 8)]), calls: calls, overlay: false);

      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      await tester.tap(find.text('选点导入'));
      await tester.pumpAndSettle();

      expect(find.textContaining('显示在其他应用上层'), findsOneWidget, reason: '应给出授权提示');
      expect(calls, contains('openOverlaySettings'), reason: '应跳转悬浮窗授权设置');
      expect(calls, isNot(contains('openPicker')), reason: '未授权时不应尝试打开面板');
    });
  });

  group('悬浮窗选点导入 · 写入后主界面同步', () {
    testWidgets('收到 configChanged 事件后重新读取配置并刷新界面', (tester) async {
      _setPhoneSurface(tester);
      // 初始：1 个时间点、0 步
      var json = _cfgJson([TimePoint(hour: 8, minute: 5, second: 30)]);
      final pick = _mockNative(() => json);

      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      // 本轮改动：分组与组内时间点默认收起，先展开
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();
      expect(find.textContaining('0 步'), findsOneWidget, reason: '初始应有 0 步的时间点');

      // 模拟：悬浮窗内确认导入 -> 原生写入 Prefs 并广播 configChanged
      json = _cfgJson([
        TimePoint(hour: 8, minute: 5, second: 30, steps: [
          TapStep(x: 540, y: 1200, delayMs: 200, sw: 1080, sh: 2400),
        ]),
      ]);
      pick.success(<String, dynamic>{'configChanged': true});
      await tester.pumpAndSettle();

      expect(find.textContaining('1 步'), findsOneWidget, reason: '主界面应刷新为原生写入后的最新配置');
    });
  });

  group('取点取消不产生写入', () {
    testWidgets('取消取点后迟到的坐标事件不会误写入旧时间点', (tester) async {
      _setPhoneSurface(tester);
      final saved = <String>[];
      final pick = _mockNative(
        () => _cfgJson([TimePoint(hour: 8, minute: 5, second: 30)]),
        saved: saved,
      );

      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      // 本轮改动：分组与组内时间点默认收起，先展开
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();

      final addStep = find.text('悬浮窗取点添加步骤');
      await tester.ensureVisible(addStep);
      await tester.pumpAndSettle();
      await tester.tap(addStep);
      await tester.pumpAndSettle();

      pick.success(<String, dynamic>{'active': true});
      await tester.pumpAndSettle();
      // 用户取消取点
      pick.success(<String, dynamic>{'active': false});
      await tester.pumpAndSettle();
      // 迟到的坐标事件（例如取消瞬间已派发）
      pick.success(<String, dynamic>{'x': 10, 'y': 20, 'sw': 1080, 'sh': 2400});
      await tester.pumpAndSettle();

      expect(saved, isEmpty, reason: '取消取点后不应向原生写入任何配置');
      expect(find.textContaining('0 步'), findsOneWidget, reason: '时间点步数应保持不变');
    });
  });
}