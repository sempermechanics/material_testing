#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include "IndicVisionCore.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "IndicVisionJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Helper to convert and pre-filter images like DICe
cv::Mat bytesToMat(JNIEnv* env, jbyteArray bytes) {
    jsize len = env->GetArrayLength(bytes);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(buf));
    std::vector<unsigned char> data(buf, buf + len);
    cv::Mat img = cv::imdecode(data, cv::IMREAD_GRAYSCALE);
    delete[] buf;

    if (!img.empty()) {
        // Gaussian Pre-filter to prevent aliasing
        cv::GaussianBlur(img, img, cv::Size(0, 0), 0.8);
    }
    return img;
}

extern "C" {

// 2. GET PREVIEW (Returns a Bitmap for the UI)
JNIEXPORT jobject JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_getPreviewFromBytes(
        JNIEnv* env, jobject, jbyteArray fileData, jint targetWidth) {

    jsize len = env->GetArrayLength(fileData);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(fileData, 0, len, reinterpret_cast<jbyte*>(buf));
    std::vector<unsigned char> data(buf, buf + len);
    cv::Mat fullImg = cv::imdecode(data, cv::IMREAD_COLOR);
    delete[] buf;

    if (fullImg.empty()) return nullptr;

    float ratio = (float)targetWidth / fullImg.cols;
    int targetHeight = (int)(fullImg.rows * ratio);
    cv::Mat resizedImg;
    cv::resize(fullImg, resizedImg, cv::Size(targetWidth, targetHeight));

    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapCls, "createBitmap",
                                                          "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configCls, argb8888Field);
    jobject jBitmap = env->CallStaticObjectMethod(bitmapCls, createBitmapMethod, targetWidth, targetHeight, config);

    void* pixels;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) < 0) return nullptr;
    cv::cvtColor(resizedImg, resizedImg, cv::COLOR_BGR2RGBA);
    memcpy(pixels, resizedImg.data, resizedImg.total() * resizedImg.elemSize());
    AndroidBitmap_unlockPixels(env, jBitmap);
    return jBitmap;
}

// 3. ANALYZE RAW BYTES (Single Point - 6-DOF Accuracy Mode)
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_analyzeRawBytes(
        JNIEnv* env, jobject,
        jbyteArray refBytes, jbyteArray defBytes,
        jint roiX, jint roiY, jint subsetSize,
        jint originalWidth, jint originalHeight,jint interpId) {

    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);

    if (refMat.empty() || defMat.empty()) {
        jfloatArray fail = env->NewFloatArray(5);
        jfloat temp[] = {0,0,0,0,1}; // Status 1 = Fail
        env->SetFloatArrayRegion(fail, 0, 5, temp);
        return fail;
    }
    // Add this to the very first line of Java_com_rafad_indicvisiondic_IndicVisionNativeLib_analyzeRawBytes
    LOGD("JNI_CALL: analyzeRawBytes entered. Subset: %d, ROI: %d, %d", subsetSize, roiX, roiY);

    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);

    // Set Interpolator
    auto type = (interpId == 1) ? IndicVision::INTERP_QUINTIC : IndicVision::INTERP_BICUBIC;
    refImg.set_interpolator(type);
    defImg.set_interpolator(type);

    auto engine = std::make_unique<IndicVision::Engine>();
    engine->set_reference(refImg, roiX, roiY, subsetSize);

    // Initial guess 0,0 for single point analysis
    IndicVision::AnalysisResult result = engine->calculate_deformation(defImg, 0.0, 0.0);

    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5];
    temp[0] = static_cast<jfloat>(result.u);
    temp[1] = static_cast<jfloat>(result.v);
    temp[2] = static_cast<jfloat>(0.0);       // Theta placeholder
    temp[3] = static_cast<jfloat>(result.ux); // Strain Exx (du/dx)
    temp[4] = static_cast<jfloat>(result.status);

    env->SetFloatArrayRegion(output, 0, 5, temp);
    return output;
}

// 4. GET IMAGE DIMENSIONS
JNIEXPORT jintArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_getImageDimensions(
        JNIEnv* env, jobject, jbyteArray fileData) {
    jsize len = env->GetArrayLength(fileData);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(fileData, 0, len, reinterpret_cast<jbyte*>(buf));
    std::vector<unsigned char> data(buf, buf + len);
    cv::Mat img = cv::imdecode(data, cv::IMREAD_UNCHANGED);
    delete[] buf;

    jintArray result = env->NewIntArray(2);
    if (img.empty()) {
        jint temp[] = {0, 0};
        env->SetIntArrayRegion(result, 0, 2, temp);
        return result;
    }
    jint temp[] = {img.cols, img.rows};
    env->SetIntArrayRegion(result, 0, 2, temp);
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeLineProfile(
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jint startX, jint endX, jint y, jint step, jint subsetSize,jint interpId, jobject callbackObj) {

    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);
    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);

    // Set Interpolator
    auto type = (interpId == 1) ? IndicVision::INTERP_QUINTIC : IndicVision::INTERP_BICUBIC;
    refImg.set_interpolator(type);
    defImg.set_interpolator(type);

    auto engine = std::make_unique<IndicVision::Engine>();
    std::vector<float> results;

    jclass callbackClass = env->GetObjectClass(callbackObj);
    jmethodID methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");

    int totalPoints = (endX - startX) / step;
    int currentPoint = 0;

    // --- DICe SEED PROPAGATION ---
    double lastU = 0.0;
    double lastV = 0.0;

    for (int x = startX; x < endX; x += step) {
        engine->set_reference(refImg, x, y, subsetSize);

        // Pass the last known good displacement as the guess for the next point
        IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, lastU, lastV);

        if (res.status == 0) {
            results.push_back((float)res.u);
            lastU = res.u; // Update seed for next neighbor
            lastV = res.v;
        } else {
            results.push_back(-999.0f);
            // NOTE: We do NOT reset lastU/lastV to 0.
            // We use the last valid position to try and skip the bad region.
        }

        currentPoint++;
        env->CallVoidMethod(callbackObj, methodId, (jint)((currentPoint * 100) / totalPoints));
    }

    jfloatArray output = env->NewFloatArray(results.size());
    env->SetFloatArrayRegion(output, 0, results.size(), results.data());
    return output;
}

} // extern C