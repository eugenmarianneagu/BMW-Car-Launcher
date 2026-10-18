package com.bmw.carlauncher

import android.app.ActivityManager
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
import android.media.AudioManager
import android.net.Uri
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
    private var activeNotification: CarNotification? = null
    private var preNotificationMode: CenterMode = CenterMode.BMW
    private val notifDismissHandler = Handler(Looper.getMainLooper())
    private val notifDismissRunnable = Runnable { dismissNotification() }
    private var textToSpeech: TextToSpeech? = null

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

        val prefs = getSharedPreferences("agama_settings", Context.MODE_PRIVATE)
        isNightMode = prefs.getBoolean("night_mode", true)

        enableImmersiveMode()
        setContentView(R.layout.activity_main)

        initViews(savedInstanceState)
        setNightMode(isNightMode, showToast = false)
        setupListeners()
        updateCenterModeUI()
        refreshStations()

        instance = this
        checkAndRequestPhonePermissions()
        setupCallServiceListener()
        handleIncomingCallIntent(intent)
    }



    private fun enableImmersiveMode() {
        // Mentine ecranul deschis in permanenta (Car Launcher)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
        btnNotifClose = findViewById(R.id.btn_notif_close)
        tvNotifSender = findViewById(R.id.tv_notif_sender)
        layoutNotifVoiceBadge = findViewById(R.id.layout_notif_voice_badge)
        tvNotifVoiceLabel = findViewById(R.id.tv_notif_voice_label)
        tvNotifText = findViewById(R.id.tv_notif_text)
        btnNotifAction = findViewById(R.id.btn_notif_action)
        ivNotifActionIcon = findViewById(R.id.iv_notif_action_icon)
        tvNotifActionText = findViewById(R.id.tv_notif_action_text)
        btnNotifSpeak = findViewById(R.id.btn_notif_speak)

        btnNotifClose.isFocusable = true
        btnNotifClose.isFocusableInTouchMode = false
        btnNotifClose.enableInstantClickOnTouch()
        btnNotifClose.setOnClickListener {
            dismissNotification()
        }

        btnNotifAction.isFocusable = true
        btnNotifAction.isFocusableInTouchMode = false
        btnNotifAction.enableInstantClickOnTouch()

        btnNotifSpeak.isFocusable = true
        btnNotifSpeak.isFocusableInTouchMode = false
        btnNotifSpeak.enableInstantClickOnTouch()

        btnNotifAction.setOnClickListener {
            val notif = activeNotification ?: return@setOnClickListener
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
                fallbackOpenNotification(notif)
            }
        }

        btnNotifSpeak.setOnClickListener {
            val notif = activeNotification ?: return@setOnClickListener
            notifDismissHandler.removeCallbacks(notifDismissRunnable)
            speakNotification(notif)
        }

        VoiceMessagePlayer.listener = object : VoiceMessagePlayer.PlaybackListener {
            override fun onPlaybackStarted() {
                runOnUiThread {
                    notifDismissHandler.removeCallbacks(notifDismissRunnable)
                    tvNotifActionText.text = "PAUZĂ"
                    ivNotifActionIcon.setImageResource(R.drawable.ic_media_pause)
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
                    notifDismissHandler.removeCallbacks(notifDismissRunnable)
                    notifDismissHandler.postDelayed(notifDismissRunnable, 8000L)
                }
            }

            override fun onPlaybackError(error: String) {
                runOnUiThread {
                    tvNotifActionText.text = "REDARE"
                    ivNotifActionIcon.setImageResource(R.drawable.ic_media_play)
                    val notif = activeNotification
                    if (notif != null) {
                        fallbackOpenNotification(notif)
                    }
                }
            }
        }

        // Initializare Text to Speech
        initTextToSpeech()

        // Abonare la evenimentele de notificare
        CarNotificationHub.onNotificationReceived = { notif ->
            displayNotification(notif)
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

        focusPlayPauseButton()
    }

    private fun initCenterMapView(savedInstanceState: Bundle?) {
        centerMapView.onCreate(savedInstanceState)
        centerMapView.getMapAsync { map ->
            mapLibreMap = map
            // Dezactivare elemente UI native pentru o integrare curata in cercul BMW
            val ui = map.uiSettings
            ui.isLogoEnabled = false
            ui.isAttributionEnabled = false
            ui.isCompassEnabled = false

            // Daca utilizatorul interactioneaza manual cu harta, oprim urmarirea automata si aratam butonul de recentrare
            map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                    isMapTrackingGps = false
                    btnMapRecenter.visibility = View.VISIBLE
                }
                override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
                override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
            })

            // Click pe harta: comuta ecranul central ca si pe celelalte ecrane
            map.addOnMapClickListener {
                cycleCenterMode()
                true
            }

            // Incarca stilul initial Day/Night
            applyMapNightMode(isNightMode)
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
                        updatePlayPauseIcon(false)
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

        // L2: NAVI - Lanseaza Waze (sau Maps)
        btnLeft2.setOnClickListener {
            launchNavigationApp()
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
        CenterMode.MAP -> viewCenterMap
        CenterMode.ENERGY -> viewCenterEnergy
        CenterMode.NOTIFICATION -> viewCenterNotification
    }

    private fun cycleCenterMode() {
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
        centerMapView.onStart()

        try {
            registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        } catch (_: Exception) {}

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

        if (isFirstRadioConnect) {
            isFirstRadioConnect = false
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
        centerMapView.onStop()
        try {
            unregisterReceiver(batteryReceiver)
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
        ExternalMediaManager.syncActiveSessions(this)
        applyScreenBrightness(isNightMode)
        centerMapView.onResume()
        registerGnssCallback()
        focusPlayPauseButton()
        if (currentMode == CenterMode.ENERGY) {
            energyHandler.removeCallbacks(energyPollRunnable)
            energyPollRunnable.run()
        }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        VoiceMessagePlayer.stop()
        energyHandler.removeCallbacks(energyPollRunnable)
        currentTaskId = taskId
        ExternalMediaManager.onLauncherPaused(this, taskId)
        centerMapView.onPause()
        unregisterGnssCallback()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        centerMapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        centerMapView.onLowMemory()
    }

    override fun onDestroy() {
        super.onDestroy()
        VoiceMessagePlayer.stop()
        VoiceMessagePlayer.listener = null
        energyHandler.removeCallbacksAndMessages(null)
        centerMapView.onDestroy()
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (_: Exception) {}
        CarNotificationHub.onNotificationReceived = null
        notifDismissHandler.removeCallbacksAndMessages(null)
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
            } else {
                "Mesaj de la ${notif.sender}: ${notif.message}"
            }
            textToSpeech?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "CarNotifUtterance")
        } catch (e: Exception) {
            Log.e("MainActivity", "TTS speak exception: ${e.message}")
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

            // Pictograma aplicatiei
            if (notif.appIcon != null) {
                ivNotifAppIcon.setImageDrawable(notif.appIcon)
            } else {
                if (notif.packageName.contains("whatsapp", ignoreCase = true)) {
                    ivNotifAppIcon.setImageResource(R.drawable.ic_mask_whatsapp)
                } else {
                    ivNotifAppIcon.setImageResource(R.drawable.ic_sms)
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

            // Auto-dismiss dupa 15 secunde
            notifDismissHandler.removeCallbacks(notifDismissRunnable)
            notifDismissHandler.postDelayed(notifDismissRunnable, 15000L)
        }
    }

    private fun dismissNotification() {
        notifDismissHandler.removeCallbacks(notifDismissRunnable)
        VoiceMessagePlayer.stop()
        if (currentMode == CenterMode.NOTIFICATION) {
            currentMode = preNotificationMode
            updateCenterModeUI(animateFrom = CenterMode.NOTIFICATION)
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
                notif.playActionIntent.send()
            } catch (e: Exception) {
                Log.e("MainActivity", "Eroare playActionIntent: ${e.message}")
                fallbackOpenNotification(notif)
            }
        } else {
            Toast.makeText(this, "Deschidere conversație WhatsApp...", Toast.LENGTH_SHORT).show()
            fallbackOpenNotification(notif)
        }
    }

    private fun fallbackOpenNotification(notif: CarNotification) {
        try {
            if (notif.contentIntent != null) {
                notif.contentIntent.send()
            } else {
                val launchIntent = packageManager.getLaunchIntentForPackage(notif.packageName)
                if (launchIntent != null) {
                    startActivity(launchIntent)
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Eroare la deschiderea notificarii: ${e.message}")
            val launchIntent = packageManager.getLaunchIntentForPackage(notif.packageName)
            if (launchIntent != null) {
                try {
                    startActivity(launchIntent)
                } catch (_: Exception) {}
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

    private val focusOrderViews: List<View> by lazy {
        listOf(
            btnLeft1,        // 1. Radio
            btnLeft2,        // 2. Navigatie
            btnLeft3,        // 3. Music
            btnPrevTrack,    // 4. Prev
            btnPlayPause,    // 5. Play
            btnNextTrack,    // 6. Next
            btnRight3,       // 7. Aplicatii
            btnRight2,       // 8. Whatsapp
            btnRight1,       // 9. Telefon
            centerCircle,    // 10. Cerc Central
            btnNightMode,    // 11. Day/Night
            btnQuickSettings // 12. Setari
        )
    }

    private fun getActiveSecondaryOverlay(): ViewGroup? {
        if (::callOverlayContainer.isInitialized && callOverlayContainer.visibility == View.VISIBLE) return callOverlayContainer
        if (::favoritesOverlayContainer.isInitialized && favoritesOverlayContainer.visibility == View.VISIBLE) return favoritesOverlayContainer
        if (::radioOverlayContainer.isInitialized && radioOverlayContainer.visibility == View.VISIBLE) return radioOverlayContainer
        if (::musicOverlayContainer.isInitialized && musicOverlayContainer.visibility == View.VISIBLE) return musicOverlayContainer
        if (::appsOverlayContainer.isInitialized && appsOverlayContainer.visibility == View.VISIBLE) return appsOverlayContainer
        if (::settingsOverlayContainer.isInitialized && settingsOverlayContainer.visibility == View.VISIBLE) return settingsOverlayContainer
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
        target.isFocusable = true
        target.isFocusableInTouchMode = true
        target.requestFocus()
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Tasta F12 de pe tastatura Bluetooth revine direct la launcher și închide ferestrele secundare
        if (event.keyCode == KeyEvent.KEYCODE_F12) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                dismissAllOverlays()
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
                    if (!isLongClicked && event.x >= 0 && event.x <= v.width &&
                        event.y >= 0 && event.y <= v.height) {
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