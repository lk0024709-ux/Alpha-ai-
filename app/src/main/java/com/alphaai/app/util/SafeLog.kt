package com.alphaai.app.util

import android.util.Log
import com.alphaai.app.BuildConfig

/** Debug-only logging. Release builds emit no app logs; all debug messages are capped at 200 chars. */
object SafeLog {
    private const val MAX = 200

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, truncate(msg))
    }

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, truncate(msg))
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.w(tag, truncate(msg)) // never print throwable payloads
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (BuildConfig.DEBUG) Log.e(tag, truncate(msg))
    }

    private fun truncate(msg: String): String =
        if (msg.length <= MAX) msg else msg.take(MAX) + "…[" + msg.length + " chars]"
}
