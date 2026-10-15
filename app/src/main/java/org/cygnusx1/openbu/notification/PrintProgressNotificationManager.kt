package org.cygnusx1.openbu.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.cygnusx1.openbu.MainActivity
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.network.PrinterStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PrintProgressNotificationManager {
    private const val CHANNEL_ID = "openbu_print_progress"
    private const val NOTIFICATION_ID = 4201

    @Volatile
    private var lastGcodeState: String = ""

    @Volatile
    private var hadActivePrintSession: Boolean = false

    fun updateFromStatus(
        context: Context,
        status: PrinterStatus,
        printerName: String,
    ) {
        val appContext = context.applicationContext
        ensureChannel(appContext)
        if (!canPostNotifications(appContext)) return

        val stateUpper = status.gcodeState.uppercase()
        val isActivePrint = stateUpper in setOf("PREPARE", "RUNNING", "PAUSE")
        val isTerminalPrint = stateUpper in setOf("FINISH", "FAILED")

        if (isActivePrint) {
            hadActivePrintSession = true
        }

        // If the printer is IDLE (or finished before we ever observed an active print),
        // don't clutter the notification shade.
        if (!isActivePrint && !(isTerminalPrint && hadActivePrintSession)) {
            if (stateUpper == "IDLE") {
                hadActivePrintSession = false
                cancel(appContext)
            }
            lastGcodeState = stateUpper
            return
        }

        lastGcodeState = stateUpper
        if (isTerminalPrint) {
            hadActivePrintSession = false
        }

        val jobName = status.cleanJobName.ifBlank { "Print Job" }
        val stageLabel = status.stageLabel
        val percent = when (stateUpper) {
            "FINISH" -> 100
            else -> status.mcPercent.coerceIn(0, 100)
        }

        val title = when (stateUpper) {
            "PREPARE" -> if (percent > 0) "$jobName • $percent%" else "$jobName • $stageLabel"
            "PAUSE" -> "$jobName • Paused ($percent%)"
            "FINISH" -> "$jobName • Complete (100%)"
            "FAILED" -> "$jobName • Print Failed"
            else -> "$jobName • $percent%"
        }

        val detailParts = mutableListOf<String>()
        detailParts.add(stageLabel)

        if (isActivePrint && status.mcRemainingTime > 0) {
            detailParts.add(formatRemainingAndEta(status.mcRemainingTime))
        }

        if (status.totalLayerNum > 0) {
            detailParts.add("Layer ${status.layerNum}/${status.totalLayerNum}")
        } else if (status.layerNum > 0) {
            detailParts.add("Layer ${status.layerNum}")
        }

        val contentText = detailParts.joinToString(" • ")

        val dashboardIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val dashboardPendingIntent = PendingIntent.getActivity(
            appContext,
            100,
            dashboardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val streamIntent = Intent(appContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_STREAM
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val streamPendingIntent = PendingIntent.getActivity(
            appContext,
            101,
            streamIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pipIntent = Intent(appContext, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_PIP
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pipPendingIntent = PendingIntent.getActivity(
            appContext,
            102,
            pipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isIndeterminate = stateUpper == "PREPARE" && percent == 0

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_widget_printer)
            .setSubText(printerName.ifBlank { "Bambu Lab" })
            .setContentTitle(title)
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setContentIntent(dashboardPendingIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(isActivePrint)
            .setAutoCancel(!isActivePrint)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(
                when (stateUpper) {
                    "PAUSE", "PREPARE" -> 0xFFFFB300.toInt()
                    "FAILED" -> 0xFFE53935.toInt()
                    else -> 0xFF00AE42.toInt()
                }
            )
            .addAction(
                R.drawable.ic_widget_pip,
                "Live Stream",
                streamPendingIntent
            )
            .addAction(
                R.drawable.ic_widget_pip,
                "Pop-out (PiP)",
                pipPendingIntent
            )

        if (isActivePrint) {
            builder.setProgress(100, percent, isIndeterminate)
        } else {
            builder.setProgress(0, 0, false)
        }

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {}
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    private fun canPostNotifications(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Print Progress",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Live 3D print completion progress, ETA, and layer updates"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun formatRemainingAndEta(remainingMinutes: Int): String {
        if (remainingMinutes <= 0) return ""
        val hours = remainingMinutes / 60
        val mins = remainingMinutes % 60
        val durationStr = if (hours > 0) "${hours}h ${mins}m left" else "${mins}m left"
        val etaMillis = System.currentTimeMillis() + remainingMinutes * 60_000L
        val etaClock = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(etaMillis))
        return "$durationStr (ETA $etaClock)"
    }
}
