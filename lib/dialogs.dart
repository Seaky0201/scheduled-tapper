import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'models.dart';

/// 时间点「新增 / 修改」对话框的返回值。
class TimeEditResult {
  final int hour;
  final int minute;
  final int second;
  final int repeatCount;
  final int repeatIntervalMs;

  /// 多选集合（第 3 项）。空列表 = 沿用单值字段。
  final List<int> hours;
  final List<int> minutes;
  final List<int> seconds;

  TimeEditResult({
    required this.hour,
    required this.minute,
    required this.second,
    required this.repeatCount,
    required this.repeatIntervalMs,
    this.hours = const <int>[],
    this.minutes = const <int>[],
    this.seconds = const <int>[],
  });

  /// 展开后的组合数（用于上限校验与预览）。
  int get comboCount {
    final h = hours.isEmpty ? 1 : hours.length;
    final m = minutes.isEmpty ? 1 : minutes.length;
    final s = seconds.isEmpty ? 1 : seconds.length;
    return h * m * s;
  }
}

/// 编辑「到下一步的延时（毫秒）」
Future<int?> showDelayDialog(BuildContext context, int current) {
  final c = TextEditingController(text: current.toString());
  return showDialog<int>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('到下一步的延时（毫秒）'),
      content: TextField(
        controller: c,
        keyboardType: TextInputType.number,
        inputFormatters: [FilteringTextInputFormatter.digitsOnly],
        autofocus: true,
        decoration: const InputDecoration(hintText: '例如 300'),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
        FilledButton(
          onPressed: () {
            final v = int.tryParse(c.text.trim());
            Navigator.pop(ctx, v ?? current);
          },
          child: const Text('确定'),
        ),
      ],
    ),
  );
}

/// 新建 / 重命名分组。返回新名称；取消返回 null。
///
/// [existing] 为 null 时是「新建」，否则是「重命名」（预填当前名称）。
Future<String?> showGroupNameDialog(BuildContext context, {String? existing}) {
  final isEdit = existing != null;
  final c = TextEditingController(text: existing ?? '');
  String? error;
  return showDialog<String>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setLocal) => AlertDialog(
        title: Text(isEdit ? '重命名分组' : '新建分组'),
        content: TextField(
          controller: c,
          autofocus: true,
          decoration: InputDecoration(
            labelText: '分组名称',
            hintText: '例如 早班',
            errorText: error,
          ),
          onChanged: (_) => setLocal(() => error = null),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () {
              final v = c.text.trim();
              if (v.isEmpty) {
                setLocal(() => error = '名称不能为空');
                return;
              }
              Navigator.pop(ctx, v);
            },
            child: Text(isEdit ? '保存' : '创建'),
          ),
        ],
      ),
    ),
  );
}

/// 删除分组前的二次确认。返回 true 表示确认删除。
///
/// 按已确认的语义：**删除分组会连同组内时间点一起删除**，因此这里必须明确告知数量。
Future<bool?> showDeleteGroupDialog(BuildContext context, String name, int pointCount) {
  return showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('删除分组'),
      content: Text(
        pointCount == 0
            ? '确定删除分组「' + name + '」吗？该分组下没有时间点。'
            : '确定删除分组「' + name + '」吗？\n该分组下的 ' +
                pointCount.toString() +
                ' 个时间点也会被一起删除，且无法恢复。',
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('取消')),
        FilledButton(
          style: FilledButton.styleFrom(backgroundColor: Colors.red),
          onPressed: () => Navigator.pop(ctx, true),
          child: const Text('删除'),
        ),
      ],
    ),
  );
}

