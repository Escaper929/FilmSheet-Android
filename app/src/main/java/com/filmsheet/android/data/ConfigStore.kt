package com.filmsheet.android.data

import android.content.Context
import android.content.SharedPreferences
import com.filmsheet.android.config.SheetConfig
import com.filmsheet.android.core.ConfigCodec

/**
 * 配置存储 — 把 [ConfigCodec] 编出来的键值对落到 [SharedPreferences]。
 *
 * ## 为什么用 SharedPreferences 而不是 DataStore
 *
 * 配置是**单条记录**（几十个字段的扁平映射），不是数据库。DataStore 的优势
 * 在于流式读写与协程安全，那是给「大量记录」用的场景；这里用它反而要引入
 * 一套 Flow 管道、protobuf 或额外依赖，收益为零。几十个字符串键值对，
 * `SharedPreferences.apply()` 一次写入足够。
 *
 * 编解码在 [ConfigCodec]（纯逻辑），这里只管存取 —— 所以编解码能被
 * 纯 JVM 单测覆盖，而这一层只有「能不能写进去」这类需要设备才能验的东西。
 */
class ConfigStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    /** 读取配置。首次启动（无记录）时返回默认值。 */
    fun load(): SheetConfig {
        val map = prefs.all.entries.associate { (k, v) -> k to v.toString() }
        // 读出来一律清洗一次：偏好文件可能来自旧版本，字段组合未必合法
        return ConfigCodec.decode(map).sanitized()
    }

    /**
     * 保存配置。
     *
     * `apply()` 而非 `commit()`：它异步落盘、不阻塞调用方，而配置丢失一两次
     * 没有代价（下次启动回到默认即可），卡顿才有代价。
     */
    fun save(config: SheetConfig) {
        val encoded = ConfigCodec.encode(config)
        prefs.edit().apply {
            // 先清掉不在本次编码结果里的键，避免删掉的字段留下残值。
            // 尤其重要：信息字段是逐个键存的，用户清空「卷号」后那个键必须消失，
            // 否则下次恢复时又冒出来。
            prefs.all.keys.filter { it !in encoded }.forEach { remove(it) }
            encoded.forEach { (k, v) -> putString(k, v) }
        }.apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val NAME = "filmsheet_config"
    }
}
