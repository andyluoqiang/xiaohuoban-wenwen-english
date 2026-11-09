package com.family.listenspeak

import android.app.Application
import android.util.Log
import com.family.listenspeak.audio.PlayerManager
import com.family.listenspeak.audio.SoundFx
import com.family.listenspeak.audio.TtsSpeaker
import com.family.listenspeak.data.ContentLoader
import com.family.listenspeak.data.LsDb
import com.family.listenspeak.eval.XfScorer
import com.family.listenspeak.learn.ReviewEngine
import com.family.listenspeak.learn.SessionEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var db: LsDb; private set
    lateinit var content: ContentLoader; private set
    lateinit var player: PlayerManager; private set
    val tts: TtsSpeaker by lazy { TtsSpeaker(this) }
    val scorer: XfScorer by lazy { XfScorer() }
    lateinit var reviewEngine: ReviewEngine; private set
    lateinit var sessionEngine: SessionEngine; private set

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        db = LsDb.get(this)
        content = ContentLoader(this)
        player = PlayerManager(this, this)
        reviewEngine = ReviewEngine(db.reviewDao())
        sessionEngine = SessionEngine(db, content, reviewEngine)
        // TTS 预热（兜底发音）
        tts
        // 反馈音效预加载（正确/错误音）
        SoundFx.init(this)
        appScope.launch { runCatching { content.load() } }
    }

    /** 崩溃日志：写到应用外部私有目录，可在手机 文件管理/Android/data/com.family.listenspeak/files/ 取出 */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val f = File(dir, "crash_${System.currentTimeMillis()}.log")
                f.writeText(
                    "time=${System.currentTimeMillis()}\nthread=${thread.name}\nversion=0.2.0\n\n" +
                        Log.getStackTraceString(throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
