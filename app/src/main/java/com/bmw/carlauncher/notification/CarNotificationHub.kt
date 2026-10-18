package com.bmw.carlauncher.notification

import android.os.Handler
import android.os.Looper

object CarNotificationHub {
    private val mainHandler = Handler(Looper.getMainLooper())
    var onNotificationReceived: ((CarNotification) -> Unit)? = null
    var currentNotification: CarNotification? = null
        private set

    fun postNotification(notification: CarNotification) {
        currentNotification = notification
        mainHandler.post {
            onNotificationReceived?.invoke(notification)
        }
    }

    fun clear() {
        currentNotification = null
    }
}
