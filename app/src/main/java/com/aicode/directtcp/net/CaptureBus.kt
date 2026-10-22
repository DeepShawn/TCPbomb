package com.aicode.directtcp.net

import android.os.Handler
import android.os.Looper

sealed class CaptureEvent {
    data class Started(val targetPackage: String?) : CaptureEvent()
    data class Captured(val host: String, val port: Int, val fromIp: String, val fromPort: Int) : CaptureEvent()
    data class Stopped(val reason: String) : CaptureEvent()
    data class Error(val message: String) : CaptureEvent()
}

/** VpnService 与界面之间的进程内事件通道（同进程，直接回调即可） */
object CaptureBus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: ((CaptureEvent) -> Unit)? = null

    fun register(callback: (CaptureEvent) -> Unit) {
        listener = callback
    }

    fun unregister() {
        listener = null
    }

    fun emit(event: CaptureEvent) {
        main.post { listener?.invoke(event) }
    }
}
