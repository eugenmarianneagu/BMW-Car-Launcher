package com.bmw.carlauncher

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Locale

data class WazeNavData(
    val distance: String,
    val road: String,
    val eta: String,
    val isNavigating: Boolean = true
)

object WazeNavManager {
    var currentData: WazeNavData = WazeNavData(
        distance = "-- m",
        road = "Porniti o ruta in Waze",
        eta = "Asteptare indicatii...",
        isNavigating = false
    )

    var listener: ((WazeNavData) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentData)
        }

    fun update(data: WazeNavData) {
        currentData = data
        listener?.invoke(data)
    }
}

data class ExternalMediaData(
    val packageName: String = "",
    val sourceName: String = "",
    val title: String = "",
    val artist: String = "",
    val artBitmap: Bitmap? = null,
    val isPlaying: Boolean = false,
    val mediaController: android.media.session.MediaController? = null
)

object ExternalMediaManager {
    var currentData: ExternalMediaData = ExternalMediaData()

    var listener: ((ExternalMediaData) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(currentData)
        }

    private var registeredController: MediaController? = null

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            syncFromController(registeredController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            syncFromController(registeredController)
        }

        override fun onSessionDestroyed() {
            registeredController = null
        }
    }

    fun attachCallback(mc: MediaController?) {
        if (mc == null) return
        if (registeredController != mc) {
            try {
                registeredController?.unregisterCallback(mediaCallback)
            } catch (_: Exception) {}
            registeredController = mc
            try {
                mc.registerCallback(mediaCallback)
            } catch (_: Exception) {}
        }
        syncFromController(mc)
    }

    fun syncFromController(mc: MediaController?) {
        val ctrl = mc ?: registeredController ?: return
        try {
            val meta = ctrl.metadata
            val state = ctrl.playbackState
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                ?: currentData.title
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: meta?.getString(MediaMetadata.METADATA_KEY_AUTHOR)
                ?: currentData.artist
            val art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: currentData.artBitmap

            val pbState = state?.state
            val isPlaying = (pbState == PlaybackState.STATE_PLAYING ||
                    pbState == PlaybackState.STATE_BUFFERING ||
                    pbState == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                    pbState == PlaybackState.STATE_SKIPPING_TO_PREVIOUS)

            val pkg = ctrl.packageName
            val source = when {
                pkg.contains("youtube.music") -> "YOUTUBE MUSIC"
                pkg.contains("youtube") -> "YOUTUBE VIDEO"
                pkg.contains("spotify") -> "SPOTIFY"
                else -> "MEDIA PLAYER"
            }

            if (title.isNotEmpty()) {
                update(
                    ExternalMediaData(
                        packageName = pkg,
                        sourceName = source,
                        title = title,
                        artist = artist,
                        artBitmap = art,
                        isPlaying = isPlaying,
                        mediaController = ctrl
                    )
                )
            }
        } catch (_: Exception) {}
    }

    fun update(data: ExternalMediaData) {
        currentData = data
        listener?.invoke(data)
    }

    fun getActiveMusicController(context: Context): MediaController? {
        // 1. Daca avem deja o sesiune valida in currentData
        currentData.mediaController?.let {
            attachCallback(it)
            return it
        }

        // 2. Cauta sesiuni active in sistem prin MediaSessionManager
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            val cn = ComponentName(context, WazeNotificationService::class.java)
            val sessions = msm?.getActiveSessions(cn) ?: emptyList()

            val musicCtrl = sessions.firstOrNull {
                val p = it.packageName.lowercase(Locale.getDefault())
                p.contains("youtube.music") || p.contains("spotify") || p.contains("music")
            } ?: sessions.firstOrNull { it.packageName != context.packageName }

            if (musicCtrl != null) {
                attachCallback(musicCtrl)
                return musicCtrl
            }
        } catch (_: Exception) {}

        return null
    }
}

class WazeNotificationService : NotificationListenerService() {

