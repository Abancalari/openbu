package org.cygnusx1.openbu.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import org.cygnusx1.openbu.MainActivity
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.network.PrinterStatus
import java.io.File
import java.io.FileOutputStream

class PrinterWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId, null, null, null, null)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PrinterWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(thisWidget)
            for (id in ids) {
                updateSingleWidget(context, appWidgetManager, id, null, null, null, null)
            }
        }
    }

    companion object {
        private const val TAG = "PrinterWidget"
        const val ACTION_REFRESH_WIDGET = "org.cygnusx1.openbu.widget.ACTION_REFRESH_WIDGET"
        private const val PREFS_NAME = "printer_widget_prefs"
        private const val SNAPSHOT_FILENAME = "widget_snapshot.jpg"

        @Volatile
        private var memoryCachedBitmap: Bitmap? = null

        fun updateAll(
            context: Context,
            rawBitmap: Bitmap?,
            status: PrinterStatus?,
            printerName: String?,
            isConnected: Boolean,
        ) {
            try {
                val scaledBitmap = rawBitmap?.let { scaleBitmapForWidget(it) }
                if (scaledBitmap != null) {
                    memoryCachedBitmap = scaledBitmap
                    saveSnapshotToFile(context, scaledBitmap)
                }

                // Cache telemetry
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().apply {
                    putBoolean("is_connected", isConnected)
                    printerName?.let { putString("printer_name", it) }
                    status?.let { s ->
                        putString("gcode_state", s.gcodeState)
                        val name = s.subtaskName.ifBlank { s.gcodeFile.substringAfterLast("/") }
                        putString("subtask_name", name)
                        putInt("mc_percent", s.mcPercent)
                        putInt("remaining_time", s.mcRemainingTime)
                        putFloat("nozzle_temp", s.nozzleTemper)
                        putFloat("bed_temp", s.bedTemper)
                    }
                    apply()
                }

                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, PrinterWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                if (appWidgetIds.isEmpty()) return

                for (id in appWidgetIds) {
                    updateSingleWidget(
                        context = context,
                        appWidgetManager = appWidgetManager,
                        widgetId = id,
                        bitmap = memoryCachedBitmap,
                        status = status,
                        printerName = printerName,
                        isConnected = isConnected,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update widgets", e)
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            widgetId: Int,
            bitmap: Bitmap?,
            status: PrinterStatus?,
            printerName: String?,
            isConnected: Boolean?,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val connected = isConnected ?: prefs.getBoolean("is_connected", false)
            val name = (printerName ?: prefs.getString("printer_name", "Bambu Lab")) ?: "Bambu Lab"
            val state = status?.gcodeState ?: prefs.getString("gcode_state", "IDLE") ?: "IDLE"
            val subtask = status?.let { s -> s.subtaskName.ifBlank { s.gcodeFile.substringAfterLast("/") } }
                ?: prefs.getString("subtask_name", "") ?: ""
            val percent = status?.mcPercent ?: prefs.getInt("mc_percent", 0)
            val remainingMin = status?.mcRemainingTime ?: prefs.getInt("remaining_time", 0)
            val nozzleTemp = status?.nozzleTemper ?: prefs.getFloat("nozzle_temp", 0f)
            val bedTemp = status?.bedTemper ?: prefs.getFloat("bed_temp", 0f)

            val views = RemoteViews(context.packageName, R.layout.widget_printer_status)

            // Printer Name
            views.setTextViewText(R.id.widget_printer_name, name)

            // Status Badge
            val displayState = when {
                !connected -> "OFFLINE"
                state.equals("RUNNING", ignoreCase = true) -> "PRINTING"
                state.equals("PAUSE", ignoreCase = true) -> "PAUSED"
                state.equals("FINISH", ignoreCase = true) -> "DONE"
                state.equals("FAILED", ignoreCase = true) -> "FAILED"
                state.isBlank() -> "IDLE"
                else -> state.uppercase()
            }
            views.setTextViewText(R.id.widget_status_badge, displayState)

            // Subtask Name
            val fileLabel = when {
                !connected -> context.getString(R.string.printer_not_connected_warning)
                subtask.isNotBlank() -> subtask
                state.equals("RUNNING", ignoreCase = true) -> "Printing..."
                else -> "Ready to print"
            }
            views.setTextViewText(R.id.widget_subtask_name, fileLabel)

            // Progress Bar
            val safePercent = percent.coerceIn(0, 100)
            views.setProgressBar(R.id.widget_progress_bar, 100, safePercent, false)

            // Progress Text & Remaining Time
            val progressText = if (safePercent > 0 || state.equals("RUNNING", ignoreCase = true)) {
                if (remainingMin > 0) {
                    "$safePercent% • ${formatRemaining(remainingMin)}"
                } else {
                    "$safePercent%"
                }
            } else {
                "0%"
            }
            views.setTextViewText(R.id.widget_progress_text, progressText)

            // Temperatures Text
            val nozzleStr = if (connected && nozzleTemp > 0f) "%.0f°C".format(nozzleTemp) else "--°C"
            val bedStr = if (connected && bedTemp > 0f) "%.0f°C".format(bedTemp) else "--°C"
            views.setTextViewText(R.id.widget_temps_text, "N: $nozzleStr  B: $bedStr")

            // Snapshot Image
            val currentBitmap = bitmap ?: memoryCachedBitmap ?: loadCachedSnapshot(context)
            if (currentBitmap != null) {
                views.setViewVisibility(R.id.widget_snapshot, View.VISIBLE)
                views.setViewVisibility(R.id.widget_no_stream_text, View.GONE)
                views.setImageViewBitmap(R.id.widget_snapshot, currentBitmap)
            } else {
                views.setViewVisibility(R.id.widget_snapshot, View.GONE)
                views.setViewVisibility(R.id.widget_no_stream_text, View.VISIBLE)
            }

            // Click on widget root opens MainActivity
            val clickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingClick = PendingIntent.getActivity(
                context,
                0,
                clickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingClick)

            // Click on refresh button
            val refreshIntent = Intent(context, PrinterWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            val pendingRefresh = PendingIntent.getBroadcast(
                context,
                widgetId,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_button, pendingRefresh)

            appWidgetManager.updateAppWidget(widgetId, views)
        }

        private fun formatRemaining(minutes: Int): String {
            if (minutes <= 0) return ""
            val h = minutes / 60
            val m = minutes % 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        private fun scaleBitmapForWidget(src: Bitmap, maxWidth: Int = 480, maxHeight: Int = 270): Bitmap {
            val width = src.width
            val height = src.height
            if (width <= maxWidth && height <= maxHeight) {
                return src
            }
            val ratio = width.toFloat() / height.toFloat()
            val targetW: Int
            val targetH: Int
            if (ratio > (maxWidth.toFloat() / maxHeight.toFloat())) {
                targetW = maxWidth
                targetH = (maxWidth / ratio).toInt().coerceAtLeast(1)
            } else {
                targetH = maxHeight
                targetW = (maxHeight * ratio).toInt().coerceAtLeast(1)
            }
            return Bitmap.createScaledBitmap(src, targetW, targetH, true)
        }

        private fun saveSnapshotToFile(context: Context, bitmap: Bitmap) {
            try {
                val file = File(context.filesDir, SNAPSHOT_FILENAME)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cache widget snapshot", e)
            }
        }

        private fun loadCachedSnapshot(context: Context): Bitmap? {
            return try {
                val file = File(context.filesDir, SNAPSHOT_FILENAME)
                if (file.exists() && file.length() > 0) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else null
            } catch (e: Exception) {
                null
            }
        }
    }
}
