package com.family.listenspeak.eval

/** 评测引擎抽象层（01 文档 §3.4）：换引擎不改 UI 与计分 */
sealed class ScoreOutcome {
    data class Ok(
        val total: Float,                 // 0-5（讯飞 5 分制）
        val words: List<WordScore>,
        val engine: String,
        val latencyMs: Long
    ) : ScoreOutcome()

    object NoVoice : ScoreOutcome()          // 本地预检未过（无声/过短）
    object Timeout : ScoreOutcome()          // 4s 硬超时 → 入补评队列
    data class Error(val msg: String) : ScoreOutcome()
}

data class WordScore(val text: String, val score5: Float)

interface ISpeechScorer {
    /** en：参考文本；pcm：16k/16bit/mono PCM 原始字节 */
    suspend fun score(en: String, pcm: ByteArray): ScoreOutcome

    /** 引擎是否可用（密钥已配置） */
    fun available(): Boolean
}

/** 评测类别：单词 / 句子（映射讯飞 read_word / read_sentence） */
object EvalCategory {
    fun of(en: String): String =
        if (en.trim().split(Regex("\\s+")).size <= 1) "read_word" else "read_sentence"
}
