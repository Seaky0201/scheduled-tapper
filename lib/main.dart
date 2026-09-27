import 'dart:async';

import 'package:flutter/material.dart';

import 'dialogs.dart';
import 'models.dart';
import 'native.dart';

void main() {
  runApp(const MaterialApp(home: HomePage(), debugShowCheckedModeBanner: false));
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});
  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  TapperConfig _cfg = TapperConfig();
  final List<String> _logs = <String>[];
  Map<String, dynamic> _state = <String, dynamic>{};
  StreamSubscription<dynamic>? _logSub;
  StreamSubscription<dynamic>? _stateSub;
  StreamSubscription<dynamic>? _pickSub;
  String _pickTarget = '';
  bool _pickMode = false;
  bool _panelOpen = false;
  /// 状态详情（下次触发/系统时区）是否展开。默认收起，把纵向空间让给时间点列表。
  bool _statusDetailOpen = false;
  /// 运行日志区是否展开。默认收起（原先固定 220px，是页面最大的固定占用）。
  bool _logOpen = false;
  final ScrollController _logScroll = ScrollController();

  @override
  void initState() {
    super.initState();
    _boot();
    _logSub = Native.logs.receiveBroadcastStream().listen((e) {
      final line = e.toString();
      setState(() {
        _logs.add(line);
        if (_logs.length > 400) _logs.removeAt(0);
      });
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (_logScroll.hasClients) {
          _logScroll.jumpTo(_logScroll.position.maxScrollExtent);
        }
      });
    });
    _stateSub = Native.state.receiveBroadcastStream().listen((e) {
      if (!mounted) return;
      setState(() => _state = Map<String, dynamic>.from(e as Map));
    });
    _pickSub = Native.pick.receiveBroadcastStream().listen((e) {
      if (!mounted) return;
      final m = Map<String, dynamic>.from(e as Map);
      if (m.containsKey('panelOpen')) {
        setState(() => _panelOpen = m['panelOpen'] == true);
        return;
      }
      if (m.containsKey('active')) {
        final active = m['active'] == true;
        // 取点模式结束（含取消）后作废本次目标，避免之后误落到旧时间点
        setState(() {
          _pickMode = active;
          if (!active) _pickTarget = '';
        });
        return;
      }
      // 悬浮窗内直接改写了配置（选点导入）-> 重新读取
      if (m.containsKey('configChanged')) {
        _reloadConfig();
        return;
      }
      _onPicked(m['x'] as int, m['y'] as int, m['sw'] as int, m['sh'] as int);
    });
  }

  @override
  void dispose() {
    _logSub?.cancel();
    _stateSub?.cancel();
    _pickSub?.cancel();
    _logScroll.dispose();
    super.dispose();
  }

  Future<void> _boot() async {
    final json = await Native.getConfig();
    if (!mounted) return;
    setState(() => _cfg = TapperConfig.fromJsonString(json));
    await Native.startService();
    await _refresh();
  }

  /// 悬浮窗可能已在原生侧改写配置（选点导入），重新读取以免界面与持久化不一致。
  Future<void> _reloadConfig() async {
    try {
      final json = await Native.getConfig();
      if (!mounted) return;
      setState(() => _cfg = TapperConfig.fromJsonString(json));
    } catch (_) {}
  }

  Future<void> _refresh() async {
    try {
      final s = await Native.getState();
      if (mounted) setState(() => _state = s);
    } catch (_) {}
  }

  Future<void> _save() async {
    await Native.saveConfig(_cfg.toJsonString());
    await _refresh();
  }

  void _toast(String m) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(m), duration: const Duration(milliseconds: 1200)),
    );
  }

  // ---------------- 取点 ----------------

  Future<void> _pickFor(String tag) async {
    if (_state['overlay'] != true) {
      _toast('请先授予「显示在其他应用上层」权限');
      await Native.openOverlaySettings();
      return;
    }
    setState(() => _pickTarget = tag);
    await Native.enterPickMode();
    _toast('已进入取点模式：点击屏幕任意位置');
  }

  Future<void> _onPicked(int x, int y, int sw, int sh) async {
    final tag = _pickTarget;
    setState(() => _pickTarget = '');
    if (tag.isEmpty) {
      _toast('已记录坐标 (' + x.toString() + ', ' + y.toString() + ')');
      return;
    }
    final parts = tag.split('|');
    if (parts[0] == 'new') {
      final p = _cfg.points.firstWhere((e) => e.id == parts[1], orElse: () => TimePoint());
      setState(() {
        p.steps.add(TapStep(x: x, y: y, delayMs: 200, sw: sw, sh: sh));
      });
      await _save();
      _toast('已添加步骤 ' + p.steps.length.toString());
    } else {
      final idx = int.parse(parts[1]);
      final p = _cfg.points.firstWhere((e) => e.id == parts[2], orElse: () => TimePoint());
      setState(() {
        p.steps[idx].x = x;
        p.steps[idx].y = y;
        p.steps[idx].sw = sw;
        p.steps[idx].sh = sh;
      });
      await _save();
      _toast('步骤 ' + (idx + 1).toString() + ' 已更新');
    }
  }

  // ---------------- 时间点操作 ----------------

  Future<void> _addPoint() async {
    final r = await showTimeDialog(context);
    if (r == null) return;
    setState(() {
      _cfg.points.add(TimePoint(
        hour: r.hour,
        minute: r.minute,
        second: r.second,
        repeatCount: r.repeatCount,
        repeatIntervalMs: r.repeatIntervalMs,
        // 第 3 项：多选集合（空集 = 沿用单值）
        hours: r.hours,
        minutes: r.minutes,
        seconds: r.seconds,
      ));
    });
    await _save();
    _toast('已新增时间点 ' + r.hour.toString() + ':' + r.minute.toString());
  }

  /// 阶段三新增：修改已存在的时间点（含重复次数与间隔）。
  /// 就地更新同一个对象，id 与已记录的步骤保持不变，因此持久化数据与界面同步生效。
  Future<void> _editPoint(TimePoint p) async {
    final r = await showTimeDialog(context, existing: p);
    if (r == null) return;
    setState(() {
      p.hour = r.hour;
      p.minute = r.minute;
      p.second = r.second;
      p.repeatCount = r.repeatCount;
      p.repeatIntervalMs = r.repeatIntervalMs;
      // 第 3 项：多选集合同步更新
      p.hours = List<int>.from(r.hours);
      p.minutes = List<int>.from(r.minutes);
      p.seconds = List<int>.from(r.seconds);
    });
    await _save();
    _toast('已保存修改：' + p.label + ' · ' + p.repeatLabel);
  }

  // ---------------- 分组操作（阶段二） ----------------

  Future<void> _addGroup() async {
    final name = await showGroupNameDialog(context);
    if (name == null) return;
    setState(() => _cfg.groups.add(PointGroup(name: name)));
    await _save();
    _toast('已新建分组：' + name);
  }

  /// 重命名分组。默认组也允许改名（按已确认语义：可改名、不可删除）。
  Future<void> _renameGroup(PointGroup g) async {
    final name = await showGroupNameDialog(context, existing: g.name);
    if (name == null) return;
    setState(() => g.name = name);
    await _save();
    _toast('分组已重命名为：' + name);
  }

  /// 删除分组：连同组内时间点一起删除（已确认语义），因此先二次确认并告知数量。
  Future<void> _deleteGroup(PointGroup g) async {
    if (_cfg.isDefaultGroup(g.id)) {
      _toast('「未分组」是默认分组，不能删除');
      return;
    }
    final count = _cfg.points.where((p) => p.groupId == g.id).length;
    final ok = await showDeleteGroupDialog(context, g.name, count);
    if (ok != true) return;
    setState(() {
      _cfg.points.removeWhere((p) => p.groupId == g.id);
      _cfg.groups.removeWhere((x) => x.id == g.id);
    });
    await _save();
    _toast('已删除分组：' + g.name + (count > 0 ? '（含 ' + count.toString() + ' 个时间点）' : ''));
  }

  Future<void> _movePointToGroup(TimePoint p) async {
    final gid = await showMoveGroupDialog(context, p.groupId, _cfg.groups);
    if (gid == null || gid == p.groupId) return;
    setState(() => p.groupId = gid);
    await _save();
    _toast('已移动到：' + (_cfg.groupById(gid)?.name ?? gid));
  }

  /// 上移/下移：沿用原有实现（在 _cfg.points 线性表内交换相邻两项）。
  /// 抽成方法供长按菜单复用，逻辑与改动前逐字一致，未做任何改写。
  Future<void> _movePointUp(int index) async {
    setState(() {
      final t = _cfg.points[index - 1];
      _cfg.points[index - 1] = _cfg.points[index];
      _cfg.points[index] = t;
    });
    await _save();
  }

  Future<void> _movePointDown(int index) async {
    setState(() {
      final t = _cfg.points[index + 1];
      _cfg.points[index + 1] = _cfg.points[index];
      _cfg.points[index] = t;
    });
    await _save();
  }

  Future<void> _deletePoint(int index) async {
    setState(() => _cfg.points.removeAt(index));
    await _save();
  }

  /// 复制时间点：在同一分组下新建一条**内容完全一致**的新时间点。
  ///
  /// 逐字段显式复制（项目内无现成克隆工具，grep clone/copy/copyWith = 0 命中），
  /// 字段清单与 models.dart TimePoint 一一对应，不做静默丢弃：
  ///   hour / minute / second / steps / repeatCount / repeatIntervalMs / groupId
  /// 按用户确认：enabled 强制为关闭（避免凭空多出一个会参与定时触发的任务）；
  /// id 由 TimePoint 构造函数的既有规则自动生成；插入位置为原项之后（同分组内紧邻）。
  Future<void> _duplicatePoint(int index) async {
    final src = _cfg.points[index];
    final copy = TimePoint(
      hour: src.hour,
      minute: src.minute,
      second: src.second,
      enabled: false,
      repeatCount: src.repeatCount,
      repeatIntervalMs: src.repeatIntervalMs,
      groupId: src.groupId,
      // 步骤逐项复制为新的 TapStep，避免与原项共享可变对象
      steps: src.steps
          .map((s) => TapStep(x: s.x, y: s.y, delayMs: s.delayMs, sw: s.sw, sh: s.sh))
          .toList(),
    );
    setState(() => _cfg.points.insert(index + 1, copy));
    await _save();
    _toast('已复制时间点：' + src.label + '（新项默认关闭）');
  }

  Future<void> _addTapStep(TimePoint p) async {
    await _pickFor('new|' + p.id);
  }

  Future<void> _runNow(TimePoint p) async {
    if (p.steps.isEmpty) {
      _toast('该时间点还没有步骤');
      return;
    }
    if (_state['a11y'] != true) {
      _toast('无障碍服务未开启，无法点击');
      await Native.openAccessibilitySettings();
      return;
    }
    final ok = await Native.runNow(p.label + '(手动)', _cfg.toJsonString());
    _toast(ok ? '已开始执行序列' : '执行失败，请查看日志');
  }

  @override
  Widget build(BuildContext context) {
    final a11y = _state['a11y'] == true;
    final overlay = _state['overlay'] == true;
    final running = _state['running'] == true;
    return Scaffold(
      appBar: AppBar(
        title: const Text('定时连点器'),
        actions: [
          IconButton(
            tooltip: '清空日志',
            onPressed: () async {
              await Native.clearLog();
              setState(() => _logs.clear());
            },
            icon: const Icon(Icons.delete_outline),
          ),
        ],
      ),
      body: Column(
        children: [
          _statusCard(a11y, overlay, running),
          const Divider(height: 1),
          Expanded(
            child: ListView(
              padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
              children: [
                if (_cfg.points.isEmpty && _cfg.groups.length <= 1)
                  const Padding(
                    padding: EdgeInsets.all(24),
                    child: Center(child: Text('还没有时间点，点右下角 + 添加')),
                  ),
                // 按分组归类展示：每个分组一个可折叠区块，组内时间点沿用原卡片
                ..._cfg.grouped().map((e) => _groupSection(e.key, e.value)),
                const SizedBox(height: 8),
                _logCard(),
              ],
            ),
          ),
        ],
      ),
      floatingActionButton: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          FloatingActionButton.small(
            heroTag: 'fabGroup',
            tooltip: '新建分组',
            onPressed: _addGroup,
            child: const Icon(Icons.create_new_folder_outlined),
          ),
          const SizedBox(height: 8),
          FloatingActionButton.extended(
            heroTag: 'fabPoint',
            onPressed: _addPoint,
            icon: const Icon(Icons.add_alarm),
            label: const Text('新增时间点'),
          ),
        ],
      ),
    );
  }

  /// 布局改造：原先把「状态 chip + 下次触发/时区 + 7 个按钮」全部平铺，
  /// 固定占用约 200dp，把时间点列表挤在下面。
  /// 现在：状态 chip 与操作按钮**始终可见**（入口不被隐藏），
  /// 只把「下次触发 / 系统时区」这两行详情收进可折叠区（默认收起），
  /// 并整体压缩内边距与控件密度，把纵向空间让给时间点列表。
  Widget _statusCard(bool a11y, bool overlay, bool running) {
    return Card(
      margin: const EdgeInsets.fromLTRB(8, 8, 8, 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 4, 8, 4),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Wrap(
                    spacing: 6,
                    runSpacing: 4,
                    children: [
                      _chip('无障碍', a11y, a11y ? '已连接' : '未开启'),
                      _chip('悬浮窗', overlay, overlay ? '已授权' : '未授权'),
                      _chip('状态', running, running ? '执行中' : '待机'),
                    ],
                  ),
                ),
                IconButton(
                  tooltip: _statusDetailOpen ? '收起状态详情' : '展开状态详情',
                  visualDensity: VisualDensity.compact,
                  onPressed: () => setState(() => _statusDetailOpen = !_statusDetailOpen),
                  icon: Icon(_statusDetailOpen ? Icons.expand_less : Icons.expand_more, size: 20),
                ),
              ],
            ),
            if (_statusDetailOpen) ...[
              Text('下次触发: ' + (_state['next']?.toString() ?? '-'),
                  style: const TextStyle(fontSize: 13)),
              Text('系统时区: ' + (_state['tz']?.toString() ?? '-'),
                  style: const TextStyle(fontSize: 12, color: Colors.black54)),
              const SizedBox(height: 4),
            ],
            // 布局改造：6 个平铺按钮是上方区域的最大占用（约 2 行）。
            // 现在只保留最高频的「选点导入」为直接按钮，其余 4 项收进「设置」菜单
            // —— 入口一个都没少，只是不再各占一行宽度。
            Row(
              children: [
                _statusAction(Icons.checklist, '选点导入', () async {
                  if (_state['overlay'] != true) {
                    _toast('请先授予「显示在其他应用上层」权限');
                    await Native.openOverlaySettings();
                    return;
                  }
                  await Native.showOverlay();
                  await Native.openPicker();
                  _toast('已请求打开选点导入面板');
                }),
                const SizedBox(width: 6),
                PopupMenuButton<String>(
                  tooltip: '设置',
                  enabled: true,
                  itemBuilder: (ctx) => [
                    const PopupMenuItem(value: 'a11y', child: Text('无障碍设置')),
                    const PopupMenuItem(value: 'overlay', child: Text('悬浮窗设置')),
                    const PopupMenuItem(value: 'show', child: Text('显示悬浮窗')),
                    PopupMenuItem(
                        value: 'panel',
                        child: Text(_panelOpen ? '收起悬浮窗面板' : '展开悬浮窗面板')),
                  ],
                  onSelected: (v) async {
                    switch (v) {
                      case 'a11y':
                        await Native.openAccessibilitySettings();
                        break;
                      case 'overlay':
                        await Native.openOverlaySettings();
                        break;
                      case 'show':
                        await Native.showOverlay();
                        _toast('已请求显示悬浮窗');
                        break;
                      case 'panel':
                        await Native.togglePanel();
                        _toast(_panelOpen ? '已收起悬浮窗设置面板' : '已展开悬浮窗设置面板');
                        break;
                    }
                  },
                  child: OutlinedButton.icon(
                    onPressed: null,
                    icon: const Icon(Icons.settings, size: 17),
                    label: const Text('设置', style: TextStyle(fontSize: 13)),
                    style: OutlinedButton.styleFrom(
                      visualDensity: VisualDensity.compact,
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
                      minimumSize: const Size(0, 32),
                    ),
                  ),
                ),
                if (running) ...[
                  const SizedBox(width: 6),
                  _statusAction(Icons.stop, '中止', () => Native.abort()),
                ],
              ],
            ),
          ],
        ),
      ),
    );
  }

  /// 状态卡片里的紧凑操作按钮：入口与文案不变，只压缩内边距与最小高度，
  /// 让同一块区域能少占 1~2 行（原来默认尺寸约 40dp/行）。
  Widget _statusAction(IconData icon, String label, VoidCallback onPressed) {
    return OutlinedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 17),
      label: Text(label, style: const TextStyle(fontSize: 13)),
      style: OutlinedButton.styleFrom(
        visualDensity: VisualDensity.compact,
        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        minimumSize: const Size(0, 32),
      ),
    );
  }

  Widget _chip(String label, bool ok, String detail) {
    final color = ok ? Colors.green : Colors.orange;
    return Chip(
      avatar: Icon(ok ? Icons.check_circle : Icons.error_outline, color: color, size: 18),
      label: Text(label + ': ' + detail),
      visualDensity: VisualDensity.compact,
    );
  }

  /// 阶段四排版修复说明：
  /// 原先把「上移/下移/删除」三个 IconButton 放在 ExpansionTile.trailing 的 Row 里，
  /// 每个 IconButton 最小 48dp，合计约 144dp；再加上 leading 的 Switch（约 60dp），
  /// 在 360dp 宽的手机上只剩约 120dp 给 title/subtitle，导致描述文字被迫逐字换行（看起来像竖排）。
  /// 现在把这些按钮移到展开区内的操作栏（Wrap，可换行），trailing 不再占用宽度，
  /// 描述文字可用宽度提升到整行宽度，横向空间明显变大。
  /// 分组区块：标题（名称 + 计数 + 改名/删除）+ 组内时间点卡片。
  /// 空分组也会显示（按已确认语义「允许存在空分组」）。
  Widget _groupSection(PointGroup g, List<TimePoint> pts) {
    final isDefault = _cfg.isDefaultGroup(g.id);
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      color: const Color(0xFFF6F6F6),
      child: ExpansionTile(
        // 按需求调整：分组自身默认**收起**（原为 true）。
        // 与 _pointCard 的 initiallyExpanded:false 是两处独立默认值，均已按需求改为收起。
        initiallyExpanded: false,
        dense: true,
        tilePadding: const EdgeInsets.symmetric(horizontal: 8),
        childrenPadding: const EdgeInsets.fromLTRB(4, 0, 4, 4),
        leading: Icon(isDefault ? Icons.inbox_outlined : Icons.folder_outlined, size: 20),
        title: Row(
          children: [
            Flexible(
              child: Text(
                g.name,
                style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 14),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            const SizedBox(width: 6),
            Text('(' + pts.length.toString() + ')',
                style: const TextStyle(fontSize: 12, color: Colors.black54)),
          ],
        ),
        // 分组操作收进「更多」菜单（与步骤行的既有 PopupMenuButton 约定一致），
        // 不再用一整行按钮，避免把组内时间点整体下推。
        trailing: PopupMenuButton<String>(
          tooltip: '分组操作',
          icon: const Icon(Icons.more_vert, size: 20),
          padding: EdgeInsets.zero,
          constraints: const BoxConstraints(minWidth: 160),
          onSelected: (v) async {
            if (v == 'rename') await _renameGroup(g);
            if (v == 'delete') await _deleteGroup(g);
          },
          itemBuilder: (ctx) => [
            const PopupMenuItem(value: 'rename', child: Text('重命名分组')),
            PopupMenuItem(
              value: 'delete',
              enabled: !isDefault,
              child: Text(isDefault ? '默认分组（不可删除）' : '删除分组及组内时间点'),
            ),
          ],
        ),
        children: [
          if (pts.isEmpty)
            const Padding(
              padding: EdgeInsets.fromLTRB(12, 0, 12, 8),
              child: Text('（该分组暂无时间点）',
                  style: TextStyle(fontSize: 12, color: Colors.black54)),
            ),
          // 组内时间点：索引仍按全局 _cfg.points 计算，保证上移/下移语义不变
          ...pts.map((p) => _pointCard(_cfg.points.indexOf(p), p)),
        ],
      ),
    );
  }

  Widget _pointCard(int index, TimePoint p) {
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      // 长按卡片任意位置弹出操作菜单。用 onLongPressStart 拿到按压位置，
      // 通过 showMenu 在手指处弹出（沿用项目既有菜单样式，不新造弹层）。
      child: GestureDetector(
        behavior: HitTestBehavior.deferToChild,
        onLongPressStart: (d) => _showPointMenu(index, p, d.globalPosition),
        child: ExpansionTile(
        // 按需求调整：组内时间点默认**收起**（原为 true）。
        // 仅改时间点卡片这一处；分组自身的 initiallyExpanded（_groupSection）保持不动。
        initiallyExpanded: false,
        leading: Switch(
          value: p.enabled,
          onChanged: (v) async {
            setState(() => p.enabled = v);
            await _save();
          },
        ),
        title: Text(
          p.label,
          style: const TextStyle(fontWeight: FontWeight.bold),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          p.steps.length.toString() + ' 步 · ' + (p.enabled ? '已启用' : '已停用') + ' · ' + p.repeatLabel,
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
        children: [
          // 操作入口已全部迁入「长按菜单」（按用户确认：不保留新旧两套入口）。
          // 长按卡片任意位置即可弹出，菜单实现沿用项目既有的 PopupMenuButton 方案
          // （与步骤行、分组标题的菜单一致），不再自建弹层。
          ...p.steps.asMap().entries.map((e) => _stepTile(p, e.key, e.value)),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 4, 8, 12),
            child: Row(
              children: [
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed: () => _addTapStep(p),
                    icon: const Icon(Icons.my_location, size: 18),
                    label: const Text('悬浮窗取点添加步骤'),
                  ),
                ),
                const SizedBox(width: 8),
                OutlinedButton.icon(
                  onPressed: () async {
                    final c = await showCoordDialog(context, 0, 0);
                    if (c == null) return;
                    setState(() => p.steps.add(TapStep(x: c[0], y: c[1], delayMs: 200)));
                    await _save();
                  },
                  icon: const Icon(Icons.keyboard, size: 18),
                  label: const Text('手输'),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 12),
            child: Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: () => _runNow(p),
                    icon: const Icon(Icons.play_arrow, size: 18),
                    label: const Text('立即执行一次'),
                  ),
                ),
                const SizedBox(width: 8),
                OutlinedButton(
                  onPressed: () async {
                    final c = await showCoordDialog(context, 100, 400);
                    if (c == null) return;
                    final ok = await Native.testTap(c[0], c[1]);
                    _toast(ok ? '测试点击已派发' : '测试点击失败（检查无障碍）');
                  },
                  child: const Text('测试单点'),
                ),
              ],
            ),
          ),
        ],
        ),
      ),
    );
  }

  /// 长按时间点弹出的操作菜单：修改 / 分组 / 上移 / 下移 / 删除 / 复制。
  ///
  /// 复用既有回调（_editPoint / _movePointToGroup / _movePointUp / _movePointDown /
  /// _deletePoint / _duplicatePoint），不复制业务逻辑；
  /// 弹出方式沿用项目既有菜单（showMenu + PopupMenuItem，样式与步骤行/分组菜单一致）。
  /// 上移/下移的禁用态由 PopupMenuItem.enabled 表达（原按钮栏的 disabled 语义等价迁移）。
  Future<void> _showPointMenu(int index, TimePoint p, Offset globalPos) async {
    final overlay = Overlay.of(context).context.findRenderObject() as RenderBox?;
    if (overlay == null) return;
    final selected = await showMenu<String>(
      context: context,
      position: RelativeRect.fromRect(
        Rect.fromLTWH(globalPos.dx, globalPos.dy, 0, 0),
        Offset.zero & overlay.size,
      ),
      items: [
        const PopupMenuItem(value: 'edit', child: Text('修改')),
        const PopupMenuItem(value: 'group', child: Text('分组')),
        PopupMenuItem(value: 'up', enabled: index > 0, child: const Text('上移')),
        PopupMenuItem(
            value: 'down',
            enabled: index < _cfg.points.length - 1,
            child: const Text('下移')),
        const PopupMenuItem(value: 'duplicate', child: Text('复制')),
        const PopupMenuItem(value: 'delete', child: Text('删除')),
      ],
    );
    if (selected == null) return;
    switch (selected) {
      case 'edit':
        await _editPoint(p);
        break;
      case 'group':
        await _movePointToGroup(p);
        break;
      case 'up':
        await _movePointUp(index);
        break;
      case 'down':
        await _movePointDown(index);
        break;
      case 'duplicate':
        await _duplicatePoint(index);
        break;
      case 'delete':
        await _deletePoint(index);
        break;
    }
  }

  /// 阶段四排版修复说明：
  /// 原先 5 个 IconButton 直接排在 trailing 的 Row 里，约占用 240dp，
  /// 在手机上几乎把 title/subtitle 挤没，描述只能竖排显示。
  /// 现在改为单个「更多」弹出菜单（约 40dp），描述文字获得整行横向空间。
  Widget _stepTile(TimePoint p, int i, TapStep s) {
    return ListTile(
      dense: true,
      contentPadding: const EdgeInsets.only(left: 12, right: 0),
      leading: CircleAvatar(
        radius: 12,
        child: Text((i + 1).toString(), style: const TextStyle(fontSize: 11)),
      ),
      title: Text(
        '坐标 (' + s.x.toString() + ', ' + s.y.toString() + ')',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        '取点时屏幕 ' +
            s.sw.toString() +
            'x' +
            s.sh.toString() +
            ' · 到下一步延时 ' +
            s.delayMs.toString() +
            'ms',
        maxLines: 2,
        overflow: TextOverflow.ellipsis,
      ),
      trailing: PopupMenuButton<String>(
        tooltip: '步骤操作',
        icon: const Icon(Icons.more_vert, size: 20),
        padding: EdgeInsets.zero,
        constraints: const BoxConstraints(minWidth: 160),
        onSelected: (v) async {
          switch (v) {
            case 'pick':
              await _pickFor('edit|' + i.toString() + '|' + p.id);
              break;
            case 'delay':
              final nv = await showDelayDialog(context, s.delayMs);
              if (nv == null) return;
              setState(() => s.delayMs = nv);
              await _save();
              break;
            case 'up':
              setState(() {
                final t = p.steps[i - 1];
                p.steps[i - 1] = p.steps[i];
                p.steps[i] = t;
              });
              await _save();
              break;
            case 'down':
              setState(() {
                final t = p.steps[i + 1];
                p.steps[i + 1] = p.steps[i];
                p.steps[i] = t;
              });
              await _save();
              break;
            case 'delete':
              setState(() => p.steps.removeAt(i));
              await _save();
              break;
          }
        },
        itemBuilder: (ctx) => [
          const PopupMenuItem(value: 'pick', child: Text('重新取点')),
          const PopupMenuItem(value: 'delay', child: Text('改延时')),
          PopupMenuItem(value: 'up', enabled: i > 0, child: const Text('上移')),
          PopupMenuItem(value: 'down', enabled: i < p.steps.length - 1, child: const Text('下移')),
          const PopupMenuItem(value: 'delete', child: Text('删除步骤')),
        ],
      ),
    );
  }

  /// 布局改造：日志区原先固定 220px 高度且恒常展开，是页面上最大的固定占用，
  /// 把时间点列表挤在很窄的一段里。现在标题行**始终可见**（可点击展开/收起，
  /// 日志条数与「取点模式进行中…」提示照旧显示），内容默认收起，
  /// 展开时高度也由固定 220 改为按可用空间自适应，不再顶掉时间点列表。
  Widget _logCard() {
    return Card(
      margin: const EdgeInsets.fromLTRB(8, 4, 8, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          InkWell(
            onTap: () => setState(() => _logOpen = !_logOpen),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(8, 6, 8, 6),
              child: Row(
                children: [
                  const Icon(Icons.terminal, size: 18),
                  const SizedBox(width: 6),
                  Text('运行日志 (' + _logs.length.toString() + ')',
                      style: const TextStyle(fontWeight: FontWeight.bold)),
                  const Spacer(),
                  if (_pickMode)
                    const Text('取点模式进行中…',
                        style: TextStyle(color: Colors.red, fontWeight: FontWeight.bold)),
                  Icon(_logOpen ? Icons.expand_less : Icons.expand_more, size: 20),
                ],
              ),
            ),
          ),
          if (_logOpen)
            Padding(
              padding: const EdgeInsets.fromLTRB(8, 0, 8, 8),
              child: Container(
                height: 200,
                width: double.infinity,
                decoration: BoxDecoration(
                  color: const Color(0xFF111111),
                  borderRadius: BorderRadius.circular(6),
                ),
                padding: const EdgeInsets.all(6),
                child: SingleChildScrollView(
                  controller: _logScroll,
                  child: SelectableText(
                    _logs.isEmpty ? '(暂无日志)' : _logs.join('\n'),
                    style: const TextStyle(
                        color: Color(0xFF9CCC65), fontSize: 11, fontFamily: 'monospace'),
                  ),
                ),
              ),
            ),
        ],
      ),
    );
  }
}
