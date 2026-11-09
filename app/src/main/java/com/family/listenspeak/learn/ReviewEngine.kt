package com.family.listenspeak.learn

import com.family.listenspeak.data.CardDef
import com.family.listenspeak.data.ReviewItem
import com.family.listenspeak.data.ReviewDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 间隔复习引擎（01 文档 §5，简化 SM-2）：
 * - 入池：首答错（高）、连错示范态（最高）、软过未达标词（中）
 * - 阶梯：同日→隔1天→隔3天→隔7天→毕业出池
 * - 出池：连续两次不同日期首答对且间隔≥7天
 */
class ReviewEngine(private val dao: ReviewDao) {

    companion object {
        private const val DAY = 24 * 3600 * 1000L
        val STEP_MS = longArrayOf(0L, DAY, 3 * DAY, 7 * DAY, 0L)  // step0..3 的下次间隔
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        fun today(): String = fmt.format(Date())

        /** 出池需连续不同日首答对的次数 */
        const val PASS_STREAK = 2
    }

    suspend fun onWrong(childId: Long, card: CardDef, doubleMiss: Boolean) = withContext(Dispatchers.IO) {
        val key = "$childId-${card.id}"
        val cur = dao.byKey(key)
        val item = (cur ?: ReviewItem(key, childId, card.id,
            priority = 1, intervalStep = 0, nextDueTs = 0)).let {
            it.copy(priority = if (doubleMiss) 3 else maxOf(it.priority, 2),
                intervalStep = 0, nextDueTs = System.currentTimeMillis(),
                wrongCount = it.wrongCount + 1, passDates = "", updatedAt = System.currentTimeMillis())
        }
        dao.upsert(item)
    }

    /** 单元内新卡首答对：若该词在复习池，按复习通过推进阶梯（跨语境复现） */
    suspend fun onUnitPass(childId: Long, cardId: String) = withContext(Dispatchers.IO) {
        val key = "$childId-$cardId"
        if (dao.byKey(key) != null) onReviewPass(childId, cardId)
    }

    /** 复习卡答对：阶梯进级；满足出池条件置 4 */
    suspend fun onReviewPass(childId: Long, cardId: String) = withContext(Dispatchers.IO) {
        val key = "$childId-$cardId"
        val cur = dao.byKey(key) ?: return@withContext
        val dates = cur.passDates.split(",").filter { it.isNotBlank() }.toMutableList()
        val today = today()
        if (dates.lastOrNull() != today) dates.add(today)
        val step = cur.intervalStep
        if (step >= 3 && dates.size >= PASS_STREAK) {
            dao.upsert(cur.copy(intervalStep = 4, passDates = dates.joinToString(","),
                nextDueTs = Long.MAX_VALUE))       // 毕业出池
        } else {
            val nextStep = (step + 1).coerceAtMost(3)
            dao.upsert(cur.copy(intervalStep = nextStep,
                passDates = dates.joinToString(","),
                nextDueTs = System.currentTimeMillis() + STEP_MS[nextStep],
                updatedAt = System.currentTimeMillis()))
        }
    }

    /** 会话混插：按档位取应复习的卡（每 3-5 新卡夹 1-2 张；L1 固定 3:1） */
    suspend fun dueForInterleave(childId: Long, band: Int, newCount: Int): List<ReviewItem> =
        withContext(Dispatchers.IO) {
            val ratio = if (band <= 1) 3 else 5
            val limit = (newCount / ratio).coerceAtLeast(1)
            dao.due(childId, System.currentTimeMillis(), limit)
        }
}
