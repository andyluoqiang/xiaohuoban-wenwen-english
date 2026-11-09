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

    /** 首个学习单元（跳过 u0 chant/功能词包） */
    fun firstOpenUnit(childBand: Int): String {
        themes.forEach { (_, tc) ->
            tc.units.firstOrNull { it.band == childBand && !it.id.endsWith("-u0") }
                ?.let { return it.id }
        }
        return unitCards.keys.firstOrNull { !it.endsWith("-u0") } ?: "body-l1-u1"
    }
}
