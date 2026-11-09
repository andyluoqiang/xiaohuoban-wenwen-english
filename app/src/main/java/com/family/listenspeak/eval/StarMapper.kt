package com.family.listenspeak.eval

/**
 * 星级映射（01 文档 §3.2 v1.2）：
 * 讯飞实测为 5 分制（W0 记录），按档位分档：
 * - L1：纯听不评分
 * - L2：整句宽容（★★★≥3.75 ★★≥3.0 ★≥2.25），不展示逐词色
 * - L3：≥4.0 / 3.25 / 2.5，仅高亮最弱词
 * - L4+：标准（≥4.25 / 3.5 / 2.5），逐词色条
 */
object StarMapper {

    data class Thresholds(val three: Float, val two: Float, val one: Float)

    fun forBand(band: Int): Thresholds? = when {
        band <= 1 -> null                                    // L1 不评分
        band == 2 -> Thresholds(3.75f, 3.0f, 2.25f)
        band == 3 -> Thresholds(4.0f, 3.25f, 2.5f)
        else -> Thresholds(4.25f, 3.5f, 2.5f)
    }

    /** 返回 0-3 星；null=该档不评分 */
    fun stars(band: Int, totalScore5: Float): Int? {
        val t = forBand(band) ?: return null
        return when {
            totalScore5 >= t.three -> 3
            totalScore5 >= t.two -> 2
            totalScore5 >= t.one -> 1
            else -> 0
        }
    }

    /** 逐词色（L4+ 才用）：true=绿 */
    fun wordGood(wordScore5: Float): Boolean = wordScore5 >= 4.0f
}
