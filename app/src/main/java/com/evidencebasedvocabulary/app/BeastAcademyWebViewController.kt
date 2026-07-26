package com.evidencebasedvocabulary.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewRenderProcess
import android.webkit.WebViewRenderProcessClient
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "BeastAcademyWebViewController"
private const val START_URL = "https://evidencebasedvocabulary.com/"
private const val RECOVERY_GRACE_PERIOD_MS = 2000L
private const val RECOVERY_LIMIT_WINDOW_MS = 60000L
private const val MAX_AUTO_RECOVERIES = 3
private const val POPUP_TIMEOUT_MS = 10000L
private const val LOAD_TIMEOUT_MS = 20000L

enum class RecoveryReason {
    RENDERER_CRASHED,
    RENDERER_KILLED,
    RENDERER_HUNG,
    MANUAL_RELOAD,
    FALLBACK
}

class BeastAcademyWebViewController(
    private val activity: MainActivity,
    private val speechBridge: AndroidSpeechSynthesis
) {
    private var host: FrameLayout? = null
    private var mainWebView: WebView? = null
    private var webViewGeneration = 0L
    private val popupWebViews = ConcurrentHashMap.newKeySet<WebView>()
    
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    
    private var lastSafeUrl: String = START_URL
    private var isRecovering = false
    private val recoveryTimestamps = mutableListOf<Long>()
    
    private val mainHandler = Handler(Looper.getMainLooper())
    private var hangWatchdogJob: Runnable? = null
    private var loadTimeoutJob: Runnable? = null
    
    var onRecoveryStateChanged: ((Boolean, String?) -> Unit)? = null

    private val keyboardBridge = AndroidKeyboardBridge(activity) { mainWebView }

    val hasCustomView: Boolean get() = customView != null
    val canGoBack: Boolean get() = mainWebView?.canGoBack() ?: false

    private val interactionLockdownScript = """
        (function() {
          if (window.__ebvLockdownInstalled) return;
          window.__ebvLockdownInstalled = true;
          var meta = document.createElement('meta');
          meta.name = 'viewport';
          meta.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
          document.getElementsByTagName('head')[0].appendChild(meta);
          var style = document.createElement('style');
          style.innerHTML = 'body, * { -webkit-user-select: none !important; -webkit-touch-callout: none !important; -webkit-tap-highlight-color: transparent !important; } input, textarea { -webkit-user-select: text !important; }';
          document.getElementsByTagName('head')[0].appendChild(style);
          window.oncontextmenu = function(event) { event.preventDefault(); event.stopPropagation(); return false; };
          window.addEventListener('selectstart', function(e) { e.preventDefault(); }, false);
          window.addEventListener('contextmenu', function(e) { e.preventDefault(); }, false);
        })();
    """.trimIndent()

    private val speechPolyfill = """
        (function() {
          if (window.__ebvSpeechPolyfillInstalled) return;
          window.__ebvSpeechPolyfillInstalled = true;
          
          const utterances = new Map();
          
          window.onSpeechStart = function(id) {
            const u = utterances.get(id);
            if (u && u.onstart) u.onstart({ target: u });
          };
          window.onSpeechDone = function(id) {
            const u = utterances.get(id);
            if (u) {
              if (u.onend) u.onend({ target: u });
              utterances.delete(id);
            }
          };
          window.onSpeechError = function(id) {
            const u = utterances.get(id);
            if (u) {
              if (u.onerror) u.onerror({ target: u, error: 'native-error' });
              utterances.delete(id);
            }
          };
          window.onSpeechEnd = function(id) {
            const u = utterances.get(id);
            if (u) {
              if (u.onend) u.onend({ target: u });
              utterances.delete(id);
            }
          };

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
              waitForBridge(function() {
                const id = "u_" + Date.now() + "_" + Math.random().toString(36).substr(2, 9);
                utterances.set(id, utterance);
                window.AndroidSpeech.speak(utterance.text, id);
              }, 20);
            },
            cancel: function() {
              utterances.clear();
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

    private val spellingKeyboardScript = """
        (function() {
          if (window.__ebvSpellingKeyboardInstalled) return;
          window.__ebvSpellingKeyboardInstalled = true;
          const seenInputs = new WeakSet();
          function checkAndShowKeyboard(reason) {
            const input = document.querySelector('.spell-hidden-input[aria-label="Type spelling"]');
            if (!input) return;
            if (seenInputs.has(input)) return;
            const isVisible = input.offsetWidth > 0 || input.offsetHeight > 0;
            const isCooldown = input.closest('.exercise-phase.exercise-cooldown');
            if (isVisible && !isCooldown) {
              seenInputs.add(input);
              try { input.focus({ preventScroll: true }); } catch(e) { input.focus(); }
              if (window.AndroidKeyboard) window.AndroidKeyboard.showSpellingKeyboard(reason);
            }
          }
          const observer = new MutationObserver(() => checkAndShowKeyboard('mutation'));
          observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
          window.addEventListener('focusin', () => checkAndShowKeyboard('focusin'));
          window.addEventListener('pageshow', () => checkAndShowKeyboard('pageshow'));
          checkAndShowKeyboard('init');
        })();
    """.trimIndent()

    fun attachHost(newHost: FrameLayout) {
        host = newHost
        if (mainWebView == null) {
            rebuildMainWebView(RecoveryReason.FALLBACK)
        } else {
            mainWebView?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                newHost.addView(wv)
            }
        }
    }

    fun detachHost(detachedHost: FrameLayout) {
        if (host === detachedHost) {
            host = null
        }
    }

    fun manualReload() {
        rebuildMainWebView(RecoveryReason.MANUAL_RELOAD)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun rebuildMainWebView(reason: RecoveryReason) {
        if (isRecovering && reason != RecoveryReason.MANUAL_RELOAD) return
        
        if (reason != RecoveryReason.MANUAL_RELOAD && !isAllowedToAutoRecover()) {
            Log.e(TAG, "Too many recoveries. Stopping auto-rebuild.")
            onRecoveryStateChanged?.invoke(true, "App keeps stopping. Please try a manual reload.")
            return
        }

        isRecovering = true
        onRecoveryStateChanged?.invoke(true, if (reason == RecoveryReason.RENDERER_HUNG) "App not responding. Recovering..." else "Recovering...")

        // Cleanup popups
        val popups = ArrayList(popupWebViews)
        popups.forEach { destroyPopup(it) }

        val oldView = mainWebView
        destroyMainWebView(oldView)

        webViewGeneration++
        val context = activity
        val newView = PersistentInputWebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            keepEdgeTouchesInApp()
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            isLongClickable = false
            isHapticFeedbackEnabled = false
            isFocusable = true
            isFocusableInTouchMode = true
        }

        mainWebView = newView
        speechBridge.attachWebView(newView, webViewGeneration)
        setupWebView(newView)
        
        host?.addView(newView)
        
        val provider = WebViewCompat.getCurrentWebViewPackage(context)
        Log.d(TAG, """
            Rebuilt WebView gen $webViewGeneration. 
            Reason: $reason. 
            App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). 
            SDK: ${Build.VERSION.SDK_INT}. 
            Model: ${Build.MODEL}. 
            Provider: ${provider?.packageName} ${provider?.versionName}. 
            URL: ${lastSafeUrl.substringBefore("?").substringBefore("#")}. 
            Popups: ${popupWebViews.size}
        """.trimIndent().replace("\n", " "))
        
        loadTimeoutJob?.let { mainHandler.removeCallbacks(it) }
        val timeout = Runnable {
            if (isRecovering && mainWebView === newView) {
                Log.e(TAG, "Recovery load timed out.")
                onRecoveryStateChanged?.invoke(true, "Load failed. Please check your connection.")
            }
        }
        loadTimeoutJob = timeout
        mainHandler.postDelayed(timeout, LOAD_TIMEOUT_MS)

        newView.loadUrl(lastSafeUrl)
    }

    private fun setupWebView(wv: WebView) {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        wv.addJavascriptInterface(speechBridge, "AndroidSpeech")
        wv.addJavascriptInterface(keyboardBridge, "AndroidKeyboard")
        
        if (BuildConfig.DEBUG) {
            wv.addJavascriptInterface(object {
                @JavascriptInterface
                fun triggerHang() {
                    Log.d(TAG, "Hang triggered via JS")
                    mainHandler.post {
                        val end = SystemClock.uptimeMillis() + 30000
                        while (SystemClock.uptimeMillis() < end);
                    }
                }
                @JavascriptInterface
                fun triggerRendererHang() {
                    Log.d(TAG, "Renderer hang triggered via JS")
                    wv.post {
                        wv.evaluateJavascript("(function(){ const end = Date.now() + 30000; while(Date.now() < end); })();", null)
                    }
                }
            }, "AndroidDebug")
            WebView.setWebContentsDebuggingEnabled(true)
        }

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            
            val baseUa = WebSettings.getDefaultUserAgent(wv.context)
            val chromeToken = Regex("Chrome/([\\d.]+)").find(baseUa)?.groupValues?.get(1) ?: "122.0.0.0"
            userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeToken Safari/537.36"
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(wv, speechPolyfill, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(wv, interactionLockdownScript, setOf("*"))
            WebViewCompat.addDocumentStartJavaScript(wv, spellingKeyboardScript, setOf("*"))
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                url?.let {
                    if (it.startsWith("https://evidencebasedvocabulary.com/") || it.startsWith("https://beastacademy.com/")) {
                        lastSafeUrl = it
                    }
                }
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                if (isRecovering && view === mainWebView) {
                    Log.i(TAG, "Page commit visible. Recovery successful.")
                    isRecovering = false
                    onRecoveryStateChanged?.invoke(false, null)
                    loadTimeoutJob?.let { mainHandler.removeCallbacks(it) }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                CookieManager.getInstance().flush()
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(speechPolyfill, null)
                    view?.evaluateJavascript(interactionLockdownScript, null)
                    view?.evaluateJavascript(spellingKeyboardScript, null)
                }
                
                view?.evaluateJavascript("""
                    (function() {
                        if (window.Howler && Howler.ctx && Howler.ctx.state === 'suspended') {
                            const resume = () => Howler.ctx.resume();
                            document.addEventListener('touchstart', resume, { once: true });
                            document.addEventListener('mousedown', resume, { once: true });
                            resume();
                        }
                    })();
                """.trimIndent(), null)
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.didCrash() ?: false
                } else false
                val priority = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    detail?.rendererPriorityAtExit() ?: -1
                } else -1
                
                Log.e(TAG, "Renderer gone! didCrash=$didCrash priority=$priority generation=$webViewGeneration")
                if (view === mainWebView) {
                    val reason = if (didCrash) RecoveryReason.RENDERER_CRASHED else RecoveryReason.RENDERER_KILLED
                    mainHandler.post { rebuildMainWebView(reason) }
                    return true
                }
                popupWebViews.find { it === view }?.let {
                    destroyPopup(it)
                    return true
                }
                return false
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val hostStr = uri.host ?: ""
                if (hostStr.endsWith("evidencebasedvocabulary.com") || hostStr.endsWith("beastacademy.com")) {
                    return false
                }
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                hideCustomViewInternal()
                customView = view
                customViewCallback = callback
                (activity.window.decorView as? ViewGroup)?.let { decor ->
                    (view?.parent as? ViewGroup)?.removeView(view)
                    decor.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                    activity.window.enterImmersiveMode()
                }
            }
            override fun onHideCustomView() = hideCustomViewInternal()
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popup = createPopupWebView(wv.context)
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                Log.d(TAG, "JS Console: ${consoleMessage?.message()}")
                return true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            wv.setWebViewRenderProcessClient(ContextCompat.getMainExecutor(activity), object : WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    if (view !== mainWebView || isRecovering) return
                    Log.w(TAG, "Renderer unresponsive! generation=$webViewGeneration")
                    onRecoveryStateChanged?.invoke(true, "App not responding...")
                    hangWatchdogJob?.let { mainHandler.removeCallbacks(it) }
                    val job = Runnable {
                        Log.e(TAG, "Renderer grace period expired. Terminating.")
                        val terminated = runCatching { renderer?.terminate() == true }.getOrDefault(false)
                        if (!terminated) {
                            rebuildMainWebView(RecoveryReason.RENDERER_HUNG)
                        } else {
                            mainHandler.postDelayed({
                                if (mainWebView === view && !isRecovering) {
                                    Log.w(TAG, "onRenderProcessGone did not arrive after terminate. Forcing recovery.")
                                    rebuildMainWebView(RecoveryReason.RENDERER_HUNG)
                                }
                            }, 2000L)
                        }
                    }
                    hangWatchdogJob = job
                    mainHandler.postDelayed(job, RECOVERY_GRACE_PERIOD_MS)
                }
                override fun onRenderProcessResponsive(view: WebView, renderer: WebViewRenderProcess?) {
                    if (view !== mainWebView) return
                    Log.i(TAG, "Renderer responsive again.")
                    hangWatchdogJob?.let { mainHandler.removeCallbacks(it); hangWatchdogJob = null }
                    if (!isRecovering) onRecoveryStateChanged?.invoke(false, null)
                }
            })
        }
    }

    private fun createPopupWebView(context: Context): WebView {
        val popup = WebView(context)
        popupWebViews.add(popup)
        var handled = false
        popup.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (handled) return true
                val uri = request?.url ?: return false
                handled = true
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                destroyPopup(view ?: popup)
                return true
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                destroyPopup(view ?: popup); return true
            }
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView?) = destroyPopup(window ?: popup)
        }
        mainHandler.postDelayed({ if (!handled) destroyPopup(popup) }, POPUP_TIMEOUT_MS)
        return popup
    }

    private fun destroyPopup(wv: WebView) {
        popupWebViews.remove(wv)
        runCatching {
            wv.stopLoading()
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
    }

    private fun destroyMainWebView(view: WebView?) {
        if (view == null) return
        hangWatchdogJob?.let { mainHandler.removeCallbacks(it) }
        hangWatchdogJob = null
        loadTimeoutJob?.let { mainHandler.removeCallbacks(it) }
        loadTimeoutJob = null
        hideCustomViewInternal()
        speechBridge.detachWebView(view)
        if (mainWebView === view) mainWebView = null
        runCatching {
            view.stopLoading()
            view.removeJavascriptInterface("AndroidSpeech")
            view.removeJavascriptInterface("AndroidKeyboard")
            view.removeJavascriptInterface("AndroidDebug")
            (view.parent as? ViewGroup)?.removeView(view)
            view.removeAllViews()
            view.destroy()
        }
    }

    fun hideCustomView() = hideCustomViewInternal()

    private fun hideCustomViewInternal() {
        val view = customView
        customView = null
        if (view != null) (activity.window.decorView as? ViewGroup)?.removeView(view)
        val callback = customViewCallback
        customViewCallback = null
        runCatching { callback?.onCustomViewHidden() }
        activity.window.enterImmersiveMode()
    }

    private fun isAllowedToAutoRecover(): Boolean {
        val now = SystemClock.uptimeMillis()
        recoveryTimestamps.removeAll { it < now - RECOVERY_LIMIT_WINDOW_MS }
        if (recoveryTimestamps.size >= MAX_AUTO_RECOVERIES) return false
        recoveryTimestamps.add(now)
        return true
    }

    fun handlePause() {
        hideCustomViewInternal()
        speechBridge.cancel()
        mainWebView?.let { wv ->
            wv.evaluateJavascript("""
                try {
                  if (window.speechSynthesis) window.speechSynthesis.cancel();
                  document.querySelectorAll('audio, video').forEach(function(el) { el.pause(); });
                  if (window.Howler) {
                    Howler.stop();
                    if (Howler.ctx && Howler.ctx.state === 'running') {
                      Howler.ctx.suspend();
                    }
                  }
                } catch(e) {}
            """.trimIndent(), null)
            wv.onPause()
            wv.pauseTimers()
        }
    }

    fun handleResume() {
        activity.window.enterImmersiveMode()
        mainWebView?.let { wv ->
            wv.resumeTimers()
            wv.onResume()
        }
    }

    fun handleBack(): Boolean {
        if (customView != null) { hideCustomViewInternal(); return true }
        mainWebView?.let { if (it.canGoBack()) { it.goBack(); return true } }
        return false
    }

    fun shutdown() {
        mainWebView?.let { destroyMainWebView(it) }
        val popups = ArrayList(popupWebViews)
        popups.forEach { destroyPopup(it) }
        popupWebViews.clear()
        speechBridge.shutdown()
    }

    private fun View.keepEdgeTouchesInApp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        fun updateExclusionRects() {
            if (width > 0 && height > 0) systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateExclusionRects() }
        post { updateExclusionRects() }
    }
}
