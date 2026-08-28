package com.cortesnotetaker.app.ui.notedetail

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import com.cortesnotetaker.app.ui.theme.LecturePalColors
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteDetailScreen(
    noteId: Long,
    onNavigateBack: () -> Unit = {},
    viewModel: NoteDetailViewModel = koinViewModel { parametersOf(noteId) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf(uiState.note?.title ?: "") }
    var isEditingTitle by remember { mutableStateOf(false) }
    var editingSegmentId by remember { mutableStateOf<Long?>(null) }
    var editingTranscript by remember { mutableStateOf("") }

    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingTitle) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                            singleLine = true
                        )
                    } else {
                        Text(
                            text = uiState.note?.title?.ifBlank { "Untitled Note" } ?: "Untitled Note",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isEditingTitle) {
                        if (uiState.segments.isNotEmpty()) {
                            IconButton(onClick = {
                                val fullText = uiState.segments.joinToString("\n\n") { it.displayTranscript }
                                if (fullText.isNotBlank()) {
                                    clipboardManager.setText(AnnotatedString(fullText))
                                    Toast.makeText(context, "Full transcript copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy all transcript")
                            }
                        }
                        IconButton(onClick = {
                            title = uiState.note?.title ?: ""
                            isEditingTitle = true
                        }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit title")
                        }
                    } else {
                        IconButton(onClick = {
                            isEditingTitle = false
                            viewModel.updateNoteTitle(title)
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                        IconButton(onClick = {
                            isEditingTitle = false
                            title = uiState.note?.title ?: ""
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Audio Player
            AudioPlayerView(
                uiState = uiState,
                viewModel = viewModel,
                onPlayPause = { viewModel.playPause() },
                onSeek = { viewModel.seekTo(it) }
            )

            // Quick Actions (Copy to Claude & Copy All)
            if (uiState.segments.isNotEmpty()) {
                val fullTranscript = remember(uiState.segments) {
                    uiState.segments.joinToString("\n\n") { it.displayTranscript }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            if (fullTranscript.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(fullTranscript))
                                Toast.makeText(context, "Transcript copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy All")
                    }

                    Button(
                        onClick = {
                            if (fullTranscript.isNotBlank()) {
                                val claudePrompt = "Please analyze this lecture transcript, summarize the core points, and generate detailed structured study notes:\n\n$fullTranscript"
                                clipboardManager.setText(AnnotatedString(claudePrompt))
                                Toast.makeText(context, "Copied prompt! Opening Claude...", Toast.LENGTH_SHORT).show()
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://claude.ai/chat/"))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.weight(1.3f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy to Claude")
                    }
                }
            }

            // Transcript
            if (uiState.segments.isNotEmpty()) {
                TranscriptList(
                    segments = uiState.segments,
                    activeSegmentIndex = uiState.activeSegmentIndex,
                    onSegmentClick = { segment -> viewModel.onSegmentClick(segment) },
                    onSegmentLongClick = { segment ->
                        editingSegmentId = segment.id
                        editingTranscript = segment.displayTranscript
                    },
                    editingSegmentId = editingSegmentId,
                    editingTranscript = editingTranscript,
                    onTranscriptChange = { editingTranscript = it },
                    onSaveEdit = {
                        editingSegmentId?.let { id ->
                            viewModel.updateSegmentTranscript(id, editingTranscript)
                            editingSegmentId = null
                        }
                    },
                    onCancelEdit = { editingSegmentId = null }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No transcript available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun AudioPlayerView(
    uiState: NoteDetailViewModel.NoteDetailUiState,
    viewModel: NoteDetailViewModel,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit
) {
    val duration = if (uiState.totalDurationMs > 0) uiState.totalDurationMs else (uiState.note?.durationMs ?: 0L)
    val currentPosition = uiState.currentPlaybackPositionMs.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = viewModel.formatTimestamp(currentPosition),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = viewModel.formatDuration(duration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Slider(
                value = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f,
                onValueChange = { progress ->
                    val newPosition = (progress * duration).toLong()
                    onSeek(newPosition)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = duration > 0
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                Button(onClick = onPlayPause) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play"
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptList(
    segments: List<SegmentEntity>,
    activeSegmentIndex: Int,
    onSegmentClick: (SegmentEntity) -> Unit,
    onSegmentLongClick: (SegmentEntity) -> Unit,
    editingSegmentId: Long?,
    editingTranscript: String,
    onTranscriptChange: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit
) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(segments) { segment ->
                val isEditing = editingSegmentId == segment.id
                Card(
                    onClick = { 
                        if (!isEditing) onSegmentClick(segment) 
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (segments.indexOf(segment) == activeSegmentIndex)
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${segment.startMs / 1000}s",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (isEditing) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    IconButton(
                                        onClick = onSaveEdit,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Save",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = onCancelEdit,
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            } else {
                                IconButton(
                                    onClick = { onSegmentLongClick(segment) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit words",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        if (isEditing) {
                            OutlinedTextField(
                                value = editingTranscript,
                                onValueChange = onTranscriptChange,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )
                        } else {
                            Text(
                                text = if (segment.isUnclear) "[unclear]" else segment.displayTranscript,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (segment.isUnclear) LecturePalColors.UnclearText else MaterialTheme.colorScheme.onSurface,
                                fontStyle = if (segment.isUnclear) FontStyle.Italic else FontStyle.Normal
                            )
                        }
                    }
                }
            }
        }
    }
}