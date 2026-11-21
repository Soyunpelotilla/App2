@file:Suppress("UnstableApiUsage", "UnsafeOptInUsageError")

package app.leiflix

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import java.io.IOException
import java.util.Locale

import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import androidx.core.view.ViewCompat
import androidx.core.graphics.Insets
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.UiModeManager

class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var tvChannel: TextView
    private lateinit var tvEpg: TextView
    private lateinit var hideHandler: Handler
    private val stopBtnId = View.generateViewId()
    private val audioBtnId = View.generateViewId()

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    // ← FIX: detecta TV real (excluye touchscreen)
    private fun isTvDevice(): Boolean {
        val uiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        val isTvMode = uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        val hasLeanback = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        
        // TV real = (modo TV O leanback) Y NO touchscreen físico
        return (isTvMode || hasLeanback) && !hasTouchscreen
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or 0x680)
        window.setBackgroundDrawableResource(android.R.color.black)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())

        val topInfoBar = findViewById<ViewGroup>(R.id.top_info_bar)
        ViewCompat.setOnApplyWindowInsetsListener(topInfoBar) { view, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()).top
            view.setPadding(view.paddingStart, topInset + 12, view.paddingEnd, view.paddingBottom)
            insets
        }

        playerView = findViewById(R.id.player_view)
        
        // ← FIX 2: desactiva controller nativo de ExoPlayer (evita focus/bordes en móvil)
        playerView.useController = false
        
        initializePlayer()

        onBackPressedDispatcher.addCallback(this) {
            releasePlayer()
            finish()
        }
    }

    private fun recogerHeaders(): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        intent.extras?.keySet()?.forEach { key ->
            if (key.startsWith("header_")) {
                val headerName = key.removePrefix("header_")
                val headerValue = intent.getStringExtra(key)
                if (!headerValue.isNullOrEmpty()) {
                    headers[headerName] = headerValue
                }
            }
        }
        return headers
    }

    private fun esStreamHls(url: String) =
        url.contains(".m3u8") || url.contains(".m3u") ||
                url.contains("playlist") || url.contains("/live/") ||
                url.contains("/movie/") || url.contains("/series/")

    private fun esStreamDash(url: String) =
        url.contains(".mpd") || url.contains("manifest") || url.contains("%2e%6d%70%64")

    private fun hexToBase64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) {
            hex.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    private fun buildClearKeyDrmManager(drmPairs: List<Pair<String, String>>): DefaultDrmSessionManager {
        val keyEntries = drmPairs.joinToString(",") { (kid, key) ->
            """{"kty":"oct","kid":"${hexToBase64Url(kid)}","k":"${hexToBase64Url(key)}"}"""
        }
        val psshJson = """{"keys":[$keyEntries],"type":"temporary"}"""
        val drmCallback = LocalMediaDrmCallback(psshJson.toByteArray(Charsets.UTF_8))
        return DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .setPlayClearSamplesWithoutKeys(true)
            .build(drmCallback)
    }

    private fun initializePlayer() {
        val url = intent.getStringExtra("videoUrl") ?: return

        val count = intent.getIntExtra("drm_count", 0)
        val drmPairs = (0 until count).mapNotNull { i ->
            val kid = intent.getStringExtra("kid_$i") ?: return@mapNotNull null
            val key = intent.getStringExtra("key_$i") ?: return@mapNotNull null
            if (kid.isNotEmpty() && key.isNotEmpty()) kid to key else null
        }
        val tieneDrm = esStreamDash(url) && drmPairs.isNotEmpty()

        val headersCapturados = recogerHeaders()
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 1_000, 1_500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val dataSourceFactory: HttpDataSource.Factory =
            DefaultHttpDataSource.Factory()
                .setUserAgent(headersCapturados["User-Agent"] ?: userAgent)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setDefaultRequestProperties(headersCapturados)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setRenderersFactory(
                androidx.media3.exoplayer.DefaultRenderersFactory(this)
                    .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                    .setEnableDecoderFallback(true)
            )
            .build()

        playerView.player = player
        playerView.keepScreenOn = true
        playerView.setShutterBackgroundColor(Color.BLACK) // shutter negro hasta que llegue el primer frame

        tvChannel = findViewById(R.id.tv_channel_name)
        tvEpg = findViewById(R.id.tv_epg_title)

        val channelName = intent.getStringExtra("channelName")?.trim().orEmpty()
        val epgTitle = intent.getStringExtra("epgTitle")?.trim().orEmpty()

        if (channelName.isNotEmpty()) {
            tvChannel.text = channelName
            tvChannel.isSelected = true
            tvChannel.visibility = View.VISIBLE
        } else {
            tvChannel.visibility = View.GONE
        }

        if (epgTitle.isNotEmpty()) {
            tvEpg.text = epgTitle
            tvEpg.isSelected = true
            tvEpg.visibility = View.VISIBLE
        } else {
            tvEpg.visibility = View.GONE
        }

        hideHandler = Handler(Looper.getMainLooper())
        playerView.controllerAutoShow = false
        playerView.controllerShowTimeoutMs = 5000

        val hideInfoBar = {
            tvChannel.visibility = View.GONE
            tvEpg.visibility = View.GONE
        }

        val showInfoBar = {
            if (channelName.isNotEmpty()) tvChannel.visibility = View.VISIBLE
            if (epgTitle.isNotEmpty()) tvEpg.visibility = View.VISIBLE
            hideHandler.removeCallbacksAndMessages(null)
            hideHandler.postDelayed(hideInfoBar, 5000L)
        }

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            if (visibility == View.VISIBLE) {
                showInfoBar()
            } else {
                hideInfoBar()
                hideHandler.removeCallbacksAndMessages(null)
            }
        })

        // Al entrar al reproductor: controles ocultos pero barra superior visible durante 5 segundos
        playerView.hideController()
        showInfoBar()

        // ──────────────────────────────────────────────
        // CONFIGURACIÓN DE FOCUS Y EFECTO TV EN CONTROLES OSD
        // ──────────────────────────────────────────────
        // Circular para controles centrales (play/pause, rwd, ffwd, prev, next)
        val centerFocusDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.exo_button_focus_selector)
        // Rectangular / cuadrado para botones de la barra inferior (stop, audio, calidad, subtítulos, etc.)
        val bottomFocusDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.exo_button_focus_selector_rect)
        val attachTvFocusEffect: (View) -> Unit = { v ->
            // ← SOLO en TV/Android TV aplica el efecto
            if (isTvDevice()) {
                v.isFocusable = true
                v.isFocusableInTouchMode = false
                v.setOnFocusChangeListener { view, hasFocus ->
                    if (hasFocus) {
                        view.animate().scaleX(1.18f).scaleY(1.18f).setDuration(150).start()
                    } else {
                        view.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                }
            }
        }

        // ──────────────────────────────────────────────
        // INYECCIÓN DINÁMICA DE BOTONES (Stop, Audio, Calidad)
        // ──────────────────────────────────────────────
        val btnSettings = playerView.findViewById<ImageButton>(androidx.media3.ui.R.id.exo_settings)
        btnSettings?.setImageResource(R.drawable.ic_hd_outline_24dp)
        btnSettings?.imageTintList = ColorStateList.valueOf(Color.WHITE)
        btnSettings?.scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        btnSettings?.let {
            if (bottomFocusDrawable != null) it.background = bottomFocusDrawable.constantState?.newDrawable()?.mutate()
            attachTvFocusEffect(it)
        }
        val controlsContainer = btnSettings?.parent as? ViewGroup

        if (controlsContainer != null) {
            val settingsIdx = controlsContainer.indexOfChild(btnSettings)

            if (controlsContainer.findViewById<View>(audioBtnId) == null) {
                val btnAudio = ImageButton(this).apply {
                    id = audioBtnId
                    setImageResource(R.drawable.ic_audio_24dp)
                    background = if (bottomFocusDrawable != null) bottomFocusDrawable.constantState?.newDrawable()?.mutate() else btnSettings?.background?.constantState?.newDrawable()?.mutate()
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = "Seleccionar Audio"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(
                        btnSettings.layoutParams.width,
                        btnSettings.layoutParams.height
                    )
                    isFocusable = true
                }
                attachTvFocusEffect(btnAudio)

                controlsContainer.addView(btnAudio, settingsIdx)

                btnAudio.setOnClickListener { view ->
                    view.isPressed = true
                    btnSettings.isPressed = false

                    player?.let { exoPlayer ->
                        TrackSelectionDialogBuilder(this, "Seleccionar audio:", exoPlayer, C.TRACK_TYPE_AUDIO)
                            .apply {
                                setShowDisableOption(false)
                                setAllowAdaptiveSelections(false)
                            }.build().apply {
                                setOnDismissListener { view.isPressed = false }
                            }.show()
                    }
                }
            }

            if (controlsContainer.findViewById<View>(stopBtnId) == null) {
                val btnStop = ImageButton(this).apply {
                    id = stopBtnId
                    setImageResource(R.drawable.ic_stop_white_24dp)
                    background = if (bottomFocusDrawable != null) bottomFocusDrawable.constantState?.newDrawable()?.mutate() else btnSettings?.background?.constantState?.newDrawable()?.mutate()
                    imageTintList = ColorStateList.valueOf(Color.WHITE)
                    contentDescription = "Detener y salir"
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    layoutParams = LinearLayout.LayoutParams(
                        btnSettings.layoutParams.width,
                        btnSettings.layoutParams.height
                    )
                    isFocusable = true
                }
                attachTvFocusEffect(btnStop)

                controlsContainer.addView(btnStop, settingsIdx)

                btnStop.setOnClickListener {
                    releasePlayer()
                    finish()
                }
            }
        }

        // Aplicar selector circular de foco a los botones centrales
        val centerButtonIds = listOf(
            androidx.media3.ui.R.id.exo_play_pause,
            androidx.media3.ui.R.id.exo_prev,
            androidx.media3.ui.R.id.exo_next,
            androidx.media3.ui.R.id.exo_rew_with_amount,
            androidx.media3.ui.R.id.exo_ffwd_with_amount
        )
        for (btnId in centerButtonIds) {
            playerView.findViewById<View>(btnId)?.let { v ->
                if (centerFocusDrawable != null) {
                    v.background = centerFocusDrawable.constantState?.newDrawable()?.mutate()
                }
                attachTvFocusEffect(v)
            }
        }

        // Aplicar selector rectangular de foco a los botones inferiores
        val bottomButtonIds = listOf(
            androidx.media3.ui.R.id.exo_subtitle,
            androidx.media3.ui.R.id.exo_fullscreen,
            androidx.media3.ui.R.id.exo_overflow_show,
            androidx.media3.ui.R.id.exo_overflow_hide
        )
        for (btnId in bottomButtonIds) {
            playerView.findViewById<View>(btnId)?.let { v ->
                if (bottomFocusDrawable != null) {
                    v.background = bottomFocusDrawable.constantState?.newDrawable()?.mutate()
                }
                attachTvFocusEffect(v)
            }
        }

        btnSettings?.setOnClickListener { view ->
            val audioBtn = controlsContainer?.findViewById<ImageButton>(audioBtnId)
            audioBtn?.isPressed = false
            view.isPressed = true

            player?.let { exoPlayer ->
                val dialogBuilder = TrackSelectionDialogBuilder(this, "Seleccionar calidad:", exoPlayer, C.TRACK_TYPE_VIDEO)

                dialogBuilder.setTrackFormatComparator { f1, f2 ->
                    f2.bitrate.compareTo(f1.bitrate)
                }

                dialogBuilder.setTrackNameProvider { format ->
                    val height = format.height
                    val bitrate = format.bitrate
                    val resText = if (height != Format.NO_VALUE) "${height}p" else "Calidad auto"
                    val mbpsText = if (bitrate != Format.NO_VALUE) {
                        val mbps = bitrate.toFloat() / 1_000_000
                        String.format(Locale.US, " (%.1f Mbps)", mbps)
                    } else ""
                    "$resText$mbpsText"
                }

                dialogBuilder.apply {
                    setShowDisableOption(false)
                    setAllowAdaptiveSelections(true)
                }.build().apply {
                    setOnDismissListener { view.isPressed = false }
                }.show()
            }
        }

        // ── Configuración de MediaSource ───────────────────────────────────
        val mediaSource = when {
            tieneDrm -> {
                val drmManager = ClearKeyCbcsDecryptor.wrapDrmSessionManager(buildClearKeyDrmManager(drmPairs))
                val chunkSourceFactory = ClearKeyCbcsDecryptor.createDashChunkSourceFactory(dataSourceFactory, drmPairs)
                DashMediaSource.Factory(chunkSourceFactory, dataSourceFactory)
                    .setManifestParser(ClearKeyCbcsDecryptor.CbcsDashManifestParser())
                    .setDrmSessionManagerProvider { drmManager }
                    .createMediaSource(MediaItem.fromUri(url))
            }
            esStreamDash(url) -> {
                DashMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            esStreamHls(url) -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(MediaItem.fromUri(url))
            }
            else -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }
        }

        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    android.util.Log.e("PlayerActivity", "ExoPlayer onPlayerError: ${error.errorCodeName} (${error.errorCode})", error)
                    if (tieneDrm) return
                    val fallback = when {
                        esStreamDash(url) -> HlsMediaSource.Factory(dataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(MediaItem.fromUri(url))
                        esStreamHls(url) -> ProgressiveMediaSource.Factory(dataSourceFactory)
                            .createMediaSource(MediaItem.fromUri(url))
                        else -> HlsMediaSource.Factory(dataSourceFactory)
                            .setAllowChunklessPreparation(true)
                            .createMediaSource(MediaItem.fromUri(url))
                    }
                    val delay = if (error.cause is IOException) 2000L else 0L
                    playerView.postDelayed({
                        if (player != null) {
                            setMediaSource(fallback)
                            prepare()
                            play()
                        }
                    }, delay)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    android.util.Log.d("PlayerActivity", "onPlaybackStateChanged: state=$state")
                    if (state == Player.STATE_READY) {
                        playerView.requestFocus()
                    }
                }

                override fun onRenderedFirstFrame() {
                    android.util.Log.d("PlayerActivity", "onRenderedFirstFrame! First frame ready!")
                    // Primer frame listo → quitamos el shutter negro
                    playerView.setShutterBackgroundColor(Color.TRANSPARENT)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    android.util.Log.d("PlayerActivity", "onTracksChanged: groups=${tracks.groups.size}")
                    for (g in tracks.groups) {
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            android.util.Log.d("PlayerActivity", "  track: ${f.sampleMimeType}, drmInit=${f.drmInitData?.schemeType}, selected=${g.isTrackSelected(i)}, supported=${g.isTrackSupported(i)}")
                        }
                    }
                }
            })
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        releasePlayer()
    }

    private fun releasePlayer() {
        player?.release()
        player = null
    }
}
