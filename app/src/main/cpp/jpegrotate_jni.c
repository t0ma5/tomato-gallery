/*
 * Lossless JPEG rotation (jpegtran-style) for the gallery.
 * Rotates DCT coefficients via libjpeg's transupp, so pixels are
 * never decoded and re-encoded. Markers (EXIF/XMP/ICC) are copied.
 * Returns 0 on success; negative on any failure. On failure the
 * destination file is removed.
 */
#include <jni.h>
#include <setjmp.h>
#include <stdio.h>
#include <string.h>

#include "jpeglib.h"
#include "jerror.h"
#include "transupp.h"

struct rot_error_mgr {
    struct jpeg_error_mgr pub;
    jmp_buf setjmp_buffer;
};

static void rot_error_exit(j_common_ptr cinfo)
{
    struct rot_error_mgr *err = (struct rot_error_mgr *) cinfo->err;
    longjmp(err->setjmp_buffer, 1);
}

JNIEXPORT jint JNICALL
Java_tomato_gallery_helpers_JpegTransform_rotateNative(
        JNIEnv *env, jclass clazz, jstring jsrc, jstring jdest, jint jdegrees)
{
    const char *src = (*env)->GetStringUTFChars(env, jsrc, NULL);
    const char *dst = (*env)->GetStringUTFChars(env, jdest, NULL);
    FILE *fin = NULL;
    FILE *fout = NULL;
    struct jpeg_decompress_struct srcinfo;
    struct jpeg_compress_struct dstinfo;
    struct rot_error_mgr jerr;
    jpeg_transform_info info;
    jvirt_barray_ptr *coefs;
    jvirt_barray_ptr *dstcoefs;
    jint result = -1;
    int created = 0;

    (void) clazz;

    if (!src || !dst) {
        goto done;
    }

    memset(&info, 0, sizeof(info));
    switch (jdegrees) {
        case 90: info.transform = JXFORM_ROT_90; break;
        case 180: info.transform = JXFORM_ROT_180; break;
        case 270: info.transform = JXFORM_ROT_270; break;
        default: goto done;
    }
    info.trim = FALSE;
    info.perfect = FALSE;
    info.force_grayscale = FALSE;
    info.crop = FALSE;

    fin = fopen(src, "rb");
    if (!fin) {
        goto done;
    }
    fout = fopen(dst, "wb");
    if (!fout) {
        goto done;
    }

    srcinfo.err = jpeg_std_error(&jerr.pub);
    jerr.pub.error_exit = rot_error_exit;
    dstinfo.err = &jerr.pub;

    if (setjmp(jerr.setjmp_buffer)) {
        if (created) {
            jpeg_destroy_compress(&dstinfo);
            jpeg_destroy_decompress(&srcinfo);
        }
        result = -2;
        goto done;
    }

    jpeg_create_decompress(&srcinfo);
    jpeg_create_compress(&dstinfo);
    created = 1;

    jpeg_stdio_src(&srcinfo, fin);
    jcopy_markers_setup(&srcinfo, JCOPYOPT_ALL);
    (void) jpeg_read_header(&srcinfo, TRUE);

    if (!jtransform_request_workspace(&srcinfo, &info)) {
        jpeg_destroy_compress(&dstinfo);
        jpeg_destroy_decompress(&srcinfo);
        created = 0;
        result = -3;
        goto done;
    }

    coefs = jpeg_read_coefficients(&srcinfo);
    jpeg_copy_critical_parameters(&srcinfo, &dstinfo);
    dstcoefs = jtransform_adjust_parameters(&srcinfo, &dstinfo, coefs, &info);
    jpeg_stdio_dest(&dstinfo, fout);
    dstinfo.optimize_coding = TRUE;
    jpeg_write_coefficients(&dstinfo, dstcoefs);
    jcopy_markers_execute(&srcinfo, &dstinfo, JCOPYOPT_ALL);
    jtransform_execute_transformation(&srcinfo, &dstinfo, coefs, &info);
    jpeg_finish_compress(&dstinfo);
    jpeg_finish_decompress(&srcinfo);
    jpeg_destroy_compress(&dstinfo);
    jpeg_destroy_decompress(&srcinfo);
    created = 0;
    result = 0;

done:
    if (fin) {
        fclose(fin);
    }
    if (fout) {
        fclose(fout);
    }
    if (result != 0 && dst) {
        remove(dst);
    }
    if (src) {
        (*env)->ReleaseStringUTFChars(env, jsrc, src);
    }
    if (dst) {
        (*env)->ReleaseStringUTFChars(env, jdest, dst);
    }
    return result;
}
