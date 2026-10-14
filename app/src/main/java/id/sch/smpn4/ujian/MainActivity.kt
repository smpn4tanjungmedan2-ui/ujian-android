package id.sch.smpn4.ujian   // <-- SESUAIKAN dengan package project Anda

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    companion object {
        const val HUB_URL =
            "https://script.google.com/macros/s/AKfycbySHc7hr3YQT5tLv4qalPMMqAFODc0j-xAOLFRmFsUhI2qIQb4pNP4-m4pCUSvXp1E/exec"
        const val NPSN = "69726973"          // <-- GANTI
        const val APP_UA = "SMPN4ExamApp/1.0"

        // Hanya host ini yang boleh dibuka di dalam aplikasi
        private val ALLOWED_HOSTS = listOf(
            "script.google.com",
            "googleusercontent.com",   // iframe Apps Script + foto
            "vercel.app"               // ganti ke domain Vercel Anda yang persis bila mau lebih ketat
        )
    }

    private lateinit var webView: WebView
    private val handler = Handler(Looper.getMainLooper())

    private var examActive = false          // diset oleh halaman ujian (Vercel)
    private var suppressUntil = 0L          // abaikan pelanggaran sampai waktu ini
    private var lastSent = 0L

    private var pinWanted = true            // sematan aktif sejak aplikasi dibuka
    private lateinit var splash: View
    private val splashStart = SystemClock.uptimeMillis()
    private var splashHidden = false

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
                examActive = true
                pinWanted = true
                suppressFor(5000)           // dialog "sematkan layar" tidak dihitung pelanggaran
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

        // Anti screenshot / rekam layar / preview di recent apps
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        splash = findViewById(R.id.splash)
        handler.postDelayed({ hideSplash() }, 12000)   // jaga-jaga kalau halaman lambat

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

        // Matikan tekan-lama (seleksi/copy)
        webView.isLongClickable = false
        webView.setOnLongClickListener { true }
        webView.isHapticFeedbackEnabled = false

        webView.addJavascriptInterface(JsBridge(), "AndroidApp")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return !isAllowed(request.url)     // true = diblokir
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

            // Izin kamera untuk foto jawaban
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

            // Pilih file / foto
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

        // Tombol Back dimatikan
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* diblokir */ }
        })

        enterImmersive()

        if (savedInstanceState == null) webView.loadUrl(startUrl())
        else webView.restoreState(savedInstanceState)

        handler.postDelayed(watchdog, 1000)
    }

    private fun startUrl() = "$HUB_URL?school=$NPSN"

    // Loading screen minimal tampil 2 detik, lalu memudar
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

    // Cek tiap 2,5 detik: kalau siswa melepas sematan saat ujian -> pelanggaran + minta sematkan lagi
    private val watchdog = object : Runnable {
        override fun run() {
            if (pinWanted && !isPinned()) {
                if (examActive) sendViolation("Melepas mode sematkan layar")
                ensurePinned()
            }
            handler.postDelayed(this, 2500)
        }
    }

    // ---------- Deteksi pelanggaran native ----------
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
        sendViolation("Menekan tombol Home / pindah aplikasi")
    }

    override fun onPause() {
        super.onPause()
        sendViolation("Aplikasi ujian ditinggalkan")
        // webView.onPause() sengaja TIDAK dipanggil agar halaman tetap bisa menerima laporan
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
        webView.removeJavascriptInterface("AndroidApp")
        webView.destroy()
        super.onDestroy()
    }
}
