import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:scheduled_tapper/dialogs.dart';
import 'package:scheduled_tapper/models.dart';

/// 第 3 项（时/分/秒 单选 / 多选 / 全选）的 UI 与模型验证。
class _ResultHolder {
  TimeEditResult? value;
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  /// 对话框结果持有者。
  ///
  /// 注意：不能在打开对话框后立刻 return result —— 那时对话框还没关闭，
  /// 结果必然是 null。必须用持有者，等测试里点完「添加/保存」后再读。
  final holder = _ResultHolder();

  Future<void> openDialog(WidgetTester tester, {TimePoint? existing}) async {
    holder.value = null;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (ctx) => ElevatedButton(
            onPressed: () async {
              holder.value = await showTimeDialog(ctx, existing: existing);
            },
            child: const Text('open'),
          ),
        ),
      ),
    ));
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  group('第3项 · 时间点标签（展示全部信息）', () {
    test('单选：与旧格式信息量一致', () {
      expect(TimePoint(hour: 8, minute: 30, second: 15).label, '08时 30分 15秒');
    });

    test('多选：逗号分隔', () {
      final p = TimePoint(hour: 8, minute: 30, second: 0,
          hours: [8, 12], minutes: [30], seconds: [0]);
      expect(p.label, '08,12时 30分 00秒');
    });

    test('连续值压缩成区间', () {
      // 「每小时」用 hour:-1（既有语义），集合为空
      final p = TimePoint(hour: -1, minute: 0, second: 3,
          minutes: List.generate(11, (i) => i), seconds: [3]);
      expect(p.label, '每小时 00-10分 03秒');
    });

    test('全选小时显示「全部小时」，与「每小时」区分', () {
      final all = TimePoint(hour: 0, minute: 30, second: 0,
          hours: List.generate(24, (i) => i), minutes: [30], seconds: [0]);
      expect(all.label, '全部小时 30分 00秒');
      final hourly = TimePoint(hour: -1, minute: 30, second: 0);
      expect(hourly.label, '每小时 30分 00秒');
    });

    test('多字段同时多选时全部展示，不截断', () {
      final p = TimePoint(hour: 8, minute: 0, second: 0,
          hours: [8, 12], minutes: [0, 30], seconds: [0, 15]);
      expect(p.label, '08,12时 00,30分 00,15秒');
    });
  });

  group('第3项 · 组合展开与上限', () {
    test('单选 = 1 个时刻；多选 = 笛卡尔积', () {
      expect(TimePoint(hour: 8, minute: 30, second: 0).combos.length, 1);
      final p = TimePoint(hour: 8, minute: 30, second: 0,
          hours: [8, 12], minutes: [10, 20], seconds: [0, 30]);
      expect(p.combos.length, 8);
    });

    test('超过上限时标记为超限（24×60×60）', () {
      final p = TimePoint(hour: 0, minute: 0, second: 0,
          hours: List.generate(24, (i) => i),
          minutes: List.generate(60, (i) => i),
          seconds: List.generate(60, (i) => i));
      expect(p.comboCount, 86400);
      expect(p.exceedsComboLimit, isTrue);
    });

    test('集合为空时回落到单值字段（旧数据兼容）', () {
      final p = TimePoint(hour: 8, minute: 30, second: 15);
      expect(p.effectiveHours, [8]);
      expect(p.effectiveMinutes, [30]);
      expect(p.effectiveSeconds, [15]);
      final h = TimePoint(hour: -1, minute: 5, second: 3);
      expect(h.effectiveHours, isEmpty);
    });
  });

  group('第3项 · 集合持久化', () {
    test('集合写入 JSON 并可往返', () {
      final p = TimePoint(hour: 8, minute: 0, second: 0,
          hours: [8, 12], minutes: [0, 30], seconds: [0]);
      final back = TimePoint.fromJson(p.toJson());
      expect(back.hours, [8, 12]);
      expect(back.minutes, [0, 30]);
      expect(back.seconds, [0]);
      expect(back.label, '08,12时 00,30分 00秒');
    });

    test('集合为空时不写入这些键（保持旧 JSON 形态）', () {
      final o = TimePoint(hour: 8, minute: 0, second: 0).toJson();
      expect(o.containsKey('hours'), isFalse);
      expect(o.containsKey('minutes'), isFalse);
      expect(o.containsKey('seconds'), isFalse);
    });
  });

  group('第3项 · 对话框交互', () {
    testWidgets('对话框含时/分/秒三个多选区，各带全选按钮', (tester) async {
      await openDialog(tester);
      expect(find.text('小时'), findsOneWidget);
      expect(find.text('分'), findsOneWidget);
      expect(find.text('秒'), findsOneWidget);
      // 三处「全选」+ 三处「清空」
      expect(find.text('全选'), findsNWidgets(3));
      expect(find.text('清空'), findsNWidgets(3));
      // 分区有稳定 Key
      expect(find.byKey(const Key('timeset-小时')), findsOneWidget);
      expect(find.byKey(const Key('timeset-分')), findsOneWidget);
      expect(find.byKey(const Key('timeset-秒')), findsOneWidget);
      // 小时区有 24 个芯片（用 descendant 限定作用域，避免与分/秒的同名数字冲突）
      expect(find.descendant(
          of: find.byKey(const Key('timeset-小时')), matching: find.text('23')),
          findsOneWidget);
      expect(find.descendant(
          of: find.byKey(const Key('timeset-分')), matching: find.text('59')),
          findsOneWidget);
    });

    testWidgets('点芯片可多选，名称预览实时更新', (tester) async {
      await openDialog(tester);

      // 默认：每小时 + 00分 00秒
      expect(find.textContaining('名称预览：每小时 00分 00秒'), findsOneWidget);

      // 选 08、12 两个小时（默认未选任何小时）；用 Key 限定在小时区
      final hourSection = find.byKey(const Key('timeset-小时'));
      await tester.tap(find.descendant(of: hourSection, matching: find.text('08')));
      await tester.pumpAndSettle();
      await tester.tap(find.descendant(of: hourSection, matching: find.text('12')));
      await tester.pumpAndSettle();
      expect(find.textContaining('08,12时'), findsOneWidget);
      expect(find.textContaining('2 个时刻'), findsOneWidget);

      // 保存后集合被带回
      await tester.tap(find.text('添加'));
      await tester.pumpAndSettle();
      expect(holder.value, isNotNull);
      expect(holder.value!.hours, [8, 12]);
      expect(holder.value!.minutes, [0]);
      expect(holder.value!.seconds, [0]);
    });

    testWidgets('全选小时 = 24 个，标签显示「全部小时」', (tester) async {
      await openDialog(tester);
      // 点小时区的「全选」
      await tester.tap(find.descendant(
          of: find.byKey(const Key('timeset-小时')), matching: find.text('全选')));
      await tester.pumpAndSettle();
      expect(find.textContaining('全部小时'), findsOneWidget);

      await tester.tap(find.text('添加'));
      await tester.pumpAndSettle();
      expect(holder.value!.hours.length, 24);
    });

    testWidgets('分/秒清空后保存会报错且对话框不关闭', (tester) async {
      await openDialog(tester);
      // 清空「分」区
      await tester.tap(find.descendant(
          of: find.byKey(const Key('timeset-分')), matching: find.text('清空')));
      await tester.pumpAndSettle();

      await tester.tap(find.text('添加'));
      await tester.pumpAndSettle();
      expect(find.textContaining('至少要各选一个'), findsOneWidget);
      expect(find.text('新增时间点'), findsOneWidget, reason: '非法时应保持打开');
    });

    testWidgets('修改模式预填已有的多选集合', (tester) async {
      final existing = TimePoint(hour: 8, minute: 30, second: 0,
          hours: [8, 12], minutes: [30], seconds: [0], repeatCount: 3);
      await openDialog(tester, existing: existing);
      expect(find.text('修改时间点'), findsOneWidget);
      expect(find.textContaining('名称预览：08,12时 30分 00秒'), findsOneWidget);
      // 已选计数：小时 2/24，分 1/60，秒 1/60
      expect(find.text('已选 2/24'), findsOneWidget);
      expect(find.text('已选 1/60'), findsNWidgets(2));
    });

    testWidgets('组合超限时保存被拒绝并提示', (tester) async {
      // 预置：小时 24 个 × 分 30 个 × 秒 1 个 = 720 > 512
      final existing = TimePoint(hour: 0, minute: 0, second: 0,
          hours: List.generate(24, (i) => i),
          minutes: List.generate(30, (i) => i),
          seconds: [0]);
      await openDialog(tester, existing: existing);
      await tester.tap(find.text('保存'));
      await tester.pumpAndSettle();
      expect(find.textContaining('组合过多'), findsOneWidget);
      expect(find.text('修改时间点'), findsOneWidget, reason: '超限时应保持打开');
    });
  });
}
