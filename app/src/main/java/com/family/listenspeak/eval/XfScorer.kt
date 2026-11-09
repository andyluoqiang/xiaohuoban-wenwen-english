package com.family.listenspeak.eval

import com.family.listenspeak.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * 讯飞语音评测（流式版）WebAPI —— 与 tools/ise_test.py 同协议（W0 已实测通过）。
 * wss://ise-api.xfyun.cn/v2/open-ise
 * 鉴权：HMAC-SHA256 签名 URL；音频 16k/16bit/mono PCM 分帧 base64 上传。
 */
class XfScorer : ISpeechScorer {

    companion object {
        const val HOST = "ise-api.xfyun.cn"
        const val PATH = "/v2/open-ise"
        const val FRAME = 1280
        const val INTERVAL_MS = 40L
        const val HARD_TIMEOUT = 4000L      // 01 文档：4s 硬超时
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(HARD_TIMEOUT, TimeUnit.MILLISECONDS)
        .writeTimeout(HARD_TIMEOUT, TimeUnit.MILLISECONDS)
        .build()

    override fun available(): Boolean =
        BuildConfig.XF_APP_ID.isNotEmpty() &&
        BuildConfig.XF_API_KEY.isNotEmpty() &&
        BuildConfig.XF_API_SECRET.isNotEmpty()

    override suspend fun score(en: String, pcm: ByteArray): ScoreOutcome = withContext(Dispatchers.IO) {
        if (!available()) return@withContext ScoreOutcome.Error("密钥未配置")
        val started = System.currentTimeMillis()
        val inner = suspendCancellableCoroutine<ScoreOutcome> { cont ->
            val wsRef = arrayOfNulls<WebSocket>(1)
            val xml = arrayOfNulls<String>(1)
            var firstSent = false

            fun sendAll(ws: WebSocket, data: ByteArray) {
                var idx = 0
                var frameIdx = 0
                val chunks = (data.size + FRAME - 1) / FRAME
                while (idx < data.size) {
                    val end = minOf(idx + FRAME, data.size)
                    val b64 = Base64.getEncoder().encodeToString(data.copyOfRange(idx, end))
                    val aus = when {
                        chunks == 1 -> 4
                        frameIdx == 0 -> 1
                        frameIdx == chunks - 1 -> 4
                        else -> 2
                    }
                    val payload = JSONObject().apply {
                        put("business", JSONObject().put("cmd", "auw").put("aus", aus))
                        put("data", JSONObject().put("status", if (aus == 4) 2 else 1).put("data", b64))
                    }
                    ws.send(payload.toString())
                    idx = end; frameIdx++
                    if (frameIdx < chunks) Thread.sleep(INTERVAL_MS)
                }
            }

            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val business = JSONObject().apply {
                        put("sub", "ise")
                        put("ent", "en_vip")
                        put("category", EvalCategory.of(en))
                        put("cmd", "ssb")
                        put("text", "\uFEFF" + buildRefText(en))
                        put("ttp_skip", true)
                        put("aue", "raw")
                        put("auf", "audio/L16;rate=16000")
                        put("tte", "utf-8")
                    }
                    val first = JSONObject().apply {
                        put("common", JSONObject().put("app_id", BuildConfig.XF_APP_ID))
                        put("business", business)
                        put("data", JSONObject().put("status", 0))
                    }
                    webSocket.send(first.toString())
                    firstSent = true
                    sendAll(webSocket, pcm)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val msg = JSONObject(text)
                    val code = msg.optInt("code", -1)
                    if (code != 0) {
                        if (cont.isActive) cont.resume(ScoreOutcome.Error("code=$code ${msg.optString("message")}"))
                        webSocket.close(1000, null); return
                    }
                    val data = msg.optJSONObject("data") ?: return
                    if (data.optInt("status") == 2) {
                        val b64 = data.optString("data", "")
                        if (b64.isNotEmpty()) xml[0] = String(Base64.getDecoder().decode(b64))
                        if (cont.isActive) {
                            cont.resume(parse(xml[0] ?: "", started))
                        }
                        webSocket.close(1000, null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isActive) cont.resume(ScoreOutcome.Timeout)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // 正常关闭但没拿到结果 → 视为超时（走补评）
                    if (cont.isActive && xml[0] == null) cont.resume(ScoreOutcome.Timeout)
                }
            }

            val url = buildAuthUrl(BuildConfig.XF_API_KEY, BuildConfig.XF_API_SECRET)
            wsRef[0] = client.newWebSocket(Request.Builder().url(url).build(), listener)
            cont.invokeOnCancellation { wsRef[0]?.cancel() }
        }
        // 外层硬超时
        val r = withTimeoutOrNull(HARD_TIMEOUT) { inner } ?: ScoreOutcome.Timeout
        r
    }

    /** 参考文本：word→[word]；sentence→[content]（讯飞试题格式） */
    private fun buildRefText(en: String): String {
        return if (EvalCategory.of(en) == "read_word") {
            "[word]\n" + en.trim()
        } else {
            "[content]\n" + en.trim()
        }
    }

    /** 解析讯飞 XML：read_sentence/read_word 的 total_score + word@total_score */
    private fun parse(xml: String, started: Long): ScoreOutcome {
        return try {
            var total: Float? = null
            val words = mutableListOf<WordScore>()
            val factory = XmlPullParserFactory.newInstance()
            val p = factory.newPullParser()
            p.setInput(InputStreamReader(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8))))
            var event = p.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (p.name) {
                        "read_sentence", "read_word", "read_chapter" ->
                            p.getAttributeValue(null, "total_score")?.toFloatOrNull()?.let {
                                if (total == null) total = it
                            }
                        "word" -> {
                            val w = p.getAttributeValue(null, "content") ?: ""
                            p.getAttributeValue(null, "total_score")?.toFloatOrNull()?.let {
                                if (w.isNotBlank()) words.add(WordScore(w, it))
                            }
                        }
                    }
                }
                event = p.next()
            }
            val t = total
            if (t == null) ScoreOutcome.Error("无总分: ${xml.take(120)}")
            else ScoreOutcome.Ok(t, words, "xf", System.currentTimeMillis() - started)
        } catch (e: Exception) {
            ScoreOutcome.Error("解析失败: ${e.message}")
        }
    }

    /** WSS 鉴权 URL（与官方文档/ise_test.py 相同） */
    private fun buildAuthUrl(apiKey: String, apiSecret: String): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        val date = fmt.format(Date())
        val origin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(apiSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = Base64.getEncoder().encodeToString(mac.doFinal(origin.toByteArray(Charsets.UTF_8)))
        val authOrigin = "api_key=\"$apiKey\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$sig\""
        val authorization = Base64.getEncoder().encodeToString(authOrigin.toByteArray(Charsets.UTF_8))
        return "wss://$HOST$PATH?authorization=${urlEncode(authorization)}&date=${urlEncode(date)}&host=$HOST"
    }

    private fun urlEncode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
