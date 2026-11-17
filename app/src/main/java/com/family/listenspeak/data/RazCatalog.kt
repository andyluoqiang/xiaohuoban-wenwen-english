package com.family.listenspeak.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/**
 * RAZ 分级阅读馆目录（v0.2.22）：
 * - manifest_raz.json：29 级骨架（aa→Z2），aa=ready(官方88本)、B=ready(87本)、其余 empty 预留
 * - books: 175 本 theme JSON（raz_b_* / raz_aa_*），与现有 ThemeContent 同构
 * 加载幂等；书 JSON 按需延迟加载（87 本全解析会拖慢启动）。
 */
object RazCatalog {

    data class RazLevel(
        val level: String,          // aa / A / B ... Z2
        val desc: String,           // 级别副标题
        val status: String,         // ready | empty
        val bookIds: List<String>   // themeId 列表（有序）
    )

    data class RazBook(
        val themeId: String,        // raz_b_xxx
        val title: String,          // 英文书名
        val nameZh: String?         // 中文名
    )

    @Volatile private var levels: List<RazLevel>? = null
    @Volatile private var books: MutableMap<String, RazBook> = LinkedHashMap()

    /** 目录加载（快：只读 29 级骨架 + 书名清单） */
    fun load(ctx: Context): Boolean {
        if (levels != null) return true
        return try {
            val am = ctx.assets
            val root = Gson().fromJson(
                am.open("content/v1/manifest_raz.json").bufferedReader().readText(),
                JsonObject::class.java
            )
            val out = mutableListOf<RazLevel>()
            val bk = LinkedHashMap<String, RazBook>()
            root.getAsJsonArray("levels").forEach { el ->
                val o = el.asJsonObject
                val lv = RazLevel(
                    level = o.get("level").asString,
                    desc = o.get("desc").asString,
                    status = o.get("status").asString,
                    bookIds = o.getAsJsonArray("books").mapNotNull { b ->
                        val bo = b.asJsonObject
                        val id = bo.get("themeId").asString
                        bk[id] = RazBook(
                            id,
                            bo.get("title").asString,
                            runCatching { bo.get("nameZh").asString }.getOrNull()
                        )
                        id
                    }
                )
                out.add(lv)
            }
            levels = out
            books = bk
            true
        } catch (e: Exception) {
            false
        }
    }

    fun allLevels(): List<RazLevel> = levels ?: emptyList()

    fun readyBookCount(): Int = levels?.filter { it.status == "ready" }?.sumOf { it.bookIds.size } ?: 0

    fun level(id: String): RazLevel? = levels?.find { it.level == id }

    fun book(themeId: String): RazBook? = books[themeId]

    /** 按需读取一本书的卡（延迟加载；数据已整体拷入 themes/，这里只解析单本） */
    fun loadBookCards(ctx: Context, themeId: String): ThemeContent? = runCatching {
        val json = ctx.assets.open("content/v1/themes/$themeId.json").bufferedReader().readText()
        Gson().fromJson(json, ThemeContent::class.java)
    }.getOrNull()
}
