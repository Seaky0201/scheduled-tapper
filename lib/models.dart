import 'dart:convert';
import 'dart:math';

/// 与原生 TapStep 一一对应的 JSON 结构（键名必须与 Kotlin 端一致）。
class TapStep {
  int x;
  int y;
  int delayMs;
  int sw; // 取点时的屏幕宽
  int sh; // 取点时的屏幕高

  TapStep({this.x = 0, this.y = 0, this.delayMs = 200, this.sw = 0, this.sh = 0});

  Map<String, dynamic> toJson() =>
      {'x': x, 'y': y, 'delayMs': delayMs, 'sw': sw, 'sh': sh};

  static TapStep fromJson(Map<String, dynamic> o) => TapStep(
        x: (o['x'] ?? 0) as int,
        y: (o['y'] ?? 0) as int,
        delayMs: (o['delayMs'] ?? 200) as int,
        sw: (o['sw'] ?? 0) as int,
        sh: (o['sh'] ?? 0) as int,
      );
}

/// 时间点分组（单层，不允许嵌套）。
///
/// 未分组的时间点归入 [TapperConfig.defaultGroupId] 对应的默认组「未分组」，
/// 该默认组可改名但不可删除（已与用户确认）。
class PointGroup {
  String id;
  String name;

  PointGroup({String? id, String? name})
      : id = id ?? _newId(),
        name = name ?? '新分组';

  static String _newId() {
    final r = Random();
    return 'g' +
        DateTime.now().microsecondsSinceEpoch.toString() +
        '-' +
        r.nextInt(1 << 32).toRadixString(16);
  }

  Map<String, dynamic> toJson() => {'id': id, 'name': name};

  static PointGroup fromJson(Map<String, dynamic> o) => PointGroup(
        id: o['id'] as String?,
        name: (o['name'] ?? '未命名分组') as String,
      );
}

/// 读取可选的整数数组字段；缺失/非法返回空列表（= 沿用单值字段）。
List<int> _intList(dynamic v) {
  if (v is! List) return <int>[];
  return v.map((e) => (e as num).toInt()).toList();
}

/// 时/分/秒多选集合的格式化逻辑（与 Kotlin 端 TapMath.TimeSets 行为一致）。
class TimeSets {
  static const int maxCombos = 512;

  /// 连续段压缩成 a-b，离散值逗号分隔。
  static String formatValues(List<int> values, {required bool pad, required String emptyLabel}) {
    if (values.isEmpty) return emptyLabel;
    final v = values.toSet().toList()..sort();
    final parts = <String>[];
    var i = 0;
    while (i < v.length) {
      var j = i;
      while (j + 1 < v.length && v[j + 1] == v[j] + 1) {
        j++;
      }
      if (j - i >= 2) {
        parts.add(_fmt(v[i], pad) + '-' + _fmt(v[j], pad));
      } else {
        parts.add([for (var k = i; k <= j; k++) _fmt(v[k], pad)].join(','));
      }
      i = j + 1;
    }
    return parts.join(',');
  }

  static String _fmt(int v, bool pad) => pad ? v.toString().padLeft(2, '0') : v.toString();

  /// 完整可读标签：「08,12时 30分 00秒」「每小时 00-10分 03秒」「全部小时 30分 00秒」。
  static String label(List<int> hours, List<int> minutes, List<int> seconds) {
    final hs = (hours.toSet().toList()..sort());
    final String hourText;
    if (hs.isEmpty) {
      hourText = '每小时';
    } else if (hs.length == 24) {
      hourText = '全部小时';
    } else {
      hourText = formatValues(hs, pad: true, emptyLabel: '每小时') + '时';
    }
    final minText = formatValues(minutes, pad: true, emptyLabel: '00') + '分';
    final secText = formatValues(seconds, pad: true, emptyLabel: '00') + '秒';
    return hourText + ' ' + minText + ' ' + secText;
  }
}

class TimePoint {
  String id;
  int hour; // -1 表示每小时重复
  int minute;
  int second;
  bool enabled;
  List<TapStep> steps;

  /// 总触发次数（含第一次）。1 = 只触发一次（与旧版行为一致）。
  int repeatCount;

  /// 相邻两次触发之间的间隔（毫秒）。0 = 首次触发后立即连续触发。
  int repeatIntervalMs;

  /// 所属分组 id。空字符串 = 未分组（展示时归入默认组）。
  String groupId;

  /// 多选时刻集合（第 3 项）。空列表 = 沿用单值字段（旧数据/旧调用方不受影响）。
  List<int> hours;
  List<int> minutes;
  List<int> seconds;

