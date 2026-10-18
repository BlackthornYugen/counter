package dev.jskw.counter.util

import android.util.Log

object AppLog {
    fun d(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: RuntimeException) {
            println("DEBUG: [$tag] $msg")
        }
    }

    fun i(tag: String, msg: String) {
        try {
            Log.i(tag, msg)
        } catch (_: RuntimeException) {
            println("INFO: [$tag] $msg")
        }
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        } catch (_: RuntimeException) {
            println("WARN: [$tag] $msg")
            tr?.printStackTrace()
        }
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: RuntimeException) {
            System.err.println("ERROR: [$tag] $msg")
            tr?.printStackTrace()
        }
    }
}
