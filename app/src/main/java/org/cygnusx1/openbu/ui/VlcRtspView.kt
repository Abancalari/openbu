package org.cygnusx1.openbu.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.cygnusx1.openbu.R
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private const val TAG = "VlcRtsp"
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 5f

class VlcPlayerHolder(context: Context, private val url: String, tag: String) {
    val libVlc = LibVLC(context, arrayListOf(
        "--network-caching=300",
        "--rtsp-tcp",
        "--no-audio",
    ))
    val player = MediaPlayer(libVlc)
    val videoLayout = VLCVideoLayout(context)

    init {
        Log.d(TAG, "$tag: Creating VLC player for: $url")
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(false, true)
        player.media = media
        media.release()
        player.attachViews(videoLayout, null, false, false)
        player.play()
    }

    fun resume() {
        Log.d(TAG, "Resuming VLC player, reconnecting to: $url")
        player.stop()
        player.detachViews()
        val media = Media(libVlc, Uri.parse(url))
        media.setHWDecoderEnabled(false, true)
        player.media = media
        media.release()
        player.attachViews(videoLayout, null, false, false)
        player.play()
    }

    fun release() {
        Log.d(TAG, "Releasing VLC player")
        player.stop()
        player.detachViews()
        player.release()
        libVlc.release()
    }
}

/**
 * Creates a movable AndroidView that reparents the VLCVideoLayout without
 * destroying the surface. Call this once per holder above any conditional
 * branches (when/if), then invoke the returned lambda in exactly one branch
 * at a time with the desired modifier.
 */
@Composable
fun rememberVlcContent(
    holder: VlcPlayerHolder?,
): (@Composable (Modifier) -> Unit)? {
    if (holder == null) return null
    val content = remember(holder) {
        androidx.compose.runtime.movableContentOf { modifier: Modifier ->
            AndroidView(
                factory = {
                    (holder.videoLayout.parent as? ViewGroup)?.removeView(holder.videoLayout)
                    holder.videoLayout
                },
                modifier = modifier,
            )
        }
    }
    return content
}

@Composable
fun VlcRtspStreamCard(
    vlcContent: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
    onEnterPip: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
    ) {
        Box {
            vlcContent(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
            )

            if (onEnterPip != null) {
                IconButton(
                    onClick = onEnterPip,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(32.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Filled.PictureInPictureAlt,
                        contentDescription = stringResource(R.string.cd_pip),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun VlcRtspStreamScreen(
    vlcContent: @Composable (Modifier) -> Unit,
    onEnterPip: (() -> Unit)? = null,
) {
    val scale = remember { mutableFloatStateOf(1f) }
    val offsetX = remember { mutableFloatStateOf(0f) }
    val offsetY = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale.floatValue = 1f
                    offsetX.floatValue = 0f
                    offsetY.floatValue = 0f
                })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale.floatValue * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    scale.floatValue = newScale
                    if (newScale == 1f) {
                        offsetX.floatValue = 0f
                        offsetY.floatValue = 0f
                    } else {
                        val maxX = (size.width * (newScale - 1)) / 2
                        val maxY = (size.height * (newScale - 1)) / 2
                        offsetX.floatValue = (offsetX.floatValue + pan.x).coerceIn(-maxX, maxX)
                        offsetY.floatValue = (offsetY.floatValue + pan.y).coerceIn(-maxY, maxY)
                    }
                }
            },
    ) {
        vlcContent(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale.floatValue
                    scaleY = scale.floatValue
                    translationX = offsetX.floatValue
                    translationY = offsetY.floatValue
                }
        )

        if (onEnterPip != null) {
            IconButton(
                onClick = onEnterPip,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.cd_pip),
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
