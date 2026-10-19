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
 * 原生这边只补网页做不到的几件事：
 *   1. 屏幕常亮（长播最怕锁屏）；
 *   2. 前台服务（让进程不被随手回收，见 LiveService）；
 *   3. 读屏采集（见 DanmuAccessibilityService）；
 *   4. 几个"只有 App 能打开的设置页"：无障碍开关、电池白名单。
 *
 * 身份交接：网页里的设备标识/登录令牌存在浏览器 localStorage，原生读不到，
 * 所以页面启动后通过 DBBApp.saveIdentity() 把自己交给 App 存一份（Prefs）——
 * 于是采集到的事件和网页的播放端是同一个账号。
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = "$userAgentString DBBApp/${BuildConfig.VERSION_NAME}"
        }
        web.addJavascriptInterface(Bridge(), "DBBApp")
        WebView.setWebContentsDebuggingEnabled(true)
        web.webViewClient = WebDiag.client(this)
        // WebView 不会自己下载文件：把下载交给系统浏览器/下载器
        web.setDownloadListener { url, _, _, _, _ -> openOutside(url) }
        web.webChromeClient = object : WebChromeClient() {}
        setContentView(web)

        web.loadUrl(getString(R.string.start_url))

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

    /** 网页调得到的原生小工具（页面里用 window.DBBApp.xxx()）。 */
    inner class Bridge {
        /** 页面把自己交过来：设备标识 + 登录令牌 + 当前域名。 */
        @JavascriptInterface
        fun saveIdentity(deviceKey: String, authToken: String, baseUrl: String) {
            Prefs.saveIdentity(applicationContext, deviceKey, authToken, baseUrl)
        }

        /** App 这边知道的事：版本、采集开关、页面门、无障碍开没开。 */
        @JavascriptInterface
        fun appInfo(): String {
            val o = JSONObject()
            o.put("inApp", true)
            o.put("version", BuildConfig.VERSION_NAME)
            o.put("collectEnabled", Prefs.collectEnabled(applicationContext))
            o.put("roomGate", Prefs.roomGateEnabled(applicationContext))
            o.put("accessibilityOn", DanmuAccessibilityService.isRunning)
            o.put("speakServiceOn", LiveService.isRunning)
            o.put("hasIdentity", Prefs.hasIdentity(applicationContext))
            return o.toString()
        }

        @JavascriptInterface
        fun setCollectEnabled(on: Boolean) {
            Prefs.setCollectEnabled(applicationContext, on)
        }

        /**
         * 直播间页面门：开着时，屏幕上没有「结束直播/退出直播间」就整轮不采。
         */
        @JavascriptInterface
        fun setRoomGate(on: Boolean) {
            Prefs.setRoomGateEnabled(applicationContext, on)
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
        if (isFinishing) stopService(Intent(this, LiveService::class.java))
        super.onDestroy()
    }
}