/// 把时间点移动到指定分组（下拉选择）。
Future<String?> showMoveGroupDialog(
    BuildContext context, String currentGroupId, List<PointGroup> groups) {
  var sel = currentGroupId;
  return showDialog<String>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setLocal) => AlertDialog(
        title: const Text('移动到分组'),
        content: DropdownButton<String>(
          isExpanded: true,
          value: groups.any((g) => g.id == sel) ? sel : groups.first.id,
          items: groups
              .map((g) => DropdownMenuItem(value: g.id, child: Text(g.name)))
              .toList(),
          onChanged: (v) => setLocal(() => sel = v!),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(ctx, sel),
            child: const Text('确定'),
          ),
        ],
      ),
    ),
  );
}

/// 时/分/秒的多选区：芯片网格 + 全选 / 清空。
///
/// 交互（第 3 项）：
/// - 点单个芯片 = 单选/切换多选（选中态可见可改）
/// - 「全选」一键选中全部取值；「清空」一键清空
/// - 小时清空 = 每小时（沿用既有语义，界面有明确提示）
Widget _multiSelectSection({
  required BuildContext ctx,
  required void Function(VoidCallback) setLocal,
  required String title,
  required String hint,
  required List<int> values,
  required List<int> selected,
  required bool pad,
  required void Function(List<int>) onChanged,
}) {
  final sel = selected.toSet();
  final allSelected = sel.length == values.length;
  return Padding(
    // 稳定 Key：便于测试与后续定位（不依赖文案）
    key: Key('timeset-' + title),
    padding: const EdgeInsets.only(bottom: 6),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Row(
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 13)),
            const SizedBox(width: 6),
            Text('已选 ' + sel.length.toString() + '/' + values.length.toString(),
                style: const TextStyle(fontSize: 11, color: Colors.black54)),
            const Spacer(),
            // 「全选」与「清空」都常驻显示：比随状态切换文案更直观，
            // 也与需求里的「单选、多选、全选」三种操作一一对应。
            TextButton(
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 6),
                minimumSize: const Size(0, 28),
              ),
              onPressed: allSelected
                  ? null
                  : () => setLocal(() => onChanged(List<int>.from(values))),
              child: const Text('全选', style: TextStyle(fontSize: 12)),
            ),
            TextButton(
              style: TextButton.styleFrom(
                visualDensity: VisualDensity.compact,
                padding: const EdgeInsets.symmetric(horizontal: 6),
                minimumSize: const Size(0, 28),
              ),
              onPressed: sel.isEmpty ? null : () => setLocal(() => onChanged(<int>[])),
              child: const Text('清空', style: TextStyle(fontSize: 12)),
            ),
          ],
        ),
        if (hint.isNotEmpty)
          Text(hint, style: const TextStyle(fontSize: 10, color: Colors.black45)),
        const SizedBox(height: 2),
        Wrap(
          spacing: 3,
          runSpacing: 3,
          children: values.map((v) {
            final on = sel.contains(v);
            return InkWell(
              onTap: () => setLocal(() {
                final next = List<int>.from(selected);
                if (on) {
                  next.remove(v);
                } else {
                  next.add(v);
                }
                next.sort();
                onChanged(next);
              }),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 3),
                decoration: BoxDecoration(
                  color: on ? const Color(0xFF1976D2) : const Color(0xFFEEEEEE),
                  borderRadius: BorderRadius.circular(4),
                ),
                child: Text(
                  pad ? v.toString().padLeft(2, '0') : v.toString(),
                  style: TextStyle(
                      fontSize: 11,
                      color: on ? Colors.white : Colors.black87,
                      fontWeight: on ? FontWeight.bold : FontWeight.normal),
                ),
              ),
            );
          }).toList(),
        ),
      ],
    ),
  );
}

/// 手动输入坐标（不依赖悬浮窗取点的兜底方式）
Future<List<int>?> showCoordDialog(BuildContext context, int x, int y) {
  final cx = TextEditingController(text: x.toString());
  final cy = TextEditingController(text: y.toString());
  return showDialog<List<int>>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: const Text('手动输入坐标'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: cx,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9-]'))],
            decoration: const InputDecoration(labelText: 'X'),
          ),
          TextField(
            controller: cy,
            keyboardType: TextInputType.number,
            inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[0-9-]'))],
            decoration: const InputDecoration(labelText: 'Y'),
          ),
        ],
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
        FilledButton(
          onPressed: () {
            final a = int.tryParse(cx.text.trim());
            final b = int.tryParse(cy.text.trim());
            if (a == null || b == null) return;
            Navigator.pop(ctx, [a, b]);
          },
          child: const Text('确定'),
        ),
      ],
    ),
  );
}

