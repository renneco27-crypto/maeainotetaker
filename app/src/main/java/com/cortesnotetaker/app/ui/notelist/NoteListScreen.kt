package com.cortesnotetaker.app.ui.notelist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cortesnotetaker.app.data.db.entity.NoteEntity
import org.koin.androidx.compose.koinViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteListScreen(
    onNewRecording: () -> Unit,
    onNoteClick: (Long) -> Unit = {},
    viewModel: NoteListViewModel = koinViewModel()
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            if (showSearch) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchText,
                            onValueChange = { text ->
                                searchText = text
                                viewModel.search(text)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search notes...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = {
                                    showSearch = false
                                    searchText = ""
                                    viewModel.search("")
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            },
                            singleLine = true
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                TopAppBar(
                    title = { Text("LecturePal", fontWeight = FontWeight.Bold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    actions = {
                        IconButton(onClick = { showSearch = true }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        
                        val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
                            contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
                        ) { uri ->
                            if (uri != null) {
                                viewModel.handleSharedAudioFile(uri)
                            }
                        }
                        
                        IconButton(onClick = { launcher.launch("audio/*") }) {
                            Icon(Icons.Default.Add, contentDescription = "Import Audio File")
                        }
                        
                        var showMenu by remember { mutableStateOf(false) }
                        var currentMode by remember { mutableStateOf(com.cortesnotetaker.app.stt.AppSettings.mode) }
                        
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Settings")
                            }
                            androidx.compose.material3.DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (currentMode == com.cortesnotetaker.app.stt.TranscriptionMode.AUTO) "✓ Auto (Dual-Engine)" else "Auto (Dual-Engine)") },
                                    onClick = {
                                        com.cortesnotetaker.app.stt.AppSettings.mode = com.cortesnotetaker.app.stt.TranscriptionMode.AUTO
                                        currentMode = com.cortesnotetaker.app.stt.TranscriptionMode.AUTO
                                        showMenu = false
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (currentMode == com.cortesnotetaker.app.stt.TranscriptionMode.HOTSPOT_ONLY) "✓ Hotspot Only" else "Hotspot Only") },
                                    onClick = {
                                        com.cortesnotetaker.app.stt.AppSettings.mode = com.cortesnotetaker.app.stt.TranscriptionMode.HOTSPOT_ONLY
                                        currentMode = com.cortesnotetaker.app.stt.TranscriptionMode.HOTSPOT_ONLY
                                        showMenu = false
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(if (currentMode == com.cortesnotetaker.app.stt.TranscriptionMode.CLOUD_ONLY) "✓ Cloud Only (HF)" else "Cloud Only (HF)") },
                                    onClick = {
                                        com.cortesnotetaker.app.stt.AppSettings.mode = com.cortesnotetaker.app.stt.TranscriptionMode.CLOUD_ONLY
                                        currentMode = com.cortesnotetaker.app.stt.TranscriptionMode.CLOUD_ONLY
                                        showMenu = false
                                    }
                                )
                            }
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewRecording,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Add, contentDescription = "New recording")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            if (notes.isEmpty()) {
                EmptyState(onNewRecording = onNewRecording)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = {
                                viewModel.markNoteAsRead(note.id)
                                onNoteClick(note.id)
                            },
                            onDelete = { viewModel.deleteNote(note.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun NoteCard(
    note: NoteEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Note") },
            text = { Text("Are you sure you want to delete this note? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault()) }
    val isGreenReady = note.isUnread && note.status == "completed"
    val isProcessing = note.status == "processing"
    val isError = note.status == "error"
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isGreenReady) {
                androidx.compose.ui.graphics.Color(0xFFE8F5E9) // Light green tint
            } else {
                MaterialTheme.colorScheme.surfaceContainerHighest
            }
        ),
        border = if (isGreenReady) {
            androidx.compose.foundation.BorderStroke(2.dp, androidx.compose.ui.graphics.Color(0xFF4CAF50))
        } else null,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled Note" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                
                if (isGreenReady) {
                    androidx.compose.material3.Surface(
                        color = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "✨ Ready",
                            color = androidx.compose.ui.graphics.Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                } else if (isProcessing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (note.progress > 0) "${note.progress}%" else "Starting...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (isError) {
                    Text(
                        text = "⚠️ Upload Error",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    note.subject?.let { subj ->
                        if (subj.isNotBlank()) {
                            SuggestionChip(
                                onClick = { },
                                label = { Text(subj, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            }
            
            if (isProcessing) {
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { if (note.progress > 0) note.progress / 100f else 0.05f },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = dateFormat.format(Date(note.createdAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (note.durationMs > 0L) {
                        Text(
                            text = formatDuration(note.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyState(onNewRecording: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = "No recordings yet",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Tap Start Recording to record and transcribe a lecture",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Button(onClick = onNewRecording) {
                Text("Start Recording")
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}