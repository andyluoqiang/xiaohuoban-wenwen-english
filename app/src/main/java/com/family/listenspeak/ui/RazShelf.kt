package com.family.listenspeak.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import com.family.listenspeak.data.VocabDef
import java.util.Locale
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.family.listenspeak.R
import com.family.listenspeak.audio.PcmRecorder
import com.family.listenspeak.audio.SoundFx
import com.family.listenspeak.data.RazCatalog
import com.family.listenspeak.eval.ScoreOutcome
import com.family.listenspeak.eval.StarMapper
import kotlinx.coroutines.launch

/**
 * RAZ 分级阅读馆（v0.2.22）：级别列表 → 书架 → 一本书三步精读
 * 三步 = ①听音选图（全书分批）→ ②听音选词（乱序分批）→ ③整句跟读（按书页序）
 * 纪律与主流程一致：答错不推进、答对才出「继续」、跟读≥60 才出「下一句」。
 * 独立于 MainActivity 主流程：仅复用 App 的 player/recorder/scorer/tts 基础设施与 UI 工具函数。
 */
class RazShelf(
    private val act: AppCompatActivity,
    private val app: com.family.listenspeak.App,
    private val container: FrameLayout,
    private val childId: Long,
    private val onExit: () -> Unit,          // 返回主流程首页
    private val onStarsGained: (Int) -> Unit, // 回话结束汇报星星（主流程累计+云端上报）
    private val setBackAction: (() -> Unit) -> Unit   // 注入：同步 MainActivity 的右滑返回动作
) {
    // ---------- UI 工具（本地复制，避免与 MainActivity 耦合） ----------
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun mp() = LinearLayout.LayoutParams.MATCH_PARENT
    private fun wrap() = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun vBox() = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun bg() = ContextCompat.getColor(act, R.color.bg_warm)
    private fun brown() = ContextCompat.getColor(act, R.color.text_brown)
    private fun hintColor() = ContextCompat.getColor(act, R.color.text_hint)
    private fun gold() = ContextCompat.getColor(act, R.color.star_gold)
    private fun yellow() = ContextCompat.getColor(act, R.color.accent_yellow)
    private fun green() = ContextCompat.getColor(act, R.color.primary_green)
    private fun greenDark() = ContextCompat.getColor(act, R.color.primary_green_dark)
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = r.toFloat() }
    private fun textTv(s: String, size: Float, color: Int) = TextView(act).apply {
        text = s; textSize = size; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
    }
    private fun subTv(s: String) = TextView(act).apply { text = s; textSize = 13f; setTextColor(hintColor()) }

    private val pastel = intArrayOf(
        0xFFFF9E80.toInt(), 0xFF80DEEA.toInt(), 0xFFB39DDB.toInt(), 0xFFAED581.toInt()
    )
    private fun lvColor(lv: String): Int = when (lv) {
        "aa" -> 0xFF8BC34A.toInt(); "A" -> 0xFF4FA3D1.toInt(); "B" -> 0xFFFFB74D.toInt()
        "C" -> 0xFFF06292.toInt(); "D" -> 0xFFBA68C8.toInt(); "E" -> 0xFF26A69A.toInt()
        else -> 0xFFA1887F.toInt()
    }

    private fun setView(v: View) {
        // 切页：取消所有延迟任务（防旧页定时播音频/解锁 busy 照跑）+ 停音频
        handler.removeCallbacksAndMessages(null)
        app.player.stop(); app.tts.stop()
        container.removeAllViews()
        container.addView(v, FrameLayout.LayoutParams(mp(), mp()))
    }

    private fun topBar(title: String, left: () -> Unit) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(Button(act).apply {
            text = "←"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(brown()); background = rounded(0xFFE8F0FE.toInt(), dp(24))
            minWidth = dp(48); minHeight = dp(48); gravity = Gravity.CENTER
            setOnClickListener { left() }
        })
        addView(textTv(title, 18f, brown()), LinearLayout.LayoutParams(0, wrap()).also { it.weight = 1f; it.leftMargin = dp(8) })
    }

    private fun closeBar(title: String, starText: TextView) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(Button(act).apply {
            text = "✕"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(brown()); background = rounded(0xFFE8F0FE.toInt(), dp(24))
            minWidth = dp(46); minHeight = dp(46); gravity = Gravity.CENTER
            setOnClickListener { app.player.stop(); app.tts.stop(); quitSession() }
        })
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val t = textTv(title, 15f, brown())
            t.isSingleLine = true
            addView(t, LinearLayout.LayoutParams(0, wrap()).also { it.weight = 1f })
        }, LinearLayout.LayoutParams(0, wrap()).also { it.weight = 1f; it.leftMargin = dp(8) })
        addView(starText)
    }

    private fun cartoonBtnIcon(text: String, icon: Int, bgc: Int, fg: Int, onClick: () -> Unit) = Button(act).apply {
        this.text = text; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        val ic = resources.getDrawable(icon, null).mutate()
        ic.setTint(fg)
        val size = dp(42)
        ic.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(ic, null, null, null)
        setCompoundDrawablePadding(dp(12))
        background = rounded(bgc, dp(30))
        setPadding(dp(22), dp(10), dp(22), dp(10))
        minHeight = dp(56)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun setupImage(path: String, iv: ImageView) {
        act.lifecycleScope.launch {
            val bmp = runCatching { act.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }

    // ---------- 页面状态 ----------
    private var curLevel: RazCatalog.RazLevel? = null
    private var curBookId: String? = null
    private var inSession = false

    /** 系统返回/右滑：RAZ 内部自处理（书内→书架→级列表→退出） */
    fun onBack() {
        when {
            inSession -> quitSession()
            curLevel != null -> { curLevel = null; showLevels() }
            else -> onExit()
        }
    }

    // ---------- 三步会话状态 ----------
    private data class Page(val img: String, val en: String, val zh: String?, val aud: String, val page: Int)
    private var pages: List<Page> = emptyList()
    private var step3 = 0                    // 0=选图 1=选词 2=跟读
    private var idx3 = 0
    private var queue3: List<Page> = emptyList()
    private var stars3 = 0
    private var busy = false
    private var attempt = 0
    private var pill0: TextView? = null
    private var pill1: TextView? = null
    private var pill2: TextView? = null
    private var scoreLine: TextView? = null
    private var nextBtn3: Button? = null
    private var hint3: TextView? = null
    private var starText3: TextView? = null
    private var imgSlot3: FrameLayout? = null
    private var recBtn3: Button? = null
    private var recording = false
    private var lastPcm: ByteArray? = null

    private val recorder = PcmRecorder()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val PASS = 60
    private fun wantOptions(): Int = if (curLevel?.level == "aa") 2 else 4

    // ================= ① 级别列表 =================
    fun showLevels() {
        inSession = false; curLevel = null
        setBackAction { onBack() }
        val ok = RazCatalog.load(act)
        val sc = ScrollView(act).apply { setBackgroundColor(bg()) }
        val col = vBox().apply { setPadding(dp(20), dp(18), dp(20), dp(64)) }
        col.addView(topBar("分级阅读馆") { onExit() })
        if (!ok) {
            col.addView(textTv("目录加载失败", 16f, brown()))
            sc.addView(col); setView(sc); return
        }
        val ready = RazCatalog.readyBookCount()
        col.addView(subTv("RAZ 分级 · 共 29 级 · 现有 $ready 本书可读").also { it.gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(10) })
        RazCatalog.allLevels().forEach { lv ->
            val locked = lv.status != "ready"
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = rounded(if (locked) 0xFFF3EEE7.toInt() else Color.WHITE, dp(18))
                alpha = if (locked) 0.55f else 1f
            }
            val badge = TextView(act).apply {
                text = lv.level; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
                textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                background = rounded(lvColor(lv.level), dp(14))
            }
            row.addView(badge, LinearLayout.LayoutParams(dp(52), dp(44)).also { it.rightMargin = dp(12) })
            val r = vBox()
            r.addView(textTv("${lv.level} 级 · ${lv.desc}", 16f, brown()))
            val statusText = when {
                !locked -> "${lv.bookIds.size} 本书 · 三步精读"
                lv.level in listOf("C", "D", "E") -> "📚 资料整理中"
                else -> "🔒 待解锁"
            }
            r.addView(textTv(statusText, 12f, hintColor()))
            row.addView(r, LinearLayout.LayoutParams(0, wrap()).apply { weight = 1f })
            row.addView(textTv(if (locked) "🔒" else "›", 20f, if (locked) hintColor() else brown()))
            if (!locked) row.setOnClickListener { showShelf(lv) }
            col.addView(row, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(11) })
        }
        sc.addView(col)
        setView(sc)
    }

    // ================= ② 书架 =================
    private fun showShelf(lv: RazCatalog.RazLevel) {
        curLevel = lv
        inSession = false
        setBackAction { onBack() }
        val sc = ScrollView(act).apply { setBackgroundColor(bg()) }
        val col = vBox().apply { setPadding(dp(18), dp(18), dp(18), dp(64)) }
        val levelName = if (lv.level == "aa") "aa 级" else "${lv.level} 级"
        col.addView(topBar("$levelName · ${lv.desc}") { showLevels() })
        col.addView(subTv("${lv.bookIds.size} 本 · 点封面开始三步精读"),
            LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(10) })
        // 封面网格：3 列
        var row: LinearLayout? = null
        lv.bookIds.forEachIndexed { i, id ->
            val b = RazCatalog.book(id) ?: return@forEachIndexed
            if (i % 3 == 0) {
                row = hbox().also { col.addView(it, LinearLayout.LayoutParams(mp(), wrap()).also { l -> l.topMargin = dp(12) }) }
            }
            val cell = vBox().apply {
                background = rounded(Color.WHITE, dp(16))
                setOnClickListener { openBook(id) }
            }
            val iv = ImageView(act).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(0xFFF0E7DA.toInt(), dp(14))
            }
            val cover = coverPath(id)
            setupImage(cover, iv)
            cell.addView(iv, LinearLayout.LayoutParams(mp(), dp(96)).also { it.gravity = Gravity.CENTER_HORIZONTAL; it.topMargin = dp(6); it.leftMargin = dp(6); it.rightMargin = dp(6) })
            val tt = textTv(b.title, 12f, brown())
            tt.maxLines = 2
            cell.addView(tt, LinearLayout.LayoutParams(mp(), wrap()).also { it.leftMargin = dp(4); it.rightMargin = dp(4); it.topMargin = dp(4) })
            b.nameZh?.let { nz ->
                val zt = textTv(nz, 10f, hintColor())
                zt.maxLines = 1
                cell.addView(zt, LinearLayout.LayoutParams(mp(), wrap()).also { it.leftMargin = dp(4); it.rightMargin = dp(4); it.bottomMargin = dp(8) })
            }
            row?.addView(cell, LinearLayout.LayoutParams(0, wrap(), 1f).also { if (i % 3 != 2) it.rightMargin = dp(10) })
        }
        sc.addView(col)
        setView(sc)
    }

    private fun coverPath(themeId: String): String {
        // B 级：img/raz-b-<slug>-cover.jpg；aa/A/C/D 级官方书：img/raz-<level>-<slug>-cover.jpg（slug 保留下划线）；自编书 slug 无下划线同样适用
        return when {
            themeId.startsWith("raz_b_") -> "content/v1/img/raz-b-${themeId.removePrefix("raz_b_")}-cover.jpg"
            themeId.startsWith("raz_aa_") -> "content/v1/img/raz-aa-${themeId.removePrefix("raz_aa_")}-cover.jpg"
            themeId.startsWith("raz_a_") -> "content/v1/img/raz-a-${themeId.removePrefix("raz_a_")}-cover.jpg"
            themeId.startsWith("raz_c_") -> "content/v1/img/raz-c-${themeId.removePrefix("raz_c_")}-cover.jpg"
            themeId.startsWith("raz_d_") -> "content/v1/img/raz-d-${themeId.removePrefix("raz_d_")}-cover.jpg"
            else -> "content/v1/img/${themeId.replace("raz_", "raz-").replace('_', '-')}-cover.jpg"
        }
    }

    // ================= ③ 打开书：三步会话 =================
    private fun openBook(themeId: String) {
        curBookId = themeId
        inSession = true
        setBackAction { onBack() }
        val tc = RazCatalog.loadBookCards(act, themeId)
        if (tc == null || tc.units.isEmpty()) { return }
        pages = tc.units[0].cards.map { c ->
            Page(c.image ?: "", c.en, c.zh, c.audio, (c.id.takeLast(1).toIntOrNull() ?: 1))
        }
        if (pages.size < 2) return
        val b = RazCatalog.book(themeId)
        bookTitle = "${b?.title ?: tc.nameZh} · ${tc.nameZh}"
        step3 = 0; idx3 = 0; stars3 = 0
        queue3 = pages.toList()
        renderStep()
    }

    private var bookTitle = ""

    private fun quitSession() {
        inSession = false
        // 中途退出：已得星星仍计入（对齐主线完成页逻辑）
        if (stars3 > 0) onStarsGained(stars3)
        curLevel?.let { showShelf(it) }
    }

    private fun renderStep() {
        attempt = 0; busy = false
        // 星数文本每次重建（复用旧实例会因已有 parent 崩溃）
        starText3 = textTv("★ $stars3", 19f, gold())
        val root = vBox().apply { setBackgroundColor(bg()) }
        root.addView(closeBar(bookTitle, starText3!!).also { it.setPadding(dp(12), dp(16), dp(12), dp(4)) })
        root.addView(stepPills())
        when (step3) {
            0 -> root.addView(scrollView(pickImageBody()))
            1 -> root.addView(scrollView(pickWordBody()))
            else -> root.addView(scrollView(readBody()))
        }
        root.addView(footForStep())
        setView(root)
        handler.postDelayed({ playCur3() }, 400)
    }

    private fun scrollView(v: View): ScrollView = ScrollView(act).apply {
        setBackgroundColor(bg()); isFillViewport = true
        addView(v)
    }

    private fun stepPills(): LinearLayout {
        val row = hbox().apply { setPadding(dp(16), dp(6), dp(16), dp(10)) }
        fun pill(label: String, state: Int): TextView {   // 0=当前 1=已完成 -1=待做
            return TextView(act).apply {
                text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = rounded(
                    if (state == 0) green() else if (state == 1) 0xFFDCF0E8.toInt() else 0xFFF0E7DA.toInt(),
                    dp(12)
                )
                setTextColor(if (state == 0) Color.WHITE else if (state == 1) greenDark() else 0xFFB9A79B.toInt())
            }
        }
        val n = queue3.size
        pill0 = pill(if (step3 == 0) "① 看图 ${idx3 + 1}/$n" else "① 看图", if (step3 == 0) 0 else 1)
        pill1 = pill(if (step3 == 1) "② 认词 ${idx3 + 1}/$n" else "② 认词", if (step3 == 1) 0 else if (step3 > 1) 1 else -1)
        pill2 = pill(if (step3 == 2) "③ 跟读 ${idx3 + 1}/$n" else "③ 跟读", if (step3 == 2) 0 else -1)
        row.addView(pill0!!, LinearLayout.LayoutParams(0, wrap(), 1f))
        row.addView(pill1!!, LinearLayout.LayoutParams(0, wrap(), 1f).also { it.leftMargin = dp(6) })
        row.addView(pill2!!, LinearLayout.LayoutParams(0, wrap(), 1f).also { it.leftMargin = dp(6) })
        return row
    }

    private fun playCur3() {
        val p = queue3.getOrNull(idx3) ?: return
        act.lifecycleScope.launch { app.player.play("content/v1/${p.aud}", fallbackText = p.en, slow = false) }
    }

    // ---------- 第①步：听音选图 ----------
    /** 干扰项过滤：排除同句不同图的页（如 Playful Puppy 的 "Let it be, Puppy." ×3，
     *  否则孩子听到这句点"语义也对"的图会被误判错） */
    private fun distractorPool(cur: Page, pages: List<Page>): List<Page> =
        pages.filter { it !== cur && it.en != cur.en }

    private fun pickImageBody(): View {
        val cur = queue3[idx3]
        val want = minOf(wantOptions(), pages.size)
        val others = distractorPool(cur, pages).shuffled().take(want - 1)
        val opts = (listOf(cur) + others).shuffled()
        val col = vBox().apply { setPadding(dp(10), dp(12), dp(10), dp(8)) }
        val rows = (opts.size + 1) / 2   // 每行 2 张
        var k = 0
        for (r in 0 until rows) {
            val row = hbox()
            for (c in 0 until 2) {
                if (k >= opts.size) break
                val p = opts[k++]
                val cell = FrameLayout(act).apply {
                    background = rounded(pastel[(k - 1) % 4], dp(20))
                    setOnClickListener { if (!busy) onPickImage(this, p, cur) }
                }
                val iv = ImageView(act).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                }
                setupImage("content/v1/${p.img}", iv)
                // 显式高度：ScrollView 内 weight 链会断，不能用 MATCH_PARENT/weight（图片高度会算成 0 → 看不见图）
                cell.addView(iv, FrameLayout.LayoutParams(mp(), dp(150)))
                row.addView(cell, LinearLayout.LayoutParams(0, wrap(), 1f).also { if (c == 0) it.rightMargin = dp(10) })
            }
            col.addView(row, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = if (r == 0) 0 else dp(10) })
        }
        col.addView(textTv("听一听，哪张图是这句话？", 15f, brown()).also { it.gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(6) })
        return col
    }

    private fun tabletWide() = act.resources.getBoolean(R.bool.is_tablet)

    private fun onPickImage(cell: View, picked: Page, cur: Page) {
        if (picked === cur) {
            busy = true
            stars3++; starText3?.text = "★ $stars3"
            SoundFx.correct()
            hint3?.text = "太棒了！+1★"
            // 词叠加图上
            if (cell is FrameLayout) {
                val strip = vBox().apply {
                    gravity = Gravity.CENTER
                    background = rounded(Color.argb(215, 255, 255, 255), dp(16))
                    setPadding(dp(10), dp(4), dp(10), dp(4))
                }
                strip.addView(TextView(act).apply {
                    text = cur.en; textSize = 17f; setTextColor(brown()); typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER; maxLines = 2
                })
                cur.zh?.let { strip.addView(TextView(act).apply {
                    text = it; textSize = 11f; setTextColor(hintColor()); gravity = Gravity.CENTER }) }
                cell.addView(strip, FrameLayout.LayoutParams(mp(), wrap()).also {
                    it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(8)
                })
            }
            nextBtn3?.visibility = View.VISIBLE
        } else {
            SoundFx.wrong()
            hint3?.text = "再听一次，选对才能继续哦～"
            wiggle(cell)
            // 句音延迟重播：错开 wrong 提示音（GAME 流会被紧接的 MEDIA 句音抢占，导致提示音听不到）
            handler.postDelayed({ playCur3() }, 800)
            handler.postDelayed({ busy = false }, 900)
        }
    }

    // ---------- 第②步：听音选词 ----------
    private fun pickWordBody(): View {
        val cur = queue3[idx3]
        val want = minOf(wantOptions(), pages.size)
        val othersAll = distractorPool(cur, pages)
        val cn = cur.en.split(" ").size
        val near = othersAll.filter { kotlin.math.abs(it.en.split(" ").size - cn) <= 1 }
        val far = othersAll.filter { kotlin.math.abs(it.en.split(" ").size - cn) > 1 }.shuffled()
        // 同句式（词数相近）优先，不足用词数差大的补；同句重复页已在干扰池排除
        val pool = (near.shuffled() + far).take(want - 1)
        val finalOpts = (listOf(cur) + pool).shuffled()
        val col = vBox().apply { setPadding(dp(14), dp(12), dp(14), dp(8)) }
        col.addView(textTv("听一听，选出这句话", 16f, brown()).also { it.gravity = Gravity.CENTER })
        finalOpts.forEachIndexed { i, p ->
            val btn = Button(act).apply {
                text = p.en; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                background = rounded(pastel[i % 4], dp(22))
                minHeight = dp(58)
                maxLines = 2
                setOnClickListener { if (!busy) onPickWord(this, p, cur) }
            }
            col.addView(btn, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(10) })
        }
        // 选对后图片区
        val slot = FrameLayout(act).apply {
            background = rounded(Color.WHITE, dp(20)); setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        imgSlot3 = slot
        col.addView(slot, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(12) })
        return col
    }

    private fun onPickWord(cell: Button, picked: Page, cur: Page) {
        if (picked === cur) {
            busy = true
            stars3++; starText3?.text = "★ $stars3"
            SoundFx.correct()
            hint3?.text = "答对啦！+1★"
            cell.background = rounded(green(), dp(22))
            val slot = imgSlot3 ?: return
            val iv = ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(4), dp(4), dp(4))
            }
            setupImage("content/v1/${cur.img}", iv)
            slot.addView(iv, FrameLayout.LayoutParams(mp(), dp(170)))
            cur.zh?.let { slot.addView(textTv(it, 13f, hintColor()).also { t -> t.gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(mp(), wrap()).also { it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(6) }) }
            nextBtn3?.visibility = View.VISIBLE
        } else {
            SoundFx.wrong()
            hint3?.text = "不对哦，再听一遍～"
            wiggle(cell)
            handler.postDelayed({ playCur3() }, 800)
            handler.postDelayed({ busy = false }, 900)
        }
    }

    // ---------- 第③步：跟读 ----------
    private fun readBody(): View {
        val cur = queue3[idx3]
        val card = vBox().apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val iv = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        setupImage("content/v1/${cur.img}", iv)
        card.addView(iv, LinearLayout.LayoutParams(mp(), dp(140)))
        // 跟读句：单词可点击，弹卡看释义+发音（与主流程点词查义同款）
        val en = buildTapTextView(cur.en, 24f, brown()).also { it.gravity = Gravity.CENTER }
        en.maxLines = 3
        card.addView(en)
        cur.zh?.let { card.addView(textTv(it, 14f, hintColor()).also { t -> t.gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(4) }) }
        val row = hbox()
        recBtn3 = Button(act).apply {
            text = "跟读"
            setBackgroundTintList(ColorStateList.valueOf(0xFFFF8A65.toInt()))
            setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            val mic = resources.getDrawable(R.drawable.ic_mic, null).mutate(); mic.setTint(Color.WHITE)
            val sz = dp(42); mic.setBounds(0, 0, sz, sz)
            setCompoundDrawablesRelative(mic, null, null, null)
            setCompoundDrawablePadding(dp(12))
            minHeight = dp(64); setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { toggleRec() }
        }
        row.addView(recBtn3, LinearLayout.LayoutParams(0, wrap(), 1f))
        val playRecBtn = Button(act).apply {
            text = "我的录音"
            setBackgroundTintList(ColorStateList.valueOf(yellow())); setTextColor(brown()); textSize = 20f
            typeface = Typeface.DEFAULT_BOLD; isSingleLine = true
            val spk = resources.getDrawable(R.drawable.ic_speaker, null).mutate(); spk.setTint(brown())
            val sz = dp(42); spk.setBounds(0, 0, sz, sz)
            setCompoundDrawablesRelative(spk, null, null, null)
            setCompoundDrawablePadding(dp(12))
            minHeight = dp(64); setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { playMyRec() }
        }
        row.addView(playRecBtn, LinearLayout.LayoutParams(0, wrap(), 1f).also { it.leftMargin = dp(12) })
        card.addView(row, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(12) })
        scoreLine = textTv("", 15f, hintColor()).also { it.gravity = Gravity.CENTER; it.minHeight = dp(44) }
        card.addView(scoreLine, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(8) })
        return card
    }

    // =============== 点词查义：跟读句的单词可点击，弹卡看释义+发音 ===============
    private fun cleanKey(s: String): String {
        var k = s.lowercase(Locale.ROOT)
        k = k.trim(' ', '.', ',', '!', '?', ';', ':', '(', ')', '"', '\'', '–', '’', '“', '”', '—')
        return k
    }

    private fun buildTapTextView(s: String, size: Float, color: Int) = TextView(act).apply {
        text = buildTapSpan(s, color)
        textSize = size; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
        movementMethod = LinkMovementMethod.getInstance()
    }

    private fun buildTapSpan(s: String, color: Int): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val parts = s.split(" ")
        parts.forEachIndexed { i, raw ->
            val start = sb.length
            sb.append(raw)
            val key = cleanKey(raw)
            if (key.isNotEmpty()) {
                val display = raw
                sb.setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) { showWordPopup(display, key) }
                    override fun updateDrawState(ds: TextPaint) {
                        ds.color = color; ds.isUnderlineText = false
                    }
                }, start, start + raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < parts.size - 1) sb.append(" ")
        }
        return sb
    }

    private fun showWordPopup(display: String, key: String) {
        val e = app.content.vocab(key)
        val overlay = FrameLayout(act).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { container.removeView(this) }
        }
        val card = vBox().apply {
            gravity = Gravity.CENTER
            background = rounded(Color.WHITE, dp(30))
            setPadding(dp(28), dp(22), dp(28), dp(22))
            setOnClickListener { }   // 点击卡片本身不关闭
        }
        card.addView(textTv(display, 30f, brown()).also { it.gravity = Gravity.CENTER })
        val imgP = e?.img?.takeIf { it.startsWith("img/") }
        if (imgP != null) {
            val iv = ImageView(act).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            setupImage("content/v1/$imgP", iv)
            card.addView(iv, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        card.addView(textTv(e?.zh ?: "…", 18f, hintColor()).also { it.gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(6) })
        e?.pos?.let { card.addView(textTv(it, 12f, hintColor()).also { t -> t.gravity = Gravity.CENTER }) }
        val sound = cartoonBtnIcon("发音", R.drawable.ic_speaker, 0xFF4FA3D1.toInt(), Color.WHITE) { playWordSound(key, display, e) }
        card.addView(sound, LinearLayout.LayoutParams(wrap(), wrap()).also { it.topMargin = dp(12) })
        val wrap = FrameLayout(act)
        wrap.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        overlay.addView(wrap, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 弹卡即自动读一遍；孩子可再点「发音」重复听
        playWordSound(key, display, e)
    }

    private fun playWordSound(key: String, display: String, e: VocabDef?) {
        if (e?.aud != null) act.lifecycleScope.launch { app.player.play("content/v1/${e.aud}", fallbackText = display, slow = false) }
        else app.tts.speak(display, false)
    }

    private fun footForStep(): View {
        val b = vBox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(20)) }
        hint3 = textTv("", 14f, brown()).also { it.gravity = Gravity.CENTER }
        b.addView(hint3!!, LinearLayout.LayoutParams(mp(), wrap()))
        when (step3) {
            2 -> {
                b.addView(cartoonBtnIcon("再听一遍", R.drawable.ic_play, yellow(), brown()) { playCur3() },
                    LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(6) })
                nextBtn3 = cartoonBtnIcon("下一句", R.drawable.ic_arrow_right, green(), Color.WHITE) {
                    nextBtn3?.visibility = View.INVISIBLE
                    advance3()
                }.apply { visibility = View.INVISIBLE }
                b.addView(nextBtn3!!, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(8) })
            }
            else -> {
                b.addView(cartoonBtnIcon("再听一次", R.drawable.ic_speaker, yellow(), brown()) { playCur3() },
                    LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(6) })
                nextBtn3 = cartoonBtnIcon("继续", R.drawable.ic_arrow_right, green(), Color.WHITE) {
                    nextBtn3?.visibility = View.INVISIBLE
                    advance3()
                }.apply { visibility = View.INVISIBLE }
                b.addView(nextBtn3!!, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(8) })
            }
        }
        return b
    }

    // ---------- 推进 ----------
    private fun advance3() {
        idx3++
        if (idx3 < queue3.size) { renderStep(); return }
        if (step3 < 2) {
            step3++
            idx3 = 0
            queue3 = if (step3 == 1) pages.shuffled() else pages.toList()
            // 步骤间过渡提示
            val msg = if (step3 == 1) "第1遍完成！来认认这些句子" else "太棒了！跟着读一读"
            val t = vBox().apply {
                gravity = Gravity.CENTER; setBackgroundColor(bg())
            }
            t.addView(textTv("✨", 46f, gold()).also { it.gravity = Gravity.CENTER })
            t.addView(textTv(msg, 20f, brown()).also { it.gravity = Gravity.CENTER })
            setView(t)
            SoundFx.cheer()
            handler.postDelayed({ renderStep() }, 1000)
            return
        }
        showDone3()
    }

    private fun showDone3() {
        val total = queue3.size
        val full = total * 2   // 选图+选词各1星
        onStarsGained(stars3.coerceAtLeast(0))
        SoundFx.cheer()
        val root = vBox().apply {
            gravity = Gravity.CENTER; setBackgroundColor(bg())
            setPadding(dp(24), dp(24), dp(24), dp(64))
        }
        root.addView(textTv("🎉 读完这本书啦！", 26f, brown()).also { it.gravity = Gravity.CENTER })
        root.addView(textTv("获得 +$stars3★", 20f, gold()).also { it.gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(12) })
        val row = hbox()
        val again = cartoonBtnIcon("再读一遍", R.drawable.ic_play, green(), Color.WHITE) {
            step3 = 0; idx3 = 0; stars3 = 0; queue3 = pages.toList()
            starText3?.text = "★ 0"
            renderStep()
        }
        row.addView(again, LinearLayout.LayoutParams(0, wrap(), 1f).also { it.rightMargin = dp(8) })
        val back = cartoonBtnIcon("回书架", R.drawable.ic_arrow_right, yellow(), brown()) {
            curLevel?.let { showShelf(it) }
        }
        row.addView(back, LinearLayout.LayoutParams(0, wrap(), 1f).also { it.leftMargin = dp(8) })
        root.addView(row, LinearLayout.LayoutParams(mp(), wrap()).also { it.topMargin = dp(24) })
        setView(root)
    }

    // ---------- 跟读评测 ----------
    private fun toggleRec() {
        if (recording) { stopRec(); return }
        if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        recorder.start()
        recording = true
        recBtn3?.text = "停止"
        scoreLine?.text = "🎙 录音中…"
        scoreLine?.setTextColor(hintColor())
    }

    private fun stopRec() {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        lastPcm = pcm
        recBtn3?.text = "跟读"
        val cur = queue3.getOrNull(idx3) ?: return
        if (!recorder.hasVoice(pcm)) {
            scoreLine?.text = "😕 没听到，再读一次"
            scoreLine?.setTextColor(0xFFE53935.toInt())
            SoundFx.wrong()
            return
        }
        act.lifecycleScope.launch {
            val res = app.scorer.score(cur.en, pcm)
            when (res) {
                is ScoreOutcome.Ok -> {
                    val pct = (res.total * 20).toInt().coerceIn(0, 100)
                    val passed = pct >= PASS
                    val tag = when {
                        pct >= 95 -> "amazing"; pct >= 85 -> "perfect"; pct >= 70 -> "great"; else -> "good"
                    }
                    val starsN = StarMapper.stars(2, res.total) ?: 0
                    val sb = StringBuilder().apply { repeat(3) { append(if (it < starsN) "★" else "☆") } }
                    if (passed) {
                        stars3 += starsN.coerceAtMost(1)
                        starText3?.text = "★ $stars3"
                        scoreLine?.text = "$sb ${pct}分 · $tag"
                        scoreLine?.setTextColor(0xFF2E7D32.toInt())
                        SoundFx.correct()
                        nextBtn3?.visibility = View.VISIBLE
                    } else {
                        scoreLine?.text = "$sb ${pct}分 · 😢 再读一次会更棒"
                        scoreLine?.setTextColor(0xFFE53935.toInt())
                        SoundFx.wrong()
                    }
                }
                is ScoreOutcome.NoVoice -> {
                    scoreLine?.text = "😕 没听到声音，再读一次"; scoreLine?.setTextColor(0xFFE53935.toInt()); SoundFx.wrong()
                }
                is ScoreOutcome.Timeout -> {
                    scoreLine?.text = "⏳ 稍后再评，再读一次"; scoreLine?.setTextColor(0xFFEF6C00.toInt())
                }
                is ScoreOutcome.Error -> {
                    scoreLine?.text = "⚠️ 评错：${res.msg}"; scoreLine?.setTextColor(0xFFEF6C00.toInt())
                }
            }
        }
    }

    private fun playMyRec() {
        val pcm = lastPcm ?: return
        val baos = java.io.ByteArrayOutputStream()
        val len = pcm.size
        baos.write("RIFF".toByteArray()); baos.write(intLE(36 + len)); baos.write("WAVE".toByteArray())
        baos.write("fmt ".toByteArray()); baos.write(intLE(16))
        baos.write(shortLE(1)); baos.write(shortLE(1)); baos.write(intLE(16000)); baos.write(intLE(32000))
        baos.write(shortLE(2)); baos.write(shortLE(16))
        baos.write("data".toByteArray()); baos.write(intLE(len)); baos.write(pcm)
        val f = java.io.File(act.cacheDir, "raz_rec_${System.currentTimeMillis()}.wav")
        f.writeBytes(baos.toByteArray())
        val mp = android.media.MediaPlayer()
        runCatching { mp.setDataSource(f.absolutePath); mp.prepare(); mp.start() }
    }

    private fun shortLE(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun intLE(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun wiggle(v: View) {
        android.animation.ObjectAnimator.ofFloat(v, "translationX", 0f, -8f, 8f, -5f, 5f, 0f).apply { duration = 400 }.start()
    }

    fun onDestroy() {
        recorder.stop()
    }
}
