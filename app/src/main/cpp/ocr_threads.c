#include <dlfcn.h>
#include <jni.h>

/*
 * PP-OCRv5 calls cv::setNumThreads(1) after the first detect. That flips
 * OpenCV 2.4 numThreads from 0 to 1, so the next cvtColor enters OpenMP.
 * OpenMP then aborts in __kmp_affinity_initialize and the process dies.
 * numThreads == 0 keeps cv::parallel_for_ on the serial path.
 */
JNIEXPORT void JNICALL
Java_tomato_gallery_helpers_PhotoOcr_disableOcrParallel(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    void *handle = dlopen("libppocrv5ncnn.so", RTLD_NOW | RTLD_NOLOAD);
    if (!handle) {
        handle = dlopen("libppocrv5ncnn.so", RTLD_NOW);
    }
    if (!handle) {
        return;
    }

    void (*set_num_threads)(int) = (void (*)(int)) dlsym(handle, "_ZN2cv13setNumThreadsEi");
    if (set_num_threads) {
        set_num_threads(0);
    }
}
