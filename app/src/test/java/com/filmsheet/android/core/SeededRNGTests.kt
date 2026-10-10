

package com.filmsheet.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随机数发生器测试。
 *
 * SplitMix64 存在的唯一理由是**可复现**：桌面端每次渲染都重新播种，
 * 让片边印字换一批落点，看起来像真的实验室印片。但「看起来随机」和
 * 「同一张照片每次都排成同一个版式」之间的界限全在这个生成器的性质上 ——
 * 它必须严格由种子决定，不能掺任何全局状态。
 */
class SeededRNGTests {

    @Test
    fun `同一种子给出同一串数`() {
        val a = (1..20).map { SeededRNG(seed = 12345L).int(0..1000) }
        val b = (1..20).map { SeededRNG(seed = 12345L).int(0..1000) }
        assertEquals(a, b)
    }

    /** 逐次取值与「每次新建」不同 —— 前者推进状态，后者从同一起点重取。 */
    @Test
    fun `连续取值会推进状态`() {
        val rng = SeededRNG(seed = 999L)
        val first = rng.int(0..Int.MAX_VALUE)
        val second = rng.int(0..Int.MAX_VALUE)
        assertTrue("连续两次不该撞出同一个值", first != second)
    }

    @Test
    fun `不同种子给出不同序列`() {
        val a = SeededRNG(seed = 1L).int(0..Int.MAX_VALUE)
        val b = SeededRNG(seed = 2L).int(0..Int.MAX_VALUE)
        assertTrue(a != b)
    }

    /** 片边散排靠这个：个数只能是 2 / 3 / 4 之一。 */
    @Test
    fun `从候选里选出的值都在候选内`() {
        val rng = SeededRNG(seed = 42L)
        repeat(200) {
            assertTrue(rng.next(2, 3, 4) in listOf(2, 3, 4))
        }
    }

    @Test
    fun `取值始终落在闭区间内`() {
        val rng = SeededRNG(seed = 7L)
        repeat(1000) {
            val v = rng.int(-50..50)
            assertTrue("越界：$v", v in -50..50)
        }
    }

    @Test
    fun `单点区间返回该点`() {
        val rng = SeededRNG(seed = 3L)
        assertEquals(5, rng.int(5..5))
        // 上界不大于下界时也要有定义，不能抛异常
        assertEquals(9, rng.int(9..3))
    }

    /** 分布大致均匀 —— 粗糙但足以抓住明显的偏移错误。 */
    @Test
    fun `分布大致均匀`() {
        val rng = SeededRNG(seed = 2024L)
        val buckets = IntArray(4)
        repeat(4000) { buckets[rng.int(0..3)]++ }
        for (b in buckets) {
            assertTrue("分桶严重不均：${buckets.toList()}", b in 800..1200)
        }
    }
}
