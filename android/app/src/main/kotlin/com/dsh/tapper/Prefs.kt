package com.dsh.tapper

import android.content.Context

/** 配置持久化：写入 SharedPreferences，进程/设备重启后仍生效。 */
object Prefs {
    private const val FILE = "scheduled_tapper"
    private const val KEY_CONFIG = "config_json"

    @Synchronized
    fun load(ctx: Context): Config =
        Config.fromJson(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_CONFIG, null))

    @Synchronized
    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONFIG, cfg.toJson().toString())
            .apply()
    }
}
