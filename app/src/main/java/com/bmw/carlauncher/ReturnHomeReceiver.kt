package com.bmw.carlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ReturnHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "ReturnHomeReceiver triggered with action: ${intent.action}")
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
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
            context.startActivity(launchIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch MainActivity", e)
        }
    }

    companion object {
        private const val TAG = "BMWAccessibility"
    }
}