/// 新增 / 修改时间点：选择小时（含「每小时」重复）与分、秒，并设置重复次数与重复间隔。
///
/// [existing] 为 null 时是「新增」，否则是「修改」（预填当前值）。
Future<TimeEditResult?> showTimeDialog(BuildContext context, {TimePoint? existing}) {
  final isEdit = existing != null;

  // 多选集合（第 3 项）。新增时默认「每小时 + 00 分 00 秒」；
  // 修改时沿用该时间点已有的集合。单值字段在保存时由集合推导。
  List<int> selHours = List<int>.from(existing?.effectiveHours ?? const <int>[]);
  List<int> selMinutes = List<int>.from(existing?.effectiveMinutes ?? const <int>[0]);
  List<int> selSeconds = List<int>.from(existing?.effectiveSeconds ?? const <int>[0]);

  final countCtl = TextEditingController(text: (existing?.repeatCount ?? 1).toString());
  final intervalCtl = TextEditingController(text: (existing?.repeatIntervalMs ?? 0).toString());
  String? countError;
  String? intervalError;
  String? comboError;

  return showDialog<TimeEditResult>(
    context: context,
    builder: (ctx) => StatefulBuilder(
      builder: (ctx, setLocal) {
        // 实时预览：展开后的时刻数
        final comboCountPreview =
            (selHours.isEmpty ? 1 : selHours.length) *
                (selMinutes.isEmpty ? 1 : selMinutes.length) *
                (selSeconds.isEmpty ? 1 : selSeconds.length);
        // 实时预览：当前输入的次数与间隔
        final c = int.tryParse(countCtl.text.trim());
        final iv = int.tryParse(intervalCtl.text.trim());
        final previewCount = TimePoint.normalizeRepeatCount(c ?? 1);
        final previewInterval = TimePoint.normalizeRepeatInterval(iv ?? 0);
        final spanMs = (previewCount - 1) * previewInterval;

        return AlertDialog(
          title: Text(isEdit ? '修改时间点' : '新增时间点'),
          content: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // 第 3 项：时/分/秒 支持 单选 / 多选 / 全选。
                // 选中集合决定该时间点包含哪些触发时刻（组合展开）。
                _multiSelectSection(
                  ctx: ctx,
                  setLocal: setLocal,
                  title: '小时',
                  hint: '不选任何小时 = 每小时触发',
                  values: List<int>.generate(24, (i) => i),
                  selected: selHours,
                  pad: true,
                  onChanged: (v) => selHours = v,
                ),
                _multiSelectSection(
                  ctx: ctx,
                  setLocal: setLocal,
                  title: '分',
                  hint: '',
                  values: List<int>.generate(60, (i) => i),
                  selected: selMinutes,
                  pad: true,
                  onChanged: (v) => selMinutes = v,
                ),
                _multiSelectSection(
                  ctx: ctx,
                  setLocal: setLocal,
                  title: '秒',
                  hint: '',
                  values: List<int>.generate(60, (i) => i),
                  selected: selSeconds,
                  pad: true,
                  onChanged: (v) => selSeconds = v,
                ),
                if (comboError != null)
                  Text(comboError!,
                      style: const TextStyle(fontSize: 11, color: Colors.red)),
                // 实时预览：完整标签（展示全部信息）+ 展开后的时刻数
                Text(
                  '名称预览：' +
                      TimeSets.label(
                        selHours,
                        selMinutes.isEmpty ? <int>[0] : selMinutes,
                        selSeconds.isEmpty ? <int>[0] : selSeconds,
                      ) +
                      (comboCountPreview > 1 ? '（' + comboCountPreview.toString() + ' 个时刻）' : ''),
                  style: const TextStyle(fontSize: 12, color: Colors.black87),
                ),
                const Divider(height: 20),
                TextField(
                  controller: countCtl,
                  keyboardType: TextInputType.number,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  decoration: InputDecoration(
                    labelText: '重复次数',
                    helperText: '1 = 只触发一次（1~' + TimePoint.maxRepeatCount.toString() + '）',
                    errorText: countError,
                  ),
                  onChanged: (_) => setLocal(() {
                    countError = null;
                  }),
                ),
                const SizedBox(height: 4),
                TextField(
                  controller: intervalCtl,
                  keyboardType: TextInputType.number,
                  inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                  decoration: InputDecoration(
                    labelText: '重复间隔（毫秒）',
                    helperText: '相邻两次触发之间的等待，0 = 立即连续执行',
                    errorText: intervalError,
                  ),
                  onChanged: (_) => setLocal(() {
                    intervalError = null;
                  }),
                ),
                const SizedBox(height: 10),
                Text(
                  previewCount <= 1
                      ? '预览：到点后只执行 1 次'
                      : '预览：共执行 ' +
                          previewCount.toString() +
                          ' 次，每次间隔 ' +
                          previewInterval.toString() +
                          'ms，整轮约 ' +
                          (spanMs / 1000).toStringAsFixed(1) +
                          ' 秒',
                  style: const TextStyle(fontSize: 12, color: Colors.black54),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('取消')),
            FilledButton(
              onPressed: () {
                // 边界处理：非法/空输入不崩溃，给出提示；越界自动夹取
                final rawCount = countCtl.text.trim();
                final rawInterval = intervalCtl.text.trim();
                final pc = rawCount.isEmpty ? null : int.tryParse(rawCount);
                final pi = rawInterval.isEmpty ? null : int.tryParse(rawInterval);
                if (pc == null) {
                  setLocal(() => countError = '请输入数字（1~' + TimePoint.maxRepeatCount.toString() + '）');
                  return;
                }
                if (pi == null) {
                  setLocal(() => intervalError = '请输入数字（0 或正整数毫秒）');
                  return;
                }
                // 第 3 项：校验组合数上限，避免一个时间点展开出几万次触发
                final combos =
                    (selHours.isEmpty ? 1 : selHours.length) *
                        (selMinutes.isEmpty ? 1 : selMinutes.length) *
                        (selSeconds.isEmpty ? 1 : selSeconds.length);
                if (combos > TimePoint.maxCombos) {
                  setLocal(() => comboError =
                      '组合过多（' + combos.toString() + ' 个时刻，上限 ' +
                          TimePoint.maxCombos.toString() + '）：请减少分/秒的选中数量');
                  return;
                }
                // 分/秒 不允许为空（否则没有任何触发时刻）
                if (selMinutes.isEmpty || selSeconds.isEmpty) {
                  setLocal(() => comboError = '「分」和「秒」至少要各选一个');
                  return;
                }
                // 单值字段取集合的最小值，保持与旧字段的兼容读取
                final hMin = selHours.isEmpty ? -1 : selHours.reduce((a, b) => a < b ? a : b);
                final mMin = selMinutes.reduce((a, b) => a < b ? a : b);
                final sMin = selSeconds.reduce((a, b) => a < b ? a : b);
                Navigator.pop(
                  ctx,
                  TimeEditResult(
                    hour: hMin,
                    minute: mMin,
                    second: sMin,
                    repeatCount: TimePoint.normalizeRepeatCount(pc),
                    repeatIntervalMs: TimePoint.normalizeRepeatInterval(pi),
                    hours: selHours,
                    minutes: selMinutes,
                    seconds: selSeconds,
                  ),
                );
              },
              child: Text(isEdit ? '保存' : '添加'),
            ),
          ],
        );
      },
    ),
  );
}
