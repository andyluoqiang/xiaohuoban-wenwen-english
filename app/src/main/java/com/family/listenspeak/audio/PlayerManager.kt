package com.family.listenspeak.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import kotlin.coroutines.resume

/**
 * 音频播放：优先 assets 预置音频；素材未生成时自动降级为系统 TTS 朗读（App 即刻有声）。
 */
class PlayerManager(
    private val ctx: Context,
    private val app: Context? = null
) : Closeable {

    private var player: MediaPlayer? = null

    /**
     * @param assetPath assets 相对路径（content/v1/audio/...）
     * @param fallbackText 音频缺失时用 TTS 读的英文
     */
    suspend fun play(assetPath: String, fallbackText: String? = null, slow: Boolean = false) {
        if (assetExists(assetPath)) {
            playAsset(assetPath)
        } else {
            fallbackText?.let { text ->
                (app as? com.family.listenspeak.App)?.tts?.speak(text, slow)
            }
        }
    }

    private fun assetExists(path: String): Boolean = runCatching {
        ctx.assets.open(path).use { it.available() }
        true
    }.getOrDefault(false)

    private suspend fun playAsset(assetPath: String) = suspendCancellableCoroutine<Unit> { cont ->
        stop()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // 首选 openFd（零拷贝）；个别打包方式下 assets 无法 openFd 时，转存 cache 再播（保证一定有声音）
            try {
                ctx.assets.openFd(assetPath).use { afd ->
                    mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
            } catch (fdEx: Exception) {
                val cache = java.io.File(ctx.cacheDir, "np_play.tmp")
                ctx.assets.open(assetPath).use { input ->
                    cache.outputStream().use { out -> input.copyTo(out) }
                }
                mp.setDataSource(cache.absolutePath)
            }
            mp.isLooping = false
            mp.setOnCompletionListener {
                runCatching { it.release() }
                if (player === it) player = null
                if (cont.isActive) cont.resume(Unit)
            }
            mp.setOnErrorListener { p, _, _ ->
                runCatching { runCatching { p.stop() }; p.release() }
                if (player === p) player = null
                if (cont.isActive) cont.resume(Unit)   // 出错不阻塞流程
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            runCatching { mp.release() }
            if (player === mp) player = null
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation { runCatching { mp.stop(); mp.release() } }
    }

    fun stop() {
        player?.let { runCatching { it.stop(); it.release() } }
        player = null
    }

    override fun close() = stop()
}