    companion object {
        var instance: WazeNotificationService? = null

        fun refresh() {
            try {
                instance?.activeNotifications?.forEach { sbn ->
                    instance?.onNotificationPosted(sbn)
                }
            } catch (_: Exception) {}
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        // 1. Asculta notificarile Waze (sau Google Maps ca fallback)
        if (pkg == "com.waze" || pkg == "com.google.android.apps.maps") {
            val notification = sbn.notification ?: return
            val extras = notification.extras ?: return

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""

            if (title.isNotEmpty() || text.isNotEmpty()) {
                val navData = WazeNavData(
                    distance = if (title.isNotEmpty()) title else "Inainte",
                    road = if (text.isNotEmpty()) text else "Traseu activ",
                    eta = if (subText.isNotEmpty()) subText else "Navigare activa",
                    isNavigating = true
                )
                WazeNavManager.update(navData)
            }
        }

        // 2. Asculta notificarile Media de la YouTube Music, Spotify, YouTube sau playere video/audio externe
        if (pkg != packageName) {
            val notification = sbn.notification ?: return
            val isMediaCategory = notification.category == Notification.CATEGORY_TRANSPORT
            val isKnownMediaApp = pkg.contains("youtube") || pkg.contains("music") || pkg.contains("spotify")

            if (isMediaCategory || isKnownMediaApp) {
                val extras = notification.extras ?: return
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
                val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""

                if (title.isNotEmpty()) {
                    var isPlaying = false
                    var mediaCtrl: android.media.session.MediaController? = null
                    try {
                        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
                        }
                        if (token != null) {
                            val mc = android.media.session.MediaController(this, token)
                            mediaCtrl = mc
                            ExternalMediaManager.attachCallback(mc)
                            val state = mc.playbackState?.state
                            isPlaying = (state == PlaybackState.STATE_PLAYING ||
                                    state == PlaybackState.STATE_BUFFERING ||
                                    state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                                    state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS)
                        } else {
                            val actions = notification.actions
                            if (actions != null) {
                                isPlaying = actions.any { action ->
                                    val actTitle = action.title?.toString()?.lowercase(Locale.getDefault()) ?: ""
                                    actTitle.contains("pause") || actTitle.contains("pauză")
                                }
                            }
                        }
                    } catch (_: Exception) {}

                    var art: Bitmap? = null
                    try {
                        val iconObj = extras.getParcelable<android.os.Parcelable>(Notification.EXTRA_LARGE_ICON)
                        if (iconObj is Bitmap) {
                            art = iconObj
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && iconObj is Icon) {
                            val drawable = iconObj.loadDrawable(this)
                            if (drawable != null) {
                                val bmp = Bitmap.createBitmap(
                                    drawable.intrinsicWidth.coerceAtLeast(100),
                                    drawable.intrinsicHeight.coerceAtLeast(100),
                                    Bitmap.Config.ARGB_8888
                                )
                                val c = android.graphics.Canvas(bmp)
                                drawable.setBounds(0, 0, c.width, c.height)
                                drawable.draw(c)
                                art = bmp
                            }
                        }
                    } catch (_: Exception) {}

                    if (art == null && mediaCtrl != null) {
                        try {
                            val meta = mediaCtrl.metadata
                            art = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                        } catch (_: Exception) {}
                    }
                    if (art == null) {
                        art = ExternalMediaManager.currentData.artBitmap
                    }

                    val source = when {
                        pkg.contains("youtube.music") -> "YOUTUBE MUSIC"
                        pkg.contains("youtube") -> "YOUTUBE VIDEO"
                        pkg.contains("spotify") -> "SPOTIFY"
                        else -> "MEDIA PLAYER"
                    }

                    ExternalMediaManager.update(
                        ExternalMediaData(
                            packageName = pkg,
                            sourceName = source,
                            title = title,
                            artist = text,
                            artBitmap = art,
                            isPlaying = isPlaying,
                            mediaController = mediaCtrl
                        )
                    )

                    // Daca un player exterior ruleaza activ, opreste radioul intern
                    if (isPlaying) {
                        val stopRadioIntent = Intent("com.bmw.carlauncher.ACTION_STOP_RADIO").apply {
                            setPackage(packageName)
                        }
                        sendBroadcast(stopRadioIntent)
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return

        if (pkg == "com.waze" || pkg == "com.google.android.apps.maps") {
            val navData = WazeNavData(
                distance = "--",
                road = "Navigatie oprita",
                eta = "Porniti Waze pentru directii",
                isNavigating = false
            )
            WazeNavManager.update(navData)
        }

        if (pkg == ExternalMediaManager.currentData.packageName) {
            ExternalMediaManager.update(
                ExternalMediaData(
                    packageName = "",
                    sourceName = "",
                    title = "",
                    artist = "",
                    artBitmap = null,
                    isPlaying = false
                )
            )
        }
    }
}
