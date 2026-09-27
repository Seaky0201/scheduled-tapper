package com.dsh.tapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/**
 * 悬浮窗宿主 + 前台服务 + 定时调度宿主。
 *
 * - 悬浮窗可拖动；**单击展开/收起设置面板**（阶段五改造）；长按隐藏悬浮窗
 * - 设置面板内可直接：取点、显示/隐藏悬浮窗、刷新配置、打开系统设置
 *   （取点仍保留原入口，见面板「选取位置」按钮与主界面「悬浮窗取点添加步骤」）
 * - 取点模式铺一层全屏透明层，点击任意位置即记录该点的屏幕坐标（rawX/rawY）
 * - 前台服务 + 常驻通知，降低后台被系统回收导致漏触发的概率
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var tv: TextView? = null

    /** 悬浮球上的时间行与「时间源不可用」提示行（本轮新增）。 */
    private var clockTv: TextView? = null
    private var clockNote: TextView? = null
    private var catcher: View? = null

    @Volatile private var pickMode = false
    private val handler = Handler(Looper.getMainLooper())
    private var overlayEnabled = true

    /** 阶段五：设置面板视图与其布局参数（null 表示未展开）。 */
    private var panel: View? = null
    private var panelLp: WindowManager.LayoutParams? = null

    /** 面板中显示「下次触发」的文本，便于在悬浮窗内直接看到调度状态。 */
    private var panelNext: TextView? = null

    /**
     * 面板中的时间摘要与微调行。
     * P2 / BUG-3：修复前这两个值只在**打开面板那一刻**取一次快照，之后静止不走；
     * 现在持有引用，由 100ms 的 [updateClockText] 一并刷新，使其持续走动。
     */
    private var panelTimeInfo: TextView? = null
    private var panelOffsetInfo: TextView? = null

    /** 选点导入面板（null 表示未展开）与其布局参数。 */
    private var picker: View? = null
    private var pickerLp: WindowManager.LayoutParams? = null

    /** 选点导入面板中已勾选的时间点 id。 */
    private var pickerSelected: MutableSet<String> = LinkedHashSet()

    /** 测试时间点面板（null 表示未展开）与其布局参数。 */
    private var testPicker: View? = null
    private var testPickerLp: WindowManager.LayoutParams? = null

    /** 测试时间点面板中当前选中的时间点 id（单选，null = 未选）。 */
    private var testSelected: String? = null

    /** 测试时间点面板中需要随选择状态刷新的控件。 */
    private var testTitle: TextView? = null
    private var testHint: TextView? = null
    private var testRows: List<TextView> = emptyList()
    private var testTotal = 0

    /** 选点面板中需要随勾选状态刷新的控件与总数。 */
    private var pickerTitle: TextView? = null
    private var pickerToggleAll: TextView? = null
    private var pickerRows: List<CheckBox> = emptyList()
    private var pickerTotal = 0

    /**
     * 本次取点得到的坐标步骤，等待在选点面板中确认后导入。
     * 仅存内存：进程被杀即失效（不新增持久化）。
     */
    private var pendingSteps: List<Step> = emptyList()

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.getStringExtra("cmd")) {
                "show" -> { overlayEnabled = true; ensureOverlay() }
                "hide" -> { overlayEnabled = false; removeOverlay() }
                "pick" -> enterPickMode()
                "cancelPick" -> exitPickMode()
                "reload" -> reloadConfig()
                "panel" -> togglePanel()
                "closePanel" -> closePanel()
                "picker" -> openPicker()
                "test" -> openTestPicker()
            }
        }
    }

    /**
     * 状态变化（无障碍连接/断开、序列开始/结束、调度刷新）时刷新悬浮球。
     *
     * 修复说明：原先这里读的是 Intent 里的 a11y/running/next extra，但**从来没有任何
     * 发送方 putExtra 过这些值**（全仓库 0 命中），所以永远渲染成「待机(无无障碍) / 下次 -」。
     * 现在改为**实时读取真实数据源**：无障碍用 [TapperAccessibilityService.isReady]，
     * 「下次」用 [Scheduler.nextFireInfo]（调度线程每 200ms 维护）。
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            refreshBubbleStatus()
        }
    }

    /**
     * 当前时间轴（时间源 + 微调）。**显示与调度共用这一条轴**（P0 修复）：
     * 时钟显示与 Scheduler 的命中判定/倒计时都从这里取，不再各自维护一份字段，
     * 从根上消除「时钟已变、倒计时没变」的分叉。
     */
    @Volatile private var axis: TimeAxis = TimeAxis(TapMath.TimeSourceKind.LOCAL, 0L)

    /** 时间轴变更的唯一入口（P0 修复）：写盘 + 应用到调度 + 通知 UI，一处都不落。 */
    private val timeAxisController by lazy {
        TimeAxisController(
            load = { Prefs.load(this) },
            persist = { Prefs.save(this, it) },
            // 关键：同时作用到调度轴（修复前这里缺失，导致 BUG-1）
            applyToScheduler = { schedulerRef?.update(it) },
            // 关键：通知 Flutter 重读，避免其过期副本回写覆盖（修复 BUG-2）
            notifyUi = { sendBroadcast(Intent(LogBus.ACTION_CONFIG).setPackage(packageName)) },
            requestSync = { reason -> TimeSources.syncAsync(applicationContext, reason) },
            log = { msg -> LogBus.add(applicationContext, "OK", msg) },
        )
    }

    /**
     * 悬浮窗实时时间刷新：每 [TapMath.ClockFormat.TICK_MS]（100ms）跑一次。
     *
     * 只更新悬浮球上的时间行，不动其他状态行——避免每秒 10 次全量重绘造成卡顿。
     * 百毫秒位取的是截断值（ms/100），与 100ms 周期对齐，因此每秒稳定跳 10 次。
     */
    private val timeTicker = object : Runnable {
        override fun run() {
            updateClockText()
            handler.postDelayed(this, TapMath.ClockFormat.TICK_MS)
        }
    }

    /**
     * 重绘悬浮球的时间行。时间源不可用（如北京时间尚未对时成功）时显示真实状态，
     * 不伪造数值。
     */
    private fun updateClockText() {
        if (tv == null) return
        val a = axis
        val base = TimeSources.now(a.kind)
        val line = if (base == null) {
            a.kind.label + " 不可用（" + TimeSources.statusText() + "）"
        } else {
            // 显示与调度共用同一条轴：显示值 = 基准 + 微调，时区取该时间源的时区
            a.format(base)
        }
        handler.post {
            clockTv?.text = line
            // 时间源不可用时把原因也显示出来，避免「看起来正常但没在走」
            clockNote?.text = if (base == null) TimeSources.statusText() else ""
            clockNote?.visibility = if (base == null) View.VISIBLE else View.GONE
            // P2 / BUG-3：面板若开着，时间行与微调行随同一个 100ms 心跳一起刷新，不再静止
            panelTimeInfo?.text = timeInfoText()
            panelOffsetInfo?.text = offsetInfoText()
        }
    }

    /** 每小时重新对时一次，避免长期运行后漂移。 */
    private val resyncTicker = object : Runnable {
        override fun run() {
            if (axis.kind == TapMath.TimeSourceKind.BEIJING &&
                TimeSources.needsResync(System.currentTimeMillis(), 60L * 60L * 1000L)
            ) {
                TimeSources.syncAsync(applicationContext, "每小时自动对时")
            }
            handler.postDelayed(this, 60_000L)
        }
    }

    /**
     * 「下次执行」倒计时刷新：每 **1 秒**一跳。
     *
     * 修复说明：倒计时的**数值**本来由 Scheduler 每 200ms 重算（nextFireLabel），
     * 但**显示**只在 refreshBubbleStatus() 里更新，而它过去只被 2 秒的 watchdog 触发，
     * 所以肉眼看到的是「约 2 秒跳一次」。这里单独加一个 1 秒的刷新，
     * 让倒计时稳定每秒减 1，同时不动 watchdog 的职责（它管的是悬浮窗丢失恢复）。
     */
    private val nextTicker = object : Runnable {
        override fun run() {
            refreshBubbleStatus()
            handler.postDelayed(this, 1000L)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (overlayEnabled && bubble == null) {
                LogBus.add(applicationContext, "WARN", "检测到悬浮窗丢失，正在恢复")
                ensureOverlay()
            }
            TapperPlugin.emitState(this@OverlayService)
            handler.postDelayed(this, 2000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // 必须在 onCreate 就初始化：取点/移除悬浮窗都依赖它，不能等到 ensureOverlay() 才赋值
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val f1 = IntentFilter(LogBus.ACTION_CMD)
        val f2 = IntentFilter(LogBus.ACTION_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cmdReceiver, f1, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(stateReceiver, f2, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(cmdReceiver, f1)
            registerReceiver(stateReceiver, f2)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        reloadConfig()
        ensureOverlay()
        refreshBubbleStatus()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
        // 100ms 时间刷新 + 每小时重新对时（重复 post 前先移除，避免多次 onStartCommand 叠加）
        handler.removeCallbacks(timeTicker)
        handler.post(timeTicker)
        // 倒计时 1 秒一跳（与 100ms 的时钟刷新分开，互不影响）
        handler.removeCallbacks(nextTicker)
        handler.post(nextTicker)
        handler.removeCallbacks(resyncTicker)
        handler.post(resyncTicker)
        // 北京时间：进入服务时对时一次（同步策略见 TimeSources 注释）
        if (axis.kind == TapMath.TimeSourceKind.BEIJING) {
            TimeSources.syncAsync(applicationContext, "服务启动")
        }
        TapperPlugin.emitState(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(timeTicker)
        handler.removeCallbacks(nextTicker)
        handler.removeCallbacks(resyncTicker)
        try { unregisterReceiver(cmdReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(stateReceiver) } catch (_: Exception) {}
        closePanel()
        closePicker()
        closeTestPicker()
        pendingSteps = emptyList()
        removeOverlay()
        schedulerRef?.stop()
        schedulerRef = null
        LogBus.add(applicationContext, "WARN", "前台服务已停止，定时调度结束")
        super.onDestroy()
    }

    // ---------------- 配置 ----------------

    private fun reloadConfig() {
        val cfg = Prefs.load(this)
        // 时间轴随配置刷新（切换后立即生效，无需重建悬浮窗）；显示与调度共用这一条轴
        axis = TimeAxis.axisOf(cfg)
        updateClockText()
        // 只维护唯一一个 Scheduler 实例，避免「显示的下次触发」与实际调度不是同一个对象
        val s = schedulerRef ?: Scheduler(this).also { schedulerRef = it }
        s.update(cfg)
        s.start(cfg)
        LogBus.add(applicationContext, "OK", "配置已加载：共 " + cfg.points.size + " 个时间点")
    }

    // ---------------- 前台服务通知 ----------------

    private fun startForegroundCompat() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "tapper_fg"
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(chId, "定时连点器", NotificationManager.IMPORTANCE_LOW)
            ch.description = "保持定时调度不被系统回收"
            nm.createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, chId) else Notification.Builder(this)
        val n = builder
            .setContentTitle("定时连点器运行中")
            .setContentText("悬浮窗与定时调度已就绪")
            .setSmallIcon(R.drawable.ic_tapper)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------- 悬浮窗 ----------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun ensureOverlay() {
        if (bubble != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            LogBus.add(applicationContext, "ERROR", "缺少悬浮窗权限，无法显示悬浮窗（请到系统设置授予「显示在其他应用上层」）")
            TapperPlugin.emitState(this)
            return
        }
        val root = FrameLayout(this)
        // 悬浮球改成两行：上行=实时时间（分:秒:百毫秒），下行=原有状态文字。
        // 时间行独立成 View，100ms 只重绘它，不影响下行状态文字。
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#E6212121"))
        bg.cornerRadius = dp(14).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#FFB300"))
        box.background = bg
        box.setPadding(dp(12), dp(8), dp(12), dp(8))

        val clock = TextView(this).apply {
            setTextColor(Color.parseColor("#FFCA28"))
            textSize = 16f
            typeface = android.graphics.Typeface.MONOSPACE
            text = "00:00:00:0"
        }
        box.addView(clock)
        clockTv = clock

        val note = TextView(this).apply {
            setTextColor(Color.parseColor("#EF5350"))
            textSize = 9f
            visibility = View.GONE
        }
        box.addView(note)
        clockNote = note

        val label = TextView(this)
        label.setTextColor(Color.WHITE)
        label.textSize = 11f
        // 创建/重建时读取**当前**真实状态，而不是写死「待机」（否则重建后会残留旧值）
        label.text = TapMath.BubbleStatus.text(
            pickMode = pickMode,
            running = SequenceRunner.isRunning(),
            a11y = TapperAccessibilityService.isReady(),
            next = schedulerRef?.nextFireInfo(),
        )
        box.addView(label)
        root.addView(box)
        tv = label

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(16)
        lp.y = dp(200)

        root.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startX = 0
            private var startY = 0
            private var moved = false
            private var longFired = false
            private val longPress = Runnable {
                longFired = true
                overlayEnabled = false
                removeOverlay()
            }
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY
                        startX = lp.x; startY = lp.y
                        moved = false
                        longFired = false
                        handler.postDelayed(longPress, 700L)
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (abs(dx) > dp(8) || abs(dy) > dp(8)) {
                            moved = true
                            handler.removeCallbacks(longPress)
                        }
                        if (moved) {
                            val dm = resources.displayMetrics
                            lp.x = (startX + dx.toInt()).coerceIn(0, (dm.widthPixels - dp(40)).coerceAtLeast(0))
                            lp.y = (startY + dy.toInt()).coerceIn(0, (dm.heightPixels - dp(40)).coerceAtLeast(0))
                            try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasLong = longFired
                        handler.removeCallbacks(longPress)
                        if (e.actionMasked == MotionEvent.ACTION_UP) {
                            // 阶段五：单击不再直接进入取点，而是展开/收起设置面板
                            // 设置面板与选点面板任一展开时，单击都视为「收起」
                            when (TapMath.BubbleGesture.decide(moved, wasLong, panel != null || picker != null || testPicker != null)) {
                                TapMath.BubbleGesture.ACTION_OPEN_PANEL -> openPanel()
                                TapMath.BubbleGesture.ACTION_CLOSE_PANEL -> { closePanel(); closePicker(); closeTestPicker() }
                                TapMath.BubbleGesture.ACTION_HIDE -> { overlayEnabled = false; removeOverlay() }
                                // ACTION_DRAG：仅移动，不触发其它动作
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        try {
            wm.addView(root, lp)
            bubble = root
            bubbleLp = lp
            LogBus.add(applicationContext, "OK", "悬浮窗已显示（可拖动，单击=展开设置，长按=隐藏）")
        } catch (t: Throwable) {
            LogBus.add(applicationContext, "ERROR", "悬浮窗添加失败: " + t)
        }
        TapperPlugin.emitState(this)
    }

    private fun removeOverlay() {
        val v = bubble ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        bubble = null
        tv = null
        clockTv = null
        clockNote = null
        LogBus.add(applicationContext, "WARN", "悬浮窗已隐藏")
        TapperPlugin.emitState(this)
    }

    private fun setBubbleText(s: String) {
        handler.post { tv?.text = s }
    }

    /**
     * 按**实时**状态重绘悬浮球：无障碍是否已连接、序列是否在执行、取点模式是否进行中、
     * 以及下一次触发时刻（[Scheduler.nextFireInfo] 的真实值，空时显示真实语义的空状态）。
     * 悬浮球尚未创建时（tv == null）直接返回，不缓存、不残留旧值。
     */
    private fun refreshBubbleStatus() {
        val next = schedulerRef?.nextFireInfo()
        setBubbleText(
            TapMath.BubbleStatus.text(
                pickMode = pickMode,
                running = SequenceRunner.isRunning(),
                a11y = TapperAccessibilityService.isReady(),
                next = next,
            )
        )
        // 面板里的「下次触发」同样接真实数据源。
        // 修复前这里是 panelNext?.text = "下次触发: " + next，而 next 恒为 "-"，
        // 等于每 2 秒把面板里正确的值覆盖成「下次触发: -」。
        handler.post { panelNext?.text = "下次触发: " + TapMath.BubbleStatus.nextLine(next) }
    }

    // ---------------- 设置面板（阶段五新增） ----------------

    /** 展开/收起设置面板。 */
    private fun togglePanel() {
        if (panel != null) closePanel() else openPanel()
    }

    /**
     * 在悬浮窗内展开一个可操作的设置面板：
     * 取点、显示/隐藏悬浮窗、刷新配置、打开系统设置、中止执行。
     * 这样「点击悬浮窗」即可直接进入操作，不再必须回主界面。
     */
    private fun openPanel() {
        if (panel != null) return
        // 设置面板与选点/测试面板互斥，避免多层窗口叠在一起
        closePicker()
        closeTestPicker()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            LogBus.add(applicationContext, "ERROR", "缺少悬浮窗权限，无法展开设置面板")
            return
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2212121"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#FFB300"))
            }
        }
        val title = TextView(this).apply {
            text = "定时连点器"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(title)

        val nextTv = TextView(this).apply {
            text = "下次触发: " + (schedulerRef?.nextFireInfo() ?: "-")
            setTextColor(Color.parseColor("#BDBDBD"))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(6))
        }
        card.addView(nextTv)
        panelNext = nextTv

        // ---- 时间源与微调 ----
        // 两个 TextView 先建好再挂按钮：按钮回调里要立即刷新它们。
        val timeInfo = TextView(this).apply {
            text = timeInfoText()
            setTextColor(Color.parseColor("#FFCA28"))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(4))
        }
        card.addView(timeInfo)
        panelTimeInfo = timeInfo
        val offsetInfo = TextView(this).apply {
            text = offsetInfoText()
            setTextColor(Color.parseColor("#BDBDBD"))
            textSize = 11f
        }
        panelOffsetInfo = offsetInfo
        card.addView(makePanelButton("切换时间源（当前：" + axis.kind.label + "）") {
            val next = if (axis.kind == TapMath.TimeSourceKind.LOCAL)
                TapMath.TimeSourceKind.BEIJING else TapMath.TimeSourceKind.LOCAL
            timeAxisController.setSource(next)
            axis = TimeAxis.axisOf(Prefs.load(this))
            refreshTimeInfo(timeInfo, offsetInfo)
        })
        card.addView(makePanelButton("北京时间对时（" + TimeSources.statusText() + "）") {
            TimeSources.syncAsync(applicationContext, "面板手动触发")
            refreshTimeInfo(timeInfo, offsetInfo)
        })
        // 微调：固定 100ms 步长，可正可负，默认 0
        val offsetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // 微调目标值基于**当前轴**计算，经唯一入口写入（写盘 + 调度 + 通知 UI 一次完成）
        offsetRow.addView(makePanelButton("−100ms") {
            timeAxisController.setOffset(axis.offsetMs - TapMath.ClockFormat.STEP_MS)
            axis = TimeAxis.axisOf(Prefs.load(this))
            refreshTimeInfo(timeInfo, offsetInfo)
        })
        offsetRow.addView(makePanelButton("+100ms") {
            timeAxisController.setOffset(axis.offsetMs + TapMath.ClockFormat.STEP_MS)
            axis = TimeAxis.axisOf(Prefs.load(this))
            refreshTimeInfo(timeInfo, offsetInfo)
        })
        offsetRow.addView(makePanelButton("归零") {
            timeAxisController.setOffset(0L)
            axis = TimeAxis.axisOf(Prefs.load(this))
            refreshTimeInfo(timeInfo, offsetInfo)
        })
        card.addView(offsetRow)
        card.addView(offsetInfo)

        card.addView(makePanelButton("选取位置（取点）") { closePanel(); enterPickMode() })
        card.addView(makePanelButton("选点导入（多选时间点）") { openPicker() })
        card.addView(makePanelButton("测试时间点") { openTestPicker() })
        card.addView(makePanelButton("显示/隐藏悬浮窗") {
            if (overlayEnabled) { overlayEnabled = false; closePanel(); removeOverlay() }
            else { overlayEnabled = true; ensureOverlay() }
        })
        card.addView(makePanelButton("刷新配置") {
            reloadConfig()
            panelNext?.text = "下次触发: " + (schedulerRef?.nextFireInfo() ?: "-")
        })
        card.addView(makePanelButton("中止当前执行") {
            SequenceRunner.abort(this, "悬浮窗手动中止")
        })
        card.addView(makePanelButton("打开主界面") {
            try {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            } catch (t: Throwable) {
                LogBus.add(applicationContext, "ERROR", "打开主界面失败: " + t)
            }
        })
        card.addView(makePanelButton("收起面板") { closePanel() })

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // 面板出现在悬浮窗下方，避免遮挡悬浮窗本体
        val blp = bubbleLp
        lp.x = ((blp?.x ?: dp(16))).coerceAtLeast(0)
        lp.y = ((blp?.y ?: dp(200)) + dp(56)).coerceAtLeast(0)

        try {
            wm.addView(card, lp)
            panel = card
            panelLp = lp
            LogBus.add(applicationContext, "OK", "已展开悬浮窗设置面板（点击悬浮窗可收起）")
            sendBroadcast(Intent(LogBus.ACTION_PANEL).setPackage(packageName).putExtra("open", true))
        } catch (t: Throwable) {
            LogBus.add(applicationContext, "ERROR", "设置面板添加失败: " + t)
            panelNext = null
        }
    }

    private fun closePanel() {
        val v = panel ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        panel = null
        panelLp = null
        panelNext = null
        panelTimeInfo = null
        panelOffsetInfo = null
        sendBroadcast(Intent(LogBus.ACTION_PANEL).setPackage(packageName).putExtra("open", false))
    }

    private fun makePanelButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#FFCA28"))
            textSize = 12f
            setPadding(dp(6), dp(9), dp(6), dp(9))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    // ---------------- 测试时间点面板（悬浮窗内单选 + 确认后立即执行） ----------------

    /**
     * 在悬浮窗内展开「测试时间点」面板：列出全部时间点，**单选**后点「确认执行」，
     * 立即按该时间点自己的 [Step] 步骤序列开始模拟点击（复用 [SequenceRunner.run]）。
     *
     * 交互（沿用选点面板的卡片样式、按钮配色与「取消」位置，不另起一套样式）：
     * - 行内点击：选中该时间点（再点一次取消选择）
     * - 「确认执行」：从该时间点开始执行；未选择或该点没有步骤时**不执行**，只记日志
     * - 「取消」：关闭面板，不产生任何点击
     */
    private fun openTestPicker() {
        if (testPicker != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            LogBus.add(applicationContext, "ERROR", "缺少悬浮窗权限，无法展开测试时间点面板")
            return
        }
        val cfg = Prefs.load(this)
        if (cfg.points.isEmpty()) {
            LogBus.add(applicationContext, "WARN", "还没有时间点，无法测试（请先在主界面添加）")
            return
        }
        closePanel()
        closePicker()
        testSelected = null
        testTotal = cfg.points.size

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2212121"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#FFB300"))
            }
        }
        val title = TextView(this).apply {
            text = "测试时间点"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(title)
        testTitle = title

        val hint = TextView(this).apply {
            text = "选择要测试的时间点"
            setTextColor(Color.parseColor("#BDBDBD"))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(6))
        }
        card.addView(hint)
        testHint = hint

        val rows = ArrayList<TextView>(cfg.points.size)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        // 与「选点导入」面板同样的分组呈现，数据同源（Prefs/Config 的 groups）
        for (g in cfg.groups) {
            val members = cfg.points.filter { it.groupId == g.id }
            listBox.addView(makeGroupHeader(g.name, members.size))
            for (p in members) {
                val tv = TextView(this).apply {
                    tag = p.id
                    setTextColor(Color.parseColor("#FFCA28"))
                    textSize = 12f
                    setPadding(dp(10), dp(9), dp(6), dp(9))
                    isClickable = true
                    setOnClickListener {
                        testSelected = TapMath.TestTarget.select(testSelected, p.id)
                        refreshTestUi(cfg)
                    }
                }
                rows.add(tv)
                listBox.addView(tv)
            }
        }
        testRows = rows

        val listH = ((cfg.points.size + cfg.groups.size) * dp(34) + dp(8)).coerceAtMost(dp(240))
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(listBox)
        }
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listH))
        refreshTestUi(cfg)

        card.addView(makePanelButton("确认执行") { confirmTestRun() })
        card.addView(makePanelButton("取消") { closeTestPicker() })

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val w = dp(268)
        val lp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val blp = bubbleLp
        lp.x = ((blp?.x ?: dp(16))).coerceIn(0, (resources.displayMetrics.widthPixels - w).coerceAtLeast(0))
        lp.y = ((blp?.y ?: dp(200)) + dp(56)).coerceAtLeast(0)

        try {
            wm.addView(card, lp)
            testPicker = card
            testPickerLp = lp
            LogBus.add(applicationContext, "OK", "已展开测试时间点面板：共 " + cfg.points.size + " 个时间点")
        } catch (t: Throwable) {
            LogBus.add(applicationContext, "ERROR", "测试时间点面板添加失败: " + t)
            testTitle = null
            testHint = null
            testRows = emptyList()
        }
    }

    /** 刷新选中态：选中项加「▶ 」前缀并高亮，同时更新标题与提示。 */
    private fun refreshTestUi(cfg: Config) {
        for (r in testRows) {
            val p = cfg.points.firstOrNull { it.id == (r.tag as String) }
            if (p == null) continue
            val on = p.id == testSelected
            r.text = (if (on) "▶ " else "") + p.label() + "（" + p.steps.size + " 步）"
            r.setTextColor(Color.parseColor(if (on) "#FFFFFF" else "#FFCA28"))
        }
        testTitle?.text = TapMath.TestTarget.title(if (testSelected == null) 0 else 1, testTotal)
        testHint?.text = if (testSelected == null)
            "选择要测试的时间点"
        else
            "已选：" + (TapMath.TestTarget.resolve(cfg, testSelected)?.label() ?: "-") + "，点「确认执行」立即按步骤模拟点击"
    }

    /**
     * 确认执行：按**所选时间点自己的步骤**立即开始模拟点击。
     *
     * 复用既有执行入口 [SequenceRunner.run]，不新增任何执行逻辑；
     * 基准时刻取当前时刻（与主界面「立即执行一次」一致）。
     * 未选择 / 时间点已不存在 / 该点没有步骤 -> [TapMath.TestTarget.blockReason] 给出原因，**不执行**。
     */
    private fun confirmTestRun() {
        val cfg = Prefs.load(this)
        val plan = TapMath.TestTarget.plan(cfg, testSelected)
        if (plan == null) {
            LogBus.add(
                applicationContext, "WARN",
                "测试时间点未执行：" + TapMath.TestTarget.blockReason(cfg, testSelected)
            )
            closeTestPicker()
            return
        }
        if (!TapperAccessibilityService.isReady()) {
            LogBus.add(applicationContext, "ERROR", "无障碍服务未连接，无法测试 " + plan.label + "；请到系统设置中开启本应用的无障碍服务")
            closeTestPicker()
            return
        }
        LogBus.add(
            applicationContext, "TEST",
            "测试时间点：" + plan.label + " ｜ 共 " + plan.steps.size + " 步 ｜ 从该时间点开始立即执行"
        )
        // 复用既有执行入口，参数完全来自 plan（未新增执行逻辑）
        SequenceRunner.run(
            this, plan.label, plan.steps, plan.tapDurationMs, System.currentTimeMillis(),
            axis.kind, axis.offsetMs,
        )
        closeTestPicker()
    }

    private fun closeTestPicker() {
        val v = testPicker ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        testPicker = null
        testPickerLp = null
        testTitle = null
        testHint = null
        testRows = emptyList()
        testSelected = null
        testTotal = 0
    }

    // ---------------- 选点导入面板（悬浮窗内多选时间点） ----------------

    /**
     * 在悬浮窗内展开「选点导入」面板：列出全部时间点，逐行复选框多选。
     *
     * 交互（复用设置面板的 makePanelButton 范式与配色，不另起一套样式）：
     * - 行内复选框：切换该时间点的选中状态
     * - 「全选 / 取消选择」：一键全选或清空（文案随状态切换）
     * - 「确认导入」：把本次取到的坐标追加到每个已勾选的时间点
     * - 「取消」：关闭面板，不产生任何写入
     */
    private fun openPicker() {
        if (picker != null) return
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            LogBus.add(applicationContext, "ERROR", "缺少悬浮窗权限，无法展开选点面板")
            return
        }
        val cfg = Prefs.load(this)
        if (cfg.points.isEmpty()) {
            LogBus.add(applicationContext, "WARN", "还没有时间点，无法选点导入（请先在主界面添加）")
            return
        }
        val steps = pendingSteps
        if (steps.isEmpty()) {
            LogBus.add(applicationContext, "WARN", "还没有取到坐标：请先点「选取位置（取点）」")
            return
        }
        closePanel()
        closeTestPicker()
        pickerSelected = LinkedHashSet()
        pickerTotal = cfg.points.size

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2212121"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor("#FFB300"))
            }
        }
        val title = TextView(this).apply {
            text = "选点导入"
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        card.addView(title)
        pickerTitle = title

        val info = TextView(this).apply {
            text = if (steps.size == 1)
                "导入坐标 (" + steps[0].x + ", " + steps[0].y + ")，勾选要导入的时间点"
            else
                "导入 " + steps.size + " 个步骤，勾选要导入的时间点"
            setTextColor(Color.parseColor("#BDBDBD"))
            textSize = 11f
            setPadding(0, dp(4), 0, dp(6))
        }
        card.addView(info)

        val toggleAll = makePanelButton("全选") {
            pickerSelected = if (pickerSelected.size >= pickerTotal)
                TapMath.PickerModel.clearSelection()
            else
                TapMath.PickerModel.selectAll(cfg.points.map { it.id })
            for (r in pickerRows) r.isChecked = pickerSelected.contains(r.tag as String)
            refreshPickerUi()
        }
        card.addView(toggleAll)
        pickerToggleAll = toggleAll

        // 逐行复选框：按分组归类展示。
        // 分组数据与主界面**同源**（都来自 Prefs/Config 的 groups 字段），不另存一份。
        val rows = ArrayList<CheckBox>(cfg.points.size)
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (g in cfg.groups) {
            val members = cfg.points.filter { it.groupId == g.id }
            listBox.addView(makeGroupHeader(g.name, members.size))
            for (p in members) {
                val cb = CheckBox(this).apply {
                    text = p.label() + "（" + p.steps.size + " 步）"
                    tag = p.id
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    buttonTintList = ColorStateList.valueOf(Color.parseColor("#FFCA28"))
                    setPadding(dp(8), dp(2), 0, dp(2))
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) pickerSelected.add(p.id) else pickerSelected.remove(p.id)
                        refreshPickerUi()
                    }
                }
                rows.add(cb)
                listBox.addView(cb)
            }
        }
        pickerRows = rows

        // 时间点多时列表可滚动，面板高度不超出屏幕（含分组标题行）
        val listH = ((cfg.points.size + cfg.groups.size) * dp(34) + dp(8)).coerceAtMost(dp(240))
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(listBox)
        }
        card.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listH))
        refreshPickerUi()

        card.addView(makePanelButton("确认导入") { confirmPickerImport() })
        card.addView(makePanelButton("取消") { closePicker() })

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val w = dp(268)
        val lp = WindowManager.LayoutParams(
            w,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        // 出现在悬浮窗下方，并夹取在屏幕内（宽度固定，避免拖到右边缘时超出屏幕）
        val blp = bubbleLp
        lp.x = ((blp?.x ?: dp(16))).coerceIn(0, (resources.displayMetrics.widthPixels - w).coerceAtLeast(0))
        lp.y = ((blp?.y ?: dp(200)) + dp(56)).coerceAtLeast(0)

        try {
            wm.addView(card, lp)
            picker = card
            pickerLp = lp
            LogBus.add(
                applicationContext, "OK",
                "已展开选点面板：共 " + cfg.points.size + " 个时间点，待导入坐标 (" + steps[0].x + ", " + steps[0].y + ")"
            )
        } catch (t: Throwable) {
            LogBus.add(applicationContext, "ERROR", "选点面板添加失败: " + t)
            pickerTitle = null
            pickerToggleAll = null
            pickerRows = emptyList()
        }
    }

    /** 分组标题行：悬浮窗内两个选择面板共用，保证分组呈现一致。 */
    private fun makeGroupHeader(name: String, count: Int): TextView {
        return TextView(this).apply {
            text = name + "（" + count + "）"
            setTextColor(Color.parseColor("#FFB300"))
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), dp(6), dp(4), dp(2))
        }
    }

    /** 勾选状态变化后刷新标题与「全选 / 取消选择」文案。 */
    private fun refreshPickerUi() {
        pickerTitle?.text = TapMath.PickerModel.title(pickerSelected.size, pickerTotal)
        pickerToggleAll?.text = TapMath.PickerModel.toggleAllLabel(pickerTotal, pickerSelected.size)
    }

    /**
     * 确认导入：把本次取到的坐标步骤**追加**到每个已勾选的时间点（追加、不去重，与主界面现有行为一致），
     * 直接写 Prefs 并通知主界面刷新，因此 App 不在前台时也能生效。
     * 未勾选任何时间点时只记日志，不写入。
     */
    private fun confirmPickerImport() {
        if (pickerSelected.isEmpty()) {
            LogBus.add(applicationContext, "WARN", "选点导入未执行：没有勾选任何时间点")
            closePicker()
            return
        }
        val steps = pendingSteps
        if (steps.isEmpty()) {
            LogBus.add(applicationContext, "WARN", "选点导入未执行：没有可用坐标")
            closePicker()
            return
        }
        val next = TapMath.PickerModel.importTo(Prefs.load(this), pickerSelected, steps)
        Prefs.save(this, next)
        reloadConfig()
        val detail = next.points.filter { pickerSelected.contains(it.id) }
            .joinToString("、") { it.label() + "(" + it.steps.size + "步)" }
        LogBus.add(
            applicationContext, "IMPORT",
            "选点导入 " + steps.size + " 个步骤 -> " + pickerSelected.size + " 个时间点：" + detail
        )
        sendBroadcast(Intent(LogBus.ACTION_CONFIG).setPackage(packageName))
        closePicker()
    }

    // ---------------- 时间源与微调（P0：收敛到唯一入口） ----------------

    /**
     * 面板里的时间信息摘要（当前时间源、显示值、对时状态、微调）。
     * 显示值同样走 [TimeAxis]，与调度轴同源，不可能各算各的。
     */
    private fun timeInfoText(): String {
        val a = axis
        // 显示值与时钟行同源（TimeAxis），面板与悬浮球不可能显示成两个时间
        val shown = SchedulerDisplay.panelClockLine(a, TimeSources.now(a.kind))
        return "时间源：" + a.kind.label + " ｜ 显示 " + shown + "\n" + offsetInfoText() +
            " ｜ " + TimeSources.statusText()
    }

    /** 面板里的微调摘要（单一来源：当前时间轴）。 */
    private fun offsetInfoText(): String =
        "微调 " + (if (axis.offsetMs >= 0) "+" else "") + axis.offsetMs + "ms"

    /** 变更后立即刷新面板两行文案（按钮回调用）。 */
    private fun refreshTimeInfo(info: TextView?, offset: TextView?) {
        info?.text = timeInfoText()
        offset?.text = offsetInfoText()
    }

    private fun closePicker() {
        val v = picker ?: return
        try { wm.removeView(v) } catch (_: Exception) {}
        picker = null
        pickerLp = null
        pickerTitle = null
        pickerToggleAll = null
        pickerRows = emptyList()
        pickerSelected = LinkedHashSet()
        pickerTotal = 0
    }

    // ---------------- 取点模式 ----------------

    private fun enterPickMode() {
        if (pickMode) return
        // 新一次取点重新开始：清掉上一次尚未导入的坐标，避免误导入旧点
        pendingSteps = emptyList()
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            LogBus.add(applicationContext, "ERROR", "缺少悬浮窗权限，无法进入取点模式")
            return
        }
        pickMode = true
        val dm = resources.displayMetrics
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            dm.widthPixels, dm.heightPixels, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        val cv = CatcherView()
        try {
            wm.addView(cv, lp)
            catcher = cv
            setBubbleText(TapMath.BubbleStatus.PICKING)
            LogBus.add(applicationContext, "PICK", "已进入选取位置模式，请点击目标位置")
            sendBroadcast(Intent(LogBus.ACTION_PICK_MODE).setPackage(packageName).putExtra("active", true))
        } catch (t: Throwable) {
            pickMode = false
            LogBus.add(applicationContext, "ERROR", "取点层添加失败: " + t)
        }
    }

    private fun exitPickMode() {
        pickMode = false
        catcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        catcher = null
        // 退出取点后按真实状态恢复（无障碍可能仍是关闭的），而不是写死「待机」
        refreshBubbleStatus()
        sendBroadcast(Intent(LogBus.ACTION_PICK_MODE).setPackage(packageName).putExtra("active", false))
        TapperPlugin.emitState(this)
    }

    /** 全屏透明取点层：按下即记录该点屏幕坐标，并画十字标记回显。 */
    private inner class CatcherView : View(this@OverlayService) {
        private var px = -1f
        private var py = -1f
        private val ring = Paint().apply {
            color = Color.parseColor("#FF1744"); strokeWidth = 6f; style = Paint.Style.STROKE; isAntiAlias = true
        }
        private val fill = Paint().apply { color = Color.parseColor("#33FF1744"); style = Paint.Style.FILL }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (px >= 0f) {
                canvas.drawCircle(px, py, dp(26).toFloat(), fill)
                canvas.drawCircle(px, py, dp(26).toFloat(), ring)
                canvas.drawLine(px - dp(46), py, px + dp(46), py, ring)
                canvas.drawLine(px, py - dp(46), px, py + dp(46), ring)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                px = e.x; py = e.y
                invalidate()
                val sx = e.rawX.toInt()
                val sy = e.rawY.toInt()
                val dm = resources.displayMetrics
                LogBus.add(
                    applicationContext, "PICK",
                    "记录坐标(" + sx + "," + sy + ") 当前屏幕 " + dm.widthPixels + "x" + dm.heightPixels
                )
                sendBroadcast(
                    Intent(LogBus.ACTION_PICK).setPackage(packageName)
                        .putExtra("x", sx).putExtra("y", sy)
                        .putExtra("sw", dm.widthPixels).putExtra("sh", dm.heightPixels)
                )
                // 记下本次取点，供悬浮窗「选点导入」使用（点击悬浮窗 → 选点导入）
                pendingSteps = listOf(Step(sx, sy, 200L, dm.widthPixels, dm.heightPixels))
                handler.postDelayed({ exitPickMode() }, 450L)
                return true
            }
            return true
        }
    }

    companion object {
        private const val NOTIF_ID = 0x5401

        @Volatile
        var schedulerRef: Scheduler? = null
            private set
    }
}
