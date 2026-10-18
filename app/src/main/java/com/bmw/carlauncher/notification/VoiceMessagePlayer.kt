package com.bmw.carlauncher.notification

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream

object VoiceMessagePlayer {
    private const val TAG = "VoiceMessagePlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingFile: File? = null
    private var isPlayingState: Boolean = false

    interface PlaybackListener {
        fun onPlaybackStarted()
        fun onPlaybackPaused()
        fun onPlaybackCompleted()
        fun onPlaybackError(error: String)
    }

    var listener: PlaybackListener? = null

    fun isPlaying(): Boolean = isPlayingState

    fun getCurrentFile(): File? = currentPlayingFile

    /**
     * Caută cel mai recent fișier de notă vocală (.opus, .m4a, .mp3, etc.)
     * asociat cu timestamp-ul notificării.
     */
    fun findLatestVoiceNote(packageName: String, notifTimestamp: Long): File? {
        try {
            val storageDir = Environment.getExternalStorageDirectory() ?: return null

            val candidateDirs = mutableListOf<File>()

            // 1. WhatsApp Folders (Standard, Business, Legacy)
            candidateDirs.add(File(storageDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes"))
            candidateDirs.add(File(storageDir, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes"))
            candidateDirs.add(File(storageDir, "WhatsApp/Media/WhatsApp Voice Notes"))
            candidateDirs.add(File(storageDir, "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"))
            candidateDirs.add(File(storageDir, "WhatsApp/Media/WhatsApp Audio"))

            // 2. Telegram Folders
            candidateDirs.add(File(storageDir, "Android/media/org.telegram.messenger/Telegram/Telegram Audio"))
            candidateDirs.add(File(storageDir, "Telegram/Telegram Audio"))

            val audioExtensions = setOf("opus", "m4a", "ogg", "mp3", "aac", "wav", "3gp")
            val candidateFiles = mutableListOf<File>()

            for (dir in candidateDirs) {
                if (!dir.exists() || !dir.isDirectory) continue
                // Scanăm folderele din interior (ex: 202643) și directorul curent
                val files = dir.listFiles() ?: continue
                for (item in files) {
                    if (item.isDirectory) {
                        val subFiles = item.listFiles() ?: continue
                        for (subFile in subFiles) {
                            if (subFile.isFile && audioExtensions.contains(subFile.extension.lowercase())) {
                                candidateFiles.add(subFile)
                            }
                        }
                    } else if (item.isFile && audioExtensions.contains(item.extension.lowercase())) {
                        candidateFiles.add(item)
                    }
                }
            }

            if (candidateFiles.isEmpty()) {
                Log.d(TAG, "Nu s-au gasit fisiere audio in directoarele WhatsApp/Media")
                return null
            }

            // Sortăm după data modificării (cel mai nou primul)
            candidateFiles.sortByDescending { it.lastModified() }

            val newest = candidateFiles.first()
            val timeDiff = Math.abs(newest.lastModified() - notifTimestamp)
            Log.d(TAG, "Cel mai nou fisier audio: ${newest.absolutePath}, modified=${newest.lastModified()}, diff=${timeDiff}ms")

            val now = System.currentTimeMillis()
            // Acceptăm dacă a fost modificat în ultimele 10 minute
            if (Math.abs(now - newest.lastModified()) < 600_000 || timeDiff < 600_000) {
                return newest
            }

            // Daca e in ultimele 30 minute, il acceptam oricum ca fiind ultimul mesaj receptionat
            if (Math.abs(now - newest.lastModified()) < 1_800_000) {
                return newest
            }

            return newest // Returnăm oricum cel mai nou dacă există
        } catch (e: Exception) {
            Log.e(TAG, "Eroare cautare fisier voce: ${e.message}", e)
            return null
        }
    }

    fun play(file: File) {
        stop()
        try {
            val mp = MediaPlayer()
            mediaPlayer = mp
            currentPlayingFile = file

            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )

            FileInputStream(file).use { fis ->
                mp.setDataSource(fis.fd)
            }

            mp.setOnCompletionListener {
                isPlayingState = false
                currentPlayingFile = null
                listener?.onPlaybackCompleted()
                stop()
            }

            mp.setOnErrorListener { _, what, extra ->
                isPlayingState = false
                currentPlayingFile = null
                Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                listener?.onPlaybackError("Eroare redare audio: $what/$extra")
                stop()
                true
            }

            mp.prepare()
            mp.start()
            isPlayingState = true
            listener?.onPlaybackStarted()
            Log.d(TAG, "Redare pornita cu succes pentru ${file.name}")
        } catch (e: Exception) {
            isPlayingState = false
            currentPlayingFile = null
            Log.e(TAG, "Exceptie la pornirea redarii: ${e.message}", e)
            listener?.onPlaybackError("Eroare: ${e.message}")
            stop()
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
                isPlayingState = false
                listener?.onPlaybackPaused()
                Log.d(TAG, "Redare pusa pe pauza")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare pauza: ${e.message}")
        }
    }

    fun resume() {
        try {
            mediaPlayer?.let { mp ->
                mp.start()
                isPlayingState = true
                listener?.onPlaybackStarted()
                Log.d(TAG, "Redare reluata")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare resume: ${e.message}")
        }
    }

    fun stop() {
        try {
            mediaPlayer?.let { mp ->
                if (mp.isPlaying) {
                    mp.stop()
                }
                mp.reset()
                mp.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Eroare stop: ${e.message}")
        } finally {
            mediaPlayer = null
            isPlayingState = false
        }
    }
}