  /// 与原生 TimePoint.MAX_REPEAT_COUNT / MAX_REPEAT_INTERVAL_MS 保持一致
  static const int maxRepeatCount = 999;
  static const int maxRepeatIntervalMs = 24 * 60 * 60 * 1000;

  TimePoint({
    String? id,
    this.hour = -1,
    this.minute = 0,
    this.second = 0,
    // 按需求调整：新建时间点默认**关闭**（原为 true）。
    // 注意这是「新建时的默认值」，不影响旧数据读取——
    // 旧 JSON 缺 enabled 字段时仍由 fromJson 回落到 true（兼容既有行为）。
    this.enabled = false,
    List<TapStep>? steps,
    int repeatCount = 1,
    int repeatIntervalMs = 0,
    this.groupId = '',
    List<int>? hours,
    List<int>? minutes,
    List<int>? seconds,
  })  : id = id ?? _newId(),
        hours = hours ?? <int>[],
        minutes = minutes ?? <int>[],
        seconds = seconds ?? <int>[],
        steps = steps ?? <TapStep>[],
        repeatCount = normalizeRepeatCount(repeatCount),
        repeatIntervalMs = normalizeRepeatInterval(repeatIntervalMs);

  /// 非法/越界输入一律夹取，绝不抛异常
  static int normalizeRepeatCount(int v) => v < 1 ? 1 : (v > maxRepeatCount ? maxRepeatCount : v);

  static int normalizeRepeatInterval(int v) =>
      v < 0 ? 0 : (v > maxRepeatIntervalMs ? maxRepeatIntervalMs : v);

  /// 重复设置的文字描述
  String get repeatLabel =>
      repeatCount <= 1 ? '单次' : (repeatCount.toString() + ' 次 · 间隔 ' + repeatIntervalMs.toString() + 'ms');

  /// 本轮总耗时（毫秒），用于界面提示
  int get totalSpanMs => (repeatCount - 1) * repeatIntervalMs;

  static String _newId() {
    final r = Random();
    return DateTime.now().microsecondsSinceEpoch.toString() +
        '-' +
        r.nextInt(1 << 32).toRadixString(16);
  }

  bool get hourly => hour < 0;

  /// 有效小时集合：集合为空时回落到单值（hour<0 = 每小时 -> 空集）。
  List<int> get effectiveHours =>
      hours.isNotEmpty ? hours : (hourly ? <int>[] : <int>[hour]);
  List<int> get effectiveMinutes => minutes.isNotEmpty ? minutes : <int>[minute];
  List<int> get effectiveSeconds => seconds.isNotEmpty ? seconds : <int>[second];

  /// 展开后的全部触发时刻（时×分×秒）。
  List<List<int>> get combos {
    final hs = effectiveHours.isEmpty ? <int>[-1] : effectiveHours;
    final ms = effectiveMinutes;
    final ss = effectiveSeconds;
    final out = <List<int>>[];
    for (final h in hs) {
      for (final m in ms) {
        for (final s in ss) {
          out.add(<int>[h, m, s]);
        }
      }
    }
    return out;
  }

  /// 组合数上限（与 Kotlin 端 TapMath.TimeSets.MAX_COMBOS 保持一致）。
  static const int maxCombos = 512;

  int get comboCount => combos.length;
  bool get exceedsComboLimit => comboCount > maxCombos;

  /// 完整可读标签，展示全部信息（中文分隔 + 连续区间压缩）。
  String get label => TimeSets.label(effectiveHours, effectiveMinutes, effectiveSeconds);

  Map<String, dynamic> toJson() => {
        'id': id,
        'hour': hour,
        'minute': minute,
        'second': second,
        'enabled': enabled,
        'repeatCount': repeatCount,
        'repeatIntervalMs': repeatIntervalMs,
        'groupId': groupId,
        // 集合为空时不写入这些键，保持旧数据 JSON 形态不变
        if (hours.isNotEmpty) 'hours': hours,
        if (minutes.isNotEmpty) 'minutes': minutes,
        if (seconds.isNotEmpty) 'seconds': seconds,
        'steps': steps.map((s) => s.toJson()).toList(),
      };

