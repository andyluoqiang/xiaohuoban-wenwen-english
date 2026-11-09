package com.family.listenspeak.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
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
import com.family.listenspeak.App
import com.family.listenspeak.R
import com.family.listenspeak.audio.PcmRecorder
import com.family.listenspeak.audio.SoundFx
import com.family.listenspeak.data.CardDef
import com.family.listenspeak.data.Child
import com.family.listenspeak.data.VocabDef
import com.family.listenspeak.eval.ScoreOutcome
import com.family.listenspeak.eval.StarMapper
import com.family.listenspeak.learn.SessionEngine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * 稳稳学英语 v0.2：难度档 → 主题 → 热身 → 会话。
 * 与验证器对齐：主题 icon、真实热身、插画卡片、答对/答错动画、音效、底部居中按钮、跟读评测、录音回放。
 */
class MainActivity : AppCompatActivity() {

    private val app get() = application as App
    private lateinit var container: FrameLayout
    private var childId: Long = 1L

    private var items: List<SessionEngine.QueueItem> = emptyList()
    private var idx = 0
    private var stars = 0
    private var attempt = 0
    private var busy = false
    private var currentBand = 1
    private var curTheme = "body"
    private var curLevel = "l1"
    private var stage = 0            // 0=词卡批(选图) 1=句子批(跟读)；批量学习阶段切换

    // ===== 课程制（2026-11-09）：每课 10 词 + 随机课 =====
    private data class LessonDef(val id: String, val idx: Int, val title: String,
                                  val cards: List<CardDef>, val cam: Boolean = false)
    private var curLesson: LessonDef? = null
    private var wordStep = 0         // 词卡双步：0=听音选图 1=听音选词 2=图词揭示
    private var wordOptions: List<String> = emptyList()   // 词选步选项

    private val recorder = PcmRecorder()
    private val handler = Handler(Looper.getMainLooper())
    private var recButton: Button? = null
    private var recResult: TextView? = null
    private var nextBtn: Button? = null
    private var recording = false
    private var lastRecPcm: ByteArray? = null
    private var hintView: TextView? = null

    private var tabletMode = false
    private val prefs get() = getSharedPreferences("ls_prefs", MODE_PRIVATE)

    // 右滑返回：记录当前层的返回动作
    private var backAction: (() -> Unit)? = null

    // 已完成的主题（完成页"完成"按钮记录，主题选择页打勾示意）
    private val completedThemes = mutableSetOf<String>()

    private val pastel = intArrayOf(
        0xFFFF9E80.toInt(), 0xFF80DEEA.toInt(), 0xFFB39DDB.toInt(), 0xFFAED581.toInt()
    )

    private val LISTEN_TYPES = setOf("sentence", "qa", "retell", "inquire", "dialog", "story")

    private data class ThemeMeta(val emoji: String, val nameZh: String)
    private val THEME_META = mapOf(
        "body" to ThemeMeta("🧒", "身体与动作"),
        "food" to ThemeMeta("🍎", "食物与饮品"),
        "play" to ThemeMeta("⚽", "运动与游戏"),
        "animals" to ThemeMeta("🐶", "动物与宠物")
    )

