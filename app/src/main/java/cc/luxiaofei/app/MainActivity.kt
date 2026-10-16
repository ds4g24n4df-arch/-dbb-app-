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
        /**
         * WebView 自己**不会下载文件**：点「下载 App」和安装包直链，以前是**毫无反应**
         * （现场反馈就是"点了没反应"）。这里把下载交给系统浏览器/系统下载器 ——
         * 它会下完并引导安装，用户点两下就更新完了。
         */
        web.setDownloadListener { url, _, _, _, _ -> openOutside(url) }
        web.webChromeClient = object : WebChromeClient() {}
        setContentView(web)

        web.loadUrl(getString(R.string.start_url))

        // 前台服务：进程活着，锁屏/切抖音之后系统才不会随手把它回收掉
        startForegroundServiceCompat()
    }

    /** 用系统浏览器打开链接：App 里的 WebView 不能下载文件，必须交给系统。 */
    private fun openOutside(url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
        } catch (_: Exception) {
            // 没装浏览器之类的极端情况：忽略，别把 App 崩了
        }
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
            o.put("speakServiceOn", LiveService.isRunning)
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

        /** 让系统浏览器打开任意链接（页面上的"下载 App"用得到）。 */
        @JavascriptInterface
        fun openExternal(url: String) {
            openOutside(url)
        }
    }

    override fun onDestroy() {
        // 用户从最近任务里划掉 App 时一起收掉服务：不然通知栏会留一张撤不掉的卡片
        if (isFinishing) stopService(Intent(this, LiveService::class.java))
        super.onDestroy()
    }
}