  /// 兼容旧数据：旧 JSON 没有 repeatCount/repeatIntervalMs，缺失时取默认值（等价旧行为）
  static TimePoint fromJson(Map<String, dynamic> o) => TimePoint(
        id: o['id'] as String?,
        hour: (o['hour'] ?? -1) as int,
        minute: (o['minute'] ?? 0) as int,
        second: (o['second'] ?? 0) as int,
        enabled: (o['enabled'] ?? true) as bool,
        repeatCount: (o['repeatCount'] ?? 1) as int,
        repeatIntervalMs: (o['repeatIntervalMs'] ?? 0) as int,
        // 兼容旧数据：旧 JSON 没有 groupId -> 空字符串 = 未分组
        groupId: (o['groupId'] ?? '') as String,
        // 兼容旧数据：旧 JSON 没有集合字段 -> 空集 -> 沿用单值字段
        hours: _intList(o['hours']),
        minutes: _intList(o['minutes']),
        seconds: _intList(o['seconds']),
        steps: ((o['steps'] ?? []) as List)
            .map((e) => TapStep.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
      );
}

class TapperConfig {
  List<TimePoint> points;

  /// 分组列表（单层）。始终包含默认组「未分组」，因此永不为空。
  List<PointGroup> groups;
  int tapDurationMs;

  /// 悬浮窗时间源 id（local / beijing）。默认本地时间。
  String timeSource;

  /// 手动微调（毫秒），相对所选时间源，可正可负，默认 0。
  int timeOffsetMs;

  /// 默认组 id：未分组的时间点都归到它名下。该组可改名、不可删除。
  static const String defaultGroupId = 'default';
  static const String defaultGroupName = '未分组';

  TapperConfig({
    List<TimePoint>? points,
    List<PointGroup>? groups,
    this.tapDurationMs = 30,
    this.timeSource = 'local',
    this.timeOffsetMs = 0,
  })  : points = points ?? <TimePoint>[],
        groups = _normalizeGroups(groups);

  /// 保证默认组存在且排在最前，并且每个时间点的 groupId 都指向一个真实存在的分组
  /// （指向已删除/不存在的分组时回落到默认组，避免出现「孤儿时间点」）。
  static List<PointGroup> _normalizeGroups(List<PointGroup>? groups) {
    final list = <PointGroup>[];
    final src = groups ?? <PointGroup>[];
    final def = src.where((g) => g.id == defaultGroupId).toList();
    list.add(def.isNotEmpty
        ? def.first
        : PointGroup(id: defaultGroupId, name: defaultGroupName));
    for (final g in src) {
      if (g.id != defaultGroupId) list.add(g);
    }
    return list;
  }

  /// 把时间点的 groupId 规范化到真实存在的分组（不存在的归入默认组）。
  void normalizePointGroups() {
    final ids = groups.map((g) => g.id).toSet();
    for (final p in points) {
      if (!ids.contains(p.groupId)) p.groupId = defaultGroupId;
    }
  }

  /// 按分组归类：返回「分组 -> 该组时间点」，顺序与 [groups] 一致。
  /// 空分组也会出现（值为空列表），以便界面展示与「允许空分组」的约定一致。
  List<MapEntry<PointGroup, List<TimePoint>>> grouped() {
    normalizePointGroups();
    return groups
        .map((g) => MapEntry(g, points.where((p) => p.groupId == g.id).toList()))
        .toList();
  }

  PointGroup? groupById(String id) {
    for (final g in groups) {
      if (g.id == id) return g;
    }
    return null;
  }

  bool isDefaultGroup(String id) => id == defaultGroupId;

  String toJsonString() => jsonEncode({
        'version': 1,
        'tapDurationMs': tapDurationMs,
        'timeSource': timeSource,
        'timeOffsetMs': timeOffsetMs,
        'groups': groups.map((g) => g.toJson()).toList(),
        'points': points.map((p) => p.toJson()).toList(),
      });

  static TapperConfig fromJsonString(String? s) {
    if (s == null || s.isEmpty) return TapperConfig();
    try {
      final o = jsonDecode(s) as Map<String, dynamic>;
      final cfg = TapperConfig(
        points: ((o['points'] ?? []) as List)
            .map((e) => TimePoint.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        // 兼容旧数据：旧 JSON 没有 groups -> 只会有默认组「未分组」
        groups: ((o['groups'] ?? []) as List)
            .map((e) => PointGroup.fromJson(Map<String, dynamic>.from(e as Map)))
            .toList(),
        tapDurationMs: (o['tapDurationMs'] ?? 30) as int,
        // 兼容旧数据：旧 JSON 没有这两个字段 -> 本地时间 + 微调 0
        timeSource: (o['timeSource'] ?? 'local') as String,
        timeOffsetMs: (o['timeOffsetMs'] ?? 0) as int,
      );
      cfg.normalizePointGroups();
      return cfg;
    } catch (_) {
      return TapperConfig();
    }
  }
}
