@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var embedWebView: WebView? = null
    private var streamYaCapturado = false
    private var loadingDialog: Dialog? = null
    private var ultimoCanalNombre: String = ""
    private var ultimoEpgTitulo: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawableResource(android.R.color.black)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)

        if (packageManager.hasSystemFeature("android.software.leanback")) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
        }

        webView = findViewById(R.id.webview)
        setupWebView()

        val backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (loadingDialog?.isShowing == true) {
                    ocultarLoading()
                    embedWebView?.destroy()
                    embedWebView = null
                    streamYaCapturado = false
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finishAffinity()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        webView.loadUrl("https://leiflix.vercel.app/private/private.html")
    }

    // -----------------------------------------------------------------------
    // Dialog de carga 100% por código — sin XMLs extra
    // -----------------------------------------------------------------------
    private fun mostrarLoading() {
        if (loadingDialog?.isShowing == true) return

        val isTv = packageManager.hasSystemFeature("android.software.leanback")

        val bgShape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 48f
            setColor("#1a2535".toColorInt())
            setStroke(2, "#2a3a50".toColorInt())
        }

        val spinnerSize = if (isTv) 128 else 96
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(
                "#58a6ff".toColorInt()
            )
            layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val textoPrincipal = TextView(this).apply {
            setText(R.string.loading_stream)
            setTextColor("#58a6ff".toColorInt())
            textSize = if (isTv) 26f else 22f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 32
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val textoSecundario = TextView(this).apply {
            setText(R.string.loading_stream_subtitle)
            setTextColor("#8b949e".toColorInt())
            textSize = if (isTv) 15f else 14f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(80, 64, 80, 64)
            background = bgShape
            addView(spinner)
            addView(textoPrincipal)
            addView(textoSecundario)
        }

        loadingDialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(container)
            window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
            val ancho = when {
                resources.displayMetrics.widthPixels >= 1920 ->
                    (resources.displayMetrics.widthPixels * 0.35).toInt()
                resources.displayMetrics.widthPixels >= 1280 ->
                    (resources.displayMetrics.widthPixels * 0.45).toInt()
                else ->
                    (resources.displayMetrics.widthPixels * 0.75).toInt()
            }
            window?.setLayout(
                ancho,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setCancelable(false)
        }
        loadingDialog?.show()
    }

    private fun ocultarLoading() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    // -----------------------------------------------------------------------
    // Setup del WebView principal
    // -----------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.BLACK)
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        // ── Bridge nativo para canales DASH+DRM desde la web ──
        webView.addJavascriptInterface(LeiflixBridge(), "LeiflixApp")

        webView.webViewClient = LeiflixClient()

        webView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val orientation = resources.configuration.orientation
            val js = if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                "(() => { const v=document.querySelector('video'); if(v&&v.requestFullscreen) v.requestFullscreen(); })();"
            } else {
                "(() => { if(document.fullscreenElement) document.exitFullscreen(); })();"
            }
            webView.evaluateJavascript(js, null)
        }
    }

    // -----------------------------------------------------------------------
    // Bridge JS → nativo: la web llama a LeiflixApp.reproducir(url, kid, key)
    // Soporta N pares kid/key separados por coma (1, 2, 3, 4, 5... ilimitados)
    // -----------------------------------------------------------------------
    inner class LeiflixBridge {

        @Suppress("unused")
        @JavascriptInterface
        fun setInfoCanal(canal: String, epg: String) {
            ultimoCanalNombre = canal.trim()
            ultimoEpgTitulo = epg.trim()
        }

        // Sin headers — canales normales
        @Suppress("unused")
        @JavascriptInterface
        fun reproducir(url: String, kid: String, key: String) {
            runOnUiThread {
                val kids = kid.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val keys = key.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                // zip empareja kid[0] con key[0], kid[1] con key[1], etc.
                val drmPairs = kids.zip(keys)
                lanzarPlayer(url, emptyMap(), drmPairs)
            }
        }

        // Con headers — canales con user-agent, referer y origin
        @Suppress("unused")
        @JavascriptInterface
        fun reproducir(url: String, kid: String, key: String, userAgent: String, referer: String, origin: String) {
            runOnUiThread {
                val kids = kid.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val keys = key.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val drmPairs = kids.zip(keys)
                val headers = mutableMapOf<String, String>()
                if (userAgent.isNotEmpty()) headers["User-Agent"] = userAgent
                if (referer.isNotEmpty()) headers["Referer"] = referer
                if (origin.isNotEmpty()) headers["Origin"] = origin
                lanzarPlayer(url, headers, drmPairs)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Lanza un WebView invisible para cazar el stream de una página embed
    // -----------------------------------------------------------------------
    @SuppressLint("SetJavaScriptEnabled")
    private fun lanzarEmbedInterceptor(embedUrl: String) {

        embedWebView?.destroy()
        embedWebView = null
        streamYaCapturado = false

        mostrarLoading()

        android.os.Handler(mainLooper).postDelayed({
            if (!streamYaCapturado) {
                ocultarLoading()
                embedWebView?.destroy()
                embedWebView = null
                streamYaCapturado = false
                Toast.makeText(this, "No se pudo obtener el stream", Toast.LENGTH_LONG).show()
            }
        }, 30_000)

        CookieManager.getInstance().setAcceptCookie(true)

        embedWebView = WebView(this).apply {

            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowContentAccess = true
                setSupportMultipleWindows(false)
                userAgentString = "Mozilla/5.0 (Linux; Android 14) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    val url = request.url.toString()

                    if (!streamYaCapturado && esUrlStream(url)) {
                        streamYaCapturado = true

                        val headers = request.requestHeaders.toMutableMap()

                        val cookies = CookieManager.getInstance().getCookie(url)
                        if (!cookies.isNullOrEmpty()) {
                            headers["Cookie"] = cookies
                        }

                        if (!headers.containsKey("Referer")) {
                            headers["Referer"] = embedUrl
                        }

                        runOnUiThread {
                            ocultarLoading()
                            lanzarPlayer(url, headers)
                            view.stopLoading()
                            embedWebView?.destroy()
                            embedWebView = null
                        }
                    }

                    return null
                }
            }

            loadUrl(embedUrl)
        }
    }

    // -----------------------------------------------------------------------
    // Lanza PlayerActivity con la URL, headers y N pares DRM opcionales
    // -----------------------------------------------------------------------
    private fun lanzarPlayer(
        url: String,
        headers: Map<String, String> = emptyMap(),
        drmPairs: List<Pair<String, String>> = emptyList()
    ) {
        val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
            putExtra("videoUrl", url)
            putExtra("channelName", ultimoCanalNombre)
            putExtra("epgTitle", ultimoEpgTitulo)
            // Guarda cuántos pares hay y cada uno indexado: kid_0, key_0, kid_1, key_1...
            putExtra("drm_count", drmPairs.size)
            drmPairs.forEachIndexed { i, (kid, key) ->
                putExtra("kid_$i", kid)
                putExtra("key_$i", key)
            }
            headers.forEach { (k, v) ->
                putExtra("header_$k", v)
            }
        }
        startActivity(intent)
    }

    // -----------------------------------------------------------------------
    // Detecta si una URL es un stream de video
    // -----------------------------------------------------------------------
    private fun esUrlStream(url: String): Boolean {
        if (url.contains(".m3u8") || url.contains(".m3u")) return true
        if (url.contains(".mpd")) return true
        if (url.endsWith(".ts")) return true
        if (url.contains(".mp4")) return true
        if (url.contains(".mkv")) return true
        if (url.contains(".avi")) return true
        if (url.contains(".webm")) return true
        if (url.contains(".f4m")) return true
        if (url.contains(".ism")) return true
        if (url.contains("/play/")) return true
        if (url.contains("/hls/")) return true
        if (url.contains("/dash/")) return true
        if (Regex(".*/live/[^/]+/[^/]+/.*\\.ts$").containsMatchIn(url)) return true
        if (Regex(".*/live/[^/]+/[^/]+/\\d+.*").containsMatchIn(url)) return true
        if (Regex(".*/movie/[^/]+/[^/]+/\\d+.*").containsMatchIn(url)) return true
        if (Regex(".*/series/[^/]+/[^/]+/\\d+.*").containsMatchIn(url)) return true
        return false
    }

    // -----------------------------------------------------------------------
    // Detecta si una URL es una página embed (no un stream directo)
    // -----------------------------------------------------------------------
    private fun esUrlEmbed(url: String): Boolean {
        if (esUrlStream(url)) return false
        if (url.startsWith("file://") || url.contains("leiflix") || url.contains("fast.com")) return false

        return url.contains("tvporinternet") ||
                url.contains("embed.saohgdasregions") ||
                url.contains("cablevisionhd") ||
                url.contains("futbollibrehd") ||
                url.contains("pirlotvonline") ||
                url.contains("deporte-libre") ||
                url.contains("deportelibree") ||
                url.endsWith(".php") ||
                url.contains("/embed/") ||
                (url.startsWith("http") && !url.contains("leiflix.vercel.app"))
    }

    // -----------------------------------------------------------------------
    // WebViewClient principal de LEIFLIX
    // -----------------------------------------------------------------------
    @Suppress("DEPRECATION")
    inner class LeiflixClient : WebViewClient() {

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val cleanCSS = """
                (function() {
                    var style = document.createElement('style');
                    style.innerHTML = 'html,body{overflow:auto!important;-ms-overflow-style:none!important;scrollbar-width:none!important;}::-webkit-scrollbar{display:none!important;width:0!important;height:0!important;}';
                    document.head.appendChild(style);

                    if (!window._leiflixInfoHooked) {
                        window._leiflixInfoHooked = true;
                        function extractAndSend(target) {
                            try {
                                if (!target || !target.closest) return;
                                // 1. LiveTV (.canal)
                                var canal = target.closest('.canal');
                                if (!canal && typeof ultimoElementoActivo !== 'undefined' && ultimoElementoActivo && ultimoElementoActivo.classList && ultimoElementoActivo.classList.contains('canal')) {
                                    canal = ultimoElementoActivo;
                                }
                                if (canal) {
                                    var cName = (canal.querySelector('.canal-nombre')?.textContent || canal.getAttribute('data-epg-id') || '').trim();
                                    var epgTxt = (canal.querySelector('.epg-titulo-programa')?.textContent || '').trim();
                                    if (!epgTxt) {
                                        var pCanal = (document.getElementById('epg-poster-canal')?.textContent || '').trim();
                                        if (pCanal && (!cName || pCanal.toLowerCase() === cName.toLowerCase())) {
                                            epgTxt = (document.getElementById('epg-poster-titulo')?.textContent || '').trim();
                                        }
                                    }
                                    if (cName && window.LeiflixApp && window.LeiflixApp.setInfoCanal) {
                                        window.LeiflixApp.setInfoCanal(cName, epgTxt);
                                        return;
                                    }
                                }
                                // 2. Agenda (.channel-button / .id-item)
                                var chBtn = target.closest('.channel-button');
                                if (chBtn) {
                                    var chName = (chBtn.getAttribute('data-channel') || chBtn.querySelector('.channel-name')?.textContent || chBtn.textContent || '').trim();
                                    var evCard = chBtn.closest('.event-card');
                                    var evTitle = '';
                                    if (evCard) {
                                        var teams = evCard.querySelectorAll('.team-name');
                                        var single = evCard.querySelector('.evento-unico-nombre');
                                        var comp = evCard.querySelector('.competicion-title');
                                        if (teams && teams.length >= 2) {
                                            evTitle = teams[0].textContent.trim() + ' vs ' + teams[1].textContent.trim();
                                        } else if (single) {
                                            evTitle = single.textContent.trim();
                                        } else if (comp) {
                                            evTitle = comp.textContent.trim();
                                        }
                                    }
                                    window._lastAgendaEvent = evTitle;
                                    if (chName && window.LeiflixApp && window.LeiflixApp.setInfoCanal) {
                                        window.LeiflixApp.setInfoCanal(chName, evTitle);
                                        return;
                                    }
                                }
                                var idItem = target.closest('.id-item');
                                if (idItem) {
                                    var mTitle = (document.getElementById('modal-title')?.textContent || '').trim();
                                    if (mTitle && window.LeiflixApp && window.LeiflixApp.setInfoCanal) {
                                        window.LeiflixApp.setInfoCanal(mTitle, window._lastAgendaEvent || '');
                                    }
                                }
                            } catch(err) {}
                        }
                        document.addEventListener('click', function(e) { extractAndSend(e.target); }, true);
                        document.addEventListener('focusin', function(e) { extractAndSend(e.target); }, true);
                        document.addEventListener('keydown', function(e) {
                            if (e.key === 'Enter' || e.key === ' ') {
                                extractAndSend(document.activeElement || e.target);
                            }
                        }, true);
                    }
                })();
            """.trimIndent()
            view?.evaluateJavascript(cleanCSS, null)
        }

        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            return handleUrl(url)
        }

        private fun handleUrl(url: String): Boolean {
            return when {

                url.startsWith("acestream://") -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                    } catch (_: Exception) {
                        Toast.makeText(
                            this@MainActivity,
                            "AceStream no instalado",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    true
                }

                esUrlStream(url) -> {
                    lanzarPlayer(url)
                    true
                }

                esUrlEmbed(url) -> {
                    lanzarEmbedInterceptor(url)
                    true
                }

                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ocultarLoading()
        embedWebView?.destroy()
        embedWebView = null
    }
}