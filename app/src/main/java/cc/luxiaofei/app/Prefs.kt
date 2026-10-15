package cc.luxiaofei.app

import android.content.Context
import android.content.SharedPreferences

/**
 * App 自己的小仓库：把网页那边的"身份"和几个开关搬进来，供原生部分（前台服务、读屏采集）用。
 *
 * 为什么要存这份：网页里的身份存在浏览器 localStorage（设备标识 + 登录令牌），
 * 原生代码读不到浏览器的库。所以页面每次启动时把它交给 App 存一份
 * （见 MainActivity 里的 DBBApp.getIdentity()），原生侧发请求时直接用它 ——
 * 于是**采集到的公屏事件和网页那边是同一个账号**，配置、日志、额度全都是同一份。
 *
 * 注意：不做任何加解密。这两个值本来就存在同一台手机的浏览器数据里，
 * 存到 App 私有目录（只有本 App 能读）已经是同一级别的保护。
 */
object Prefs {
    private const val FILE = "dbb"
    private const val KEY_DEVICE = "device_key"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_BASE = "base_url"
    private const val KEY_COLLECT = "collect_enabled"

    private fun sp(ctx: Context): SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 网页那边把身份交过来时调它（页面每次启动都会调一次，写一次很便宜）。 */
    fun saveIdentity(ctx: Context, deviceKey: String, authToken: String, baseUrl: String) {
        sp(ctx).edit()
            .putString(KEY_DEVICE, deviceKey)
            .putString(KEY_TOKEN, authToken)
            .putString(KEY_BASE, baseUrl)
            .apply()
    }

    fun deviceKey(ctx: Context): String = sp(ctx).getString(KEY_DEVICE, "") ?: ""

    fun authToken(ctx: Context): String = sp(ctx).getString(KEY_TOKEN, "") ?: ""

    /** 服务端地址：优先用页面报上来的（跟着页面走，换域名不用重装 App）。 */
    fun baseUrl(ctx: Context): String =
        (sp(ctx).getString(KEY_BASE, "") ?: "").ifBlank { "https://luffy-22dc5b09.run13.luxiaofei.cc" }

    /** 采集总开关：用户能在 App 里关掉读屏采集（关了就只推网页那边的互动）。 */
    fun collectEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_COLLECT, true)

    fun setCollectEnabled(ctx: Context, on: Boolean) = sp(ctx).edit().putBoolean(KEY_COLLECT, on).apply()

    /** 有没有可用的身份：没有就别白推请求（服务端也认不出来）。 */
    fun hasIdentity(ctx: Context): Boolean = deviceKey(ctx).isNotBlank()
}
