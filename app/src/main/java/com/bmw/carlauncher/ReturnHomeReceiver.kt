package com.bmw.carlauncher

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log

class ReturnHomeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "ReturnHomeReceiver triggered with action: $action")

        if (action == "com.bmw.carlauncher.ACTION_LOCK_SCREEN") {
            val locked = BMWAccessibilityService.lockScreen()
            Log.i(TAG, "Direct ACTION_LOCK_SCREEN: lockScreen returned $locked")
            return
        }

        val prefs = context.getSharedPreferences("agama_settings", Context.MODE_PRIVATE)

        val isA2dpConnect = (action == BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1) == BluetoothProfile.STATE_CONNECTED)
        val isAclConnect = (action == BluetoothDevice.ACTION_ACL_CONNECTED)

        // ==============================================================
        // 1. CONECTARE BLUETOOTH LA DISPOZITIVUL SELECTAT
        // ==============================================================
        if (isAclConnect || isA2dpConnect) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            val devAddress = device?.address ?: ""
            Log.i(TAG, "Bluetooth device connected ($action): '$devName', address=$devAddress")

            val savedCarBt = prefs.getString("car_bt_address", null)
            val isTargetDevice = if (!savedCarBt.isNullOrEmpty()) {
                devAddress.equals(savedCarBt, ignoreCase = true)
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv ignorat (nu corespunde MAC-ului mașinii): '$devName', adresa=$devAddress")
                return
            }

            Log.i(TAG, "Dispozitiv mașină detectat ($devAddress)! Pornire muzică și aducere în prim-plan...")
            MainActivity.lastDisconnectTimestamp = 0L
            MainActivity.isCarConnectedState = true

            // A. Pornește redarea radio / muzică
            try {
                val radioIntent = Intent(context, RadioService::class.java).apply {
                    this.action = RadioService.ACTION_START_PLAY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(radioIntent)
                } else {
                    context.startService(radioIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Eroare la pornirea RadioService: ${e.message}")
            }

            // B. Notifică instanța dacă e deja activă
            MainActivity.instance?.let { act ->
                act.runOnUiThread {
                    act.startRadioPlayback()
                }
            }

            // C. Aduce aplicația în prim-plan
            BMWAccessibilityService.bringToForeground(context)
            return
        }

        // ==============================================================
        // 2. DECONECTARE BLUETOOTH DE LA DISPOZITIVUL SELECTAT
        // ==============================================================
        val isAclDisconnect = (action == BluetoothDevice.ACTION_ACL_DISCONNECTED)
        val isBtOff = (action == android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED &&
                (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_OFF ||
                 intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1) == android.bluetooth.BluetoothAdapter.STATE_TURNING_OFF))

        if (isAclDisconnect || isBtOff) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            val devAddress = device?.address ?: ""
            Log.i(TAG, "Bluetooth device disconnected ($action): '$devName', address=$devAddress")

            val savedCarBt = prefs.getString("car_bt_address", null)
            val isTargetDevice = if (isBtOff) {
                true
            } else if (!savedCarBt.isNullOrEmpty()) {
                devAddress.equals(savedCarBt, ignoreCase = true)
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv deconectat ignorat: '$devName', adresa=$devAddress")
                return
            }

            val pendingResult = goAsync()
            Log.i(TAG, "Dispozitiv țintă deconectat ($devAddress)! Oprire muzică, revenire pe Home și blocare telefon...")
            MainActivity.isCarConnectedState = false
            MainActivity.lastDisconnectTimestamp = SystemClock.elapsedRealtime()

            // A. Oprește radioul / muzica la deconectare
            try {
                context.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(context.packageName))
            } catch (_: Exception) {}

            MainActivity.instance?.let { act ->
                act.runOnUiThread {
                    act.stopAllAudioPlayers()
                    try {
                        act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } catch (_: Exception) {}
                }
            }

            // B. Revine la ecranul de Home al telefonului
            val homeSuccess = BMWAccessibilityService.goToHomeScreen()
            if (!homeSuccess) {
                try {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare lansare Home Intent", e)
                }
            }

            // C. Blochează ecranul telefonului după tranziția la Home
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val locked = BMWAccessibilityService.lockScreen()
                    Log.i(TAG, "BMWAccessibilityService.lockScreen() apelat: $locked")
                } catch (e: Exception) {
                    Log.e(TAG, "Eroare lockScreen in ReturnHomeReceiver: ${e.message}")
                } finally {
                    try {
                        pendingResult.finish()
                    } catch (_: Exception) {}
                }
            }, 450L)

            return
        }

        launchMainActivity(context)
    }

    private fun launchMainActivity(context: Context) {
        BMWAccessibilityService.bringToForeground(context)
    }

    companion object {
        private const val TAG = "ReturnHomeReceiver"
    }
}
