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

    // ===== 课程制（每课 10 词）两轮制：第1遍听音选图（选对图上叠词），第2遍乱序听音选词（选对图显词下） =====
    private data class LessonDef(val id: String, val idx: Int, val title: String,
                                  val cards: List<CardDef>, val cam: Boolean = false)
    private var curLesson: LessonDef? = null
    private var round = 0                       // 词课：0=图轮 1=词轮；句课恒 0
    private var round2Items: List<SessionEngine.QueueItem> = emptyList()   // 词轮队列（乱序）
    private var wordImgSlot: FrameLayout? = null // 词轮：选对后图片显示区（在文字下面）
    private var wordOptions: List<String> = emptyList()   // 词轮选项

    private fun curQueue(): List<SessionEngine.QueueItem> =
        if (round == 1 && round2Items.isNotEmpty()) round2Items else items

    private val recorder = PcmRecorder()
    private val handler = Handler(Looper.getMainLooper())
    private var recButton: Button? = null
    private var recording = false
    private var lastRecPcm: ByteArray? = null
    private var hintView: TextView? = null
    private var nextBtn: Button? = null   // 选择卡（词卡）「继续」

    // ===== 跟读评分（句子卡）：≥60 分才出「下一句」；低分只提示重试 =====
    private val PASS_PERCENT = 60                       // 百分制及格线
    private var listenNext: Button? = null              // 句子卡底部「下一句」（≥60 才显示）
    private var listenResult: TextView? = null          // 句子卡跟读结果（分数+表情+标签）

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
        "animals" to ThemeMeta("🐶", "动物与宠物"),
        "clothes" to ThemeMeta("👕", "衣服与配饰"),
        "home" to ThemeMeta("🏠", "在家里"),
        "school" to ThemeMeta("🏫", "在学校"),
        "toys" to ThemeMeta("🧸", "玩具世界"),
        "transport" to ThemeMeta("🚌", "交通工具"),
        "beach" to ThemeMeta("🏖️", "海滩玩耍"),
        "family" to ThemeMeta("👨‍👩‍👧", "家人与朋友"),
        "rooms" to ThemeMeta("🛋️", "房间与家具"),
        "sports" to ThemeMeta("🏀", "运动比赛"),
        "mv_town" to ThemeMeta("🏙️", "城镇与场所"),
        "mv_nature" to ThemeMeta("🌳", "自然与天气"),
        "mv_home" to ThemeMeta("🏡", "家居与用品"),
        "mv_food" to ThemeMeta("🍕", "更多食物"),
        "mv_people" to ThemeMeta("👩‍🍳", "人物与职业"),
        "mv_looks" to ThemeMeta("🧔", "外貌与身体"),
        "mv_clothes" to ThemeMeta("🧥", "更多衣物"),
        "mv_media" to ThemeMeta("💻", "媒体与学习"),
        "fl_nature" to ThemeMeta("🌍", "自然与地球"),
        "fl_animal" to ThemeMeta("🦅", "动物与生物"),
        "fl_job" to ThemeMeta("👨‍🚒", "职业与身份"),
        "fl_place" to ThemeMeta("🏛️", "场所与建筑"),
        "fl_food" to ThemeMeta("🍯", "厨房与食物"),
        "fl_object" to ThemeMeta("🎒", "物品与服饰"),
        "fl_body" to ThemeMeta("🩹", "身体与健康"),
        "fl_world" to ThemeMeta("🎿", "四季与出行")
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
        // 版本指纹：装对/装错一眼可辨（内容改版后递增，主题数随 manifest 更新）
        col.addView(TextView(this).apply {
            text = "v0.2.14 · 29主题"
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
        val band = if (levelKey == "all") null else (levelKey.substring(1).toIntOrNull() ?: 1)
        val sc = ScrollView(this).apply { setBackgroundColor(bg()) }
        val col = vBox().apply { setPadding(dp(20), dp(18), dp(20), dp(64)) }
        col.addView(topBar("难度 · ${lv?.short ?: "全部连练"}", left = { showDifficultyMap() }))
        app.content.themes.forEach { (tid, tc) ->
            val meta = THEME_META[tid] ?: ThemeMeta("📖", tc.nameZh)
            // 卡片数跟随当前档位：仅统计该档位可学单元（无则 0）；「全部」统计全量
            val cards = tc.units.filter { band == null || it.band == band }.sumOf { it.cards.size }
            col.addView(themeRow(meta.emoji, meta.nameZh, "共 $cards 张卡", completedThemes.contains(tid), onClick = { showLessons(tid, levelKey) }),
                lp(mp()).also { it.topMargin = dp(12) })
        }
        sc.addView(col)
        setView(sc)
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

    /** 会话启动：词课两轮（图轮→乱序词轮）；句课一轮跟读 */
    private fun startSession(cards: List<CardDef>) {
        backAction = { showDifficultyMap() }
        stage = if (currentBand <= 2) 0 else 1
        val pool = app.content.imagePool(curTheme)
        val want = if (currentBand <= 1) 2 else 4
        items = cards.map { c ->
            val opts = if (c.type in LISTEN_TYPES) emptyList() else buildOpts(c, pool, want)
            SessionEngine.QueueItem(c, false, opts)
        }.filter { it.optionsShuffled.isNotEmpty() || it.card.type in LISTEN_TYPES }
        idx = 0; stars = 0; round = 0
        renderSession()
    }

    /** 词选步选项：正确词 + 同主题干扰词（仅词/短语卡，L1 二选一，L2 四选一） */
    private fun buildWordOptions(card: CardDef): List<String> {
        val want = if (currentBand <= 1) 2 else 4
        val pool = app.content.themes[curTheme]?.units
            ?.filter { it.band == currentBand }
            ?.flatMap { it.cards }
            ?.filter { it.type == "word" || it.type == "phrase" }
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

    // =============== 会话渲染（两轮制） ===============
    private fun renderSession() {
        attempt = 0
        val queue = curQueue()
        val cur = queue.getOrNull(idx)
        if (cur == null) { onBatchEnd(); return }
        busy = false
        wordImgSlot = null
        val root = vBox().apply { setBackgroundColor(bg()) }
        root.addView(sessionTop(cur))
        if (stage == 1 || cur.optionsShuffled.isEmpty()) {
            // 句子课：一轮跟读（逐词可点 + 跟读评分）
            val sc = ScrollView(this).apply { setBackgroundColor(bg()); isFillViewport = true }
            sc.addView(listenCard(cur))
            root.addView(sc, lp(mpBar(), 0).also { it.weight = 1f })
            root.addView(listenBottom())
        } else if (round == 0) {
            // 第 1 遍：🎧 听音选图（选对 → 单词叠加在图上）
            root.addView(optionGrid(cur), lp(mpBar(), 0).also { it.weight = 1f })
            root.addView(choiceBottom())
        } else {
            // 第 2 遍：🔤 听音选词（选对 → 图片显示在文字下面）
            val sc = ScrollView(this).apply { setBackgroundColor(bg()); isFillViewport = true }
            sc.addView(wordRoundView(cur))
            root.addView(sc, lp(mpBar(), 0).also { it.weight = 1f })
            root.addView(choiceBottom())
        }
        setView(root)
        animateIn(root.getChildAt(1))
        handler.postDelayed({ playCurrent() }, 400)
    }

    /** 词轮视图：题干 + 大词按钮（下方预留图片显示区） */
    private fun wordRoundView(cur: SessionEngine.QueueItem): LinearLayout {
        val box = vBox().apply { setPadding(dp(12), dp(12), dp(12), dp(8)) }
        box.addView(textTv("🔤 第2遍 · 听一听，选出这个单词", 16f, brown()).also { it.gravity = Gravity.CENTER })
        wordOptions = buildWordOptions(cur.card)
        wordOptions.forEachIndexed { i, w ->
            val btn = cartoonBtn(w, "", pastel[i % 4], Color.WHITE) { }
            btn.setOnClickListener { onWordChoice(btn, w) }
            btn.textSize = 24f
            box.addView(btn, lp(mpBar(), wrapPx()).also { it.topMargin = dp(8) })
        }
        // 图片显示区（选对后填充，在文字按钮下面）
        val slot = FrameLayout(this).apply {
            background = rounded(Color.WHITE, dp(20))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        wordImgSlot = slot
        box.addView(slot, LinearLayout.LayoutParams(mpBar(), wrapPx()).also { it.topMargin = dp(10) })
        return box
    }

    /** 词轮选对：图片 + 中文显示在单词按钮区域下面（原位填充 slot） */
    private fun showImageUnderWords(cur: SessionEngine.QueueItem) {
        val slot = wordImgSlot ?: return
        val imgPath = cur.card.image?.takeIf { it.startsWith("img/") }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        if (imgPath != null) setupImage("content/v1/$imgPath", iv)
        slot.addView(iv, FrameLayout.LayoutParams(mpBar(), dp(180)))
        cur.card.zh?.let { slot.addView(textTv(it, 14f, hintColor()).also { t ->
            t.gravity = Gravity.CENTER }, FrameLayout.LayoutParams(mpBar(), wrapPx()).also {
            it.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; it.bottomMargin = dp(6) }) }
    }

    /** 继续：图轮完 → 进入乱序词轮；词轮完 → 完成页 */
    private fun advanceCard() {
        idx++
        if (idx < curQueue().size) { renderSession(); return }
        if (stage == 0 && round == 0 && currentBand <= 2) {
            // 图轮完成 → 词轮（乱序）
            round = 1
            round2Items = items.shuffled()
            idx = 0
            renderSession()
        } else showDone()
    }

    private fun sessionTop(cur: SessionEngine.QueueItem): LinearLayout {
        val bar = hbox().apply { setPadding(dp(14), dp(18), dp(14), dp(6)) }
        bar.addView(circleBtn("✕") { showDifficultyMap() })
        val tag = when {
            stage == 1 -> ""
            round == 0 -> "🎧 第1遍 · 看图 "
            else -> "🔤 第2遍 · 认词 "
        }
        bar.addView(textTv("$tag(${idx + 1}/${curQueue().size}) ${cur.card.zh ?: ""}", 16f, brown()),
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
        // 跟读 + 回放按钮（加大 + 矢量图标，方便小朋友识别；自适应高度 + minHeight 保底不裁字）
        val row = hbox()
        recButton = Button(this).apply {
            text = "跟读"
            setBackgroundTintList(ColorStateList.valueOf(0xFFFF8A65.toInt()))
            setTextColor(Color.WHITE); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
            val mic = resources.getDrawable(R.drawable.ic_mic).mutate(); mic.setTint(Color.WHITE)
            val sz = dp(42); mic.setBounds(0, 0, sz, sz)
            setCompoundDrawablesRelative(mic, null, null, null)
            setCompoundDrawablePadding(dp(12))
            minHeight = dp(64); setPadding(dp(28), dp(10), dp(28), dp(10))
            setOnClickListener { toggleRec() }
        }
        row.addView(recButton, lp(wrapPx(), wrapPx()))
        val playRec = Button(this).apply {
            text = "我的录音"
            setBackgroundTintList(ColorStateList.valueOf(yellow())); setTextColor(brown()); textSize = 18f
            val spk = resources.getDrawable(R.drawable.ic_speaker).mutate(); spk.setTint(brown())
            val sz = dp(42); spk.setBounds(0, 0, sz, sz)
            setCompoundDrawablesRelative(spk, null, null, null)
            setCompoundDrawablePadding(dp(12))
            minHeight = dp(64); setPadding(dp(22), dp(10), dp(22), dp(10))
            setOnClickListener { playMyRec() }
        }
        row.addView(playRec, lp(wrapPx(), wrapPx()).also { it.leftMargin = dp(12) })
        card.addView(row, lp(wrapPx()).also { it.topMargin = dp(12) })
        listenResult = textTv("", 15f, hintColor()).also { it.gravity = Gravity.CENTER; it.minHeight = dp(44) }
        card.addView(listenResult!!, lp(mpBar()).also { it.topMargin = dp(8) })
        return card
    }

    private fun choiceBottom(): LinearLayout {
        val b = vV().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(20)) }
        hintView = textTv("", 15f, brown()).also { it.gravity = Gravity.CENTER }
        b.addView(hintView!!, lp(mpBar()))
        // 两按钮统一全宽自适应高度：不裁字（固定高度在系统字体缩放下会裁内容）；图标统一为矢量，与「继续」协调
        val replay = cartoonBtnIcon("再听一次", R.drawable.ic_speaker, yellow(), brown()) { playCurrent() }
        b.addView(replay, lp(mpBar(), wrapPx()).also { it.topMargin = dp(6) })
        // 答对后才出现「继续」按钮（INVISIBLE 保持占位不跳动）：图轮完→乱序词轮；词轮完→完成
        nextBtn = cartoonBtnIcon("继续", R.drawable.ic_arrow_right, green(), Color.WHITE) {
            nextBtn?.visibility = View.INVISIBLE
            advanceCard()
        }.apply { visibility = View.INVISIBLE }
        b.addView(nextBtn!!, lp(mpBar(), wrapPx()).also { it.topMargin = dp(8) })
        return b
    }

    private fun listenBottom(): LinearLayout {
        val b = vV().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(16), dp(6), dp(16), dp(20)) }
        // 「再听一遍」：三角播放图标，重复播放模型例句（随时可点，不受评分门槛影响）
        val replay = cartoonBtnIcon("再听一遍", R.drawable.ic_play, yellow(), brown()) { playCurrent() }
        b.addView(replay, lp(mpBar(), wrapPx()).also { it.topMargin = dp(6) })
        // 「下一句」：向右箭头图标，跟读评分 ≥60 才显示（INVISIBLE 保持占位不跳动）
        listenNext = cartoonBtnIcon("下一句", R.drawable.ic_arrow_right, green(), Color.WHITE) { stopRec(); idx++; renderSession() }
            .apply { visibility = View.INVISIBLE }
        b.addView(listenNext!!, lp(mpBar(), wrapPx()).also { it.topMargin = dp(8) })
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
        val sound = cartoonBtn("发音", "🔊", 0xFF4FA3D1.toInt(), Color.WHITE) { playWordSound(key, display, e) }
        card.addView(sound, lp(wrapPx()).also { it.topMargin = dp(12) })
        val wrap = FrameLayout(this)
        wrap.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
        overlay.addView(wrap, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        container.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // 弹卡即自动读一遍；孩子可再点「🔊 发音」重复听
        playWordSound(key, display, e)
    }

    private fun playWordSound(key: String, display: String, e: VocabDef?) {
        if (e?.aud != null) lifecycleScope.launch { app.player.play("content/v1/${e.aud}", fallbackText = display, slow = false) }
        else app.tts.speak(display, false)
    }

    // =============== 判定（两轮：图轮/词轮 各自首答得星） ===============
    private fun onChoice(cell: View, picked: String) {
        val cur = curQueue()[idx]
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val r = app.sessionEngine.judge(childId, cur, picked, ++attempt)
            if (r.correct) {
                // 图轮选对：单词【叠加在图上】+ 出继续（不再切入词选，词选在第二轮乱序重来）
                stars += r.stars
                SoundFx.correct()
                hint(if (r.stars > 0) "太棒了！+${r.stars}★" else "不错哦～")
                cheer(cell)
                showWordOnCell(cell, cur.card.en, cur.card.zh)
                nextBtn?.visibility = View.VISIBLE
            } else if (r.teachMode) {
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

    /** 词轮判定：对→记星，图片+中文【显示在单词下面】（原位填充），出继续；错→不推进 */
    private fun onWordChoice(cell: View, picked: String) {
        val cur = curQueue()[idx]
        if (busy) return
        busy = true
        lifecycleScope.launch {
            val correct = picked.equals(cur.card.en, ignoreCase = true)
            if (correct) {
                val r = app.sessionEngine.judge(childId, cur, cur.card.image ?: "", 1)
                stars += r.stars
                SoundFx.correct()
                hint(if (r.stars > 0) "太棒了！+${r.stars}★" else "不错哦～")
                cheer(cell)
                showImageUnderWords(cur)
                nextBtn?.visibility = View.VISIBLE
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

    private fun hint(s: String) { hintView?.text = s }

    /** 选对后：单词【叠加显示在图片上】（FrameLayout 底部半透明白条 + 中文） */
    private fun showWordOnCell(cell: View, word: String, zh: String? = null) {
        if (cell !is FrameLayout) return
        cell.findViewWithTag<LinearLayout>("cell_word")?.let { cell.removeView(it) }
        val strip = vBox().apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(215, 255, 255, 255), dp(16))
            setPadding(dp(12), dp(6), dp(12), dp(6))
            tag = "cell_word"
        }
        strip.addView(textTv(word, 24f, brown()).also { it.gravity = Gravity.CENTER })
        zh?.let { strip.addView(textTv(it, 12f, hintColor()).also { t -> t.gravity = Gravity.CENTER }) }
        cell.addView(strip, FrameLayout.LayoutParams(mpBar(), wrapPx()).also {
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
        recButton?.text = "停止"
        listenResult?.text = "🎙 录音中…"
        listenResult?.setTextColor(hintColor())
    }

    private fun stopRec() {
        if (!recording) return
        recording = false
        val pcm = recorder.stop()
        lastRecPcm = pcm
        recButton?.text = "跟读"
        val cur = curQueue().getOrNull(idx) ?: return
        if (!recorder.hasVoice(pcm)) {
            listenResult?.text = "😕 没听到，再读一次"
            listenResult?.setTextColor(0xFFE53935.toInt())
            SoundFx.wrong()
            listenNext?.visibility = View.INVISIBLE
            return
        }
        lifecycleScope.launch {
            val res = app.scorer.score(cur.card.en, pcm)
            when (res) {
                is ScoreOutcome.Ok -> onScoreOk(res.total)
                is ScoreOutcome.NoVoice -> {
                    listenResult?.text = "😕 没听到声音，再读一次"
                    listenResult?.setTextColor(0xFFE53935.toInt()); SoundFx.wrong()
                    listenNext?.visibility = View.INVISIBLE
                }
                is ScoreOutcome.Timeout -> {
                    listenResult?.text = "⏳ 稍后再评，再读一次"
                    listenResult?.setTextColor(0xFFEF6C00.toInt())
                    listenNext?.visibility = View.INVISIBLE
                }
                is ScoreOutcome.Error -> {
                    listenResult?.text = "⚠️ 评错：${res.msg}"
                    listenResult?.setTextColor(0xFFEF6C00.toInt())
                }
            }
        }
    }

    /** 跟读打分结果：≥60 出「下一句」+ 鼓励；<60 难过 + 重读（不出现下一句） */
    private fun onScoreOk(total5: Float) {
        val pct = (total5 * 20).toInt().coerceIn(0, 100)
        val passed = pct >= PASS_PERCENT
        val stars = StarMapper.stars(currentBand, total5) ?: 0
        val starStr = StringBuilder().apply { repeat(3) { append(if (it < stars) "★" else "☆") } }
        if (passed) {
            val tag = praiseTag(pct)
            listenResult?.text = "$starStr ${pct}分 · $tag"
            listenResult?.setTextColor(0xFF2E7D32.toInt())
            SoundFx.correct()
            praiseVoice(tag)
            listenNext?.visibility = View.VISIBLE
        } else {
            listenResult?.text = "$starStr ${pct}分 · 😢 再读一次会更棒"
            listenResult?.setTextColor(0xFFE53935.toInt())
            SoundFx.wrong()
            listenNext?.visibility = View.INVISIBLE
        }
    }

    /** 打分标签：按百分制分档（good/great/perfect/amazing） */
    private fun praiseTag(pct: Int): String = when {
        pct >= 95 -> "amazing"
        pct >= 85 -> "perfect"
        pct >= 70 -> "great"
        else -> "good"
    }

    /** 播报标签语音：复用词库真人音频（amazing/great/good 已有；perfect 走 TTS 兜底） */
    private fun praiseVoice(tag: String) {
        val e = app.content.vocab(tag)
        if (e?.aud != null) lifecycleScope.launch { app.player.play("content/v1/${e.aud}", fallbackText = tag, slow = false) }
        else app.tts.speak(tag, false)
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
        val continueBtn = cartoonBtnIcon("继续", R.drawable.ic_arrow_right, green(), Color.WHITE) { continueNextTheme() }
        row.addView(continueBtn, LinearLayout.LayoutParams(0, wrapPx(), 1f).also { it.rightMargin = dp(8) })
        val doneBtn = cartoonBtn("完成", "🎉", yellow(), brown()) {
            completedThemes.add(curTheme)
            showLessons(curTheme, curLevel)
        }
        row.addView(doneBtn, LinearLayout.LayoutParams(0, wrapPx(), 1f).also { it.leftMargin = dp(8) })
        root.addView(row, lp(mp()).also { it.topMargin = dp(24) })
        setView(root)
    }

    /** 继续：优先进入本主题下一课；课程学完则进入下一主题课程树 */
    private fun continueNextTheme() {
        val lessons = lessonsFor(curTheme, currentBand)
        val next = lessons.getOrNull(curLesson?.idx ?: 0)   // idx 从 1 起：下一课正好是 lessons[idx]
        if (next != null) startLesson(next)
        else {
            val themeOrder = app.content.themes.keys.toList()
            val idxNow = themeOrder.indexOf(curTheme)
            val nt = if (idxNow >= 0 && idxNow < themeOrder.size - 1) themeOrder[idxNow + 1] else themeOrder.firstOrNull() ?: "body"
            showLessons(nt, curLevel)
        }
    }

    // =============== 播放 ===============
    private fun playCurrent() {
        val c = curQueue().getOrNull(idx)?.card ?: return
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

    /** 带矢量图标的卡通按钮：左侧 icon + 文字（icon 颜色随前景色）。
 *  用 setBounds 强制图标尺寸，与文字比例协调；所有带图标按钮统一规范，避免大小/位置不一。 */
    private fun cartoonBtnIcon(text: String, icon: Int, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; textSize = 19f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(fg)
        val ic = resources.getDrawable(icon).mutate()
        ic.setTint(fg)
        val size = dp(42)   // 图标放大到接近文字、占按钮高度50%+，幼儿清晰可辨
        ic.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(ic, null, null, null)
        setCompoundDrawablePadding(dp(12))
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