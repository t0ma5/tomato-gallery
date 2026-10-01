cmake_minimum_required(VERSION 3.22.1)
project(jpegoptim-jni C)

set(CMAKE_C_STANDARD 99)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)

set(ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(ENABLE_STATIC ON CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG OFF CACHE BOOL "" FORCE)
set(WITH_JAVA OFF CACHE BOOL "" FORCE)
set(PNG_SUPPORTED OFF CACHE BOOL "" FORCE)
if(ANDROID_ABI MATCHES "^x86")
    set(WITH_SIMD OFF CACHE BOOL "" FORCE)
endif()

include(FetchContent)
FetchContent_Declare(
    mozjpeg
    GIT_REPOSITORY https://github.com/mozilla/mozjpeg.git
    GIT_TAG a2d2907ff023227e80c1e4efa809812410275a12
)
FetchContent_MakeAvailable(mozjpeg)

add_library(jpegoptim-jni SHARED
    jpegoptim_jni.c
    jpegrotate_jni.c
    ${mozjpeg_SOURCE_DIR}/transupp.c
    jpegoptim/jpegoptim.c
    jpegoptim/jpegdest.c
    jpegoptim/jpegsrc.c
    jpegoptim/jpegmarker.c
    jpegoptim/misc.c
)

target_include_directories(jpegoptim-jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/jpegoptim
    ${mozjpeg_SOURCE_DIR}
    ${mozjpeg_BINARY_DIR}
)

target_compile_definitions(jpegoptim-jni PRIVATE
    BUILD_FOR_OSS_FUZZ=1
    JPEGOPTIM_ANDROID_JNI=1
    HAVE_UNISTD_H=1
    HAVE_STRING_H=1
    HAVE_LIBGEN_H=1
    HAVE_MATH_H=1
    HAVE_FCNTL_H=1
    HAVE_SYS_STAT_H=1
    HAVE_SYS_TYPES_H=1
    HAVE_GETOPT_H=1
    HAVE_GETOPT_LONG=1
    HAVE_MKSTEMPS=1
    HAVE_LABS=1
    HAVE_FILENO=1
    HAVE_JINT_DC_SCAN_OPT_MODE=1
    HOST_TYPE="Android"
)

find_library(log-lib log)
target_link_options(jpegoptim-jni PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(jpegoptim-jni
    jpeg-static
    ${log-lib}
)

add_library(ocr-threads SHARED ocr_threads.c)
target_link_options(ocr-threads PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(ocr-threads ${log-lib})
