package com.evidencebasedvocabulary.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "EBVTTS"

class AndroidSpeechSynthesis(
    context: Context,
    private val onTtsUnavailable: () -> Unit
) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech = TextToSpeech(context, this)
    private var isReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class WebViewBinding(
        val generation: Long,
        val webView: WeakReference<WebView>
    )

    @Volatile
    private var currentBinding: WebViewBinding? = null
    private val utteranceGenerations = ConcurrentHashMap<String, Long>()

    init {
        val available = tts.engines
        Log.d(TAG, "Available TTS engines: ${available.map { it.name }}")
        if (available.isEmpty()) {
            onTtsUnavailable()
        }

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                dispatchToJs("onSpeechStart", utteranceId, false)
            }

            override fun onDone(utteranceId: String?) {
                dispatchToJs("onSpeechDone", utteranceId, true)
            }

            @Deprecated("Deprecated in Java", ReplaceWith("dispatchToJs(\"onSpeechError\", utteranceId, true)"))
            override fun onError(utteranceId: String?) {
                dispatchToJs("onSpeechError", utteranceId, true)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS Error: $errorCode for $utteranceId")
                dispatchToJs("onSpeechError", utteranceId, true)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                dispatchToJs("onSpeechEnd", utteranceId, true)
            }
        })
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            isReady = true
            Log.d(TAG, "TTS initialized successfully")
        } else {
            Log.e(TAG, "TTS Initialization failed!")
            onTtsUnavailable()
        }
    }

    fun attachWebView(wv: WebView, generation: Long) {
        currentBinding = WebViewBinding(generation, WeakReference(wv))
        Log.d(TAG, "Attached WebView generation $generation")
    }

    fun detachWebView(wv: WebView) {
        if (currentBinding?.webView?.get() === wv) {
            val gen = currentBinding?.generation
            currentBinding = null
            Log.d(TAG, "Detached WebView generation $gen")
            cancel()
        }
    }

    @JavascriptInterface
    fun speak(text: String?, utteranceId: String?) {
        val id = utteranceId ?: "utterance_${System.currentTimeMillis()}"
        val binding = currentBinding
        if (isReady && !text.isNullOrEmpty() && binding != null) {
            Log.d(TAG, "Speaking generation ${binding.generation}: $text")
            utteranceGenerations[id] = binding.generation
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (result != TextToSpeech.SUCCESS) {
                utteranceGenerations.remove(id)
            }
        } else {
            Log.e(TAG, "TTS not ready or text/binding empty. isReady=$isReady")
        }
    }

    @JavascriptInterface
    fun cancel() {
        if (isReady) {
            tts.stop()
            utteranceGenerations.clear()
        }
    }

    @JavascriptInterface
    fun isReady(): Boolean = isReady

    private fun dispatchToJs(functionName: String, utteranceId: String?, terminal: Boolean) {
        val id = utteranceId ?: return
        val expectedGeneration = utteranceGenerations[id] ?: return

        if (terminal) {
            utteranceGenerations.remove(id)
        }

        val quotedId = JSONObject.quote(id)
        mainHandler.post {
            val binding = currentBinding ?: return@post
            if (binding.generation != expectedGeneration) {
                Log.d(TAG, "Dropping stale TTS callback for $id (expected $expectedGeneration, current ${binding.generation})")
                return@post
            }

            val wv = binding.webView.get() ?: return@post
            runCatching {
                wv.evaluateJavascript(
                    "if (typeof $functionName === 'function') { $functionName($quotedId); }",
                    null
                )
            }.onFailure {
                Log.w(TAG, "Failed to dispatch TTS callback to JS", it)
            }
        }
    }

    fun shutdown() {
        currentBinding = null
        utteranceGenerations.clear()
        mainHandler.removeCallbacksAndMessages(null)
        tts.shutdown()
    }
}
