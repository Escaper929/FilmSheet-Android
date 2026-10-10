package com.filmsheet.android.core

/**
 * 行计划（Row Plan）— 排版中最容易出错的一处。
 *
 * 权威来源是 SPEC.md §4，iOS 端实现在 `SheetRenderer.swift` 的
 * `buildRowIndexCache`。两端必须给出完全相同的每行张数与起始帧号，
 * 否则同一卷胶片在两端排出来的版式会不一样。
 *
 * ## 为什么必须是前缀和
 *
 * 每行放几张取决于该行还剩多少帧，而剩余量又取决于前面所有行放了几张 ——
 * 依赖本质是顺序的。但**不能**写成
 * `firstImageIndex(row) = Σ countImages(r for r < row)` 且
 * `countImages(row)` 内部再调 `firstImageIndex(row)`：
 * 那是互递归且无记忆化，展开后 T(row) ≈ 2·T(row−1)，斐波那契型。
 * iOS 端曾因此需要约 8.6×10^10 次调用才能排完 200 张照片，渲染永不完成。
 *
 * 正确做法是一趟前向扫描，之后两个查询都是 O(1) 查表。
 *
 * @param photoCount 照片总数
 * @param cols 列数
 * @param rows 行数，由 [rowsFor] 算出
 * @param firstColumn 行首列。135 首行要跳两个片头引带位；120 恒为 0。
 */
class RowPlan private constructor(
    val photoCount: Int,
    val cols: Int,
    val rows: Int,
    private val starts: IntArray,
) {

    /** 该行实际放入的帧数。 */
    fun countInRow(row: Int): Int {
        if (row < 0 || row + 1 >= starts.size) return 0
        return starts[row + 1] - starts[row]
    }

    /** 该行第一帧在整卷中的全局下标。 */
    fun firstImageIndex(row: Int): Int =
        starts[row.coerceIn(0, starts.size - 1)]

    /** 把各行拼起来，必须恰好是 `0..<photoCount`，无重复无遗漏。 */
    fun flattenedFrames(): List<Int> {
        val out = ArrayList<Int>(photoCount)
        for (row in 0 until rows) {
            val start = starts[row]
            val n = countInRow(row)
            for (i in 0 until n) out.add(start + i)
        }
        return out
    }

    companion object {

        /**
         * 135 行数：`ceil((photoCount + 2) / cols)`。
         *
         * `+2` 把两个片头引带位算进行数，这样一整卷 72 张不会丢掉最后两张。
         * 单列时强制 1 行。
         */
        fun rowsFor135(photoCount: Int, cols: Int): Int {
            if (cols <= 1) return 1
            return ceilDiv(photoCount + 2, cols)
        }

        /** 120 行数：`max(1, ceil(photoCount / cols))`，无引带。 */
        fun rowsFor120(photoCount: Int, cols: Int): Int =
            maxOf(1, ceilDiv(photoCount, maxOf(1, cols)))

        /**
         * 135 行首列：首行跳过两个片头引带位。
         *
         * `cols > 1` 的守卫不可省 —— 单列时 `2..<1` 为空，会导致什么都不渲染。
         */
        fun firstColumn135(row: Int, cols: Int): Int =
            if (row == 0 && cols > 1) 2 else 0

        /** 120 行首列恒为 0：120 没有打孔引带。
         *
         * 这正是 iOS 端覆写 `firstColumn(forRow:)` 修掉的 bug：
         * 桌面端按 135 的引带算行首推进量，但 120 从第 0 列放图，
         * 于是第 2 行重复第 1 行、最后几张永不出现。
         */
        fun firstColumn120(@Suppress("UNUSED_PARAMETER") row: Int): Int = 0

        /** 一次性算出整张表的行起始索引。 */
        fun build(
            photoCount: Int,
            cols: Int,
            rows: Int,
            firstColumn: (Int) -> Int,
        ): RowPlan {
            val starts = IntArray(rows + 1)
            for (row in 0 until rows) {
                val start = firstColumn(row)
                val placed = when {
                    start >= cols -> 0
                    starts[row] >= photoCount -> 0
                    else -> minOf(cols - start, photoCount - starts[row])
                }
                starts[row + 1] = starts[row] + placed
            }
            return RowPlan(photoCount, cols, rows, starts)
        }

        /** 135 格式的行计划。 */
        fun for135(photoCount: Int, cols: Int): RowPlan {
            val c = cols.coerceAtLeast(1)
            return build(photoCount, c, rowsFor135(photoCount, c)) { row ->
                firstColumn135(row, c)
            }
        }

        /** 120 格式的行计划。 */
        fun for120(photoCount: Int, cols: Int): RowPlan {
            val c = cols.coerceAtLeast(1)
            return build(photoCount, c, rowsFor120(photoCount, c)) { row ->
                firstColumn120(row)
            }
        }

        private fun ceilDiv(a: Int, b: Int): Int = if (b <= 0) 0 else (a + b - 1) / b
    }
}