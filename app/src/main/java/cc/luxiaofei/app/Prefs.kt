package cc.luxiaofei.app

import android.content.Context
import android.content.SharedPreferences

/**
 * App 自己的小仓库：把网页那边的"身份"和几个开关搬进来，供原生部分（前台服务、读屏采集）用。
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
    /** 直播间页面门：屏幕上有「结束直播」才算在直播间，否则整轮不采 */
    private const val KEY_ROOM_GATE = "room_gate"
    /** 本机语音包版本：和服务器一致 = 这一场的互动语音已下载到机内 */
    private const val KEY_PACK_VERSION = "voice_pack_version"

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

    /** 直播间页面门开关（默认开：不在直播间就不采）。 */
    fun roomGateEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ROOM_GATE, true)

    fun setRoomGateEnabled(ctx: Context, on: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ROOM_GATE, on).apply()

    /** 本机语音包版本（见 VoiceCache.kt）。 */
    fun voicePackVersion(ctx: Context): String = sp(ctx).getString(KEY_PACK_VERSION, "") ?: ""

    fun setVoicePackVersion(ctx: Context, v: String) =
        sp(ctx).edit().putString(KEY_PACK_VERSION, v).apply()

    /** 有没有可用的身份：没有就别白推请求（服务端也认不出来）。 */
    fun hasIdentity(ctx: Context): Boolean = deviceKey(ctx).isNotBlank()
}
