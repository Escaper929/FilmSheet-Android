package com.filmsheet.android.core

/**
 * SplitMix64 — 移植自 iOS 端 `SeededRNG`（桌面端用 Python 梅森旋转）。
 *
 * 桌面端用墙钟时间播种发生器，所以每次渲染出来的片边印字落点都不一样 ——
 * 真实胶片就是这样的重复印字，规规矩矩排成网格反而假。
 *
 * 这里用一个足够小、**给定种子就确定可复现**的生成器做同样的事。
 * 严格由种子决定、不掺任何全局状态，是它存在的全部意义：
 * 同一张照片重复渲染必须得到同一版式，否则没法对照排查。
 *
 * 放在 `core` 而不是 `render` 是有意的：这是纯数学，不依赖任何图形 API，
 * 所以能被纯 JVM 单测覆盖（见 `SeededRNGTests`）。渲染层里混着
 * `android.graphics` 与 Compose 类型，整个包就只能靠 SDK 才能测。
 */
class SeededRNG(seed: Long) {
    private var state: Long = seed

    private fun next(): Long {
        state += -0x61c8864680b583ebL          // 0x9E3779B97F4A7C15
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L  // 0xBF58476D1CE4E5B9
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L  // 0x94D049BB133111EB
        return z xor (z ushr 31)
    }

    /** [IntRange] 闭区间内取值。上下界颠倒或相等时返回下界，不抛异常。 */
    fun int(range: IntRange): Int {
        if (range.last <= range.first) return range.first
        val span = (range.last - range.first + 1).toULong()
        return range.first + (next().toULong() % span).toInt()
    }

    /** 从若干候选里等概率选一个。 */
    fun next(vararg choices: Int): Int = choices[int(0 until choices.size)]
}
