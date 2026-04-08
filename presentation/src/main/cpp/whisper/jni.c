/**
 * SilentPulse — Whisper JNI bridge
 *
 * Package : com.silentpulse.messenger.feature.drivemode
 * Class   : WhisperLib (Kotlin companion object)
 *
 * Provides fully offline, on-device ASR via whisper.cpp (MIT).
 * No audio leaves the device. No network calls.
 */

#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <sys/sysinfo.h>

#include "whisper.h"
#include "ggml.h"

#define TAG     "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define UNUSED(x) (void)(x)

/* ─── Package prefix ──────────────────────────────────────────────────────── */
/* ## can only be used inside a #define body, not at file scope.
 * The two-level macro trick forces argument expansion before concatenation.  */
#define JNICAT_(a, b) a##b
#define JNICAT(a, b)  JNICAT_(a, b)
#define JNI_FUN(name) JNICAT(Java_com_silentpulse_messenger_feature_drivemode_WhisperLib_00024Companion_, name)

/* ─── Helpers ─────────────────────────────────────────────────────────────── */

/** Preferred thread count: min(physical cores, 8) */
static int preferred_threads(void) {
    struct sysinfo info;
    if (sysinfo(&info) != 0) return 4;
    int nproc = (int)info.procs;
    if (nproc < 1) nproc = 1;
    if (nproc > 8) nproc = 8;
    return nproc;
}

/* ─── Init / free ─────────────────────────────────────────────────────────── */

/**
 * Load a GGML Whisper model from an absolute file path.
 * The model file can be anywhere accessible to the app (internal storage,
 * SD card path passed by the user in settings, etc).
 *
 * Returns a native pointer (cast to jlong) or 0 on failure.
 */
JNIEXPORT jlong JNICALL
JNI_FUN(initContext)(JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    const char *path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    LOGI("Loading Whisper model: %s", path);
    struct whisper_context *ctx =
        whisper_init_from_file_with_params(path, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, path);
    if (!ctx) { LOGE("Failed to load model"); }
    return (jlong)(intptr_t)ctx;
}

/** Release a previously loaded context. */
JNIEXPORT void JNICALL
JNI_FUN(freeContext)(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    UNUSED(env); UNUSED(thiz);
    if (ctx_ptr) whisper_free((struct whisper_context *)(intptr_t)ctx_ptr);
}

/* ─── Transcription ───────────────────────────────────────────────────────── */

/**
 * Run full transcription on a float PCM buffer.
 *
 * @param ctx_ptr      Native context pointer from initContext
 * @param audio_data   16 kHz mono float samples (range [-1, 1])
 * @param language     BCP-47 language code ("en", "fr", etc.) or null for auto
 * @param translate    If true, translate to English regardless of source language
 */
JNIEXPORT void JNICALL
JNI_FUN(fullTranscribe)(JNIEnv *env, jobject thiz,
                     jlong ctx_ptr, jfloatArray audio_data,
                     jstring language_str, jboolean translate) {
    UNUSED(thiz);
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)ctx_ptr;
    if (!ctx) { LOGE("fullTranscribe: null context"); return; }

    jfloat *samples   = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    jsize   n_samples = (*env)->GetArrayLength(env, audio_data);

    struct whisper_full_params params =
        whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    params.n_threads        = preferred_threads();
    params.print_realtime   = false;
    params.print_progress   = false;
    params.print_timestamps = false;
    params.print_special    = false;
    params.translate        = (bool)translate;
    params.no_context       = true;      /* don't carry context between calls */
    params.single_segment   = true;       /* expect one short command, not a conversation */
    params.no_speech_thold  = 0.3f;      /* lower threshold — short commands were being suppressed at 0.6 */
    params.offset_ms        = 0;

    /* Language: null → auto-detect, set detect_language=true */
    const char *lang_chars = NULL;
    if (language_str != NULL) {
        lang_chars = (*env)->GetStringUTFChars(env, language_str, NULL);
        params.language        = lang_chars;
        params.detect_language = false;
    } else {
        params.language        = "auto";
        params.detect_language = true;
    }

    whisper_reset_timings(ctx);
    LOGI("Running whisper_full on %d samples (%d threads, lang=%s)",
         (int)n_samples, params.n_threads,
         params.language ? params.language : "auto");

    int result = whisper_full(ctx, params, samples, (int)n_samples);
    if (result != 0) { LOGE("whisper_full failed: %d", result); }

    if (lang_chars) (*env)->ReleaseStringUTFChars(env, language_str, lang_chars);
    (*env)->ReleaseFloatArrayElements(env, audio_data, samples, JNI_ABORT);
}

/* ─── Result accessors ────────────────────────────────────────────────────── */

JNIEXPORT jint JNICALL
JNI_FUN(getTextSegmentCount)(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    UNUSED(env); UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *)(intptr_t)ctx_ptr);
}

JNIEXPORT jstring JNICALL
JNI_FUN(getTextSegment)(JNIEnv *env, jobject thiz, jlong ctx_ptr, jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text(
        (struct whisper_context *)(intptr_t)ctx_ptr, index);
    return (*env)->NewStringUTF(env, text ? text : "");
}

/** Returns the auto-detected language string (e.g. "en", "fr") or "unknown". */
JNIEXPORT jstring JNICALL
JNI_FUN(getDetectedLanguage)(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    UNUSED(thiz);
    struct whisper_context *ctx = (struct whisper_context *)(intptr_t)ctx_ptr;
    int lang_id = whisper_full_lang_id(ctx);
    const char *lang = whisper_lang_str(lang_id);
    return (*env)->NewStringUTF(env, lang ? lang : "unknown");
}

/* ─── Diagnostics ─────────────────────────────────────────────────────────── */

JNIEXPORT jstring JNICALL
JNI_FUN(getSystemInfo)(JNIEnv *env, jobject thiz) {
    UNUSED(thiz);
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}
