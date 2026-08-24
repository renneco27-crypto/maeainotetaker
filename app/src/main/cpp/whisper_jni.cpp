#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct WhisperContext {
    whisper_context* ctx = nullptr;
    whisper_full_params params;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_cortesnotetaker_app_stt_WhisperEngine_nativeInit(
    JNIEnv* env, jobject thiz, jstring model_path) {
    
    const char* model_path_c = env->GetStringUTFChars(model_path, nullptr);
    if (!model_path_c) {
        LOGE("Failed to get model path string");
        return 0;
    }

    LOGD("Loading model from: %s", model_path_c);

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;

    whisper_context* ctx = whisper_init_from_file_with_params(model_path_c, cparams);
    env->ReleaseStringUTFChars(model_path, model_path_c);

    if (!ctx) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }

    WhisperContext* wrapper = new WhisperContext();
    wrapper->ctx = ctx;

    // Default parameters for transcription
    wrapper->params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wrapper->params.print_realtime = false;
    wrapper->params.print_progress = false;
    wrapper->params.print_timestamps = true;
    wrapper->params.print_special = false;
    wrapper->params.translate = false;
    wrapper->params.language = "auto";
    wrapper->params.detect_language = true;
    wrapper->params.suppress_blank = true;
    wrapper->params.suppress_non_speech_tokens = true;
    wrapper->params.max_len = 0;
    wrapper->params.split_on_word = true;
    wrapper->params.token_timestamps = true;
    wrapper->params.thold_pt = 0.01f;
    wrapper->params.thold_ptsum = 0.01f;
    wrapper->params.max_tokens = 0;
    wrapper->params.speed_up = false;
    wrapper->params.audio_ctx = 0;

    LOGD("Whisper context initialized successfully");
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jobject JNICALL
Java_com_cortesnotetaker_app_stt_WhisperEngine_nativeTranscribe(
    JNIEnv* env, jobject thiz, jlong ctx_ptr, jfloatArray pcm_data, jstring language) {
    
    WhisperContext* wrapper = reinterpret_cast<WhisperContext*>(ctx_ptr);
    if (!wrapper || !wrapper->ctx) {
        LOGE("Invalid whisper context");
        return nullptr;
    }

    // Get PCM data
    jfloat* pcm_elements = env->GetFloatArrayElements(pcm_data, nullptr);
    jsize pcm_length = env->GetArrayLength(pcm_data);
    
    if (!pcm_elements || pcm_length == 0) {
        LOGE("Invalid PCM data");
        return nullptr;
    }

    // Get language parameter
    const char* language_c = "auto";
    if (language) {
        language_c = env->GetStringUTFChars(language, nullptr);
    }

    // Update language if specified
    if (language_c && strcmp(language_c, "auto") != 0) {
        wrapper->params.language = language_c;
        wrapper->params.detect_language = false;
    } else {
        wrapper->params.language = "auto";
        wrapper->params.detect_language = true;
    }

    LOGD("Starting transcription: %d samples, language: %s", pcm_length, language_c);

    // Run whisper
    int result = whisper_full(wrapper->ctx, wrapper->params, pcm_elements, pcm_length);
    
    env->ReleaseFloatArrayElements(pcm_data, pcm_elements, JNI_ABORT);
    if (language) {
        env->ReleaseStringUTFChars(language, language_c);
    }

    if (result != 0) {
        LOGE("Whisper transcription failed with code: %d", result);
        return nullptr;
    }

    // Get results
    int n_segments = whisper_full_n_segments(wrapper->ctx);
    LOGD("Transcription complete: %d segments", n_segments);

    // Build result object
    jclass result_class = env->FindClass("com/cortesnotetaker/app/stt/WhisperResult");
    if (!result_class) {
        LOGE("Failed to find WhisperResult class");
        return nullptr;
    }

    jmethodID constructor = env->GetMethodID(result_class, "<init>", "()V");
    jobject result_obj = env->NewObject(result_class, constructor);

    // Set text field (combined transcript)
    std::string full_text;
    for (int i = 0; i < n_segments; i++) {
        const char* segment_text = whisper_full_get_segment_text(wrapper->ctx, i);
        if (segment_text) {
            full_text += segment_text;
            if (i < n_segments - 1) full_text += " ";
        }
    }

    jfieldID text_field = env->GetFieldID(result_class, "text", "Ljava/lang/String;");
    env->SetObjectField(result_obj, text_field, env->NewStringUTF(full_text.c_str()));

    // Set avgLogProb field
    float avg_logprob = 0.0f;
    int total_tokens = 0;
    for (int i = 0; i < n_segments; i++) {
        const whisper_token* tokens = whisper_full_get_token_ids(wrapper->ctx, i);
        int n_tokens = whisper_full_get_n_tokens(wrapper->ctx, i);
        for (int j = 0; j < n_tokens; j++) {
            avg_logprob += whisper_full_get_token_logprob(wrapper->ctx, i, j);
            total_tokens++;
        }
    }
    if (total_tokens > 0) {
        avg_logprob /= total_tokens;
    }

    jfieldID logprob_field = env->GetFieldID(result_class, "avgLogProb", "F");
    env->SetFloatField(result_obj, logprob_field, avg_logprob);

    // Set segments array
    jclass segment_class = env->FindClass("com/cortesnotetaker/app/stt/WhisperSegment");
    if (!segment_class) {
        LOGE("Failed to find WhisperSegment class");
        return result_obj;
    }

    jmethodID segment_constructor = env->GetMethodID(segment_class, "<init>", "()V");
    jfieldID segment_text_field = env->GetFieldID(segment_class, "text", "Ljava/lang/String;");
    jfieldID segment_start_field = env->GetFieldID(segment_class, "startMs", "J");
    jfieldID segment_end_field = env->GetFieldID(segment_class, "endMs", "J");
    jfieldID segment_avg_logprob_field = env->GetFieldID(segment_class, "avgLogProb", "F");

    jobjectArray segments_array = env->NewObjectArray(n_segments, segment_class, nullptr);
    
    for (int i = 0; i < n_segments; i++) {
        jobject segment_obj = env->NewObject(segment_class, segment_constructor);
        
        const char* seg_text = whisper_full_get_segment_text(wrapper->ctx, i);
        if (seg_text) {
            env->SetObjectField(segment_obj, segment_text_field, env->NewStringUTF(seg_text));
        }

        int64_t t0 = whisper_full_get_segment_t0(wrapper->ctx, i);
        int64_t t1 = whisper_full_get_segment_t1(wrapper->ctx, i);
        env->SetLongField(segment_obj, segment_start_field, t0 * 10); // Convert to ms
        env->SetLongField(segment_obj, segment_end_field, t1 * 10);   // Convert to ms

        // Calculate segment avg logprob
        float seg_logprob = 0.0f;
        int seg_tokens = whisper_full_get_n_tokens(wrapper->ctx, i);
        for (int j = 0; j < seg_tokens; j++) {
            seg_logprob += whisper_full_get_token_logprob(wrapper->ctx, i, j);
        }
        if (seg_tokens > 0) {
            seg_logprob /= seg_tokens;
        }
        env->SetFloatField(segment_obj, segment_avg_logprob_field, seg_logprob);

        env->SetObjectArrayElement(segments_array, i, segment_obj);
    }

    jfieldID segments_field = env->GetFieldID(result_class, "segments", "[Lcom/cortesnotetaker/app/stt/WhisperSegment;");
    env->SetObjectField(result_obj, segments_field, segments_array);

    LOGD("Returning transcription result");
    return result_obj;
}

JNIEXPORT void JNICALL
Java_com_cortesnotetaker_app_stt_WhisperEngine_nativeRelease(
    JNIEnv* env, jobject thiz, jlong ctx_ptr) {
    
    WhisperContext* wrapper = reinterpret_cast<WhisperContext*>(ctx_ptr);
    if (wrapper) {
        if (wrapper->ctx) {
            whisper_free(wrapper->ctx);
            wrapper->ctx = nullptr;
        }
        delete wrapper;
        LOGD("Whisper context released");
    }
}

} // extern "C"