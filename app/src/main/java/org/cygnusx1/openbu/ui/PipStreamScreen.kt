package org.cygnusx1.openbu.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.network.PrinterStatus

@Composable
fun PipStreamScreen(
    frame: Bitmap?,
    internalRtspPlayer: ExoPlayer?,
    rtspPlayer: ExoPlayer?,
    externalVlcContent: (@Composable (Modifier) -> Unit)?,
    printerStatus: PrinterStatus? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            internalRtspPlayer != null -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = internalRtspPlayer
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { view -> view.player = internalRtspPlayer },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            frame != null -> {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_camera_preview),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            rtspPlayer != null -> {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = rtspPlayer
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        }
                    },
                    update = { view -> view.player = rtspPlayer },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            externalVlcContent != null -> {
                externalVlcContent(Modifier.fillMaxSize())
            }
            else -> {
                Text(
                    text = stringResource(R.string.cd_camera_stream),
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
        }

        // Print progress & status overlay
        if (printerStatus != null) {
            val state = printerStatus.gcodeState.uppercase()
            val percent = printerStatus.mcPercent
            val remainingMin = printerStatus.mcRemainingTime
            val isPrinting = state in listOf("RUNNING", "PAUSE", "FINISH")

            if (isPrinting || state == "PREPARE" || state == "FAILED") {
                // Top-start status badge
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (state) {
                            "RUNNING" -> {
                                Text(
                                    text = "$percent%",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                if (remainingMin > 0) {
                                    val timeStr = if (remainingMin >= 60) {
                                        "${remainingMin / 60}h ${remainingMin % 60}m"
                                    } else {
                                        "${remainingMin}m"
                                    }
                                    Text(
                                        text = " • $timeStr",
                                        color = Color(0xFFDDDDDD),
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                            "PAUSE" -> {
                                Text(
                                    text = "PAUSED • $percent%",
                                    color = Color(0xFFFFB300),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            "FINISH" -> {
                                Text(
                                    text = "Finished 100%",
                                    color = Color(0xFF00AE42),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            "PREPARE" -> {
                                Text(
                                    text = "Preparing...",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                )
                            }
                            "FAILED" -> {
                                Text(
                                    text = "Failed",
                                    color = Color(0xFFFF5252),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }

                // Slim bottom progress bar
                if (isPrinting) {
                    val progressFraction = (percent / 100f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color.Black.copy(alpha = 0.5f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progressFraction)
                                .background(if (state == "PAUSE") Color(0xFFFFB300) else Color(0xFF00AE42)),
                        )
                    }
                }
            }
        }
    }
}
