package com.family.listenspeak.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VPS 后台 API 客户端：注册/登录/星星同步。
 * 服务器地址在登录页配置（默认 https://www.jxonline.top/ww），存 SharedPreferences。
 * 所有请求带 6s 超时；返回 ApiResp(code, msg, 数据字段)。
 */
object ApiClient {

    const val DEFAULT_BASE = "https://www.jxonline.top/ww"
    const val CODE_OK = 0

    data class ApiResp(
        val code: Int,            // 0=成功；1=格式非法 2=账号不存在/已存在/密码错 3=其他错误 -1=网络失败
        val msg: String,
        val userId: Long = 0,
        val stars: Int = 0
    )

    // okhttp 3.x（JDK8 兼容）用传统静态工厂 API；4.x 扩展函数不适用
    private val JSON = MediaType.parse("application/json; charset=utf-8")
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .writeTimeout(6, TimeUnit.SECONDS)
        .build()

    /** 账号合法性：仅字母数字，3-20 位（可纯数字如手机号） */
    fun validName(s: String): Boolean = s.length in 3..20 && s.all { it.isLetterOrDigit() }

    /** 密码合法性：仅字母数字，4-20 位（用户明确不要求复杂性） */
    fun validPwd(s: String): Boolean = s.length in 4..20 && s.all { it.isLetterOrDigit() }

    suspend fun register(base: String, username: String, password: String): ApiResp =
        post("$base/api/register", username, password)

    suspend fun login(base: String, username: String, password: String): ApiResp =
        post("$base/api/login", username, password)

    suspend fun ping(base: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$base/api/ping").build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    /** 上报星星增量：成功返回新总数；失败返回 null */
    suspend fun starsAdd(base: String, userId: Long, add: Int): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val body = RequestBody.create(JSON, JSONObject().put("user_id", userId).put("add", add).toString())
            client.newCall(Request.Builder().url("$base/api/stars/add").post(body).build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val jo = JSONObject(r.body()?.string() ?: "{}")
                    if (jo.optInt("code", -1) != CODE_OK) null else jo.optInt("total", 0)
                }
        }.getOrNull()
    }

    /** 拉取总星数：失败返回 null */
    suspend fun starsGet(base: String, userId: Long): Int? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url("$base/api/stars?user_id=$userId").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val jo = JSONObject(r.body()?.string() ?: "{}")
                    if (jo.optInt("code", -1) != CODE_OK) null else jo.optInt("total", 0)
                }
        }.getOrNull()
    }

    private suspend fun post(url: String, username: String, password: String): ApiResp =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = RequestBody.create(JSON, JSONObject().put("username", username).put("password", password).toString())
                client.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use ApiResp(-1, "网络错误 HTTP${r.code()}")
                    val jo = JSONObject(r.body()?.string() ?: "{}")
                    ApiResp(jo.optInt("code", -1), jo.optString("msg", ""), jo.optLong("user_id", 0), jo.optInt("stars", 0))
                }
            }.getOrElse { ApiResp(-1, "网络连接失败，请检查网络或服务器地址") }
        }
}
