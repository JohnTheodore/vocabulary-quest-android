package com.evidencebasedvocabulary.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.evidencebasedvocabulary.app.ui.theme.EvidenceBasedVocabularyTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "EBVApp"
private const val START_URL = "https://evidencebasedvocabulary.com/"
private const val WEBVIEW_TIMER_PAUSE_GRACE_MS = 5 * 60 * 1000L

private val STOP_MEDIA_SCRIPT = """
    try {
      if (window.speechSynthesis) window.speechSynthesis.cancel();
    } catch(e) {}
    try {
      document.querySelectorAll('audio,video').forEach(function(el) {
        el.pause();
        el.removeAttribute('src');
        el.load();
      });
    } catch(e) {}
    try {
      if (window.Howler) {
        Howler.stop();
        if (Howler.ctx && Howler.ctx.state !== 'closed') Howler.ctx.close();
      }
    } catch(e) {}
""".trimIndent()

private fun Window.enterImmersiveMode() {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    WindowCompat.getInsetsController(this, decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

private fun View.keepEdgeTouchesInApp() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

    fun updateExclusionRects() {
        if (width > 0 && height > 0) {
            systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
    }

    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateExclusionRects()
    }
    post { updateExclusionRects() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the screen on while this Activity is foregrounded. Kiosk
        // learning sessions where the kid pauses for >screen-timeout would
        // otherwise auto-lock, paint the WebView's network stack into a
        // power-save state, and freeze mid-session — observed as
        // exercise-transition-stalled in Sentry for student_mabel on
        // 2026-05-14 and 2026-05-17.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ensure volume is up for media playback
        (getSystemService(AUDIO_SERVICE) as? AudioManager)?.let { audioManager ->
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2,
                    0
                )
            }
        }

        enableEdgeToEdge()
        window.enterImmersiveMode()
        setContent {
            EvidenceBasedVocabularyTheme {
                EvidenceBasedVocabularyWebView(START_URL)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.enterImmersiveMode()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {                                                                                                                                        
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) return true                                                                                                                                      
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (event != null && (event.isMetaPressed || event.isAltPressed || event.isCtrlPressed)) {
            Log.d(TAG, "Intercepted potential system shortcut: keyCode=$keyCode")
        }
        return super.onKeyDown(keyCode, event)
    }
}

/**
 * Custom WebView to handle persistent backspace issues and lockdown interactions.
 */
class PersistentInputWebView(context: android.content.Context) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo?): InputConnection? {
        val baseConnection = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(baseConnection, true) {
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength == 1 && afterLength == 0) {
                    val downEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                    val upEvent = KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
                    return sendKeyEvent(downEvent) && sendKeyEvent(upEvent)
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isMeta = event.isMetaPressed
        
        if (isMeta && (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_H)) {
            Log.d(TAG, "Preventing System Home/Search shortcut")
            return true 
        }
        return super.dispatchKeyEvent(event)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EvidenceBasedVocabularyWebView(url: String) {
    val activity = LocalContext.current as MainActivity
    val window = activity.window
    val lifecycle = activity.lifecycle
    var showTtsWarning by remember { mutableStateOf(false) }
    
    var customView: View? by remember { mutableStateOf(null) }
    var customViewCallback: WebChromeClient.CustomViewCallback? by remember { mutableStateOf(null) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var filePathCallbackState by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // Lifecycle/Timer state
    var timersPausedByWrapper by remember { mutableStateOf(false) }
    var pauseTimersJob by remember { mutableStateOf<Job?>(null) }

    fun emitNativeLifecycle(wv: WebView?, type: String, reason: String? = null) {
        if (wv == null) return
        val detail = JSONObject().apply {
            put("type", type)
            put("reason", reason ?: JSONObject.NULL)
            put("atMs", System.currentTimeMillis()) // For JS parity
            put("nativeAtMs", System.currentTimeMillis())
            put("timersPausedByWrapper", timersPausedByWrapper)
            put("url", wv.url ?: JSONObject.NULL)
            put("lifecycleState", lifecycle.currentState.name)
        }
        val js = "window.dispatchEvent(new CustomEvent('ebv-native-lifecycle', { detail: $detail }));"
        wv.post { wv.evaluateJavascript(js, null) }
    }

    fun scheduleTimerPause(reason: String) {
        pauseTimersJob?.cancel()
        emitNativeLifecycle(webView, "pause-timers-scheduled", reason)
        pauseTimersJob = activity.lifecycleScope.launch {
            delay(WEBVIEW_TIMER_PAUSE_GRACE_MS)
            webView?.let { wv ->
                emitNativeLifecycle(wv, "pause-timers-fired", reason)
                wv.pauseTimers()
                timersPausedByWrapper = true
            }
        }
    }

    fun cancelTimerPause(reason: String) {
        pauseTimersJob?.cancel()
        pauseTimersJob = null
        if (timersPausedByWrapper) {
            webView?.let { wv ->
                wv.resumeTimers()
                timersPausedByWrapper = false
                emitNativeLifecycle(wv, "resume-timers-canceled", reason)
            }
        } else {
            emitNativeLifecycle(webView, "pause-timers-canceled", reason)
        }
    }
    
    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = if (data == null) {
                null
            } else {
                val clipData = data.clipData
                if (clipData != null) {
                    val uris = Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    uris
                } else {
                    val uri = data.data
                    if (uri != null) arrayOf(uri) else null
                }
            }
            filePathCallbackState?.onReceiveValue(results)
        } else {
            filePathCallbackState?.onReceiveValue(null)
        }
        filePathCallbackState = null
    }

    val speechBridge = remember { 
        AndroidSpeechSynthesis(context = activity) {
            showTtsWarning = true
        } 
    }

    fun handleScreenOff() {
        Log.d(TAG, "Screen turned off; scheduling delayed cleanup")
        emitNativeLifecycle(webView, "screen-off")
        
        // We no longer blank the WebView or stop loading immediately on screen-off.
        // Instead, we let it run for a grace period.
        scheduleTimerPause("screen-off")
        
        webView?.let { wv ->
            // Still pause the WebView rendering/JS execution to be a good citizen,
            // but keep timers (network/async tasks) running for the grace period.
            wv.onPause()
            emitNativeLifecycle(wv, "webview-on-pause", "screen-off")
        }
    }

    fun handleScreenOn(reason: String) {
        Log.d(TAG, "Screen active ($reason); resuming")
        emitNativeLifecycle(webView, reason)
        
        cancelTimerPause(reason)
        
        webView?.let { wv ->
            wv.onResume()
            emitNativeLifecycle(wv, "webview-on-resume", reason)
        }
        window.enterImmersiveMode()
    }

    DisposableEffect(activity, speechBridge, url) {
        val screenPowerReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> handleScreenOff()
                    Intent.ACTION_SCREEN_ON -> handleScreenOn("screen-on")
                    Intent.ACTION_USER_PRESENT -> handleScreenOn("user-present")
                }
            }
        }

        val screenPowerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        ContextCompat.registerReceiver(
            activity,
            screenPowerReceiver,
            screenPowerFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            runCatching { activity.unregisterReceiver(screenPowerReceiver) }
        }
    }

    DisposableEffect(Unit) {
        onDispose { 
            speechBridge.shutdown() 
            CookieManager.getInstance().flush()
        }
    }

    // Forward Activity lifecycle to the WebView.
    // We now avoid immediate pauseTimers() on ON_PAUSE to prevent severe
    // JS runtime throttling during transient focus losses.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    emitNativeLifecycle(webView, "activity-on-pause")
                    webView?.let { wv ->
                        // onPause() is appropriate for backgrounding, but we avoid
                        // pauseTimers() here as it's too aggressive.
                        wv.onPause()
                        emitNativeLifecycle(wv, "webview-on-pause", "activity-on-pause")
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    emitNativeLifecycle(webView, "activity-on-resume")
                    window.enterImmersiveMode()
                    cancelTimerPause("activity-on-resume")
                    webView?.let { wv ->
                        wv.onResume()
                        emitNativeLifecycle(wv, "webview-on-resume", "activity-on-resume")
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    emitNativeLifecycle(webView, "activity-on-stop")
                    // Start the 5-minute grace period before globally pausing timers.
                    scheduleTimerPause("activity-on-stop")
                }
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    if (showTtsWarning) {
        LaunchedEffect(Unit) {
            delay(8000L)
            showTtsWarning = false
        }
    }

    val interactionLockdownScript = """
        (function() {
          // Lockdown Zoom
          var meta = document.createElement('meta');
          meta.name = 'viewport';
          meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
          document.getElementsByTagName('head')[0].appendChild(meta);

          // Lockdown Text Selection and Context Menus
          var style = document.createElement('style');
          style.innerHTML = 'body, * { -webkit-user-select: none !important; -webkit-touch-callout: none !important; -webkit-tap-highlight-color: transparent !important; } input, textarea { -webkit-user-select: text !important; }';
          document.getElementsByTagName('head')[0].appendChild(style);

          // Prevent right-click/context menu
          window.oncontextmenu = function(event) {
              event.preventDefault();
              event.stopPropagation();
              return false;
          };

          // Prevent text selection start
          window.addEventListener('selectstart', function(e) { e.preventDefault(); }, false);
          
          // Secondary defense against long-press context menu
          window.addEventListener('contextmenu', function(e) { e.preventDefault(); }, false);
        })();
    """.trimIndent()

    val speechPolyfill = """
        (function() {
          function waitForBridge(callback, retries) {
            if (retries <= 0) return;
            if (window.AndroidSpeech && window.AndroidSpeech.isReady()) {
              callback();
            } else {
              setTimeout(function() { waitForBridge(callback, retries - 1); }, 300);
            }
          }

          window.SpeechSynthesisUtterance = function(text) {
            this.text = text || '';
            this.lang = 'en-US';
            this.rate = 1; this.pitch = 1; this.volume = 1;
            this.onend = null; this.onstart = null; this.onerror = null;
          };

          var _listeners = {};

          window.speechSynthesis = {
            speaking: false,
            pending: false,
            paused: false,

            addEventListener: function(type, fn) {
              if (!_listeners[type]) _listeners[type] = [];
              _listeners[type].push(fn);
            },
            removeEventListener: function(type, fn) {
              if (!_listeners[type]) return;
              _listeners[type] = _listeners[type].filter(function(f) { return f !== fn; });
            },
            dispatchEvent: function(event) {
              var fns = _listeners[event.type] || [];
              fns.forEach(function(fn) { fn(event); });
              return true;
            },

            getVoices: function() {
              return [{ name: 'Android TTS', lang: 'en-US', default: true, localService: true, voiceURI: 'Android TTS' }];
            },
            speak: function(utterance) {
              var self = this;
              waitForBridge(function() {
                self.speaking = true;
                if (utterance.onstart) utterance.onstart({});
                window.AndroidSpeech.speak(utterance.text);
                var duration = Math.max(2000, utterance.text.length * 65);
                setTimeout(function() {
                  self.speaking = false;
                  if (utterance.onend) utterance.onend({});
                }, duration);
              }, 20);
            },
            cancel: function() {
              this.speaking = false;
              if (window.AndroidSpeech) window.AndroidSpeech.cancel();
            },
            pause: function() {},
            resume: function() {}
          };

          setTimeout(function() {
            var event = new Event('voiceschanged');
            window.speechSynthesis.dispatchEvent(event);
          }, 500);
        })();
    """.trimIndent()

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { factoryContext ->
                PersistentInputWebView(factoryContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    keepEdgeTouchesInApp()
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    
                    // Interaction Lockdown: Native Layer
                    isLongClickable = false
                    isHapticFeedbackEnabled = false
                    
                    isFocusable = true
                    isFocusableInTouchMode = true

                    val webViewInstance = this
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setAcceptThirdPartyCookies(webViewInstance, true)
                    }

                    addJavascriptInterface(speechBridge, "AndroidSpeech")

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        mediaPlaybackRequiresUserGesture = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        allowFileAccess = true
                        allowContentAccess = true
                        javaScriptCanOpenWindowsAutomatically = true
                        
                        // Interaction Lockdown: Settings Layer
                        setSupportZoom(false)
                        builtInZoomControls = false
                        displayZoomControls = false
                    }

                    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        WebViewCompat.addDocumentStartJavaScript(this, speechPolyfill, setOf("*"))
                        WebViewCompat.addDocumentStartJavaScript(this, interactionLockdownScript, setOf("*"))
                    }
                    
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (customView != null) {
                                onHideCustomView()
                                return
                            }
                            customView = view
                            customViewCallback = callback
                            val decor = window.decorView as ViewGroup
                            decor.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                            view?.keepEdgeTouchesInApp()
                            window.enterImmersiveMode()
                        }

                        override fun onHideCustomView() {
                            if (customView == null) return
                            val decor = window.decorView as ViewGroup
                            decor.removeView(customView)
                            customView = null
                            customViewCallback?.onCustomViewHidden()
                            customViewCallback = null
                            window.enterImmersiveMode()
                        }

                        override fun onPermissionRequest(request: PermissionRequest?) {
                            request?.grant(request.resources)
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            Log.d(TAG, "JS Console: ${consoleMessage?.message()}")
                            return true
                        }

                        override fun onShowFileChooser(
                            webView: WebView?,
                            filePathCallback: ValueCallback<Array<Uri>>?,
                            fileChooserParams: FileChooserParams?
                        ): Boolean {
                            filePathCallbackState?.onReceiveValue(null)
                            filePathCallbackState = filePathCallback
                            val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "*/*"
                            }
                            try {
                                fileChooserLauncher.launch(intent)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error launching file chooser", e)
                                filePathCallbackState?.onReceiveValue(null)
                                filePathCallbackState = null
                                return false
                            }
                            return true
                        }
                    }
                    
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            canGoBack = view?.canGoBack() ?: false
                            CookieManager.getInstance().flush()
                            
                            view?.requestFocus()
                            
                            // Lockdown: Force scripts again on finish to be sure
                            view?.evaluateJavascript(interactionLockdownScript, null)
                            
                            view?.evaluateJavascript("""
                                (function() {
                                    if (window.Howler && Howler.ctx && Howler.ctx.state === 'suspended') {
                                        const resume = () => Howler.ctx.resume();
                                        document.addEventListener('touchstart', resume, { once: true });
                                        document.addEventListener('mousedown', resume, { once: true });
                                        resume();
                                    }
                                    
                                    const input = document.querySelector('input:not([type="hidden"]):not([type="submit"]), textarea, [contenteditable="true"]');
                                    if (input) {
                                        input.focus();
                                        input.click();
                                    }
                                })();
                            """.trimIndent(), null)
                        }
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                    }
                    
                    loadUrl(url)
                    webView = this
                }
            }
        )

        if (showTtsWarning) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.Black.copy(alpha = 0.7f)
                    ),
                    modifier = Modifier.padding(horizontal = 24.dp)
                ) {
                    Text(
                        text = "No TTS engine found. Go to Settings → Accessibility → Text-to-speech to install one.",
                        color = Color.White,
                        modifier = Modifier.padding(16.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    BackHandler(enabled = customView != null || canGoBack) {
        if (customView != null) {
            webView?.webChromeClient?.onHideCustomView()
        } else {
            webView?.goBack()
        }
    }
}
