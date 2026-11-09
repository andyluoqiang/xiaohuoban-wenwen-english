package com.family.listenspeak.audio

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 系统 TTS 兜底发音：素材音频未生成前，用设备自带 TTS（美音）读词/读句。
 * App 立即"有声"；CON-03 批量音频生成后自动优先使用预置音频。
 */
class TtsSpeaker(ctx: Context) {

    private var tts: TextToSpeech? = null
    @Volatile var ready = false; private set

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                runCatching {
                    tts?.language = Locale.US
                    tts?.setSpeechRate(0.95f)
                    tts?.setPitch(1.05f)
                    ready = true
                }
            }
        }
    }

    fun speak(text: String, slow: Boolean) {
        val t = tts ?: return
        if (!ready) return
        runCatching {
            t.setSpeechRate(if (slow) 0.8f else 0.95f)
            t.setPitch(1.08f)
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ls_${System.currentTimeMillis()}")
        }
    }

    /** 停止当前朗读（切页时用，不销毁引擎） */
    fun stop() {
        runCatching { tts?.stop() }
    }

    fun shutdown() {
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null; ready = false
    }
}
