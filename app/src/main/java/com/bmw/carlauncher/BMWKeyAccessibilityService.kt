package com.bmw.carlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class BMWKeyAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
        Log.d("BMWKeyAccessibility", "Service connected and filtering key events")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_F12,
                KeyEvent.KEYCODE_F11,
                KeyEvent.KEYCODE_F10,
                KeyEvent.KEYCODE_BUTTON_MODE -> {
                    Log.d("BMWKeyAccessibility", "Intercepted key ${event.keyCode} -> bringing BMWCarLauncher to front")
                    bringLauncherToFront()
                    return true
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun bringLauncherToFront() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e("BMWKeyAccessibility", "Error starting MainActivity: ${e.message}", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nu este necesara procesarea altor evenimente UI
    }

    override fun onInterrupt() {
        Log.d("BMWKeyAccessibility", "Service interrupted")
    }
}
