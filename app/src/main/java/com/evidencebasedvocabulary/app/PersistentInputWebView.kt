package com.evidencebasedvocabulary.app

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.webkit.WebView

private const val TAG = "PersistentInputWebView"

/**
 * Custom WebView to handle persistent backspace issues and lockdown interactions.
 */
class PersistentInputWebView(context: Context) : WebView(context) {
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
