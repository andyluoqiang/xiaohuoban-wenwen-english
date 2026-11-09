package com.family.listenspeak.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * 跟读录音器：16k/16bit/单声道 PCM（评测引擎标准输入）。
 * 一键开始 / stop() 结束；返回 PCM 字节流。
 */
class PcmRecorder {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    val buffer = ByteArrayOutputStream()

    val isRecording: Boolean get() = running

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        fun minBufSize(): Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
    }

    @SuppressLint("MissingPermission")   // 调用方已确保 RECORD_AUDIO 权限
    fun start(): Boolean {
        if (running) return true
        val minBuf = minBufSize()
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING,
            maxOf(minBuf, SAMPLE_RATE)   // ≥0.5s 缓冲
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        buffer.reset()
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val chunk = ShortArray(1024)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) {
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        bytes[i * 2] = (chunk[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (chunk[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    synchronized(buffer) { buffer.write(bytes) }
                }
            }
        }.also { it.start() }
        return true
    }

    /** 结束并取走 PCM 数据 */
    fun stop(): ByteArray {
        running = false
        runCatching { thread?.join(500) }
        record?.let { runCatching { it.stop(); it.release() } }
        record = null; thread = null
        synchronized(buffer) { return buffer.toByteArray() }
    }

    /** 简易 RMS 静音预检（01 文档 §2：无声/太短不送评测） */
    fun hasVoice(pcm: ByteArray): Boolean {
        if (pcm.size < 16000 * 2 * 6 / 10) return false   // <0.6s
        var sum = 0.0
        var i = 0
        var count = 0
        while (i + 1 < pcm.size) {
            val s = (pcm[i].toInt() and 0xFF or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
            sum += (s * s).toDouble(); count++
            i += 320
        }
        val rms = kotlin.math.sqrt(sum / count)
        return rms > 250.0
    }
}
