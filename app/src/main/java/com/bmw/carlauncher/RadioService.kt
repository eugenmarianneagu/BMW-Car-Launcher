package com.bmw.carlauncher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

@OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer
    private var stations = mutableListOf<RadioStation>()
    private val artworkCache = mutableMapOf<Int, ByteArray>()

    private val stopRadioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.bmw.carlauncher.ACTION_STOP_RADIO") {
                if (::player.isInitialized && player.isPlaying) {
                    player.pause()
                }
            }
        }
    }

    companion object {
        const val ACTION_RELOAD_STATIONS = "com.bmw.carlauncher.RELOAD_STATIONS"
        const val ACTION_RESTART_STANDBY = "com.bmw.carlauncher.RESTART_STANDBY"
        private const val BG_CHANNEL_ID = "bmw_radio_bg_channel"
        private const val BG_NOTIF_ID = 2002

        fun scheduleRestart(context: Context) {
            try {
                val intent = Intent(context, RestartReceiver::class.java).apply {
                    action = "com.bmw.carlauncher.RESTART_SERVICE"
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context,
                    101,
                    intent,
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000L,
                    pendingIntent
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureForegroundStandby()

        val filter = IntentFilter("com.bmw.carlauncher.ACTION_STOP_RADIO")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopRadioReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stopRadioReceiver, filter)
            }
        } catch (_: Exception) {}

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // Porneste pe pauza (autoPlay = false) cand serviciul este creat/repornit in fundal
        loadPlaylist(autoPlay = false)

        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val index = player.currentMediaItemIndex
                if (index in stations.indices) {
                    StationManager.saveLastIndex(this@RadioService, index)
                    fetchOrGenerateLogo(index, stations[index])
                }
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata[i]
                    if (entry is IcyInfo) {
                        val rawTitle = entry.title?.trim() ?: ""
                        if (rawTitle.isNotEmpty()) {
                            updateLiveStreamTitle(rawTitle)
                        }
                    }
                }
            }
        })

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val connectionResult = super.onConnect(session, controller)
                val sessionCommands = connectionResult.availableSessionCommands
                    .buildUpon()
                    .add(SessionCommand(ACTION_RELOAD_STATIONS, Bundle.EMPTY))
                    .build()

                return MediaSession.ConnectionResult.accept(
                    sessionCommands,
                    connectionResult.availablePlayerCommands
                )
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction == ACTION_RELOAD_STATIONS) {
                    val wasPlaying = player.isPlaying
                    loadPlaylist(autoPlay = wasPlaying)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                return super.onCustomCommand(session, controller, customCommand, args)
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()

        try {
            startService(Intent(this, RadioService::class.java))
        } catch (_: Exception) {
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ensureForegroundStandby()

        if (intent?.action == ACTION_RESTART_STANDBY) {
            player.playWhenReady = false
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
        }
        return START_STICKY
    }

    private fun ensureForegroundStandby() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(BG_CHANNEL_ID) == null) {
                    val ch = NotificationChannel(
                        BG_CHANNEL_ID,
                        "BMW Car Launcher",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        setShowBadge(false)
                    }
                    nm.createNotificationChannel(ch)
                }
            }
            val notif: Notification = NotificationCompat.Builder(this, BG_CHANNEL_ID)
                .setContentTitle("BMW Car Launcher")
                .setContentText("In standby (Pe pauza)")
                .setSmallIcon(android.R.drawable.ic_media_pause)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()

            startForeground(BG_NOTIF_ID, notif)
        } catch (_: Exception) {
        }
    }

    private fun updateLiveStreamTitle(rawTitle: String) {
        val index = player.currentMediaItemIndex
        if (index !in stations.indices) return
        val station = stations[index]
        val artBytes = artworkCache[index] ?: generateStationBitmapBytes(station.name)

        val parts = rawTitle.split(" - ", limit = 2)
        val displayTitle: String
        val displayArtist: String

        if (parts.size == 2) {
            displayArtist = "${parts[0].trim()} • ${station.name}"
            displayTitle = parts[1].trim()
        } else {
            displayArtist = station.name
            displayTitle = rawTitle
        }

        val currentItem = player.getMediaItemAt(index)
        val updatedMeta = currentItem.mediaMetadata.buildUpon()
            .setTitle(displayTitle)
            .setArtist(displayArtist)
            .setStation(station.name)
            .setAlbumTitle(station.name)
            .setArtworkData(artBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()

        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMeta)
            .build()

        player.replaceMediaItem(index, updatedItem)
    }

    private fun loadPlaylist(autoPlay: Boolean) {
        stations = StationManager.getStations(this)
        artworkCache.clear()

        val items = stations.mapIndexed { index, station ->
            val artworkBytes = generateStationBitmapBytes(station.name)
            artworkCache[index] = artworkBytes

            val metadata = MediaMetadata.Builder()
                .setTitle(station.name)
                .setArtist(station.genre)
                .setStation(station.name)
                .setAlbumTitle(station.name)
                .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                .apply {
                    if (station.logoUrl.isNotBlank()) {
                        setArtworkUri(Uri.parse(station.logoUrl))
                    }
                }
                .build()

            MediaItem.Builder()
                .setMediaId(station.name)
                .setUri(station.url)
                .setMediaMetadata(metadata)
                .build()
        }

        player.setMediaItems(items)
        player.repeatMode = ExoPlayer.REPEAT_MODE_ALL
        val lastIndex = StationManager.getLastIndex(this).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        if (items.isNotEmpty()) {
            player.seekTo(lastIndex, C.TIME_UNSET)
            fetchOrGenerateLogo(lastIndex, stations[lastIndex])
        }
        player.playWhenReady = autoPlay
        player.prepare()
    }

    private fun fetchOrGenerateLogo(index: Int, station: RadioStation) {
        if (station.logoUrl.isBlank()) return
        thread {
            try {
                val url = URL(station.logoUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doInput = true
                conn.connect()
                val bmp = BitmapFactory.decodeStream(conn.inputStream)
                if (bmp != null && bmp.width > 32) {
                    val stream = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
                    val bytes = stream.toByteArray()
                    artworkCache[index] = bytes
                    mainExecutor.execute {
                        if (index < player.mediaItemCount) {
                            val currentItem = player.getMediaItemAt(index)
                            val updatedMeta = currentItem.mediaMetadata.buildUpon()
                                .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                .build()
                            val updatedItem = currentItem.buildUpon()
                                .setMediaMetadata(updatedMeta)
                                .build()
                            player.replaceMediaItem(index, updatedItem)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun generateStationBitmapBytes(name: String): ByteArray {
        val size = 400
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        val colors = listOf("#D32F2F", "#1976D2", "#388E3C", "#F57C00", "#7B1FA2", "#00796B")
        val colorHex = colors[Math.abs(name.hashCode()) % colors.size]
        canvas.drawColor(Color.parseColor(colorHex))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = 54f
        }

        val words = name.split(" ")
        if (words.size >= 2) {
            canvas.drawText(words[0], size / 2f, size / 2f - 15f, paint)
            canvas.drawText(words.drop(1).joinToString(" "), size / 2f, size / 2f + 50f, paint)
        } else {
            canvas.drawText(name.take(10), size / 2f, size / 2f + 20f, paint)
        }

        val stream = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 90, stream)
        return stream.toByteArray()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        scheduleRestart(applicationContext)
    }

    override fun onDestroy() {
        scheduleRestart(applicationContext)
        try {
            unregisterReceiver(stopRadioReceiver)
        } catch (_: Exception) {}
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val serviceIntent = Intent(context, RadioService::class.java).apply {
            action = RadioService.ACTION_RESTART_STANDBY
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            try {
                context.startService(serviceIntent)
            } catch (_: Exception) {
            }
        }
    }
}