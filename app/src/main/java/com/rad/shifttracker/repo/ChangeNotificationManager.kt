package com.rad.shifttracker.repo

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal class ChangeNotificationManager(private val logTag: String = "ChangeNotifier") {
    private val callbacks = ConcurrentHashMap<Int, () -> Unit>()
    private val nextId = AtomicInteger(0)

    fun register(callback: () -> Unit): Int {
        val id = nextId.getAndIncrement()
        callbacks[id] = callback
        return id
    }

    fun unregister(token: Int) {
        callbacks.remove(token)
    }

    fun notifyChange() {
        for ((_, cb) in callbacks) {
            try {
                cb()
            } catch (e: Exception) {
                Log.w(logTag, "onChange callback failed", e)
            }
        }
    }
}
