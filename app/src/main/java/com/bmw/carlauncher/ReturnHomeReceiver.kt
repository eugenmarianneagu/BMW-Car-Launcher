package com.bmw.carlauncher

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class ReturnHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "ReturnHomeReceiver triggered with action: $action")

        if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            Log.i(TAG, "Bluetooth device connected: '$devName', address=${device?.address}")

            // Filtru strict: reacționează DOAR dacă numele dispozitivului conține "BMW", "iDrive" sau "ESP32"
            val isTargetDevice = devName.contains("BMW", ignoreCase = true) ||
                    devName.contains("iDrive", ignoreCase = true) ||
                    devName.contains("ESP32", ignoreCase = true)

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv Bluetooth ignorat (nu este BMW/iDrive/ESP32): '$devName', adresa=${device?.address}")
                return
            }

            Log.i(TAG, "Dispozitiv BMW/ESP32 detectat ('$devName')! Se lanseaza aplicatia...")
            try {
                android.widget.Toast.makeText(context, "BMW iDrive conectat", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}
        }

        if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            Log.i(TAG, "Bluetooth device disconnected: '$devName', address=${device?.address}")

            val isTargetDevice = devName.contains("BMW", ignoreCase = true) ||
                    devName.contains("iDrive", ignoreCase = true) ||
                    devName.contains("ESP32", ignoreCase = true)

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv Bluetooth deconectat ignorat (nu este BMW/iDrive): '$devName'")
                return
            }

            Log.i(TAG, "BMW iDrive deconectat! Inchidere automata BMW Car Launcher...")
            try {
                android.widget.Toast.makeText(context, "BMW iDrive deconectat", android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {}

            MainActivity.closeLauncher()
            return
        }

        launchMainActivity(context)
    }

    private fun launchMainActivity(context: Context) {
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
            Log.i(TAG, "MainActivity lansata cu succes din ReturnHomeReceiver")
        } catch (e: Exception) {
            Log.e(TAG, "Eroare la lansarea MainActivity din ReturnHomeReceiver", e)
        }
    }

    companion object {
        private const val TAG = "BMWAccessibility"
    }
}

