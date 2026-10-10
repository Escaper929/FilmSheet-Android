package com.filmsheet.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行计划测试 — 与 iOS 端 `RowPlanTests.swift` 一一对应。
 *
 * 这组测试的存在是因为一个真实缺陷：iOS 端原先用互递归算行索引，
 * 复杂度斐波那契型，200 张照片需要约 8.6×10^10 次调用，渲染永不完成。
 * 修法是前缀和。行为必须完全一致 —— 「更快但结果不同」会悄悄重排每一张拼版。
 */
class RowPlanTests {

    // MARK: - 135 的片头引带

    @Test
    fun `135 首行跳过两个引带位`() {
        // 6 列 6 张：首行只能放 4 张（两个位置是引带），次行放剩下 2 张。
        val plan = RowPlan.for135(photoCount = 6, cols = 6)
        assertEquals(listOf(4, 2), (0 until plan.rows).map { plan.countInRow(it) })
    }

    /** 桌面端的 bug：按列数推进会让一整卷 72 张静默丢掉最后两张。 */
    @Test
    fun `整卷不会丢帧`() {
        val plan = RowPlan.for135(photoCount = 72, cols = 6)
        val placed = (0 until plan.rows).sumOf { plan.countInRow(it) }
        assertEquals(72, placed)
    }

    @Test
    fun `单列不因引带而空渲染`() {
        // 没有 cols > 1 的守卫时 `2..<1` 为空，会什么都不渲染。
        val plan = RowPlan.for135(photoCount = 1, cols = 1)
        assertEquals(1, plan.rows)
        assertEquals(0, plan.firstImageIndex(0))
        assertEquals(1, plan.countInRow(0))
    }

    // MARK: - 120 没有打孔引带

    @Test
    fun `120 每行从第 0 列开始`() {
        // Renderer120.firstColumn 覆写存在的理由：沿用 135 的引带会让
        // 第 1 行重复第 0 行的照片。
        val plan = RowPlan.for120(photoCount = 6, cols = 3)
        assertEquals(listOf(3, 3), (0 until plan.rows).map { plan.countInRow(it) })
        assertEquals(listOf(0, 3), (0 until plan.rows).map { plan.firstImageIndex(it) })
    }

    @Test
    fun `120 行首列恒为零`() {
        for (row in 0..5) {
            assertEquals(0, RowPlan.firstColumn120(row))
        }
    }

    // MARK: - 不变量

    /** 帧必须连续、无重复、无遗漏 —— placeImages 只单向遍历 imgIdx。 */
    @Test
    fun `帧连续且不重复`() {
        for ((name, plan) in plans()) {
            val flat = plan.flattenedFrames()
            // 135 单列是**单张模式**：整张拼版只出第一张，其余照片由调用方
            // 逐张单独渲染（见 FilmSheetPipeline.singlePhotoMode 分支），
            // 所以这里期望 1 而不是 photoCount。
            val expectedCount = if (
                plan.cols == 1 &&
                name.startsWith("135") &&
                plan.photoCount > 0
            ) 1 else plan.photoCount

            assertEquals(name, (0 until expectedCount).toList(), flat)
            // 任何情况下都不能有重复或负数下标。
            assertTrue(name, flat.all { it >= 0 })
            assertEquals(name, flat.distinct().size, flat.size)
        }
    }

    /** 120 单列排整卷，每张各占一行，帧必须全部放下。 */
    @Test
    fun `120 单列排下全部帧`() {
        for (n in listOf(1, 5, 6, 7, 40)) {
            val plan = RowPlan.for120(photoCount = n, cols = 1)
            assertEquals(n, plan.flattenedFrames().size)
            assertEquals((0 until n).toList(), plan.flattenedFrames())
        }
    }

    /** 行计划整体必须是 O(rows)，不是递归。 */
    @Test
    fun `大选择也保持线性`() {
        val plan = RowPlan.for135(photoCount = 200, cols = 6)
        val placed = (0 until plan.rows).sumOf { plan.countInRow(it) }
        assertEquals(200, placed)

        val start = System.nanoTime()
        val rebuilt = RowPlan.for135(photoCount = 200, cols = 6)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertEquals(34, rebuilt.rows)
        assertTrue("行计划必须线性，实际 ${elapsedMs}ms", elapsedMs < 50.0)
    }

    @Test
    fun `空选择产生空计划`() {
        for (plan in listOf(RowPlan.for135(0, 6), RowPlan.for120(0, 6))) {
            assertTrue((0 until plan.rows).all { plan.countInRow(it) == 0 })
            assertTrue(plan.flattenedFrames().isEmpty())
        }
    }

    @Test
    fun `超出范围的行返回空`() {
        val plan = RowPlan.for135(photoCount = 6, cols = 6)
        assertEquals(6, plan.firstImageIndex(plan.rows))
        assertEquals(0, plan.countInRow(plan.rows))
        assertEquals(0, plan.countInRow(-1))
    }

    // MARK: - 行数公式

    @Test
    fun `135 行数把引带算进去`() {
        // 6 张 6 列仍需 2 行：首行让出两个引带位，只放得下 4 张。
        assertEquals(2, RowPlan.rowsFor135(photoCount = 6, cols = 6))
        assertEquals(2, RowPlan.rowsFor135(photoCount = 7, cols = 6))
        assertEquals(13, RowPlan.rowsFor135(photoCount = 72, cols = 6))
        assertEquals(1, RowPlan.rowsFor135(photoCount = 6, cols = 1))
    }

    @Test
    fun `120 行数不含引带`() {
        assertEquals(1, RowPlan.rowsFor120(photoCount = 6, cols = 6))
        assertEquals(2, RowPlan.rowsFor120(photoCount = 7, cols = 6))
        assertEquals(12, RowPlan.rowsFor120(photoCount = 72, cols = 6))
        assertEquals(6, RowPlan.rowsFor120(photoCount = 6, cols = 1))
        assertEquals(1, RowPlan.rowsFor120(photoCount = 0, cols = 6))
    }

    @Test
    fun `照片数少于列数也能正确建表`() {
        val plan = RowPlan.for135(photoCount = 1, cols = 6)
        assertEquals(listOf(1), (0 until plan.rows).map { plan.countInRow(it) })
        assertEquals(listOf(0), (0 until plan.rows).map { plan.firstImageIndex(it) })
    }

    private fun plans(): List<Pair<String, RowPlan>> {
        val out = mutableListOf<Pair<String, RowPlan>>()
        for (format in listOf("135", "120")) {
            for (cols in listOf(1, 2, 3, 6)) {
                for (count in listOf(0, 1, 5, 6, 7, 13, 40, 72)) {
                    val plan = if (format == "135") {
                        RowPlan.for135(count, cols)
                    } else {
                        RowPlan.for120(count, cols)
                    }
                    out += "$format cols=$cols n=$count" to plan
                }
            }
        }
        return out
    }
}