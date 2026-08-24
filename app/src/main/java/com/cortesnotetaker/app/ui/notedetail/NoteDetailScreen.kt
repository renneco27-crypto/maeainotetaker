package com.cortesnotetaker.app.ui.notedetail

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortesnotetaker.app.data.db.entity.SegmentEntity
import com.cortesnotetaker.app.ui.theme.LecturePalColors
import com.cortesnotetaker.app.ui.theme.LecturePalTheme
import com.google.android.material.slider.Slider
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.launch

@Composable
fun NoteDetailScreen(
    noteId: Long,
    viewModel: NoteDetailViewModel = viewModel(factory = NoteDetailViewModel.Factory(noteId))
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var title by remember { mutableStateOf(uiState.note?.title ?: "") }
    var isEditingTitle by remember { mutableStateOf(false) }
    var editingSegmentId by remember { mutableStateOf<Long?>(null) }
    var editingTranscript by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (isEditingTitle) {
                        TextField(
                            value = title,
                            onValueChange = { title = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 16.dp),
                            singleLine = true,
                            keyboardOptions = androidx.compose.ui.input.keyboard.KeyboardOptions.Default,
                            colors = androidx.compose.material3.TextFieldDefaults.textFieldColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    } else {
                        Text(
                            text = title.ifBlank { "Untitled" },
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { /* Navigate back */ }) {
                        Icon(androidx.compose.material.icons.filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isEditingTitle) {
                        IconButton(onClick = { isEditingTitle = true }) {
                            Icon(androidx.compose.material.icons.filled.Edit, contentDescription = "Edit title")
                        }
                    } else {
                        IconButton(onClick = {
                            isEditingTitle = false
                            viewModel.updateNoteTitle(title)
                        }) {
                            Icon(androidx.compose.material.icons.filled.Check, contentDescription = "Save")
                        }
                        IconButton(onClick = { 
                            isEditingTitle = false
                            title = uiState.note?.title ?: ""
                        }) {
                            Icon(androidx.compose.material.icons.filled.Close, contentDescription = "Cancel")
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
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Audio Player
            AudioPlayerView(
                uiState = uiState,
                viewModel = viewModel,
                onPlayPause = { viewModel.playPause() },
                onSeek = { viewModel.seekTo(it) }
            )

            // Transcript
            if (uiState.segments.isNotEmpty()) {
                TranscriptList(
                    segments = uiState.segments,
                    activeSegmentIndex = uiState.activeSegmentIndex,
                    onSegmentClick = { segment ->
                        viewModel.onSegmentClick(segment)
                    },
                    onSegmentLongClick = { segment ->
                        editingSegmentId = segment.id
                        editingTranscript = segment.displayTranscript
                    },
                    editingSegmentId = editingSegmentId,
                    editingTranscript = editingTranscript,
                    onSaveEdit = {
                        editingSegmentId?.let { id ->
                            viewModel.updateSegmentTranscript(id, editingTranscript)
                            editingSegmentId = null
                        }
                    },
                    onCancelEdit = {
                        editingSegmentId = null
                    }
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
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
    val duration = uiState.note?.durationMs ?: 0L
    val currentPosition = uiState.currentPlaybackPositionMs

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

            // Progress bar
            androidx.compose.material3.Slider(
                value = currentPosition.toFloat() / duration.toFloat().coerceAtLeast(1f),
                onValueChange = { progress ->
                    val newPosition = (progress * duration).toLong()
                    onSeek(newPosition)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = duration > 0
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = onPlayPause,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) 
                            androidx.compose.material.icons.filled.Pause 
                        else 
                            androidx.compose.material.icons.filled.PlayArrow,
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
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp, 8.dp)
    ) {
        items(segments) { segment ->
            val isActive = segments.indexOf(segment) == activeSegmentIndex
            val isEditing = editingSegmentId == segment.id
            
            TranscriptSegmentRow(
                segment = segment,
                isActive = isActive,
                isEditing = isEditing,
                editingTranscript = editingTranscript,
                onClick = { onSegmentClick(segment) },
                onLongClick = { onSegmentLongClick(segment) },
                onSaveEdit = onSaveEdit,
                onCancelEdit = onCancelEdit,
                onTranscriptChange = { editingTranscript = it }
            )
        }
    }
}

@Composable
fun TranscriptSegmentRow(
    segment: SegmentEntity,
    isActive: Boolean,
    isEditing: Boolean,
    editingTranscript: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onTranscriptChange: (String) -> Unit
) {
    val isUnclear = segment.isUnclear
    val displayText = if (isEditing) editingTranscript else segment.displayTranscript
    val textColor = if (isUnclear) LecturePalColors.UnclearText else MaterialTheme.colorScheme.onSurface
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(12.dp),
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatSegmentTimestamp(segment.startMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (segment.speakerLabel != null) {
                    Text(
                        text = segment.speakerLabel!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isEditing) {
                TextField(
                    value = editingTranscript,
                    onValueChange = onTranscriptChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 2,
                    colors = androidx.compose.material3.TextFieldDefaults.textFieldColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onCancelEdit) {
                        Text("Cancel")
                    }
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = onSaveEdit, colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )) {
                        Text("Save")
                    }
                }
            } else {
                Text(
                    text = if (isUnclear) "[unclear]" else displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    fontStyle = if (isUnclear) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
                )
            }
        }
    }
}

fun formatSegmentTimestamp(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
    else String.format("%02d:%02d", minutes, seconds)
}