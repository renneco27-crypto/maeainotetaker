package com.cortesnotetaker.app.ui.recording

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.keyboard.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortesnotetaker.app.service.RecordingService
import com.cortesnotetaker.app.stt.TranscriptSegment
import com.cortesnotetaker.app.ui.theme.LecturePalColors
import com.cortesnotetaker.app.ui.theme.LecturePalTheme

@Composable
fun RecordingScreen(
    onRecordingComplete: (Long) -> Unit,
    viewModel: RecordingViewModel = viewModel()
) {
    var subject by remember { mutableStateOf("") }
    var showSubjectInput by remember { mutableStateOf(true) }
    var isBinding by remember { mutableStateOf(false) }
    
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Service connection
    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? RecordingService.LocalBinder
                binder?.let { viewModel.setService(it.getService()) }
                isBinding = true
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isBinding = false
            }
        }
    }

    // Bind to service
    DisposableEffect(key1 = true) {
        val intent = Intent(LocalContext.current, RecordingService::class.java)
        val context = LocalContext.current
        val bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            viewModel._uiState.update { it.copy(error = "Failed to bind to recording service") }
        }
        onDispose {
            if (isBinding) {
                context.unbindService(serviceConnection)
            }
        }
    }

    var stopRequested by remember { mutableStateOf(false) }
    
    LaunchedEffect(uiState.isRecording, stopRequested) {
        if (!uiState.isRecording && !showSubjectInput && stopRequested) {
            viewModel.stopRecording { noteId ->
                onRecordingComplete(noteId)
            }
            stopRequested = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recording", fontWeight = FontWeight.Bold) },
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
            if (showSubjectInput) {
                SubjectInput(
                    subject = subject,
                    onSubjectChange = { subject = it },
                    onStartRecording = {
                        showSubjectInput = false
                        viewModel.startRecording(subject)
                    }
                )
            } else {
                RecordingActiveView(
                    uiState = uiState,
                    onPause = { viewModel.pauseRecording() },
                    onResume = { viewModel.resumeRecording() },
                    onStop = {
                        stopRequested = true
                        viewModel.stopRecording { /* callback handled by LaunchedEffect */ }
                    }
                )
            }

            if (uiState.error != null) {
                Text(
                    text = uiState.error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun SubjectInput(
    subject: String,
    onSubjectChange: (String) -> Unit,
    onStartRecording: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            androidx.compose.material3.Icon(
                imageVector = androidx.compose.material.icons.filled.Mic,
                contentDescription = "",
                tint = MaterialTheme.colorScheme.primary,
                size = 96.dp
            )
            Text(
                text = "New Lecture Recording",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Enter a subject (optional) and tap Start Recording",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            androidx.compose.material3.TextField(
                value = subject,
                onValueChange = onSubjectChange,
                label = { Text("Subject (e.g., Calculus, History)") },
                modifier = Modifier.fillMaxWidth().width(300.dp),
                keyboardOptions = KeyboardOptions.Default,
                singleLine = true
            )
            Button(
                onClick = onStartRecording,
                modifier = Modifier.fillMaxWidth().width(200.dp).height(56.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Start Recording", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            }
        }
    )
}

@Composable
fun RecordingActiveView(
    uiState: RecordingViewModel.RecordingUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Subject display
        if (uiState.subject.isNotBlank()) {
            Text(
                text = uiState.subject,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Timer
        Text(
            text = viewModel.formatElapsedTime(uiState.elapsedMs),
            style = MaterialTheme.typography.displayLarge,
            fontWeight = FontWeight.Bold,
            color = if (uiState.isPaused) MaterialTheme.colorScheme.onSurfaceVariant
            else LecturePalColors.RecordingRed
        )

        // Recording indicator
        RecordingIndicator(isRecording = uiState.isRecording && !uiState.isPaused)

        // Live transcript
        if (uiState.segments.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    reverseLayout = true,
                    userScrollEnabled = true
                ) {
                    items(uiState.segments.reversed()) { segment ->
                        TranscriptSegmentItem(segment = segment)
                    }
                }
            }
        }

        // Control buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            if (uiState.isPaused) {
                Button(
                    onClick = onResume,
                    modifier = Modifier.width(0f).weight(1f).padding(end = 8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Resume", fontWeight = FontWeight.Medium)
                }
            } else {
                Button(
                    onClick = onPause,
                    modifier = Modifier.width(0f).weight(1f).padding(end = 8.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text("Pause", fontWeight = FontWeight.Medium)
                }
            }

            Button(
                onClick = onStop,
                modifier = Modifier.width(0f).weight(1f).padding(start = 8.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text("Stop", fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun RecordingIndicator(isRecording: Boolean) {
    Box(
        modifier = Modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        // Outer pulse ring
        if (isRecording) {
            PulseRing()
        }
        // Inner circle
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(60.dp)
        ) {
            val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
            drawCircle(
                color = if (isRecording) LecturePalColors.RecordingRed else LecturePalColors.WaveformInactive,
                center = center,
                radius = 30.dp.toPx()
            )
        }
    }
}

@Composable
fun PulseRing() {
    var progress by remember { mutableStateOf(0f) }
    androidx.compose.animation.animateFloatAsState(
        targetValue = 1f,
        animationSpec = androidx.compose.animation.infiniteRepeatable(
            animation = androidx.compose.animation.tween(1500, delayMillis = 0, easing = androidx.compose.animation.LinearEasing),
            repeatMode = androidx.compose.animation.RepeatMode.Restart
        )
    ).value.also { progress = it }

    androidx.compose.foundation.Canvas(
        modifier = Modifier.size(80.dp)
    ) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val radius = 30.dp.toPx() + (20.dp.toPx() * progress)
        val alpha = (1f - progress) * 0.5f
        drawCircle(
            color = LecturePalColors.RecordingRed.copy(alpha = alpha),
            center = center,
            radius = radius,
            style = androidx.compose.ui.draw.Stroke(width = 3.dp.toPx())
        )
    }
}

@Composable
fun TranscriptSegmentItem(segment: TranscriptSegment) {
    val isUnclear = segment.isUnclear
    val text = segment.text
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = segment.formatTimestamp(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = if (isUnclear) "[unclear]" else text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isUnclear) LecturePalColors.UnclearText else MaterialTheme.colorScheme.onSurface,
            fontStyle = if (isUnclear) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal
        )
    }
}