package com.bmw.carlauncher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class BMWAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            serviceInfo = info
            Log.d(TAG, "BMWAccessibilityService connected, key filter active.")
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring AccessibilityServiceInfo", e)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Log.i(TAG, "GLOBAL onKeyEvent: keyCode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}), action=${event.action}")
        when (event.keyCode) {
            KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_F11,
            KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_BUTTON_MODE -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    Log.i(TAG, "Return key ${event.keyCode} detected! Returning to BMW Car Launcher...")
                    returnToLauncher()
                }
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun returnToLauncher() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("EXTRA_F12_RETURN", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity directly, attempting launcher fallback", e)
            try {
                val fallback = packageManager.getLaunchIntentForPackage(packageName)
                fallback?.apply {
                    putExtra("EXTRA_F12_RETURN", true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (fallback != null) {
                    startActivity(fallback)
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback launch also failed", e2)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nu este necesar pentru interceptarea tastelor
    }

    override fun onInterrupt() {
        Log.d(TAG, "BMWAccessibilityService interrupted")
    }

    companion object {
        private const val TAG = "BMWAccessibility"

        fun isServiceEnabled(context: Context): Boolean {
            val expectedComponentName = ComponentName(context, BMWAccessibilityService::class.java)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)
            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledService = ComponentName.unflattenFromString(componentNameString)
                if (enabledService != null && enabledService == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
