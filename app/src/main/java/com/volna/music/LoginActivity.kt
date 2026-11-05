package com.volna.music

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.volna.music.data.YtAccount

/** Окно входа Google. Как только оказались на music.youtube.com с cookies — вход выполнен. */
class LoginActivity : ComponentActivity() {

    private val cookies = CookieManager.getInstance()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Google не пускает «встроенные браузеры» — убираем пометку WebView из User-Agent
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) = check(url)
        }
        ViewCompat.setOnApplyWindowInsetsListener(web) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(web)
        web.loadUrl(
            "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true" +
                "&continue=https%3A%2F%2Fmusic.youtube.com%2F",
        )
    }

    private fun check(url: String) {
        if (!url.startsWith("https://music.youtube.com")) return
        val c = cookies.getCookie("https://music.youtube.com") ?: return
        if (!c.contains("SAPISID=")) return
        cookies.flush()
        YtAccount.cookie = c
        setResult(RESULT_OK)
        finish()
    }
}
