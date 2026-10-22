package org.cygnusx1.openbu

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.launch
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.cygnusx1.openbu.data.FilamentRepository
import org.cygnusx1.openbu.data.ModelPackageHelper
import org.cygnusx1.openbu.data.PrintJobRequest
import org.cygnusx1.openbu.data.PrintOptions
import org.cygnusx1.openbu.network.Socks5SocketFactory
import org.cygnusx1.openbu.network.Socks5TlsSslSocketFactory
import org.cygnusx1.openbu.ui.ConnectionScreen
import org.cygnusx1.openbu.ui.DashboardScreen
import org.cygnusx1.openbu.ui.FileManagerScreen
import org.cygnusx1.openbu.ui.PrintJobDialog
import org.cygnusx1.openbu.ui.TimelapseScreen
import org.cygnusx1.openbu.ui.PrinterSettingsScreen
import org.cygnusx1.openbu.ui.RtspStreamScreen
import org.cygnusx1.openbu.ui.SettingsScreen
import org.cygnusx1.openbu.ui.GeneralSettingsScreen
import org.cygnusx1.openbu.ui.RelaySettingsScreen
import org.cygnusx1.openbu.ui.DebuggingSettingsScreen
import org.cygnusx1.openbu.ui.SkipObjectsScreen
import android.view.WindowManager
import org.cygnusx1.openbu.ui.PipStreamScreen
import org.cygnusx1.openbu.ui.StreamScreen
import org.cygnusx1.openbu.ui.VideoPlayerScreen
import org.cygnusx1.openbu.ui.VlcPlayerHolder
import org.cygnusx1.openbu.ui.VlcRtspStreamScreen
import org.cygnusx1.openbu.ui.rememberVlcContent
import org.cygnusx1.openbu.ui.theme.OpenbuTheme
import org.cygnusx1.openbu.viewmodel.BambuStreamViewModel
import org.cygnusx1.openbu.viewmodel.ConnectionState

class MainActivity : ComponentActivity() {
    companion object {
        const val ACTION_OPEN_STREAM = "org.cygnusx1.openbu.ACTION_OPEN_STREAM"
        const val ACTION_OPEN_PIP = "org.cygnusx1.openbu.ACTION_OPEN_PIP"
    }

    private var viewModelRef: BambuStreamViewModel? = null
    private var volumeDownPressTime = 0L

