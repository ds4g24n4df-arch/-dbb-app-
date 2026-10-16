package cc.luxiaofei.app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import org.json.JSONObject

/**
 * App 的入口：一个装着线上语音台的 WebView + 少量原生能力。
 *
 * 为什么用 WebView 而不是把界面重写一遍：语音台（话术、音色、投放节奏、互动链路、
 * 播放引擎）已经那一套跑通并上线了。搬进来只换外壳，**界面和播放逻辑一行不改**，
 * 以后改规则、改文案都不用重新发版 —— 用户在网页和 App 里看到的是同一份东西。
 *
 * 原生这边只补网页做不到的几件事：
 *   1. 屏幕常亮（长播最怕锁屏）；
 *   2. 前台服务（让进程不被随手回收，见 LiveService）；
 *   3. 读屏采集（见 DanmuAccessibilityService）；
 *   4. 几个"只有 App 能打开的设置页"：通知权限、电池白名单、无障碍开关。
 *
 * 身份交接：网页里的设备标识/登录令牌存在浏览器 localStorage，原生读不到，
 * 所以页面启动后通过 DBBApp.getIdentity() 把自己交给 App 存一份（Prefs）——
 * 于是**采集到的事件和网页的播放端是同一个账号**。
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 长播期间别让屏幕黑掉（用户要锁屏时自己按电源键，那时由前台服务兜着）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            // localStorage 必须开：设备标识、登录态、主题都在里面，关了等于每次都是新账号
            domStorageEnabled = true
            databaseEnabled = true
            // 开播按钮点过一次之后，后续音频不该再要手势（否则后台一刷新就哑了）
            mediaPlaybackRequiresUserGesture = false
            // 页面是 https，但开发时可能连本机的 http 服务，放开兼容模式免得白屏
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 让网页知道"我在 App 里"：页面可以据此显示 App 专属的入口/提示
            userAgentString = "$userAgentString DBBApp/${BuildConfig.VERSION_NAME}"
        }
        web.addJavascriptInterface(Bridge(), "DBBApp")
            WebView.setWebContentsDebuggingEnabled(true)
        web.webViewClient = WebDiag.client(this)
        web.webChromeClient = object : WebChromeClient() {}
        setContentView(web)

        web.loadUrl(getString(R.string.start_url))

        // 前台服务：进程活着，锁屏/切抖音之后系统才不会随手把它回收掉
        startForegroundServiceCompat()
    }

    private fun startForegroundServiceCompat() {
        val intent = Intent(this, LiveService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    /**
     * 网页调得到的原生小工具（页面里用 window.DBBApp.xxx()）。
     *
     * 注意 @JavascriptInterface 的方法都在 WebView 的 JS 线程上调用，
     * 所以只做轻活（读写偏好、发 Intent），重活一律扔给服务或线程。
     */
    inner class Bridge {
        /** 页面把自己交过来：设备标识 + 登录令牌 + 当前域名。 */
        @JavascriptInterface
        fun saveIdentity(deviceKey: String, authToken: String, baseUrl: String) {
            Prefs.saveIdentity(applicationContext, deviceKey, authToken, baseUrl)
        }

        /** App 这边知道的事：版本、采集开关、无障碍开没开，页面拿它显示状态。 */
        @JavascriptInterface
        fun appInfo(): String {
            val o = JSONObject()
            o.put("inApp", true)
            o.put("version", BuildConfig.VERSION_NAME)
            o.put("collectEnabled", Prefs.collectEnabled(applicationContext))
            o.put("accessibilityOn", DanmuAccessibilityService.isRunning)
            o.put("hasIdentity", Prefs.hasIdentity(applicationContext))
            return o.toString()
        }

        @JavascriptInterface
        fun setCollectEnabled(on: Boolean) {
            Prefs.setCollectEnabled(applicationContext, on)
        }

        /** 打开系统的无障碍设置页：安卓不允许 App 自己开，必须用户手动开。 */
        @JavascriptInterface
        fun openAccessibilitySettings() {
            val i = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        }

        /** 打开"电池不受限制"的申请页，减少后台被杀。 */
        @JavascriptInterface
        fun requestBatteryUnrestricted() {
            try {
                val i = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                i.data = android.net.Uri.parse("package:$packageName")
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            } catch (_: Exception) {
                // 有的系统/厂商没有这个页面，退回电池优化列表
                val i = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
            }
        }
    }

    override fun onDestroy() {
        // 用户从最近任务里划掉 App 时一起收掉服务：不然通知栏会留一张撤不掉的卡片
        if (isFinishing) stopService(Intent(this, LiveService::class.java))
        super.onDestroy()
    }
}

