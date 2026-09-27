import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/dialogs.dart';
import 'package:scheduled_tapper/main.dart';
import 'package:scheduled_tapper/models.dart';

/// 阶段三 / 阶段四的可观测验证。
/// 这些是真实的 widget 渲染测试，能测出实际布局宽度与对话框行为，不依赖真机。
/// （Android 特有的悬浮窗显示与无障碍注入仍需真机，已在报告中说明。）

const _methodChannel = MethodChannel('scheduled_tapper/control');
const _logsChannel = EventChannel('scheduled_tapper/logs');
const _stateChannel = EventChannel('scheduled_tapper/state');
const _pickChannel = EventChannel('scheduled_tapper/pick');

/// 手机竖屏逻辑尺寸（360x800 为常见机型）
void _setPhoneSurface(WidgetTester tester, {double w = 360, double h = 800}) {
  tester.view.physicalSize = Size(w * 3, h * 3);
  tester.view.devicePixelRatio = 3.0;
  addTearDown(tester.view.reset);
}

void _mockEventChannels() {
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  for (final ch in [_logsChannel, _stateChannel, _pickChannel]) {
    messenger.setMockStreamHandler(ch, MockStreamHandler.inline(onListen: (args, sink) {}));
  }
}

/// 模拟原生通道，让 HomePage 能在纯 Dart 测试环境启动
void _mockNativeChannels(String configJson, {void Function(String)? onSave}) {
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  messenger.setMockMethodCallHandler(_methodChannel, (call) async {
    switch (call.method) {
      case 'getConfig':
        return configJson;
      case 'saveConfig':
        onSave?.call((call.arguments as Map)['json'] as String);
        return true;
      case 'getState':
        return <String, dynamic>{
          'a11y': true,
          'a11ySettings': true,
          'overlay': true,
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
  _mockEventChannels();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // ================= 阶段三：修改时间点对话框 =================

  group('阶段三 · 时间点编辑对话框', () {
    testWidgets('修改模式：标题为「修改时间点」且预填已有重复参数', (tester) async {
      TimeEditResult? result;
      final existing = TimePoint(hour: 8, minute: 5, second: 30, repeatCount: 4, repeatIntervalMs: 500);

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () async {
                result = await showTimeDialog(ctx, existing: existing);
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.text('修改时间点'), findsOneWidget, reason: '修改模式标题');
      expect(find.text('保存'), findsOneWidget, reason: '按钮应为「保存」');
      expect(find.text('4'), findsOneWidget, reason: '预填 repeatCount=4');
      expect(find.text('500'), findsOneWidget, reason: '预填 repeatIntervalMs=500');

      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect(result, isNotNull);
      expect(result!.repeatCount, 4);
      expect(result!.repeatIntervalMs, 500);
      expect(result!.hour, 8);
      expect(result!.minute, 5);
      expect(result!.second, 30);
    });

    testWidgets('新增模式：标题为「新增时间点」，默认单次', (tester) async {
      TimeEditResult? result;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () async {
                result = await showTimeDialog(ctx);
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      expect(find.text('新增时间点'), findsOneWidget);
      expect(find.text('添加'), findsOneWidget);

      await tester.tap(find.text('添加'));
      await tester.pumpAndSettle();
      expect(result!.repeatCount, 1);
      expect(result!.repeatIntervalMs, 0);
    });

    testWidgets('修改重复次数与间隔后可保存生效', (tester) async {
      TimeEditResult? result;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () async {
                result = await showTimeDialog(ctx, existing: TimePoint(hour: 1, minute: 0, second: 0));
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      final fields = find.byType(TextField);
      expect(fields, findsNWidgets(2), reason: '重复次数 + 重复间隔两个输入框');

      await tester.enterText(fields.at(0), '6');
      await tester.enterText(fields.at(1), '750');
      await tester.pumpAndSettle();

      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      expect(result!.repeatCount, 6);
      expect(result!.repeatIntervalMs, 750);
    });

    testWidgets('边界：清空重复次数点保存 -> 报错且对话框不关闭', (tester) async {
      TimeEditResult? result;
      var closed = false;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () async {
                result = await showTimeDialog(ctx, existing: TimePoint(hour: 1, minute: 0, second: 0));
                closed = true;
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).at(0), '');
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      expect(find.text('修改时间点'), findsOneWidget, reason: '非法输入时对话框应保持打开');
      expect(closed, isFalse, reason: '不应返回结果');
      expect(result, isNull);
      expect(find.textContaining('请输入数字'), findsWidgets);
    });

    testWidgets('边界：输入超大次数被夹取到上限而非崩溃', (tester) async {
      TimeEditResult? result;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: Builder(
            builder: (ctx) => ElevatedButton(
              onPressed: () async {
                result = await showTimeDialog(ctx, existing: TimePoint(hour: 1, minute: 0, second: 0));
              },
              child: const Text('open'),
            ),
          ),
        ),
      ));
      await tester.tap(find.text('open'));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextField).at(0), '999999');
      await tester.enterText(find.byType(TextField).at(1), '999999999');
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      expect(result!.repeatCount, TimePoint.maxRepeatCount);
      expect(result!.repeatIntervalMs, TimePoint.maxRepeatIntervalMs);
    });
  });

  // ================= 阶段四：卡片排版 =================

  group('阶段四 · 时间点卡片展开排版', () {
    // 与修复前完全一致的结构（trailing 里放 3 个 IconButton）
    Widget oldStyleCard(String subtitle) => Card(
          margin: const EdgeInsets.only(bottom: 8),
          child: ExpansionTile(
            initiallyExpanded: true,
            leading: Switch(value: true, onChanged: (_) {}),
            title: Text('每小时 10分 00秒', style: const TextStyle(fontWeight: FontWeight.bold)),
            subtitle: Text(subtitle),
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                IconButton(icon: const Icon(Icons.arrow_upward, size: 18), onPressed: () {}),
                IconButton(icon: const Icon(Icons.arrow_downward, size: 18), onPressed: () {}),
                IconButton(icon: const Icon(Icons.delete, size: 18), onPressed: () {}),
              ],
            ),
            children: const [SizedBox(height: 40)],
          ),
        );

    testWidgets('对比：修复后描述横向宽度明显大于修复前，且不溢出不重叠', (tester) async {
      _setPhoneSurface(tester);
      const subtitle = '5 步 · 已启用 · 5 次 · 间隔 200ms';

      // ---- 修复前的结构 ----
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: ListView(children: [oldStyleCard(subtitle)])),
      ));
      await tester.pumpAndSettle();
      final oldSize = tester.getSize(find.text(subtitle));

      // ---- 修复后的真实实现 ----
      final cfg = TapperConfig(points: [
        TimePoint(
          hour: -1,
          minute: 10,
          second: 0,
          repeatCount: 5,
          repeatIntervalMs: 200,
          steps: List<TapStep>.generate(5, (i) => TapStep(x: 10 * i, y: 20 * i, delayMs: 100)),
        ),
      ]);
      _mockNativeChannels(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      // 本轮改动：分组与组内时间点均默认收起，测量前先展开两者
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('每小时 10分 00秒'));
      await tester.pumpAndSettle();

      final newFinder = find.textContaining('5 步');
      expect(newFinder, findsOneWidget, reason: '应能渲染出时间点卡片描述');
      final newSize = tester.getSize(newFinder);

      // ignore: avoid_print
      print('[排版实测] 修复前 宽度=' + oldSize.width.toStringAsFixed(1) + 'dp 高度=' +
          oldSize.height.toStringAsFixed(1) + 'dp | 修复后 宽度=' +
          newSize.width.toStringAsFixed(1) + 'dp 高度=' + newSize.height.toStringAsFixed(1) + 'dp');

      expect(newSize.width, greaterThan(oldSize.width * 1.5),
          reason: '修复后横向可用宽度应明显大于修复前');
      expect(newSize.height, lessThanOrEqualTo(oldSize.height),
          reason: '修复后描述不应比修复前占用更多行（即不再竖排换行）');

      // 不溢出：文字右边界不得超出卡片
      final cardRect = tester.getRect(find.byType(Card).last);
      final textRect = tester.getRect(newFinder);
      expect(textRect.right, lessThanOrEqualTo(cardRect.right + 0.5), reason: '文字不得溢出卡片');

      // 不重叠：描述不得与同一行的 leading 控件（Switch）重叠。
      // 注：本轮把「修改/分组/上移/下移/删除」迁入长按菜单，卡片内已无操作按钮行，
      // 因此这里改为与仍然存在的 Switch 比较（保持「不重叠」这一断言的初衷）。
      final sw = find.byType(Switch).first;
      final swRect = tester.getRect(sw);
      expect(textRect.overlaps(swRect), isFalse, reason: '描述不得与 leading 控件重叠');
    });

    testWidgets('修复后卡片包含「修改」入口且可打开编辑并预填', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 5, second: 30, repeatCount: 3, repeatIntervalMs: 400),
      ]);
      _mockNativeChannels(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      // 本轮改动：分组与组内时间点默认收起，先展开才能长按卡片
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();

      // 修改入口已迁入长按菜单（本轮改动）
      await tester.longPress(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();
      expect(find.text('修改'), findsOneWidget, reason: '长按菜单应含「修改」');

      await tester.tap(find.text('修改'));
      await tester.pumpAndSettle();

      expect(find.text('修改时间点'), findsOneWidget);
      expect(find.text('3'), findsOneWidget, reason: '应预填现有 repeatCount');
      expect(find.text('400'), findsOneWidget, reason: '应预填现有 repeatIntervalMs');
    });

    testWidgets('步骤行使用弹出菜单，描述获得整行宽度', (tester) async {
      _setPhoneSurface(tester);
      final cfg = TapperConfig(points: [
        TimePoint(hour: 8, minute: 5, second: 30, steps: [
          TapStep(x: 540, y: 1200, delayMs: 300, sw: 1080, sh: 2400),
        ]),
      ]);
      _mockNativeChannels(cfg.toJsonString());
      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      // 本轮改动：分组与组内时间点均默认收起，先展开才能看到步骤行
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();

      expect(find.byType(PopupMenuButton<String>), findsWidgets, reason: '步骤操作收进弹出菜单');

      final stepSubtitle = find.textContaining('到下一步延时');
      expect(stepSubtitle, findsOneWidget);
      final size = tester.getSize(stepSubtitle);
      // ignore: avoid_print
      print('[步骤行实测] 描述宽度=' + size.width.toStringAsFixed(1) + 'dp');
      expect(size.width, greaterThan(180), reason: '步骤描述应获得较宽横向空间');
    });
  });

  // ================= 保存链路 =================

  group('保存链路（UI -> 模型 -> JSON -> 原生）', () {
    testWidgets('修改后保存会向原生发送含新重复参数的配置', (tester) async {
      _setPhoneSurface(tester);
      String? savedJson;
      final cfg = TapperConfig(points: [TimePoint(hour: 8, minute: 5, second: 30)]);
      _mockNativeChannels(cfg.toJsonString(), onSave: (j) => savedJson = j);

      await tester.pumpWidget(const MaterialApp(home: HomePage()));
      await tester.pumpAndSettle();

      // 本轮改动：分组与组内时间点默认收起，先展开
      await tester.tap(find.text('未分组'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();

      await tester.longPress(find.text('08时 05分 30秒'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('修改'));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextField).at(0), '9');
      await tester.enterText(find.byType(TextField).at(1), '250');
      await tester.pumpAndSettle();
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();

      expect(savedJson, isNotNull, reason: '保存应调用原生 saveConfig');
      final decoded = jsonDecode(savedJson!) as Map<String, dynamic>;
      final pts = decoded['points'] as List;
      expect(pts.length, 1);
      expect(pts.first['repeatCount'], 9, reason: '持久化数据应包含新的重复次数');
      expect(pts.first['repeatIntervalMs'], 250, reason: '持久化数据应包含新的重复间隔');
      expect(pts.first['hour'], 8, reason: '其余字段应保持不变');
    });
  });
}