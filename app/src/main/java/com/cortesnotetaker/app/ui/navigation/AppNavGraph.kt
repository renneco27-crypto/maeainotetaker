package com.cortesnotetaker.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cortesnotetaker.app.ui.notedetail.NoteDetailScreen
import com.cortesnotetaker.app.ui.notelist.NoteListScreen
import com.cortesnotetaker.app.ui.recording.RecordingScreen

@Composable
fun AppNavHost(navController: NavHostController, startDestination: String = "note_list") {
    NavHost(navController = navController, startDestination = startDestination) {
        composable("note_list") {
            NoteListScreen(
                onNewRecording = {
                    navController.navigate("recording")
                },
                onNoteClick = { noteId ->
                    navController.navigate("note_detail/$noteId")
                }
            )
        }
        
        composable("recording") {
            RecordingScreen(onRecordingComplete = { noteId ->
                navController.navigate("note_detail/$noteId") {
                    popUpTo("note_list") { inclusive = false }
                }
            })
        }
        
        composable(
            route = "note_detail/{noteId}",
            arguments = listOf(navArgument("noteId") { type = NavType.LongType })
        ) { backStackEntry ->
            val noteId = backStackEntry.arguments?.getLong("noteId") ?: 0L
            NoteDetailScreen(
                noteId = noteId,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

@Composable
fun RememberAppNavController(): NavHostController = rememberNavController()