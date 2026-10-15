package id.sch.smpn4.ujian

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
import android.view.KeyEvent
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
        const val SIREN_MS = 30_000L          // lama sirine berbunyi
        const val USE_DEVICE_ADMIN = false    // false = tanpa admin perangkat (HP tidak ikut terkunci)
        const val FOCUS_LOSS_MS = 1200L       // fokus hilang selama ini = dianggap keluar
        const val BACK_DISQUALIFIES = true    // true = Back saat ujian langsung sirine + diskualifikasi
        const val SR = 22050                  // sample rate suara buatan

        private val ALLOWED_HOSTS = listOf(
            "script.google.com",
            "googleusercontent.com",
            "vercel.app"
        )

        // Sirine hidup di luar siklus activity agar tetap bunyi walau aplikasi ditutup
        private var sirenTrack: AudioTrack? = null
        private val sirenHandler = Handler(Looper.getMainLooper())
        @Volatile private var sirenOn = false
    }

    private lateinit var webView: WebView
    private lateinit var splash: View
    private lateinit var blackout: View
    private val handler = Handler(Looper.getMainLooper())

    private var examActive = false
    private var suppressUntil = 0L
    private var lastSent = 0L

    private var pinWanted = true
    private var everPinned = false            // pernah benar-benar tersemat
    private var awaitingPin = false           // baru mulai ujian, menunggu siswa menekan "Mulai" di dialog sematan
    private var lastPinTry = 0L
    private val splashStart = SystemClock.uptimeMillis()
    private var splashHidden = false

    private var disqualified = false
    private var dqReason = ""
    private var exiting = false

    private var sirenData: ShortArray? = null
    private var buzzerData: ShortArray? = null
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

    private val adminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            pinWanted = true
        }

    // Dipicu bila fokus layar hilang lebih dari FOCUS_LOSS_MS (gestur dari bawah, aplikasi lain, dsb.)
    private val focusLossCheck = Runnable {
        hardDisqualify("Meninggalkan layar ujian (gestur / aplikasi lain)")
    }

    // ---------- Jembatan JS: dipanggil halaman ujian ----------
    inner class JsBridge {
        @JavascriptInterface
        fun lock() {
            runOnUiThread {
                if (disqualified) return@runOnUiThread
                examActive = true
                pinWanted = true
                if (!isPinned()) everPinned = false
                // beri waktu siswa menekan "Mulai" pada dialog sematan
                awaitingPin = !isPinned()
                suppressFor(if (awaitingPin) 20_000L else 3_000L)
                ensurePinned()
            }
        }

        @JavascriptInterface
        fun exit() {
            runOnUiThread {
                examActive = false
                pinWanted = false
                everPinned = false
                awaitingPin = false
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

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val cut = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            v.setPadding(cut.left, cut.top, cut.right, maxOf(ime.bottom, cut.bottom))
            insets
        }

        Thread {
            sirenData = buildSiren()
            buzzerData = buildBuzzer()
        }.start()
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
            override fun handleOnBackPressed() {
                // Back (tombol / gestur) saat ujian = pelanggaran berat; di luar ujian diabaikan
                if (BACK_DISQUALIFIES) hardDisqualify("Menekan tombol / gestur Back saat ujian")
            }
        })

        enterImmersive()

        if (savedInstanceState == null) webView.loadUrl(startUrl())
        else webView.restoreState(savedInstanceState)

        if (!USE_DEVICE_ADMIN || isAdminActive()) {
            pinWanted = true
        } else {
            pinWanted = false
            handler.postDelayed({ requestAdmin() }, 1500)
        }
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

    // ---------- Admin perangkat (nonaktif bila USE_DEVICE_ADMIN = false) ----------
    private fun adminComp() = ComponentName(this, AdminReceiver::class.java)

    private fun isAdminActive(): Boolean {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComp())
    }

    private fun requestAdmin() {
        if (isAdminActive()) { pinWanted = true; return }
        try {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp())
                .putExtra(
                    DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Diperlukan agar layar HP terkunci otomatis bila siswa mencoba keluar saat ujian."
                )
            adminLauncher.launch(i)
        } catch (_: Exception) {
            pinWanted = true
        }
    }

    private fun lockScreenNow() {
        if (!USE_DEVICE_ADMIN) return
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (dpm.isAdminActive(adminComp())) dpm.lockNow()
        } catch (_: Exception) {}
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
        everPinned = false
        awaitingPin = false
        handler.removeCallbacks(focusLossCheck)
        try { stopLockTask() } catch (_: Exception) {}
        speakThen("Kamu keluar dari ujian. Kamu keluar dari ujian.") { finishAndRemoveTask() }
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
        try {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            t.setPitch(0.45f)
            t.setSpeechRate(0.8f)
            maxAlarmVolume()
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "exit")
        } catch (_: Exception) { once(); return }
        handler.postDelayed({ once() }, 8000)
    }

    // ---------- Diskualifikasi langsung ----------
    private fun hardDisqualify(reason: String) {
        if (!examActive || disqualified || exiting) return
        if (System.currentTimeMillis() < suppressUntil) return
        disqualified = true
        dqReason = reason
        handler.removeCallbacks(focusLossCheck)
        startSiren()                       // sirine dulu, secepat mungkin
        blackout.visibility = View.VISIBLE
        pushDisqualify()
        examActive = false
        pinWanted = false
        everPinned = false
        awaitingPin = false
        try { stopLockTask() } catch (_: Exception) {}
        handler.postDelayed({ lockScreenNow() }, 400)
    }

    private fun pushDisqualify() {
        val safe = dqReason.replace("'", "").replace("\\", "")
        webView.post {
            webView.evaluateJavascript(
                "window.onNativeDisqualify && window.onNativeDisqualify('$safe')", null
            )
        }
    }

    // ---------- Suara buatan ----------
    private fun buildSiren(): ShortArray {
        val cycle = 3.0
        val n = (SR * cycle).toInt()
        val out = ShortArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val f = 600.0 + 900.0 * (0.5 - 0.5 * Math.cos(2 * Math.PI * t / cycle))
            phase += 2 * Math.PI * f / SR
            val s = Math.sin(phase) + 0.5 * Math.sin(2 * phase) + 0.3 * Math.sin(3 * phase)
            out[i] = (Math.tanh(s * 2.0) * 32767).toInt().toShort()
        }
        return out
    }

    private fun buildBuzzer(): ShortArray {
        val n = (SR * 1.3).toInt()
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val on = (t % 0.45) < 0.30
            if (!on) { out[i] = 0; continue }
            val s = Math.sin(2 * Math.PI * 220 * t) +
                0.6 * Math.sin(2 * Math.PI * 440 * t) +
                0.4 * Math.sin(2 * Math.PI * 660 * t)
            out[i] = (Math.tanh(s * 2.2) * 32767).toInt().toShort()
        }
        return out
    }

    private fun maxAlarmVolume() {
        try {
            val am = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        } catch (_: Exception) {}
    }

    private fun alarmTrack(data: ShortArray): AudioTrack =
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

    private fun playShot(data: ShortArray) {
        try {
            maxAlarmVolume()
            val track = alarmTrack(data)
            track.write(data, 0, data.size)
            track.setVolume(1.0f)
            track.play()
            handler.postDelayed({ try { track.release() } catch (_: Exception) {} },
                data.size * 1000L / SR + 500)
        } catch (_: Exception) {}
    }

    private fun startSiren() {
        if (sirenOn) return
        val data = sirenData ?: return
        try {
            maxAlarmVolume()
            val track = alarmTrack(data)
            track.write(data, 0, data.size)
            track.setLoopPoints(0, data.size, -1)
            track.setVolume(1.0f)
            track.play()
            sirenTrack = track
            sirenOn = true
        } catch (_: Exception) { return }

        val guard = object : Runnable {
            override fun run() {
                if (!sirenOn) return
                maxAlarmVolume()
                sirenHandler.postDelayed(this, 150)
            }
        }
        sirenHandler.post(guard)
        sirenHandler.postDelayed({
            sirenOn = false
            try { sirenTrack?.stop(); sirenTrack?.release() } catch (_: Exception) {}
            sirenTrack = null
        }, SIREN_MS)
    }

    // ---------- Screen pinning ----------
    private fun isPinned(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    private fun ensurePinned() {
        if (isPinned()) return
        val now = System.currentTimeMillis()
        if (now - lastPinTry < 2500) return
        lastPinTry = now
        try { startLockTask() } catch (_: Exception) {}
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val pinned = isPinned()
            if (pinned) {
                everPinned = true
                if (awaitingPin) {            // siswa baru menekan "Mulai": beri jeda singkat
                    awaitingPin = false
                    suppressFor(1500)
                }
            }
            if (pinWanted && !pinned) {
                if (examActive && everPinned) {
                    hardDisqualify("Melepas sematan layar saat ujian")
                } else {
                    if (examActive) sendViolation("Sematan layar belum aktif")
                    ensurePinned()
                }
            }
            handler.postDelayed(this, 600)
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

    // ---------- Pemicu keluar: semuanya langsung dari sistem Android (tidak menunggu timer) ----------
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        hardDisqualify("Mencoba keluar dari aplikasi ujian (Home / pindah aplikasi)")
    }

    override fun onPause() {
        super.onPause()
        hardDisqualify("Aplikasi ujian ditinggalkan")
    }

    override fun onStop() {
        super.onStop()
        hardDisqualify("Aplikasi ujian tidak lagi tampil")
    }

    override fun onResume() {
        super.onResume()
        if (disqualified) {
            blackout.visibility = View.VISIBLE
            pushDisqualify()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            handler.removeCallbacks(focusLossCheck)
            enterImmersive()
        } else {
            sendViolation("Layar ujian kehilangan fokus (notifikasi / aplikasi lain)")
            if (examActive) handler.postDelayed(focusLossCheck, FOCUS_LOSS_MS)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (sirenOn && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_MUTE)) return true
        return super.onKeyDown(keyCode, event)
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