    private data class Level(val key: String, val short: String, val color: Int, val name: String, val desc: String)
    private val LEVELS = listOf(
        Level("l1", "L1", 0xFFFF8A65.toInt(), "启蒙 · 核心词汇", "12 词 · 看图 + 认词两轮"),
        Level("l2", "L2", 0xFF4FA3D1.toInt(), "拓展 · 词汇短语", "16 词四选一 + 3 张短语卡"),
        Level("l3", "L3", 0xFFAB83BD.toInt(), "短句 · 听指令", "8 句 TPR · 听句选图"),
        Level("l4", "L4", 0xFF8BC34A.toInt(), "问答与表达", "6 问答 + 3 复述 + 3 主动提问"),
        Level("l5", "L5", 0xFFFFB74D.toInt(), "情景对话", "6 轮情景对话"),
        Level("l6", "L6", 0xFF4DB6AC.toInt(), "小小叙述家", "5 句连续叙述 · 挑战跟读")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabletMode = prefs.getBoolean("tablet_mode", resources.getBoolean(R.bool.is_tablet))
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.container)
        setupSwipeBack()
        lifecycleScope.launch {
            runCatching { app.content.load() }
            ensureChild()
            showDifficultyMap()
        }
    }

    /** 右滑返回：依赖 Android 全面屏手势/返回键触发 onBackPressed（无需 container 手势拦截，避免阻断子控件点击） */
    private fun setupSwipeBack() {
        // 全面屏右滑手势由系统触发 onBackPressed，已在其重写中处理；此处保留空实现占位
    }

    /** 系统返回键/全面屏右滑 → 走我们的 backAction（回上层），不退出 Activity */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val action = backAction
        if (action != null) action()
        else super.onBackPressed()
    }

    private suspend fun ensureChild() {
        val dao = app.db.childDao()
        val existing = dao.all().first()
        childId = if (existing.isEmpty()) {
            dao.upsert(Child(name = "小山姆", avatar = "bear", birthYear = 2022, bandLevel = 1))
        } else existing.first().id
    }

    // =============== ① 难度档首页 ===============
    private fun showDifficultyMap() {
        backAction = null
        val sc = ScrollView(this).apply { setBackgroundColor(bg()) }
        val col = vBox().apply { setPadding(dp(24), dp(40), dp(24), dp(64)) }   // 底部留安全区，防文字被导航条裁切
        col.addView(textTv("稳稳学英语", 40f, brown()).also { it.gravity = Gravity.CENTER })
        col.addView(subTv("由易到难 · 先选难度，再学主题"))
        // 版本指纹：装对/装错一眼可辨（剑桥改版一起标识）
        col.addView(TextView(this).apply {
            text = "v0.2.5 · 剑桥岛版"
            textSize = 12f; setTextColor(hintColor()); gravity = Gravity.CENTER
        })
        LEVELS.forEach { lv ->
            col.addView(levelRow(lv) { showThemePicker(lv.key) }, lp(mp(), wrapPx()).also { it.topMargin = dp(12) })
        }
        col.addView(levelRowAll { showThemePicker("all") }, lp(mp(), wrapPx()).also { it.topMargin = dp(12) })
        col.addView(modeToggle(), lp(mp()).also { it.topMargin = dp(16) })
        sc.addView(col)
        setView(sc)
    }

    // =============== ② 难度档 → 主题选择 ===============
    private fun showThemePicker(levelKey: String) {
        backAction = { showDifficultyMap() }
        val lv = LEVELS.find { it.key == levelKey }
        val sc = ScrollView(this).apply { setBackgroundColor(bg()) }
        val col = vBox().apply { setPadding(dp(20), dp(18), dp(20), dp(64)) }
        col.addView(topBar("难度 · ${lv?.short ?: "全部连练"}", left = { showDifficultyMap() }))
        app.content.themes.forEach { (tid, tc) ->
            val meta = THEME_META[tid] ?: ThemeMeta("📖", tc.nameZh)
            val cards = tc.units.sumOf { it.cards.size }
            col.addView(themeRow(meta.emoji, meta.nameZh, "共 $cards 张卡", completedThemes.contains(tid), onClick = { showWarm(tid, levelKey) }),
                lp(mp()).also { it.topMargin = dp(12) })
        }
        sc.addView(col)
        setView(sc)
    }

    // =============== ③ 热身（真实内容：儿歌 + 指令词卡） ===============
    private data class Warm(val emoji: String, val en: String, val zh: String, val audio: String)

    private fun chantFor(themeId: String): String =
        if (themeId == "food") "Apple, apple, yum yum yum! Banana, banana, in my tum-tum-tum!"
        else if (themeId == "play") "Jump, jump, jump up high! Run, run, run, so fast! Walk, walk, walk and stop! We love to play all day!"
        else if (themeId == "animals") "Dog says woof, cat says meow! Duck says quack, cow says moo! Animals, animals, we love you!"
        else "Nose, nose, touch your nose! Toes, toes, tap your toes!"

    private fun warmWords(themeId: String): List<Warm> =
        if (themeId == "food") listOf(
            Warm("➕", "More", "还要", "101_more"),
            Warm("✅", "All done", "吃完了", "102_all-done"),
            Warm("🙏", "Please", "请", "103_please"),
            Warm("😋", "Yum yum", "好吃", "104_yum-yum")
        ) else if (themeId == "play") listOf(
            Warm("🙋", "Ready?", "预备", "201_ready"),
            Warm("⚡", "Faster!", "快点", "202_faster"),
            Warm("⚠️", "Careful!", "小心", "203_careful"),
            Warm("🎉", "Hooray!", "好棒", "204_hooray")
        ) else if (themeId == "animals") listOf(
            Warm("👀", "Look!", "看哦", "301_look"),
            Warm("👂", "Listen!", "听", "302_listen"),
            Warm("😲", "Wow!", "哇", "303_wow"),
            Warm("🥰", "So cute!", "好可爱", "304_so-cute")
        ) else listOf(
            Warm("✋", "Stop", "停", "01_stop"),
            Warm("🏃", "Go", "走", "02_go"),
            Warm("👀", "Look", "看", "03_look"),
            Warm("🧼", "Wash wash", "洗洗", "04_wash-wash")
        )

    private fun showWarm(themeId: String, levelKey: String) {
        curTheme = themeId
        curLevel = levelKey
        backAction = { showThemePicker(levelKey) }
        // fillViewport + 底部弹性空隙：内容均衡分布，「开始学习」贴近底部，不再上半屏堆叠
        val sc = ScrollView(this).apply { setBackgroundColor(bg()); isFillViewport = true }
        val col = vBox().apply { setPadding(dp(20), dp(12), dp(20), dp(36)) }
        col.addView(topBar("课前热身", left = { showThemePicker(levelKey) }))

        // 儿歌卡（点击播放）
        val chant = linearV(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = rounded(0xFFFFF3E0.toInt(), dp(22))
        }
        val chantEn = chantFor(themeId)
        chant.addView(textTv("🎵 热身儿歌", 15f, brown()).also { it.gravity = Gravity.CENTER })
        chant.addView(textTv(chantEn, 14f, brown()).also { it.gravity = Gravity.CENTER }, lp(mp()).also { it.topMargin = dp(8) })
        chant.setOnClickListener { playEn(chantEn) }
        col.addView(chant, lp(mp(), wrap()).also { it.topMargin = dp(14) })

        // 指令词卡
        val words = warmWords(themeId)
        val cols = if (tabletMode) 4 else 2
        val rows = (words.size + cols - 1) / cols
        for (r in 0 until rows) {
            val row = hbox()
            for (c in 0 until cols) {
                val w = words.getOrNull(r * cols + c) ?: break
                val cell = warmV(w)
                // dp(150)：充足容纳 emoji + 英文 + 中文三行，中文不再被截断
                row.addView(cell, LinearLayout.LayoutParams(0, dp(150), 1f).also { if (c < cols - 1) it.rightMargin = dp(8) })
            }
            col.addView(row, lp(mp()).also { it.topMargin = dp(10) })
        }

        // 弹性空隙：把「开始学习」推到页面底部，头重脚轻问题解决
        col.addView(View(this), LinearLayout.LayoutParams(mpBar(), 0, 1f))

        val go = cartoonBtn("开始学习", "🚀", green(), Color.WHITE) { showLessons(themeId, levelKey) }
        col.addView(go, lp(mpBar(), dp(60)).also { it.topMargin = dp(12) })
        sc.addView(col)
        setView(sc)
        // 进入热身页自动播放儿歌（不需点击）
        handler.postDelayed({ playEn(chantEn) }, 400)
    }

    private fun warmV(w: Warm): LinearLayout {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = rounded(Color.WHITE, dp(18))
            setOnClickListener { playEn(w.en) }
        }
        // 子内容也水平居中：卡片内 emoji/英文/中文三行整体居中，不再靠左
        cell.addView(textTv(w.emoji, 30f, brown()).also { it.gravity = Gravity.CENTER })
        cell.addView(textTv(w.en, 15f, brown()).also { it.gravity = Gravity.CENTER })
        cell.addView(textTv(w.zh, 11f, hintColor()).also { it.gravity = Gravity.CENTER })
        return cell
    }

    // =============== ④ 课程树（每课 10 词 + 随机课） ===============
    /** 该主题该档位的全部学习卡按 10 个一课分块；L1/L2=词/短语卡（选图+选词双步），L3+=句子卡（跟读）。
     *  顺序保持数据顺序（由易到难）；剑桥新单元的课打 🎓 标（替代原 newFirst 提前的方案） */
    private fun lessonsFor(themeId: String, band: Int): List<LessonDef> {
        val units = app.content.themes[themeId]?.units?.filter { it.band == band } ?: emptyList()
        data class UC(val card: CardDef, val isNew: Boolean)
        val all = units.flatMap { u -> u.cards.map { UC(it, u.newFirst == true) } }
            .filter { if (band <= 2) (it.card.type !in LISTEN_TYPES && it.card.image != null) else it.card.type in LISTEN_TYPES }
        return all.chunked(10).mapIndexed { i, list ->
            LessonDef("$themeId-l$band-lesson${i + 1}", i + 1, list.first().card.en,
                list.map { it.card }, list.any { it.isNew })
        }
    }

    /** 课程树页：所有课程随时可学（不做解锁门槛）+ 🎲 随机学习 */
    private fun showLessons(themeId: String, levelKey: String) {
        curTheme = themeId
        curLevel = levelKey
        currentBand = if (levelKey == "l1") 1 else levelKey.substring(1).toIntOrNull() ?: 1
        backAction = { showThemePicker(levelKey) }
        lifecycleScope.launch {
            val lessons = lessonsFor(themeId, currentBand)
            val passed = app.db.attemptDao().passedCardIds(childId).toSet()
            val sc = ScrollView(this@MainActivity).apply { setBackgroundColor(bg()) }
            val col = vBox().apply { setPadding(dp(20), dp(18), dp(20), dp(64)) }
            col.addView(topBar("课程 · ${THEME_META[themeId]?.nameZh ?: themeId}", left = { showThemePicker(levelKey) }))
            col.addView(subTv("共 ${lessons.size} 课 · 每课 10 个内容 · 点开即学"))
            // 随机学习入口
            val random = cartoonBtn("🎲 随机学习 10 个", "🎲", 0xFFAB83BD.toInt(), Color.WHITE) { startRandom() }
            col.addView(random, lp(mp(), wrapPx()).also { it.topMargin = dp(14) })
            lessons.forEach { l ->
                val done = l.cards.count { it.id in passed }
                col.addView(lessonRow(l, done) { startLesson(l) },
                    lp(mp(), wrapPx()).also { it.topMargin = dp(12) })
            }
            sc.addView(col)
            setView(sc)
        }
    }

    /** 课程行：📖 第N课 · 标题（首词）· 已学 x/10 */
    private fun lessonRow(l: LessonDef, done: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(0xFFFFF3E0.toInt(), dp(20))
        setOnClickListener { onClick() }
        addView(textTv("📖", 30f, brown()), LinearLayout.LayoutParams(wrapPx(), wrapPx()).also { it.rightMargin = dp(12) })
        val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val title = textTv(if (l.idx > 0) "第 ${l.idx} 课" else "随机课", 18f, brown())
        r.addView(title)
        // 剑桥新单元的课：🎓 徽章标识（家长一眼找到新内容）
        if (l.cam) {
            val badge = TextView(this@MainActivity).apply {
                text = "🎓 剑桥新内容"; textSize = 11f; setTextColor(0xFFE65100.toInt())
                setPadding(dp(8), dp(2), dp(8), dp(2))
                background = rounded(0xFFFFE0B2.toInt(), dp(10))
            }
            (title.layoutParams as? LinearLayout.LayoutParams)?.topMargin = 0
            r.addView(badge, LinearLayout.LayoutParams(wrapPx(), wrapPx()).also { it.topMargin = dp(4) })
        }
        r.addView(textTv("${l.title} … 共 ${l.cards.size} 个", 13f, hintColor()))
        addView(r, LinearLayout.LayoutParams(0, wrapPx()).apply { weight = 1f })
        addView(textTv("$done/${l.cards.size}", 15f, if (done >= l.cards.size) green() else hintColor()),
            LinearLayout.LayoutParams(wrapPx(), wrapPx()).also { it.rightMargin = dp(8) })
        addView(textTv(if (done >= l.cards.size) "✓" else "›", 22f, if (done >= l.cards.size) green() else brown()))
    }

    private fun startLesson(l: LessonDef) { curLesson = l; startSession(l.cards) }

    /** 随机课：主题档位全部学习卡随机抽 10 个 */
    private fun startRandom() {
        val all = lessonsFor(curTheme, currentBand).flatMap { it.cards }
        if (all.isEmpty()) return
        val cards = all.shuffled().take(10)
        curLesson = LessonDef("${curTheme}-l$currentBand-random", 0, "随机课", cards)
        startSession(cards)
    }

    /** 会话启动：词卡带图选项（图选步）；句子卡走跟读 */
    private fun startSession(cards: List<CardDef>) {
        backAction = { showDifficultyMap() }
        stage = if (currentBand <= 2) 0 else 1
        val pool = app.content.imagePool(curTheme)
        val want = if (currentBand <= 1) 2 else 4
        items = cards.map { c ->
            val opts = if (c.type in LISTEN_TYPES) emptyList() else buildOpts(c, pool, want)
            SessionEngine.QueueItem(c, false, opts)
        }.filter { it.optionsShuffled.isNotEmpty() || it.card.type in LISTEN_TYPES }
        idx = 0; stars = 0; wordStep = 0
        renderSession()
    }

    /** 词选步选项：正确词 + 同主题干扰词（L1 二选一，L2 四选一） */
    private fun buildWordOptions(card: CardDef): List<String> {
        val want = if (currentBand <= 1) 2 else 4
        val pool = app.content.themes[curTheme]?.units
            ?.filter { it.band == currentBand }
            ?.flatMap { it.cards }
            ?.filter { it.type !in LISTEN_TYPES }
            ?.map { it.en } ?: emptyList()
        val others = pool.filter { !it.equals(card.en, ignoreCase = true) }.shuffled().take(want - 1)
        return (listOf(card.en) + others).shuffled()
    }

    private fun buildOpts(c: CardDef, pool: List<String>, want: Int): List<String> {
        val isImg = c.image?.startsWith("img/") == true
        if (!isImg) return emptyList()
        val correct = c.image
        val declared = (c.distractors ?: emptyList()).filter { it != correct }
        val extra = pool.filter { it != correct && it !in declared }.shuffled()
        val distractors = (declared + extra).distinct().take(want - 1)
        return (listOf(correct) + distractors).shuffled()
    }

    // =============== 会话渲染 ===============
    private fun renderSession() {
        wordStep = 0          // 新卡：从图选步开始
        attempt = 0
        renderCurrent()
    }

    /** 当前卡渲染（wordStep 切换时调用，不重置步态） */
    private fun renderCurrent() {
        val cur = items.getOrNull(idx)
        if (cur == null) { onBatchEnd(); return }
        busy = false
        val root = vBox().apply { setBackgroundColor(bg()) }
        root.addView(sessionTop(cur))
        if (cur.optionsShuffled.isEmpty()) {
            // 句子卡：用 ScrollView 包裹，避免文字/内容被屏幕裁切
            val sc = ScrollView(this).apply { setBackgroundColor(bg()); isFillViewport = true }
            sc.addView(listenCard(cur))
            root.addView(sc, lp(mpBar(), 0).also { it.weight = 1f })
            root.addView(listenBottom())
        } else when (wordStep) {
            1 -> {   // 听音选词步
                root.addView(wordGrid(cur), lp(mpBar(), 0).also { it.weight = 1f })
                root.addView(choiceBottom())
            }
            2 -> {   // 图词揭示页
                val sc = ScrollView(this).apply { setBackgroundColor(bg()); isFillViewport = true }
                sc.addView(revealView(cur))
                root.addView(sc, lp(mpBar(), 0).also { it.weight = 1f })
                root.addView(revealBottom())
            }
            else -> { // 听音选图步
                root.addView(optionGrid(cur), lp(mpBar(), 0).also { it.weight = 1f })
                root.addView(choiceBottom())
            }
        }
        setView(root)
        animateIn(root.getChildAt(1))
        handler.postDelayed({ playCurrent() }, 400)
    }

    /** 听音选词步：大单词按钮（卡通胶囊，自适应高度防裁字） */
    private fun wordGrid(cur: SessionEngine.QueueItem): LinearLayout {
        val box = vBox().apply { setPadding(dp(12), dp(16), dp(12), dp(8)); gravity = Gravity.CENTER }
        box.addView(textTv("🎧 听一听，选出这个单词", 17f, brown()).also { it.gravity = Gravity.CENTER })
        wordOptions.forEachIndexed { i, w ->
            val btn = cartoonBtn(w, "🔤", pastel[i % 4], Color.WHITE) { }
            btn.setOnClickListener { onWordChoice(btn, w) }
            btn.textSize = 26f
            box.addView(btn, lp(mpBar(), wrapPx()).also { it.topMargin = dp(10) })
        }
        return box
    }

    /** 词选判定：对→记星+图词揭示；错→与图选错误流一致（不推进） */
    private fun onWordChoice(cell: View, picked: String) {
        val cur = items[idx]
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val correct = picked.equals(cur.card.en, ignoreCase = true)
            if (correct) {
                val r = app.sessionEngine.judge(childId, cur, cur.card.image ?: "", 1)
                stars += r.stars
                SoundFx.correct()
                hint(if (r.stars > 0) "太棒了！+${r.stars}★" else "不错哦～")
                wordStep = 2
                renderCurrent()
            } else {
                val r = app.sessionEngine.judge(childId, cur, picked, ++attempt)
                if (r.teachMode) {
                    hint("听仔细，再对它才过关哦～")
                    SoundFx.wrong()
                    wiggle(cell)
                    playCurrent()
                    handler.postDelayed({ playCurrent() }, 1200)
                    handler.postDelayed({ busy = false }, 500)
                } else {
                    hint("再试一次，选对才能过关哦～")
                    SoundFx.wrong()
                    wiggle(cell)
                    playCurrent()
                    handler.postDelayed({ busy = false }, 500)
                }
            }
        }
    }

    /** 图词揭示页：大图 + 单词 + 中文 + 点词查义 + 再听按钮 */
    private fun revealView(cur: SessionEngine.QueueItem): LinearLayout {
        val c = cur.card
        val card = vBox().apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(16), dp(16), dp(8)) }
        val imgPath = c.image?.takeIf { it.startsWith("img/") }
        if (imgPath != null) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            setupImage("content/v1/$imgPath", iv)
            card.addView(iv, LinearLayout.LayoutParams(mpBar(), dp(220)))
        }
        card.addView(textTv(c.en, 36f, brown()).also { it.gravity = Gravity.CENTER })
        card.addView(textTv(c.zh ?: "", 18f, hintColor()).also { it.gravity = Gravity.CENTER },
            lp(mp()).also { it.topMargin = dp(4) })
        val relisten = cartoonBtn("🔊 再听一次", "🔊", 0xFF4FA3D1.toInt(), Color.WHITE) { playCurrent() }
        card.addView(relisten, lp(wrapPx()).also { it.topMargin = dp(14) })
        return card
    }

    /** 揭示页底部：继续按钮（推进下一卡） */
    private fun revealBottom(): LinearLayout {
        val b = vV().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(20)) }
        val next = cartoonBtn("继续", "🐣", green(), Color.WHITE) { idx++; renderSession() }
        b.addView(next, lp(mpBar(), wrapPx()))
        return b
    }

    private fun sessionTop(cur: SessionEngine.QueueItem): LinearLayout {
        val bar = hbox().apply { setPadding(dp(14), dp(18), dp(14), dp(6)) }
        bar.addView(circleBtn("✕") { showDifficultyMap() })
        bar.addView(textTv("(${idx + 1}/${items.size}) ${cur.card.zh ?: ""}", 17f, brown()),
            LinearLayout.LayoutParams(0, wrapPx()).also { it.weight = 1f; it.leftMargin = dp(8) })
        bar.addView(textTv("★ $stars", 22f, gold()))
        return bar
    }

    private fun optionGrid(cur: SessionEngine.QueueItem): LinearLayout {
        val grid = vBox().apply { setPadding(dp(8), dp(12), dp(8), dp(8)) }
        // 平板：2 选项并排一行；手机：2 选项单列
        val cols = if (tabletMode) 2 else if (cur.optionsShuffled.size <= 2) 1 else 2
        val rows = (cur.optionsShuffled.size + cols - 1) / cols
        var ci = 0
        for (r in 0 until rows) {
            val row = hbox()
            for (c in 0 until cols) {
                val opt = cur.optionsShuffled.getOrNull(r * cols + c) ?: break
                val cell = optionCell(cur.card, opt, ci++ % 4)
                row.addView(cell, LinearLayout.LayoutParams(0, mpBar(), 1f).also { if (c == 0 && cols == 2) it.rightMargin = dp(8) })
            }
            grid.addView(row, LinearLayout.LayoutParams(mpBar(), 0, 1f).also { it.bottomMargin = dp(8) })
        }
        return grid
    }

    private fun optionCell(card: CardDef, opt: String, colorIdx: Int): View {
        val cell = FrameLayout(this).apply {
            background = rounded(pastel[colorIdx], dp(20))
        }
        cell.setOnClickListener { if (!busy) onChoice(cell, opt) }
        if (opt.startsWith("img/")) {
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            setupImage("content/v1/$opt", img)
            cell.addView(img, FrameLayout.LayoutParams(mpBar(), mpBar()))
        } else {
            cell.addView(textTv(opt, if (opt.length <= 4) 26f else 20f, brown()),
                FrameLayout.LayoutParams(mpBar(), mpBar()).also { it.gravity = Gravity.CENTER })
        }
        return cell
    }

    private fun setupImage(path: String, iv: ImageView) {
        lifecycleScope.launch {
            val bmp = runCatching { assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
            if (bmp != null) iv.setImageBitmap(bmp)
        }
    }

    // 句子跟读卡：配图 + 跟读 + 录音回放
    private fun listenCard(cur: SessionEngine.QueueItem): LinearLayout {
        val c = cur.card
        val card = vBox().apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        // 场景图
        val imgPath = c.image?.takeIf { it.startsWith("img/") }
        if (imgPath != null) {
            val iv = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(4), dp(4), dp(4), dp(4)) }
            setupImage("content/v1/$imgPath", iv)
            card.addView(iv, LinearLayout.LayoutParams(mpBar(), dp(130)))
        } else {
            card.addView(textTv(themeEmoji(), 22f, brown()))
        }
        card.addView(TextView(this).apply {
            text = buildTapText(c.en)
            textSize = 24f; setTextColor(brown()); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 3
            setHorizontallyScrolling(false)
            movementMethod = LinkMovementMethod()
        })
        card.addView(textTv(c.zh ?: "", 14f, hintColor()).also { it.gravity = Gravity.CENTER }, lp(mpBar()).also { it.topMargin = dp(4) })
        // 跟读 + 回放按钮
        val row = hbox()
        recButton = Button(this).apply {
            text = "🎤 跟读"
            setBackgroundTintList(ColorStateList.valueOf(0xFFFF8A65.toInt()))
            setTextColor(Color.WHITE); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { toggleRec() }
        }
        row.addView(recButton, lp(wrapPx(), wrapPx()))
        val playRec = Button(this).apply {
            text = "🔊 我的录音"; setBackgroundTintList(ColorStateList.valueOf(yellow())); setTextColor(brown()); textSize = 15f
            setOnClickListener { playMyRec() }
        }
        row.addView(playRec, lp(wrapPx(), wrapPx()).also { it.leftMargin = dp(10) })
        card.addView(row, lp(wrapPx()).also { it.topMargin = dp(12) })
        recResult = textTv("", 15f, hintColor())
        card.addView(recResult!!, lp(mpBar()).also { it.topMargin = dp(8) })
        return card
    }

    private fun choiceBottom(): LinearLayout {
        val b = vV().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(20)) }
        hintView = textTv("", 15f, brown()).also { it.gravity = Gravity.CENTER }
        b.addView(hintView!!, lp(mpBar()))
        // 两按钮统一全宽自适应高度：不裁字（固定高度在系统字体缩放下会裁内容）
        val replay = cartoonBtn("再听一次", "🔊", yellow(), brown()) { playCurrent() }
        b.addView(replay, lp(mpBar(), wrapPx()).also { it.topMargin = dp(6) })
        // 答对后才出现「继续」按钮（INVISIBLE 保持占位不跳动）
        nextBtn = cartoonBtn("继续", "🐣", green(), Color.WHITE) {
            nextBtn?.visibility = View.INVISIBLE
            idx++
            renderSession()
        }.apply { visibility = View.INVISIBLE }
        b.addView(nextBtn!!, lp(mpBar(), wrapPx()).also { it.topMargin = dp(8) })
        return b
    }

    private fun listenBottom(): LinearLayout {
        val b = vV().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(20)) }
        val next = cartoonBtn("下一句", "🐣", green(), Color.WHITE) { stopRec(); idx++; renderSession() }
        b.addView(next, lp(mpBar(), wrapPx()))
        return b
    }

    // =============== 批次/课程结束：直接庆祝完成 ===============
    private fun onBatchEnd() { showDone() }

    // =============== 点词查义：句子/对话卡的单词可点击，弹卡看释义+发音 ===============
    private fun buildTapText(s: String): SpannableStringBuilder {
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
                        ds.color = brown()
                        ds.isUnderlineText = false
                    }
                }, start, start + raw.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (i < parts.size - 1) sb.append(" ")
        }
        return sb
    }

    private fun cleanKey(s: String): String {
        var k = s.lowercase(Locale.ROOT)
        k = k.trim(' ', '.', ',', '!', '?', ';', ':', '(', ')', '"', '\'', '–', '’')
        return k
    }

    private fun showWordPopup(display: String, key: String) {
        val e = app.content.vocab(key)
        val overlay = FrameLayout(this).apply {
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
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            setupImage("content/v1/$imgP", iv)
            card.addView(iv, LinearLayout.LayoutParams(dp(96), dp(96)))
        }
        card.addView(textTv(e?.zh ?: "…", 18f, hintColor()).also { it.gravity = Gravity.CENTER },
            lp(mp()).also { it.topMargin = dp(6) })
        e?.pos?.let { card.addView(textTv(it, 12f, hintColor()).also { t -> t.gravity = Gravity.CENTER }) }
        val sound = cartoonBtn("🔊 发音", "🔊", 0xFF4FA3D1.toInt(), Color.WHITE) { playWordSound(key, display, e) }
        card.addView(sound, lp(wrapPx()).also { it.topMargin = dp(12) })
        val wrap = FrameLayout(this)
        wrap.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        overlay.addView(wrap, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun playWordSound(key: String, display: String, e: VocabDef?) {
        if (e?.aud != null) lifecycleScope.launch { app.player.play("content/v1/${e.aud}", fallbackText = display, slow = false) }
        else app.tts.speak(display, false)
    }

    // =============== 判定（动画 + 音效） ===============
    private fun onChoice(cell: View, picked: String) {
        val cur = items[idx]
        busy = true
        lifecycleScope.launch {
            val r = app.sessionEngine.judge(childId, cur, picked, ++attempt)
            when {
                r.correct -> {
                    // 图选步答对：进入「听音选词」步（星星在词选步记）
                    SoundFx.soft()
                    cheer(cell)
                    wordOptions = buildWordOptions(cur.card)
                    wordStep = 1
                    renderCurrent()
                }
                r.teachMode -> {
                    // 连错2次：示范态，播正确读音帮助小朋友，但【不推进】，必须答对才过关
                    hint("听仔细，再对它才过关哦～")
                    SoundFx.wrong()   // 第二次错误与第一次一致的错误音
                    wiggle(cell)
                    playCurrent()
                    handler.postDelayed({ playCurrent() }, 1200)
                    handler.postDelayed({ busy = false }, 500)
                }
                else -> {
                    // 答错：不推进，停在当前题，重放读音，直到选对
                    hint("再试一次，选对才能过关哦～")
                    SoundFx.wrong()
                    wiggle(cell)
                    playCurrent()
                    handler.postDelayed({ busy = false }, 500)
                }
            }
        }
    }

    private fun hint(s: String) { hintView?.text = s }

    /** 选对后：在选项卡底部叠加显示单词（FrameLayout） */
    private fun showWordOnCell(cell: View, word: String) {
        if (cell !is FrameLayout) return
        // 避免重复叠加
        cell.findViewWithTag<TextView>("cell_word")?.let { it.text = word; return }
        val tv = textTv(word, 22f, Color.WHITE).apply {
            tag = "cell_word"
            gravity = Gravity.CENTER
            setBackgroundColor(0xAA000000.toInt())   // 半透明底提高可读
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        cell.addView(tv, FrameLayout.LayoutParams(mpBar(), wrapPx()).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            it.bottomMargin = dp(8)
        })
    }

    // =============== 跟读 + 录音回放 ===============
    private fun toggleRec() {
        if (recording) { stopRec(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        recorder.start()
        recording = true
        recButton?.text = "⏹ 停止"
        recResult?.text = "录音中…"
    }

    private fun stopRec() {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        lastRecPcm = pcm
        recButton?.text = "🎤 跟读"
        val cur = items.getOrNull(idx) ?: return
        if (!recorder.hasVoice(pcm)) { recResult?.text = "没听到，再读一次"; return }
        lifecycleScope.launch {
            val res = app.scorer.score(cur.card.en, pcm)
            recResult?.text = when (res) {
                is ScoreOutcome.Ok -> scoreText(res.total)
                is ScoreOutcome.NoVoice -> "没听到声音"
                is ScoreOutcome.Timeout -> "稍后再评"
                is ScoreOutcome.Error -> "评错：${res.msg}"
            }
        }
    }

    /** 播放自己的录音（PCM → WAV → MediaPlayer） */
    private fun playMyRec() {
        val pcm = lastRecPcm ?: return
        val baos = ByteArrayOutputStream()
        val len = pcm.size
        baos.write("RIFF".toByteArray())
        baos.write(intLE(36 + len))
        baos.write("WAVE".toByteArray())
        baos.write("fmt ".toByteArray())
        baos.write(intLE(16))
        baos.write(shortLE(1)); baos.write(shortLE(1)); baos.write(intLE(16000)); baos.write(intLE(32000))
        baos.write(shortLE(2)); baos.write(shortLE(16))
        baos.write("data".toByteArray())
        baos.write(intLE(len))
        baos.write(pcm)
        val wav = baos.toByteArray()
        lifecycleScope.launch {
            val f = java.io.File(cacheDir, "rec_${System.currentTimeMillis()}.wav")
            f.writeBytes(wav)
            val mp = MediaPlayer()
            runCatching { mp.setDataSource(f.absolutePath); mp.prepare(); mp.start() }
        }
    }

    private fun shortLE(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun intLE(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )

    private fun scoreText(score5: Float): String {
        val st = StarMapper.stars(currentBand, score5) ?: 0
        val sb = StringBuilder()
        repeat(3) { i -> sb.append(if (i < st) "★" else "☆") }
        return "$sb ${(score5 * 20).toInt()}分"
    }

    // =============== 结束页 ===============
    private fun showDone() {
        SoundFx.cheer()
        val root = vBox().apply {
            gravity = Gravity.CENTER
            setBackgroundColor(bg())
            setPadding(dp(24), dp(24), dp(24), dp(64))
        }
        root.addView(textTv("🎉 恭喜你完成任务！", 26f, brown()).also { it.gravity = Gravity.CENTER })
        root.addView(textTv("获得 +$stars★", 20f, gold()).also { it.gravity = Gravity.CENTER }, lp(mp()).also { it.topMargin = dp(12) })

        // 继续 / 完成
        val row = hbox()
        val continueBtn = cartoonBtn("继续", "🐣", green(), Color.WHITE) { continueNextTheme() }
        row.addView(continueBtn, LinearLayout.LayoutParams(0, wrapPx(), 1f).also { it.rightMargin = dp(8) })
        val doneBtn = cartoonBtn("完成", "🎉", yellow(), brown()) {
            completedThemes.add(curTheme)
            showLessons(curTheme, curLevel)
        }
        row.addView(doneBtn, LinearLayout.LayoutParams(0, wrapPx(), 1f).also { it.leftMargin = dp(8) })
        root.addView(row, lp(mp()).also { it.topMargin = dp(24) })
        setView(root)
    }

    /** 继续：优先进入本主题下一课；课程学完则进入下一主题 */
    private fun continueNextTheme() {
        val lessons = lessonsFor(curTheme, currentBand)
        val next = lessons.getOrNull(curLesson?.idx ?: 0)   // idx 从 1 起：下一课正好是 lessons[idx]
        if (next != null) startLesson(next)
        else {
            val themeOrder = app.content.themes.keys.toList()
            val idxNow = themeOrder.indexOf(curTheme)
            val nt = if (idxNow >= 0 && idxNow < themeOrder.size - 1) themeOrder[idxNow + 1] else themeOrder.firstOrNull() ?: "body"
            showWarm(nt, curLevel)
        }
    }

    // =============== 播放 ===============
    private fun playCurrent() {
        val c = items.getOrNull(idx)?.card ?: return
        lifecycleScope.launch { app.player.play("content/v1/${c.audio}", fallbackText = c.en, slow = false) }
    }

    private fun playEn(en: String) {
        // 用卡查 audio
        val c = app.content.cards.values.firstOrNull { it.en.equals(en, ignoreCase = true) }
        if (c != null) {
            lifecycleScope.launch { app.player.play("content/v1/${c.audio}", fallbackText = c.en, slow = false) }
        } else app.tts.speak(en, false)
    }

    // =============== 动画 ===============
    private fun cheer(v: View) {
        val sx = ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.12f, 1f)
        val sy = ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.12f, 1f)
        AnimatorSet().apply { playTogether(sx, sy); duration = 500 }.start()
    }

    private fun wiggle(v: View) {
        ObjectAnimator.ofFloat(v, "translationX", 0f, -10f, 10f, -6f, 6f, 0f).apply { duration = 400 }.start()
    }

    private fun animateIn(v: View) {
        val a = ObjectAnimator.ofFloat(v, "scaleX", 0.6f, 1f)
        val b = ObjectAnimator.ofFloat(v, "scaleY", 0.6f, 1f)
        AnimatorSet().apply { playTogether(a, b); duration = 400 }.start()
    }

    // =============== UI 工具 ===============
    private fun setView(v: View) {
        // 切页时：取消所有延迟任务（防返回后旧页延迟播放仍触发）+ 停止当前音频
        handler.removeCallbacksAndMessages(null)
        app.player.stop()
        app.tts.stop()
        container.removeAllViews()
        container.addView(v, FrameLayout.LayoutParams(mpBar(), mpBar()))
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun mpBar() = LinearLayout.LayoutParams.MATCH_PARENT
    private fun mp() = mpBar()
    private fun wrapPx() = LinearLayout.LayoutParams.WRAP_CONTENT
    private fun wrap() = wrapPx()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun lp(w: Int) = LinearLayout.LayoutParams(w, wrapPx())
    private fun vBox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun linearV(c: AppCompatActivity) = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
    private fun hbox() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun vV() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun bg() = ContextCompat.getColor(this, R.color.bg_warm)
    private fun brown() = ContextCompat.getColor(this, R.color.text_brown)
    private fun hintColor() = ContextCompat.getColor(this, R.color.text_hint)
    private fun gold() = ContextCompat.getColor(this, R.color.star_gold)
    private fun yellow() = ContextCompat.getColor(this, R.color.accent_yellow)
    private fun green() = ContextCompat.getColor(this, R.color.primary_green)
    private fun greenDark() = ContextCompat.getColor(this, R.color.primary_green_dark)

    private fun textTv(s: String, size: Float, color: Int) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); typeface = Typeface.DEFAULT_BOLD
    }
    private fun subTv(s: String) = TextView(this).apply { text = s; textSize = 14f; setTextColor(hintColor()); gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) }
    private fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = r.toFloat() }

    /** 卡通圆角按钮：emoji 图标引导 + 大圆角胶囊粗体，自适应高度不裁字（对不识字的小朋友友好） */
    private fun cartoonBtn(text: String, emoji: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = "$emoji  $text"; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        background = rounded(bg, dp(30))
        setPadding(dp(22), dp(10), dp(22), dp(10))
        minHeight = dp(56)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun topBar(title: String, left: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(circleBtn("←", left))
        addView(textTv(title, 18f, brown()), LinearLayout.LayoutParams(0, wrapPx()).also { it.weight = 1f; it.leftMargin = dp(8) })
    }

    private fun circleBtn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t; textSize = 20f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(brown())
        background = rounded(0xFFE8F0FE.toInt(), dp(24))
        minWidth = dp(48); minHeight = dp(48)
        gravity = Gravity.CENTER
        setOnClickListener { onClick() }
    }

    private fun levelRow(lv: Level, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(Color.WHITE, dp(18))
        setOnClickListener { onClick() }
        val badge = TextView(this@MainActivity).apply {
            text = lv.short; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(lv.color)
        }
        addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)).also { it.rightMargin = dp(12) })
        val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        r.addView(textTv(lv.name, 16f, brown()))
        r.addView(textTv(lv.desc, 12f, hintColor()))
        addView(r, LinearLayout.LayoutParams(0, wrapPx()).apply { weight = 1f })
        addView(textTv("›", 22f, brown()))
    }

    private fun levelRowAll(onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = rounded(0xFFE8F5EE.toInt(), dp(18))
        setOnClickListener { onClick() }
        addView(textTv("▶ 全部连练", 16f, greenDark()), LinearLayout.LayoutParams(0, wrapPx()).apply { weight = 1f })
        addView(textTv("L1→L6 不间断闯关", 12f, hintColor()))
    }

    private fun themeRow(emoji: String, name: String, sub: String, completed: Boolean, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(0xFFFFF3E0.toInt(), dp(20))
        setOnClickListener { onClick() }
        addView(textTv(emoji, 34f, brown()), LinearLayout.LayoutParams(wrapPx(), wrapPx()).also { it.rightMargin = dp(12) })
        val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        r.addView(textTv(name, 20f, brown()))
        r.addView(textTv(sub, 13f, hintColor()))
        addView(r, LinearLayout.LayoutParams(0, wrapPx()).apply { weight = 1f })
        addView(textTv(if (completed) "✓" else "›", 22f, if (completed) green() else brown()))
    }

    private fun modeToggle() = Button(this).apply {
        text = if (tabletMode) "🖥 平板模式（点此切手机）" else "📱 手机模式（点此切平板）"
        textSize = 15f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(brown())
        background = rounded(0xFFFFF3E0.toInt(), dp(28))
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener {
            tabletMode = !tabletMode
            prefs.edit().putBoolean("tablet_mode", tabletMode).apply()
            showDifficultyMap()
        }
    }

    private fun themeEmoji(): String = THEME_META[curTheme]?.emoji ?: "📖"

    override fun onDestroy() {
        app.player.stop()
        recorder.stop()
        super.onDestroy()
    }
}