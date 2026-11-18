package com.family.listenspeak.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * RAZ 分级阅读馆资源缓存（v0.2.25 混合模式）：
 * - aa 级：离线，资源打包进 APK（assets 内为 .webp 图 + .mp3 音频）
 * - A/B/C/D 级：在线，按级从 VPS（www.jxonline.top/raz/）下载到 filesDir 本地缓存，之后离线可用
 * 职责：解析 assets 路径 → 判断级别 → 缓存文件优先 → assets 兜底 → VPS 下载。
 * 图片后缀统一映射：引用 .jpg → 实际 .webp（assets 与 VPS 都是 webp，BitmapFactory 可解码）。
 */
object MediaCache {

    private const val BASE = "https://www.jxonline.top/raz/"
    private lateinit var ctx: Context
    private lateinit var client: OkHttpClient
    @Volatile private var initialized = false

    fun init(app: Context) {
        if (initialized) return
        ctx = app.applicationContext
        client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        initialized = true
    }

    private fun cacheDir(): File = File(ctx.filesDir, "raz_cache")

    /** 由资源路径识别级别：img/raz-<lv>-... → 取 lv；audio/all/<数字>_... → 按编号区间 */
    fun levelOf(ref: String): String? {
        val img = Regex("raz-(aa|[A-Za-z0-9]+)-").find(ref)?.groupValues?.get(1)
        if (img != null) return img.lowercase()
        val num = Regex("/(\\d+)_").find(ref)?.groupValues?.get(1)?.toIntOrNull()
            ?: return null
        return when (num) {
            in 0..909 -> "aa"                 // aa 级音频 0-911
            in 5100..5784 -> "a"              // A 级
            in 6000..6990 -> "b"              // B 级
            in 7100..8276 -> "c"              // C 级
            in 8400..9104 -> "d"              // D 级
            else -> null
        }
    }

    fun isOffline(lv: String?): Boolean = lv == null || lv == "aa"

    /** 单个文件：本地缓存是否已存在 */
    fun hasCache(ref: String): Boolean {
        val f = cacheFile(ref) ?: return false
        return f.exists() && f.length() > 0
    }

    /** 图片解析路径：RAZ 资源把 .jpg 后缀统一为 .webp（书 JSON 引用 jpg，assets/VPS 均为 webp）；非 RAZ（单词卡）保持原样 */
    fun imagePath(ref: String): String =
        if (ref.contains("raz-") && ref.endsWith(".jpg", true)) ref.substring(0, ref.length - 4) + ".webp" else ref

    /** assets 内实际读取路径：RAZ 图走 webp，音频/非 RAZ 原样 */
    fun assetPath(ref: String): String =
        if (ref.contains("raz-") && ref.startsWith("content/v1/img/") && ref.endsWith(".jpg", true))
            ref.substring(0, ref.length - 4) + ".webp" else ref

    private fun cacheFile(ref: String): File? {
        val lv = levelOf(ref) ?: return null
        if (isOffline(lv)) return null
        val dir = File(cacheDir(), lv)
        val name = ref.substringAfterLast('/')
        return File(dir, name)
    }

    private fun vpsUrl(ref: String): String {
        // ref: content/v1/img/raz-a-xxx-p01.jpg → 图片换 webp 并保留 img/ 前缀；audio/all/NNN.mp3 保留前缀
        val rel = ref.removePrefix("content/v1/")
        return BASE + if (rel.startsWith("img/")) {
            "img/" + imagePath(rel.substringAfter("img/"))
        } else if (rel.startsWith("audio/all/")) {
            "audio/all/" + rel.substringAfter("audio/all/")
        } else {
            rel
        }
    }

    /**
     * 确保某资源已本地可用。返回可用「读取方式」：
     * - 返回 null 表示走 assets（离线级，或下载失败时由调用方 assets 兜底）
     * - 返回 File 表示已缓存，可直接读
     */
    suspend fun ensureCached(ref: String): File? = withContext(Dispatchers.IO) {
        val lv = levelOf(ref) ?: return@withContext null
        if (isOffline(lv)) return@withContext null   // aa 离线走 assets
        val f = cacheFile(ref) ?: return@withContext null
        if (f.exists() && f.length() > 0) return@withContext f
        runCatching {
            val req = Request.Builder().url(vpsUrl(ref)).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code()}")
                resp.body()!!.byteStream().use { inp ->
                    f.parentFile?.mkdirs()
                    f.outputStream().use { out -> inp.copyTo(out) }
                }
            }
            f
        }.getOrNull()
    }

    /** 按级预下载：进 A/B/C/D 级时调用，把该级全部图+音频拉到本地缓存 */
    suspend fun preloadLevel(lv: String, refs: List<String>) {
        withContext(Dispatchers.IO) {
            if (isOffline(lv)) return@withContext
            refs.distinct().forEach { ref ->
                val f = cacheFile(ref) ?: return@forEach
                if (f.exists() && f.length() > 0) return@forEach
                runCatching {
                    val req = Request.Builder().url(vpsUrl(ref)).build()
                    client.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body()?.byteStream()?.use { inp ->
                                f.parentFile?.mkdirs()
                                f.outputStream().use { out -> inp.copyTo(out) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** 供 setupImage 解码：返回缓存 Bitmap（在线级），null 时调用方读 assets */
    suspend fun cachedBitmap(ref: String): Bitmap? = withContext(Dispatchers.IO) {
        val f = ensureCached(ref) ?: return@withContext null
        runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }
}