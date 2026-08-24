package com.cortesnotetaker.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cortesnotetaker.app.ui.notelist.NoteListScreen
import com.cortesnotetaker.app.ui.recording.RecordingScreen
import com.cortesnotetaker.app.ui.notedetail.NoteDetailScreen

@Composable
fun AppNavHost(navController: NavHostController, startDestination: String = "note_list") {
    NavHost(navController, startDestination) {
        composable("note_list") {
            NoteListScreen(onNewRecording = {
                navController.navigate("recording") {
                    popUpTo("note_list") { inclusive = true }
                }
            })
        }
        
        composable("recording") {
            RecordingScreen(onRecordingComplete = { noteId ->
                navController.navigate("note_detail/$noteId") {
                    popUpTo("note_list") { inclusive = true }
                }
            })
        }
        
        composable(
            route = "note_detail/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.LongType })
        ) { backStackEntry ->
            val noteId = backStackEntry.getLong() ?: 0L
            NoteDetailScreen(noteId = noteId)
        }
    }
}

@Composable
fun RememberAppNavController(): NavHostController = rememberNavController()