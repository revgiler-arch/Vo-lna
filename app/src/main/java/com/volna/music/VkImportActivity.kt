package com.volna.music

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject

/**
 * Окно ВК внутри «Волны»: открываем ссылку на плейлист (при необходимости — вход в ВК),
 * по кнопке прокручиваем страницу до конца и считываем список треков.
 * Результат — текст «Исполнитель — Название» по строкам, дальше его разбирает обычный импорт.
 */
class VkImportActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var read: Button
    private var busy = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 12, 12, 12)
            setBackgroundColor(Color.parseColor("#101828"))
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Если ВК просит — войдите. Когда увидите плейлист, нажмите «Считать»."
        }
        read = Button(this).apply { text = "Считать"; setOnClickListener { startReading() } }
        val close = Button(this).apply { text = "✕"; setOnClickListener { finish() } }
        bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(read)
        bar.addView(close)

        web = WebView(this)
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        web.addJavascriptInterface(Bridge(), "Volna")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                cookies.flush()
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
        web.loadUrl(url)
    }

    override fun onDestroy() {
        runCatching { web.destroy() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun startReading() {
        if (busy) return
        busy = true
        read.isEnabled = false
        status.text = "Прокручиваю плейлист…"
        web.evaluateJavascript(SCRIPT, null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun progress(found: String) {
            runOnUiThread { status.text = "Прокручиваю плейлист… найдено треков: $found" }
        }

        @JavascriptInterface
        fun done(json: String) {
            runOnUiThread { finishWith(json) }
        }
    }

    private fun finishWith(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull()
        val tracks = o?.optJSONArray("tracks")
        val text = if (tracks != null && tracks.length() > 0) {
            (0 until tracks.length()).joinToString("\n") { tracks.optString(it) }
        } else {
            o?.optString("text").orEmpty()
        }
        if (text.isBlank()) {
            busy = false
            read.isEnabled = true
            status.text = "Не нашёл треков на странице. Откройте сам плейлист и нажмите «Считать» ещё раз."
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_TEXT, text).putExtra(EXTRA_TITLE, o?.optString("title").orEmpty()))
        finish()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_TEXT = "text"
        const val EXTRA_TITLE = "title"

        /** Ссылка на ВК из любого текста («Послушайте плейлист … https://vk.com/…»). */
        fun findVkLink(text: String?): String? =
            text?.let { Regex("""https?://[^\s]*\bvk\.(?:com|ru|cc|me)/[^\s]*""").find(it)?.value?.trimEnd('.', ',', ')') }

        /**
         * Скрипт на странице: крутим вниз, пока подгружаются новые треки, потом собираем список.
         * Пробуем известные разметки ВК; если ни одна не подошла — отдаём весь текст страницы,
         * его разберёт тот же разбор, что и для скопированного списка.
         */
        private val SCRIPT = """
(function(){
  function dec(s){var d=document.createElement('textarea');d.innerHTML=s||'';return d.value.replace(/\s+/g,' ').trim();}
  function txt(e){return e?(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim():'';}
  function collect(){
    var out=[],seen={};
    function add(a,t){a=(a||'').trim();t=(t||'').trim();if(!t)return;var k=(a+' — '+t);if(seen[k])return;seen[k]=1;out.push(a?k:t);}
    document.querySelectorAll('[data-audio]').forEach(function(e){
      try{var a=JSON.parse(e.getAttribute('data-audio'));if(a&&a[3])add(dec(a[4]),dec(a[3]));}catch(x){}
    });
    if(!out.length)document.querySelectorAll('.audio_item,.ai_info').forEach(function(e){
      var t=e.querySelector('.ai_title');if(t)add(txt(e.querySelector('.ai_artist')),txt(t));
    });
    if(!out.length)document.querySelectorAll('.audio_row').forEach(function(e){
      var t=e.querySelector('.audio_row__title_inner,.audio_row__title');if(t)add(txt(e.querySelector('.audio_row__performers')),txt(t));
    });
    if(!out.length)document.querySelectorAll('[class*="AudioRow"],[class*="audioRow"],[data-testid*="audio"]').forEach(function(e){
      var t=e.querySelector('[class*="itle"]'),a=e.querySelector('[class*="erformer"],[class*="rtist"]');if(t)add(txt(a),txt(t));
    });
    return out;
  }
  function scroller(){
    var best=document.scrollingElement||document.body,bh=best.scrollHeight;
    document.querySelectorAll('div,main,section').forEach(function(e){
      if(e.scrollHeight>e.clientHeight+200&&e.clientHeight>200){var s=getComputedStyle(e).overflowY;if((s=='auto'||s=='scroll')&&e.scrollHeight>bh){best=e;bh=e.scrollHeight;}}
    });
    return best;
  }
  var sc=scroller(),last=-1,same=0,n=0;
  function step(){
    sc.scrollTop=sc.scrollHeight;window.scrollTo(0,document.body.scrollHeight);
    var c=collect().length,h=sc.scrollHeight+c;
    if(h==last)same++;else{same=0;last=h;}
    Volna.progress(String(c));
    if(same>=5||++n>400){
      var t=collect(),title=txt(document.querySelector('h1'))||((document.querySelector('meta[property="og:title"]')||{}).content||'');
      Volna.done(JSON.stringify({tracks:t,title:title,text:t.length?'':(document.body.innerText||'')}));
    }else setTimeout(step,800);
  }
  step();
})();
"""
    }
}
