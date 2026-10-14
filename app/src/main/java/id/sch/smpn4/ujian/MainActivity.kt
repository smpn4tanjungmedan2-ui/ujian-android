package id.sch.smpn4.ujian

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val HUB_URL =
            "https://script.google.com/macros/s/AKfycbySHc7hr3YQT5tLv4qalPMMqAFODc0j-xAOLFRmFsUhI2qIQb4pNP4-m4pCUSvXp1E/exec"
        const val NPSN = "69726973"
        const val APP_UA = "SMPN4ExamApp/1.0"

        private val ALLOWED_HOSTS = listOf(
            "script.google.com",
            "googleusercontent.com",
            "vercel.app"
        )
    }

    private lateinit var webView: WebView
    private lateinit var splash: View
    private lateinit var blackout: View
    private val handler = Handler(Looper.getMainLooper())

    private var examActive = false
    private var suppressUntil = 0L
    private var lastSent = 0L

    private var pinWanted = true
    private val splashStart = SystemClock.uptimeMillis()
    private var splashHidden = false

    private var disqualified = false
    private var dqReason = ""
    private var exiting = false

    private var boomData: ShortArray? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPerm: PermissionRequest? = null

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data)
            )
            fileCallback = null
            suppressFor(1500)
        }

    private val camLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            val r = pendingPerm
            pendingPerm = null
            if (r != null) {
                if (ok) r.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) else r.deny()
            }
            suppressFor(1500)
        }

    // ---------- Jembatan JS: dipanggil halaman ujian ----------
    inner class JsBridge {
        @JavascriptInterface
        fun lock() {
            runOnUiThread {
                if (disqualified) return@runOnUiThread
                examActive = true
                pinWanted = true
                suppressFor(5000)
                ensurePinned()
            }
        }

        @JavascriptInterface
        fun exit() {
            runOnUiThread {
                examActive = false
                pinWanted = false
                try { stopLockTask() } catch (_: Exception) {}
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        splash = findViewById(R.id.splash)
        blackout = findViewById(R.id.blackout)
        handler.postDelayed({ hideSplash() }, 12000)

        // Keyboard & lubang kamera: beri ruang supaya tombol bawah naik di atas keyboard
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, maxOf(ime.bottom, cut.bottom))
            insets
        }

        // Siapkan suara "boom" dan suara bicara
        Thread { boomData = buildBoom() }.start()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("id", "ID"))
                ttsReady = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            }
        }

        WebView.setWebContentsDebuggingEnabled(false)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = userAgentString + " " + APP_UA
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false

        webView.addJavascriptInterface(JsBridge(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !isAllowed(request.url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                hideSplash()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val html = "<html><body style='font-family:sans-serif;text-align:center;padding:40px'>" +
                        "<h3>Koneksi bermasalah</h3><p>Periksa internet, lalu coba lagi.</p>" +
                        "<a href='${startUrl()}' style='display:inline-block;padding:12px 24px;" +
                        "background:#2563eb;color:#fff;border-radius:8px;text-decoration:none'>Coba lagi</a>" +
                        "</body></html>"
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsCam = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                    if (!isAllowed(request.origin) || !wantsCam) {
                        request.deny(); return@runOnUiThread
                    }
                    val granted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) {
                        request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                    } else {
                        pendingPerm = request
                        suppressFor(Long.MAX_VALUE / 2)
                        camLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    suppressFor(Long.MAX_VALUE / 2)
                    fileLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                    suppressFor(1500)
                    true
                }
            }
        }

        // Tombol bawah
        findViewById<View>(R.id.btnBack).setOnClickListener {
            if (examActive) toast("Tombol BACK dinonaktifkan saat ujian")
            else if (webView.canGoBack()) webView.goBack()
        }
        findViewById<View>(R.id.btnForward).setOnClickListener {
            if (examActive) toast("Tombol FORWARD dinonaktifkan saat ujian")
            else if (webView.canGoForward()) webView.goForward()
        }
        findViewById<View>(R.id.btnRefresh).setOnClickListener {
            val u = webView.url
            if (u.isNullOrEmpty() || u == "about:blank") webView.loadUrl(startUrl()) else webView.reload()
        }
        findViewById<View>(R.id.btnExit).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btnClose).setOnClickListener { finishAndRemoveTask() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* diblokir */ }
        })

        enterImmersive()

        if (savedInstanceState == null) webView.loadUrl(startUrl())
        else webView.restoreState(savedInstanceState)

        handler.postDelayed(watchdog, 1000)
    }

    private fun startUrl() = "$HUB_URL?school=$NPSN"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun hideSplash() {
        if (splashHidden) return
        splashHidden = true
        val wait = (2000 - (SystemClock.uptimeMillis() - splashStart)).coerceAtLeast(0)
        handler.postDelayed({
            splash.animate().alpha(0f).setDuration(400)
                .withEndAction { splash.visibility = View.GONE }
                .start()
        }, wait)
    }

    private fun isAllowed(uri: Uri): Boolean {
        if (uri.scheme != "https") return false
        val host = uri.host ?: return false
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    // ---------- EXIT (keluar biasa, BUKAN diskualifikasi) ----------
    private fun confirmExit() {
        if (disqualified || exiting) return
        suppressFor(Long.MAX_VALUE / 2)
        AlertDialog.Builder(this)
            .setTitle("Keluar dari ujian?")
            .setMessage("Jawaban yang sudah tersimpan tetap aman. Anda bisa masuk lagi selama waktu ujian masih ada.")
            .setNegativeButton("Batal") { d, _ -> d.dismiss() }
            .setPositiveButton("Keluar") { _, _ -> doExit() }
            .setOnDismissListener { suppressFor(1500) }
            .show()
    }

    private fun doExit() {
        if (exiting) return
        exiting = true
        examActive = false
        pinWanted = false
        try { stopLockTask() } catch (_: Exception) {}
        speakThen("Kamu keluar dari ujian") { finishAndRemoveTask() }
    }

    private fun speakThen(text: String, then: () -> Unit) {
        var done = false
        val once: () -> Unit = { if (!done) { done = true; then() } }
        val t = tts
        if (t == null || !ttsReady) { once(); return }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { runOnUiThread { once() } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { runOnUiThread { once() } }
        })
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "exit")
        handler.postDelayed({ once() }, 5000)
    }

    // ---------- Diskualifikasi langsung: mencoba keluar (Home / Recent) ----------
    private fun hardDisqualify(reason: String) {
        if (!examActive || disqualified || exiting) return
        if (System.currentTimeMillis() < suppressUntil) return
        disqualified = true
        dqReason = reason
        playBoom()
        blackout.visibility = View.VISIBLE
        pushDisqualify()
        examActive = false
        pinWanted = false
        try { stopLockTask() } catch (_: Exception) {}
    }

    private fun pushDisqualify() {
        val safe = dqReason.replace("'", "").replace("\\", "")
        webView.post {
            webView.evaluateJavascript(
                "window.onNativeDisqualify && window.onNativeDisqualify('$safe')", null
            )
        }
    }

    // Suara "boom" dibuat lewat kode (tanpa file suara)
    private fun buildBoom(): ShortArray {
        val sr = 22050
        val n = (sr * 2.0).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 55.0 + 140.0 * Math.exp(-5.0 * t)
            phase += 2 * Math.PI * f / sr
            val body = Math.sin(phase) * Math.exp(-2.0 * t)
            val crack = (rnd.nextDouble() * 2 - 1) * Math.exp(-12.0 * t)
            val s = Math.tanh((body * 1.7 + crack * 1.3) * 2.4)
            out[i] = (s * 32767).toInt().toShort()
        }
        return out
    }

    private fun playBoom() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            try {
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            } catch (_: Exception) {}
            val data = boomData ?: return
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(22050)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(data.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(data, 0, data.size)
            track.play()
            handler.postDelayed({ try { track.release() } catch (_: Exception) {} }, 3000)
        } catch (_: Exception) {}
    }

    // ---------- Screen pinning ----------
    private fun isPinned(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun ensurePinned() {
        if (!isPinned()) {
            try { startLockTask() } catch (_: Exception) {}
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (pinWanted && !isPinned()) {
                if (examActive) sendViolation("Melepas mode sematkan layar")
                ensurePinned()
            }
            handler.postDelayed(this, 2500)
        }
    }

    // ---------- Pelanggaran ringan (3x = Curang) ----------
    private fun suppressFor(ms: Long) {
        suppressUntil = System.currentTimeMillis() + ms
    }

    private fun sendViolation(reason: String) {
        val now = System.currentTimeMillis()
        if (!examActive) return
        if (now < suppressUntil) return
        if (now - lastSent < 1200) return
        lastSent = now
        val safe = reason.replace("'", "").replace("\\", "")
        webView.post {
            webView.evaluateJavascript(
                "window.onNativeViolation && window.onNativeViolation('$safe')", null
            )
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        hardDisqualify("Mencoba keluar dari aplikasi ujian (Home / pindah aplikasi)")
    }

    override fun onResume() {
        super.onResume()
        if (disqualified) {
            blackout.visibility = View.VISIBLE
            pushDisqualify()   // kirim ulang, jaga-jaga laporan pertama gagal saat di latar belakang
        }
    }

    override fun onPause() {
        super.onPause()
        sendViolation("Aplikasi ujian ditinggalkan")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
        else sendViolation("Layar ujian kehilangan fokus (notifikasi / aplikasi lain)")
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tts?.shutdown()
        webView.removeJavascriptInterface("AndroidApp")
        webView.destroy()
        super.onDestroy()
    }
}
