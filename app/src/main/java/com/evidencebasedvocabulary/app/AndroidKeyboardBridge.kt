package com.evidencebasedvocabulary.app

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import java.net.URL

private const val TAG = "EBVKeyboardBridge"

/**
 * JavaScript interface to handle native keyboard requests from the web app.
 */
class AndroidKeyboardBridge(
    private val activity: MainActivity,
    private val webViewProvider: () -> WebView?
) {
    @JavascriptInterface
    fun showSpellingKeyboard(reason: String?) {
        val wv = webViewProvider() ?: return
        
        // Security check: only allow requests from the trusted host
        val currentUrl = wv.url ?: return
        try {
            val host = URL(currentUrl).host
            if (host != "evidencebasedvocabulary.com" && !host.endsWith(".evidencebasedvocabulary.com")) {
                Log.w(TAG, "Keyboard request rejected: unauthorized host $host")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Keyboard request rejected: invalid URL", e)
            return
        }

        wv.post {
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                Log.d(TAG, "Showing spelling keyboard. Reason: $reason")
                wv.requestFocus()
                
                // Primary method: Use WindowInsetsController
                WindowCompat.getInsetsController(activity.window, wv).show(WindowInsetsCompat.Type.ime())
                
                // Fallback: InputMethodManager
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}
