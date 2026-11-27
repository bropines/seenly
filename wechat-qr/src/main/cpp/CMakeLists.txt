# WeChat's QR decoder (Tencent's, from the opencv_contrib module wechat_qrcode) with just the
# part of OpenCV it needs, core and imgproc, built from source for each ABI and linked statically
# into one small library. No neural networks: the decoder alone reads codes Java ZXing, zxing-cpp,
# BoofCV and OpenCV's own QR detector all miss (a code photographed off a monitor, with its moiré),
# as its binarizers try harder. The networks only find tiny codes in big pictures, at the cost of
# OpenCV's dnn module and protobuf, megabytes more.
cmake_minimum_required(VERSION 3.22.1)
project(wechatqr CXX)

include(ExternalProject)

set(OPENCV_VERSION 4.14.0)
set(OPENCV_SHA256 ee8fb9b30eb60850431b4656447080e3737b56e45719c92b67f245950609f86e)
set(CONTRIB_SHA256 4f17abd1bc7f88e19c3380c8de7cbf2d863aced5b5ee8d8934cc7902b67d42c9)

if(NOT QR_DOWNLOADS OR NOT QR_SOURCES)
    message(FATAL_ERROR "QR_DOWNLOADS and QR_SOURCES come from wechat-qr/build.gradle.kts")
endif()

# The sources are fetched once for every ABI and variant; Gradle may configure several at once.
file(MAKE_DIRECTORY ${QR_DOWNLOADS} ${QR_SOURCES})
file(LOCK ${QR_SOURCES}/.lock GUARD FILE TIMEOUT 1800)

function(fetch_sources name url sha256 pattern)
    set(done ${QR_SOURCES}/${name}-${OPENCV_VERSION}.done)
    if(EXISTS ${done})
        return()
    endif()
    set(archive ${QR_DOWNLOADS}/${name}-${OPENCV_VERSION}.tar.gz)
    if(EXISTS ${archive})
        file(SHA256 ${archive} have)
        if(NOT have STREQUAL sha256)
            file(REMOVE ${archive})
        endif()
    endif()
    if(NOT EXISTS ${archive})
        message(STATUS "Downloading ${url}")
        file(DOWNLOAD ${url} ${archive}.part EXPECTED_HASH SHA256=${sha256} STATUS status TLS_VERIFY ON)
        list(GET status 0 code)
        if(NOT code EQUAL 0)
            file(REMOVE ${archive}.part)
            message(FATAL_ERROR "Could not download ${url}: ${status}")
        endif()
        file(RENAME ${archive}.part ${archive})
    endif()
    message(STATUS "Unpacking ${archive}")
    file(ARCHIVE_EXTRACT INPUT ${archive} DESTINATION ${QR_SOURCES} PATTERNS ${pattern})
    file(TOUCH ${done})
endfunction()

fetch_sources(opencv
    https://github.com/opencv/opencv/archive/refs/tags/${OPENCV_VERSION}.tar.gz
    ${OPENCV_SHA256} opencv-${OPENCV_VERSION})
fetch_sources(opencv_contrib
    https://github.com/opencv/opencv_contrib/archive/refs/tags/${OPENCV_VERSION}.tar.gz
    ${CONTRIB_SHA256} opencv_contrib-${OPENCV_VERSION}/modules/wechat_qrcode)
file(LOCK ${QR_SOURCES}/.lock RELEASE)

set(OPENCV_SRC ${QR_SOURCES}/opencv-${OPENCV_VERSION})
set(WECHAT_SRC ${QR_SOURCES}/opencv_contrib-${OPENCV_VERSION}/modules/wechat_qrcode)

