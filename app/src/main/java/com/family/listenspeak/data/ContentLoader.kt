package com.family.listenspeak.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内容加载器：读 assets/content/v1 的 manifest + themes JSON → 内存索引。
 * 全本地，无网络依赖；load() 幂等（App 预热 + Activity 再调不重复解析）。
 */
class ContentLoader(private val ctx: Context) {

    val gson = Gson()
    var manifest: Manifest? = null; private set
    val themes = linkedMapOf<String, ThemeContent>()           // id → content
    val cards = linkedMapOf<String, CardDef>()                  // cardId → def
    val unitCards = linkedMapOf<String, MutableList<CardDef>>() // unitId → cards
    val vocabMap = linkedMapOf<String, VocabDef>()              // 词 → 词条（点词查义）
    private val loaded = AtomicBoolean(false)

    val isLoaded: Boolean get() = loaded.get()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (loaded.get()) return@withContext
        val am = ctx.assets
        val m = gson.fromJson(am.open("content/v1/manifest.json").bufferedReader().readText(), Manifest::class.java)
        manifest = m
        m.themes.forEach { te ->
            val json = am.open("content/v1/themes/${te.id}.json").bufferedReader().readText()
            val tc = gson.fromJson(json, ThemeContent::class.java)
            themes[tc.theme] = tc
            tc.units.forEach { u ->
                unitCards.getOrPut(u.id) { mutableListOf() }.addAll(u.cards)
                u.cards.forEach { cards[it.id] = it }
            }
        }
        // 词汇库（点词查义；不存在则忽略）
        runCatching {
            val type = object : TypeToken<Map<String, VocabDef>>() {}.type
            val vj: Map<String, VocabDef> = gson.fromJson(am.open("content/v1/vocab.json").bufferedReader().readText(), type)
            vocabMap.putAll(vj)
        }
        loaded.set(true)
    }

    fun card(id: String): CardDef? = cards[id]
    fun unit(id: String): List<CardDef> = unitCards[id] ?: emptyList()

    /** 点词查义 */
    fun vocab(w: String): VocabDef? = vocabMap[w]

    /** 主题图片池（听选干扰项补齐用） */
    fun imagePool(themeId: String): List<String> =
        themes[themeId]?.units?.flatMap { it.cards.mapNotNull { c -> c.image } }?.distinct() ?: emptyList()

    // ===== 干扰项语义冲突防护（v0.2.19）：人物类词与部位/物品词互不当干扰 =====
    // 人物部位类词（图里必然带人物）：body 主题的 head/face/eye/ear 等
    private val BODY_PART_WORDS = setOf(
        "head", "face", "eye", "ear", "nose", "mouth", "tooth", "hair", "neck",
        "shoulder", "arm", "hand", "finger", "tummy", "back", "waist", "leg",
        "knee", "foot", "toe", "elbow", "smile", "beard", "moustache"
    )
    // 人物全身类词（图主体是人物）：做部位词干扰时孩子会把"人"当部位
    private val PERSON_WORDS = setOf(
        "boy", "girl", "man", "woman", "baby", "kid", "children", "people",
        "brother", "sister", "father", "mother", "dad", "mum", "grandfather",
        "grandmother", "grandpa", "grandma", "family", "cousin", "friend",
        "person", "man", "woman", "teacher", "doctor", "nurse", "cook", "driver",
        "farmer", "fisherman", "waiter", "pilot", "singer", "clown"
    )

    /** 卡片词是否为人物部位类 */
    fun isBodyPartCard(en: String): Boolean {
        val w = en.trim().lowercase().split(" ").firstOrNull() ?: return false
        return w in BODY_PART_WORDS
    }

    /** 图片文件名对应的词是否为人物类（含全身人物图） */
    fun isPersonImage(img: String): Boolean {
        val stem = img.substringAfterLast('/').removeSuffix(".jpg")
        val word = stem.split("-").lastOrNull()?.lowercase() ?: return false
        return word in PERSON_WORDS || word in BODY_PART_WORDS
    }

    /** 图片对卡片是否构成语义混淆干扰：部位词 ↔ 人物图互相排除 */
    fun conflictsAsDistractor(cardEn: String, img: String): Boolean {
        val isCardBodyPart = isBodyPartCard(cardEn)
        val isImgPerson = isPersonImage(img)
        return isCardBodyPart && isImgPerson   // 部位卡不能拿人物图当干扰（人物图里也有头/脸等）
    }

    /** 首个学习单元（跳过 u0 chant/功能词包） */
    fun firstOpenUnit(childBand: Int): String {
        themes.forEach { (_, tc) ->
            tc.units.firstOrNull { it.band == childBand && !it.id.endsWith("-u0") }
                ?.let { return it.id }
        }
        return unitCards.keys.firstOrNull { !it.endsWith("-u0") } ?: "body-l1-u1"
    }
}