// ── 以下是「空白页看护」：加载失败 / 页面没渲染出来时给中文提示（原先想单独放 WebDiag.kt，
//    这里直接放在同一个文件里，少一次"新建文件"的操作）──────────────────────────────
object WebDiag {

    private var helpShown = false

    fun client(ctx: android.content.Context): android.webkit.WebViewClient =
        object : android.webkit.WebViewClient() {

            override fun onReceivedError(
                view: android.webkit.WebView,
                request: android.webkit.WebResourceRequest,
                error: android.webkit.WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                val reason = error.description?.toString().orEmpty()
                view.loadDataWithBaseURL(
                    null,
                    page(ctx, "连不上服务器", "先看看手机网络（Wi-Fi / 流量）是否正常，再点下面重试。", "原因：$reason"),
                    "text/html", "utf-8", null,
                )
            }

            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                if (helpShown) return
                view.evaluateJavascript(PROBE) { raw ->
                    val s = raw ?: ""
                    if (helpShown) return@evaluateJavascript
                    if (!(s.contains("\"n\":-1") || s.contains("\"n\":0") || s.contains("\"err\":"))) {
                        return@evaluateJavascript
                    }
                    helpShown = true
                    view.loadDataWithBaseURL(
                        null,
                        page(
                            ctx,
                            "网页没能显示出来",
                            "这台手机的系统 WebView 可能太旧了：去应用商店更新「Android System WebView」" +
                                "（有的机型叫「安卓系统WebView」）或 Chrome，再重新打开本 App。",
                            "系统 WebView：${webViewVersion()}\n页面状态：${s.take(140)}",
                        ),
                        "text/html", "utf-8", null,
                    )
                }
            }
        }

    private const val PROBE =
        "(function(){try{var b=document.body;return JSON.stringify({n:b?b.innerText.replace(/\\s+/g,'').length:-1});}" +
            "catch(e){return JSON.stringify({err:String(e&&e.message||e)});}})()"

    @android.annotation.SuppressLint("NewApi")
    fun webViewVersion(): String = try {
        val p = android.webkit.WebView.getCurrentWebViewPackage()
        if (p == null) "未知（系统没装 WebView？）" else "${p.packageName} ${p.versionName}"
    } catch (e: Throwable) {
        "未知（${e.javaClass.simpleName}）"
    }

    private fun page(ctx: android.content.Context, title: String, tip: String, detail: String): String {
        val url = try { ctx.getString(R.string.start_url) } catch (e: Throwable) { "" }
        return """
            <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1"><style>
            body{margin:0;padding:56px 22px;background:#0b1220;color:#e8f0ff;text-align:center;
            font-family:-apple-system,"PingFang SC","Noto Sans SC",sans-serif}
            h1{font-size:17px;margin:0 0 12px}
            p{font-size:13.5px;line-height:1.8;color:#9fb3d9;margin:8px 0;text-align:left}
            .why{font-size:11px;color:#7d8fb3;margin-top:16px;word-break:break-all;text-align:left}
            a{display:inline-block;margin-top:24px;padding:14px 28px;border-radius:14px;color:#fff;
            text-decoration:none;font-size:15px;font-weight:600;background:linear-gradient(135deg,#4C1D95,#0EA5E9)}
            </style></head><body><h1>$title</h1><p>$tip</p><p class="why">$detail</p>
            <a href="$url">重新加载</a>
            <p class="why">（一直这样就把这一屏截图发给客服，上面那行版本号能直接定位问题）</p>
            </body></html>
        """.trimIndent()
    }
}
