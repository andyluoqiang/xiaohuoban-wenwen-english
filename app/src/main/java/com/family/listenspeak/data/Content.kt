package com.family.listenspeak.data

import androidx.room.Entity
import androidx.room.PrimaryKey

// ============ 内容模型（assets JSON，运行时只读） ============

data class Manifest(
    val version: Int,
    val generatedAt: String,
    val themes: List<ThemeEntry>
)

data class ThemeEntry(
    val id: String,
    val nameZh: String,
    val units: Int,
    val cards: Int
)

data class ThemeContent(
    val theme: String,
    val nameZh: String,
    val units: List<UnitDef>
)

data class UnitDef(
    val id: String,
    val band: Int,
    val title: String,
    val cards: List<CardDef>,
    val newFirst: Boolean? = null       // 剑桥新单元：批次优先学（家长验证 + 增量内容先见）
)

data class CardDef(
    val id: String,
    val type: String,          // word|func|sentence|qa|retell|inquire|dialog|story|chant
    val en: String,
    val zh: String?,
    val audio: String,
    val slow: String,
    val image: String,
    val distractors: List<String>?
) {
    /** 听选选项图（正确图 + 干扰图），顺序待洗牌 */
    fun options(): List<String> = listOf(image) + (distractors ?: emptyList())
}

/** 词汇库词条（点词查义用；w=单词，zh=中文释义，pos=词性，img/aud=可选插图与发音） */
data class VocabDef(
    val w: String,
    val zh: String?,
    val pos: String?,
    val img: String?,
    val aud: String?
)

// ============ Room 实体（进度流水，v1 完整 Schema） ============

@Entity
data class Child(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val avatar: String,
    val birthYear: Int,
    val bandLevel: Int,
    val pureListenMode: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity
data class Progress(
    @PrimaryKey val key: String,     // "${childId}-${unitId}"
    val childId: Long,
    val unitId: String,
    val cardsPassed: Int,
    val cardsTotal: Int,
    val stars: Int,
    val unlocked: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class Attempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val cardId: String,
    val kind: String,                // LISTEN / SPEAK
    val firstTryCorrect: Boolean?,
    val score: Float?,               // 引擎 0-5 分制
    val stars: Int?,
    val audioPath: String?,
    val ts: Long = System.currentTimeMillis()
)

@Entity
data class ReviewItem(
    @PrimaryKey val key: String,     // "${childId}-${cardId}"
    val childId: Long,
    val cardId: String,
    val priority: Int,               // 1中 2高 3最高
    val intervalStep: Int,           // 0同日 1隔1天 2隔3天 3隔7天 4可出池
    val nextDueTs: Long,
    val passDates: String = "",      // 出池验证日期 CSV
    val wrongCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity
data class EvalQueue(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val cardId: String,
    val text: String,
    val audioPath: String,
    val status: String = "PENDING",
    val resultJson: String? = null,
    val ts: Long = System.currentTimeMillis()
)

@Entity
data class Award(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val stickerId: String,
    val ts: Long = System.currentTimeMillis()
)

@Entity
data class ParentTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val childId: Long,
    val date: String,
    val themeId: String,
    val content: String,
    val done: Boolean = false,
    val doneTs: Long? = null
)

// ============ v0.2.15 新增：账号体系 + 课程学习统计 ============

/** 登录账号（App 本地）：username → childId 绑定；totalStars 为云端权威总数缓存 */
@Entity(tableName = "UserAccount")
data class UserAccount(
    @PrimaryKey val username: String,
    val childId: Long,
    val serverUserId: Long,                 // VPS 后台 user_id
    val totalStars: Int = 0,                // 服务器权威累计星数（登录时拉取，得星本地+增量）
    val createdAt: Long = System.currentTimeMillis()
)

/** 课程学习统计：每次学完一课（showDone）learnCount+1 并记录时间 */
@Entity(tableName = "LessonStat")
data class LessonStat(
    @PrimaryKey val key: String,            // "${childId}-${lessonId}"
    val childId: Long,
    val lessonId: String,
    val learnCount: Int = 0,
    val lastTs: Long = 0
)