    var isInPipMode by mutableStateOf(false)
        private set
    private var hasActiveStream: Boolean = false
    private var pendingOpenStream by mutableStateOf(false)
    private var pendingOpenPip by mutableStateOf(false)

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            try {
                enterPictureInPictureMode(params)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to enter PiP mode", e)
            }
        }
    }

    private fun updatePipParams(hasActiveStream: Boolean) {
        this.hasActiveStream = hasActiveStream
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .setAutoEnterEnabled(hasActiveStream)
                    .build()
                setPictureInPictureParams(params)
            } catch (e: Exception) {
                Log.w("MainActivity", "Failed to update PiP params", e)
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (hasActiveStream) {
            enterPip()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) {
                volumeDownPressTime = System.currentTimeMillis()
            }
            val held = System.currentTimeMillis() - volumeDownPressTime
            val vm = viewModelRef
            if (held >= 1500 && vm != null &&
                vm.connectionState.value != ConnectionState.Connected
            ) {
                vm.enterDemoMode()
                return true
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            volumeDownPressTime = 0L
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleStreamActionIntent(intent)
        handleIncomingFileIntent(intent, viewModelRef)
    }

    private fun handleStreamActionIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_OPEN_STREAM) {
            intent.action = null
            pendingOpenStream = true
        } else if (action == ACTION_OPEN_PIP) {
            intent.action = null
            pendingOpenStream = true
            pendingOpenPip = true
        }
    }

    private fun handleIncomingFileIntent(intent: Intent?, vm: BambuStreamViewModel?) {
        if (intent == null || vm == null) return
        val action = intent.action
        val targetUri = (when (action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                }
            }
            else -> null
        }) ?: return

        var fileName = targetUri.lastPathSegment?.substringAfterLast("/") ?: "print_job"
        var fileSize = -1L
        try {
            contentResolver.query(targetUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) cursor.getString(nameIdx)?.let { fileName = it }
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Failed to query incoming file Uri", e)
        }

        var resolvedFileName = ModelPackageHelper.sanitizePrintFileName(fileName) {
            try { contentResolver.openInputStream(targetUri) } catch (_: Exception) { null }
        }

        val lower = resolvedFileName.lowercase()
        val is3mf = lower.endsWith(".3mf")
        val isGcode = lower.endsWith(".gcode")
        if (!is3mf && !isGcode) {
            val mime = intent.type ?: try { contentResolver.getType(targetUri) } catch (_: Exception) { null }
            if (mime == "model/3mf" || mime == "application/vnd.ms-package.3dmanufacturing-3dmodel+xml") {
                resolvedFileName = "$resolvedFileName.3mf"
            } else if (mime == "text/x-gcode" || mime == "text/gcode") {
                resolvedFileName = "$resolvedFileName.gcode"
            }
        }

        val finalLower = resolvedFileName.lowercase()
        if (finalLower.endsWith(".3mf") || finalLower.endsWith(".gcode")) {
            intent.action = null // Clear action so config changes don't re-trigger
            vm.requestPrintJob(
                PrintJobRequest(
                    uri = targetUri,
                    fileName = resolvedFileName,
                    fileSize = fileSize,
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
        handleStreamActionIntent(intent)

        setContent {
            val viewModel: BambuStreamViewModel = viewModel()
            viewModelRef = viewModel

            LaunchedEffect(Unit) {
                handleIncomingFileIntent(intent, viewModel)
            }

            LifecycleResumeEffect(Unit) {
                Log.d("AutoReconnect", "Activity resumed, calling retryIfNeeded")
                viewModel.retryIfNeeded()
                onPauseOrDispose {}
            }
            var vlcResumeKey by remember { mutableStateOf(0) }
            val forceDarkMode by viewModel.forceDarkMode.collectAsState()
            val customBgColor by viewModel.customBgColor.collectAsState()
            val connectionState by viewModel.connectionState.collectAsState()
            val isConnected = connectionState == ConnectionState.Connected
            val widgetRefreshInterval by viewModel.widgetRefreshInterval.collectAsState()
            OpenbuTheme(
                overrideDeviceTheme = forceDarkMode,
                customBackgroundColor = if (isConnected) customBgColor else null,
            ) {
                val frame by viewModel.frame.collectAsState()
                val fps by viewModel.fps.collectAsState()
                val errorMessage by viewModel.errorMessage.collectAsState()
                val isLightOn by viewModel.isLightOn.collectAsState()
                val isMqttConnected by viewModel.isMqttConnected.collectAsState()
                val printerStatus by viewModel.printerStatus.collectAsState()
                val showMainStream by viewModel.showMainStream.collectAsState()
                val internalRtspUrl by viewModel.internalRtspUrl.collectAsState()
                val rtspEnabled by viewModel.rtspEnabled.collectAsState()
                val rtspUrl by viewModel.rtspUrl.collectAsState()
                val useVlcForRtsp by viewModel.useVlcForRtsp.collectAsState()
                val debugLogging by viewModel.debugLogging.collectAsState()
                val redactLogs by viewModel.redactLogs.collectAsState()
                val discoveredPrinters by viewModel.discoveredPrinters.collectAsState()
                val savedPrinters by viewModel.savedPrinters.collectAsState()
                val mockPrinters by viewModel.mockPrinters.collectAsState()
                val connectedSerialNumber by viewModel.connectedSerialNumber.collectAsState()
                val isReconnecting by viewModel.isReconnecting.collectAsState()
                val mjpegCameraFailed by viewModel.mjpegCameraFailed.collectAsState()
                val noRouteToHost by viewModel.noRouteToHost.collectAsState()
                val hasLastConnectedPrinter by viewModel.hasLastConnectedPrinter.collectAsState()
                val rtspReconnectKey by viewModel.rtspReconnectKey.collectAsState()
                val currentProxyConfig by viewModel.currentProxyConfig.collectAsState()

                var showFullscreen by rememberSaveable { mutableStateOf(false) }
                var showRtspFullscreen by rememberSaveable { mutableStateOf(false) }
                var showInternalRtspFullscreen by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                var showGeneralSettings by rememberSaveable { mutableStateOf(false) }
                var showRelaySettings by rememberSaveable { mutableStateOf(false) }
                var showDebuggingSettings by rememberSaveable { mutableStateOf(false) }
                var showPrinterSettings by rememberSaveable { mutableStateOf(false) }
                var showFileManager by rememberSaveable { mutableStateOf(false) }
                var showTimelapse by rememberSaveable { mutableStateOf(false) }
                var showSkipObjects by rememberSaveable { mutableStateOf(false) }
                val effectiveRtspUrl = if (rtspEnabled && rtspUrl.isNotBlank()) rtspUrl else ""

                // Helper to build an ExoPlayer for an RTSP/RTSPS URL
                @OptIn(UnstableApi::class)
                fun createRtspPlayer(url: String, tag: String): ExoPlayer {
                    val isRtsps = url.startsWith("rtsps://")
                    Log.d(tag, "Creating player: isRtsps=$isRtsps, url=$url")
                    val renderersFactory = DefaultRenderersFactory(this@MainActivity)
                        .setEnableDecoderFallback(true)
                    return ExoPlayer.Builder(this@MainActivity, renderersFactory).build().apply {
                        addListener(object : Player.Listener {
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                val stateName = when (playbackState) {
                                    Player.STATE_IDLE -> "IDLE"
                                    Player.STATE_BUFFERING -> "BUFFERING"
                                    Player.STATE_READY -> "READY"
                                    Player.STATE_ENDED -> "ENDED"
                                    else -> "UNKNOWN($playbackState)"
                                }
                                Log.d(tag, "Playback state: $stateName")
                            }
                            override fun onPlayerError(error: PlaybackException) {
                                Log.e(tag, "Player error: ${error.errorCodeName} (${error.errorCode})", error)
                            }
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                Log.d(tag, "isPlaying=$isPlaying")
                            }
                            override fun onRenderedFirstFrame() {
                                Log.d(tag, "First frame rendered")
                            }
                        })
                        val factory = RtspMediaSource.Factory().apply {
                            if (isRtsps) {
                                val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                                })
                                val sslContext = SSLContext.getInstance("TLS")
                                sslContext.init(null, trustAll, java.security.SecureRandom())
                                val socketFactory = if (currentProxyConfig != null) {
                                    Log.d(tag, "Using SOCKS5+TLS SSLSocketFactory + forceRtpTcp")
                                    Socks5TlsSslSocketFactory(currentProxyConfig!!, sslContext)
                                } else {
                                    Log.d(tag, "Using trust-all SSLSocketFactory + forceRtpTcp")
                                    sslContext.socketFactory
                                }
                                setSocketFactory(socketFactory)
                                setForceUseRtpTcp(true)
                            } else if (currentProxyConfig != null) {
                                Log.d(tag, "Using SOCKS5 SocketFactory for plain RTSP + forceRtpTcp")
                                setSocketFactory(Socks5SocketFactory(currentProxyConfig!!))
                                setForceUseRtpTcp(true)
                            }
                        }
                        val mediaSource = factory.createMediaSource(MediaItem.fromUri(url))
                        setMediaSource(mediaSource)
                        prepare()
                        playWhenReady = true
                    }
                }

                // Internal RTSP player (non-P1 printer built-in camera) — always ExoPlayer
                @OptIn(UnstableApi::class)
                val internalRtspPlayer = remember(internalRtspUrl, rtspReconnectKey) {
                    if (internalRtspUrl.isNotBlank()) {
                        Log.d("RTSP-Internal", "Using ExoPlayer decoder for: $internalRtspUrl")
                        createRtspPlayer(internalRtspUrl, "RTSP-Internal")
                    } else null
                }
                DisposableEffect(internalRtspUrl, rtspReconnectKey) {
                    onDispose { internalRtspPlayer?.release() }
                }

                // External RTSP player (user-configured secondary camera)
                // VLC does not support SOCKS5 proxy tunneling, so disable when both VLC and relay are active
                val shouldDisableExternalRtsp = useVlcForRtsp && currentProxyConfig != null
                @OptIn(UnstableApi::class)
                val rtspPlayer = remember(effectiveRtspUrl, rtspReconnectKey, useVlcForRtsp, shouldDisableExternalRtsp) {
                    if (!shouldDisableExternalRtsp && !useVlcForRtsp && effectiveRtspUrl.isNotBlank()) {
                        Log.d("RTSP-External", "Using ExoPlayer decoder for: $effectiveRtspUrl")
                        createRtspPlayer(effectiveRtspUrl, "RTSP-External")
                    } else null
                }
                DisposableEffect(effectiveRtspUrl, rtspReconnectKey, useVlcForRtsp, shouldDisableExternalRtsp) {
                    onDispose { rtspPlayer?.release() }
                }
                val externalVlcPlayer = remember(effectiveRtspUrl, rtspReconnectKey, useVlcForRtsp, shouldDisableExternalRtsp) {
                    if (!shouldDisableExternalRtsp && useVlcForRtsp && effectiveRtspUrl.isNotBlank()) {
                        Log.d("RTSP-External", "Using VLC decoder for: $effectiveRtspUrl")
                        VlcPlayerHolder(this@MainActivity, effectiveRtspUrl, "RTSP-External")
                    } else null
                }
                DisposableEffect(effectiveRtspUrl, rtspReconnectKey, useVlcForRtsp, shouldDisableExternalRtsp) {
                    onDispose { externalVlcPlayer?.release() }
                }

                // Card VLC content — stays alive permanently in the dashboard card
                val externalVlcContent = rememberVlcContent(externalVlcPlayer)

                // Fullscreen VLC player — separate instance created only when fullscreen is shown.
                // The card player above is never touched during navigation, so it keeps playing
                // on return without any surface swap or reconnection.
                // VLC does not support SOCKS5 proxy tunneling, so disable when both VLC and relay are active
                val fullscreenVlcPlayer = remember(effectiveRtspUrl, rtspReconnectKey, showRtspFullscreen, useVlcForRtsp, shouldDisableExternalRtsp) {
                    if (!shouldDisableExternalRtsp && useVlcForRtsp && showRtspFullscreen && effectiveRtspUrl.isNotBlank()) {
                        Log.d("RTSP-Fullscreen", "Using VLC decoder for fullscreen: $effectiveRtspUrl")
                        VlcPlayerHolder(this@MainActivity, effectiveRtspUrl, "RTSP-Fullscreen")
                    } else null
                }
                DisposableEffect(effectiveRtspUrl, rtspReconnectKey, showRtspFullscreen, useVlcForRtsp, shouldDisableExternalRtsp) {
                    onDispose { fullscreenVlcPlayer?.release() }
                }
                val fullscreenVlcContent = rememberVlcContent(fullscreenVlcPlayer)

                // Resume VLC players when returning from background
                LifecycleResumeEffect(externalVlcPlayer, fullscreenVlcPlayer) {
                    if (vlcResumeKey > 0) {
                        externalVlcPlayer?.resume()
                        fullscreenVlcPlayer?.resume()
                    }
                    vlcResumeKey++
                    onPauseOrDispose {}
                }

                val fileList by viewModel.fileList.collectAsState()
                val currentFtpPath by viewModel.currentFtpPath.collectAsState()
                val ftpLoading by viewModel.ftpLoading.collectAsState()
                val ftpError by viewModel.ftpError.collectAsState()
                val ftpTransferProgress by viewModel.ftpTransferProgress.collectAsState()
                val ftpTransferName by viewModel.ftpTransferName.collectAsState()

                val timelapseListState = androidx.compose.foundation.lazy.rememberLazyListState()
                val timelapseScope = androidx.compose.runtime.rememberCoroutineScope()
                var timelapseNewestFirst by rememberSaveable { mutableStateOf(true) }
                val timelapseFileList by viewModel.timelapseFileList.collectAsState()
                val timelapseThumbnails by viewModel.timelapseThumbnails.collectAsState()
                val timelapseLoading by viewModel.timelapseLoading.collectAsState()
                val timelapseError by viewModel.timelapseError.collectAsState()
                val timelapseDownloadProgress by viewModel.timelapseDownloadProgress.collectAsState()
                val timelapseDownloadName by viewModel.timelapseDownloadName.collectAsState()
                val timelapsePlaybackFile by viewModel.timelapsePlaybackFile.collectAsState()

                val skipObjectsList by viewModel.skipObjectsList.collectAsState()
                val skipObjectsLoading by viewModel.skipObjectsLoading.collectAsState()
                val skipObjectsLoadingMessage by viewModel.skipObjectsLoadingMessage.collectAsState()
                val skipObjectsError by viewModel.skipObjectsError.collectAsState()
                val skipObjectsPlateImage by viewModel.skipObjectsPlateImage.collectAsState()

                LaunchedEffect(frame, internalRtspPlayer, rtspPlayer, externalVlcContent, isConnected, pendingOpenStream, pendingOpenPip, internalRtspUrl) {
                    val hasStream = isConnected && (frame != null || internalRtspPlayer != null || rtspPlayer != null || externalVlcContent != null)
                    updatePipParams(hasStream)

                    if (pendingOpenStream && isConnected) {
                        showSettings = false
                        showGeneralSettings = false
                        showRelaySettings = false
                        showDebuggingSettings = false
                        showPrinterSettings = false
                        showFileManager = false
                        showTimelapse = false
                        showSkipObjects = false
                        if (internalRtspUrl.isNotBlank()) {
                            showInternalRtspFullscreen = true
                            showFullscreen = false
                        } else {
                            showFullscreen = true
                            showInternalRtspFullscreen = false
                        }
                        pendingOpenStream = false
                    }

                    if (pendingOpenPip && hasStream) {
                        pendingOpenPip = false
                        enterPip()
                    }
                }

                if (isInPipMode) {
                    PipStreamScreen(
                        frame = frame,
                        internalRtspPlayer = internalRtspPlayer,
                        rtspPlayer = rtspPlayer,
                        externalVlcContent = fullscreenVlcContent ?: externalVlcContent,
                        printerStatus = printerStatus,
                    )
                } else {
                    when {
                    // Skip Objects screen
                    showSkipObjects -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showSkipObjects = false }
                        SkipObjectsScreen(
                            objects = skipObjectsList,
                            skippedObjectIds = printerStatus.skippedObjects,
                            isLoading = skipObjectsLoading,
                            loadingMessage = skipObjectsLoadingMessage,
                            error = skipObjectsError,
                            gcodeState = printerStatus.gcodeState,
                            layerNum = printerStatus.layerNum,
                            plateImage = skipObjectsPlateImage,
                            onLoadObjects = { viewModel.loadSkipObjects() },
                            onSkipSelected = { viewModel.skipSelectedObjects(it) },
                            onClearError = { viewModel.clearSkipObjectsError() },
                            onBack = { showSkipObjects = false },
                        )
                    }
                    // Video player — shown when timelapse file is downloaded
                    showTimelapse && timelapsePlaybackFile != null -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        BackHandler {
                            viewModel.clearPlaybackFile()
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        VideoPlayerScreen(
                            videoFile = timelapsePlaybackFile!!,
                            onFinished = {
                                viewModel.clearPlaybackFile()
                            },
                        )
                    }
                    // Timelapse recordings browser
                    showTimelapse -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler {
                            viewModel.closeTimelapse()
                            showTimelapse = false
                        }
                        TimelapseScreen(
                            fileList = timelapseFileList,
                            thumbnails = timelapseThumbnails,
                            isLoading = timelapseLoading,
                            error = timelapseError,
                            downloadProgress = timelapseDownloadProgress,
                            downloadName = timelapseDownloadName,
                            listState = timelapseListState,
                            newestFirst = timelapseNewestFirst,
                            onToggleSortOrder = {
                                timelapseNewestFirst = !timelapseNewestFirst
                                timelapseScope.launch { timelapseListState.scrollToItem(0) }
                            },
                            onPlayVideo = { viewModel.playVideo(it) },
                            onCancelDownload = { viewModel.cancelTimelapseDownload() },
                            onClearError = { viewModel.clearTimelapseError() },
                            onBack = {
                                viewModel.closeTimelapse()
                                showTimelapse = false
                            },
                        )
                    }
                    // File manager stays visible even if camera/MQTT drops
                    showFileManager -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler {
                            if (currentFtpPath != "/") {
                                viewModel.navigateUp()
                            } else {
                                viewModel.closeFileManager()
                                showFileManager = false
                            }
                        }
                        FileManagerScreen(
                            fileList = fileList,
                            currentPath = currentFtpPath,
                            isLoading = ftpLoading,
                            error = ftpError,
                            transferProgress = ftpTransferProgress,
                            transferName = ftpTransferName,
                            onCancelTransfer = { viewModel.cancelTransfer() },
                            onNavigateTo = { viewModel.navigateTo(it) },
                            onNavigateUp = { viewModel.navigateUp() },
                            onDownloadFile = { viewModel.downloadFile(it) },
                            onDeleteFile = { viewModel.deleteFtpFile(it) },
                            onUploadFile = { viewModel.uploadFile(it) },
                            onSelectFileForPrint = { entry ->
                                val remotePath = if (currentFtpPath.endsWith("/")) "$currentFtpPath${entry.name}" else "$currentFtpPath/${entry.name}"
                                viewModel.requestPrintJob(
                                    PrintJobRequest(
                                        remotePath = remotePath,
                                        fileName = entry.name,
                                        fileSize = entry.size,
                                    )
                                )
                            },
                            onClearError = { viewModel.clearFtpError() },
                            onBack = {
                                if (currentFtpPath != "/") {
                                    viewModel.navigateUp()
                                } else {
                                    viewModel.closeFileManager()
                                    showFileManager = false
                                }
                            },
                        )
                    }
                    showSettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showSettings = false }
                        SettingsScreen(
                            onOpenGeneralSettings = {
                                showSettings = false
                                showGeneralSettings = true
                            },
                            onOpenRelaySettings = {
                                showSettings = false
                                showRelaySettings = true
                            },
                            onOpenDebuggingSettings = {
                                showSettings = false
                                showDebuggingSettings = true
                            },
                            onBack = { showSettings = false },
                        )
                    }
                    showGeneralSettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler {
                            showGeneralSettings = false
                            showSettings = true
                        }
                        GeneralSettingsScreen(
                            showMainStream = showMainStream,
                            onShowMainStreamChanged = { viewModel.setShowMainStream(it) },
                            forceDarkMode = forceDarkMode,
                            onForceDarkModeChanged = { viewModel.setForceDarkMode(it) },
                            useVlcForRtsp = useVlcForRtsp,
                            onUseVlcForRtspChanged = { viewModel.setUseVlcForRtsp(it) },
                            widgetRefreshInterval = widgetRefreshInterval,
                            onWidgetRefreshIntervalChanged = { viewModel.setWidgetRefreshInterval(it) },
                            onBack = {
                                showGeneralSettings = false
                                showSettings = true
                            },
                        )
                    }
                    showRelaySettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showRelaySettings = false; showSettings = true }
                        val relayEnabled by viewModel.relayEnabled.collectAsState()
                        val relayHost by viewModel.relayHost.collectAsState()
                        val relayPort by viewModel.relayPort.collectAsState()
                        val relayUsername by viewModel.relayUsername.collectAsState()
                        val relayPassword by viewModel.relayPassword.collectAsState()
                        RelaySettingsScreen(
                            relayEnabled = relayEnabled,
                            onRelayEnabledChanged = { viewModel.setRelayEnabled(it) },
                            relayHost = relayHost,
                            onRelayHostChanged = { viewModel.setRelayHost(it) },
                            relayPort = relayPort,
                            onRelayPortChanged = { viewModel.setRelayPort(it) },
                            relayUsername = relayUsername,
                            onRelayUsernameChanged = { viewModel.setRelayUsername(it) },
                            relayPassword = relayPassword,
                            onRelayPasswordChanged = { viewModel.setRelayPassword(it) },
                            onBack = {
                                showRelaySettings = false
                                showSettings = true
                            },
                        )
                    }
                    showDebuggingSettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showDebuggingSettings = false; showSettings = true }
                        val mqttDataMessages by viewModel.mqttDataMessages.collectAsState()
                        val logcatText by viewModel.logcatText.collectAsState()
                        val accessCode by viewModel.connectedAccessCode.collectAsState()
                        DebuggingSettingsScreen(
                            debugLogging = debugLogging,
                            onDebugLoggingChanged = { viewModel.setDebugLogging(it) },
                            redactLogs = redactLogs,
                            onRedactLogsChanged = { viewModel.setRedactLogs(it) },
                            mqttDataMessages = mqttDataMessages,
                            logcatText = logcatText,
                            accessCode = accessCode,
                            serialNumber = connectedSerialNumber,
                            onCaptureLogcat = { filter -> viewModel.captureLogcat(filter) },
                            onBack = {
                                showDebuggingSettings = false
                                showSettings = true
                            },
                        )
                    }
                    connectionState == ConnectionState.Connected && showPrinterSettings -> {
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler { showPrinterSettings = false }
                        val customPrinterName by viewModel.customPrinterName.collectAsState()
                        val printerRelayEnabled by viewModel.relayEnabled.collectAsState()
                        val printerRelayOverride by viewModel.relayOverride.collectAsState()
                        PrinterSettingsScreen(
                            customPrinterName = customPrinterName,
                            onCustomPrinterNameChanged = { viewModel.setCustomPrinterName(it) },
                            rtspEnabled = rtspEnabled,
                            onRtspEnabledChanged = { viewModel.setRtspEnabled(it) },
                            rtspUrl = rtspUrl,
                            onRtspUrlChanged = { viewModel.setRtspUrl(it) },
                            customBgColor = customBgColor,
                            onBgColorChanged = { viewModel.setCustomBgColor(it) },
                            relayEnabled = printerRelayEnabled,
                            relayOverride = printerRelayOverride,
                            onRelayOverrideChanged = { viewModel.setRelayOverride(it) },
                            isSaved = savedPrinters.any { it.serialNumber == connectedSerialNumber },
                            onDeletePrinter = { viewModel.deleteSavedPrinter(connectedSerialNumber) },
                            onBack = { showPrinterSettings = false },
                        )
                    }
                    connectionState == ConnectionState.Connected && showFullscreen -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        BackHandler {
                            showFullscreen = false
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        StreamScreen(
                            frame = frame,
                            fps = fps,
                            isRelayed = currentProxyConfig != null,
                            onEnterPip = { enterPip() },
                        )
                    }
                    connectionState == ConnectionState.Connected && showInternalRtspFullscreen && internalRtspUrl.isNotBlank() -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        BackHandler {
                            showInternalRtspFullscreen = false
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        RtspStreamScreen(
                            player = internalRtspPlayer,
                            isRelayed = currentProxyConfig != null,
                            onEnterPip = { enterPip() },
                        )
                    }
                    connectionState == ConnectionState.Connected && showRtspFullscreen && effectiveRtspUrl.isNotBlank() -> {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        BackHandler {
                            showRtspFullscreen = false
                            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        }
                        RtspStreamScreen(
                            player = rtspPlayer,
                            isRelayed = currentProxyConfig != null,
                            onEnterPip = { enterPip() },
                        )
                    }
                    connectionState == ConnectionState.Connected || hasLastConnectedPrinter -> {
                        if (connectionState == ConnectionState.Connected) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        // Orientation driven by fullscreen overlay state
                        requestedOrientation = if (connectionState == ConnectionState.Connected && showRtspFullscreen && effectiveRtspUrl.isNotBlank())
                            ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        else
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        BackHandler {
                            showFullscreen = false
                            showRtspFullscreen = false
                            showInternalRtspFullscreen = false
                            showSettings = false
                            showGeneralSettings = false
                            showRelaySettings = false
                            showDebuggingSettings = false
                            showPrinterSettings = false
                            showFileManager = false
                            showTimelapse = false
                            showSkipObjects = false
                            viewModel.closeFileManager()
                            viewModel.closeTimelapse()
                            viewModel.disconnect()
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        val filamentCatalog = remember { FilamentRepository.getFilaments(this@MainActivity) }
                        val dashboardCustomName by viewModel.customPrinterName.collectAsState()
                        val isDemoMode by viewModel.demoMode.collectAsState()
                        val printerName = if (isDemoMode) "demo-printer" else dashboardCustomName.ifBlank {
                            discoveredPrinters.firstOrNull { it.serialNumber == connectedSerialNumber }?.deviceName
                                ?: savedPrinters.firstOrNull { it.serialNumber == connectedSerialNumber }?.deviceName
                                ?: ""
                        }
                        val dashRelayEnabled by viewModel.relayEnabled.collectAsState()
                        // Wrap in a Box so the fullscreen VLC overlay can sit above the dashboard
                        // without unmounting it. DashboardScreen (and the card VLC view) stays
                        // composed the whole time, keeping the card player's SurfaceView alive.
                        Box(modifier = Modifier.fillMaxSize()) {
                        DashboardScreen(
                            frame = frame,
                            fps = fps,
                            isLightOn = isLightOn,
                            isMqttConnected = isMqttConnected,
                            printerStatus = printerStatus,
                            printerName = printerName,
                            serialNumber = connectedSerialNumber,
                            availablePrinters = savedPrinters + mockPrinters,
                            onSelectPrinter = { printer -> viewModel.switchToPrinter(printer) },
                            showMainStream = showMainStream,
                            internalRtspPlayer = internalRtspPlayer,
                            rtspPlayer = rtspPlayer,
                            externalVlcContent = externalVlcContent,
                            isReconnecting = isReconnecting,
                            mjpegCameraFailed = mjpegCameraFailed,
                            noRouteToHost = noRouteToHost,
                            onDisconnect = {
                                viewModel.closeFileManager()
                                viewModel.closeTimelapse()
                                viewModel.disconnect()
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            },
                            onReconnect = { viewModel.reconnect() },
                            onToggleLight = { viewModel.toggleLight(it) },
                            onOpenFullscreen = { showFullscreen = true },
                            onOpenInternalRtspFullscreen = { showInternalRtspFullscreen = true },
                            onOpenRtspFullscreen = { showRtspFullscreen = true },
                            onOpenSettings = { showSettings = true },
                            onOpenPrinterSettings = { showPrinterSettings = true },
                            onOpenFileManager = {
                                viewModel.openFileManager()
                                showFileManager = true
                            },
                            onOpenTimelapse = {
                                viewModel.openTimelapse()
                                showTimelapse = true
                            },
                            onOpenSkipObjects = {
                                showSkipObjects = true
                            },
                            onSetSpeedLevel = { viewModel.setSpeedLevel(it) },
                            onSetNozzleTemperature = { viewModel.setNozzleTemperature(it) },
                            onSetBedTemperature = { viewModel.setBedTemperature(it) },
                            onSetFanSpeed = { fan, pwm -> viewModel.setFanSpeed(fan, pwm) },
                            onPrinterActionCommand = { viewModel.sendPrinterActionCommand(it) },
                            filaments = filamentCatalog,
                            onSetFilament = { amsId, trayId, profile, colorHex ->
                                viewModel.setFilament(amsId, trayId, profile, colorHex)
                            },
                            relayEnabled = dashRelayEnabled,
                            onRelayEnabledChanged = { viewModel.setRelayEnabled(it) },
                            isRelayed = currentProxyConfig != null,
                            onEnterPip = { enterPip() },
                        )
                        // VLC fullscreen overlay — rendered on top of the dashboard so the
                        // card player's SurfaceView is never detached from the window.
                        if (connectionState == ConnectionState.Connected && showRtspFullscreen && effectiveRtspUrl.isNotBlank()) {
                            BackHandler {
                                showRtspFullscreen = false
                            }
                            if (fullscreenVlcContent != null) VlcRtspStreamScreen(vlcContent = fullscreenVlcContent, onEnterPip = { enterPip() })
                            else RtspStreamScreen(player = rtspPlayer, onEnterPip = { enterPip() })
                        }
                        } // end Box
                    }
                    else -> {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        showFullscreen = false
                        showRtspFullscreen = false
                        showInternalRtspFullscreen = false
                        showSettings = false
                        showGeneralSettings = false
                        showRelaySettings = false
                        showDebuggingSettings = false
                        showPrinterSettings = false
                        showFileManager = false
                        showTimelapse = false
                        showSkipObjects = false
                        ConnectionScreen(
                            connectionState = connectionState,
                            errorMessage = errorMessage,
                            discoveredPrinters = discoveredPrinters,
                            savedPrinters = savedPrinters,
                            onStartDiscovery = { viewModel.startDiscovery() },
                            onStopDiscovery = { viewModel.stopDiscovery() },
                            onGetSavedAccessCode = { serial -> viewModel.getSavedAccessCode(serial) },
                            onConnect = { ip, accessCode, serialNumber, manualMode ->
                                viewModel.connect(ip, accessCode, serialNumber, manualMode)
                            },
                            onRetry = { viewModel.reconnect() },
                            onDeletePrinter = { serial ->
                                viewModel.deleteSavedPrinter(serial)
                            },
                        )
                    }
                }

                val pendingPrintJob by viewModel.pendingPrintJob.collectAsState()
                val currentPrinterName = (discoveredPrinters.firstOrNull { it.serialNumber == connectedSerialNumber }?.deviceName
                    ?: savedPrinters.firstOrNull { it.serialNumber == connectedSerialNumber }?.deviceName)
                    ?.ifBlank { "Bambu Lab Printer" } ?: "Bambu Lab Printer"

                pendingPrintJob?.let { request ->
                    PrintJobDialog(
                        request = request,
                        printerName = currentPrinterName,
                        isConnected = isConnected,
                        hasAms = printerStatus.amsUnits.isNotEmpty(),
                        amsUnits = printerStatus.amsUnits,
                        activeTrayId = printerStatus.trayNow,
                        errorMessage = ftpError,
                        transferProgress = ftpTransferProgress,
                        transferName = ftpTransferName,
                        onCancelTransfer = { viewModel.cancelTransfer() },
                        onConnectPrinter = if (savedPrinters.isNotEmpty()) { { viewModel.reconnect() } } else null,
                        onUploadOnly = { opts ->
                            request.uri?.let { uri ->
                                viewModel.uploadAndPrint(
                                    uri = uri,
                                    options = opts,
                                    startPrintAfterUpload = false,
                                    targetDir = "/model",
                                    onComplete = {
                                        viewModel.clearPrintJob()
                                    },
                                )
                            }
                        },
                        onUploadAndPrint = { opts ->
                            request.uri?.let { uri ->
                                viewModel.uploadAndPrint(
                                    uri = uri,
                                    options = opts,
                                    startPrintAfterUpload = true,
                                    targetDir = "/model",
                                    onComplete = {
                                        viewModel.clearPrintJob()
                                        viewModel.closeFileManager()
                                        showFileManager = false
                                    },
                                )
                            }
                        },
                        onStartPrintFromSd = { opts ->
                            request.remotePath?.let { path ->
                                viewModel.startPrintFromSd(
                                    remotePath = path,
                                    fileName = request.fileName,
                                    options = opts,
                                    md5 = request.md5,
                                )
                                viewModel.clearPrintJob()
                                viewModel.closeFileManager()
                                showFileManager = false
                            }
                        },
                        onDownloadFile = {
                            val entry = fileList.firstOrNull { it.name == request.fileName }
                            if (entry != null) {
                                viewModel.downloadFile(entry)
                            }
                            viewModel.clearPrintJob()
                        },
                        onDismiss = {
                            viewModel.clearPrintJob()
                        },
                    )
                }
                } // end else (!isInPipMode)
            }
        }
    }
}
