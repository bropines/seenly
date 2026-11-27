// JNI for io.github.bropines.wechatqr.WeChatQr: the codes in an Android bitmap, by WeChat's decoder.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <string>
#include <vector>

#include "opencv2/core.hpp"
#include "opencv2/imgproc.hpp"
#include "opencv2/wechat_qrcode.hpp"

namespace {

void logError(const char* what) { __android_log_print(ANDROID_LOG_WARN, "WeChatQr", "%s", what); }

}  // namespace

// Returns the codes' contents as raw bytes (their text encoding is the caller's to guess), or null
// when the bitmap could not be read. corners receives 8 floats per code, x and y of its four
// corners in bitmap pixels, for as many codes as it has room for.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_io_github_bropines_wechatqr_WeChatQr_decode(JNIEnv* env, jclass, jobject bitmap, jfloatArray corners) {
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        logError("not an ARGB_8888 bitmap");
        return nullptr;
    }
    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        logError("could not lock the bitmap");
        return nullptr;
    }
    std::vector<std::string> texts;
    std::vector<cv::Mat> points;
    try {
        cv::Mat gray;
        {
            cv::Mat rgba(static_cast<int>(info.height), static_cast<int>(info.width), CV_8UC4, pixels, info.stride);
            cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
        }
        AndroidBitmap_unlockPixels(env, bitmap);
        pixels = nullptr;
        cv::wechat_qrcode::WeChatQRCode reader;
        texts = reader.detectAndDecode(gray, points);
    } catch (const std::exception& e) {
        if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
        logError(e.what());
        return nullptr;
    } catch (...) {
        if (pixels) AndroidBitmap_unlockPixels(env, bitmap);
        logError("unknown failure");
        return nullptr;
    }

    jclass bytesClass = env->FindClass("[B");
    auto result = env->NewObjectArray(static_cast<jsize>(texts.size()), bytesClass, nullptr);
    for (size_t i = 0; i < texts.size(); i++) {
        auto bytes = env->NewByteArray(static_cast<jsize>(texts[i].size()));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(texts[i].size()), reinterpret_cast<const jbyte*>(texts[i].data()));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), bytes);
        env->DeleteLocalRef(bytes);
    }
    if (corners) {
        const jsize room = env->GetArrayLength(corners) / 8;
        for (size_t i = 0; i < points.size() && i < texts.size() && static_cast<jsize>(i) < room; i++) {
            cv::Mat p;
            points[i].convertTo(p, CV_32F);
            if (p.total() * p.channels() < 8) continue;
            p = p.reshape(1, 1);
            env->SetFloatArrayRegion(corners, static_cast<jsize>(i * 8), 8, p.ptr<float>());
        }
    }
    return result;
}
