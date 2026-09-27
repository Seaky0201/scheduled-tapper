import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/main.dart';
import 'package:scheduled_tapper/models.dart';

/// 阶段 1（布局改造）的可观测验证：真实渲染 + 实测尺寸对比。
///
/// 改造前：状态卡片把「chip + 下次触发 + 系统时区 + 6 个默认尺寸按钮」全部平铺，
/// 且它位于 ListView **之外**（Column 的固定部分），因此直接压缩时间点列表的可视高度；
/// 日志区固定 220px 恒常展开，位于列表末尾。
/// 改造后：状态详情收进可折叠区（默认收起）、按钮改紧凑样式、日志区可折叠（默认收起）。
///
/// 按用户确认「不用加宽，保持原样」，本次**不做**宽度/多列改动，
/// 因此这里断言的是**纵向可用空间**与**入口可见性/无溢出**，不是宽度。

const _methodChannel = MethodChannel('scheduled_tapper/control');
const _logsChannel = EventChannel('scheduled_tapper/logs');
const _stateChannel = EventChannel('scheduled_tapper/state');
const _pickChannel = EventChannel('scheduled_tapper/pick');

void _setPhoneSurface(WidgetTester tester, {double w = 360, double h = 800}) {
  tester.view.physicalSize = Size(w * 3, h * 3);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
}

