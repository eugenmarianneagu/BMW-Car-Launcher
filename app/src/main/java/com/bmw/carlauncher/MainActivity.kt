package com.bmw.carlauncher

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.telecom.TelecomManager
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    enum class CenterMode {
        BMW, MEDIA, RADIO, MAP, WAZE, SPEEDO
    }

    private var currentMode = CenterMode.BMW
    private var isNightMode = true

    // Media Controller & Stations
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var stations = mutableListOf<RadioStation>()

    // Top Bar elements
    private lateinit var textClock: TextView
    private lateinit var btnVolDown: TextView
    private lateinit var btnVolUp: TextView

    // Bottom Bar elements
    private lateinit var btnPrevTrack: TextView
    private lateinit var btnNextTrack: TextView
    private lateinit var btnPlayPause: TextView
    private lateinit var bottomBatteryIcon: TextView
    private lateinit var bottomGpsCount: TextView
    private lateinit var btnNightMode: TextView
    private lateinit var btnQuickSettings: TextView

    // Agama Wings - Left column
    private lateinit var btnLeft1: FrameLayout
    private lateinit var btnLeft2: FrameLayout
    private lateinit var btnLeft3: FrameLayout

    // Agama Wings - Right column
    private lateinit var btnRight1: FrameLayout
    private lateinit var btnRight2: FrameLayout
    private lateinit var btnRight3: FrameLayout

    // Center Circle & Views
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
    private var locationOverlay: MyLocationNewOverlay? = null

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

    // GPS Speedometer
    private var locationManager: LocationManager? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val speedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f
            val speedInt = Math.round(speedKmh).coerceAtLeast(0)
            centerSpeedValue.text = "$speedInt"
            centerSpeedSub.text = "GPS ACTIV • ±${location.accuracy.toInt()}m"
            bottomGpsCount.text = "⌖ LOCK"

            // Actualizare badge viteza si orientare harta GPS live
            mapSpeedText.text = "$speedInt"
            if (currentMode == CenterMode.MAP && location.hasBearing() && location.speed > 0.8f) {
                centerMapView.setMapOrientation(-location.bearing)
            }
        }
        override fun onProviderEnabled(provider: String) {
            centerSpeedSub.text = "GPS CONECTAT"
            bottomGpsCount.text = "⌖ CON"
        }
        override fun onProviderDisabled(provider: String) {
            centerSpeedSub.text = "GPS OPRIT"
            bottomGpsCount.text = "⌖ NO"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initializare osmdroid inainte de incarcarea layout-ului
        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        Configuration.getInstance().userAgentValue = packageName

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        isNightMode = prefs.getBoolean("night_mode", true)

        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        initViews()
        setNightMode(isNightMode, showToast = false)
        setupListeners()
        updateCenterModeUI()
        refreshStations()

        // Focus implicit pe Cercul Central pentru controlul DPAD
        centerCircle.post {
            centerCircle.requestFocus()
        }
    }

    private fun enableImmersiveMode() {
        // Mentine ecranul deschis in permanenta (Car Launcher)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        }
    }

    private fun initViews() {
        // Top Bar
        textClock = findViewById(R.id.text_clock)
        btnVolDown = findViewById(R.id.btn_vol_down)
        btnVolUp = findViewById(R.id.btn_vol_up)

        // Bottom Bar
        btnPrevTrack = findViewById(R.id.btn_prev_track)
        btnNextTrack = findViewById(R.id.btn_next_track)
        btnPlayPause = findViewById(R.id.btn_play_pause)
        bottomBatteryIcon = findViewById(R.id.bottom_battery_icon)
        bottomGpsCount = findViewById(R.id.bottom_gps_count)
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
        centerCircle = findViewById(R.id.center_circle)
        viewCenterBmw = findViewById(R.id.view_center_bmw)

        viewCenterMedia = findViewById(R.id.view_center_media)
        viewCenterMedia.clipToOutline = true
        viewCenterMedia.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        centerMediaThumbnail = findViewById(R.id.center_media_thumbnail)
        centerMediaSource = findViewById(R.id.center_media_source)
        centerMediaTitle = findViewById(R.id.center_media_title)
        centerMediaArtist = findViewById(R.id.center_media_artist)

        // Harta Rotunda Live (Dark Mode)
        viewCenterMap = findViewById(R.id.view_center_map)
        viewCenterMap.clipToOutline = true
        viewCenterMap.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        centerMapView = findViewById(R.id.center_map_view)
        mapSpeedText = findViewById(R.id.map_speed_text)
        initCenterMapView()

        viewCenterRadio = findViewById(R.id.view_center_radio)
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
    }

    private fun initCenterMapView() {
        centerMapView.setTileSource(TileSourceFactory.MAPNIK)
        centerMapView.setMultiTouchControls(true)
        centerMapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        centerMapView.isTilesScaledToDpi = true
        centerMapView.setBackgroundColor(0xFF101216.toInt())

        // Zoom initial harta
        centerMapView.controller.setZoom(15.0)
        centerMapView.minZoomLevel = 4.0
        centerMapView.maxZoomLevel = 20.0

        // Aplica stilul hartii conform modului zi/noapte
        applyMapNightMode(isNightMode)

        // MyLocationNewOverlay cu urmarire automata a locatiei
        val provider = GpsMyLocationProvider(this)
        val overlay = MyLocationNewOverlay(provider, centerMapView)
        overlay.enableMyLocation()
        overlay.enableFollowLocation()
        overlay.isDrawAccuracyEnabled = false

        // Cursor de navigatie BMW Amber (sageata de directie orientata in sus)
        val arrowBitmap = createNavArrowBitmap()
        overlay.setDirectionIcon(arrowBitmap)
        overlay.setPersonIcon(arrowBitmap)
        overlay.setDirectionAnchor(0.5f, 0.5f)
        overlay.setPersonAnchor(0.5f, 0.5f)
        centerMapView.overlays.add(overlay)
        locationOverlay = overlay

        // Detectare click / atingere pe harta pentru comutarea modului (la fel ca in restul modurilor)
        val eventsOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                cycleCenterMode()
                return true
            }
            override fun longPressHelper(p: GeoPoint?): Boolean {
                return false
            }
        })
        centerMapView.overlays.add(eventsOverlay)
    }

    private fun createNavArrowBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val path = Path().apply {
            moveTo(size / 2f, 6f) // varf indreptat in sus
            lineTo(size - 10f, size - 10f)
            lineTo(size / 2f, size - 18f) // chevron interior
            lineTo(10f, size - 10f)
            close()
        }

        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#80FF7500")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF7500")
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        canvas.drawPath(path, glowPaint)
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, borderPaint)
        return bmp
    }

    private fun setupListeners() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Top Bar Volume
        btnVolDown.setOnClickListener {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
        }
        btnVolUp.setOnClickListener {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
        }

        // Bottom Bar Media Controls
        btnPrevTrack.setOnClickListener {
            if (currentMode == CenterMode.MEDIA) {
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.skipToPrevious()
                    btnPlayPause.text = "⏸ PAUSE"
                    ExternalMediaManager.attachCallback(mc)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 300L)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 800L)
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                    btnPlayPause.text = "⏸ PAUSE"
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
                            try {
                                ExternalMediaManager.getActiveMusicController(this)?.transportControls?.pause()
                            } catch (_: Exception) {}
                            c.play()
                        }
                    }
                }
                CenterMode.MEDIA -> {
                    val isCurrentlyPlaying = btnPlayPause.text.toString().contains("PAUSE")
                    val mc = ExternalMediaManager.getActiveMusicController(this)
                    if (mc != null) {
                        if (isCurrentlyPlaying) mc.transportControls.pause() else mc.transportControls.play()
                    } else {
                        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                    }
                    btnPlayPause.text = if (isCurrentlyPlaying) "▶ PLAY" else "⏸ PAUSE"
                }
                else -> {
                    if (controller?.isPlaying == true) {
                        controller?.pause()
                    } else if (ExternalMediaManager.currentData.isPlaying) {
                        val mc = ExternalMediaManager.getActiveMusicController(this)
                        if (mc != null) {
                            mc.transportControls.pause()
                        } else {
                            val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                            am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                        }
                        btnPlayPause.text = "▶ PLAY"
                    } else {
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
                val mc = ExternalMediaManager.getActiveMusicController(this)
                if (mc != null) {
                    mc.transportControls.skipToNext()
                    btnPlayPause.text = "⏸ PAUSE"
                    ExternalMediaManager.attachCallback(mc)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 300L)
                    window.decorView.postDelayed({ ExternalMediaManager.syncFromController(mc) }, 800L)
                } else {
                    val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT))
                    am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_NEXT))
                    btnPlayPause.text = "⏸ PAUSE"
                }
            } else {
                controller?.seekToNextMediaItem()
            }
        }

        // Night Mode / Settings
        btnNightMode.setOnClickListener {
            setNightMode(!isNightMode, showToast = true)
        }
        btnQuickSettings.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (_: Exception) {}
        }
        btnQuickSettings.setOnLongClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Toast.makeText(this, "Setari Accesibilitate -> Activati 'BMW Car Launcher'", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            true
        }

        // Clic / Enter pe Cercul Central -> comuta afisajul (BMW -> Media -> Radio -> Map -> Waze -> Speedometer)
        centerCircle.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterMedia.setOnClickListener {
            cycleCenterMode()
        }

        viewCenterMap.setOnClickListener {
            cycleCenterMode()
        }

        findViewById<View?>(R.id.map_speed_badge)?.setOnClickListener {
            cycleCenterMode()
        }

        // L1: RADIO - Deschide direct lista cu posturile radio (Station Picker Dialog)
        btnLeft1.setOnClickListener {
            showStationPickerDialog()
        }
        btnLeft1.setOnLongClickListener {
            showStationPickerDialog()
            true
        }

        // L2: NAVI - Lanseaza Waze (sau Maps)
        btnLeft2.setOnClickListener {
            launchNavigationApp()
        }

        // L3: MUSIC - Ruleaza sau comuta muzica in fundal (fara deschiderea aplicatiei pe ecran)
        btnLeft3.setOnClickListener {
            playOrToggleMusic()
        }

        // R1: PHONE - Deschide ecranul de apelare
        btnRight1.setOnClickListener {
            launchPhoneDialer()
        }

        // R2: INTERNET - Deschide browserul web
        btnRight2.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Browser web indisponibil", Toast.LENGTH_SHORT).show()
            }
        }

        // R3: APPS - Deschide lista de aplicatii instalate (App Drawer)
        btnRight3.setOnClickListener {
            openAllApps()
        }

        // Button icon click forwarding
        findViewById<View?>(R.id.icon_left_1)?.setOnClickListener { btnLeft1.performClick() }
        findViewById<View?>(R.id.icon_left_2)?.setOnClickListener { btnLeft2.performClick() }
        findViewById<View?>(R.id.icon_left_3)?.setOnClickListener { btnLeft3.performClick() }
        findViewById<View?>(R.id.icon_right_1)?.setOnClickListener { btnRight1.performClick() }
        findViewById<View?>(R.id.icon_right_2)?.setOnClickListener { btnRight2.performClick() }
        findViewById<View?>(R.id.icon_right_3)?.setOnClickListener { btnRight3.performClick() }
    }

    private fun cycleCenterMode() {
        currentMode = when (currentMode) {
            CenterMode.BMW -> if (ExternalMediaManager.currentData.isPlaying) CenterMode.MEDIA else CenterMode.RADIO
            CenterMode.MEDIA -> CenterMode.RADIO
            CenterMode.RADIO -> CenterMode.MAP
            CenterMode.MAP -> CenterMode.WAZE
            CenterMode.WAZE -> CenterMode.SPEEDO
            CenterMode.SPEEDO -> CenterMode.BMW
        }
        updateCenterModeUI()

        if (currentMode == CenterMode.WAZE && !isNotificationAccessGranted()) {
            promptNotificationAccess()
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }

    private fun promptNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("Acces Notificari Waze & Media")
            .setMessage("Pentru a afisa indicatiile Waze si videoclipurile/piesele YouTube Music in Cercul Central, acordati permisiunea de citire a notificarilor.")
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

    private fun updateCenterModeUI() {
        viewCenterBmw.visibility = if (currentMode == CenterMode.BMW) View.VISIBLE else View.GONE
        viewCenterMedia.visibility = if (currentMode == CenterMode.MEDIA) View.VISIBLE else View.GONE
        viewCenterRadio.visibility = if (currentMode == CenterMode.RADIO) View.VISIBLE else View.GONE
        viewCenterMap.visibility = if (currentMode == CenterMode.MAP) View.VISIBLE else View.GONE
        viewCenterWaze.visibility = if (currentMode == CenterMode.WAZE) View.VISIBLE else View.GONE
        viewCenterSpeedo.visibility = if (currentMode == CenterMode.SPEEDO) View.VISIBLE else View.GONE

        if (currentMode == CenterMode.MAP) {
            locationOverlay?.enableFollowLocation()
        }

        when (currentMode) {
            CenterMode.RADIO -> {
                btnPlayPause.text = if (controller?.isPlaying == true) "⏸ PAUSE" else "▶ PLAY"
            }
            CenterMode.MEDIA -> {
                btnPlayPause.text = if (ExternalMediaManager.currentData.isPlaying) "⏸ PAUSE" else "▶ PLAY"
            }
            else -> {
                val isAnyPlaying = (controller?.isPlaying == true) || ExternalMediaManager.currentData.isPlaying
                btnPlayPause.text = if (isAnyPlaying) "⏸ PAUSE" else "▶ PLAY"
            }
        }
    }

    private fun updateClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("H:mm", Locale.getDefault())
        textClock.text = timeFormat.format(now)
    }

    private fun launchNavigationApp() {
        val wazePackage = "com.waze"
        val mapsPackage = "com.google.android.apps.maps"
        val pm = packageManager

        val intent = pm.getLaunchIntentForPackage(wazePackage)
            ?: pm.getLaunchIntentForPackage(mapsPackage)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Nicio aplicatie de navigatie gasita", Toast.LENGTH_SHORT).show()
        }
    }

    private fun playOrToggleMusic() {
        // 1. Opreste radioul intern
        controller?.pause()

        // 2. Comuta afisajul central pe modul MEDIA
        currentMode = CenterMode.MEDIA
        updateCenterModeUI()

        // 3. Cauta controllerul de muzica in fundal
        val musicCtrl = ExternalMediaManager.getActiveMusicController(this)
        if (musicCtrl != null) {
            val isPlaying = musicCtrl.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
            if (isPlaying) {
                musicCtrl.transportControls.pause()
                btnPlayPause.text = "▶ PLAY"
            } else {
                musicCtrl.transportControls.play()
                btnPlayPause.text = "⏸ PAUSE"
            }
            return
        }

        // 4. Daca nu este deschisa in fundal, o pornim discret in fundal si dam play
        startMusicInBackground()
    }

    private fun startMusicInBackground() {
        // Trimite broadcast-uri MediaButton catre YouTube Music pentru pornire in fundal fara interfata
        try {
            val downIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName("com.google.android.apps.youtube.music", "androidx.media.session.MediaButtonReceiver")
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            sendBroadcast(downIntent)

            val upIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
                component = ComponentName("com.google.android.apps.youtube.music", "androidx.media.session.MediaButtonReceiver")
                putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            }
            sendBroadcast(upIntent)
        } catch (_: Exception) {}

        // Daca procesul aplicatiei nu ruleaza deloc, o lansam discret si readucem instant Agama pe ecran
        val ytMusicIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music")
            ?: packageManager.getLaunchIntentForPackage("com.spotify.music")

        if (ytMusicIntent != null) {
            ytMusicIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            try {
                startActivity(ytMusicIntent)
            } catch (_: Exception) {}

            // Readuce instant Agama Launcher in prim-plan, lasand YouTube Music sa ruleze in fundal
            window.decorView.postDelayed({
                try {
                    val bringBack = Intent(this@MainActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
                    }
                    startActivity(bringBack)
                } catch (_: Exception) {}

                // Trimite comanda de play imediat ce a fost initializata sesiunea in fundal
                window.decorView.postDelayed({
                    val mc = ExternalMediaManager.getActiveMusicController(this@MainActivity)
                    if (mc != null) {
                        mc.transportControls.play()
                    } else {
                        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
                        am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
                    }
                    btnPlayPause.text = "⏸ PAUSE"
                }, 300L)
            }, 300L)
        } else {
            Toast.makeText(this, "Nicio aplicatie de muzica gasita", Toast.LENGTH_SHORT).show()
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

    private fun openAllApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString() }

        val appNames = apps.map { it.loadLabel(pm).toString() }

        AlertDialog.Builder(this)
            .setTitle("Aplicatii Instalate")
            .setItems(appNames.toTypedArray()) { _, which ->
                val chosen = apps[which]
                val launchIntent = pm.getLaunchIntentForPackage(chosen.activityInfo.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            }
            .setNegativeButton("Inchide", null)
            .show()
    }

    private fun refreshStations() {
        stations = StationManager.getStations(this)
    }

    private fun showStationPickerDialog() {
        refreshStations()
        val names = stations.mapIndexed { idx, s -> "${idx + 1}. ${s.name} (${s.genre})" }.toMutableList()
        names.add("+ ADAUGA POST RADIO NOU")

        AlertDialog.Builder(this)
            .setTitle("Selecteaza Post Radio")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == stations.size) {
                    showAddStationDialog()
                } else {
                    controller?.let {
                        try {
                            ExternalMediaManager.currentData.mediaController?.transportControls?.pause()
                        } catch (_: Exception) {}
                        it.seekToDefaultPosition(which)
                        it.play()
                    }
                    currentMode = CenterMode.RADIO
                    updateCenterModeUI()
                }
            }
            .setNegativeButton("Inchide", null)
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
                val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                if (isGpsEnabled) {
                    lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener)
                    centerSpeedSub.text = "CAUTARE SEMNAL GPS..."
                } else {
                    centerSpeedSub.text = "ACTIVATI GPS DIN SETARI"
                }
            }
        } catch (e: Exception) {
            centerSpeedSub.text = "EROARE GPS"
        }
    }

    private fun stopGpsUpdates() {
        try {
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
        }
    }

    override fun onStart() {
        super.onStart()
        enableImmersiveMode()
        clockHandler.post(clockRunnable)
        startGpsUpdates()

        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}

        WazeNotificationService.refresh()

        WazeNavManager.listener = { navData ->
            runOnUiThread {
                updateWazeUI(navData)
            }
        }

        ExternalMediaManager.listener = { data ->
            runOnUiThread {
                if (data.title.isNotEmpty()) {
                    centerMediaSource.text = "▶ ${data.sourceName.ifEmpty { "YOUTUBE MUSIC" }}"
                    centerMediaTitle.text = data.title
                    centerMediaArtist.text = data.artist
                    if (data.artBitmap != null) {
                        centerMediaThumbnail.setImageBitmap(data.artBitmap)
                        centerMediaThumbnail.visibility = View.VISIBLE
                    } else {
                        centerMediaThumbnail.visibility = View.GONE
                    }
                    if (data.isPlaying) {
                        // Daca un player exterior ruleaza (YouTube Music etc), opreste radioul
                        controller?.pause()
                        if (currentMode == CenterMode.MEDIA) {
                            btnPlayPause.text = "⏸ PAUSE"
                        }
                    } else if (currentMode == CenterMode.MEDIA) {
                        btnPlayPause.text = "▶ PLAY"
                    }
                } else if (!data.isPlaying && currentMode == CenterMode.MEDIA) {
                    btnPlayPause.text = "▶ PLAY"
                }
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

        if (!ctrl.isPlaying) {
            ctrl.play()
        }

        ctrl.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                updateRadioUI(ctrl)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateRadioUI(ctrl)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateRadioUI(ctrl)
            }
        })
    }

    private fun updateRadioUI(ctrl: MediaController) {
        val idx = ctrl.currentMediaItemIndex
        val stationName = if (idx in stations.indices) stations[idx].name else "BMW RADIO"
        val stationGenre = if (idx in stations.indices) stations[idx].genre else ""

        val liveTitle = ctrl.mediaMetadata.title?.toString() ?: ""
        centerRadioTitle.text = stationName

        centerRadioSub.text = if (liveTitle.isNotEmpty() && liveTitle != stationName) {
            "Live: $liveTitle"
        } else {
            stationGenre
        }

        // Butonul de PLAY / PAUSE din bara de jos
        if (currentMode != CenterMode.MEDIA) {
            btnPlayPause.text = if (ctrl.isPlaying) "⏸ PAUSE" else "▶ PLAY"
        }

        val artBytes = ctrl.mediaMetadata.artworkData
        if (artBytes != null) {
            val bmp = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
            if (bmp != null) {
                centerRadioArt.setImageBitmap(bmp)
            }
        }

        val colorAmber = ContextCompat.getColor(this, R.color.bmw_amber)
        val colorDim = ContextCompat.getColor(this, R.color.bmw_gray_text)

        if (ctrl.isPlaying) {
            centerRadioStatus.text = "▶ PLAYING"
            centerRadioStatus.setTextColor(colorAmber)
        } else {
            centerRadioStatus.text = "❚❚ PE PAUZA"
            centerRadioStatus.setTextColor(colorDim)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        WazeNavManager.listener = null
        ExternalMediaManager.listener = null
        stopGpsUpdates()
        clockHandler.removeCallbacks(clockRunnable)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }

    override fun onResume() {
        super.onResume()
        applyScreenBrightness(isNightMode)
        centerMapView.onResume()
    }

    override fun onPause() {
        super.onPause()
        centerMapView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        centerMapView.onDetach()
    }

    private fun setNightMode(night: Boolean, showToast: Boolean = true) {
        isNightMode = night

        // 1. Schimba pictograma butonului intre noapte (luna) si zi (soare)
        btnNightMode.text = if (night) "🌙" else "☀️"

        // 2. Controleaza luminozitatea display-ului: 10% pe Night, 90% pe Day
        applyScreenBrightness(night)

        // 3. Controleaza stilul hartii: Dark Mode pe Night, stil normal OSM pe Day
        applyMapNightMode(night)

        // 4. Sincronizeaza pictograma de la temperatura din bara superioara
        findViewById<TextView?>(R.id.top_temp_text)?.let { tv ->
            val text = tv.text.toString()
            val tempPart = if (text.contains("°")) {
                text.substring(text.indexOf("°") - 2).trim()
            } else "+5°"
            tv.text = if (night) "🌙 $tempPart" else "☀️ $tempPart"
        }

        // 5. Salveaza starea pentru relansari viitoare
        getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("night_mode", night)
            .apply()

        if (showToast) {
            val msg = if (night) "Mod Nocturn Activ • Luminozitate 10%" else "Mod Zi Activ • Luminozitate 90%"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyScreenBrightness(night: Boolean) {
        val lp = window.attributes
        lp.screenBrightness = if (night) 0.10f else 0.90f
        window.attributes = lp
    }

    private fun applyMapNightMode(night: Boolean) {
        if (!::centerMapView.isInitialized) return

        if (night) {
            // Dark Auto: desaturare + inversare luminanta pentru bord BMW
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(0.20f)

            val darkMatrix = ColorMatrix(floatArrayOf(
                -0.85f,  0.00f,  0.00f, 0f, 215f,
                 0.00f, -0.85f,  0.00f, 0f, 215f,
                 0.00f,  0.00f, -0.80f, 0f, 210f,
                 0.00f,  0.00f,  0.00f, 1f,   0f
            ))
            darkMatrix.preConcat(satMatrix)
            centerMapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkMatrix))
            centerMapView.setBackgroundColor(0xFF101216.toInt())
        } else {
            // Day mode: stil normal luminos OpenStreetMap
            centerMapView.overlayManager.tilesOverlay.setColorFilter(null)
            centerMapView.setBackgroundColor(0xFFE5E5E5.toInt())
        }
        centerMapView.invalidate()
    }
}