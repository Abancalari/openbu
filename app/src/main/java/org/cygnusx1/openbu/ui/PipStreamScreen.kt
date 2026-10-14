package org.cygnusx1.openbu.ui

import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.cygnusx1.openbu.R

@Composable
fun PipStreamScreen(
    frame: Bitmap?,
    internalRtspPlayer: ExoPlayer?,
    rtspPlayer: ExoPlayer?,
    externalVlcContent: (@Composable (Modifier) -> Unit)?,
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
    }
}
