package com.bmw.carlauncher

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

@OptIn(UnstableApi::class)
object LocalMusicPlayer {
    private const val TAG = "LocalMusicPlayer"

    interface PlaybackListener {
        fun onTrackChanged(track: LocalTrack?)
        fun onPlaybackStateChanged(isPlaying: Boolean)
    }

    private var appContext: Context? = null
    private var exoPlayer: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var currentPlaylist: LocalPlaylist? = null
        private set
    val currentQueue = mutableListOf<LocalTrack>()
    private val listeners = mutableListOf<PlaybackListener>()

    val isPlaying: Boolean
        get() = exoPlayer?.isPlaying == true

    val currentTrack: LocalTrack?
        get() {
            val idx = exoPlayer?.currentMediaItemIndex ?: -1
            return if (idx in currentQueue.indices) currentQueue[idx] else null
        }

    val isShuffleEnabled: Boolean
        get() = exoPlayer?.shuffleModeEnabled == true

    val repeatMode: Int
        get() = exoPlayer?.repeatMode ?: Player.REPEAT_MODE_OFF

    fun addListener(listener: PlaybackListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    @Synchronized
    fun initialize(context: Context) {
        if (exoPlayer != null) return
        appContext = context.applicationContext

        val player = ExoPlayer.Builder(context.applicationContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true // handleAudioFocus = true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                notifyPlaybackStateChanged(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                notifyTrackChanged(currentTrack)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    notifyPlaybackStateChanged(false)
                }
            }
        })

        exoPlayer = player
        Log.d(TAG, "LocalMusicPlayer initialized successfully")
    }

    fun playPlaylist(context: Context, playlist: LocalPlaylist, startIndex: Int = 0) {
        initialize(context)
        val player = exoPlayer ?: return

        // Stop Radio
        try {
            val stopIntent = Intent(RadioService.ACTION_STOP_RADIO)
            context.sendBroadcast(stopIntent)
        } catch (_: Exception) {}

        val tracks = LocalMusicManager.getTracksForPlaylist(playlist)
        if (tracks.isEmpty()) {
            Log.w(TAG, "Cannot play empty playlist: ${playlist.name}")
            return
        }

        currentPlaylist = playlist
        currentQueue.clear()
        currentQueue.addAll(tracks)

        val mediaItems = tracks.map { track ->
            val uri = if (track.id > 0) {
                ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, track.id)
            } else {
                Uri.fromFile(File(track.path))
            }
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(track.path)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setAlbumTitle(track.album)
                        .build()
                )
                .build()
        }

        val safeIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        player.setMediaItems(mediaItems, safeIndex, 0L)
        player.prepare()
        player.play()

        notifyTrackChanged(currentTrack)
        notifyPlaybackStateChanged(true)
    }

    fun playSingleTrack(context: Context, track: LocalTrack) {
        initialize(context)
        // Find existing playlist or use all songs
        val allSongsPl = LocalPlaylist(
            id = LocalMusicManager.ID_ALL_SONGS,
            name = "Toate piesele",
            isSystem = true,
            isFolder = false,
            trackPaths = LocalMusicManager.cachedTracks.map { it.path }.toMutableList()
        )
        val idx = LocalMusicManager.cachedTracks.indexOfFirst { it.path == track.path }
        playPlaylist(context, allSongsPl, if (idx >= 0) idx else 0)
    }

    fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE && currentQueue.isNotEmpty()) {
                val pl = currentPlaylist
                if (pl != null && appContext != null) {
                    playPlaylist(appContext!!, pl, 0)
                    return
                }
            }
            // Stop Radio if radio was playing
            appContext?.let { ctx ->
                try {
                    ctx.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO))
                } catch (_: Exception) {}
            }
            player.play()
        }
    }

    fun play() {
        val player = exoPlayer ?: return
        appContext?.let { ctx ->
            try {
                ctx.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO))
            } catch (_: Exception) {}
        }
        player.play()
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun next() {
        val player = exoPlayer ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else if (player.repeatMode == Player.REPEAT_MODE_ALL && currentQueue.isNotEmpty()) {
            player.seekToDefaultPosition(0)
        }
    }

    fun previous() {
        val player = exoPlayer ?: return
        if (player.currentPosition > 3000) {
            player.seekTo(0L)
        } else if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
        } else if (currentQueue.isNotEmpty()) {
            player.seekToDefaultPosition(currentQueue.size - 1)
        }
    }

    fun toggleShuffle(): Boolean {
        val player = exoPlayer ?: return false
        val newShuffle = !player.shuffleModeEnabled
        player.shuffleModeEnabled = newShuffle
        return newShuffle
    }

    fun cycleRepeatMode(): Int {
        val player = exoPlayer ?: return Player.REPEAT_MODE_OFF
        val nextMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        player.repeatMode = nextMode
        return nextMode
    }

    fun loadArtwork(context: Context, track: LocalTrack): Bitmap {
        if (track.albumId > 0) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, track.albumId)
                    val bmp = context.contentResolver.loadThumbnail(uri, Size(512, 512), null)
                    if (bmp != null) return bmp
                }
            } catch (_: Exception) {}
        }
        try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(track.path)
            val artBytes = mmr.embeddedPicture
            mmr.release()
            if (artBytes != null) {
                val decoded = BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size)
                if (decoded != null) return decoded
            }
        } catch (_: Exception) {}
        return createDefaultMusicBitmap(track.title, track.artist)
    }

    fun createDefaultMusicBitmap(title: String, artist: String): Bitmap {
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val cx = size / 2f
        val cy = size / 2f

        // Dark vinyl disc background
        val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#121212")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, cx, bgPaint)

        // Vinyl concentric grooves
        val groovePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#242424")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        for (r in 145..242 step 11) {
            canvas.drawCircle(cx, cy, r.toFloat(), groovePaint)
        }

        // Accent amber outer ring
        val ringPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FF7200")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(cx, cy, 126f, ringPaint)

        // Center vinyl label
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1C1A17")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 124f, labelPaint)

        // Center vinyl hole
        val holePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#0A0A0A")
            style = android.graphics.Paint.Style.FILL
        }
        canvas.drawCircle(cx, cy, 16f, holePaint)

        val titlePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val artistPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#FFB300")
            textSize = 18f
            textAlign = android.graphics.Paint.Align.CENTER
        }

        val displayTitle = if (title.length > 18) title.take(16) + "..." else title
        val displayArtist = if (artist.length > 22) artist.take(20) + "..." else artist

        canvas.drawText(displayTitle, cx, cy - 42f, titlePaint)
        canvas.drawText(displayArtist, cx, cy + 58f, artistPaint)

        return bmp
    }

    private fun notifyTrackChanged(track: LocalTrack?) {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onTrackChanged(track)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error onTrackChanged: ${e.message}", e)
                }
            }
        }
    }

    private fun notifyPlaybackStateChanged(isPlaying: Boolean) {
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener.onPlaybackStateChanged(isPlaying)
                } catch (e: Exception) {
                    Log.e(TAG, "Listener error onPlaybackStateChanged: ${e.message}", e)
                }
            }
        }
    }
}
