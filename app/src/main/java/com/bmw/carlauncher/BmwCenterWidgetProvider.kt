package com.bmw.carlauncher

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class BmwCenterWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_PLAY_PAUSE = "com.bmw.carlauncher.WIDGET_PLAY_PAUSE"
        const val ACTION_PREV = "com.bmw.carlauncher.WIDGET_PREV"
        const val ACTION_NEXT = "com.bmw.carlauncher.WIDGET_NEXT"
        const val ACTION_OPEN_APP = "com.bmw.carlauncher.WIDGET_OPEN_APP"

        fun updateAllWidgets(context: Context) {
            try {
                val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
                val component = ComponentName(context, BmwCenterWidgetProvider::class.java)
                val ids = appWidgetManager.getAppWidgetIds(component)
                if (ids != null && ids.isNotEmpty()) {
                    for (id in ids) {
                        updateWidget(context, appWidgetManager, id)
                    }
                }
            } catch (_: Exception) {}
        }

        fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_bmw_center)

            val isRadio = RadioService.isRadioPlaying
            val extData = ExternalMediaManager.currentData
            val isExtPlaying = extData.isPlaying
            val isPlaying = isRadio || isExtPlaying

            val (displayTitle, displayArtist, customArt) = when {
                isRadio -> {
                    val t = RadioService.currentTrackTitle.ifEmpty { RadioService.currentStationName }
                    val a = RadioService.currentTrackArtist.ifEmpty { "Radio Live" }
                    Triple(t, a, RadioService.currentStationBitmap)
                }
                isExtPlaying || extData.title.isNotEmpty() -> {
                    val t = extData.title
                    val a = extData.artist.ifEmpty { extData.sourceName.ifEmpty { "Media Player" } }
                    Triple(t, a, extData.artBitmap)
                }
                else -> {
                    Triple("", "", null)
                }
            }

            // Rendare bitmap cadran rotund 3D BMW
            val dialBitmap = renderDialBitmap(context, 480, isPlaying, isRadio, customArt)
            views.setImageViewBitmap(R.id.widget_dial_image, dialBitmap)

            // Titlu si Artist
            if (displayTitle.isNotEmpty()) {
                views.setViewVisibility(R.id.widget_info_container, View.VISIBLE)
                views.setTextViewText(R.id.widget_title, displayTitle)
                views.setTextViewText(R.id.widget_artist, displayArtist)
            } else {
                views.setViewVisibility(R.id.widget_info_container, View.GONE)
            }

            // Buton Play / Pause icon
            views.setImageViewResource(
                R.id.widget_btn_play_pause,
                if (isPlaying) R.drawable.ic_media_pause else R.drawable.ic_media_play
            )

            // Intent-uri pentru butoane
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openAppPi = PendingIntent.getActivity(
                context, 301, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openAppPi)
            views.setOnClickPendingIntent(R.id.widget_dial_image, openAppPi)

            // Buton Play / Pause
            val playPauseIntent = Intent(context, BmwCenterWidgetProvider::class.java).apply {
                action = ACTION_PLAY_PAUSE
            }
            val playPausePi = PendingIntent.getBroadcast(
                context, 302, playPauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_play_pause, playPausePi)

            // Buton Prev
            val prevIntent = Intent(context, BmwCenterWidgetProvider::class.java).apply {
                action = ACTION_PREV
            }
            val prevPi = PendingIntent.getBroadcast(
                context, 303, prevIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_prev, prevPi)

            // Buton Next
            val nextIntent = Intent(context, BmwCenterWidgetProvider::class.java).apply {
                action = ACTION_NEXT
            }
            val nextPi = PendingIntent.getBroadcast(
                context, 304, nextIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_next, nextPi)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        private fun renderDialBitmap(
            context: Context,
            size: Int,
            isPlaying: Boolean,
            isRadio: Boolean,
            customArt: Bitmap?
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val center = size / 2f
            val radius = size / 2f

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

            // 1. Fundal cadran negru adanc
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#060709")
            canvas.drawCircle(center, center, radius, paint)

            // 2. Imaginea centrala rotunda (Artwork / Radio Logo / BMW Logo)
            val innerRadius = radius * 0.82f
            val srcBitmap: Bitmap = if (customArt != null && !customArt.isRecycled) {
                customArt
            } else {
                BitmapFactory.decodeResource(context.resources, R.drawable.bmw_logo)
            }

            if (srcBitmap != null && !srcBitmap.isRecycled) {
                val shader = BitmapShader(srcBitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                val matrix = Matrix()
                val scale = (innerRadius * 2f) / Math.min(srcBitmap.width, srcBitmap.height).toFloat()
                matrix.setScale(scale, scale)
                matrix.postTranslate(
                    center - (srcBitmap.width * scale) / 2f,
                    center - (srcBitmap.height * scale) / 2f
                )
                shader.setLocalMatrix(matrix)

                paint.shader = shader
                canvas.drawCircle(center, center, innerRadius, paint)
                paint.shader = null
            }

            // 3. Umbra interioara subtila pentru efect de adancime 3D
            val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            val vignetteShader = RadialGradient(
                center, center, innerRadius,
                intArrayOf(Color.TRANSPARENT, Color.argb(40, 0, 0, 0), Color.argb(160, 0, 0, 0)),
                floatArrayOf(0.6f, 0.85f, 1f),
                Shader.TileMode.CLAMP
            )
            vignettePaint.shader = vignetteShader
            canvas.drawCircle(center, center, innerRadius, vignettePaint)

            // 4. Inel Neon BMW Amber
            val neonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = size * 0.022f
                color = if (isPlaying) Color.parseColor("#FFAA00") else Color.parseColor("#CC7700")
            }
            canvas.drawCircle(center, center, innerRadius + neonPaint.strokeWidth / 2f, neonPaint)

            // 5. Rama metalica 3D solida din crom periat (Chrome Bezel)
            val bezelWidth = size * 0.055f
            val bezelRadius = radius - bezelWidth / 2f
            val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = bezelWidth
                shader = SweepGradient(
                    center, center,
                    intArrayOf(
                        Color.parseColor("#2B2E35"),
                        Color.parseColor("#E0E6EE"),
                        Color.parseColor("#1B1E22"),
                        Color.parseColor("#D0D8E2"),
                        Color.parseColor("#2B2E35")
                    ),
                    null
                )
            }
            canvas.drawCircle(center, center, bezelRadius, chromePaint)

            return bitmap
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                val isRadio = RadioService.isRadioPlaying
                val isExtPlaying = ExternalMediaManager.currentData.isPlaying
                if (isRadio) {
                    val stopIntent = Intent(context, RadioService::class.java).apply {
                        action = RadioService.ACTION_STOP_RADIO
                    }
                    context.sendBroadcast(Intent(RadioService.ACTION_STOP_RADIO).setPackage(context.packageName))
                } else if (isExtPlaying) {
                    try {
                        ExternalMediaManager.getActiveMusicController(context)?.transportControls?.pause()
                    } catch (_: Exception) {}
                } else {
                    // Nimic nu canta -> porneste radioul
                    val startIntent = Intent(context, RadioService::class.java).apply {
                        action = RadioService.ACTION_START_PLAY
                    }
                    ContextCompat.startForegroundService(context, startIntent)
                }
                updateAllWidgets(context)
            }
            ACTION_NEXT -> {
                val isExtPlaying = ExternalMediaManager.currentData.isPlaying
                if (isExtPlaying) {
                    try {
                        ExternalMediaManager.getActiveMusicController(context)?.transportControls?.skipToNext()
                    } catch (_: Exception) {}
                } else {
                    // Schimba postul radio urmator
                    val stations = StationManager.getStations(context)
                    if (stations.isNotEmpty()) {
                        val curIdx = StationManager.getLastIndex(context)
                        val nextIdx = (curIdx + 1) % stations.size
                        StationManager.saveLastIndex(context, nextIdx)
                        val startIntent = Intent(context, RadioService::class.java).apply {
                            action = RadioService.ACTION_START_PLAY
                        }
                        ContextCompat.startForegroundService(context, startIntent)
                    }
                }
                updateAllWidgets(context)
            }
            ACTION_PREV -> {
                val isExtPlaying = ExternalMediaManager.currentData.isPlaying
                if (isExtPlaying) {
                    try {
                        ExternalMediaManager.getActiveMusicController(context)?.transportControls?.skipToPrevious()
                    } catch (_: Exception) {}
                } else {
                    // Schimba postul radio anterior
                    val stations = StationManager.getStations(context)
                    if (stations.isNotEmpty()) {
                        val curIdx = StationManager.getLastIndex(context)
                        val prevIdx = (curIdx - 1 + stations.size) % stations.size
                        StationManager.saveLastIndex(context, prevIdx)
                        val startIntent = Intent(context, RadioService::class.java).apply {
                            action = RadioService.ACTION_START_PLAY
                        }
                        ContextCompat.startForegroundService(context, startIntent)
                    }
                }
                updateAllWidgets(context)
            }
        }
    }
}
