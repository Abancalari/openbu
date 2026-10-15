package org.cygnusx1.openbu.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.cygnusx1.openbu.MainActivity
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.network.BambuCameraClient
import org.cygnusx1.openbu.network.BambuMqttClient
import org.cygnusx1.openbu.network.PrinterStatus
import org.cygnusx1.openbu.viewmodel.BambuStreamViewModel
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference

class PrinterWidgetProvider : AppWidgetProvider() {

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val interval = prefs.getInt("refresh_interval_sec", 30)
        schedulePeriodicRefresh(context, interval)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        cancelPeriodicRefresh(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (widgetId in appWidgetIds) {
            updateSingleWidget(context, appWidgetManager, widgetId, null, null, null, null)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val interval = prefs.getInt("refresh_interval_sec", 30)
        schedulePeriodicRefresh(context, interval)
        refreshWidgetDataAsync(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH_WIDGET || intent.action == ACTION_AUTO_REFRESH_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PrinterWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(thisWidget)
            if (ids.isEmpty()) {
                cancelPeriodicRefresh(context)
                return
            }
            for (id in ids) {
                updateSingleWidget(context, appWidgetManager, id, null, null, null, null)
            }
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val interval = prefs.getInt("refresh_interval_sec", 30)
            schedulePeriodicRefresh(context, interval)
            refreshWidgetDataAsync(context)
        }
    }

    private fun refreshWidgetDataAsync(context: Context) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val vm = activeViewModel?.get()
                if (vm != null) {
                    val refreshedByVm = vm.refreshForWidgetSuspend()
                    if (!refreshedByVm) {
                        pollPrinterDirectly(appContext)
                    }
                } else {
                    pollPrinterDirectly(appContext)
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshWidgetDataAsync error", e)
            } finally {
                try {
                    pendingResult.finish()
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        private const val TAG = "PrinterWidget"
        const val ACTION_REFRESH_WIDGET = "org.cygnusx1.openbu.widget.ACTION_REFRESH_WIDGET"
        const val ACTION_AUTO_REFRESH_WIDGET = "org.cygnusx1.openbu.widget.ACTION_AUTO_REFRESH_WIDGET"
        private const val PREFS_NAME = "printer_widget_prefs"
        private const val SNAPSHOT_FILENAME = "widget_snapshot.jpg"
        private const val ALARM_REQUEST_CODE = 9481

        @Volatile
        private var memoryCachedBitmap: Bitmap? = null

        @Volatile
        var activeViewModel: WeakReference<BambuStreamViewModel>? = null

        fun cacheConnectionParams(
            context: Context,
            ip: String,
            serialNumber: String,
            accessCode: String,
            mqttClientId: String,
            usesMjpeg: Boolean,
            printerName: String,
            refreshIntervalSec: Int,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (ip.isNotBlank()) putString("conn_ip", ip)
                if (serialNumber.isNotBlank()) putString("conn_serial", serialNumber)
                if (accessCode.isNotBlank()) putString("conn_access_code", accessCode)
                if (mqttClientId.isNotBlank()) putString("conn_mqtt_client_id", mqttClientId)
                putBoolean("conn_uses_mjpeg", usesMjpeg)
                if (printerName.isNotBlank()) putString("printer_name", printerName)
                putInt("refresh_interval_sec", refreshIntervalSec.coerceIn(15, 60))
                apply()
            }
        }

        fun schedulePeriodicRefresh(context: Context, intervalSeconds: Int) {
            try {
                val appContext = context.applicationContext
                val appWidgetManager = AppWidgetManager.getInstance(appContext)
                val thisWidget = ComponentName(appContext, PrinterWidgetProvider::class.java)
                val ids = appWidgetManager.getAppWidgetIds(thisWidget)
                if (ids.isEmpty()) return

                val safeSec = intervalSeconds.coerceIn(15, 60)
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putInt("refresh_interval_sec", safeSec).apply()

                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(appContext, PrinterWidgetProvider::class.java).apply {
                    action = ACTION_AUTO_REFRESH_WIDGET
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    ALARM_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                // Use ELAPSED_REALTIME (non-wakeup) so we never wake the CPU while screen is off,
                // but refresh reliably while the user is using their device.
                alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + safeSec * 1000L,
                    pendingIntent,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule widget refresh alarm", e)
            }
        }

        fun cancelPeriodicRefresh(context: Context) {
            try {
                val appContext = context.applicationContext
                val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
                val intent = Intent(appContext, PrinterWidgetProvider::class.java).apply {
                    action = ACTION_AUTO_REFRESH_WIDGET
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    ALARM_REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
                )
                if (pendingIntent != null) {
                    alarmManager.cancel(pendingIntent)
                }
            } catch (_: Exception) {}
        }

        suspend fun pollPrinterDirectly(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean("user_disconnected", false)) return

            val ip = prefs.getString("conn_ip", "") ?: ""
            val serial = prefs.getString("conn_serial", "") ?: ""
            val accessCode = prefs.getString("conn_access_code", "") ?: ""
            val clientId = prefs.getString("conn_mqtt_client_id", "openbu_widget") ?: "openbu_widget"
            val usesMjpeg = prefs.getBoolean("conn_uses_mjpeg", true)
            val printerName = prefs.getString("printer_name", "Bambu Lab") ?: "Bambu Lab"

            if (ip.isBlank() || serial.isBlank() || accessCode.isBlank()) return

            // Seed with last cached status so partial MQTT reports don't reset fields to default
            val cachedStatus = PrinterStatus(
                gcodeState = prefs.getString("gcode_state", "IDLE") ?: "IDLE",
                subtaskName = prefs.getString("subtask_name", "") ?: "",
                mcPercent = prefs.getInt("mc_percent", 0),
                mcRemainingTime = prefs.getInt("remaining_time", 0),
                nozzleTemper = prefs.getFloat("nozzle_temp", 0f),
                bedTemper = prefs.getFloat("bed_temp", 0f),
            )

            val mqtt = BambuMqttClient(
                ip = ip,
                accessCode = accessCode,
                serialNumber = serial,
                clientId = clientId,
                initialStatus = cachedStatus,
            )
            val status = mqtt.fetchStatusOnce(timeoutMs = 6_000)

            val frame = if (usesMjpeg) {
                try {
                    withTimeoutOrNull(5_000L) {
                        BambuCameraClient(
                            ip = ip,
                            accessCode = accessCode,
                            connectTimeoutMs = 4_000,
                            readTimeoutMs = 4_000,
                        ).frameFlow().firstOrNull()
                    }
                } catch (_: Exception) {
                    null
                }
            } else null

            if (status != null || frame != null) {
                prefs.edit().putInt("consecutive_failures", 0).apply()
                updateAll(
                    context = context,
                    rawBitmap = frame,
                    status = status,
                    printerName = printerName,
                    isConnected = true,
                )
            } else {
                val failures = prefs.getInt("consecutive_failures", 0) + 1
                prefs.edit().putInt("consecutive_failures", failures).apply()
                // Only flip widget to OFFLINE after 3 consecutive unreachable checks
                if (failures >= 3) {
                    updateAll(
                        context = context,
                        rawBitmap = null,
                        status = null,
                        printerName = printerName,
                        isConnected = false,
                    )
                }
            }
        }

        fun updateAll(
            context: Context,
            rawBitmap: Bitmap?,
            status: PrinterStatus?,
            printerName: String?,
            isConnected: Boolean?,
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
                    if (isConnected != null) {
                        putBoolean("is_connected", isConnected)
                        if (isConnected) {
                            putInt("consecutive_failures", 0)
                        }
                    }
                    if (!printerName.isNullOrBlank()) {
                        putString("printer_name", printerName)
                    }
                    status?.let { s ->
                        putString("gcode_state", s.gcodeState)
                        val name = s.subtaskName.ifBlank { s.gcodeFile.substringAfterLast("/") }
                        if (name.isNotBlank() || s.gcodeState.equals("IDLE", ignoreCase = true)) {
                            putString("subtask_name", name)
                        }
                        putInt("mc_percent", s.mcPercent)
                        putInt("remaining_time", s.mcRemainingTime)
                        if (s.nozzleTemper > 0f || s.bedTemper > 0f || s.gcodeState.equals("IDLE", ignoreCase = true)) {
                            putFloat("nozzle_temp", s.nozzleTemper)
                            putFloat("bed_temp", s.bedTemper)
                        }
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
