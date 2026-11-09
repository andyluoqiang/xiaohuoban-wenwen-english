package com.family.listenspeak.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import com.family.listenspeak.R
import kotlin.math.PI
import kotlin.math.sin

/**
 * 反馈音效：正确/错误使用用户选定的真人音效素材（res/raw）。
 * - correct：答对 / 完成（sfx_correct.mp3，清亮叮咚铃）
 * - wrong：答错（sfx_wrong.mp3，双声下行）
 * - soft：二答对（正确音 55% 音量）
 * - demo：示范提示（轻量合成双音）
 * 用 SoundPool 播放，不打断单词朗读（PlayerManager 独立实例）。
 */
object SoundFx {

    private var pool: SoundPool? = null
    private var correctId = 0
    private var wrongId = 0
    private var cheerId = 0

    /** App 启动时初始化（预加载音效） */
    fun init(ctx: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        correctId = p.load(ctx.applicationContext, R.raw.sfx_correct, 1)
        wrongId = p.load(ctx.applicationContext, R.raw.sfx_wrong, 1)
        cheerId = p.load(ctx.applicationContext, R.raw.sfx_cheer, 1)
        pool = p
    }

    /** 答对：用户音效原声 */
    fun correct() = playRaw(correctId, 1.0f)

    /** 答错：用户音效原声 */
    fun wrong() = playRaw(wrongId, 1.0f)

    /** 二答对：正确音轻一点 */
    fun soft() = playRaw(correctId, 0.55f)

    /** 完成：欢呼音（旋律+真实掌声） */
    fun cheer() = playRaw(cheerId, 1.0f)

    /** 示范提示：保留轻量合成双音（D5→G5 柔和） */
    fun demo() {
        val sr = 44100
        Thread {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sr)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes((sr * 0.5).toInt() * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            val notes = intArrayOf(587, 784)
            val durMs = 170; val gapMs = 40; val vol = 0.15f
            val total = notes.size * (durMs + gapMs) * sr / 1000
            val buf = ShortArray(total)
            var pos = 0
            for (f in notes) {
                val samples = durMs * sr / 1000
                val rel = samples / 3
                for (i in 0 until samples) {
                    if (pos >= total) break
                    val env = if (i > samples - rel) (samples - i).toFloat() / rel else 1f
                    val t = i.toDouble() / sr
                    val v = sin(2.0 * PI * f * t) * vol * env
                    buf[pos] = (v.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
                    pos++
                }
                pos += gapMs * sr / 1000
                if (pos > total) pos = total
            }
            runCatching {
                track.write(buf, 0, pos)
                track.play()
                Thread.sleep((pos / (sr / 1000)).toLong())
                track.stop()
            }
            runCatching { track.release() }
        }.start()
    }

    private fun playRaw(id: Int, vol: Float) {
        if (id == 0) return
        runCatching { pool?.play(id, vol, vol, 1, 0, 1.0f) }
    }
}