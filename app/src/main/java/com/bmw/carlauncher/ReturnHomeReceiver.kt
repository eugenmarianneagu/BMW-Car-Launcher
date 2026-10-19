package com.bmw.carlauncher

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
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

        if (isAclConnect || isA2dpConnect) {
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""
            Log.i(TAG, "Bluetooth device connected ($action): '$devName', address=${device?.address}")

            // Filtru: daca exista dispozitiv selectat in setari, verifica adresa MAC; altfel verifica BMW/iDrive/ESP32
            val savedCarBt = prefs.getString("car_bt_address", null)
            val isTargetDevice = if (!savedCarBt.isNullOrEmpty()) {
                device?.address.equals(savedCarBt, ignoreCase = true)
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv Bluetooth ignorat (nu este mașina selectată): '$devName', adresa=${device?.address}")
                return
            }

            Log.i(TAG, "Dispozitiv mașină detectat ('$devName')! Pornire radio în fundal (fără aprindere ecran)...")
            MainActivity.lastDisconnectTimestamp = 0L
            MainActivity.isCarConnectedState = true

            // Asigură că force_display_mode este resetat pe auto pentru a permite comutarea automată
            prefs.edit().putString("force_display_mode", "auto").apply()

            // 1. Pornește redarea radio imediat în fundal (ecranul rămâne stins/blocat)
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

            // 2. Notifică instanța activă pentru actualizare orientare bord dacă rulează deja
            MainActivity.instance?.let { act ->
                act.runOnUiThread {
                    act.checkBluetoothConnectionAndUpdateOrientation()
                    act.startRadioPlayback()
                }
            }
            return
        }

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
            Log.i(TAG, "Bluetooth device disconnected ($action): '$devName', address=${device?.address}")

            val savedCarBt = prefs.getString("car_bt_address", null)
            val isTargetDevice = if (isBtOff) {
                true
            } else if (!savedCarBt.isNullOrEmpty()) {
                device?.address?.equals(savedCarBt, ignoreCase = true) == true
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            if (!isTargetDevice) {
                Log.d(TAG, "Dispozitiv Bluetooth deconectat ignorat: '$devName', adresa=${device?.address}")
                return
            }

            Log.i(TAG, "Dispozitiv țintă deconectat ('$devName')! Trecere în mod Portret și blocare ecran...")
            MainActivity.isCarConnectedState = false
            MainActivity.lastDisconnectTimestamp = android.os.SystemClock.elapsedRealtime()

            // Asigură că force_display_mode este resetat pe auto
            prefs.edit().putString("force_display_mode", "auto").apply()

            // Oprește radioul la deconectare
            try {
                context.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(context.packageName))
            } catch (_: Exception) {}

            // Notifică instanța activă pentru actualizare la portret
            MainActivity.instance?.let { act ->
                act.runOnUiThread {
                    act.checkBluetoothConnectionAndUpdateOrientation()
                    act.lockScreenIfPossible()
                }
            }

            // Blochează ecranul imediat prin serviciul de accesibilitate
            try {
                val locked = BMWAccessibilityService.lockScreen()
                Log.i(TAG, "BMWAccessibilityService.lockScreen() apelat din ReturnHomeReceiver: $locked")
            } catch (e: Exception) {
                Log.e(TAG, "Eroare lockScreen in ReturnHomeReceiver: ${e.message}")
            }
            return
        }

        launchMainActivity(context)
    }

    private fun launchMainActivity(context: Context) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                this.action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                putExtra("EXTRA_CAR_BT_CONNECTED", true)
                putExtra("EXTRA_F12_RETURN", true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            // Dacă AccessibilityService este activ, folosește contextul său privilegiat pentru pornire din ecran blocat
            BMWAccessibilityService.instance?.let { a11y ->
                try {
                    a11y.startActivity(launchIntent)
                    Log.i(TAG, "MainActivity lansată via BMWAccessibilityService")
                    return
                } catch (e: Exception) {
                    Log.w(TAG, "Lansare via AccessibilityService a eșuat, fallback pe context", e)
                }
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

