package com.bmw.carlauncher

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.KeyguardManager
import android.os.PowerManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.telephony.TelephonyManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.app.role.RoleManager
import android.telecom.TelecomManager
import android.util.Log
import android.content.res.Configuration
import android.provider.MediaStore
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewParent
import android.view.WindowManager
import android.widget.AdapterView
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RelativeLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.speech.tts.TextToSpeech
import android.widget.ImageButton
import android.os.Environment
import com.bmw.carlauncher.notification.CarNotification
import com.bmw.carlauncher.notification.CarNotificationHub
import com.bmw.carlauncher.notification.CarNotificationListenerService
import com.bmw.carlauncher.notification.VoiceMessagePlayer

class MainActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var isCarConnectedState: Boolean = false
        @Volatile
        var lastDisconnectTimestamp: Long = 0L
        @Volatile
        var isInForeground: Boolean = false
        @Volatile
        var currentTaskId: Int = -1

        var instance: MainActivity? = null

        fun onPhoneRinging(context: Context, number: String?) {
            instance?.runOnUiThread {
                instance?.showIncomingCallUI(number, null)
            }
        }

        fun onPhoneOffhook(context: Context) {
            instance?.runOnUiThread {
                instance?.showActiveCallUI()
            }
        }

        fun onPhoneIdle(context: Context) {
            instance?.runOnUiThread {
                instance?.dismissCallUI()
            }
        }

        fun closeLauncher() {
            instance?.let { act ->
                act.runOnUiThread {
                    Log.i("MainActivity", "closeLauncher: Oprire muzică și închidere automată la deconectarea ESP32...")
                    // 1. Oprește radioul intern
                    try {
                        act.controller?.pause()
                        act.controller?.stop()
                    } catch (_: Exception) {}
                    // 2. Oprește YouTube Web dacă este activ
                    try {
                        if (act.isYouTubeWebMode && act.isYouTubeWebPlaying) {
                            act.youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
                            act.isYouTubeWebPlaying = false
                        }
                    } catch (_: Exception) {}
                    // 3. Pune pe pauză orice aplicație media externă conectată (Spotify, YouTube Music etc.)
                    try {
                        ExternalMediaManager.getActiveMusicController(act)?.transportControls?.pause()
                    } catch (_: Exception) {}
                    // 4. Oprește redarea mesajelor vocale și TTS
                    try {
                        VoiceMessagePlayer.stop()
                    } catch (_: Exception) {}
                    try {
                        act.textToSpeech?.stop()
                    } catch (_: Exception) {}

                    // 5. Închide launcherul complet
                    act.finishAndRemoveTask()
                }
            }
        }
    }

    data class FavoriteContact(
        val name: String,
        val number: String,
        val photo: Bitmap?
    )

    enum class CenterMode {
        BMW, MEDIA, RADIO, MAP, ENERGY, NOTIFICATION
    }

    private var currentMode = CenterMode.BMW
    private var isNightMode = true
    private var isFirstRadioConnect = true

    // YouTube Live Video in Center Circle
    private var isYouTubeWebMode = false
    private var isYouTubeWebPlaying = false
    private var isYouTubeDockedInCircle = false
    private var urlAtFullscreenOpen = ""
    private var youTubeWebTitle = ""
    private var youTubeWebArtist = ""
    private var youtubeWebView: WebView? = null
    private lateinit var youtubeCircleContainer: FrameLayout
    private lateinit var youtubeCircleTouchOverlay: View
    private lateinit var youtubeFullscreenOverlay: LinearLayout
    private lateinit var youtubeFullscreenContainer: FrameLayout
    private lateinit var btnYtBack: TextView
    private lateinit var btnYtDockCircle: TextView
    private lateinit var centerMediaScrim: View
    private lateinit var centerMediaInfoContainer: LinearLayout

    // Media Controller & Stations
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var stations = mutableListOf<RadioStation>()

    // Top Bar elements
    private lateinit var textClock: TextView

    // Bottom Bar elements
    private lateinit var btnPrevTrack: ImageView
    private lateinit var btnNextTrack: ImageView
    private lateinit var btnPlayPause: ImageView
    private var isCurrentlyPlayingState = false
    private lateinit var bottomTrackTitle: TextView
    private lateinit var bottomTrackArtist: TextView
    private lateinit var bottomBatteryIcon: TextView
    private var bottomGpsCount: TextView? = null
    private lateinit var btnVolumeDown: ImageView
    private lateinit var btnVolumeUp: ImageView
    private lateinit var btnNightMode: ImageView
    private lateinit var btnQuickSettings: ImageView

    // Agama Wings - Left column
    private lateinit var btnLeft1: FrameLayout
    private lateinit var btnLeft2: FrameLayout
    private lateinit var btnLeft3: FrameLayout

    // Agama Wings - Right column
    private lateinit var btnRight1: FrameLayout
    private lateinit var btnRight2: FrameLayout
    private lateinit var btnRight3: FrameLayout

    // Center Circle & Views
    private lateinit var centerCircleHalo: BmwCircleHaloView
    private lateinit var centerCircle: FrameLayout
    private lateinit var viewCenterBmw: FrameLayout
    private lateinit var viewCenterMedia: FrameLayout
    private lateinit var centerMediaThumbnail: ImageView
    private lateinit var centerMediaSource: TextView
    private lateinit var centerMediaTitle: TextView
    private lateinit var centerMediaArtist: TextView

    private lateinit var viewCenterMap: FrameLayout
    private lateinit var centerMapView: MapView
    private lateinit var mapSpeedText: TextView
    private lateinit var btnMapRecenter: TextView
    private var mapLibreMap: MapLibreMap? = null
    private var isMapTrackingGps = true
    private var lastCameraAnimTime = 0L
    private var lastSpeedBucket = -1
    private var lastKnownLocation: Location? = null

    private lateinit var viewCenterRadio: LinearLayout
    private lateinit var centerRadioArt: ImageView
    private lateinit var centerRadioTitle: TextView
    private lateinit var centerRadioSub: TextView
    private lateinit var centerRadioStatus: TextView

    private lateinit var viewCenterWaze: LinearLayout
    private lateinit var centerWazeIcon: TextView
    private lateinit var centerWazeDistance: TextView
    private lateinit var centerWazeRoad: TextView
    private lateinit var centerWazeEta: TextView

    private lateinit var viewCenterSpeedo: LinearLayout
    private lateinit var centerSpeedValue: TextView
    private lateinit var centerSpeedUnit: TextView
    private lateinit var centerSpeedSub: TextView

    // Solar & Energy Center View UI
    private lateinit var viewCenterEnergy: RelativeLayout
    private lateinit var tvEnergySolarPower: TextView
    private lateinit var tvEnergyHouseLoad: TextView
    private lateinit var tvEnergyBatterySoc: TextView
    private lateinit var tvEnergyBatteryStatus: TextView
    private lateinit var tvEnergyBatteryVoltage: TextView
    private lateinit var tvEnergyBatteryPower: TextView
    private val energyHandler = Handler(Looper.getMainLooper())
    private val energyPollRunnable = object : Runnable {
        override fun run() {
            if (isInForeground && currentMode == CenterMode.ENERGY) {
                fetchAndDisplayEnergyData()
                energyHandler.postDelayed(this, 5000L)
            }
        }
    }

    // Car Notification Center View UI
    private lateinit var viewCenterNotification: RelativeLayout
    private lateinit var ivNotifAppIcon: ImageView
    private lateinit var tvNotifAppName: TextView
    private lateinit var tvNotifTime: TextView
    private lateinit var btnNotifClose: ImageButton
    private lateinit var tvNotifSender: TextView
    private lateinit var layoutNotifVoiceBadge: LinearLayout
    private lateinit var tvNotifVoiceLabel: TextView
    private lateinit var tvNotifText: TextView
    private lateinit var btnNotifAction: LinearLayout
    private lateinit var ivNotifActionIcon: ImageView
    private lateinit var tvNotifActionText: TextView
    private lateinit var btnNotifSpeak: LinearLayout
    private var tvNotifCounter: TextView? = null
    private var notifGestureDetector: GestureDetector? = null
    private var isNotifSwipeHandled = false
    private var activeNotification: CarNotification? = null
    private var preNotificationMode: CenterMode = CenterMode.BMW
    private val notifDismissHandler = Handler(Looper.getMainLooper())
    private val notifDismissRunnable = Runnable { dismissNotification() }
    private var textToSpeech: TextToSpeech? = null
    private var wasRadioPlayingBeforeVoice: Boolean = false
    private var wasYouTubePlayingBeforeVoice: Boolean = false
    private var wasExternalMediaPlayingBeforeVoice: Boolean = false

    // Favorites Overlay UI
    private lateinit var favoritesOverlayContainer: FrameLayout
    private lateinit var btnCloseFavorites: ImageView
    private lateinit var btnOpenDialer: TextView
    private lateinit var listFavoriteContacts: ListView
    private lateinit var tvEmptyFavorites: TextView

    // Radio Overlay UI
    private lateinit var radioOverlayContainer: FrameLayout
    private lateinit var btnCloseRadio: ImageView
    private lateinit var btnAddStation: TextView
    private lateinit var listRadioStations: ListView

    // Music Apps Overlay UI
    private lateinit var musicOverlayContainer: FrameLayout
    private lateinit var btnCloseMusic: ImageView
    private lateinit var listMusicApps: ListView

    // All Apps Overlay UI
    private lateinit var appsOverlayContainer: FrameLayout
    private lateinit var btnCloseApps: ImageView
    private lateinit var gridAllApps: GridView

    // Settings Overlay UI
    private lateinit var settingsOverlayContainer: FrameLayout
    private lateinit var btnCloseSettings: ImageView
    private lateinit var seekDayBrightness: SeekBar
    private lateinit var seekNightBrightness: SeekBar
    private lateinit var tvDayBrightnessVal: TextView
    private lateinit var tvNightBrightnessVal: TextView
    private lateinit var btnOpenNotifSettings: LinearLayout
    private lateinit var tvNotifAccessStatus: TextView
    private lateinit var btnCarBtSelect: LinearLayout
    private lateinit var tvCarBtStatus: TextView
    private lateinit var btnToggleForceDisplayMode: LinearLayout
    private lateinit var tvDisplayModeStatus: TextView
    private lateinit var tvDisplayModeBadge: TextView
    private var tvNavSourceLabel: TextView? = null
    private var centerNavIconImg: ImageView? = null

    // Bluetooth Car Detection & Orientation
    private var a2dpProxy: BluetoothA2dp? = null
    private var headsetProxy: BluetoothHeadset? = null
    private var portraitGestureDetector: GestureDetector? = null

    fun isPortraitOrientation(): Boolean {
        return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    }

    private val requestBtConnectPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.i("MainActivity", "Permisiune BLUETOOTH_CONNECT acordata de utilizator")
            initBluetoothProfiles()
            checkBluetoothConnectionAndUpdateOrientation()
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            Log.i("MainActivity", "Bluetooth broadcast event: $action")

            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val devAddress = device?.address ?: ""
            val devName = try { device?.name } catch (_: SecurityException) { null } ?: ""

            val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            val savedCarAddress = prefs.getString("car_bt_address", null)
            val isCarDevice = if (!savedCarAddress.isNullOrEmpty()) {
                devAddress.equals(savedCarAddress, ignoreCase = true)
            } else {
                devName.contains("BMW", ignoreCase = true) ||
                devName.contains("iDrive", ignoreCase = true) ||
                devName.contains("ESP32", ignoreCase = true)
            }

            when (action) {
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    if (isCarDevice && devAddress.isNotEmpty()) {
                        Log.i("MainActivity", "Car device ACL disconnected: $devName ($devAddress)")
                        prefs.edit().putString("force_display_mode", "auto").apply()
                        isCarConnectedState = false
                        lastDisconnectTimestamp = android.os.SystemClock.elapsedRealtime()
                        checkBluetoothConnectionAndUpdateOrientation()
                        lockScreenIfPossible()
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (isCarDevice && state == BluetoothProfile.STATE_CONNECTED) {
                        Log.i("MainActivity", "Car device A2DP connected: $devName")
                        prefs.edit().putString("force_display_mode", "auto").apply()
                        lastDisconnectTimestamp = 0L
                        isCarConnectedState = true
                        checkBluetoothConnectionAndUpdateOrientation()
                    }
                }
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (isCarDevice && state == BluetoothProfile.STATE_CONNECTED) {
                        Log.i("MainActivity", "Car device Headset connected: $devName")
                        prefs.edit().putString("force_display_mode", "auto").apply()
                        lastDisconnectTimestamp = 0L
                        isCarConnectedState = true
                        checkBluetoothConnectionAndUpdateOrientation()
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    if (isCarDevice && devAddress.isNotEmpty()) {
                        Log.i("MainActivity", "Car device ACL connected: $devName ($devAddress)")
                        prefs.edit().putString("force_display_mode", "auto").apply()
                        lastDisconnectTimestamp = 0L
                        isCarConnectedState = true
                        checkBluetoothConnectionAndUpdateOrientation()
                    }
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                        Log.i("MainActivity", "Bluetooth adapter turned off")
                        isCarConnectedState = false
                        lastDisconnectTimestamp = android.os.SystemClock.elapsedRealtime()
                        checkBluetoothConnectionAndUpdateOrientation()
                        lockScreenIfPossible()
                    }
                }
            }
        }
    }

    // Incoming / In-Call Overlay UI
    private lateinit var callOverlayContainer: FrameLayout
    private lateinit var callPhotoFrame: FrameLayout
    private lateinit var callStatusTitle: TextView
    private lateinit var callDurationTimer: TextView
    private lateinit var callContactPhoto: ImageView
    private lateinit var callContactName: TextView
    private lateinit var callPhoneNumber: TextView
    private lateinit var callRingingActions: LinearLayout
    private lateinit var btnCallDeny: LinearLayout
    private lateinit var btnCallAnswer: LinearLayout
    private lateinit var btnCallHangup: LinearLayout

    private val circleOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val size = minOf(view.width, view.height)
            if (size > 0) {
                val left = (view.width - size) / 2
                val top = (view.height - size) / 2
                outline.setOval(left, top, left + size, top + size)
            } else {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    private var callDurationSeconds = 0
    private val callTimerHandler = Handler(Looper.getMainLooper())
    private val callTimerRunnable = object : Runnable {
        override fun run() {
            callDurationSeconds++
            val mins = callDurationSeconds / 60
            val secs = callDurationSeconds % 60
            callDurationTimer.text = String.format("%02d:%02d", mins, secs)
            callTimerHandler.postDelayed(this, 1000)
        }
    }

    // Clock updater
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            clockHandler.postDelayed(this, 1000)
        }
    }

    // Battery Monitor
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                val pct = (level * 100) / scale
                val prefix = if (isCharging) "⚡ " else "🔋 "
                bottomBatteryIcon.text = "$prefix$pct%"
            }
        }
    }

    // GPS Speedometer & 3D Camera Follower
    private var locationManager: LocationManager? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            val speedInt = Math.round(speedKmh).coerceAtLeast(0)
            centerSpeedValue.text = "$speedInt"
            centerSpeedSub.text = "GPS ACTIV • ±${location.accuracy.toInt()}m"
            bottomGpsCount?.text = "⌖ LOCK"

            // Actualizare badge viteza pe harta
            mapSpeedText.text = "$speedInt"

            // Actualizare camera 3D MapLibre cand este activa urmarirea GPS
            val isInitialZoomNeeded = (mapLibreMap?.cameraPosition?.zoom ?: 0.0) < 5.0
            updateMapLibreCamera(location, speedKmh, force = isInitialZoomNeeded)
        }
        override fun onProviderEnabled(provider: String) {
            centerSpeedSub.text = "GPS CONECTAT"
            bottomGpsCount?.text = "⌖ CON"
        }
        override fun onProviderDisabled(provider: String) {
            centerSpeedSub.text = "GPS OPRIT"
            bottomGpsCount?.text = "⌖ NO"
        }
    }

    private lateinit var tvSatelliteCount: TextView
    private var lastGnssTotal = 0
    private var lastGnssUsed = 0

    private val gnssStatusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            lastGnssTotal = status.satelliteCount
            var used = 0
            for (i in 0 until status.satelliteCount) {
                if (status.usedInFix(i)) {
                    used++
                }
            }
            lastGnssUsed = used
            if (::tvSatelliteCount.isInitialized) {
                tvSatelliteCount.text = "$lastGnssTotal ($lastGnssUsed)"
            }
        }

        override fun onStopped() {
            super.onStopped()
            lastGnssTotal = 0
            lastGnssUsed = 0
            if (::tvSatelliteCount.isInitialized) {
                tvSatelliteCount.text = "0 (0)"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initializare MapLibre Native SDK inainte de setContentView
        MapLibre.getInstance(this)

        if (intent.getBooleanExtra("EXTRA_CAR_BT_CONNECTED", false)) {
            isCarConnectedState = true
        }

        checkAndRequestBluetoothPermissions()
        initBluetoothProfiles()
        applyTargetOrientation()

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        isNightMode = prefs.getBoolean("night_mode", true)

        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        initViews(savedInstanceState)
        setNightMode(isNightMode, showToast = false)
        setupListeners()
        if (isPortraitOrientation()) {
            stopAllAudioPlayers()
        }
        updateCenterModeUI()
        refreshStations()

        instance = this
        if (intent.getBooleanExtra("EXTRA_AUTO_CLOSE", false)) {
            closeLauncher()
            return
        }
        if (intent.getBooleanExtra("EXTRA_CAR_BT_CONNECTED", false)) {
            startRadioPlayback()
        }
        checkAndRequestPhonePermissions()
        setupCallServiceListener()
        handleIncomingCallIntent(intent)
    }

    private fun enableImmersiveMode() {
        // În modul Mașină (Landscape) dacă ecranul este activ, îl ținem deschis pe durata utilizării bordului.
        // În modul Portret (Telefon) ecranul se oprește/blochează conform setărilor de sistem (screen timeout).
        if (isPortraitOrientation() || determineTargetOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            clearShowWhenLocked()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Permite desenarea bordului pe 100% din ecran fizic (inclusiv in jurul camerei de selfie, eliminand banda neagra)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        // Ecran complet imersiv (fara bara de notificari si bara de navigare)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enableImmersiveMode()
            focusPlayPauseButton()
        }
    }

    private fun initViews(savedInstanceState: Bundle?) {
        // Top Status Pill
        textClock = findViewById(R.id.text_clock)
        tvSatelliteCount = findViewById(R.id.tv_satellite_count)
        findViewById<View>(R.id.container_gps_satellites)?.setOnClickListener {
            val msg = if (lastGnssTotal > 0) {
                "Sateliți GNSS în vizor: $lastGnssTotal ($lastGnssUsed folosiți pentru fix)"
            } else {
                "Căutare sateliți GPS..."
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Bottom Bar
        btnPrevTrack = findViewById(R.id.btn_prev_track)
        btnNextTrack = findViewById(R.id.btn_next_track)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        bottomTrackTitle = findViewById(R.id.bottom_track_title)
        bottomTrackTitle.isSelected = true
        bottomTrackArtist = findViewById(R.id.bottom_track_artist)
        bottomBatteryIcon = findViewById(R.id.bottom_battery_icon)
        btnVolumeDown = findViewById(R.id.btn_volume_down)
        btnVolumeUp = findViewById(R.id.btn_volume_up)
        btnNightMode = findViewById(R.id.btn_night_mode)
        btnQuickSettings = findViewById(R.id.btn_quick_settings)

        // Wings Stanga
        btnLeft1 = findViewById(R.id.btn_left_1)
        btnLeft2 = findViewById(R.id.btn_left_2)
        btnLeft3 = findViewById(R.id.btn_left_3)

        // Wings Dreapta
        btnRight1 = findViewById(R.id.btn_right_1)
        btnRight2 = findViewById(R.id.btn_right_2)
        btnRight3 = findViewById(R.id.btn_right_3)

        // Center
        centerCircleHalo = findViewById(R.id.center_circle_halo)
        centerCircle = findViewById(R.id.center_circle)
        centerCircle.setOnFocusChangeListener { _, hasFocus ->
            centerCircleHalo.setGlowActive(hasFocus)
        }
        viewCenterBmw = findViewById(R.id.view_center_bmw)

        viewCenterMedia = findViewById(R.id.view_center_media)
        viewCenterMedia.outlineProvider = circleOutlineProvider
        viewCenterMedia.clipToOutline = true

        youtubeCircleContainer = findViewById(R.id.youtube_circle_container)
        youtubeCircleContainer.outlineProvider = circleOutlineProvider
        youtubeCircleContainer.clipToOutline = true
        youtubeCircleTouchOverlay = findViewById(R.id.youtube_circle_touch_overlay)

        youtubeFullscreenOverlay = findViewById(R.id.youtube_fullscreen_overlay)
        youtubeFullscreenContainer = findViewById(R.id.youtube_fullscreen_container)
        btnYtBack = findViewById(R.id.btn_yt_back)
        btnYtDockCircle = findViewById(R.id.btn_yt_dock_circle)

        centerMediaThumbnail = findViewById(R.id.center_media_thumbnail)
        centerMediaScrim = findViewById(R.id.center_media_scrim)
        centerMediaInfoContainer = findViewById(R.id.center_media_info_container)
        centerMediaSource = findViewById(R.id.center_media_source)
        centerMediaTitle = findViewById(R.id.center_media_title)
        centerMediaArtist = findViewById(R.id.center_media_artist)

        initYouTubeWebView()

        // Harta Rotunda Live (MapLibre 3D Vector)
        viewCenterMap = findViewById(R.id.view_center_map)
        viewCenterMap.outlineProvider = circleOutlineProvider
        viewCenterMap.clipToOutline = true
        centerMapView = findViewById(R.id.center_map_view)
        mapSpeedText = findViewById(R.id.map_speed_text)
        btnMapRecenter = findViewById(R.id.btn_map_recenter)
        initCenterMapView(savedInstanceState)

        viewCenterRadio = findViewById(R.id.view_center_radio)
        viewCenterRadio.outlineProvider = circleOutlineProvider
        viewCenterRadio.clipToOutline = true
        centerRadioArt = findViewById(R.id.center_radio_art)
        centerRadioTitle = findViewById(R.id.center_radio_title)
        centerRadioSub = findViewById(R.id.center_radio_sub)
        centerRadioStatus = findViewById(R.id.center_radio_status)

        viewCenterWaze = findViewById(R.id.view_center_waze)
        centerWazeIcon = findViewById(R.id.center_waze_icon)
        centerWazeDistance = findViewById(R.id.center_waze_distance)
        centerWazeRoad = findViewById(R.id.center_waze_road)
        centerWazeEta = findViewById(R.id.center_waze_eta)

        viewCenterSpeedo = findViewById(R.id.view_center_speedo)
        centerSpeedValue = findViewById(R.id.center_speed_value)
        centerSpeedUnit = findViewById(R.id.center_speed_unit)
        centerSpeedSub = findViewById(R.id.center_speed_sub)

        // Solar & Energy View
        viewCenterEnergy = findViewById(R.id.view_center_energy)
        viewCenterEnergy.outlineProvider = circleOutlineProvider
        viewCenterEnergy.clipToOutline = true
        tvEnergySolarPower = findViewById(R.id.tv_energy_solar_power)
        tvEnergyHouseLoad = findViewById(R.id.tv_energy_house_load)
        tvEnergyBatterySoc = findViewById(R.id.tv_energy_battery_soc)
        tvEnergyBatteryStatus = findViewById(R.id.tv_energy_battery_status)
        tvEnergyBatteryVoltage = findViewById(R.id.tv_energy_battery_voltage)
        tvEnergyBatteryPower = findViewById(R.id.tv_energy_battery_power)

        // Notification View
        viewCenterNotification = findViewById(R.id.view_center_notification)
        viewCenterNotification.outlineProvider = circleOutlineProvider
        viewCenterNotification.clipToOutline = true
        ivNotifAppIcon = findViewById(R.id.iv_notif_app_icon)
        tvNotifAppName = findViewById(R.id.tv_notif_app_name)
        tvNotifTime = findViewById(R.id.tv_notif_time)
        tvNotifCounter = findViewById(R.id.tv_notif_counter)
        btnNotifClose = findViewById(R.id.btn_notif_close)
        tvNotifSender = findViewById(R.id.tv_notif_sender)
        layoutNotifVoiceBadge = findViewById(R.id.layout_notif_voice_badge)
        tvNotifVoiceLabel = findViewById(R.id.tv_notif_voice_label)
        tvNotifText = findViewById(R.id.tv_notif_text)
        btnNotifAction = findViewById(R.id.btn_notif_action)
        ivNotifActionIcon = findViewById(R.id.iv_notif_action_icon)
        tvNotifActionText = findViewById(R.id.tv_notif_action_text)
        btnNotifSpeak = findViewById(R.id.btn_notif_speak)

        setupNotifGestureDetector()

        btnNotifClose.isFocusable = true
        btnNotifClose.isFocusableInTouchMode = true
        btnNotifClose.enableInstantClickOnTouch()
        btnNotifClose.setOnClickListener {
            dismissNotification()
        }

        btnNotifAction.isFocusable = true
        btnNotifAction.isFocusableInTouchMode = true
        btnNotifAction.isClickable = true
        btnNotifAction.enableInstantClickOnTouch()

        btnNotifSpeak.isFocusable = true
        btnNotifSpeak.isFocusableInTouchMode = true
        btnNotifSpeak.isClickable = true
        btnNotifSpeak.enableInstantClickOnTouch()

        btnNotifAction.setOnClickListener {
            val notif = activeNotification ?: return@setOnClickListener
            Log.i("MainActivity", "btnNotifAction apasat! isVoice=${notif.isVoiceMessage}, pkg=${notif.packageName}")
            if (notif.isVoiceMessage) {
                if (VoiceMessagePlayer.isPlaying()) {
                    VoiceMessagePlayer.pause()
                } else if (VoiceMessagePlayer.getCurrentFile() != null) {
                    VoiceMessagePlayer.resume()
                } else {
                    notifDismissHandler.removeCallbacks(notifDismissRunnable)
                    playVoiceMessageOrFallback(notif)
                }
            } else {
                notifDismissHandler.removeCallbacks(notifDismissRunnable)
                openNotification(notif)
            }
        }

        btnNotifSpeak.setOnClickListener {
            val notif = activeNotification ?: return@setOnClickListener
            Log.i("MainActivity", "btnNotifSpeak apasat pentru pkg=${notif.packageName}")
            notifDismissHandler.removeCallbacks(notifDismissRunnable)
            speakNotification(notif)
        }

        VoiceMessagePlayer.init(this)
        VoiceMessagePlayer.listener = object : VoiceMessagePlayer.PlaybackListener {
            override fun onPlaybackStarted() {
                runOnUiThread {
                    notifDismissHandler.removeCallbacks(notifDismissRunnable)
                    tvNotifActionText.text = "PAUZĂ"
                    ivNotifActionIcon.setImageResource(R.drawable.ic_media_pause)
                    pauseOtherPlayersForVoiceMessage()
                }
            }

            override fun onPlaybackPaused() {
                runOnUiThread {
                    tvNotifActionText.text = "REIA"
                    ivNotifActionIcon.setImageResource(R.drawable.ic_media_play)
                }
            }

            override fun onPlaybackCompleted() {
                runOnUiThread {
                    tvNotifActionText.text = "REDARE"
                    ivNotifActionIcon.setImageResource(R.drawable.ic_media_play)
                    resumeOtherPlayersAfterVoiceMessage()
                    resetNotifDismissTimer()
                }
            }

            override fun onPlaybackError(error: String) {
                runOnUiThread {
                    tvNotifActionText.text = "REDARE"
                    ivNotifActionIcon.setImageResource(R.drawable.ic_media_play)
                    resumeOtherPlayersAfterVoiceMessage()
                    resetNotifDismissTimer()
                    val notif = activeNotification
                    if (notif != null) {
                        openNotification(notif)
                    }
                }
            }
        }

        // Initializare Text to Speech
        initTextToSpeech()

        // Abonare la evenimentele de notificare
        CarNotificationHub.onNotificationReceived = { notif ->
            displayNotification(notif)
            if (isPortraitOrientation()) {
                updateNetworkStatus()
            }
        }
        CarNotificationHub.onUnreadCountChanged = { _ ->
            if (isPortraitOrientation()) {
                updateNetworkStatus()
            }
        }
        CarNotificationHub.onNotificationsListChanged = { _ ->
            checkAndShowPendingNotifications()
        }
        if (CarNotificationListenerService.isNotificationAccessGranted(this)) {
            CarNotificationListenerService.rebindService(this)
        }
        handleNotificationTestIntent(intent)

        // Inițializare Fereastră Contacte Favorite (Half-Screen Overlay)
        favoritesOverlayContainer = findViewById(R.id.favorites_overlay_container)
        btnCloseFavorites = findViewById(R.id.btn_close_favorites)
        btnOpenDialer = findViewById(R.id.btn_open_dialer)
        listFavoriteContacts = findViewById(R.id.list_favorite_contacts)
        listFavoriteContacts.itemsCanFocus = true
        tvEmptyFavorites = findViewById(R.id.tv_empty_favorites)

        btnCloseFavorites.setOnClickListener {
            favoritesOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        favoritesOverlayContainer.setOnClickListener {
            favoritesOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        btnOpenDialer.setOnClickListener {
            favoritesOverlayContainer.visibility = View.GONE
            launchPhoneDialer()
            focusPlayPauseButton()
        }

        // Inițializare Fereastră Posturi Radio
        radioOverlayContainer = findViewById(R.id.radio_overlay_container)
        btnCloseRadio = findViewById(R.id.btn_close_radio)
        btnAddStation = findViewById(R.id.btn_add_station)
        listRadioStations = findViewById(R.id.list_radio_stations)
        listRadioStations.itemsCanFocus = true
        btnCloseRadio.setOnClickListener {
            radioOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        radioOverlayContainer.setOnClickListener {
            radioOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        btnAddStation.setOnClickListener {
            radioOverlayContainer.visibility = View.GONE
            showAddStationDialog()
            focusPlayPauseButton()
        }

        // Inițializare Fereastră Aplicații Muzică
        musicOverlayContainer = findViewById(R.id.music_overlay_container)
        btnCloseMusic = findViewById(R.id.btn_close_music)
        listMusicApps = findViewById(R.id.list_music_apps)
        listMusicApps.itemsCanFocus = true
        btnCloseMusic.setOnClickListener {
            musicOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        musicOverlayContainer.setOnClickListener {
            musicOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }

        // Inițializare Fereastră Toate Aplicațiile
        appsOverlayContainer = findViewById(R.id.apps_overlay_container)
        btnCloseApps = findViewById(R.id.btn_close_apps)
        gridAllApps = findViewById(R.id.grid_all_apps)
        btnCloseApps.setOnClickListener {
            appsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        appsOverlayContainer.setOnClickListener {
            appsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }

        // Inițializare Fereastră Setări Aplicație
        settingsOverlayContainer = findViewById(R.id.settings_overlay_container)
        btnCloseSettings = findViewById(R.id.btn_close_settings)
        seekDayBrightness = findViewById(R.id.seek_day_brightness)
        seekNightBrightness = findViewById(R.id.seek_night_brightness)
        tvDayBrightnessVal = findViewById(R.id.tv_day_brightness_val)
        tvNightBrightnessVal = findViewById(R.id.tv_night_brightness_val)
        btnOpenNotifSettings = findViewById(R.id.btn_open_notif_settings)
        tvNotifAccessStatus = findViewById(R.id.tv_notif_access_status)
        btnCarBtSelect = findViewById(R.id.btn_car_bt_select)
        tvCarBtStatus = findViewById(R.id.tv_car_bt_status)
        btnToggleForceDisplayMode = findViewById(R.id.btn_toggle_force_display_mode)
        tvDisplayModeStatus = findViewById(R.id.tv_display_mode_status)
        tvDisplayModeBadge = findViewById(R.id.tv_display_mode_badge)

        tvNavSourceLabel = findViewById(R.id.tv_nav_source_label)
        centerNavIconImg = findViewById(R.id.center_nav_icon_img)

        initSettingsOverlay()

        // Inițializare Fereastră Apel Telefonic (Half-Screen Overlay)
        callOverlayContainer = findViewById(R.id.call_overlay_container)
        callPhotoFrame = findViewById(R.id.call_photo_frame)
        callPhotoFrame.outlineProvider = circleOutlineProvider
        callPhotoFrame.clipToOutline = true

        callStatusTitle = findViewById(R.id.call_status_title)
        callDurationTimer = findViewById(R.id.call_duration_timer)
        callContactPhoto = findViewById(R.id.call_contact_photo)
        callContactPhoto.outlineProvider = circleOutlineProvider
        callContactPhoto.clipToOutline = true
        callContactName = findViewById(R.id.call_contact_name)
        callPhoneNumber = findViewById(R.id.call_phone_number)
        callRingingActions = findViewById(R.id.call_ringing_actions)
        btnCallDeny = findViewById(R.id.btn_call_deny)
        btnCallAnswer = findViewById(R.id.btn_call_answer)
        btnCallHangup = findViewById(R.id.btn_call_hangup)

        btnCallAnswer.setOnClickListener {
            if (!BMWInCallService.answerCurrentCall()) {
                answerPhoneCall()
            }
            showActiveCallUI()
        }

        btnCallDeny.setOnClickListener {
            if (!BMWInCallService.rejectCurrentCall()) {
                hangupPhoneCall()
            }
            dismissCallUI()
            focusPlayPauseButton()
        }

        btnCallHangup.setOnClickListener {
            if (!BMWInCallService.hangupCurrentCall()) {
                hangupPhoneCall()
            }
            dismissCallUI()
            focusPlayPauseButton()
        }

        // Configurare focusare pentru navigare TAB / tastatură / iDrive și clic instant tactil
        focusOrderViews.forEach { view ->
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.enableInstantClickOnTouch()
        }
        viewCenterBmw.enableInstantClickOnTouch()
        viewCenterRadio.enableInstantClickOnTouch()
        viewCenterMedia.enableInstantClickOnTouch()
        youtubeCircleTouchOverlay.enableInstantClickOnTouch()
        viewCenterMap.enableInstantClickOnTouch()
        viewCenterEnergy.enableInstantClickOnTouch()

        // Butoane din ferestrele auxiliare - clic instant la prima atingere
        val overlayButtons = listOf(
            btnCloseFavorites, btnOpenDialer,
            btnCloseRadio, btnAddStation,
            btnCloseMusic, btnCloseApps,
            btnCloseSettings,
            btnCallAnswer, btnCallDeny, btnCallHangup
        )
        overlayButtons.forEach { btn ->
            btn.isFocusable = true
            btn.isFocusableInTouchMode = false
            btn.enableInstantClickOnTouch()
        }

        if (isPortraitOrientation()) {
            setupPortraitMode()
        }

        focusPlayPauseButton()
    }

    private fun initCenterMapView(savedInstanceState: Bundle?) {
        // Harta 3D MapLibre este anulata si inlocuita cu notificarile de navigatie Google Maps
        return
    }

    private fun setupPortraitMode() {
        // 1. Top status pill clic lung -> Deschide Setari
        val topStatusPill = findViewById<View>(R.id.top_status_pill)
        topStatusPill?.isLongClickable = true
        topStatusPill?.setOnLongClickListener {
            showSettingsOverlay()
            true
        }

        // 2. Cercul central clic lung -> Deschide Setari
        centerCircle.isLongClickable = true
        centerCircle.setOnLongClickListener {
            showSettingsOverlay()
            true
        }
        viewCenterBmw.isLongClickable = true
        viewCenterBmw.setOnLongClickListener {
            showSettingsOverlay()
            true
        }

        // 3. Indicator discret permisiune notificari
        findViewById<View>(R.id.tv_portrait_notif_perm_indicator)?.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {}
        }

        // 4. Buton Cancel / Inchide notificare in cercul din modul Portret
        findViewById<View>(R.id.btn_notif_close_wrapper)?.setOnClickListener {
            dismissNotification()
        }

        // 4b. Buton / Pill notificari din bara de sus deschide panoul de notificari al telefonului
        findViewById<View>(R.id.port_notif_container)?.setOnClickListener {
            openNotificationShade()
        }

        // 5. Grila de 8 aplicatii (Click transparent peste grafica de fundal cu ripple)
        findViewById<View>(R.id.btn_port_gallery)?.setOnClickListener { launchGalleryApp() }
        findViewById<View>(R.id.btn_port_facebook)?.setOnClickListener { launchFacebookApp() }
        findViewById<View>(R.id.btn_port_play_store)?.setOnClickListener { launchPlayStoreApp() }
        findViewById<View>(R.id.btn_port_files)?.setOnClickListener { launchFilesApp() }
        findViewById<View>(R.id.btn_port_home_assistant)?.setOnClickListener { launchHomeAssistantApp() }
        findViewById<View>(R.id.btn_port_notes)?.setOnClickListener { launchNotesApp() }
        findViewById<View>(R.id.btn_port_wallet)?.setOnClickListener { launchWalletApp() }
        findViewById<View>(R.id.btn_port_chrome)?.setOnClickListener { launchChromeApp() }

        // 6. Dock-ul de jos (3 butoane principale)
        findViewById<View>(R.id.btn_port_phone)?.setOnClickListener { launchPhoneDialer() }
        findViewById<View>(R.id.btn_port_whatsapp)?.setOnClickListener { launchWhatsApp() }
        findViewById<View>(R.id.btn_port_camera)?.setOnClickListener { launchCameraApp() }

        val portraitInteractiveViews = listOfNotNull(
            topStatusPill,
            findViewById<View>(R.id.port_notif_container),
            findViewById<View>(R.id.btn_port_gallery),
            findViewById<View>(R.id.btn_port_facebook),
            findViewById<View>(R.id.btn_port_play_store),
            findViewById<View>(R.id.btn_port_files),
            findViewById<View>(R.id.btn_port_home_assistant),
            findViewById<View>(R.id.btn_port_notes),
            findViewById<View>(R.id.btn_port_wallet),
            findViewById<View>(R.id.btn_port_chrome),
            findViewById<View>(R.id.btn_port_phone),
            findViewById<View>(R.id.btn_port_whatsapp),
            findViewById<View>(R.id.btn_port_camera),
            findViewById<View>(R.id.btn_notif_close_wrapper),
            findViewById<View>(R.id.tv_portrait_notif_perm_indicator)
        )
        portraitInteractiveViews.forEach { view ->
            view.isFocusable = true
            view.enableInstantClickOnTouch()
        }

        setupPortraitGestures()
    }

    private fun setupPortraitGestures() {
        portraitGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                if (::appsOverlayContainer.isInitialized && appsOverlayContainer.visibility == View.VISIBLE) return false
                if (::settingsOverlayContainer.isInitialized && settingsOverlayContainer.visibility == View.VISIBLE) return false
                if (::favoritesOverlayContainer.isInitialized && favoritesOverlayContainer.visibility == View.VISIBLE) return false
                if (::radioOverlayContainer.isInitialized && radioOverlayContainer.visibility == View.VISIBLE) return false
                if (::musicOverlayContainer.isInitialized && musicOverlayContainer.visibility == View.VISIBLE) return false
                if (::callOverlayContainer.isInitialized && callOverlayContainer.visibility == View.VISIBLE) return false

                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                if (Math.abs(diffY) > Math.abs(diffX) && Math.abs(diffY) > 100 && Math.abs(velocityY) > 150) {
                    if (diffY < 0) {
                        // Swipe Up -> Sertar aplicatii
                        openAllApps()
                        return true
                    } else {
                        // Swipe Down -> Bara notificari
                        expandStatusBar()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun expandStatusBar() {
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager = Class.forName("android.app.StatusBarManager")
            val expandMethod = statusBarManager.getMethod("expandNotificationsPanel")
            expandMethod.invoke(statusBarService)
        } catch (e: Exception) {
            Log.e("MainActivity", "expandStatusBar failed: ${e.message}")
        }
    }

    private fun launchGalleryApp() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_GALLERY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (intent.resolveActivity(packageManager) != null) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        val packages = listOf(
            "com.sec.android.gallery3d",
            "com.google.android.apps.photos",
            "com.android.gallery3d",
            "com.miui.gallery"
        )
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (_: Exception) {}
            }
        }
        try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(viewIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Aplicație Galerie indisponibilă", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFacebookApp() {
        val pkg = "com.facebook.katana"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage("com.facebook.lite")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                return
            } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Facebook nu este instalat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPlayStoreApp() {
        val pkg = "com.android.vending"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
                return
            } catch (_: Exception) {}
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Google Play Store indisponibil", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchFilesApp() {
        val packages = listOf(
            "com.sec.android.app.myfiles",
            "com.google.android.apps.nbu.files",
            "com.android.documentsui",
            "com.mi.android.globalFileexplorer"
        )
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (_: Exception) {}
            }
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_FILES)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (intent.resolveActivity(packageManager) != null) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {}
        }
        try {
            val docIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(docIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Aplicație Fișiere indisponibilă", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchHomeAssistantApp() {
        val pkg = "io.homeassistant.companion.android"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage("io.homeassistant.companion.android.minimal")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: Exception) {}
        } else {
            Toast.makeText(this, "Home Assistant nu este instalat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchNotesApp() {
        val packages = listOf(
            "com.samsung.android.app.notes",
            "com.google.android.keep",
            "com.miui.notes",
            "com.microsoft.notes"
        )
        for (pkg in packages) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (_: Exception) {}
            }
        }
        try {
            val intent = Intent(Intent.ACTION_CREATE_NOTE).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
                return
            }
        } catch (_: Exception) {}
        Toast.makeText(this, "Aplicație de Note indisponibilă", Toast.LENGTH_SHORT).show()
    }

    private fun launchWalletApp() {
        val pkg = "com.google.android.apps.walletnfcrel"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            ?: packageManager.getLaunchIntentForPackage("com.google.android.apps.walletnfcrel.staging")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: Exception) {}
        } else {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } catch (e: Exception) {
                Toast.makeText(this, "Google Wallet nu este instalat", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchChromeApp() {
        val pkg = "com.android.chrome"
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (_: Exception) {}
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Google Chrome indisponibil", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchCameraApp() {
        try {
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Camera foto indisponibilă", Toast.LENGTH_SHORT).show()
        }
    }

    private fun enableLocationComponent(style: Style) {
        val map = mapLibreMap ?: return
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            val locationComponent = map.locationComponent
            val activationOptions = LocationComponentActivationOptions.builder(this, style)
                .useDefaultLocationEngine(true)
                .build()
            locationComponent.activateLocationComponent(activationOptions)
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.NONE
            locationComponent.renderMode = RenderMode.COMPASS

            lastKnownLocation?.let { loc ->
                locationComponent.forceLocationUpdate(loc)
            }
        } catch (_: Exception) {}
    }

    private fun updateMapLibreCamera(location: Location, speedKmh: Float, force: Boolean = false) {
        val map = mapLibreMap ?: return
        if (currentMode != CenterMode.MAP && !force) return
        if (!isMapTrackingGps && !force) return

        val now = System.currentTimeMillis()
        // Stabilire viteza (bucket) pentru zoom si tilt 3D:
        // 0: sub 25 km/h -> zoom 18.0, tilt 35.0 (giratorii, manevre)
        // 1: 25 - 60 km/h -> zoom 16.5, tilt 52.0 (perspectiva 3D strada oras)
        // 2: peste 60 km/h -> zoom 15.0, tilt 60.0 (drum intins orizont)
        val bucket = when {
            speedKmh < 25f -> 0
            speedKmh < 60f -> 1
            else -> 2
        }

        val debounceExpired = (now - lastCameraAnimTime > 2500L)
        if (!debounceExpired && bucket != lastSpeedBucket && !force) {
            // Nu oscilam brusc camera daca inca nu a trecut intervalul de debounce
            return
        }

        val (targetZoom, targetTilt) = when (bucket) {
            0 -> Pair(18.0, 35.0)
            1 -> Pair(16.5, 52.0)
            else -> Pair(15.0, 60.0)
        }

        lastSpeedBucket = bucket
        lastCameraAnimTime = now

        val targetLatLng = LatLng(location.latitude, location.longitude)
        val bearing = if (location.hasBearing() && speedKmh > 2.0f) location.bearing.toDouble() else map.cameraPosition.bearing

        val cameraPosition = CameraPosition.Builder()
            .target(targetLatLng)
            .bearing(bearing)
            .zoom(targetZoom)
            .tilt(targetTilt)
            .build()

        if (map.cameraPosition.zoom < 5.0) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1500)
        } else {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 1000)
        }
    }

    @Suppress("SetJavaScriptEnabled")
    private fun initYouTubeWebView() {
        val wv = object : WebView(this) {
            override fun onWindowVisibilityChanged(visibility: Int) {
                // Mentine redarea fluida chiar si cand utilizatorul comuta Cercul Central pe Harta / Vitezometru
                if (isYouTubeWebMode) {
                    super.onWindowVisibilityChanged(View.VISIBLE)
                } else {
                    super.onWindowVisibilityChanged(visibility)
                }
            }
        }

        wv.setBackgroundColor(Color.BLACK)
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false

        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadsImagesAutomatically = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(wv, true)
        } catch (_: Exception) {}

        wv.addJavascriptInterface(object {
            @JavascriptInterface
            fun onVideoStateChanged(playing: Boolean) {
                runOnUiThread {
                    val stateChanged = (isYouTubeWebPlaying != playing)
                    isYouTubeWebPlaying = playing
                    if (playing && stateChanged) {
                        controller?.pause()
                        try {
                            val mc = ExternalMediaManager.getActiveMusicController(this@MainActivity)
                            if (mc != null && mc.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) {
                                mc.transportControls.pause()
                            }
                        } catch (_: Exception) {}
                    }
                    if (currentMode == CenterMode.MEDIA && isYouTubeWebMode) {
                        updatePlayPauseIcon(playing)
                    }
                    updateNowPlayingBar()
                }
            }

            @JavascriptInterface
            fun onVideoMetadataChanged(title: String, artist: String) {
                runOnUiThread {
                    youTubeWebTitle = title.trim()
                    youTubeWebArtist = artist.trim()
                    updateNowPlayingBar()
                }
            }
        }, "BmwYtBridge")

        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                val currentUrl = url ?: return
                injectYouTubeMonitorJs()

                val isWatchUrl = currentUrl.contains("/watch") || currentUrl.contains("/shorts/")
                if (isWatchUrl && youtubeFullscreenOverlay.visibility == View.VISIBLE && currentUrl != urlAtFullscreenOpen) {
                    // Cand utilizatorul apasa pe un videoclip din lista YouTube, il mutam automat in Cercul Central
                    urlAtFullscreenOpen = currentUrl
                    window.decorView.postDelayed({
                        if (youtubeFullscreenOverlay.visibility == View.VISIBLE) {
                            dockYouTubeToCircle()
                        }
                    }, 450L)
                } else if (isYouTubeDockedInCircle) {
                    applyCircleVideoModeJs()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectYouTubeMonitorJs()
                if (isYouTubeDockedInCircle) {
                    applyCircleVideoModeJs()
                }
            }
        }

        youtubeWebView = wv
    }

    private fun injectYouTubeMonitorJs() {
        val js = """
            (function() {
                if (!HTMLMediaElement.prototype.__bmwOrigPause) {
                    HTMLMediaElement.prototype.__bmwOrigPause = HTMLMediaElement.prototype.pause;
                    HTMLMediaElement.prototype.pause = function() {
                        if (window.__bmwCircleModeActive && !window.__bmwUserPaused && !this.ended && !this.seeking) {
                            return;
                        }
                        return HTMLMediaElement.prototype.__bmwOrigPause.apply(this, arguments);
                    };
                }
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch(e) {}

                if (window.__bmwYtMonitorInstalled) return;
                window.__bmwYtMonitorInstalled = true;
                window.__bmwLastPlayAttempt = 0;
                setInterval(function() {
                    var isWatch = location.pathname.indexOf('/watch') !== -1 || location.pathname.indexOf('/shorts') !== -1;
                    if (window.__bmwCircleModeActive && isWatch) {
                        if (!document.getElementById('bmw-circle-style') && window.__bmwInstallCircleCss) {
                            window.__bmwInstallCircleCss();
                        }
                    }
                    var v = document.querySelector('video');
                    if (v && isWatch) {
                        if (window.__bmwCircleModeActive) {
                            if (v.muted) v.muted = false;
                            var now = Date.now();
                            if (!window.__bmwUserPaused && v.paused && !v.seeking && !v.ended && (now - (window.__bmwLastPlayAttempt || 0) > 1200)) {
                                window.__bmwLastPlayAttempt = now;
                                var mp = document.getElementById('movie_player');
                                if (mp && typeof mp.playVideo === 'function') {
                                    try { mp.playVideo(); } catch(e) {}
                                }
                                v.play().catch(function(){});
                            }
                        }
                        var isPlaying = !v.paused && !v.ended;
                        if (window.BmwYtBridge && window.__bmwLastReportedPlaying !== isPlaying) {
                            window.__bmwLastReportedPlaying = isPlaying;
                            window.BmwYtBridge.onVideoStateChanged(isPlaying);
                        }
                        var rawTitle = '';
                        var rawArtist = '';
                        if (navigator.mediaSession && navigator.mediaSession.metadata) {
                            rawTitle = navigator.mediaSession.metadata.title || '';
                            rawArtist = navigator.mediaSession.metadata.artist || '';
                        }
                        if (!rawTitle) {
                            var titleEl = document.querySelector('.slim-video-information-title, ytm-slim-video-metadata-section-renderer h2, .ytp-title-link');
                            if (titleEl) rawTitle = (titleEl.textContent || '').trim();
                        }
                        if (!rawTitle && document.title) {
                            rawTitle = document.title.replace(/\s*-\s*YouTube$/i, '').trim();
                        }
                        if (!rawArtist) {
                            var artistEl = document.querySelector('ytm-slim-owner-renderer .subhead, .slim-owner-icon-and-title .subhead, .ytm-channel-name');
                            if (artistEl) rawArtist = (artistEl.textContent || '').trim();
                        }
                        if (window.BmwYtBridge && rawTitle && (window.__bmwLastTitle !== rawTitle || window.__bmwLastArtist !== rawArtist)) {
                            window.__bmwLastTitle = rawTitle;
                            window.__bmwLastArtist = rawArtist;
                            window.BmwYtBridge.onVideoMetadataChanged(rawTitle, rawArtist || 'YouTube');
                        }
                    }
                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button');
                    if (skipBtn) skipBtn.click();
                }, 600);
            })();
        """.trimIndent()
        youtubeWebView?.evaluateJavascript(js, null)
    }

    private fun applyCircleVideoModeJs() {
        val js = """
            (function() {
                window.__bmwCircleModeActive = true;
                window.__bmwUserPaused = false;
                if (!HTMLMediaElement.prototype.__bmwOrigPause) {
                    HTMLMediaElement.prototype.__bmwOrigPause = HTMLMediaElement.prototype.pause;
                    HTMLMediaElement.prototype.pause = function() {
                        if (window.__bmwCircleModeActive && !window.__bmwUserPaused && !this.ended && !this.seeking) {
                            return;
                        }
                        return HTMLMediaElement.prototype.__bmwOrigPause.apply(this, arguments);
                    };
                }
                window.__bmwInstallCircleCss = function() {
                    if (document.getElementById('bmw-circle-style')) return;
                    var style = document.createElement('style');
                    style.id = 'bmw-circle-style';
                    style.textContent = `
                        header, ytm-mobile-topbar-renderer, .mobile-topbar-header,
                        ytm-single-column-watch-next-results-renderer, ytm-item-section-renderer,
                        ytm-structured-description-content-renderer, ytm-comment-section-renderer,
                        .related-chips-slot-wrapper, ytm-engagement-panel-section-list-renderer,
                        .player-controls-content, .ytp-chrome-top, .ytp-chrome-bottom,
                        #player-control-overlay, ytm-custom-control, .slim-video-metadata-header,
                        ytm-pivot-bar-renderer, ytm-mealbar-promo-renderer, .ytp-watermark,
                        .ytp-pause-overlay, .ytp-unmute, .ytp-cued-thumbnail-overlay {
                            opacity: 0 !important;
                            pointer-events: none !important;
                        }
                        html, body, ytm-app, #app, ytm-watch, .watch-below-the-player {
                            margin: 0 !important;
                            padding: 0 !important;
                            background: #000 !important;
                            overflow: hidden !important;
                            width: 100vw !important;
                            height: 100vh !important;
                        }
                        #player-container-id, .player-container, #player, .html5-video-player, .html5-video-container {
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            right: 0 !important;
                            bottom: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            max-width: 100vw !important;
                            max-height: 100vh !important;
                            margin: 0 !important;
                            padding: 0 !important;
                            z-index: 2147483646 !important;
                            background: #000 !important;
                        }
                        video, .html5-main-video {
                            position: fixed !important;
                            top: 0 !important;
                            left: 0 !important;
                            width: 100vw !important;
                            height: 100vh !important;
                            min-width: 100vw !important;
                            min-height: 100vh !important;
                            object-fit: cover !important;
                            z-index: 2147483647 !important;
                        }
                    `;
                    if (document.head) document.head.appendChild(style);
                };

                var isWatch = location.pathname.indexOf('/watch') !== -1 || location.pathname.indexOf('/shorts') !== -1;
                if (!isWatch && !window.__bmwAutoOpeningWatch) {
                    var firstLink = document.querySelector('ytm-video-with-context-renderer a[href*="/watch"], ytm-media-item a[href*="/watch"], a[href*="/watch?v="]');
                    if (firstLink) {
                        window.__bmwAutoOpeningWatch = true;
                        setTimeout(function() { window.__bmwAutoOpeningWatch = false; }, 2500);
                        firstLink.click();
                        return;
                    }
                }

                window.__bmwInstallCircleCss();
                var mp = document.getElementById('movie_player');
                if (mp && typeof mp.playVideo === 'function') {
                    try { mp.playVideo(); } catch(e) {}
                }
                var v = document.querySelector('video');
                if (v) {
                    if (v.muted) v.muted = false;
                    if (v.paused) {
                        window.__bmwLastPlayAttempt = Date.now();
                        v.play().catch(function(){});
                    }
                }
            })();
        """.trimIndent()
        youtubeWebView?.evaluateJavascript(js, null)
    }

    private fun disableCircleVideoModeJs() {
        val js = """
            (function() {
                window.__bmwCircleModeActive = false;
                var style = document.getElementById('bmw-circle-style');
                if (style) style.remove();
            })();
        """.trimIndent()
        youtubeWebView?.evaluateJavascript(js, null)
    }

    private fun openYouTubeFullscreen() {
        // 1. Opreste radioul intern si sesiunile audio externe
        controller?.pause()
        try {
            ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
        } catch (_: Exception) {}

        isYouTubeWebMode = true
        isYouTubeDockedInCircle = false
        currentMode = CenterMode.MEDIA

        val wv = youtubeWebView ?: return
        urlAtFullscreenOpen = wv.url ?: ""

        disableCircleVideoModeJs()

        (wv.parent as? ViewGroup)?.removeView(wv)
        youtubeFullscreenContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        youtubeFullscreenOverlay.visibility = View.VISIBLE
        updateCenterModeUI()

        if (wv.url.isNullOrEmpty() || wv.url == "about:blank") {
            wv.loadUrl("https://m.youtube.com")
        } else {
            wv.evaluateJavascript("var v = document.querySelector('video'); if (v && v.paused) v.play();", null)
        }
    }

    private fun dockYouTubeToCircle() {
        val wv = youtubeWebView ?: return
        isYouTubeWebMode = true
        isYouTubeDockedInCircle = true
        currentMode = CenterMode.MEDIA

        youtubeFullscreenOverlay.visibility = View.GONE

        (wv.parent as? ViewGroup)?.removeView(wv)
        youtubeCircleContainer.addView(
            wv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        youtubeCircleContainer.visibility = View.VISIBLE
        youtubeCircleTouchOverlay.visibility = View.VISIBLE
        centerMediaThumbnail.visibility = View.GONE
        centerMediaScrim.visibility = View.GONE
        centerMediaInfoContainer.visibility = View.GONE

        updateCenterModeUI()
        injectYouTubeMonitorJs()
        applyCircleVideoModeJs()

        window.decorView.postDelayed({
            applyCircleVideoModeJs()
        }, 400L)
        window.decorView.postDelayed({
            applyCircleVideoModeJs()
        }, 1000L)
    }

    private fun stopYouTubeWebPlayer() {
        isYouTubeWebMode = false
        isYouTubeWebPlaying = false
        isYouTubeDockedInCircle = false
        youtubeFullscreenOverlay.visibility = View.GONE
        youtubeCircleContainer.visibility = View.GONE
        youtubeCircleTouchOverlay.visibility = View.GONE
        centerMediaScrim.visibility = View.GONE
        centerMediaInfoContainer.visibility = View.GONE
        centerMediaThumbnail.visibility = View.VISIBLE
        youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
        updateNowPlayingBar()
    }

    private fun setupListeners() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Bottom Bar Media Controls
        btnPrevTrack.setOnClickListener {
            if (currentMode == CenterMode.MEDIA) {
                if (isYouTubeWebMode) {
                    youtubeWebView?.evaluateJavascript(
                        "(function(){ window.__bmwUserPaused = false; var v = document.querySelector('video'); if (v && v.currentTime > 5) { v.currentTime = 0; v.play(); } else { history.back(); } })();",
                        null
                    )
                    updatePlayPauseIcon(true)
                    return@setOnClickListener
                }
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.skipToPrevious()
                    updatePlayPauseIcon(true)
                    ExternalMediaManager.attachCallback(mc)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 300L)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 800L)
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    updatePlayPauseIcon(true)
                }
            } else {
                controller?.seekToPreviousMediaItem()
            }
        }
        btnPlayPause.setOnClickListener {
            when (currentMode) {
                CenterMode.RADIO -> {
                    controller?.let { c ->
                        if (c.isPlaying) {
                            c.pause()
                        } else {
                            stopYouTubeWebPlayer()
                            try {
                                ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
                            } catch (_: Exception) {}
                            c.play()
                        }
                    }
                }
                CenterMode.MEDIA -> {
                    if (isYouTubeWebMode) {
                        val isCurrentlyPlaying = isCurrentlyPlayingState
                        if (isCurrentlyPlaying) {
                            youtubeWebView?.evaluateJavascript(
                                "window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();",
                                null
                            )
                            isYouTubeWebPlaying = false
                            updatePlayPauseIcon(false)
                        } else {
                            applyCircleVideoModeJs()
                            isYouTubeWebPlaying = true
                            updatePlayPauseIcon(true)
                        }
                        return@setOnClickListener
                    }
                    val isCurrentlyPlaying = isCurrentlyPlayingState
                    val mc = ExternalMediaManager.getActiveMusicController(this)
                    if (mc != null) {
                        if (isCurrentlyPlaying) mc.transportControls.pause() else mc.transportControls.play()
                    } else {
                        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    }
                    updatePlayPauseIcon(!isCurrentlyPlaying)
                }
                else -> {
                    if (controller?.isPlaying == true) {
                        controller?.pause()
                    } else if (isYouTubeWebMode && isYouTubeWebPlaying) {
                        youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
                        isYouTubeWebPlaying = false
                        updatePlayPauseIcon(false)
                    } else if (ExternalMediaManager.currentData.isPlaying) {
                        val mc = ExternalMediaManager.getActiveMusicController(this)
                        if (mc != null) {
                            mc.transportControls.pause()
                        } else {
                            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                        }
                        if (isPortraitOrientation() || determineTargetOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            stopAllAudioPlayers()
                            return@setOnClickListener
                        }
                        try {
                            ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
                        } catch (_: Exception) {}
                        controller?.play()
                    }
                }
            }
        }
        btnNextTrack.setOnClickListener {
            if (currentMode == CenterMode.MEDIA) {
                if (isYouTubeWebMode) {
                    val nextJs = """
                        (function() {
                            var nextBtn = document.querySelector('.ytp-next-button, [aria-label*="Next"], [aria-label*="Înainte"], [aria-label*="Următor"]');
                            if (nextBtn) {
                                nextBtn.click();
                                return;
                            }
                            var links = document.querySelectorAll('ytm-media-item a[href*="/watch"], a.compact-media-item-image[href*="/watch"]');
                            if (links && links.length > 0) {
                                links[0].click();
                            }
                        })();
                    """.trimIndent()
                    youtubeWebView?.evaluateJavascript(nextJs, null)
                    updatePlayPauseIcon(true)
                    return@setOnClickListener
                }
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.skipToNext()
                    updatePlayPauseIcon(true)
                    ExternalMediaManager.attachCallback(mc)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 300L)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 800L)
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                    updatePlayPauseIcon(true)
                }
            } else {
                controller?.seekToNextMediaItem()
            }
        }

        // Butoane Volum Jos
        btnVolumeDown.setOnClickListener {
            adjustVolume(AudioManager.ADJUST_LOWER)
        }
        btnVolumeUp.setOnClickListener {
            adjustVolume(AudioManager.ADJUST_RAISE)
        }

        // Night Mode / Settings
        btnNightMode.setOnClickListener {
            setNightMode(!isNightMode, showToast = true)
        }
        btnQuickSettings.setOnClickListener {
            showSettingsOverlay()
        }
        btnQuickSettings.setOnLongClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Setari Accesibilitate -> Activati 'BMW Car Launcher'", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            true
        }

        // Clic / Enter pe Cercul Central -> comuta afisajul in bucla (BMW -> Media -> Radio -> Map -> BMW)
        centerCircle.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterBmw.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterEnergy.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterNotification.setOnClickListener {
            if (isNotifSwipeHandled) {
                isNotifSwipeHandled = false
                return@setOnClickListener
            }
            cycleCenterMode()
        }

        viewCenterRadio.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterMedia.setOnClickListener {
            cycleCenterMode()
        }

        youtubeCircleTouchOverlay.setOnClickListener {
            cycleCenterMode()
        }
        youtubeCircleTouchOverlay.setOnLongClickListener {
            if (currentMode == CenterMode.MEDIA) {
                openYouTubeFullscreen()
                true
            } else {
                false
            }
        }

        btnYtBack.setOnClickListener {
            val wv = youtubeWebView
            if (wv != null && wv.canGoBack()) {
                wv.goBack()
            } else {
                dockYouTubeToCircle()
            }
        }

        btnYtDockCircle.setOnClickListener {
            dockYouTubeToCircle()
        }

        viewCenterMap.setOnClickListener {
            cycleCenterMode()
        }

        findViewById<View?>(R.id.map_speed_badge)?.setOnClickListener {
            cycleCenterMode()
        }

        btnMapRecenter.setOnClickListener {
            isMapTrackingGps = true
            btnMapRecenter.visibility = View.GONE
            lastKnownLocation?.let { loc ->
                val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                updateMapLibreCamera(loc, speedKmh, force = true)
            }
        }

        // L1: RADIO - Deschide direct lista cu posturile radio (Station Picker Dialog)
        btnLeft1.setOnClickListener {
            showStationPickerDialog()
        }
        btnLeft1.setOnLongClickListener {
            showStationPickerDialog()
            true
        }

        // L2: NAVI - Comută cercul central pe Navigare Google Maps sau lansează aplicația
        btnLeft2.setOnClickListener {
            if (currentMode != CenterMode.MAP) {
                val prev = currentMode
                currentMode = CenterMode.MAP
                updateCenterModeUI(animateFrom = prev)
            } else {
                launchNavigationApp()
            }
        }
        btnLeft2.setOnLongClickListener {
            launchNavigationApp()
            true
        }

        // L3: MUSIC - Deschide lista de selectie (YouTube Music / YouTube) si lanseaza aplicatia aleasa
        btnLeft3.setOnClickListener {
            showMusicAppPickerDialog()
        }
        btnLeft3.setOnLongClickListener {
            showMusicAppPickerDialog()
            true
        }

        // R1: PHONE - Deschide fereastra cu Contacte Favorite (sau dialer la apasare lunga)
        btnRight1.setOnClickListener {
            showFavoritesOverlay()
        }
        btnRight1.setOnLongClickListener {
            launchPhoneDialer()
            true
        }

        // R2: WHATSAPP - Deschide aplicatia WhatsApp
        btnRight2.setOnClickListener {
            launchWhatsApp()
        }

        // R3: APPS - Deschide lista de aplicatii instalate (App Drawer)
        btnRight3.setOnClickListener {
            openAllApps()
        }
    }

    private fun getViewForMode(mode: CenterMode): View = when (mode) {
        CenterMode.BMW -> viewCenterBmw
        CenterMode.MEDIA -> viewCenterMedia
        CenterMode.RADIO -> viewCenterRadio
        CenterMode.MAP -> viewCenterWaze
        CenterMode.ENERGY -> viewCenterEnergy
        CenterMode.NOTIFICATION -> viewCenterNotification
    }

    private fun cycleCenterMode() {
        if (isPortraitOrientation()) {
            val previousMode = currentMode
            val hasNotifs = CarNotificationHub.unreadNotifications.isNotEmpty()
            currentMode = when (currentMode) {
                CenterMode.BMW -> if (hasNotifs) CenterMode.NOTIFICATION else CenterMode.ENERGY
                CenterMode.NOTIFICATION -> CenterMode.ENERGY
                CenterMode.ENERGY -> CenterMode.BMW
                else -> CenterMode.BMW
            }
            if (currentMode == CenterMode.NOTIFICATION) {
                val notif = activeNotification?.takeIf { act -> CarNotificationHub.unreadNotifications.any { it.key == act.key } }
                    ?: CarNotificationHub.unreadNotifications.firstOrNull()
                if (notif != null) {
                    displayNotification(notif)
                    return
                }
            }
            updateCenterModeUI(animateFrom = previousMode)
            if (currentMode == CenterMode.ENERGY) {
                fetchAndDisplayEnergyData()
            }
            return
        }
        val previousMode = currentMode
        currentMode = when (currentMode) {
            CenterMode.BMW -> CenterMode.MEDIA
            CenterMode.MEDIA -> CenterMode.RADIO
            CenterMode.RADIO -> CenterMode.MAP
            CenterMode.MAP -> CenterMode.ENERGY
            CenterMode.ENERGY -> CenterMode.BMW
            CenterMode.NOTIFICATION -> preNotificationMode
        }
        updateCenterModeUI(animateFrom = previousMode)

        if (currentMode == CenterMode.MEDIA && !isYouTubeWebMode) {
            ExternalMediaManager.syncActiveSessions(this)
        }

        if (currentMode == CenterMode.MEDIA && !isYouTubeWebMode && !isNotificationAccessGranted()) {
            promptNotificationAccess()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun promptNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("Acces Notificari Media")
            .setMessage("Pentru a afisa playerul YouTube Music in Cercul Central, acordati permisiunea de citire a notificarilor.")
            .setPositiveButton("Setari") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Deschideti Setari -> Notificari", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Mai tarziu", null)
            .show()
    }

    private fun updateWazeUI(data: WazeNavData) {
        centerWazeDistance.text = data.distance
        centerWazeRoad.text = data.road
        centerWazeEta.text = data.eta

        tvNavSourceLabel?.text = "NAVIGARE ${data.sourceApp}"

        if (data.iconBitmap != null) {
            centerNavIconImg?.setImageBitmap(data.iconBitmap)
            centerNavIconImg?.visibility = View.VISIBLE
            centerWazeIcon.visibility = View.GONE
        } else {
            centerNavIconImg?.visibility = View.GONE
            centerWazeIcon.visibility = View.VISIBLE
            val fullText = "${data.distance} ${data.road}".lowercase(Locale.getDefault())
            centerWazeIcon.text = when {
                fullText.contains("stanga") || fullText.contains("stânga") || fullText.contains("left") -> "↰"
                fullText.contains("dreapta") || fullText.contains("right") -> "↱"
                fullText.contains("giratoriu") || fullText.contains("roundabout") -> "⟳"
                fullText.contains("inainte") || fullText.contains("înainte") || fullText.contains("straight") -> "↑"
                fullText.contains("u-turn") || fullText.contains("intoarcere") || fullText.contains("întoarcere") -> "⤺"
                else -> "🧭"
            }
        }
    }

    private fun updateCenterModeUI(animateFrom: CenterMode? = null) {
        viewCenterWaze.visibility = View.GONE
        viewCenterSpeedo.visibility = View.GONE

        // Mentine youtubeCircleTouchOverlay activ peste WebView cat timp e docat in cerc,
        // astfel incat WebView-ul invizibil din fundal sa nu fure niciodata click-urile!
        youtubeCircleTouchOverlay.visibility =
            if (isYouTubeWebMode && isYouTubeDockedInCircle) {
                View.VISIBLE
            } else {
                View.GONE
            }

        val allModes = CenterMode.values()

        if (animateFrom != null && animateFrom != currentMode) {
            // Asigura ca ecranele care nu participa la tranzitie sunt ascunse (sau alpha 0 pt YouTube WebView)
            for (mode in allModes) {
                if (mode != animateFrom && mode != currentMode) {
                    val v = getViewForMode(mode)
                    v.animate().cancel()
                    v.scaleX = 1f
                    v.scaleY = 1f
                    v.rotation = 0f
                    if (mode == CenterMode.MEDIA && isYouTubeWebMode && isYouTubeDockedInCircle) {
                        v.visibility = View.VISIBLE
                        v.alpha = 0f
                    } else {
                        v.visibility = View.GONE
                        v.alpha = 1f
                    }
                }
            }

            // Puls tactil pe inelul cromat al Cercului Central (stil iDrive controller)
            centerCircle.animate().cancel()
            centerCircle.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(110L)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    centerCircle.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(230L)
                        .setInterpolator(OvershootInterpolator(1.6f))
                        .start()
                }
                .start()

            // Ecranul care iese: rotatie fina spre stanga + micsorare + fade out
            val outView = getViewForMode(animateFrom)
            outView.animate().cancel()
            outView.visibility = View.VISIBLE
            outView.alpha = 1f
            outView.scaleX = 1f
            outView.scaleY = 1f
            outView.rotation = 0f
            outView.animate()
                .alpha(0f)
                .scaleX(0.76f)
                .scaleY(0.76f)
                .rotation(-22f)
                .setDuration(250L)
                .setInterpolator(DecelerateInterpolator(1.5f))
                .withEndAction {
                    outView.scaleX = 1f
                    outView.scaleY = 1f
                    outView.rotation = 0f
                    if (animateFrom == CenterMode.MEDIA && isYouTubeWebMode && isYouTubeDockedInCircle && currentMode != CenterMode.MEDIA) {
                        outView.visibility = View.VISIBLE
                        outView.alpha = 0f
                    } else if (currentMode != animateFrom) {
                        outView.visibility = View.GONE
                        outView.alpha = 1f
                    }
                }
                .start()

            // Ecranul care intra: apare cu rotatie fina + zoom elastic (Overshoot) + fade in
            val inView = getViewForMode(currentMode)
            inView.animate().cancel()
            inView.visibility = View.VISIBLE
            inView.alpha = 0f
            inView.scaleX = 0.76f
            inView.scaleY = 0.76f
            inView.rotation = 22f
            inView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .rotation(0f)
                .setDuration(340L)
                .setInterpolator(OvershootInterpolator(1.15f))
                .start()
        } else {
            for (mode in allModes) {
                val v = getViewForMode(mode)
                v.animate().cancel()
                v.scaleX = 1f
                v.scaleY = 1f
                v.rotation = 0f
                if (mode == currentMode) {
                    v.visibility = View.VISIBLE
                    v.alpha = 1f
                } else if (mode == CenterMode.MEDIA && isYouTubeWebMode && isYouTubeDockedInCircle) {
                    // Mentine viewCenterMedia activ cu alpha 0 in spate pentru ca WebView sa nu opreasca redarea video/audio
                    v.visibility = View.VISIBLE
                    v.alpha = 0f
                } else {
                    v.visibility = View.GONE
                    v.alpha = 1f
                }
            }
        }

        if (currentMode == CenterMode.MAP) {
            isMapTrackingGps = true
            btnMapRecenter.visibility = View.GONE
            val loc = lastKnownLocation ?: getBestLastKnownLocation()
            if (loc != null) {
                lastKnownLocation = loc
                val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                updateMapLibreCamera(loc, speedKmh, force = true)
            }
        }

        if (currentMode == CenterMode.ENERGY) {
            energyHandler.removeCallbacks(energyPollRunnable)
            energyPollRunnable.run()
        } else {
            energyHandler.removeCallbacks(energyPollRunnable)
        }

        when (currentMode) {
            CenterMode.RADIO -> {
                updatePlayPauseIcon(controller?.isPlaying == true)
            }
            CenterMode.MEDIA -> {
                val playing = if (isYouTubeWebMode) isYouTubeWebPlaying else ExternalMediaManager.currentData.isPlaying
                updatePlayPauseIcon(playing)
            }
            else -> {
                val isAnyPlaying = (controller?.isPlaying == true) ||
                        (isYouTubeWebMode && isYouTubeWebPlaying) ||
                        ExternalMediaManager.currentData.isPlaying
                updatePlayPauseIcon(isAnyPlaying)
            }
        }
        updateNowPlayingBar()
    }

    private fun updatePlayPauseIcon(isPlaying: Boolean) {
        isCurrentlyPlayingState = isPlaying
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.setImageResource(if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play)
        }
    }

    private fun updateNowPlayingBar() {
        if (!::bottomTrackTitle.isInitialized || !::bottomTrackArtist.isInitialized) return

        val extData = ExternalMediaManager.currentData
        val isRadioPlaying = (controller?.isPlaying == true)

        val (titleText, artistText) = when {
            isYouTubeWebMode -> {
                val t = youTubeWebTitle.ifEmpty { "YouTube" }
                val a = youTubeWebArtist.ifEmpty { "YouTube Video" }
                Pair(t, a)
            }
            (extData.isPlaying || (currentMode == CenterMode.MEDIA && extData.title.isNotEmpty())) && !isRadioPlaying -> {
                val t = extData.title.ifEmpty { "YouTube Music" }
                val a = extData.artist.ifEmpty { extData.sourceName.ifEmpty { "YouTube Music" } }
                Pair(t, a)
            }
            isRadioPlaying || currentMode == CenterMode.RADIO -> {
                val idx = controller?.currentMediaItemIndex ?: 0
                val stationName = if (idx in stations.indices) stations[idx].name else "BMW RADIO"
                val stationGenre = if (idx in stations.indices) stations[idx].genre else "Radio Live"
                val liveTitle = controller?.mediaMetadata?.title?.toString()?.trim() ?: ""

                if (liveTitle.isNotEmpty() && !liveTitle.equals(stationName, ignoreCase = true)) {
                    if (liveTitle.contains(" - ")) {
                        val parts = liveTitle.split(" - ", limit = 2)
                        val artistPart = parts[0].trim()
                        val songPart = parts[1].trim().ifEmpty { liveTitle }
                        Pair(songPart, "$artistPart • $stationName")
                    } else {
                        Pair(liveTitle, stationName)
                    }
                } else {
                    Pair(stationName, stationGenre.ifEmpty { "Radio Live" })
                }
            }
            extData.title.isNotEmpty() -> {
                val t = extData.title
                val a = extData.artist.ifEmpty { extData.sourceName.ifEmpty { "YouTube Music" } }
                Pair(t, a)
            }
            else -> {
                Pair("No Media", "Selectează o piesă...")
            }
        }

        if (bottomTrackTitle.text.toString() != titleText) {
            bottomTrackTitle.text = titleText
            bottomTrackTitle.isSelected = true
        }
        if (bottomTrackArtist.text.toString() != artistText) {
            bottomTrackArtist.text = artistText
        }
    }

    private fun updateClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("H:mm", Locale.getDefault())
        textClock.text = timeFormat.format(now)

        if (isPortraitOrientation()) {
            if (controller?.isPlaying == true || (isYouTubeWebMode && isYouTubeWebPlaying) || ExternalMediaManager.currentData.isPlaying) {
                stopAllAudioPlayers()
            }

            updateNetworkStatus()

            val dateView = findViewById<TextView>(R.id.portrait_date_text)
            if (dateView != null) {
                val dateFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ro", "RO"))
                val dateFormatted = dateFormat.format(now).replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(Locale("ro", "RO")) else it.toString()
                }
                dateView.text = dateFormatted
            }

            val permIndicator = findViewById<TextView>(R.id.tv_portrait_notif_perm_indicator)
            if (permIndicator != null) {
                val hasAccess = CarNotificationListenerService.isNotificationAccessGranted(this)
                permIndicator.visibility = if (!hasAccess) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateNetworkStatus() {
        val netStatusView = findViewById<TextView>(R.id.port_network_status) ?: return
        val signalIconView = findViewById<ImageView>(R.id.port_signal_icon)
        val notifContainer = findViewById<View>(R.id.port_notif_container)
        val notifCountView = findViewById<TextView>(R.id.port_notif_count)

        // 1. Notificări necitite în bara de sus
        val unreadCount = CarNotificationHub.getUnreadCount()
        if (unreadCount > 0) {
            notifContainer?.visibility = View.VISIBLE
            notifCountView?.text = "$unreadCount"
        } else {
            notifContainer?.visibility = View.GONE
        }

        // 2. Semnal curat (doar bare verticale albe, fără fond) și tip rețea
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNet)

            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                signalIconView?.visibility = View.GONE
                netStatusView.text = "📵 Lipsă semnal"
                return
            }

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                signalIconView?.setImageResource(R.drawable.ic_wifi_white)
                signalIconView?.visibility = View.VISIBLE
                netStatusView.text = "Wi-Fi"
                return
            }

            signalIconView?.setImageResource(R.drawable.ic_cellular_signal_white)
            signalIconView?.visibility = View.VISIBLE

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                val netType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    try {
                        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                            tm?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
                        } else {
                            TelephonyManager.NETWORK_TYPE_UNKNOWN
                        }
                    } catch (_: Exception) {
                        TelephonyManager.NETWORK_TYPE_UNKNOWN
                    }
                } else {
                    @Suppress("DEPRECATION")
                    tm?.networkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
                }

                val typeLabel = when (netType) {
                    TelephonyManager.NETWORK_TYPE_NR -> "5G"
                    TelephonyManager.NETWORK_TYPE_LTE -> "4G"
                    TelephonyManager.NETWORK_TYPE_HSDPA,
                    TelephonyManager.NETWORK_TYPE_HSPA,
                    TelephonyManager.NETWORK_TYPE_HSPAP,
                    TelephonyManager.NETWORK_TYPE_HSUPA,
                    TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
                    TelephonyManager.NETWORK_TYPE_EDGE,
                    TelephonyManager.NETWORK_TYPE_GPRS,
                    TelephonyManager.NETWORK_TYPE_CDMA,
                    TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
                    else -> "4G"
                }
                netStatusView.text = typeLabel
                return
            }

            netStatusView.text = "OK"
        } catch (_: Exception) {
            signalIconView?.setImageResource(R.drawable.ic_cellular_signal_white)
            signalIconView?.visibility = View.VISIBLE
            netStatusView.text = "4G"
        }
    }

    private fun launchNavigationApp() {
        val mapsPackage = "com.google.android.apps.maps"
        val wazePackage = "com.waze"
        val pm = packageManager

        val intent = pm.getLaunchIntentForPackage(mapsPackage)
            ?: pm.getLaunchIntentForPackage(wazePackage)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nicio aplicatie de navigatie gasita", Toast.LENGTH_SHORT).show()
        }
    }

    private data class MusicOption(
        val title: String,
        val desc: String,
        val iconRes: Int,
        val action: () -> Unit
    )

    private fun showMusicAppPickerDialog() {
        val options = mutableListOf<MusicOption>()

        // 1. YouTube Music
        options.add(MusicOption(
            title = "YouTube Music",
            desc = "Redare audio sincronizată în Cercul Central",
            iconRes = R.drawable.ic_gauge_music,
            action = { launchSelectedMediaApp(isYouTubeMusic = true) }
        ))

        // 2. YouTube Live Video
        options.add(MusicOption(
            title = "YouTube Live Video",
            desc = "Redare video circulară în Cercul Central",
            iconRes = R.drawable.ic_media_play,
            action = { launchSelectedMediaApp(isYouTubeMusic = false) }
        ))

        // 3. Spotify (dacă este instalat)
        val pm = packageManager
        if (pm.getLaunchIntentForPackage("com.spotify.music") != null) {
            options.add(MusicOption(
                title = "Spotify",
                desc = "Streaming audio extern",
                iconRes = R.drawable.ic_gauge_music,
                action = {
                    musicOverlayContainer.visibility = View.GONE
                    focusPlayPauseButton()
                    controller?.pause()
                    stopYouTubeWebPlayer()
                    currentMode = CenterMode.MEDIA
                    updateCenterModeUI()
                    ExternalMediaManager.preferredPackage = "com.spotify.music"
                    val launchIntent = pm.getLaunchIntentForPackage("com.spotify.music")
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                    }
                }
            ))
        }

        listMusicApps.adapter = object : BaseAdapter() {
            override fun getCount(): Int = options.size
            override fun getItem(position: Int): Any = options[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_music_app, parent, false)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                val opt = options[position]

                val frame = view.findViewById<FrameLayout>(R.id.music_app_icon_frame)
                frame.outlineProvider = circleOutlineProvider
                frame.clipToOutline = true

                val icon = view.findViewById<ImageView>(R.id.music_app_icon)
                val title = view.findViewById<TextView>(R.id.music_app_title)
                val desc = view.findViewById<TextView>(R.id.music_app_desc)

                icon.setImageResource(opt.iconRes)
                title.text = opt.title
                desc.text = opt.desc

                view.setOnClickListener {
                    musicOverlayContainer.visibility = View.GONE
                    focusPlayPauseButton()
                    opt.action()
                }

                return view
            }
        }

        musicOverlayContainer.visibility = View.VISIBLE
        btnCloseMusic.isFocusable = true
        btnCloseMusic.isFocusableInTouchMode = false
        btnCloseMusic.enableInstantClickOnTouch()
    }

    private fun launchSelectedMediaApp(isYouTubeMusic: Boolean) {
        // 1. Opreste radioul intern
        controller?.pause()

        if (!isYouTubeMusic) {
            // Deschide playerul video YouTube integrat care reda videoclipul live cropat in Cercul Central
            openYouTubeFullscreen()
            return
        }

        // Daca a fost selectat YouTube Music, oprim playerul video YouTube WebView
        stopYouTubeWebPlayer()

        // 2. Comuta Cercul Central pe modul MEDIA (playerul integrat YT Music)
        currentMode = CenterMode.MEDIA
        updateCenterModeUI()

        val candidatePackages = listOf(
            "com.google.android.apps.youtube.music",
            "app.revanced.android.apps.youtube.music",
            "com.vanced.android.apps.youtube.music"
        )

        val pm = packageManager
        var launchIntent: Intent? = null
        var resolvedPkg = candidatePackages.first()

        for (pkg in candidatePackages) {
            val intent = pm.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                launchIntent = intent
                resolvedPkg = pkg
                break
            }
        }

        // 3. Seteaza pachetul preferat pentru integrarea cu Cercul Central
        ExternalMediaManager.preferredPackage = resolvedPkg

        // Daca in Cercul Central era afisata cealalta aplicatie, actualizeaza imediat afisajul de jos
        if (!ExternalMediaManager.currentData.packageName.equals(resolvedPkg, ignoreCase = true)) {
            centerMediaThumbnail.setImageResource(R.drawable.bmw_logo)
            centerMediaThumbnail.visibility = View.VISIBLE
            bottomTrackTitle.text = "YouTube Music"
            bottomTrackArtist.text = "Selectează o melodie..."
        }

        // Sincronizeaza sesiunea activa daca aplicatia ruleaza deja in fundal
        ExternalMediaManager.syncActiveSessions(this)
        updateNowPlayingBar()

        // Armeaza revenirea automata in BMW Car Launcher imediat ce utilizatorul selecteaza o melodie in YouTube Music
        ExternalMediaManager.armAutoReturnFromYtMusic(this, taskId)

        // 4. Deschide aplicatia selectata
        if (launchIntent != null) {
            try {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } catch (e: Exception) {
                ExternalMediaManager.cancelAutoReturn()
                Toast.makeText(this, "Eroare la deschiderea aplicației", Toast.LENGTH_SHORT).show()
            }
        } else {
            ExternalMediaManager.cancelAutoReturn()
            Toast.makeText(this, "YouTube Music nu este instalat", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchPhoneDialer() {
        try {
            val intent = Intent(Intent.ACTION_DIAL)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nu se poate deschide ecranul telefon", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchWhatsApp() {
        val pm = packageManager
        val launchIntent = pm.getLaunchIntentForPackage("com.whatsapp")
        if (launchIntent != null) {
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Toast.makeText(this, "Eroare la deschiderea WhatsApp", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Aplicația WhatsApp nu este instalată", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAllApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString() }

        gridAllApps.adapter = object : BaseAdapter() {
            override fun getCount(): Int = apps.size
            override fun getItem(position: Int): Any = apps[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app_grid, parent, false)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                val info = apps[position]

                val icon = view.findViewById<ImageView>(R.id.app_icon)
                val name = view.findViewById<TextView>(R.id.app_name)

                icon.setImageDrawable(info.loadIcon(pm))
                name.text = info.loadLabel(pm)

                view.setOnClickListener {
                    appsOverlayContainer.visibility = View.GONE
                    focusPlayPauseButton()
                    val launchIntent = pm.getLaunchIntentForPackage(info.activityInfo.packageName)
                    if (launchIntent != null) {
                        startActivity(launchIntent)
                    }
                }

                return view
            }
        }

        appsOverlayContainer.visibility = View.VISIBLE
        btnCloseApps.isFocusable = true
        btnCloseApps.isFocusableInTouchMode = false
        btnCloseApps.enableInstantClickOnTouch()
    }

    private fun initSettingsOverlay() {
        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val dayBrightness = prefs.getInt("day_brightness", 90).coerceIn(5, 100)
        val nightBrightness = prefs.getInt("night_brightness", 10).coerceIn(5, 100)

        seekDayBrightness.progress = dayBrightness
        tvDayBrightnessVal.text = "$dayBrightness%"

        seekNightBrightness.progress = nightBrightness
        tvNightBrightnessVal.text = "$nightBrightness%"

        seekDayBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(5, 100)
                if (clamped != progress) {
                    seekBar?.progress = clamped
                }
                tvDayBrightnessVal.text = "$clamped%"
                if (fromUser) {
                    prefs.edit().putInt("day_brightness", clamped).apply()
                    if (!isNightMode) {
                        applyScreenBrightness(false)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        seekNightBrightness.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceIn(5, 100)
                if (clamped != progress) {
                    seekBar?.progress = clamped
                }
                tvNightBrightnessVal.text = "$clamped%"
                if (fromUser) {
                    prefs.edit().putInt("night_brightness", clamped).apply()
                    if (isNightMode) {
                        applyScreenBrightness(true)
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnCloseSettings.setOnClickListener {
            settingsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }
        settingsOverlayContainer.setOnClickListener {
            settingsOverlayContainer.visibility = View.GONE
            focusPlayPauseButton()
        }

        btnOpenNotifSettings.setOnClickListener {
            val isNotifGranted = CarNotificationListenerService.isNotificationAccessGranted(this)
            val isStorageGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
            if (!isNotifGranted) {
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Deschide Setări -> Notificări", Toast.LENGTH_SHORT).show()
                }
            } else if (!isStorageGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            } else {
                Toast.makeText(this, "Accesul este deja configurat complet!", Toast.LENGTH_SHORT).show()
            }
        }

        btnCarBtSelect.setOnClickListener {
            showCarBluetoothPicker()
        }

        btnToggleForceDisplayMode.setOnClickListener {
            cycleForceDisplayMode()
        }
    }

    private fun showSettingsOverlay() {
        dismissAllOverlays()
        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val dayBrightness = prefs.getInt("day_brightness", 90).coerceIn(5, 100)
        val nightBrightness = prefs.getInt("night_brightness", 10).coerceIn(5, 100)

        seekDayBrightness.progress = dayBrightness
        tvDayBrightnessVal.text = "$dayBrightness%"

        seekNightBrightness.progress = nightBrightness
        tvNightBrightnessVal.text = "$nightBrightness%"

        val carBtName = prefs.getString("car_bt_name", null)
        val carBtAddress = prefs.getString("car_bt_address", null)
        if (!carBtAddress.isNullOrEmpty()) {
            tvCarBtStatus.text = if (!carBtName.isNullOrEmpty()) "$carBtName ($carBtAddress)" else carBtAddress
        } else {
            tvCarBtStatus.text = "Neselectat (Apasă pentru selectare)"
        }

        val forceMode = prefs.getString("force_display_mode", "auto") ?: "auto"
        updateDisplayModeUI(forceMode)

        val isNotifGranted = CarNotificationListenerService.isNotificationAccessGranted(this)
        val isStorageGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val allOk = isNotifGranted && isStorageGranted

        tvNotifAccessStatus.text = when {
            allOk -> "Activat (WhatsApp / SMS / Note Vocale)"
            !isNotifGranted -> "Inactiv • Necesită Acces Notificări"
            else -> "Atenție • Necesită Acces Fișiere (Note Vocale)"
        }
        tvNotifAccessStatus.setTextColor(ContextCompat.getColor(this, if (allOk) R.color.bmw_amber else R.color.bmw_gray_text))

        settingsOverlayContainer.visibility = View.VISIBLE
        btnCloseSettings.isFocusable = true
        btnCloseSettings.isFocusableInTouchMode = false
        btnCloseSettings.enableInstantClickOnTouch()
    }

    private fun showCarBluetoothPicker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBtConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                return
            }
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth este dezactivat!", Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = try {
            btAdapter.bondedDevices?.toList() ?: emptyList()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Lipsă permisiune Bluetooth!", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val currentCarAddress = prefs.getString("car_bt_address", "") ?: ""

        val items = ArrayList<String>()
        items.add("Niciunul (Dezactivat)")
        var selectedIndex = 0

        bondedDevices.forEachIndexed { index, device ->
            val devName = try { device.name } catch (_: SecurityException) { null } ?: "Dispozitiv necunoscut"
            items.add("$devName (${device.address})")
            if (device.address.equals(currentCarAddress, ignoreCase = true)) {
                selectedIndex = index + 1
            }
        }

        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Selectează Bluetooth-ul Mașinii")
        builder.setSingleChoiceItems(items.toTypedArray(), selectedIndex) { dialog, which ->
            if (which == 0) {
                prefs.edit().remove("car_bt_address").remove("car_bt_name").putString("force_display_mode", "auto").apply()
                lastDisconnectTimestamp = 0L
                isCarConnectedState = false
                tvCarBtStatus.text = "Neselectat (Apasă pentru selectare)"
                Toast.makeText(this, "Bluetooth mașină dezactivat", Toast.LENGTH_SHORT).show()
            } else {
                val selectedDevice = bondedDevices[which - 1]
                val devName = try { selectedDevice.name } catch (_: SecurityException) { null } ?: "Dispozitiv necunoscut"
                prefs.edit()
                    .putString("car_bt_address", selectedDevice.address)
                    .putString("car_bt_name", devName)
                    .putString("force_display_mode", "auto")
                    .apply()
                lastDisconnectTimestamp = 0L
                isCarConnectedState = false
                tvCarBtStatus.text = "$devName (${selectedDevice.address})"
                Toast.makeText(this, "Bluetooth mașină setat: $devName", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
            checkBluetoothConnectionAndUpdateOrientation()
        }
        builder.setNegativeButton("Anulează", null)
        builder.show()
    }

    private fun cycleForceDisplayMode() {
        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val currentMode = prefs.getString("force_display_mode", "auto") ?: "auto"
        val nextMode = when (currentMode) {
            "auto" -> "landscape"
            "landscape" -> "portrait"
            else -> "auto"
        }
        prefs.edit().putString("force_display_mode", nextMode).apply()
        updateDisplayModeUI(nextMode)
        val msg = when (nextMode) {
            "landscape" -> "Mod Forțat: Mașină (Landscape)"
            "portrait" -> "Mod Forțat: Portret (Telefon)"
            else -> "Mod: Automat (urmează conexiunea Bluetooth a mașinii)"
        }
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        checkBluetoothConnectionAndUpdateOrientation()
    }

    private fun updateDisplayModeUI(mode: String) {
        tvDisplayModeStatus.text = when (mode) {
            "landscape" -> "Forțat Mașină (Landscape)"
            "portrait" -> "Forțat Portret (Telefon)"
            else -> "Automat (Bluetooth mașină)"
        }
        tvDisplayModeBadge.text = when (mode) {
            "landscape" -> "MAȘINĂ ›"
            "portrait" -> "PORTRET ›"
            else -> "AUTO ›"
        }
    }

    private fun checkAndRequestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestBtConnectPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
    }

    private fun initBluetoothProfiles() {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            btAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dpProxy = proxy as? BluetoothA2dp
                    } else if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = proxy as? BluetoothHeadset
                    }
                    checkBluetoothConnectionAndUpdateOrientation()
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.A2DP) a2dpProxy = null
                    if (profile == BluetoothProfile.HEADSET) headsetProxy = null
                }
            }, BluetoothProfile.A2DP)

            btAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        headsetProxy = proxy as? BluetoothHeadset
                    }
                    checkBluetoothConnectionAndUpdateOrientation()
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) headsetProxy = null
                }
            }, BluetoothProfile.HEADSET)
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare init profile proxies Bluetooth: ${e.message}")
        }
    }

    private fun isCarBluetoothConnected(): Boolean {
        // Dacă s-a deconectat în ultimele 5 secunde, ignoră orice stare reziduală din proxy-uri/reflection
        if (!isCarConnectedState && android.os.SystemClock.elapsedRealtime() - lastDisconnectTimestamp < 5000L) {
            return false
        }

        if (isCarConnectedState) return true

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val savedCarAddress = prefs.getString("car_bt_address", null)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
        }

        try {
            if (!savedCarAddress.isNullOrEmpty()) {
                val a2dpConn = a2dpProxy?.connectedDevices?.any { it.address.equals(savedCarAddress, ignoreCase = true) } == true
                val headsetConn = headsetProxy?.connectedDevices?.any { it.address.equals(savedCarAddress, ignoreCase = true) } == true
                val gattConn = try {
                    btManager?.getConnectedDevices(BluetoothProfile.GATT)?.any { it.address.equals(savedCarAddress, ignoreCase = true) } == true
                } catch (_: Exception) { false }

                var devIsConnected = false
                try {
                    val remoteDev = btAdapter.getRemoteDevice(savedCarAddress)
                    val method = remoteDev.javaClass.getMethod("isConnected")
                    devIsConnected = (method.invoke(remoteDev) as? Boolean) == true
                } catch (_: Exception) {}

                Log.d("MainActivity", "isCarBluetoothConnected check for $savedCarAddress: a2dp=$a2dpConn, hfp=$headsetConn, gatt=$gattConn, isConnected=$devIsConnected")
                if (a2dpConn || headsetConn || gattConn || devIsConnected) {
                    isCarConnectedState = true
                    return true
                }
                return false
            }

            // Fallback dacă nu este selectat explicit un dispozitiv: detectează BMW / iDrive / ESP32 doar prin profiluri active A2DP/Headset
            val connectedA2dp = a2dpProxy?.connectedDevices ?: emptyList()
            val connectedHeadset = headsetProxy?.connectedDevices ?: emptyList()
            val allConnected = (connectedA2dp + connectedHeadset).distinctBy { it.address }
            for (dev in allConnected) {
                val name = try { dev.name } catch (_: Exception) { null } ?: ""
                if (name.contains("BMW", ignoreCase = true) || name.contains("iDrive", ignoreCase = true) || name.contains("ESP32", ignoreCase = true)) {
                    isCarConnectedState = true
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare verificare conexiune Bluetooth: ${e.message}")
        }

        return false
    }

    private fun determineTargetOrientation(): Int {
        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val forceMode = prefs.getString("force_display_mode", "auto") ?: "auto"
        return when (forceMode) {
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> {
                if (isCarBluetoothConnected()) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
        }
    }

    fun stopAllAudioPlayers() {
        try {
            sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(packageName))
        } catch (_: Exception) {}
        try {
            controller?.pause()
            controller?.stop()
        } catch (_: Exception) {}
        try {
            if (isYouTubeWebMode && isYouTubeWebPlaying) {
                youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
                isYouTubeWebPlaying = false
            }
        } catch (_: Exception) {}
        try {
            ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
        } catch (_: Exception) {}
        try {
            VoiceMessagePlayer.stop()
        } catch (_: Exception) {}
        try {
            textToSpeech?.stop()
        } catch (_: Exception) {}
        try {
            updatePlayPauseIcon(false)
        } catch (_: Exception) {}
    }

    fun wakeScreenAndShowOverLockscreen() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (_: Exception) {}
    }

    fun clearShowWhenLocked() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(false)
                setTurnScreenOn(false)
            } else {
                @Suppress("DEPRECATION")
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            Log.w("MainActivity", "clearShowWhenLocked warning: ${e.message}")
        }
    }

    private var lastRadioStartTimestamp = 0L

    fun startRadioPlayback() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRadioStartTimestamp < 2500L) return
        lastRadioStartTimestamp = now
        try {
            val ctrl = controller
            if (ctrl != null) {
                if (!ctrl.isPlaying && currentMode != CenterMode.MEDIA && !ExternalMediaManager.currentData.isPlaying) {
                    ctrl.play()
                    Log.i("MainActivity", "Radio intern pornit via controller la conectare la mașină")
                }
            } else {
                val radioIntent = Intent(this, RadioService::class.java).apply {
                    action = RadioService.ACTION_START_PLAY
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(radioIntent)
                } else {
                    startService(radioIntent)
                }
                Log.i("MainActivity", "RadioService pornit cu ACTION_START_PLAY la conectare la mașină")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare la startRadioPlayback: ${e.message}", e)
        }
    }

    private fun applyTargetOrientation() {
        checkBluetoothConnectionAndUpdateOrientation()
    }

    fun checkBluetoothConnectionAndUpdateOrientation() {
        val target = determineTargetOrientation()
        if (target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            clearShowWhenLocked()
            stopAllAudioPlayers()
        } else {
            startRadioPlayback()
        }
        if (requestedOrientation != target) {
            val previousOrientation = requestedOrientation
            Log.i("MainActivity", "checkBluetoothConnectionAndUpdateOrientation: comutare de la $previousOrientation la $target")
            requestedOrientation = target

            // Când trece în Mod Portret, blochează ecranul (lock display)
            if (target == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                lockScreenIfPossible()
            }
        }
    }

    fun lockScreenIfPossible() {
        runOnUiThread {
            try {
                val locked = BMWAccessibilityService.lockScreen()
                if (!locked) {
                    Log.d("MainActivity", "lockScreen via Accessibility returned false (serviciul nu este activat de utilizator)")
                } else {
                    Log.i("MainActivity", "Display locked successfully on transition to Portrait mode")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Eroare blocare ecran la comutare in portret: ${e.message}")
            }
        }
    }

    private fun refreshStations() {
        stations = StationManager.getStations(this)
    }

    private fun showStationPickerDialog() {
        refreshStations()
        listRadioStations.adapter = object : BaseAdapter() {
            override fun getCount(): Int = stations.size
            override fun getItem(position: Int): Any = stations[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val view = convertView ?: layoutInflater.inflate(R.layout.item_radio_station, parent, false)
                view.isFocusable = true
                view.isFocusableInTouchMode = false
                view.enableInstantClickOnTouch()
                val station = stations[position]

                val frame = view.findViewById<FrameLayout>(R.id.radio_logo_frame)
                frame.outlineProvider = circleOutlineProvider
                frame.clipToOutline = true

                val logo = view.findViewById<ImageView>(R.id.radio_station_logo)
                logo.outlineProvider = circleOutlineProvider
                logo.clipToOutline = true

                val name = view.findViewById<TextView>(R.id.radio_station_name)
                val genre = view.findViewById<TextView>(R.id.radio_station_genre)
                val actionIcon = view.findViewById<ImageView>(R.id.radio_station_action_icon)

                name.text = station.name
                genre.text = station.genre.ifEmpty { "Live Radio" }

                val isCurrentlyPlaying = (currentMode == CenterMode.RADIO && controller?.isPlaying == true && controller?.currentMediaItemIndex == position)
                if (isCurrentlyPlaying) {
                    actionIcon.setImageResource(R.drawable.ic_media_pause)
                } else {
                    actionIcon.setImageResource(R.drawable.ic_media_play)
                }

                val btnDelete = view.findViewById<FrameLayout>(R.id.btn_delete_station)
                btnDelete.isFocusable = true
                btnDelete.isFocusableInTouchMode = false
                btnDelete.enableInstantClickOnTouch()
                btnDelete.setOnClickListener {
                    showDeleteStationDialog(position, station.name)
                }

                view.setOnClickListener {
                    radioOverlayContainer.visibility = View.GONE
                    focusPlayPauseButton()
                    stopYouTubeWebPlayer()
                    controller?.let {
                        try {
                            ExternalMediaManager.currentData.mediaController?.transportControls?.pause()
                        } catch (_: Exception) {}
                        it.seekToDefaultPosition(position)
                        it.play()
                    }
                    currentMode = CenterMode.RADIO
                    updateCenterModeUI()
                }

                return view
            }
        }
        radioOverlayContainer.visibility = View.VISIBLE
        btnCloseRadio.isFocusable = true
        btnCloseRadio.isFocusableInTouchMode = false
        btnCloseRadio.enableInstantClickOnTouch()
    }

    private fun showDeleteStationDialog(position: Int, stationName: String) {
        AlertDialog.Builder(this)
            .setTitle("Ștergere Post Radio")
            .setMessage("Sigur doriți să ștergeți postul \"$stationName\" din listă?")
            .setPositiveButton("Șterge") { _, _ ->
                if (position in 0 until stations.size) {
                    val wasPlayingThis = (currentMode == CenterMode.RADIO && controller?.currentMediaItemIndex == position)
                    if (wasPlayingThis) {
                        try {
                            controller?.pause()
                        } catch (_: Exception) {}
                    }
                    stations.removeAt(position)
                    StationManager.saveStations(this, stations)
                    refreshStations()
                    reloadServicePlaylist()
                    (listRadioStations.adapter as? BaseAdapter)?.notifyDataSetChanged()
                    Toast.makeText(this, "Postul \"$stationName\" a fost șters", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Anulează", null)
            .show()
    }

    private fun showAddStationDialog() {
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }

        val nameInput = EditText(this).apply { hint = "Nume post (ex: Guerilla)" }
        val genreInput = EditText(this).apply { hint = "Gen (ex: Alternative)" }
        val urlInput = EditText(this).apply { hint = "URL Stream (http/https...)" }
        val logoInput = EditText(this).apply { hint = "URL Sigla (optional)" }

        dialogLayout.addView(nameInput)
        dialogLayout.addView(genreInput)
        dialogLayout.addView(urlInput)
        dialogLayout.addView(logoInput)

        AlertDialog.Builder(this)
            .setTitle("Adauga Post Radio")
            .setView(dialogLayout)
            .setPositiveButton("Salveaza") { _, _ ->
                val name = nameInput.text.toString().trim()
                val genre = genreInput.text.toString().trim().ifEmpty { "Live Radio" }
                val url = urlInput.text.toString().trim()
                val logo = logoInput.text.toString().trim()

                if (name.isNotEmpty() && url.startsWith("http")) {
                    stations.add(RadioStation(name, genre, url, logo))
                    StationManager.saveStations(this, stations)
                    refreshStations()
                    reloadServicePlaylist()
                } else {
                    Toast.makeText(this, "Completeaza numele si un URL valid (http/https)!", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Anuleaza", null)
            .show()
    }

    private fun reloadServicePlaylist() {
        controller?.sendCustomCommand(
            SessionCommand(RadioService.ACTION_RELOAD_STATIONS, Bundle.EMPTY),
            Bundle.EMPTY
        )
    }

    // Gestiunea tastelor fizice transmise de ESP32-C3 prin BLE HID
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && youtubeFullscreenOverlay.visibility == View.VISIBLE) {
            dockYouTubeToCircle()
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_HEADSETHOOK -> {
                answerPhoneCall()
                return true
            }
            KeyEvent.KEYCODE_ENDCALL -> {
                hangupPhoneCall()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                btnNextTrack.performClick()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                btnPrevTrack.performClick()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                btnPlayPause.performClick()
                return true
            }
            KeyEvent.KEYCODE_F12, KeyEvent.KEYCODE_F11 -> {
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun answerPhoneCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    tm?.acceptRingingCall()
                    return
                }
            }
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            sendOrderedBroadcast(intent, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Preluare apel...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hangupPhoneCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    tm?.endCall()
                    return
                }
            }
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            }
            sendOrderedBroadcast(intent, null)
        } catch (e: Exception) {
            Toast.makeText(this, "Inchidere apel...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getBestLastKnownLocation(): Location? {
        val lm = locationManager ?: (getSystemService(Context.LOCATION_SERVICE) as? LocationManager) ?: return null
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        var bestLocation: Location? = null
        try {
            val providers = lm.getProviders(true)
            for (provider in providers) {
                val l = lm.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.time > bestLocation.time) {
                    bestLocation = l
                }
            }
        } catch (_: Exception) {}
        return bestLocation
    }

    private fun startGpsUpdates() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                1001
            )
            return
        }

        try {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            locationManager?.let { lm ->
                // Preluam instantaneu ultima locatie din cache-ul sistemului pentru a avea zoom imediat la pornire
                val initialLoc = getBestLastKnownLocation()
                if (initialLoc != null) {
                    lastKnownLocation = initialLoc
                    val speedKmh = if (initialLoc.hasSpeed()) initialLoc.speed * 3.6f else 0f
                    updateMapLibreCamera(initialLoc, speedKmh, force = true)
                }

                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener)
                }
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, locationListener)
                }
                registerGnssCallback()
                centerSpeedSub.text = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) "CAUTARE SEMNAL GPS..." else "ACTIVATI GPS DIN SETARI"
            }
        } catch (e: Exception) {
            centerSpeedSub.text = "EROARE GPS"
        }
    }

    private fun registerGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        locationManager?.registerGnssStatusCallback(ContextCompat.getMainExecutor(this), gnssStatusCallback)
                    } else {
                        locationManager?.registerGnssStatusCallback(gnssStatusCallback, Handler(Looper.getMainLooper()))
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun unregisterGnssCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                locationManager?.unregisterGnssStatusCallback(gnssStatusCallback)
            } catch (_: Exception) {}
        }
    }

    private fun stopGpsUpdates() {
        try {
            unregisterGnssCallback()
            locationManager?.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startGpsUpdates()
            } else {
                centerSpeedSub.text = "PERMISIUNE REFUZATA"
            }
        } else if (requestCode == 2001) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                showFavoritesOverlay()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        enableImmersiveMode()
        clockHandler.post(clockRunnable)
        startGpsUpdates()
        if (!isPortraitOrientation() && mapLibreMap != null) {
            try { centerMapView.onStart() } catch (_: Exception) {}
        }

        try {
            val batteryStatusIntent = registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            batteryStatusIntent?.let { bIntent ->
                val level = bIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = bIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                if (level >= 0 && scale > 0) {
                    val pct = (level * 100) / scale
                    val prefix = if (isCharging) "⚡ " else "🔋 "
                    bottomBatteryIcon.text = "$prefix$pct%"
                }
            }
        } catch (_: Exception) {}

        val btFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        try {
            registerReceiver(bluetoothReceiver, btFilter)
        } catch (_: Exception) {}
        initBluetoothProfiles()
        checkBluetoothConnectionAndUpdateOrientation()

        WazeNotificationService.refresh()
        ExternalMediaManager.syncActiveSessions(this)

        WazeNavManager.listener = { navData ->
            runOnUiThread {
                updateWazeUI(navData)
            }
        }

        ExternalMediaManager.listener = { data ->
            runOnUiThread {
                if (isYouTubeWebMode) {
                    if (data.isPlaying && data.packageName.contains("youtube.music")) {
                        stopYouTubeWebPlayer()
                    } else {
                        return@runOnUiThread
                    }
                }
                centerMediaScrim.visibility = View.GONE
                centerMediaInfoContainer.visibility = View.GONE
                if (data.title.isNotEmpty()) {
                    if (data.artBitmap != null) {
                        centerMediaThumbnail.setImageBitmap(data.artBitmap)
                        centerMediaThumbnail.visibility = View.VISIBLE
                    } else {
                        centerMediaThumbnail.setImageResource(R.drawable.bmw_logo)
                        centerMediaThumbnail.visibility = View.VISIBLE
                    }
                    if (data.isPlaying) {
                        // Daca un player exterior ruleaza (YouTube sau YouTube Music), opreste radioul
                        controller?.pause()
                        if (currentMode == CenterMode.BMW) {
                            currentMode = CenterMode.MEDIA
                            updateCenterModeUI()
                        } else if (currentMode == CenterMode.MEDIA) {
                            updatePlayPauseIcon(true)
                        }
                    } else if (currentMode == CenterMode.MEDIA) {
                        updatePlayPauseIcon(false)
                    }
                } else if (!data.isPlaying && currentMode == CenterMode.MEDIA) {
                    updatePlayPauseIcon(false)
                }
                updateNowPlayingBar()
            }
        }

        val sessionToken = SessionToken(this, ComponentName(this, RadioService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            controller = controllerFuture?.get()
            setupController()
        }, MoreExecutors.directExecutor())
    }

    private fun setupController() {
        val ctrl = controller ?: return
        updateRadioUI(ctrl)

        if (isPortraitOrientation() || determineTargetOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            try {
                ctrl.pause()
                ctrl.stop()
                sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(packageName))
            } catch (_: Exception) {}
        } else {
            if (!ctrl.isPlaying && currentMode != CenterMode.MEDIA && !ExternalMediaManager.currentData.isPlaying) {
                ctrl.play()
            }
        }

        ctrl.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateRadioUI(ctrl)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateRadioUI(ctrl)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && (isPortraitOrientation() || determineTargetOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)) {
                    try {
                        ctrl.pause()
                        ctrl.stop()
                        sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(packageName))
                    } catch (_: Exception) {}
                }
                updateRadioUI(ctrl)
            }
        })
    }

    private fun updateRadioUI(ctrl: MediaController) {
        // Butonul de PLAY / PAUSE din bara de jos
        if (currentMode != CenterMode.MEDIA) {
            updatePlayPauseIcon(ctrl.isPlaying)
        }

        val artBytes = ctrl.mediaMetadata.artworkData
        if (artBytes != null) {
            val bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            if (bmp != null) {
                centerRadioArt.setPadding(0, 0, 0, 0)
                centerRadioArt.scaleType = ImageView.ScaleType.CENTER_CROP
                centerRadioArt.setImageBitmap(bmp)
            }
        } else {
            val pad = (42 * resources.displayMetrics.density).toInt()
            centerRadioArt.setPadding(pad, pad, pad, pad)
            centerRadioArt.scaleType = ImageView.ScaleType.FIT_CENTER
            centerRadioArt.setImageResource(R.drawable.ic_gauge_radio)
        }

        updateNowPlayingBar()
    }

    override fun onStop() {
        super.onStop()
        if (!isPortraitOrientation() && mapLibreMap != null) {
            try { centerMapView.onStop() } catch (_: Exception) {}
        }
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
        WazeNavManager.listener = null
        ExternalMediaManager.listener = null
        stopGpsUpdates()
        clockHandler.removeCallbacks(clockRunnable)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallIntent(intent)
        if (intent.getBooleanExtra("EXTRA_AUTO_CLOSE", false)) {
            closeLauncher()
            return
        }
        if (intent.getBooleanExtra("EXTRA_CAR_BT_CONNECTED", false)) {
            Log.i("MainActivity", "onNewIntent: EXTRA_CAR_BT_CONNECTED primit, trecere in mod masina si pornire radio")
            isCarConnectedState = true
            lastDisconnectTimestamp = 0L
            checkBluetoothConnectionAndUpdateOrientation()
            startRadioPlayback()
        }
        if (intent.getBooleanExtra("EXTRA_F12_RETURN", false)) {
            dismissAllOverlays()
        }
        if (intent.getBooleanExtra("FROM_YT_MUSIC_AUTO_RETURN", false)) {
            currentMode = CenterMode.MEDIA
            updateCenterModeUI()
            ExternalMediaManager.syncActiveSessions(this)
        }
        handleNotificationTestIntent(intent)
    }

    private fun handleNotificationTestIntent(intent: Intent?) {
        if (intent != null && intent.hasExtra("TEST_NOTIF_SENDER")) {
            val sender = intent.getStringExtra("TEST_NOTIF_SENDER") ?: "Contact"
            val text = intent.getStringExtra("TEST_NOTIF_TEXT") ?: "Salut! Cum merge drumul?"
            val isVoice = intent.getBooleanExtra("TEST_NOTIF_VOICE", false)
            val appName = intent.getStringExtra("TEST_NOTIF_APP") ?: "WhatsApp"
            val pkg = intent.getStringExtra("TEST_NOTIF_PKG") ?: "com.whatsapp"
            displayNotification(CarNotification(
                key = "test_${System.currentTimeMillis()}",
                packageName = pkg,
                appName = appName,
                sender = sender,
                message = text,
                timestamp = System.currentTimeMillis(),
                isVoiceMessage = isVoice,
                contentIntent = null,
                appIcon = null
            ))
        }
    }

    override fun onResume() {
        super.onResume()
        isInForeground = true
        currentTaskId = taskId
        ExternalMediaManager.cancelAutoReturn()
        if (isPortraitOrientation()) {
            stopAllAudioPlayers()
        } else {
            ExternalMediaManager.syncActiveSessions(this)
        }
        applyScreenBrightness(isNightMode)
        if (!isPortraitOrientation() && mapLibreMap != null) {
            try { centerMapView.onResume() } catch (_: Exception) {}
        }
        registerGnssCallback()
        focusPlayPauseButton()
        if (currentMode == CenterMode.ENERGY) {
            energyHandler.removeCallbacks(energyPollRunnable)
            energyPollRunnable.run()
        }

        // Reconectare / Abonare la notificări
        CarNotificationHub.onNotificationReceived = { notif ->
            displayNotification(notif)
            if (isPortraitOrientation()) {
                updateNetworkStatus()
            }
        }
        CarNotificationHub.onUnreadCountChanged = { _ ->
            if (isPortraitOrientation()) {
                updateNetworkStatus()
            }
        }
        CarNotificationHub.onNotificationsListChanged = { _ ->
            checkAndShowPendingNotifications()
        }

        // Asigură că serviciul NotificationListenerService este conectat la sistem și sincronizat
        if (CarNotificationListenerService.isNotificationAccessGranted(this)) {
            CarNotificationListenerService.rebindService(this)
            CarNotificationListenerService.instance?.syncActiveNotificationsCount()
            checkAndShowPendingNotifications()
            if (isPortraitOrientation()) {
                updateNetworkStatus()
            }
        }
    }

    fun openNotificationShade() {
        try {
            @Suppress("WrongConstant")
            val sbservice = getSystemService("statusbar")
            val statusbarManager = Class.forName("android.app.StatusBarManager")
            val expands = statusbarManager.getMethod("expandNotificationsPanel")
            expands.invoke(sbservice)
        } catch (e: Exception) {
            Log.e("MainActivity", "Error expanding notification panel: ${e.message}")
        }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        VoiceMessagePlayer.stop()
        energyHandler.removeCallbacks(energyPollRunnable)
        currentTaskId = taskId
        ExternalMediaManager.onLauncherPaused(this, taskId)
        if (!isPortraitOrientation() && mapLibreMap != null) {
            try { centerMapView.onPause() } catch (_: Exception) {}
        }
        unregisterGnssCallback()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!isPortraitOrientation() && mapLibreMap != null) {
            try { centerMapView.onSaveInstanceState(outState) } catch (_: Exception) {}
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!isPortraitOrientation() && mapLibreMap != null) {
            try { centerMapView.onLowMemory() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        VoiceMessagePlayer.stop()
        VoiceMessagePlayer.listener = null
        energyHandler.removeCallbacksAndMessages(null)
        if (!isPortraitOrientation() && mapLibreMap != null) {
            try { centerMapView.onDestroy() } catch (_: Exception) {}
        }
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        CarNotificationHub.onNotificationReceived = null
        CarNotificationHub.onUnreadCountChanged = null
        notifDismissHandler.removeCallbacksAndMessages(null)
        try {
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            a2dpProxy?.let { btAdapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
            headsetProxy?.let { btAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        } catch (_: Exception) {}
    }

    private fun initTextToSpeech() {
        try {
            textToSpeech = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val ro = Locale("ro", "RO")
                    val res = textToSpeech?.setLanguage(ro)
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        textToSpeech?.language = Locale.getDefault()
                    }
                    val audioAttributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                        .build()
                    textToSpeech?.setAudioAttributes(audioAttributes)
                    textToSpeech?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            runOnUiThread {
                                resumeOtherPlayersAfterVoiceMessage()
                                resetNotifDismissTimer()
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            runOnUiThread {
                                resumeOtherPlayersAfterVoiceMessage()
                                resetNotifDismissTimer()
                            }
                        }
                    })
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "TTS init exception: ${e.message}")
        }
    }

    private fun speakNotification(notif: CarNotification) {
        try {
            val textToSpeak = if (notif.isVoiceMessage) {
                "Mesaj vocal primit de la ${notif.sender}"
            } else if (notif.packageName.contains("gm", ignoreCase = true) || notif.appName.contains("Gmail", ignoreCase = true)) {
                "Email de la ${notif.sender}: ${notif.message}"
            } else if (notif.packageName.contains("homeassistant", ignoreCase = true) || notif.appName.contains("Home Assistant", ignoreCase = true)) {
                if (notif.sender.equals("Home Assistant", ignoreCase = true)) {
                    "Notificare Home Assistant: ${notif.message}"
                } else {
                    "Notificare Home Assistant, ${notif.sender}: ${notif.message}"
                }
            } else {
                "Mesaj de la ${notif.sender}: ${notif.message}"
            }
            pauseOtherPlayersForVoiceMessage()
            textToSpeech?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "CarNotifUtterance")
        } catch (e: Exception) {
            Log.e("MainActivity", "TTS speak exception: ${e.message}")
            resumeOtherPlayersAfterVoiceMessage()
        }
    }

    private fun setupNotifGestureDetector() {
        notifGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            private val SWIPE_THRESHOLD = 50
            private val SWIPE_VELOCITY_THRESHOLD = 50

            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                if (Math.abs(diffX) > Math.abs(diffY) && Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    isNotifSwipeHandled = true
                    if (diffX < 0) {
                        onNotificationSwiped(direction = -1)
                    } else {
                        onNotificationSwiped(direction = 1)
                    }
                    return true
                }
                return false
            }
        })
    }

    private fun onNotificationSwiped(direction: Int) {
        val current = activeNotification ?: return
        val unreadList = CarNotificationHub.unreadNotifications
        if (unreadList.isEmpty()) return

        val currentIndex = unreadList.indexOfFirst { it.key == current.key }
        val slideOutDistance = (viewCenterNotification.width.toFloat().takeIf { it > 0f } ?: 400f) * if (direction < 0) -1f else 1f

        viewCenterNotification.animate()
            .translationX(slideOutDistance)
            .alpha(0f)
            .setDuration(160)
            .withEndAction {
                try {
                    CarNotificationListenerService.instance?.cancelNotification(current.key)
                } catch (_: Exception) {}
                CarNotificationHub.removeNotification(current.key)

                val remaining = CarNotificationHub.unreadNotifications
                if (remaining.isEmpty()) {
                    viewCenterNotification.translationX = 0f
                    viewCenterNotification.alpha = 1f
                    currentMode = if (isPortraitOrientation()) CenterMode.BMW else preNotificationMode
                    updateCenterModeUI(animateFrom = CenterMode.NOTIFICATION)
                } else {
                    val nextNotif = if (currentIndex >= 0 && currentIndex < remaining.size) {
                        remaining[currentIndex]
                    } else {
                        remaining.first()
                    }
                    displayNotification(nextNotif)
                    viewCenterNotification.translationX = -slideOutDistance
                    viewCenterNotification.alpha = 0f
                    viewCenterNotification.animate()
                        .translationX(0f)
                        .alpha(1f)
                        .setDuration(180)
                        .start()
                }
            }
            .start()
    }

    private fun checkAndShowPendingNotifications() {
        runOnUiThread {
            val unread = CarNotificationHub.unreadNotifications
            if (currentMode == CenterMode.NOTIFICATION) {
                if (unread.isEmpty()) {
                    currentMode = if (isPortraitOrientation()) CenterMode.BMW else preNotificationMode
                    updateCenterModeUI(animateFrom = CenterMode.NOTIFICATION)
                } else {
                    val currentKey = activeNotification?.key
                    val item = unread.firstOrNull { it.key == currentKey } ?: unread.first()
                    displayNotification(item)
                }
            }
        }
    }

    private fun displayNotification(notif: CarNotification) {
        activeNotification = notif
        VoiceMessagePlayer.stop()
        runOnUiThread {
            if (currentMode != CenterMode.NOTIFICATION) {
                preNotificationMode = currentMode
                currentMode = CenterMode.NOTIFICATION
                updateCenterModeUI(animateFrom = preNotificationMode)
            } else {
                updateCenterModeUI()
            }

            val unreadList = CarNotificationHub.unreadNotifications
            val total = unreadList.size
            val currentIndex = unreadList.indexOfFirst { it.key == notif.key }
            if (total > 1 && currentIndex >= 0) {
                tvNotifCounter?.visibility = View.VISIBLE
                tvNotifCounter?.text = "${currentIndex + 1} / $total"
            } else {
                tvNotifCounter?.visibility = View.GONE
            }

            // Pictograma aplicatiei
            if (notif.appIcon != null) {
                ivNotifAppIcon.setImageDrawable(notif.appIcon)
            } else {
                when {
                    notif.packageName.contains("whatsapp", ignoreCase = true) -> {
                        ivNotifAppIcon.setImageResource(R.drawable.ic_mask_whatsapp)
                    }
                    notif.packageName.contains("gm", ignoreCase = true) || notif.packageName.contains("mail", ignoreCase = true) -> {
                        ivNotifAppIcon.setImageResource(R.drawable.ic_email)
                    }
                    notif.packageName.contains("homeassistant", ignoreCase = true) -> {
                        ivNotifAppIcon.setImageResource(R.drawable.ic_energy_home)
                    }
                    else -> {
                        ivNotifAppIcon.setImageResource(R.drawable.ic_sms)
                    }
                }
            }

            tvNotifAppName.text = notif.appName.uppercase(Locale.getDefault())
            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(notif.timestamp))
            tvNotifTime.text = timeStr
            tvNotifSender.text = notif.sender

            if (notif.isVoiceMessage) {
                layoutNotifVoiceBadge.visibility = View.VISIBLE
                tvNotifVoiceLabel.text = "MESAJ VOCAL"
                tvNotifText.text = if (notif.message.isNotBlank()) notif.message else "Atinge Redare pentru a asculta"
                ivNotifActionIcon.setImageResource(R.drawable.ic_media_play)
                tvNotifActionText.text = "REDARE"
                btnNotifSpeak.visibility = View.GONE
            } else {
                layoutNotifVoiceBadge.visibility = View.GONE
                tvNotifText.text = notif.message
                ivNotifActionIcon.setImageResource(R.drawable.ic_media_play)
                tvNotifActionText.text = "DESCHIDE"
                btnNotifSpeak.visibility = View.VISIBLE
            }

            // Permite focalizarea copiilor din interiorul cercului central
            centerCircle.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

            // In modul masina -> auto-dismiss dupa 20s; in modul portret -> persistent cat timp sunt notificari in bara
            resetNotifDismissTimer()

            // Focalizare automata pe butonul de actiune principala
            btnNotifAction.isFocusable = true
            btnNotifAction.isFocusableInTouchMode = true
            btnNotifAction.post {
                btnNotifAction.requestFocus()
            }
        }
    }

    private fun resetNotifDismissTimer() {
        notifDismissHandler.removeCallbacks(notifDismissRunnable)
        if (!isPortraitOrientation()) {
            if (currentMode == CenterMode.NOTIFICATION) {
                if (!VoiceMessagePlayer.isPlaying() && textToSpeech?.isSpeaking != true) {
                    notifDismissHandler.postDelayed(notifDismissRunnable, 20000L)
                }
            }
        }
    }

    private fun dismissNotification() {
        notifDismissHandler.removeCallbacks(notifDismissRunnable)
        if (VoiceMessagePlayer.isPlaying() || VoiceMessagePlayer.getCurrentFile() != null) {
            VoiceMessagePlayer.stop()
            resumeOtherPlayersAfterVoiceMessage()
        }
        centerCircle.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        if (!isPortraitOrientation()) {
            if (currentMode == CenterMode.NOTIFICATION) {
                currentMode = preNotificationMode
                updateCenterModeUI(animateFrom = CenterMode.NOTIFICATION)
            }
            return
        }

        activeNotification?.let { notif ->
            try {
                CarNotificationListenerService.instance?.cancelNotification(notif.key)
            } catch (_: Exception) {}
            CarNotificationHub.removeNotification(notif.key)
        }
        val remaining = CarNotificationHub.unreadNotifications
        if (remaining.isNotEmpty()) {
            displayNotification(remaining.first())
        } else {
            if (currentMode == CenterMode.NOTIFICATION) {
                currentMode = CenterMode.BMW
                updateCenterModeUI(animateFrom = CenterMode.NOTIFICATION)
            }
        }
    }

    private fun pauseOtherPlayersForVoiceMessage() {
        wasRadioPlayingBeforeVoice = controller?.isPlaying == true
        if (wasRadioPlayingBeforeVoice) {
            controller?.pause()
            Log.d("MainActivity", "Radio intern oprit temporar pentru mesaj vocal/audio")
        }

        wasYouTubePlayingBeforeVoice = isYouTubeWebMode && isYouTubeWebPlaying
        if (wasYouTubePlayingBeforeVoice) {
            youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = true; var v = document.querySelector('video'); if (v) v.pause();", null)
            isYouTubeWebPlaying = false
            updatePlayPauseIcon(false)
            Log.d("MainActivity", "YouTube oprit temporar pentru mesaj vocal/audio")
        }

        wasExternalMediaPlayingBeforeVoice = ExternalMediaManager.currentData.isPlaying
        if (wasExternalMediaPlayingBeforeVoice) {
            try {
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.pause()
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Eroare oprire media externa: ${e.message}")
            }
            Log.d("MainActivity", "Media externa oprita temporar pentru mesaj vocal/audio")
        }
    }

    private fun resumeOtherPlayersAfterVoiceMessage() {
        if (isPortraitOrientation() || determineTargetOrientation() == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            wasRadioPlayingBeforeVoice = false
            wasYouTubePlayingBeforeVoice = false
            wasExternalMediaPlayingBeforeVoice = false
            stopAllAudioPlayers()
            return
        }
        if (wasRadioPlayingBeforeVoice) {
            wasRadioPlayingBeforeVoice = false
            controller?.play()
            Log.d("MainActivity", "Radio intern reluat dupa mesaj vocal")
        } else if (wasYouTubePlayingBeforeVoice) {
            wasYouTubePlayingBeforeVoice = false
            youtubeWebView?.evaluateJavascript("window.__bmwUserPaused = false; var v = document.querySelector('video'); if (v) v.play();", null)
            isYouTubeWebPlaying = true
            updatePlayPauseIcon(true)
            Log.d("MainActivity", "YouTube reluat dupa mesaj vocal")
        } else if (wasExternalMediaPlayingBeforeVoice) {
            wasExternalMediaPlayingBeforeVoice = false
            try {
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.play()
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Eroare reluare media externa: ${e.message}")
            }
            Log.d("MainActivity", "Media externa reluata dupa mesaj vocal")
        }
    }

    private fun playVoiceMessageOrFallback(notif: CarNotification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(this, "Permite accesul la stocare pentru redarea mesajelor vocale", Toast.LENGTH_LONG).show()
                return
            } catch (_: Exception) {}
        }

        val voiceFile = VoiceMessagePlayer.findLatestVoiceNote(notif.packageName, notif.timestamp)
        if (voiceFile != null && voiceFile.exists()) {
            VoiceMessagePlayer.play(voiceFile)
        } else if (notif.playActionIntent != null) {
            try {
                val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    try {
                        ActivityOptions.makeBasic().apply {
                            setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        }.toBundle()
                    } catch (_: Exception) {
                        ActivityOptions.makeBasic().toBundle()
                    }
                } else {
                    ActivityOptions.makeBasic().toBundle()
                }
                notif.playActionIntent.send(this, 0, null, null, null, null, optionsBundle)
            } catch (e: Exception) {
                Log.e("MainActivity", "Eroare playActionIntent: ${e.message}")
                openNotification(notif)
            }
        } else {
            Toast.makeText(this, "Deschidere conversație...", Toast.LENGTH_SHORT).show()
            openNotification(notif)
        }
    }

    private fun openNotification(notif: CarNotification) {
        Log.i("MainActivity", "Deschidere notificare pentru: ${notif.packageName}, app=${notif.appName}")
        dismissNotification()

        val optionsBundle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }.toBundle()
            } catch (e: Exception) {
                Log.w("MainActivity", "Eroare setPendingIntentBackgroundActivityStartMode: ${e.message}")
                ActivityOptions.makeBasic().toBundle()
            }
        } else {
            ActivityOptions.makeBasic().toBundle()
        }

        var intentSenderOpened = false
        if (notif.contentIntent != null) {
            try {
                startIntentSender(
                    notif.contentIntent.intentSender,
                    null,
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                    Intent.FLAG_ACTIVITY_NEW_TASK,
                    0,
                    optionsBundle
                )
                intentSenderOpened = true
                Log.i("MainActivity", "startIntentSender executat cu succes pentru ${notif.packageName}")
            } catch (e: Exception) {
                Log.w("MainActivity", "startIntentSender a esuat (${e.message}), se incearca direct send()...")
                try {
                    notif.contentIntent.send(this, 0, null, null, null, null, optionsBundle)
                    intentSenderOpened = true
                    Log.i("MainActivity", "contentIntent.send executat cu succes pentru ${notif.packageName}")
                } catch (e2: Exception) {
                    Log.e("MainActivity", "contentIntent.send a esuat: ${e2.message}")
                }
            }
        }

        // Daca nu a reusit prin PendingIntent sau e null (ex. com.android.shell sau PendingIntent anulat), lansam direct pachetul aplicatiei
        if (!intentSenderOpened) {
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage(notif.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    startActivity(launchIntent)
                    Log.i("MainActivity", "Lansat cu succes prin launchIntent: ${notif.packageName}")
                } else {
                    Log.w("MainActivity", "Nu s-a gasit launch intent pentru: ${notif.packageName}")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Eroare la deschiderea fallback a aplicatiei ${notif.packageName}: ${e.message}")
            }
        }
    }

    private fun fetchAndDisplayEnergyData() {
        HomeAssistantManager.fetchEnergyData(this) { data ->
            if (!isInForeground || currentMode != CenterMode.ENERGY) return@fetchEnergyData

            // 1. STÂNGA: Solar & Consum Casă
            val solarText = if (data.solarPower >= 1000f) {
                String.format(java.util.Locale.US, "%.1f kW", data.solarPower / 1000f)
            } else {
                "${data.solarPower.toInt()} W"
            }
            tvEnergySolarPower.text = solarText

            val houseText = if (data.houseLoad >= 1000f) {
                String.format(java.util.Locale.US, "%.1f kW", data.houseLoad / 1000f)
            } else {
                "${data.houseLoad.toInt()} W"
            }
            tvEnergyHouseLoad.text = houseText

            // 2. CENTRU: SOC Baterie
            tvEnergyBatterySoc.text = "${data.batterySoc}%"
            val isCharging = data.batteryPower > 20f
            val isDischarging = data.batteryPower < -20f
            tvEnergyBatteryStatus.text = when {
                isCharging -> "⚡ ÎNCĂRCARE"
                isDischarging -> "🔋 DESCĂRCARE"
                else -> "STANDBY"
            }
            tvEnergyBatteryStatus.setTextColor(when {
                isCharging -> android.graphics.Color.parseColor("#4CAF50")
                isDischarging -> android.graphics.Color.parseColor("#FFA033")
                else -> android.graphics.Color.parseColor("#8E929B")
            })

            // 3. DREAPTA: Tensiune Baterie & Putere Baterie
            tvEnergyBatteryVoltage.text = String.format(java.util.Locale.US, "%.1f V", data.batteryVoltage)

            val batPowerAbs = kotlin.math.abs(data.batteryPower)
            val batPowerStr = if (batPowerAbs >= 1000f) {
                String.format(java.util.Locale.US, "%.1f kW", data.batteryPower / 1000f)
            } else {
                "${data.batteryPower.toInt()} W"
            }
            tvEnergyBatteryPower.text = batPowerStr
        }
    }

    private fun setNightMode(night: Boolean, showToast: Boolean = true) {
        isNightMode = night

        // 1. Schimba pictograma butonului intre noapte (luna) si zi (soare)
        btnNightMode.setImageResource(if (night) R.drawable.ic_moon else R.drawable.ic_sun)

        // 2. Controleaza luminozitatea display-ului: 10% pe Night, 90% pe Day
        applyScreenBrightness(night)

        // 3. Controleaza stilul vectorial al hartii MapLibre 3D: Carto Dark Matter vs Positron
        applyMapNightMode(night)

        // 4. Salveaza starea pentru relansari viitoare
        getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("night_mode", night)
            .apply()

        if (showToast) {
            val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            val percent = if (night) {
                prefs.getInt("night_brightness", 10).coerceIn(5, 100)
            } else {
                prefs.getInt("day_brightness", 90).coerceIn(5, 100)
            }
            val msg = if (night) "Mod Nocturn Activ • Luminozitate $percent%" else "Mod Zi Activ • Luminozitate $percent%"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyScreenBrightness(night: Boolean) {
        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        val dayPercent = prefs.getInt("day_brightness", 90).coerceIn(5, 100)
        val nightPercent = prefs.getInt("night_brightness", 10).coerceIn(5, 100)
        val percent = if (night) nightPercent else dayPercent

        val lp = window.attributes
        lp.screenBrightness = (percent / 100f).coerceIn(0.05f, 1.0f)
        window.attributes = lp
    }

    private fun applyMapNightMode(night: Boolean) {
        val map = mapLibreMap ?: return

        val styleUrl = if (night) {
            "https://basemaps.cartocdn.com/gl/dark-matter-gl-style/style.json"
        } else {
            "https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"
        }

        map.setStyle(styleUrl) { style ->
            enableLocationComponent(style)
            val loc = lastKnownLocation ?: getBestLastKnownLocation()
            if (loc != null) {
                lastKnownLocation = loc
                val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
                updateMapLibreCamera(loc, speedKmh, force = true)
            } else {
                val defaultPos = CameraPosition.Builder()
                    .target(LatLng(45.0608, 25.2606))
                    .zoom(16.5)
                    .tilt(35.0)
                    .build()
                map.cameraPosition = defaultPos
            }
        }
    }

    private fun adjustVolume(direction: Int) {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI or AudioManager.FLAG_PLAY_SOUND
            )
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare reglare volum: ${e.message}")
        }
    }

    private val focusOrderViews: List<View> by lazy {
        listOf(
            btnLeft1,        // 1. Radio
            btnLeft2,        // 2. Navigatie
            btnLeft3,        // 3. Music
            btnVolumeDown,   // 4. Volum - (Cercul 3)
            btnPrevTrack,    // 5. Prev
            btnPlayPause,    // 6. Play
            btnNextTrack,    // 7. Next
            btnVolumeUp,     // 8. Volum + (Cercul 4)
            btnRight3,       // 9. Aplicatii
            btnRight2,       // 10. Whatsapp
            btnRight1,       // 11. Telefon
            centerCircle,    // 12. Cerc Central
            btnNightMode,    // 13. Day/Night
            btnQuickSettings // 14. Setari
        )
    }

    private fun getActiveSecondaryOverlay(): ViewGroup? {
        if (::callOverlayContainer.isInitialized && callOverlayContainer.visibility == View.VISIBLE) return callOverlayContainer
        if (::favoritesOverlayContainer.isInitialized && favoritesOverlayContainer.visibility == View.VISIBLE) return favoritesOverlayContainer
        if (::radioOverlayContainer.isInitialized && radioOverlayContainer.visibility == View.VISIBLE) return radioOverlayContainer
        if (::musicOverlayContainer.isInitialized && musicOverlayContainer.visibility == View.VISIBLE) return musicOverlayContainer
        if (::appsOverlayContainer.isInitialized && appsOverlayContainer.visibility == View.VISIBLE) return appsOverlayContainer
        if (::settingsOverlayContainer.isInitialized && settingsOverlayContainer.visibility == View.VISIBLE) return settingsOverlayContainer
        if (::viewCenterNotification.isInitialized && viewCenterNotification.visibility == View.VISIBLE && currentMode == CenterMode.NOTIFICATION) {
            return viewCenterNotification
        }
        return null
    }

    private fun isViewDescendantOf(child: View, parent: View): Boolean {
        var v: ViewParent? = child.parent
        while (v != null) {
            if (v == parent) return true
            v = v.parent
        }
        return false
    }

    private fun getOverlayFocusableViews(overlay: ViewGroup): List<View> {
        val views = mutableListOf<View>()
        when (overlay) {
            viewCenterNotification -> {
                if (::btnNotifAction.isInitialized && btnNotifAction.visibility == View.VISIBLE) views.add(btnNotifAction)
                if (::btnNotifSpeak.isInitialized && btnNotifSpeak.visibility == View.VISIBLE) views.add(btnNotifSpeak)
                if (::btnNotifClose.isInitialized && btnNotifClose.visibility == View.VISIBLE) views.add(btnNotifClose)
            }
            favoritesOverlayContainer -> {
                if (::btnCloseFavorites.isInitialized && btnCloseFavorites.visibility == View.VISIBLE) views.add(btnCloseFavorites)
                if (::btnOpenDialer.isInitialized && btnOpenDialer.visibility == View.VISIBLE) views.add(btnOpenDialer)
                if (::listFavoriteContacts.isInitialized && listFavoriteContacts.visibility == View.VISIBLE) {
                    for (i in 0 until listFavoriteContacts.childCount) {
                        val child = listFavoriteContacts.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            radioOverlayContainer -> {
                if (::btnCloseRadio.isInitialized && btnCloseRadio.visibility == View.VISIBLE) views.add(btnCloseRadio)
                if (::btnAddStation.isInitialized && btnAddStation.visibility == View.VISIBLE) views.add(btnAddStation)
                if (::listRadioStations.isInitialized && listRadioStations.visibility == View.VISIBLE) {
                    for (i in 0 until listRadioStations.childCount) {
                        val child = listRadioStations.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            musicOverlayContainer -> {
                if (::btnCloseMusic.isInitialized && btnCloseMusic.visibility == View.VISIBLE) views.add(btnCloseMusic)
                if (::listMusicApps.isInitialized && listMusicApps.visibility == View.VISIBLE) {
                    for (i in 0 until listMusicApps.childCount) {
                        val child = listMusicApps.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            appsOverlayContainer -> {
                if (::btnCloseApps.isInitialized && btnCloseApps.visibility == View.VISIBLE) views.add(btnCloseApps)
                if (::gridAllApps.isInitialized && gridAllApps.visibility == View.VISIBLE) {
                    for (i in 0 until gridAllApps.childCount) {
                        val child = gridAllApps.getChildAt(i)
                        if (child.visibility == View.VISIBLE) views.add(child)
                    }
                }
            }
            settingsOverlayContainer -> {
                if (::btnCloseSettings.isInitialized && btnCloseSettings.visibility == View.VISIBLE) views.add(btnCloseSettings)
                if (::seekDayBrightness.isInitialized && seekDayBrightness.visibility == View.VISIBLE) views.add(seekDayBrightness)
                if (::seekNightBrightness.isInitialized && seekNightBrightness.visibility == View.VISIBLE) views.add(seekNightBrightness)
                if (::btnOpenNotifSettings.isInitialized && btnOpenNotifSettings.visibility == View.VISIBLE) views.add(btnOpenNotifSettings)
                if (::btnCarBtSelect.isInitialized && btnCarBtSelect.visibility == View.VISIBLE) views.add(btnCarBtSelect)
                if (::btnToggleForceDisplayMode.isInitialized && btnToggleForceDisplayMode.visibility == View.VISIBLE) views.add(btnToggleForceDisplayMode)
            }
            callOverlayContainer -> {
                val ringing = findViewById<View>(R.id.call_ringing_actions)
                val deny = findViewById<View>(R.id.btn_call_deny)
                val answer = findViewById<View>(R.id.btn_call_answer)
                val hangup = findViewById<View>(R.id.btn_call_hangup)
                if (ringing?.visibility == View.VISIBLE) {
                    if (deny?.visibility == View.VISIBLE) views.add(deny)
                    if (answer?.visibility == View.VISIBLE) views.add(answer)
                } else if (hangup?.visibility == View.VISIBLE) {
                    views.add(hangup)
                }
            }
        }
        return views
    }

    private fun handleOverlayTabNavigation(overlay: ViewGroup, isShift: Boolean): Boolean {
        val listView: AdapterView<*>? = when (overlay) {
            favoritesOverlayContainer -> if (::listFavoriteContacts.isInitialized) listFavoriteContacts else null
            radioOverlayContainer -> if (::listRadioStations.isInitialized) listRadioStations else null
            musicOverlayContainer -> if (::listMusicApps.isInitialized) listMusicApps else null
            appsOverlayContainer -> if (::gridAllApps.isInitialized) gridAllApps else null
            else -> null
        }

        val current = currentFocus

        // Daca suntem pe ultimul copil vizibil din lista si mai sunt elemente in jos, derulam lista
        if (listView != null && listView.visibility == View.VISIBLE && listView.childCount > 0) {
            val lastChild = listView.getChildAt(listView.childCount - 1)
            val firstChild = listView.getChildAt(0)

            if (!isShift && (current == lastChild || (current != null && isViewDescendantOf(current, lastChild)))) {
                val adapterCount = listView.count
                val lastPos = listView.lastVisiblePosition
                if (lastPos < adapterCount - 1) {
                    val nextPos = (lastPos + 1).coerceAtMost(adapterCount - 1)
                    if (listView is ListView) {
                        listView.setSelection(nextPos)
                    } else if (listView is GridView) {
                        listView.setSelection(nextPos)
                    }
                    listView.post {
                        val newLast = listView.getChildAt(listView.childCount - 1)
                        newLast?.isFocusable = true
                        newLast?.isFocusableInTouchMode = true
                        newLast?.requestFocus()
                    }
                    return true
                }
            } else if (isShift && (current == firstChild || (current != null && isViewDescendantOf(current, firstChild)))) {
                val firstPos = listView.firstVisiblePosition
                if (firstPos > 0) {
                    val prevPos = (firstPos - 1).coerceAtLeast(0)
                    if (listView is ListView) {
                        listView.setSelection(prevPos)
                    } else if (listView is GridView) {
                        listView.setSelection(prevPos)
                    }
                    listView.post {
                        val newFirst = listView.getChildAt(0)
                        newFirst?.isFocusable = true
                        newFirst?.isFocusableInTouchMode = true
                        newFirst?.requestFocus()
                    }
                    return true
                }
            }
        }

        val focusableViews = getOverlayFocusableViews(overlay)
        Log.d("MainActivity", "handleOverlayTabNavigation: overlay=$overlay, focusableCount=${focusableViews.size}, current=$current")
        if (focusableViews.isEmpty()) return true

        val currentIndex = focusableViews.indexOfFirst { it == current || (current != null && isViewDescendantOf(current, it)) }

        val nextIndex = if (currentIndex < 0) {
            if (isShift) focusableViews.size - 1 else 0
        } else if (isShift) {
            if (currentIndex <= 0) focusableViews.size - 1 else currentIndex - 1
        } else {
            if (currentIndex >= focusableViews.size - 1) 0 else currentIndex + 1
        }

        val target = focusableViews[nextIndex]
        Log.d("MainActivity", "handleOverlayTabNavigation: currentIndex=$currentIndex, nextIndex=$nextIndex, target=$target")
        target.isFocusable = true
        target.isFocusableInTouchMode = true
        val reqRes = target.requestFocus()
        Log.d("MainActivity", "handleOverlayTabNavigation: requestFocus result=$reqRes, hasFocus=${target.hasFocus()}")
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev != null) {
            if (ev.action == MotionEvent.ACTION_DOWN) {
                resetNotifDismissTimer()
                isNotifSwipeHandled = false
            }
            if (currentMode == CenterMode.NOTIFICATION && ::centerCircle.isInitialized) {
                val location = IntArray(2)
                centerCircle.getLocationOnScreen(location)
                val x = ev.rawX
                val y = ev.rawY
                if (x >= location[0] && x <= location[0] + centerCircle.width &&
                    y >= location[1] && y <= location[1] + centerCircle.height) {
                    notifGestureDetector?.onTouchEvent(ev)
                    if (isNotifSwipeHandled) {
                        val cancelEvent = MotionEvent.obtain(ev)
                        cancelEvent.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancelEvent)
                        cancelEvent.recycle()
                        return true
                    }
                }
            }
            if (isPortraitOrientation()) {
                portraitGestureDetector?.onTouchEvent(ev)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onBackPressed() {
        if (getActiveSecondaryOverlay() != null) {
            dismissAllOverlays()
            return
        }
        if (currentMode == CenterMode.NOTIFICATION) {
            currentMode = if (isPortraitOrientation()) CenterMode.BMW else preNotificationMode
            updateCenterModeUI(animateFrom = CenterMode.NOTIFICATION)
            return
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            resetNotifDismissTimer()
        }
        // Tasta F12 de pe tastatura Bluetooth revine direct la launcher și închide ferestrele secundare
        if (event.keyCode == KeyEvent.KEYCODE_F12) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Log.i("MainActivity", "F12 apasat! Revenire la ecranul principal BMW...")
                dismissAllOverlays()
                if (currentMode != CenterMode.BMW) {
                    val prev = currentMode
                    currentMode = CenterMode.BMW
                    updateCenterModeUI(animateFrom = prev)
                }
            }
            return true
        }

        val activeOverlay = getActiveSecondaryOverlay()

        if (event.keyCode == KeyEvent.KEYCODE_TAB && event.action == KeyEvent.ACTION_DOWN) {
            if (activeOverlay != null) {
                return handleOverlayTabNavigation(activeOverlay, event.isShiftPressed)
            }

            val isShift = event.isShiftPressed
            val currentFocusView = currentFocus
            val currentIndex = focusOrderViews.indexOfFirst { it == currentFocusView || it.hasFocus() }

            val nextIndex = if (currentIndex < 0) {
                // Dacă nu e focalizat niciun buton, pornim de la 0 (Radio) sau ultimul
                if (isShift) focusOrderViews.size - 1 else 0
            } else if (isShift) {
                if (currentIndex <= 0) focusOrderViews.size - 1 else currentIndex - 1
            } else {
                if (currentIndex >= focusOrderViews.size - 1) 0 else currentIndex + 1
            }

            val target = focusOrderViews[nextIndex]
            target.isFocusable = true
            target.isFocusableInTouchMode = true
            target.requestFocus()
            return true
        }

        // Actionare cu Enter / DPad Center pentru oricare element focalizat din launcher
        if ((event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER)
            && event.action == KeyEvent.ACTION_DOWN
        ) {
            if (activeOverlay != null) {
                val focused = currentFocus
                if (focused != null && isViewDescendantOf(focused, activeOverlay)) {
                    val clickTarget = if (focused.hasOnClickListeners()) focused else {
                        var p: ViewParent? = focused.parent
                        var target: View? = null
                        while (p != null && p != activeOverlay) {
                            if (p is View && p.hasOnClickListeners()) {
                                target = p
                                break
                            }
                            p = p.parent
                        }
                        target ?: focused
                    }
                    clickTarget.performClick()
                    return true
                }
            }

            val focused = currentFocus
            if (focused != null && (focused in focusOrderViews || focusOrderViews.any { it.hasFocus() })) {
                val target = focusOrderViews.firstOrNull { it == focused || it.hasFocus() } ?: focused
                target.performClick()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    fun dismissAllOverlays() {
        if (::favoritesOverlayContainer.isInitialized && favoritesOverlayContainer.visibility == View.VISIBLE) {
            favoritesOverlayContainer.visibility = View.GONE
        }
        if (::radioOverlayContainer.isInitialized && radioOverlayContainer.visibility == View.VISIBLE) {
            radioOverlayContainer.visibility = View.GONE
        }
        if (::musicOverlayContainer.isInitialized && musicOverlayContainer.visibility == View.VISIBLE) {
            musicOverlayContainer.visibility = View.GONE
        }
        if (::appsOverlayContainer.isInitialized && appsOverlayContainer.visibility == View.VISIBLE) {
            appsOverlayContainer.visibility = View.GONE
        }
        if (::settingsOverlayContainer.isInitialized && settingsOverlayContainer.visibility == View.VISIBLE) {
            settingsOverlayContainer.visibility = View.GONE
        }
        if (currentMode == CenterMode.NOTIFICATION) {
            dismissNotification()
        }
    }

    private fun focusPlayPauseButton() {
        // Dezactivat conform solicitării utilizatorului: focusul nu se mai mută automat pe butonul play/pause
    }

    private fun View.enableInstantClickOnTouch() {
        var isLongClicked = false
        var downX = 0f
        var downY = 0f
        val longPressHandler = Handler(Looper.getMainLooper())
        val longPressRunnable = Runnable {
            if (isPressed) {
                isLongClicked = performLongClick()
            }
        }

        setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isLongClicked = false
                    downX = event.x
                    downY = event.y
                    v.isPressed = true
                    longPressHandler.postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    if (Math.abs(event.x - downX) > slop || Math.abs(event.y - downY) > slop) {
                        longPressHandler.removeCallbacks(longPressRunnable)
                        v.isPressed = false
                    }
                    false
                }
                MotionEvent.ACTION_UP -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    val slop = ViewConfiguration.get(v.context).scaledTouchSlop
                    if (!isLongClicked && event.x >= -slop && event.x <= v.width + slop &&
                        event.y >= -slop && event.y <= v.height + slop) {
                        v.performClick()
                        return@setOnTouchListener true
                    }
                    false
                }
                MotionEvent.ACTION_CANCEL -> {
                    longPressHandler.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    isLongClicked = false
                    false
                }
                else -> false
            }
        }
    }

    fun showIncomingCallUI(number: String?, name: String?, customBitmap: Bitmap? = null) {
        val contactInfo = if (customBitmap != null) {
            Pair(name, customBitmap)
        } else if (number != null) {
            getContactInfo(this, number)
        } else {
            Pair(name, null)
        }
        val displayName = name ?: contactInfo.first ?: number ?: "Număr Necunoscut"
        val displayPhone = number ?: ""

        callContactName.text = displayName
        callPhoneNumber.text = displayPhone

        if (contactInfo.second != null) {
            callContactPhoto.setPadding(0, 0, 0, 0)
            try {
                val circularBitmap = getCircularBitmap(contactInfo.second!!)
                callContactPhoto.setImageBitmap(circularBitmap)
            } catch (e: Exception) {
                callContactPhoto.setImageBitmap(contactInfo.second)
            }
            callContactPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val pad = (14 * resources.displayMetrics.density).toInt()
            callContactPhoto.setPadding(pad, pad, pad, pad)
            callContactPhoto.setImageResource(R.drawable.ic_call_avatar)
            callContactPhoto.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        callStatusTitle.text = "⚡ APEL DE INTRARE"
        callDurationTimer.visibility = View.GONE
        callRingingActions.visibility = View.VISIBLE
        btnCallHangup.visibility = View.GONE
        callOverlayContainer.visibility = View.VISIBLE

        btnCallAnswer.requestFocus()
    }

    fun showActiveCallUI() {
        callStatusTitle.text = "● APEL ÎN DESFĂȘURARE"
        callRingingActions.visibility = View.GONE
        btnCallHangup.visibility = View.VISIBLE
        callDurationTimer.visibility = View.VISIBLE

        callDurationSeconds = 0
        callDurationTimer.text = "00:00"
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callTimerHandler.postDelayed(callTimerRunnable, 1000)

        callOverlayContainer.visibility = View.VISIBLE
        btnCallHangup.requestFocus()
    }

    fun dismissCallUI() {
        callTimerHandler.removeCallbacks(callTimerRunnable)
        callOverlayContainer.visibility = View.GONE
        focusPlayPauseButton()
    }

    fun showOutgoingCallUI(number: String, name: String, photo: Bitmap?) {
        callContactName.text = name
        callPhoneNumber.text = number

        if (photo != null) {
            callContactPhoto.setPadding(0, 0, 0, 0)
            try {
                val circularBitmap = getCircularBitmap(photo)
                callContactPhoto.setImageBitmap(circularBitmap)
            } catch (e: Exception) {
                callContactPhoto.setImageBitmap(photo)
            }
            callContactPhoto.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            val pad = (14 * resources.displayMetrics.density).toInt()
            callContactPhoto.setPadding(pad, pad, pad, pad)
            callContactPhoto.setImageResource(R.drawable.ic_call_avatar)
            callContactPhoto.scaleType = ImageView.ScaleType.FIT_CENTER
        }

        callStatusTitle.text = "● APELARE ÎN CURS..."
        callDurationTimer.visibility = View.GONE
        callRingingActions.visibility = View.GONE
        btnCallHangup.visibility = View.VISIBLE
        callOverlayContainer.visibility = View.VISIBLE

        btnCallHangup.requestFocus()
    }

    private fun showFavoritesOverlay() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.CALL_PHONE),
                2001
            )
            return
        }

        // Sugeram setarea ca Default Phone App daca nu este deja setat, pentru a elimina complet dialerul nativ
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(roleIntent, 2002)
                } catch (_: Exception) {}
            }
        }

        val favorites = loadFavoriteContacts()
        if (favorites.isEmpty()) {
            tvEmptyFavorites.visibility = View.VISIBLE
            listFavoriteContacts.visibility = View.GONE
        } else {
            tvEmptyFavorites.visibility = View.GONE
            listFavoriteContacts.visibility = View.VISIBLE

            listFavoriteContacts.adapter = object : BaseAdapter() {
                override fun getCount(): Int = favorites.size
                override fun getItem(position: Int): Any = favorites[position]
                override fun getItemId(position: Int): Long = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.item_favorite_contact, parent, false)
                    view.isFocusable = true
                    view.isFocusableInTouchMode = false
                    view.enableInstantClickOnTouch()
                    val contact = favorites[position]

                    val photoFrame = view.findViewById<FrameLayout>(R.id.fav_contact_photo_frame)
                    photoFrame.outlineProvider = circleOutlineProvider
                    photoFrame.clipToOutline = true

                    val photoView = view.findViewById<ImageView>(R.id.fav_contact_photo)
                    photoView.outlineProvider = circleOutlineProvider
                    photoView.clipToOutline = true

                    val nameView = view.findViewById<TextView>(R.id.fav_contact_name)
                    val phoneView = view.findViewById<TextView>(R.id.fav_contact_phone)

                    nameView.text = contact.name
                    phoneView.text = contact.number

                    if (contact.photo != null) {
                        photoView.setPadding(0, 0, 0, 0)
                        try {
                            photoView.setImageBitmap(getCircularBitmap(contact.photo))
                        } catch (_: Exception) {
                            photoView.setImageBitmap(contact.photo)
                        }
                        photoView.scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        val pad = (10 * resources.displayMetrics.density).toInt()
                        photoView.setPadding(pad, pad, pad, pad)
                        photoView.setImageResource(R.drawable.ic_call_avatar)
                        photoView.scaleType = ImageView.ScaleType.FIT_CENTER
                    }

                    view.setOnClickListener {
                        startCallToContact(contact)
                    }

                    return view
                }
            }
        }

        favoritesOverlayContainer.visibility = View.VISIBLE
        btnCloseFavorites.isFocusable = true
        btnCloseFavorites.isFocusableInTouchMode = false
        btnCloseFavorites.enableInstantClickOnTouch()
    }

    private fun startCallToContact(contact: FavoriteContact) {
        favoritesOverlayContainer.visibility = View.GONE
        showOutgoingCallUI(contact.number, contact.name, contact.photo)

        try {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager != null) {
                    val uri = Uri.fromParts("tel", contact.number, null)
                    telecomManager.placeCall(uri, Bundle())
                } else {
                    val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(contact.number)}")).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(callIntent)
                }
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(contact.number)}")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(dialIntent)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Eroare la inițierea apelului: ${e.message}", Toast.LENGTH_SHORT).show()
        }

        keepLauncherOnTop()
    }

    private fun keepLauncherOnTop() {
        val handler = Handler(Looper.getMainLooper())
        val delays = longArrayOf(80L, 220L, 480L, 850L, 1400L, 2200L)
        for (delay in delays) {
            handler.postDelayed({
                if (callOverlayContainer.visibility == View.VISIBLE) {
                    try {
                        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        am?.moveTaskToFront(taskId, 0)
                    } catch (_: Exception) {}
                    try {
                        val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                            addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                            )
                        }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }
            }, delay)
        }
    }

    private fun loadFavoriteContacts(): List<FavoriteContact> {
        val contactsList = mutableListOf<FavoriteContact>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return contactsList
        }

        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI,
            ContactsContract.CommonDataKinds.Phone.STARRED
        )

        try {
            // 1. Mai întâi căutăm contactele favorite (starred = 1)
            val cursorStarred = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                "${ContactsContract.CommonDataKinds.Phone.STARRED} = 1",
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            val seenNumbers = HashSet<String>()
            cursorStarred?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                while (cursor.moveToNext()) {
                    val name = if (nameIdx != -1) cursor.getString(nameIdx) else "Contact"
                    val rawNumber = if (numberIdx != -1) cursor.getString(numberIdx) else ""
                    val cleanNumber = rawNumber.replace("\\s+".toRegex(), "")
                    if (cleanNumber.isEmpty() || seenNumbers.contains(cleanNumber)) continue
                    seenNumbers.add(cleanNumber)

                    var photoBitmap: Bitmap? = null
                    if (photoIdx != -1) {
                        val photoUriStr = cursor.getString(photoIdx)
                        if (!photoUriStr.isNullOrEmpty()) {
                            try {
                                contentResolver.openInputStream(Uri.parse(photoUriStr))?.use { stream ->
                                    photoBitmap = BitmapFactory.decodeStream(stream)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    contactsList.add(FavoriteContact(name, rawNumber, photoBitmap))
                }
            }

            // 2. Dacă nu sunt contacte cu steluță, încărcăm primele contacte din agendă pentru a fi util
            if (contactsList.isEmpty()) {
                val cursorAll = contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
                )
                cursorAll?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    val photoIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)

                    while (cursor.moveToNext() && contactsList.size < 25) {
                        val name = if (nameIdx != -1) cursor.getString(nameIdx) else "Contact"
                        val rawNumber = if (numberIdx != -1) cursor.getString(numberIdx) else ""
                        val cleanNumber = rawNumber.replace("\\s+".toRegex(), "")
                        if (cleanNumber.isEmpty() || seenNumbers.contains(cleanNumber)) continue
                        seenNumbers.add(cleanNumber)

                        var photoBitmap: Bitmap? = null
                        if (photoIdx != -1) {
                            val photoUriStr = cursor.getString(photoIdx)
                            if (!photoUriStr.isNullOrEmpty()) {
                                try {
                                    contentResolver.openInputStream(Uri.parse(photoUriStr))?.use { stream ->
                                        photoBitmap = BitmapFactory.decodeStream(stream)
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        contactsList.add(FavoriteContact(name, rawNumber, photoBitmap))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return contactsList
    }

    private fun getContactInfo(context: Context, phoneNumber: String): Pair<String?, Bitmap?> {
        var name: String? = null
        var photo: Bitmap? = null
        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
                val projection = arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME,
                    ContactsContract.PhoneLookup.PHOTO_URI
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                        if (nameIndex != -1) name = cursor.getString(nameIndex)
                        val photoUriIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.PHOTO_URI)
                        if (photoUriIndex != -1) {
                            val photoUriStr = cursor.getString(photoUriIndex)
                            if (!photoUriStr.isNullOrEmpty()) {
                                val photoUri = Uri.parse(photoUriStr)
                                context.contentResolver.openInputStream(photoUri)?.use { stream ->
                                    photo = BitmapFactory.decodeStream(stream)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(name, photo)
    }

    private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
        val size = Math.min(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
            isDither = true
        }
        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        val srcLeft = (bitmap.width - size) / 2
        val srcTop = (bitmap.height - size) / 2
        val srcRect = Rect(srcLeft, srcTop, srcLeft + size, srcTop + size)

        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, srcRect, rect, paint)

        return output
    }

    private fun checkAndRequestPhonePermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.READ_PHONE_STATE)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.ANSWER_PHONE_CALLS)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(android.Manifest.permission.CALL_PHONE)
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 2001)
        }

        // Cerere Default Phone Role daca este pe Android 10+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                try {
                    val roleIntent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(roleIntent, 2002)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Verificare permisiune overlay (pentru revenire automata YT Music si suprimare ecran nativ dialer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                val overlayIntent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(overlayIntent)
            } catch (_: Exception) {}
        }
    }

    private fun setupCallServiceListener() {
        BMWInCallService.listener = object : BMWInCallService.Companion.CallListener {
            override fun onIncomingCall(number: String?, name: String?) {
                runOnUiThread {
                    showIncomingCallUI(number, name)
                }
            }

            override fun onOutgoingCall(number: String?, name: String?) {
                runOnUiThread {
                    val contactInfo = if (number != null) getContactInfo(this@MainActivity, number) else Pair(name, null)
                    showOutgoingCallUI(number ?: "", name ?: contactInfo.first ?: number ?: "Număr Necunoscut", contactInfo.second)
                }
            }

            override fun onCallStateChanged(state: Int) {
                runOnUiThread {
                    when (state) {
                        android.telecom.Call.STATE_ACTIVE -> showActiveCallUI()
                        android.telecom.Call.STATE_DISCONNECTED -> dismissCallUI()
                    }
                }
            }

            override fun onCallEnded() {
                runOnUiThread {
                    dismissCallUI()
                }
            }
        }
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("EXTRA_INCOMING_CALL", false) == true) {
            val number = intent.getStringExtra("EXTRA_CALL_NUMBER")
            val name = intent.getStringExtra("EXTRA_CALL_NAME")
            val testPhoto = intent.getBooleanExtra("EXTRA_TEST_PHOTO", false)
            val customBitmap = if (testPhoto) {
                BitmapFactory.decodeResource(resources, R.drawable.bmw_logo)
            } else null
            showIncomingCallUI(number, name, customBitmap)
        } else if (intent?.getBooleanExtra("EXTRA_OUTGOING_CALL", false) == true) {
            val number = intent.getStringExtra("EXTRA_CALL_NUMBER")
            val name = intent.getStringExtra("EXTRA_CALL_NAME")
            val contactInfo = if (number != null) getContactInfo(this, number) else Pair(name, null)
            showOutgoingCallUI(number ?: "", name ?: contactInfo.first ?: number ?: "Număr Necunoscut", contactInfo.second)
        }
    }
}