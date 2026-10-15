package org.cygnusx1.openbu.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.cygnusx1.openbu.MainActivity
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.network.BambuMqttClient
import org.cygnusx1.openbu.network.PrinterStatus
import org.cygnusx1.openbu.notification.PrintProgressNotificationManager
import org.cygnusx1.openbu.viewmodel.BambuStreamViewModel
import java.io.File
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
            updateSingleWidget(context, appWidgetManager, widgetId, null, null, null)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val interval = prefs.getInt("refresh_interval_sec", 30)
        schedulePeriodicRefresh(context, interval)
        refreshWidgetDataAsync(context, isManual = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        if (action == ACTION_REFRESH_WIDGET || action == ACTION_AUTO_REFRESH_WIDGET) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PrinterWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(thisWidget)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isPrinting = (prefs.getString("gcode_state", "IDLE") ?: "IDLE").uppercase() in setOf("PREPARE", "RUNNING", "PAUSE")

            if (ids.isEmpty() && !isPrinting) {
                cancelPeriodicRefresh(context)
                return
            }

            val interval = prefs.getInt("refresh_interval_sec", 30)
            schedulePeriodicRefresh(context, interval)

            val isManual = action == ACTION_REFRESH_WIDGET
            if (!isManual && !isScreenInteractive(context)) {
                // Screen is off: skip network polling to preserve tablet battery
                return
            }

            for (id in ids) {
                updateSingleWidget(context, appWidgetManager, id, null, null, null)
            }
            refreshWidgetDataAsync(context, isManual = isManual)
        }
    }

    private fun refreshWidgetDataAsync(context: Context, isManual: Boolean) {
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
                Log.w(TAG, "refreshWidgetDataAsync error (manual=$isManual)", e)
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
        private const val LEGACY_SNAPSHOT_FILENAME = "widget_snapshot.jpg"
        private const val ALARM_REQUEST_CODE = 9481

        @Volatile
        var activeViewModel: WeakReference<BambuStreamViewModel>? = null

        fun isScreenInteractive(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isInteractive ?: true
        }

        fun hasPlacedWidgets(context: Context): Boolean {
            return try {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, PrinterWidgetProvider::class.java)
                appWidgetManager.getAppWidgetIds(thisWidget).isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

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
            if (ip.isBlank() || serialNumber.isBlank() || accessCode.isBlank()) return
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
                putString("conn_ip", ip)
                putString("conn_serial", serialNumber)
                putString("conn_access_code", accessCode)
                putString("conn_mqtt_client_id", mqttClientId)
                putBoolean("conn_uses_mjpeg", usesMjpeg)
                putString("printer_name", printerName)
                putInt("refresh_interval_sec", refreshIntervalSec.coerceIn(15, 60))
                apply()
            }
            // Clean up any legacy camera snapshot file from disk
            try {
                val legacyFile = File(context.filesDir, LEGACY_SNAPSHOT_FILENAME)
                if (legacyFile.exists()) legacyFile.delete()
            } catch (_: Exception) {}
        }

        /**
         * Lightweight MQTT-only background poll when the main ViewModel is inactive or disconnected.
         * Does not connect to the camera stream or decode bitmaps, keeping battery impact minimal.
         */
        suspend fun pollPrinterDirectly(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val ip = prefs.getString("conn_ip", null)
            val serial = prefs.getString("conn_serial", null)
            val accessCode = prefs.getString("conn_access_code", null)
            val printerName = prefs.getString("printer_name", "Bambu Lab") ?: "Bambu Lab"

            if (ip.isNullOrBlank() || serial.isNullOrBlank() || accessCode.isNullOrBlank()) {
                return
            }

            val clientId = "openbu_widget_${serial.takeLast(6)}"
            val tempMqtt = BambuMqttClient(
                ip = ip,
                accessCode = accessCode,
                serialNumber = serial,
                clientId = clientId,
            )
            val fetchedStatus = tempMqtt.fetchStatusOnce(timeoutMs = 6_000)

            if (fetchedStatus != null) {
                prefs.edit().putInt("consecutive_failures", 0).apply()
                PrintProgressNotificationManager.updateFromStatus(
                    context = context,
                    status = fetchedStatus,
                    printerName = printerName,
                )
                updateAll(
                    context = context,
                    status = fetchedStatus,
                    printerName = printerName,
                    isConnected = true,
                )
            } else {
                val failures = prefs.getInt("consecutive_failures", 0) + 1
                prefs.edit().putInt("consecutive_failures", failures).apply()
                if (failures >= 3) {
                    updateAll(
                        context = context,
                        status = null,
                        printerName = printerName,
                        isConnected = false,
                    )
                } else {
                    updateAll(
                        context = context,
                        status = null,
                        printerName = printerName,
                        isConnected = null,
                    )
                }
            }
        }

        fun schedulePeriodicRefresh(context: Context, intervalSec: Int) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PrinterWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(thisWidget)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isPrinting = (prefs.getString("gcode_state", "IDLE") ?: "IDLE").uppercase() in setOf("PREPARE", "RUNNING", "PAUSE")
            if (ids.isEmpty() && !isPrinting) return

            val safeIntervalMs = intervalSec.coerceIn(15, 60) * 1000L
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, PrinterWidgetProvider::class.java).apply {
                action = ACTION_AUTO_REFRESH_WIDGET
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + safeIntervalMs,
                safeIntervalMs,
                pendingIntent
            )
        }

        fun cancelPeriodicRefresh(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, PrinterWidgetProvider::class.java).apply {
                action = ACTION_AUTO_REFRESH_WIDGET
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }

        fun updateAll(
            context: Context,
            status: PrinterStatus?,
            printerName: String?,
            isConnected: Boolean? = null,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                if (printerName != null) putString("printer_name", printerName)
                if (isConnected != null) {
                    putBoolean("is_connected", isConnected)
                    if (isConnected) putInt("consecutive_failures", 0)
                }
                if (status != null) {
                    putString("gcode_state", status.gcodeState)
                    putString("subtask_name", status.cleanJobName)
                    putString("stage_label", status.stageLabel)
                    putInt("mc_percent", status.mcPercent)
                    putInt("mc_remaining_time", status.mcRemainingTime)
                    putInt("layer_num", status.layerNum)
                    putInt("total_layer_num", status.totalLayerNum)
                    putLong("last_updated_ms", System.currentTimeMillis())
                }
                apply()
            }

            if (status != null && status.gcodeState.uppercase() in setOf("PREPARE", "RUNNING", "PAUSE")) {
                schedulePeriodicRefresh(context, prefs.getInt("refresh_interval_sec", 30))
            }

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, PrinterWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (appWidgetIds.isEmpty()) return

            for (widgetId in appWidgetIds) {
                updateSingleWidget(
                    context = context,
                    appWidgetManager = appWidgetManager,
                    appWidgetId = widgetId,
                    status = status,
                    printerName = printerName,
                    isConnected = isConnected,
                )
            }
        }

        private fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            status: PrinterStatus?,
            printerName: String?,
            isConnected: Boolean?,
        ) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val views = RemoteViews(context.packageName, R.layout.widget_printer_status)

            val displayPrinterName = printerName ?: prefs.getString("printer_name", "Bambu Lab") ?: "Bambu Lab"
            val connected = isConnected ?: prefs.getBoolean("is_connected", false)
            val gcodeState = status?.gcodeState ?: prefs.getString("gcode_state", "IDLE") ?: "IDLE"
            val subtaskName = status?.cleanJobName ?: prefs.getString("subtask_name", "") ?: ""
            val stageLabel = status?.stageLabel ?: prefs.getString("stage_label", "Idle") ?: "Idle"
            val mcPercent = status?.mcPercent ?: prefs.getInt("mc_percent", 0)
            val mcRemainingTime = status?.mcRemainingTime ?: prefs.getInt("mc_remaining_time", 0)
            val layerNum = status?.layerNum ?: prefs.getInt("layer_num", 0)
            val totalLayerNum = status?.totalLayerNum ?: prefs.getInt("total_layer_num", 0)

            views.setTextViewText(R.id.widget_printer_name, displayPrinterName)

            val stateUpper = if (!connected) "OFFLINE" else gcodeState.uppercase()
            val (badgeText, badgeBgRes) = when (stateUpper) {
                "RUNNING" -> "PRINTING" to R.drawable.widget_badge_running
                "PREPARE" -> "PREPARING" to R.drawable.widget_badge_pause
                "PAUSE" -> "PAUSED" to R.drawable.widget_badge_pause
                "FINISH" -> "COMPLETE" to R.drawable.widget_badge_running
                "FAILED" -> "FAILED" to R.drawable.widget_badge_failed
                "OFFLINE" -> "OFFLINE" to R.drawable.widget_badge_bg
                else -> "IDLE" to R.drawable.widget_badge_bg
            }
            views.setTextViewText(R.id.widget_status_badge, badgeText)
            views.setInt(R.id.widget_status_badge, "setBackgroundResource", badgeBgRes)

            val isActivePrint = stateUpper in setOf("RUNNING", "PREPARE", "PAUSE")

            // Job / File Name
            val jobDisplay = when {
                subtaskName.isNotBlank() && stateUpper != "IDLE" -> subtaskName
                stateUpper == "OFFLINE" && subtaskName.isNotBlank() -> subtaskName
                stateUpper == "OFFLINE" -> "Printer Offline"
                else -> "Ready to print"
            }
            views.setTextViewText(R.id.widget_subtask_name, jobDisplay)

            // Current Stage
            val stageDisplay = when (stateUpper) {
                "OFFLINE" -> "Stage: Disconnected (tap refresh)"
                else -> "Stage: $stageLabel"
            }
            views.setTextViewText(R.id.widget_stage_text, stageDisplay)

            // Completion Percentage & Progress Bar
            val clampedPercent = when (stateUpper) {
                "FINISH" -> 100
                "IDLE" -> 0
                else -> mcPercent.coerceIn(0, 100)
            }
            views.setProgressBar(
                R.id.widget_progress_bar,
                100,
                clampedPercent,
                stateUpper == "PREPARE" && clampedPercent == 0
            )
            views.setTextViewText(R.id.widget_percent_text, "$clampedPercent%")
            val percentColor = when (stateUpper) {
                "RUNNING", "FINISH" -> 0xFF00E676.toInt()
                "PAUSE", "PREPARE" -> 0xFFFFB300.toInt()
                "FAILED" -> 0xFFFF5252.toInt()
                else -> 0xFF787886.toInt()
            }
            views.setTextColor(R.id.widget_percent_text, percentColor)

            // ETA / Remaining Time
            val etaDisplay = when {
                isActivePrint && mcRemainingTime > 0 -> {
                    PrintProgressNotificationManager.formatRemainingAndEta(mcRemainingTime)
                }
                stateUpper == "PREPARE" -> "Heating & calibrating..."
                stateUpper == "PAUSE" -> "Print paused"
                stateUpper == "FINISH" -> "100% • Print finished"
                stateUpper == "FAILED" -> "Print stopped with error"
                stateUpper == "OFFLINE" -> "Last seen status"
                else -> "No active print job"
            }
            views.setTextViewText(R.id.widget_eta_text, etaDisplay)

            // Layer Info
            val layerDisplay = when {
                totalLayerNum > 0 && stateUpper != "IDLE" -> "Layer $layerNum / $totalLayerNum"
                layerNum > 0 && stateUpper != "IDLE" -> "Layer $layerNum"
                else -> "Layer -- / --"
            }
            views.setTextViewText(R.id.widget_layer_text, layerDisplay)

            // Click on widget root opens MainActivity (Dashboard)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val launchPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, launchPendingIntent)

            // Click on "Live" button opens Live Stream / PiP directly
            val streamIntent = Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_STREAM
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val streamPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId + 20000,
                streamIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_stream_button, streamPendingIntent)

            // Click on refresh button triggers widget refresh
            val refreshIntent = Intent(context, PrinterWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_WIDGET
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId + 10000,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
