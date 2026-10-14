package org.cygnusx1.openbu.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cygnusx1.openbu.R
import org.cygnusx1.openbu.network.FtpFileEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    fileList: List<FtpFileEntry>,
    currentPath: String,
    isLoading: Boolean,
    error: String?,
    transferProgress: Float?,
    transferName: String?,
    onCancelTransfer: () -> Unit,
    onNavigateTo: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onDownloadFile: (FtpFileEntry) -> Unit,
    onDeleteFile: (FtpFileEntry) -> Unit,
    onUploadFile: (Uri) -> Unit,
    onSelectFileForPrint: (FtpFileEntry) -> Unit = {},
    onClearError: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var fileToDelete by remember { mutableStateOf<FtpFileEntry?>(null) }

    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(R.string.delete_file)) },
            text = { Text(stringResource(R.string.delete_file_confirm, fileToDelete!!.name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteFile(fileToDelete!!)
                    fileToDelete = null
                }) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onUploadFile(it) }
    }

    LaunchedEffect(error) {
        if (error != null) {
            snackbarHostState.showSnackbar(error)
            onClearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.file_manager))
                        Text(
                            text = currentPath,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath != "/") {
                            onNavigateUp()
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("*/*")) },
                        enabled = transferProgress == null,
                    ) {
                        Icon(Icons.Filled.Upload, contentDescription = stringResource(R.string.cd_upload_file))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Transfer progress bar
            if (transferProgress != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = transferName ?: "Transferring...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { transferProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.percent, (transferProgress * 100).toInt()),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCancelTransfer) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cd_cancel_transfer),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                HorizontalDivider()
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading && fileList.isEmpty() && transferProgress == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else if (fileList.isEmpty() && !isLoading && transferProgress == null) {
                    Text(
                        text = stringResource(R.string.directory_empty),
                        modifier = Modifier.align(Alignment.Center),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // Up navigation
                        if (currentPath != "/") {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onNavigateUp() }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.CreateNewFolder,
                                        contentDescription = stringResource(R.string.cd_parent_directory),
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(text = stringResource(R.string.parent_directory), style = MaterialTheme.typography.bodyLarge)
                                }
                                HorizontalDivider()
                            }
                        }

                        items(fileList, key = { it.name }) { entry ->
                            if (entry.isDirectory) {
                                FileEntryRow(
                                    entry = entry,
                                    onClick = { onNavigateTo(entry.name) },
                                    onLongClick = {},
                                )
                                HorizontalDivider()
                            } else {
                                val lower = entry.name.lowercase()
                                val isPrintable = lower.endsWith(".3mf") || lower.endsWith(".gcode")
                                val dismissState = rememberSwipeToDismissBoxState(
                                    confirmValueChange = { value ->
                                        when (value) {
                                            SwipeToDismissBoxValue.StartToEnd -> {
                                                if (isPrintable) {
                                                    onSelectFileForPrint(entry)
                                                }
                                                false
                                            }
                                            SwipeToDismissBoxValue.EndToStart -> {
                                                fileToDelete = entry
                                                false
                                            }
                                            SwipeToDismissBoxValue.Settled -> false
                                        }
                                    },
                                    positionalThreshold = { distance -> distance * 0.3f },
                                )

                                SwipeToDismissBox(
                                    state = dismissState,
                                    enableDismissFromStartToEnd = isPrintable,
                                    enableDismissFromEndToStart = true,
                                    backgroundContent = {
                                        val direction = dismissState.dismissDirection
                                        val color by animateColorAsState(
                                            targetValue = when (dismissState.targetValue) {
                                                SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                                SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                                SwipeToDismissBoxValue.Settled -> Color.Transparent
                                            },
                                            label = "swipeColor",
                                        )
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .background(color)
                                                .padding(horizontal = 20.dp),
                                            contentAlignment = when (direction) {
                                                SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                                SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                                SwipeToDismissBoxValue.Settled -> Alignment.Center
                                            },
                                        ) {
                                            if (direction == SwipeToDismissBoxValue.StartToEnd && isPrintable) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Filled.Print,
                                                        contentDescription = stringResource(R.string.start_print),
                                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = stringResource(R.string.start_print),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                }
                                            } else if (direction == SwipeToDismissBoxValue.EndToStart) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = stringResource(R.string.delete),
                                                        style = MaterialTheme.typography.labelLarge,
                                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                                        fontWeight = FontWeight.Bold,
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Icon(
                                                        imageVector = Icons.Filled.Delete,
                                                        contentDescription = stringResource(R.string.delete),
                                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                                        modifier = Modifier.size(24.dp),
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    content = {
                                        FileEntryRow(
                                            entry = entry,
                                            onPrintClick = if (isPrintable) { { onSelectFileForPrint(entry) } } else null,
                                            onClick = {
                                                if (isPrintable) {
                                                    onSelectFileForPrint(entry)
                                                } else {
                                                    onDownloadFile(entry)
                                                }
                                            },
                                            onLongClick = {
                                                fileToDelete = entry
                                            },
                                        )
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }

                if (isLoading && fileList.isNotEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .size(24.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileEntryRow(
    entry: FtpFileEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPrintClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
            contentDescription = if (entry.isDirectory) stringResource(R.string.cd_folder) else stringResource(R.string.cd_file),
            modifier = Modifier.size(24.dp),
            tint = if (entry.isDirectory) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.isDirectory) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = formatFileSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = entry.lastModified,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (onPrintClick != null) {
            IconButton(onClick = onPrintClick) {
                Icon(
                    imageVector = Icons.Filled.Print,
                    contentDescription = stringResource(R.string.cd_print_file),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
}
