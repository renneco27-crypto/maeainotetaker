# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# Koin
-keep class org.koin.** { *; }
-keep class com.cortesnotetaker.app.di.** { *; }

# ONNX Runtime
-keep class com.microsoft.onnxruntime.** { *; }

# Whisper JNI
-keep class com.cortesnotetaker.app.stt.WhisperEngine { *; }
-keep class com.cortesnotetaker.app.stt.TranscriptSegment { *; }

# Media3 ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-keep class androidx.media3.** { *; }

# Coroutines
-keep class kotlinx.coroutines.** { *; }

# Compose
-keep class androidx.compose.** { *; }

# Model classes
-keep class com.cortesnotetaker.app.data.db.entity.** { *; }
-keep class com.cortesnotetaker.app.data.repository.** { *; }

# Serialization
-keepclassmembers class * {
    @kotlinx.serialization.Serializable *;
}

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep JNI bridge class
-keep class com.cortesnotetaker.app.stt.WhisperEngine {
    native <methods>;
    *;
}