void _mockNative(String configJson) {
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  messenger.setMockMethodCallHandler(_methodChannel, (call) async {
    switch (call.method) {
      case 'getConfig':
        return configJson;
      case 'getState':
        return <String, dynamic>{
          'a11y': true,
          'a11ySettings': true,
          'overlay': true,
          'running': false,
          'next': '08:00:00 (in 30s)',
          'tz': 'GMT+8',
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

/// 改造**前**的状态卡片结构（与改动前源码逐项对应），用于同屏对比实测。
Widget _oldTopArea() => Card(
      margin: const EdgeInsets.all(8),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                Chip(
                  avatar: const Icon(Icons.check_circle, color: Colors.green, size: 18),
                  label: const Text('无障碍: 已连接'),
                  visualDensity: VisualDensity.compact,
                ),
                Chip(
                  avatar: const Icon(Icons.check_circle, color: Colors.green, size: 18),
                  label: const Text('悬浮窗: 已授权'),
                  visualDensity: VisualDensity.compact,
                ),
                Chip(
                  avatar: const Icon(Icons.error_outline, color: Colors.orange, size: 18),
                  label: const Text('状态: 待机'),
                  visualDensity: VisualDensity.compact,
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text('下次触发: 08:00:00 (in 30s)', style: TextStyle(fontSize: 13)),
            const Text('系统时区: GMT+8', style: TextStyle(fontSize: 12, color: Colors.black54)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              children: [
                for (final label in ['无障碍设置', '悬浮窗设置', '显示悬浮窗', '选点导入', '展开悬浮窗面板'])
                  OutlinedButton.icon(
                    onPressed: () {},
                    icon: const Icon(Icons.circle, size: 18),
                    label: Text(label),
                  ),
              ],
            ),
          ],
        ),
      ),
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('阶段1 · 时间点上方布局重排', () {
    testWidgets('对比实测：改造后时间点列表起始位置明显上移', (tester) async {
      _setPhoneSurface(tester);
      const marker = '时间点列表起点标记';

      // ---- 改造前的结构：状态卡片平铺 ----
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Column(
            children: [
              _oldTopArea(),
              const Divider(height: 1),
              Expanded(child: ListView(padding: const EdgeInsets.all(8), children: const [Text(marker)])),
            ],
          ),
        ),
      ));
      await tester.pumpAndSettle();
      final oldTop = tester.getTopLeft(find.text(marker)).dy;

      // ---- 改造后的真实实现 ----
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 0, second: 0, steps: [TapStep(x: 1, y: 2)]),
      ]);
      _mockNative(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();
      // 本轮改动：分组默认收起，展开后才有组内时间点可测（对比的是「上方占用」，
      // 分组标题本身的位置即代表时间点列表的起始处，展开后测卡片位置更直观）
      final newTop = tester.getTopLeft(find.text('未分组')).dy;

      // ignore: avoid_print
      print('[布局实测] 时间点起始 y：改造前 ' + oldTop.toStringAsFixed(1) +
          'dp -> 改造后 ' + newTop.toStringAsFixed(1) +
          'dp（上移 ' + (oldTop - newTop).toStringAsFixed(1) + 'dp，约 ' +
          ((oldTop - newTop) / oldTop * 100).toStringAsFixed(0) + '%）');

      expect(newTop, lessThan(oldTop),
          reason: '改造后时间点列表的起始位置应比改造前更靠上（上方占用更少）');
      expect(oldTop - newTop, greaterThan(40),
          reason: '上移幅度应可观测（>40dp）');
    });

    testWidgets('上方入口一个都没少：状态 chip 与全部操作按钮仍可见', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [TimePoint(hour: 8, minute: 0, second: 0)]);
      _mockNative(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(find.textContaining('无障碍: 已连接'), findsOneWidget);
      expect(find.textContaining('悬浮窗: 已授权'), findsOneWidget);
      expect(find.textContaining('状态: 待机'), findsOneWidget);
      expect(find.text('选点导入'), findsOneWidget);
      expect(find.text('设置'), findsOneWidget);
      expect(find.text('新增时间点'), findsOneWidget);

      // 收进「设置」菜单的 4 个入口必须仍然可达（打开菜单逐个断言）
      await tester.tap(find.text('设置'));
      await tester.pumpAndSettle();
      expect(find.text('无障碍设置'), findsOneWidget);
      expect(find.text('悬浮窗设置'), findsOneWidget);
      expect(find.text('显示悬浮窗'), findsOneWidget);
      expect(find.textContaining('悬浮窗面板'), findsOneWidget);
      await tester.tapAt(const Offset(5, 5)); // 关闭菜单
      await tester.pumpAndSettle();
    });

    testWidgets('状态详情默认收起，可展开再收起', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [TimePoint(hour: 8, minute: 0, second: 0)]);
      _mockNative(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(find.textContaining('系统时区'), findsNothing, reason: '默认收起，把空间让给时间点');
      expect(find.textContaining('下次触发'), findsNothing);

      await tester.tap(find.byTooltip('展开状态详情'));
      await tester.pumpAndSettle();
      expect(find.textContaining('系统时区'), findsOneWidget);
      expect(find.textContaining('下次触发'), findsOneWidget);

      await tester.tap(find.byTooltip('收起状态详情'));
      await tester.pumpAndSettle();
      expect(find.textContaining('系统时区'), findsNothing);
    });

    testWidgets('日志区默认收起且入口可见，可展开查看内容', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [TimePoint(hour: 8, minute: 0, second: 0)]);
      _mockNative(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      // 日志标题入口必须在树里（ListView 会懒加载，先滚到底）
      await tester.scrollUntilVisible(find.textContaining('运行日志'), 200,
          scrollable: find.byType(Scrollable).first);
      await tester.pumpAndSettle();
      expect(find.textContaining('运行日志'), findsOneWidget, reason: '日志标题入口必须仍可见');
      expect(find.text('(暂无日志)'), findsNothing, reason: '默认收起，不占 220px');

      await tester.tap(find.textContaining('运行日志'));
      await tester.pumpAndSettle();
      expect(find.text('(暂无日志)'), findsOneWidget, reason: '展开后内容可见');
    });

    testWidgets('12 个时间点：可滚动、无渲染异常', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(
        points: List.generate(12, (i) => TimePoint(hour: i % 24, minute: 0, second: 0)),
      );
      _mockNative(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      expect(tester.takeException(), isNull, reason: '不应有布局溢出/渲染异常');

      // 本轮改动：分组默认收起，先展开默认组
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();

      // 标签格式已改为「08时 00分 00秒」，直接取模型的 label 生成期望值，
      // 避免测试里再手写一份格式（否则格式一变测试就假失败）
      final visible = <String>[];
      for (var h = 0; h < 12; h++) {
        final t = TimePoint(hour: h, minute: 0, second: 0).label;
        if (find.text(t).evaluate().isNotEmpty) visible.add(t);
      }
      // ignore: avoid_print
      print('[布局实测] 800dp 屏首屏可见时间点数量 = ' + visible.length.toString() + ' / 12');
      expect(visible.length, greaterThanOrEqualTo(2),
          reason: '首屏应能看到多个时间点，且无溢出异常');
    });
  });
}