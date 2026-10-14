package org.cygnusx1.openbu.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.data.PrintJobRequest
import org.cygnusx1.openbu.data.PrintOptions
import org.cygnusx1.openbu.network.AmsUnit

data class AmsSlotItem(
    val globalIndex: Int,
    val label: String,
    val type: String,
    val color: Color,
    val isEmpty: Boolean,
)

@Composable
fun PrintJobDialog(
    request: PrintJobRequest,
    printerName: String,
    isConnected: Boolean,
    hasAms: Boolean,
    amsUnits: List<AmsUnit> = emptyList(),
    activeTrayId: String = "255",
    errorMessage: String? = null,
    transferProgress: Float?,
    transferName: String?,
    onCancelTransfer: () -> Unit,
    onConnectPrinter: (() -> Unit)? = null,
    onUploadOnly: (PrintOptions) -> Unit,
    onUploadAndPrint: (PrintOptions) -> Unit,
    onStartPrintFromSd: (PrintOptions) -> Unit,
    onDownloadFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    var bedLeveling by remember { mutableStateOf(true) }
    var flowCali by remember { mutableStateOf(false) }
    var vibrationCali by remember { mutableStateOf(false) }
    var timelapse by remember { mutableStateOf(false) }
    var useAms by remember { mutableStateOf(hasAms) }

    val amsSlots = remember(amsUnits) {
        val list = mutableListOf<AmsSlotItem>()
        amsUnits.forEachIndexed { unitIdx, unit ->
            unit.trays.forEachIndexed { trayIdx, tray ->
                val globalIdx = unitIdx * 4 + trayIdx
                val unitLetter = ('A' + unitIdx)
                val isEmpty = tray.trayType.isBlank()
                list.add(
                    AmsSlotItem(
                        globalIndex = globalIdx,
                        label = "$unitLetter${trayIdx + 1}",
                        type = if (isEmpty) "Empty" else tray.trayType,
                        color = parseHexColor(tray.trayColor),
                        isEmpty = isEmpty,
                    )
                )
            }
        }
        list
    }

    val initialSlot = remember(activeTrayId, amsSlots) {
        activeTrayId.toIntOrNull()?.takeIf { it in 0..15 }
            ?: amsSlots.firstOrNull { !it.isEmpty }?.globalIndex
            ?: 0
    }
    var selectedSlot by remember(initialSlot) { mutableIntStateOf(initialSlot) }

    val options = PrintOptions(
        bedLeveling = bedLeveling,
        flowCali = flowCali,
        vibrationCali = vibrationCali,
        timelapse = timelapse,
        useAms = useAms,
        amsMapping = if (useAms) listOf(selectedSlot) else listOf(-1),
    )

    val isTransferring = transferProgress != null || transferName != null
    val isExternalFile = request.uri != null

    // Load 3MF preview thumbnail asynchronously
    val context = LocalContext.current
    var thumbnail by remember(request.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(request.uri) {
        val uri = request.uri
        if (uri != null && request.fileName.endsWith(".3mf", ignoreCase = true)) {
            withContext(Dispatchers.IO) {
                thumbnail = load3mfThumbnail(context, uri)
            }
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isTransferring) onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isExternalFile) Icons.Filled.Upload else Icons.Filled.Print,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isExternalFile) stringResource(R.string.send_to_printer) else stringResource(R.string.start_print),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                // 3MF thumbnail preview
                thumbnail?.let { bmp ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(R.string.preview),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }

                // File info card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = request.fileName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (request.fileSize > 0) {
                                Text(
                                    text = formatFileSize(request.fileSize),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Error message banner
                if (errorMessage != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Printer target banner
                if (!isConnected) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.printer_not_connected_warning),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            if (onConnectPrinter != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = onConnectPrinter,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text(stringResource(R.string.connect))
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                } else {
                    Text(
                        text = "Target: $printerName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                // Transfer progress
                if (isTransferring) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = transferName ?: "Transferring...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    if (transferProgress != null) {
                        LinearProgressIndicator(
                            progress = { transferProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${(transferProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                // Print Options
                Text(
                    text = "Calibration & Settings",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(6.dp))

                PrintOptionSwitchRow(
                    title = stringResource(R.string.bed_leveling),
                    subtitle = stringResource(R.string.bed_leveling_desc),
                    checked = bedLeveling,
                    onCheckedChange = { bedLeveling = it },
                    enabled = isConnected && !isTransferring,
                )

                PrintOptionSwitchRow(
                    title = stringResource(R.string.timelapse_recording),
                    subtitle = stringResource(R.string.timelapse_recording_desc),
                    checked = timelapse,
                    onCheckedChange = { timelapse = it },
                    enabled = isConnected && !isTransferring,
                )

                PrintOptionSwitchRow(
                    title = stringResource(R.string.flow_calibration),
                    subtitle = stringResource(R.string.flow_calibration_desc),
                    checked = flowCali,
                    onCheckedChange = { flowCali = it },
                    enabled = isConnected && !isTransferring,
                )

                PrintOptionSwitchRow(
                    title = stringResource(R.string.vibration_calibration),
                    subtitle = stringResource(R.string.vibration_calibration_desc),
                    checked = vibrationCali,
                    onCheckedChange = { vibrationCali = it },
                    enabled = isConnected && !isTransferring,
                )

                if (hasAms) {
                    PrintOptionSwitchRow(
                        title = stringResource(R.string.use_ams_label),
                        subtitle = stringResource(R.string.use_ams_desc),
                        checked = useAms,
                        onCheckedChange = { useAms = it },
                        enabled = isConnected && !isTransferring,
                    )

                    // AMS Slot selector
                    if (useAms && amsSlots.isNotEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, top = 2.dp, bottom = 6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.ams_slot),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                amsSlots.forEach { slot ->
                                    val isSelected = selectedSlot == slot.globalIndex
                                    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                    val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                                    Surface(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable(enabled = isConnected && !isTransferring && !slot.isEmpty) {
                                                selectedSlot = slot.globalIndex
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = containerColor,
                                        border = BorderStroke(1.dp, borderColor),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .clip(CircleShape)
                                                    .background(if (slot.isEmpty) Color.LightGray else slot.color)
                                                    .border(0.5.dp, Color.Gray, CircleShape)
                                            )
                                            Column {
                                                Text(
                                                    text = slot.label,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                                )
                                                Text(
                                                    text = slot.type,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (isTransferring) {
                OutlinedButton(onClick = onCancelTransfer) {
                    Text(stringResource(R.string.cancel))
                }
            } else if (isExternalFile) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { onUploadOnly(options) },
                        enabled = isConnected,
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.upload_only))
                    }
                    Button(
                        onClick = { onUploadAndPrint(options) },
                        enabled = isConnected,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.upload_and_print))
                    }
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onDownloadFile,
                        enabled = !isTransferring,
                    ) {
                        Text(stringResource(R.string.download_to_device))
                    }
                    Button(
                        onClick = { onStartPrintFromSd(options) },
                        enabled = isConnected && !isTransferring,
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.start_print))
                    }
                }
            }
        },
        dismissButton = {
            if (!isTransferring) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun PrintOptionSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}

private fun parseHexColor(hex: String): Color {
    if (hex.length < 6) return Color.Gray
    return try {
        val r = hex.substring(0, 2).toInt(16)
        val g = hex.substring(2, 4).toInt(16)
        val b = hex.substring(4, 6).toInt(16)
        val a = if (hex.length >= 8) hex.substring(6, 8).toInt(16) else 255
        Color(android.graphics.Color.argb(a, r, g, b))
    } catch (_: Exception) {
        Color.Gray
    }
}

private fun load3mfThumbnail(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            java.util.zip.ZipInputStream(stream).use { zip ->
                var plateBytes: ByteArray? = null
                var topBytes: ByteArray? = null
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name.equals("Metadata/plate_1.png", ignoreCase = true) ||
                        name.matches(Regex("""(?i)Metadata/plate_\d+\.png"""))
                    ) {
                        plateBytes = zip.readBytes()
                        break
                    } else if (name.equals("Metadata/top_1.png", ignoreCase = true) ||
                        name.matches(Regex("""(?i)Metadata/top_\d+\.png"""))
                    ) {
                        topBytes = zip.readBytes()
                    }
                    entry = zip.nextEntry
                }
                val chosenBytes = plateBytes ?: topBytes
                chosenBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
        }
    } catch (_: Exception) {
        null
    }
}
