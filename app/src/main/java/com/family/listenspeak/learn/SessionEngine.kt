package com.family.listenspeak.learn

import com.family.listenspeak.data.Attempt
import com.family.listenspeak.data.CardDef
import com.family.listenspeak.data.ContentLoader
import com.family.listenspeak.data.LsDb
import com.family.listenspeak.data.Progress

/**
 * 会话引擎：一次学习会话的队列构建与判定（01 文档 §1/§5）。
 * - 队列：chant 开场 → 新卡+复习卡混插（L1 3:1，L2+ 5:1）→ 时长到点收尾
 * - 听选：首答对 +1★；二答对 +0★；连错进示范态（无星无惩罚）
 * - 软过：单元首答正确率 ≥60%（L4+ 70%）放行
 */
class SessionEngine(
    private val db: LsDb,
    private val content: ContentLoader,
    private val review: ReviewEngine
) {

    data class QueueItem(
        val card: CardDef,
        val isReview: Boolean,
        val optionsShuffled: List<String>     // 洗牌后的选项（正确图含在其中）
    )

    data class JudgeResult(
        val correct: Boolean,
        val stars: Int,
        val doubleMiss: Boolean,
        val teachMode: Boolean
    )

    /** 构建一次会话队列；limit = 档位卡量（L1 6 / L2 8 / L3 10） */
    suspend fun buildSession(childId: Long, unitId: String, limit: Int): List<QueueItem> {
        val band = unitId.substringAfter("-l").first().digitToInt()
        val themeId = unitId.substringBefore("-l")
        val pool = content.imagePool(themeId)
        val newCards = content.unit(unitId).filter { it.type != "chant" }.take(limit)
        val reviews = review.dueForInterleave(childId, band, newCards.size)
            .mapNotNull { content.card(it.cardId) }
        val items = mutableListOf<QueueItem>()
        // 混插：每 ratio 张新卡后插 1 张复习卡
        val ratio = if (band <= 1) 3 else 5
        var rIdx = 0
        newCards.forEachIndexed { i, c ->
            items += QueueItem(c, false, buildOptions(c, band, pool))
            if ((i + 1) % ratio == 0 && rIdx < reviews.size) {
                items += QueueItem(reviews[rIdx++], true, buildOptions(reviews[rIdx - 1], band, pool))
            }
        }
        while (rIdx < reviews.size && reviews.size > 0)
            items += QueueItem(reviews[rIdx++], true, buildOptions(reviews[rIdx - 1], band, pool))
        return items
    }

    /**
     * 选项构建（01 文档 §1.4 + §1.7）：
     * - 数量按档位：L1 = 2 张大卡（二选一，最低龄友好）；L2+ = 4 张（2×2 满格）
     * - 正确图必含；不足从主题图池补（同主题）
     * - 每次洗牌，防位置记忆（同卡两次布局不同）
     */
    private fun buildOptions(c: CardDef, band: Int, pool: List<String>): List<String> {
        val want = if (band <= 1) 2 else 4
        val correct = c.image
        val declared = (c.distractors ?: emptyList()).filter { it != correct }
        val extra = pool.filter { it != correct && it !in declared }.shuffled()
        val distractors = (declared + extra).distinct().take(want - 1)
        return (listOf(correct) + distractors).shuffled()
    }

    /** 听选判定（状态机三态：首答/二答/示范） */
    suspend fun judge(childId: Long, item: QueueItem, pickedImage: String, attemptIndex: Int): JudgeResult {
        val correct = pickedImage == item.card.image
        val prevOk = db.attemptDao().firstTryOkCount(childId, item.card.id) > 0
        return when {
            correct && attemptIndex == 1 -> {
                if (!item.isReview) db.attemptDao().insert(
                    Attempt(childId = childId, cardId = item.card.id, kind = "LISTEN",
                        firstTryCorrect = true, score = null, stars = 1, audioPath = null)
                )
                if (item.isReview) review.onReviewPass(childId, item.card.id)
                else review.onUnitPass(childId, item.card.id)
                JudgeResult(true, 1, false, false)
            }
            correct -> JudgeResult(true, 0, false, false)   // 二答对：无星无惩罚
            attemptIndex >= 2 -> {
                // 连错 2 次：示范态 + 入复习池（最高优先级）；不扣任何
                review.onWrong(childId, item.card, doubleMiss = true)
                JudgeResult(false, 0, false, true)
            }
            else -> {
                review.onWrong(childId, item.card, doubleMiss = false)
                JudgeResult(false, 0, false, false)
            }
        }
    }

    /** 单元软过判定：首答正确率 ≥60%（L4+ 70%）→ 解锁下一单元 */
    suspend fun unitPassed(childId: Long, unitId: String): Boolean {
        val band = unitId.substringAfter("-l").first().digitToInt()
        val cards = content.unit(unitId)
        val ok = cards.count { db.attemptDao().firstTryOkCount(childId, it.id) > 0 }
        val rate = if (cards.isEmpty()) 0f else ok.toFloat() / cards.size
        val pass = rate >= (if (band >= 4) 0.7f else 0.6f)
        val key = "$childId-$unitId"
        val cur = db.progressDao().byKey(key)
        db.progressDao().upsert(
            Progress(key, childId, unitId, ok, cards.size,
                stars = (cur?.stars ?: 0), unlocked = pass or (cur?.unlocked ?: false))
        )
        return pass
    }

    /** 累计单元星数 */
    suspend fun addStars(childId: Long, unitId: String, delta: Int) {
        val key = "$childId-$unitId"
        val cur = db.progressDao().byKey(key)
        db.progressDao().upsert(
            (cur ?: Progress(key, childId, unitId, 0, content.unit(unitId).size, 0)).let {
                it.copy(stars = it.stars + delta, updatedAt = System.currentTimeMillis())
            }
        )
    }
}
