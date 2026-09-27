package com.dsh.tapper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/** Flutter 与原生之间的桥：方法调用 + 日志/状态/取点三条事件流。 */
class TapperPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var ctx: Context
    private lateinit var method: MethodChannel
    private var logSink: EventChannel.EventSink? = null
    private var stateSink: EventChannel.EventSink? = null
    private var pickSink: EventChannel.EventSink? = null
    private var receiver: BroadcastReceiver? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        ctx = binding.applicationContext
        method = MethodChannel(binding.binaryMessenger, "scheduled_tapper/control")
        method.setMethodCallHandler(this)

        EventChannel(binding.binaryMessenger, "scheduled_tapper/logs").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(a: Any?, s: EventChannel.EventSink?) { logSink = s }
                override fun onCancel(a: Any?) { logSink = null }
            })
        EventChannel(binding.binaryMessenger, "scheduled_tapper/state").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(a: Any?, s: EventChannel.EventSink?) { stateSink = s }
                override fun onCancel(a: Any?) { stateSink = null }
            })
        EventChannel(binding.binaryMessenger, "scheduled_tapper/pick").setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(a: Any?, s: EventChannel.EventSink?) { pickSink = s }
                override fun onCancel(a: Any?) { pickSink = null }
            })

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    LogBus.ACTION_LOG -> logSink?.success(i.getStringExtra(LogBus.EXTRA_LINE) ?: "")
                    LogBus.ACTION_STATE -> stateSink?.success(currentState())
                    LogBus.ACTION_PICK -> {
                        val m = HashMap<String, Any>()
                        m["x"] = i.getIntExtra("x", 0)
                        m["y"] = i.getIntExtra("y", 0)
                        m["sw"] = i.getIntExtra("sw", 0)
                        m["sh"] = i.getIntExtra("sh", 0)
                        pickSink?.success(m)
                    }
                    LogBus.ACTION_PICK_MODE -> {
                        val m = HashMap<String, Any>()
                        m["active"] = i.getBooleanExtra("active", false)
                        pickSink?.success(m)
                    }
                    LogBus.ACTION_PANEL -> {
                        val m = HashMap<String, Any>()
                        m["panelOpen"] = i.getBooleanExtra("open", false)
                        pickSink?.success(m)
                    }
                    // 悬浮窗直接改写了配置（选点导入）-> 通知主界面重新读取
                    LogBus.ACTION_CONFIG -> {
                        val m = HashMap<String, Any>()
                        m["configChanged"] = true
                        pickSink?.success(m)
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(LogBus.ACTION_LOG)
            addAction(LogBus.ACTION_STATE)
            addAction(LogBus.ACTION_PICK)
            addAction(LogBus.ACTION_PICK_MODE)
            addAction(LogBus.ACTION_PANEL)
            addAction(LogBus.ACTION_CONFIG)
        }
        receiver = r
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, f)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        method.setMethodCallHandler(null)
        receiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
        logSink = null; stateSink = null; pickSink = null
    }

    private fun currentState(): HashMap<String, Any> {
        val m = HashMap<String, Any>()
        m["a11y"] = TapperAccessibilityService.isReady()
        m["a11ySettings"] = TapperAccessibilityService.isEnabledInSettings(ctx)
        m["overlay"] = Settings.canDrawOverlays(ctx)
        m["running"] = SequenceRunner.isRunning()
        m["next"] = OverlayService.schedulerRef?.nextFireInfo() ?: "-"
        m["tz"] = Scheduler.tzName()
        return m
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        try {
            when (call.method) {
                "getState" -> result.success(currentState())

                "getConfig" -> result.success(Prefs.load(ctx).toJson().toString())

                "saveConfig" -> {
                    val json = call.argument<String>("json") ?: "{}"
                    val cfg = Config.fromJson(json)
                    Prefs.save(ctx, cfg)
                    OverlayService.schedulerRef?.update(cfg)
                    startOverlayService()
                    result.success(true)
                }

                "startService" -> { startOverlayService(); result.success(true) }

                "showOverlay" -> { sendCmd("show"); result.success(true) }
                "hideOverlay" -> { sendCmd("hide"); result.success(true) }

                "enterPickMode" -> { startOverlayService(); sendCmd("pick"); result.success(true) }
                "togglePanel" -> { sendCmd("panel"); result.success(true) }
                "openPicker" -> { sendCmd("picker"); result.success(true) }
                "closePanel" -> { sendCmd("closePanel"); result.success(true) }
                "cancelPickMode" -> { sendCmd("cancelPick"); result.success(true) }

                "testTap" -> {
                    val x = call.argument<Int>("x") ?: 0
                    val y = call.argument<Int>("y") ?: 0
                    val svc = TapperAccessibilityService.instance
                    if (svc == null) {
                        LogBus.add(ctx, "ERROR", "无障碍服务未连接，测试点击无法执行")
                        result.success(false)
                    } else {
                        LogBus.add(ctx, "TEST", "测试点击(" + x + "," + y + ")")
                        result.success(svc.tap(x, y, 30L))
                    }
                }

                "runNow" -> {
                    val label = call.argument<String>("label") ?: "手动"
                    val cfg = Config.fromJson(call.argument<String>("json"))
                    val p = cfg.points.firstOrNull()
                    if (p == null || p.steps.isEmpty()) {
                        LogBus.add(ctx, "ERROR", "手动执行失败：没有可用步骤")
                        result.success(false)
                    } else {
                        SequenceRunner.run(
                            ctx, label, p.steps, cfg.tapDurationMs, System.currentTimeMillis(),
                            TapMath.TimeSourceKind.fromId(cfg.timeSource), cfg.timeOffsetMs,
                        )
                        result.success(true)
                    }
                }

                "abort" -> { SequenceRunner.abort(ctx, "用户手动中止"); result.success(true) }

                "openAccessibilitySettings" -> {
                    ctx.startActivity(
                        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    result.success(true)
                }

                "openOverlaySettings" -> {
                    ctx.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + ctx.packageName)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    result.success(true)
                }

                "getLogs" -> result.success(LogBus.text())
                "clearLog" -> { LogBus.clear(); result.success(true) }

                else -> result.notImplemented()
            }
        } catch (t: Throwable) {
            LogBus.add(ctx, "ERROR", "原生调用 " + call.method + " 异常: " + t)
            result.error("NATIVE_ERROR", t.message, null)
        }
    }

    private fun startOverlayService() {
        val i = Intent(ctx, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
    }

    private fun sendCmd(cmd: String) {
        startOverlayService()
        ctx.sendBroadcast(Intent(LogBus.ACTION_CMD).setPackage(ctx.packageName).putExtra("cmd", cmd))
    }

    companion object {
        /** 供无障碍服务/执行器主动推送状态（同一进程，静态引用即可）。 */
        fun emitState(ctx: Context) {
            try {
                ctx.sendBroadcast(Intent(LogBus.ACTION_STATE).setPackage(ctx.packageName))
            } catch (_: Exception) {
            }
        }
    }
}
