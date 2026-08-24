#include <jni.h>
#include <string>
#include <vector>
#include <cmath>
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
    wrapper->params.n_threads = 4;
    wrapper->params.print_realtime = false;
    wrapper->params.print_progress = false;
    wrapper->params.print_timestamps = true;
    wrapper->params.print_special = false;
    wrapper->params.translate = false;
    wrapper->params.language = "auto";
    wrapper->params.detect_language = true;
    wrapper->params.suppress_blank = true;
    wrapper->params.max_len = 0;
    wrapper->params.split_on_word = true;
    wrapper->params.token_timestamps = true;
    wrapper->params.thold_pt = 0.01f;
    wrapper->params.thold_ptsum = 0.01f;
    wrapper->params.max_tokens = 0;
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

    // Set text field (combined transcript)
    std::string full_text;
    for (int i = 0; i < n_segments; i++) {
        const char* segment_text = whisper_full_get_segment_text(wrapper->ctx, i);
        if (segment_text) {
            full_text += segment_text;
            if (i < n_segments - 1) full_text += " ";
        }
    }

    // Set avgLogProb field
    float avg_logprob = 0.0f;
    int total_tokens = 0;
    for (int i = 0; i < n_segments; i++) {
        int n_tokens = whisper_full_n_tokens(wrapper->ctx, i);
        for (int j = 0; j < n_tokens; j++) {
            float p = whisper_full_get_token_p(wrapper->ctx, i, j);
            avg_logprob += (p > 0.0001f ? logf(p) : -9.21f);
            total_tokens++;
        }
    }
    if (total_tokens > 0) {
        avg_logprob /= total_tokens;
    }

    jmethodID constructor = env->GetMethodID(result_class, "<init>", "(Ljava/lang/String;F)V");
    if (!constructor) {
        LOGE("Failed to find WhisperResult constructor(String, float)");
        return nullptr;
    }

    jstring jtext = env->NewStringUTF(full_text.c_str());
    jobject result_obj = env->NewObject(result_class, constructor, jtext, avg_logprob);

    LOGD("Returning transcription result: %s", full_text.c_str());
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