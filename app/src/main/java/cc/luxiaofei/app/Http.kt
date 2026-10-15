package cc.luxiaofei.app

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * 原生侧的一个极小 HTTP 工具：只发 JSON、只读返回。
 *
 * 为什么不用 OkHttp / Retrofit：模板的原则是"能不加库就不加"。这里只有两个请求
 * （推公屏事件、看一眼服务端在不在），用系统自带的 HttpURLConnection 完全够，
 * 也少了国内拉依赖的麻烦。
 *
 * 尽量不抛异常：采集是"顺带做的事"，网络抖一下不能让读屏服务崩掉，
 * 失败就返回 null，由调用方下次再发。
 */
object Http {
    /**
     * POST 一段 JSON。
     *
     * 身份沿用网页那套（x-device-key + x-auth-token），于是服务端认出来的是**同一个账号** ——
     * 公屏事件进的就是这个账号的待念队列，主播那边才会念（见 lib/session.ts 的 currentUser）。
     */
    fun postJson(
        url: String,
        json: String,
        deviceKey: String,
        authToken: String,
        timeoutMs: Int = 8000,
    ): String? {
        var conn: HttpURLConnection? = null
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (deviceKey.isNotBlank()) c.setRequestProperty("x-device-key", deviceKey)
            if (authToken.isNotBlank()) c.setRequestProperty("x-auth-token", authToken)

            OutputStreamWriter(c.outputStream, Charsets.UTF_8).use { it.write(json) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = if (stream == null) {
                ""
            } else {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
            }
            return if (code in 200..299) body else null
        } catch (_: Exception) {
            return null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
                // 断开失败无所谓，不打扰上层
            }
        }
    }
}
