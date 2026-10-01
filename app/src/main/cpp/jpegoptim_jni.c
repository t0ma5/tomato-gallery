/*
 * JNI entry for jpegoptim 1.5.6. quality < 0 is lossless Huffman;
 * 0–100 is jpegoptim -m (lossy max quality).
 * fatal() is patched to longjmp so a bad JPEG cannot kill the app.
 */
#include <jni.h>
#include <pthread.h>
#include <setjmp.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <android/log.h>

#include "jpegoptim.h"

jmp_buf jpegoptim_fatal_jmp;
int jpegoptim_fatal_jmp_ready = 0;

extern int quiet_mode;
extern int preserve_mode;
extern int all_progressive;
extern int save_extra;
extern int quality;
extern int force;
extern int dest;
extern int noaction;
extern int verbose_mode;
extern int target_size;
extern double threshold;
extern int strip_none;

static pthread_mutex_t jpegoptim_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT jlong JNICALL
Java_tomato_gallery_helpers_JpegOptim_optimizeNative(
        JNIEnv *env, jclass clazz, jstring jpath, jstring jtmpdir, jint jquality)
{
    const char *path = (*env)->GetStringUTFChars(env, jpath, NULL);
    const char *tmpdir = (*env)->GetStringUTFChars(env, jtmpdir, NULL);
    jlong result = -1;
    struct stat st;
    off_t before;
    double rate = 0.0;
    double saved = 0.0;
    int rc;

    (void) clazz;

    if (!path || !tmpdir) {
        goto done;
    }
    if (stat(path, &st) != 0) {
        goto done;
    }
    before = st.st_size;

    pthread_mutex_lock(&jpegoptim_lock);

    quiet_mode = 1;
    verbose_mode = 0;
    preserve_mode = 1;
    all_progressive = 0;
    save_extra = 1;
    strip_none = 1;
    quality = (int) jquality;
    if (quality > 100) {
        quality = 100;
    }
    force = 0;
    dest = 0;
    noaction = 0;
    target_size = 0;
    threshold = -1.0;

    if (setjmp(jpegoptim_fatal_jmp) != 0) {
        jpegoptim_fatal_jmp_ready = 0;
        pthread_mutex_unlock(&jpegoptim_lock);
        __android_log_print(ANDROID_LOG_ERROR, "jpegoptim", "fatal during optimize of %s", path);
        result = -3;
        goto done;
    }
    jpegoptim_fatal_jmp_ready = 1;

    rc = optimize(stderr, path, path, tmpdir, &st, &rate, &saved);
    jpegoptim_fatal_jmp_ready = 0;
    pthread_mutex_unlock(&jpegoptim_lock);

    if (rc == 3) {
        __android_log_print(ANDROID_LOG_ERROR, "jpegoptim", "optimize fatal rc=3 for %s", path);
        result = -3;
        goto done;
    }
    if (rc != 0) {
        __android_log_print(ANDROID_LOG_WARN, "jpegoptim", "optimize failed rc=%d for %s", rc, path);
        result = -1;
        goto done;
    }
    if (stat(path, &st) != 0) {
        result = -2;
        goto done;
    }
    result = (jlong) (before - st.st_size);
    if (result < 0) {
        result = 0;
    }

done:
    if (path) {
        (*env)->ReleaseStringUTFChars(env, jpath, path);
    }
    if (tmpdir) {
        (*env)->ReleaseStringUTFChars(env, jtmpdir, tmpdir);
    }
    return result;
}