# OpenCV's core and imgproc as static libraries, built in Release whatever the variant is.
set(OPENCV_INSTALL ${CMAKE_BINARY_DIR}/opencv-install)
set(OPENCV_LIBS ${OPENCV_INSTALL}/sdk/native/staticlibs/${ANDROID_ABI})
set(OPENCV_3RDPARTY_LIBS ${OPENCV_INSTALL}/sdk/native/3rdparty/libs/${ANDROID_ABI})
set(EXTRA_BYPRODUCTS "")
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(EXTRA_BYPRODUCTS ${OPENCV_3RDPARTY_LIBS}/libcpufeatures.a)
endif()
set(SIZE_FLAGS "-ffunction-sections -fdata-sections -fvisibility=hidden -fvisibility-inlines-hidden")
ExternalProject_Add(opencv
    SOURCE_DIR ${OPENCV_SRC}
    BINARY_DIR ${CMAKE_BINARY_DIR}/opencv-build
    INSTALL_DIR ${OPENCV_INSTALL}
    CMAKE_ARGS
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_STL=c++_static
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_INSTALL_PREFIX=<INSTALL_DIR>
        -DCMAKE_C_FLAGS=${SIZE_FLAGS}
        -DCMAKE_CXX_FLAGS=${SIZE_FLAGS}
        -DBUILD_LIST=core,imgproc
        -DBUILD_SHARED_LIBS=OFF
        -DCPU_DISPATCH=
        -DBUILD_ANDROID_PROJECTS=OFF
        -DBUILD_ANDROID_EXAMPLES=OFF
        -DINSTALL_ANDROID_EXAMPLES=OFF
        -DBUILD_JAVA=OFF
        -DBUILD_FAT_JAVA_LIB=OFF
        -DBUILD_KOTLIN_EXTENSIONS=OFF
        -DBUILD_opencv_apps=OFF
        -DBUILD_TESTS=OFF
        -DBUILD_PERF_TESTS=OFF
        -DBUILD_EXAMPLES=OFF
        -DBUILD_DOCS=OFF
        -DOPENCV_GENERATE_PKGCONFIG=OFF
        -DCV_TRACE=OFF
        -DWITH_ITT=OFF
        -DWITH_IPP=OFF
        -DWITH_OPENCL=OFF
        -DWITH_CAROTENE=OFF
        -DWITH_KLEIDICV=OFF
        -DWITH_NDSRVP=OFF
        -DWITH_EIGEN=OFF
        -DWITH_LAPACK=OFF
        -DWITH_TBB=OFF
        -DWITH_OPENMP=OFF
        -DWITH_PROTOBUF=OFF
        -DWITH_FLATBUFFERS=OFF
        -DWITH_ANDROID_MEDIANDK=OFF
        -DWITH_ANDROID_NATIVE_CAMERA=OFF
        # No picture files are read or written here: none of the codecs.
        -DWITH_JPEG=OFF
        -DWITH_PNG=OFF
        -DWITH_TIFF=OFF
        -DWITH_WEBP=OFF
        -DWITH_OPENJPEG=OFF
        -DWITH_JASPER=OFF
        -DWITH_OPENEXR=OFF
        -DWITH_AVIF=OFF
        -DWITH_SPNG=OFF
        -DWITH_IMGCODEC_HDR=OFF
        -DWITH_IMGCODEC_SUNRASTER=OFF
        -DWITH_IMGCODEC_PXM=OFF
        -DWITH_IMGCODEC_PFM=OFF
        -DWITH_FFMPEG=OFF
        -DWITH_GSTREAMER=OFF
        -DWITH_V4L=OFF
    BUILD_BYPRODUCTS ${OPENCV_LIBS}/libopencv_core.a ${OPENCV_LIBS}/libopencv_imgproc.a ${EXTRA_BYPRODUCTS}
)

add_library(opencv_core STATIC IMPORTED)
set_target_properties(opencv_core PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS}/libopencv_core.a)
add_library(opencv_imgproc STATIC IMPORTED)
set_target_properties(opencv_imgproc PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS}/libopencv_imgproc.a)
add_dependencies(opencv_core opencv)
add_dependencies(opencv_imgproc opencv)

file(GLOB_RECURSE WECHAT_SOURCES CONFIGURE_DEPENDS ${WECHAT_SRC}/src/*.cpp)
add_library(wechatqr SHARED wechatqr.cpp ${WECHAT_SOURCES})
add_dependencies(wechatqr opencv)
target_include_directories(wechatqr PRIVATE
    # First: a stand-in for opencv2/dnn.hpp, for the networks this build leaves out.
    ${CMAKE_CURRENT_SOURCE_DIR}/nodnn
    ${OPENCV_INSTALL}/sdk/native/jni/include
    ${WECHAT_SRC}/include
    ${WECHAT_SRC}/src)
# NO_ICONV: Android has iconv only from API 28; texts in legacy charsets come through as they are.
target_compile_definitions(wechatqr PRIVATE NO_ICONV=1)
target_compile_options(wechatqr PRIVATE -O2 -ffunction-sections -fdata-sections -fvisibility=hidden
    -Wno-deprecated-declarations -Wno-unused-parameter)
target_link_options(wechatqr PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--icf=all -s)
target_link_libraries(wechatqr opencv_imgproc opencv_core jnigraphics log z dl m)
# 32-bit ARM: core asks the NDK's cpufeatures whether there is NEON.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(cpufeatures STATIC IMPORTED)
    set_target_properties(cpufeatures PROPERTIES IMPORTED_LOCATION ${OPENCV_3RDPARTY_LIBS}/libcpufeatures.a)
    target_link_libraries(wechatqr cpufeatures)
endif()
