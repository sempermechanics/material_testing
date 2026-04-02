#include <android/bitmap.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <exception>
#include <fstream>
#include <jni.h>
#include <memory>
#include <mutex>
#include <omp.h>
#include <opencv2/calib3d.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/opencv.hpp>
#include <queue>
#include <string>
#include <thread>
#include <vector>

// --- ARCHITECTURE HEADERS ---
#include "../core/OptimizationEngine.h"
#include "../postprocessing/StrainCalculator.h"
#include "../preprocessing/ImageProcessor.h"
#include "../preprocessing/SubsetPrecomputer.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "IndicVisionJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==========================================
// ⏱️ RAII PROFILING INFRASTRUCTURE
// ==========================================
struct ThreadStats {
    double icgn_time_ms = 0.0;
    int icgn_iters = 0;
    double simplex_time_ms = 0.0;
    int simplex_iters = 0;
    double hessian_time_ms = 0.0;
    double queue_wait_time_ms = 0.0;
    int points_solved = 0;

    // Detailed Simplex Telemetry
    int simplex_calls = 0;
    int simplex_saved = 0;
    int simplex_dead = 0;

    // Track WHY it went to Simplex
    int simplex_from_crash = 0;   // Failed in < 20 iters (Bad guess / out of bounds)
    int simplex_from_timeout = 0; // Failed at exactly 20 iters (Non-convergence)
};

struct EngineStatFlusher {
    IndicVision::OptimizationEngine &engine;
    ThreadStats &bucket;
    int &local_points;
    double &local_hessian;
    double &local_wait;

    EngineStatFlusher(IndicVision::OptimizationEngine &e, ThreadStats &b, int &lp, double &lh, double &lw)
            : engine(e), bucket(b), local_points(lp), local_hessian(lh), local_wait(lw) {}

    ~EngineStatFlusher() {
        bucket.icgn_time_ms += engine.time_icgn_ms;
        //bucket.icgn_iters += engine.count_icgn;
        bucket.simplex_time_ms += engine.time_simplex_ms;
        bucket.simplex_iters += engine.count_simplex;
        bucket.points_solved += local_points;
        bucket.hessian_time_ms += local_hessian;
        bucket.queue_wait_time_ms += local_wait;
    }
};

struct ScopedTimer {
    std::chrono::time_point<std::chrono::high_resolution_clock> start;
    double &out_ms;
    ScopedTimer(double &out) : out_ms(out) {
        start = std::chrono::high_resolution_clock::now();
    }
    ~ScopedTimer() {
        out_ms = std::chrono::duration<double, std::milli>(
                std::chrono::high_resolution_clock::now() - start).count();
    }
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGD("IndicVision Native Library Loaded.");
    // 🚀 FIXED: Prevent OpenCV from spawning zombie thread pools that fight OpenMP
    cv::setNumThreads(1);
    return JNI_VERSION_1_6;
}
static IndicVision::Image *g_refImg = nullptr;
static int g_refWidth = 0;
static int g_refHeight = 0;
static std::mutex jni_engine_mutex;
static std::string g_debugDir = "";
// 🚀 ADDED: Bulletproof AKAZE Reference Caching
static std::vector<cv::KeyPoint> g_cached_ref_kp;
static cv::Mat g_cached_ref_desc;
// 🚀 PRIORITY 2: Track the active scale so we don't invalidate the cache unnecessarily between frames
static double g_current_akaze_scale = 0.25;

cv::Mat bytesToMat(JNIEnv *env, jbyteArray bytes, int expectedWidth = 0, int expectedHeight = 0) {
    if (bytes == nullptr) return cv::Mat();
    jsize len = env->GetArrayLength(bytes);
    jbyte *buf = env->GetByteArrayElements(bytes, nullptr);
    cv::Mat img;
    if (expectedWidth > 0 && expectedHeight > 0 && len == expectedWidth * expectedHeight * 4) {
        cv::Mat rawData(expectedHeight, expectedWidth, CV_8UC4, (void *)buf);
        cv::cvtColor(rawData, img, cv::COLOR_RGBA2GRAY);
    } else {
        cv::Mat rawData(1, len, CV_8UC1, (void *)buf);
        img = cv::imdecode(rawData, cv::IMREAD_GRAYSCALE);
    }
    cv::Mat result = img.clone();
    env->ReleaseByteArrayElements(bytes, buf, JNI_ABORT);
    return result;
}

void drawOutlinedText(cv::Mat &img, const std::string &text, cv::Point pt, double scale = 0.5) {
    cv::putText(img, text, pt, cv::FONT_HERSHEY_SIMPLEX, scale, cv::Scalar(0, 0, 0), 3, cv::LINE_AA);
    cv::putText(img, text, pt, cv::FONT_HERSHEY_SIMPLEX, scale, cv::Scalar(255, 255, 255), 1, cv::LINE_AA);
}

// ==========================================
// 🚀 PHASE 2: AKAZE RANSAC EXTRACTION
// ==========================================
bool extractAkazeFeatures(cv::Mat &ref, cv::Mat &def, cv::Mat &roiMask, double scale, std::vector<cv::Point2f> &out_ref_pts,
                          std::vector<cv::Point2f> &out_def_pts, float &out_bounding_box_area_ratio,
                          double &out_akaze_ms, double &out_ransac_ms,
                          std::vector<cv::KeyPoint>& cached_kp, cv::Mat& cached_desc,
                          int offsetX, int offsetY, // 🚀 ADDED OFFSETS HERE
                          const std::string &debugDir = "") {

    auto t_start_akaze = std::chrono::high_resolution_clock::now();
    cv::Mat smallRef, smallDef;
    cv::resize(ref, smallRef, cv::Size(), scale, scale, cv::INTER_AREA);
    cv::resize(def, smallDef, cv::Size(), scale, scale, cv::INTER_AREA);

    auto detector = cv::AKAZE::create();
    std::vector<cv::KeyPoint> kp2;
    cv::Mat desc2;

    if (cached_kp.empty() || cached_desc.empty()) {
        detector->detectAndCompute(smallRef, cv::noArray(), cached_kp, cached_desc);
    }
    detector->detectAndCompute(smallDef, cv::noArray(), kp2, desc2);

    if (cached_kp.empty() || kp2.empty()) {
        out_akaze_ms = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_start_akaze).count();
        return false;
    }

    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<std::vector<cv::DMatch>> matches;
    matcher.knnMatch(cached_desc, desc2, matches, 2);

    std::vector<cv::Point2f> p1, p2;
    std::vector<cv::DMatch> good_matches;
    for (auto &m : matches) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            float full_x = cached_kp[m[0].queryIdx].pt.x / scale;
            float full_y = cached_kp[m[0].queryIdx].pt.y / scale;

            bool is_valid = true;
            if (!roiMask.empty()) {
                // 🚀 FIX: Apply offsets to check the correct global mask location!
                int mask_x = (int)(full_x + offsetX);
                int mask_y = (int)(full_y + offsetY);
                if (mask_x >= 0 && mask_x < roiMask.cols && mask_y >= 0 && mask_y < roiMask.rows) {
                    if (roiMask.at<uchar>(mask_y, mask_x) < 128) {
                        is_valid = false;
                    }
                } else {
                    is_valid = false;
                }
            }

            if (is_valid) {
                p1.push_back(cached_kp[m[0].queryIdx].pt);
                p2.push_back(kp2[m[0].trainIdx].pt);
                good_matches.push_back(m[0]);
            }
        }
    }

    auto t_end_akaze = std::chrono::high_resolution_clock::now();
    out_akaze_ms = std::chrono::duration<double, std::milli>(t_end_akaze - t_start_akaze).count();

    if (p1.size() < 10) return false;

    auto t_start_ransac = std::chrono::high_resolution_clock::now();
    std::vector<uchar> inlier_mask;
    cv::findHomography(p1, p2, cv::RANSAC, 3.0, inlier_mask);

    out_ref_pts.clear(); out_def_pts.clear();
    for (size_t i = 0; i < inlier_mask.size(); ++i) {
        if (inlier_mask[i]) {
            out_ref_pts.push_back(cv::Point2f(p1[i].x / scale, p1[i].y / scale));
            out_def_pts.push_back(cv::Point2f(p2[i].x / scale, p2[i].y / scale));
        }
    }

    if (out_ref_pts.size() >= 3) {
        std::vector<cv::Point2f> hull;
        cv::convexHull(out_ref_pts, hull);
        float hull_area = (float)cv::contourArea(hull);
        float total_area = (float)(ref.cols * ref.rows);
        out_bounding_box_area_ratio = hull_area / total_area;
    } else {
        out_bounding_box_area_ratio = 0.0f;
    }

    out_ransac_ms = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_start_ransac).count();
    return out_ref_pts.size() >= 10;
}

extern "C" {

JNIEXPORT void JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_setDebugOutputDir(JNIEnv *env, jobject, jstring debugDir) {
    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);
    if (debugDir == nullptr) { g_debugDir = ""; return; }
    const char *dir = env->GetStringUTFChars(debugDir, nullptr);
    g_debugDir = std::string(dir);
    env->ReleaseStringUTFChars(debugDir, dir);
}

JNIEXPORT jobject JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_getPreviewFromBytes(
        JNIEnv *env, jobject, jbyteArray fileData, jint targetWidth) {
    jsize len = env->GetArrayLength(fileData);
    jbyte *buf = env->GetByteArrayElements(fileData, nullptr);
    cv::Mat rawData(1, len, CV_8UC1, (void *)buf);
    cv::Mat fullImg = cv::imdecode(rawData, cv::IMREAD_COLOR);
    env->ReleaseByteArrayElements(fileData, buf, JNI_ABORT);
    if (fullImg.empty()) return nullptr;

    float ratio = (float)targetWidth / fullImg.cols;
    int targetHeight = (int)(fullImg.rows * ratio);
    cv::Mat resizedImg;
    cv::resize(fullImg, resizedImg, cv::Size(targetWidth, targetHeight));

    jclass bitmapCls = env->FindClass("android/graphics/Bitmap");
    jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapCls, "createBitmap", "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jclass configCls = env->FindClass("android/graphics/Bitmap$Config");
    jfieldID argb8888Field = env->GetStaticFieldID(configCls, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject config = env->GetStaticObjectField(configCls, argb8888Field);
    jobject jBitmap = env->CallStaticObjectMethod(bitmapCls, createBitmapMethod, targetWidth, targetHeight, config);

    void *pixels;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) < 0) return nullptr;
    cv::cvtColor(resizedImg, resizedImg, cv::COLOR_BGR2RGBA);
    memcpy(pixels, resizedImg.data, resizedImg.total() * resizedImg.elemSize());
    AndroidBitmap_unlockPixels(env, jBitmap);
    return jBitmap;
}

JNIEXPORT jintArray JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_getImageDimensions(
        JNIEnv *env, jobject, jbyteArray fileData) {
    jsize len = env->GetArrayLength(fileData);
    jbyte *buf = env->GetByteArrayElements(fileData, nullptr);
    cv::Mat rawData(1, len, CV_8UC1, (void *)buf);
    cv::Mat img = cv::imdecode(rawData, cv::IMREAD_UNCHANGED);
    env->ReleaseByteArrayElements(fileData, buf, JNI_ABORT);
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

JNIEXPORT void JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_initializeReference(JNIEnv *env, jobject, jbyteArray refBytes, jint width, jint height, jboolean applyBlur) {
    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);
    if (g_refImg != nullptr) { delete g_refImg; g_refImg = nullptr; }
    if (refBytes == nullptr) return;
    cv::Mat refMat = bytesToMat(env, refBytes, width, height);
    if (refMat.empty()) return;
    // 🚀 DELETED cv::GaussianBlur. We do not want generic OpenCV blurring.
    g_refWidth = refMat.cols; g_refHeight = refMat.rows;
    g_refImg = new IndicVision::Image(g_refWidth, g_refHeight, refMat.data);
    // 🚀 PASSED the UI toggle down to the C++ engine
    g_refImg->prepare_data(applyBlur);

    // 🚀 FIX 0d: Clear the AKAZE cache whenever a NEW reference image is loaded
    g_cached_ref_kp.clear();
    g_cached_ref_desc.release();
    g_current_akaze_scale = 0.25; // 🚀 PRIORITY 2: Reset the pyramid for the new specimen
}

JNIEXPORT jfloatArray JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_analyzeRawBytes(
        JNIEnv *env, jobject, jbyteArray refBytes, jbyteArray defBytes, jint roiX,
        jint roiY, jint subsetSize, jint originalWidth, jint originalHeight) {
    cv::Mat refMat = bytesToMat(env, refBytes, originalWidth, originalHeight);
    cv::Mat defMat = bytesToMat(env, defBytes, originalWidth, originalHeight);
    if (refMat.empty() || defMat.empty()) {
        jfloatArray fail = env->NewFloatArray(5);
        jfloat temp[] = {0.0f, 0.0f, 0.0f, 0.0f, 1.0f};
        env->SetFloatArrayRegion(fail, 0, 5, temp);
        return fail;
    }
    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    refImg.prepare_data(false); // Default to false for raw byte analysis
    defImg.prepare_data(false);
    IndicVision::SubsetData subset;
    IndicVision::SubsetPrecomputer::precompute_subset(subset, refImg, roiX, roiY, subsetSize);
    IndicVision::OptimizationEngine engine;
    IndicVision::AnalysisResult res =
            engine.calculate_deformation(subset, defImg, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
                                         0.0f, IndicVision::INIT_AUTO_SEARCH);
    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5] = {res.u, res.v, 0.0f, res.ux, (jfloat)res.status};
    env->SetFloatArrayRegion(output, 0, 5, temp);
    return output;
}

// ==========================================
// 🚀 ROUTING ENGINE - HYBRID CORE ONLY
// ==========================================
JNIEXPORT jint JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeFullFieldDirect(
        JNIEnv *env, jobject, jbyteArray refBytes, jbyteArray defBytes, jbyteArray maskBytes, jint rectX, jint rectY, jint rectWidth,
        jint rectHeight, jint step, jint subsetSize, jint strainWindow, jboolean useDelaunay, jboolean useFallback, jboolean useRGDIC,
        jboolean applyGaussianBlur, jboolean useNlvcStrain, jobject outputBuffer, jobject callbackObj, jfloatArray out_metrics) {

    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);
    std::string local_debug_dir = g_debugDir; g_debugDir = "";

    auto t_total_start = std::chrono::high_resolution_clock::now();
    double time_img_prep = 0, time_akaze = 0, time_ransac = 0, time_delaunay = 0, time_contour_assign = 0, time_extrapolate = 0, time_smoothing = 0;
    double time_prepass = 0, time_pathA = 0, time_pathB = 0, time_strain = 0;

    // 🚀 PRIORITY 3: Return -3 for memory/init errors
    if (env == nullptr || defBytes == nullptr || outputBuffer == nullptr || g_refImg == nullptr) return -3;
    float *output_ptr = (float *)env->GetDirectBufferAddress(outputBuffer);
    if (!output_ptr) return -3;

    static int s_frame_count = 0; s_frame_count++;
    LOGD("=== FRAME %d computeFullFieldDirect (HYBRID CORE) START ===", s_frame_count);

    auto t_prep_start = std::chrono::high_resolution_clock::now();
    cv::Mat defMat = bytesToMat(env, defBytes, g_refWidth, g_refHeight);
    if (defMat.empty()) return -3;

    cv::Mat roiMask;
    if (maskBytes != nullptr && env->GetArrayLength(maskBytes) > 0) {
        roiMask = bytesToMat(env, maskBytes);
        if (!roiMask.empty() && (roiMask.cols != g_refWidth || roiMask.rows != g_refHeight)) {
            cv::resize(roiMask, roiMask, cv::Size(g_refWidth, g_refHeight), 0, 0, cv::INTER_NEAREST);
        }
    }

    // 🚀 DELETED cv::GaussianBlur.
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    // 🚀 PASSED the UI toggle down to the C++ engine
    defImg.prepare_data(applyGaussianBlur);
    time_img_prep = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_prep_start).count();

    int safe_cores = std::max(1, (int)std::thread::hardware_concurrency());

    // Toggle Simplex ON or OFF
    bool ALLOW_SIMPLEX_RESCUE = true;

    std::vector<cv::Point2f> akaze_ref_pts;
    std::vector<cv::Point2f> akaze_def_pts;
    float inlier_bb_area_ratio = 0.0f;
    bool has_good_akaze = false; // 🚀 RESTORED

    // 🚀 IMPLEMENTATION: Priority 6 & 4 States
    enum class MeshQuality { NONE, SPARSE, FULL };
    MeshQuality mesh_quality = MeshQuality::NONE;
    float globalU = 0.0f, globalV = 0.0f;

    // Declare Path C variables here so they exist for the entire function
    bool execute_path_c = false;
    int path_c_seed_x = -1;
    int path_c_seed_y = -1;

    // 🚀 ADAPTIVE PADDING: Removed hardcoded padding from outside the loop

    if (rectWidth > 32 && rectHeight > 32) {
        try {
            cv::Mat refMat = bytesToMat(env, refBytes, g_refWidth, g_refHeight);
            if (!refMat.empty()) {

                // 🚀 PRIORITY 2: ADAPTIVE SCALE PYRAMID
                // Build the scale list based on the globally established baseline for this specimen
                std::vector<double> scales_to_try;
                if (g_current_akaze_scale <= 0.25) scales_to_try = {0.25, 0.5, 1.0};
                else if (g_current_akaze_scale <= 0.5) scales_to_try = {0.5, 1.0};
                else scales_to_try = {1.0};

                cv::Rect winning_padded_roi; // 🚀 Keep track of the offsets used for the winning scale

                for (double current_scale : scales_to_try) {

                    int adaptive_padding = (int)(40.0 / current_scale);
                    cv::Rect padded_roi(rectX - adaptive_padding, rectY - adaptive_padding, rectWidth + 2 * adaptive_padding, rectHeight + 2 * adaptive_padding);
                    padded_roi = padded_roi & cv::Rect(0, 0, g_refWidth, g_refHeight);

                    // 🚀 FIX: Must use padded_roi here, not winning_padded_roi!
                    cv::Mat refROI = refMat(padded_roi);
                    cv::Mat defROI = defMat(padded_roi);

                    if (current_scale != g_current_akaze_scale) {
                        g_cached_ref_kp.clear();
                        g_cached_ref_desc.release();
                        g_current_akaze_scale = current_scale;
                    }

                    double iter_akaze = 0, iter_ransac = 0;
                    // 🚀 FIX: Pass padded_roi.x and padded_roi.y into the function!
                    bool success = extractAkazeFeatures(refROI, defROI, roiMask, current_scale, akaze_ref_pts, akaze_def_pts, inlier_bb_area_ratio, iter_akaze, iter_ransac, g_cached_ref_kp, g_cached_ref_desc, padded_roi.x, padded_roi.y, local_debug_dir);
                    time_akaze += iter_akaze;
                    time_ransac += iter_ransac;

                    // 🚀 THE FIX: Separate Scale Escalation from Quality Routing
                    if (success && akaze_ref_pts.size() >= 25) {
                        // We found enough features! Zooming in further won't change the physical coverage area.
                        has_good_akaze = true;
                        winning_padded_roi = padded_roi; // 🚀 SAVE OFFSETS

                        // Now, evaluate the structural integrity of the mesh
                        if (inlier_bb_area_ratio >= 0.30f) {
                            mesh_quality = MeshQuality::FULL;
                            LOGD("ROUTING: FULL Mesh at scale %.2fx (Pts: %d, Cov: %.2f)", current_scale, (int)akaze_ref_pts.size(), inlier_bb_area_ratio);
                        } else if (inlier_bb_area_ratio >= 0.05f) {
                            mesh_quality = MeshQuality::SPARSE;
                            LOGD("ROUTING: SPARSE Mesh at scale %.2fx (Pts: %d, Cov: %.2f)", current_scale, (int)akaze_ref_pts.size(), inlier_bb_area_ratio);
                        } else {
                            mesh_quality = MeshQuality::NONE; // Too clustered, drop to Path C
                            LOGD("ROUTING: Features too clustered (Cov: %.2f). Forcing Path C.", inlier_bb_area_ratio);
                        }

                        break; // 🚀 CRITICAL: Stop escalating the scale! We have enough points.

                    } else {
                        LOGD("ROUTING: AKAZE Insufficient at scale %.2fx (Points: %d, Cov: %.2f). Escalating...", current_scale, (int)akaze_ref_pts.size(), inlier_bb_area_ratio);
                    }
                } // <--- END OF SCALE LOOP

                if (has_good_akaze) {
                    if (!local_debug_dir.empty()) {
                        cv::Mat akazeRefDraw, akazeDefDraw;
                        cv::Mat refROI = refMat(winning_padded_roi); // 🚀 Re-extract just for drawing
                        cv::Mat defROI = defMat(winning_padded_roi);
                        cv::cvtColor(refROI, akazeRefDraw, cv::COLOR_GRAY2BGR);
                        cv::cvtColor(defROI, akazeDefDraw, cv::COLOR_GRAY2BGR);

                        for(size_t i = 0; i < akaze_ref_pts.size(); ++i) {
                            cv::circle(akazeRefDraw, akaze_ref_pts[i], 3, cv::Scalar(0, 255, 0), -1);
                            cv::circle(akazeDefDraw, akaze_def_pts[i], 3, cv::Scalar(0, 255, 0), -1);
                        }

                        drawOutlinedText(akazeRefDraw, "AKAZE Reference Features: " + std::to_string(akaze_ref_pts.size()), cv::Point(10, 25), 0.6);
                        drawOutlinedText(akazeDefDraw, "AKAZE Deformed Features", cv::Point(10, 25), 0.6);

                        cv::imwrite(local_debug_dir + "/0_akaze_features_ref.jpg", akazeRefDraw);
                        cv::imwrite(local_debug_dir + "/0_akaze_features_def.jpg", akazeDefDraw);
                    }

                    for (size_t i = 0; i < akaze_ref_pts.size(); ++i) {
                        // 🚀 Apply the exact offset used during the successful extraction
                        akaze_ref_pts[i].x += winning_padded_roi.x;
                        akaze_ref_pts[i].y += winning_padded_roi.y;
                        akaze_def_pts[i].x += winning_padded_roi.x;
                        akaze_def_pts[i].y += winning_padded_roi.y;
                    }
                    std::vector<float> us, vs;
                    for (size_t i = 0; i < akaze_ref_pts.size(); i++) {
                        us.push_back(akaze_def_pts[i].x - akaze_ref_pts[i].x);
                        vs.push_back(akaze_def_pts[i].y - akaze_ref_pts[i].y);
                    }
                    std::sort(us.begin(), us.end()); std::sort(vs.begin(), vs.end());
                    globalU = us[us.size() / 2]; globalV = vs[vs.size() / 2];
                }
            }
        } catch (...) {}
    }

    // 🚀 PRIORITY 4 & 6: Path C Smart Seed Fallback
    // If AKAZE found too few points, or they are too clustered (<30% convex hull coverage),
    // trigger the Path C Seed Hunter instead of aborting.
    // 🚀 IMPLEMENTATION: If we didn't even get a SPARSE mesh, trigger Path C
    execute_path_c = (mesh_quality == MeshQuality::NONE);

    int gridW = rectWidth / step;
    int gridH = rectHeight / step;
    if (gridW <= 0 || gridH <= 0) return -2; // 🚀 PRIORITY 3: Return -2 for ROI Errors

    // Now that gridW and gridH exist, we can set their default fallbacks
    path_c_seed_x = gridW / 2;
    path_c_seed_y = gridH / 2;

    struct GridPoint {
        float x, y, u, v, ux, uy, vx, vy, corr;
        bool solved;
        int thread_id;
        int compute_order;
        int mesh_assignment_type;
        bool used_simplex;
        int icgn_iters;
        float guess_u, guess_v, guess_ux, guess_uy, guess_vx, guess_vy;
    };
    std::vector<std::vector<GridPoint>> resultGrid(gridH, std::vector<GridPoint>(gridW));
    int total_valid_points = 0;

    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            int realX = rectX + x * step; int realY = rectY + y * step;
            bool shouldSkip = (!roiMask.empty() && roiMask.at<uchar>(realY, realX) < 128);
            if (!shouldSkip) total_valid_points++;
            resultGrid[y][x] = {(float)realX, (float)realY, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, shouldSkip, -1, -1, 0, false, 0};
        }
    }

    if (total_valid_points == 0) return -2; // 🚀 PRIORITY 3: Return -2 for Empty Mask

    std::atomic<int> global_points_solved(0);
    std::atomic<int> compute_order_counter(1);

    std::vector<ThreadStats> stats_pathA(safe_cores);
    std::vector<ThreadStats> stats_pathB(safe_cores);

    // =========================================================
    // 🔒 PROGRESS THREAD & JNI CALLBACK SETUP
    // =========================================================
    struct JniGlobalRefGuard {
        JNIEnv *env; jobject &ref;
        ~JniGlobalRefGuard() { if (ref != nullptr) { env->DeleteGlobalRef(ref); ref = nullptr; } }
    };

    jclass callbackClass = nullptr;
    jmethodID methodId = nullptr;
    jobject globalCallbackObj = nullptr;
    JavaVM *jvm = nullptr;
    env->GetJavaVM(&jvm);

    if (callbackObj != nullptr) {
        globalCallbackObj = env->NewGlobalRef(callbackObj);
        callbackClass = env->GetObjectClass(callbackObj);
        methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");
        if (callbackClass != nullptr) {
            env->DeleteLocalRef(callbackClass);
            callbackClass = nullptr;
        }
    }

    JniGlobalRefGuard callbackGuard{env, globalCallbackObj};

    std::mutex progress_cv_mutex;
    std::condition_variable progress_cv;
    std::atomic<bool> progress_thread_should_stop(false);
    bool progress_thread_detached = false;

    std::thread progress_thread([&]() {
        if (jvm == nullptr || globalCallbackObj == nullptr || methodId == nullptr) {
            std::lock_guard<std::mutex> lk(progress_cv_mutex);
            progress_thread_detached = true;
            progress_cv.notify_one();
            return;
        }

        JNIEnv *pEnv = nullptr;
        if (jvm->AttachCurrentThread(&pEnv, nullptr) != JNI_OK) {
            std::lock_guard<std::mutex> lk(progress_cv_mutex);
            progress_thread_detached = true;
            progress_cv.notify_one();
            return;
        }

        while (!progress_thread_should_stop.load(std::memory_order_acquire)) {
            if (globalCallbackObj == nullptr) break;
            int solved = global_points_solved.load(std::memory_order_relaxed);
            int percentage = (int)((((float)solved / total_valid_points) * 80.0f) + 10.0f);
            percentage = std::max(0, std::min(100, percentage));

            if (!pEnv->ExceptionCheck()) {
                pEnv->CallVoidMethod(globalCallbackObj, methodId, (jint)percentage);
                if (pEnv->ExceptionCheck()) pEnv->ExceptionClear();
            }

            for (int i = 0; i < 10; ++i) {
                if (progress_thread_should_stop.load(std::memory_order_acquire)) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        }
        jvm->DetachCurrentThread();
        {
            std::lock_guard<std::mutex> lk(progress_cv_mutex);
            progress_thread_detached = true;
        }
        progress_cv.notify_one();
    });

    struct ThreadJoinGuard {
        std::thread &t; std::atomic<bool> &stop_flag; std::condition_variable &cv;
        std::mutex &cv_mutex; bool &detached_flag;
        ~ThreadJoinGuard() {
            if (t.joinable()) {
                stop_flag.store(true, std::memory_order_release);
                {
                    std::unique_lock<std::mutex> lk(cv_mutex);
                    cv.wait(lk, [this] { return detached_flag; });
                }
                t.join();
            }
        }
    };

    ThreadJoinGuard progressGuard{progress_thread, progress_thread_should_stop,
                                  progress_cv, progress_cv_mutex, progress_thread_detached};

    std::vector<float> guessU(gridW * gridH, globalU);
    std::vector<float> guessV(gridW * gridH, globalV);
    std::vector<float> guessUx(gridW * gridH, 0.0f);
    std::vector<float> guessUy(gridW * gridH, 0.0f);
    std::vector<float> guessVx(gridW * gridH, 0.0f);
    std::vector<float> guessVy(gridW * gridH, 0.0f);
    std::vector<bool> inMesh(gridW * gridH, false);

    struct AffineTriangle { cv::Point2f pts[3]; double u, v, ux, uy, vx, vy; cv::Rect2f boundingBox; };
    std::vector<AffineTriangle> affTriangles;

    // ==========================================
    // 🚀 PATH A (DELAUNAY MESH SETUP)
    // ==========================================
    {
        auto t_mesh_start = std::chrono::high_resolution_clock::now();

        cv::Subdiv2D subdiv(cv::Rect(0, 0, g_refWidth, g_refHeight));
        for (size_t i = 0; i < akaze_ref_pts.size(); i++) {
            if (akaze_ref_pts[i].x > 0 && akaze_ref_pts[i].x < g_refWidth &&
                akaze_ref_pts[i].y > 0 && akaze_ref_pts[i].y < g_refHeight) {
                subdiv.insert(akaze_ref_pts[i]);
            }
        }

        std::vector<cv::Vec6f> triangleList;
        subdiv.getTriangleList(triangleList);

        auto getDefPt = [&](cv::Point2f pt) -> cv::Point2f {
            float min_dist = 1e9; cv::Point2f best_pt = pt;
            for (size_t i = 0; i < akaze_ref_pts.size(); i++) {
                float d = (akaze_ref_pts[i].x - pt.x) * (akaze_ref_pts[i].x - pt.x) + (akaze_ref_pts[i].y - pt.y) * (akaze_ref_pts[i].y - pt.y);
                if (d < min_dist) { min_dist = d; best_pt = akaze_def_pts[i]; }
            }
            return best_pt;
        };

        try {
            for (size_t i = 0; i < triangleList.size(); i++) {
                cv::Vec6f t = triangleList[i]; cv::Point2f pt[3];
                pt[0] = cv::Point2f(t[0], t[1]); pt[1] = cv::Point2f(t[2], t[3]); pt[2] = cv::Point2f(t[4], t[5]);
                if (pt[0].x < 0 || pt[0].x >= g_refWidth || pt[1].x < 0 || pt[1].x >= g_refWidth || pt[2].x < 0 || pt[2].x >= g_refWidth) continue;
                cv::Point2f dst[3];
                dst[0] = getDefPt(pt[0]);
                dst[1] = getDefPt(pt[1]);
                dst[2] = getDefPt(pt[2]);

                // OpenCV returns: [ x_def ] = [ M00 M01 M02 ] * [ x_ref ]
                //                 [ y_def ]   [ M10 M11 M12 ]   [ y_ref ]
                //                                               [   1   ]
                cv::Mat warp_mat = cv::getAffineTransform(pt, dst);

                AffineTriangle at;
                at.pts[0] = pt[0]; at.pts[1] = pt[1]; at.pts[2] = pt[2];

                // ICGN Engine Expects: U(dx, dy) = U0 + Ux*dx + Uy*dy
                // Where dx, dy is relative to the Grid Point (0,0)

                double M00 = warp_mat.at<double>(0, 0);
                double M01 = warp_mat.at<double>(0, 1);
                double M02 = warp_mat.at<double>(0, 2);
                double M10 = warp_mat.at<double>(1, 0);
                double M11 = warp_mat.at<double>(1, 1);
                double M12 = warp_mat.at<double>(1, 2);

                // Strain/Shear derivatives (Ux = du/dx)
                at.ux = M00 - 1.0;
                at.uy = M01;
                at.vx = M10;
                at.vy = M11 - 1.0;

                // The Translation (U0, V0) evaluated at Coordinate (0,0) of the image!
                // Because OpenCV's matrix is global, the intercept M02 is the translation at absolute pixel (0,0).
                at.u = M02;
                at.v = M12;
                float minX = std::min({pt[0].x, pt[1].x, pt[2].x}); float maxX = std::max({pt[0].x, pt[1].x, pt[2].x});
                float minY = std::min({pt[0].y, pt[1].y, pt[2].y}); float maxY = std::max({pt[0].y, pt[1].y, pt[2].y});
                at.boundingBox = cv::Rect2f(minX - 15.0f, minY - 15.0f, (maxX - minX) + 30.0f, (maxY - minY) + 30.0f);
                affTriangles.push_back(at);
            }
        } catch (...) {}

        if (!local_debug_dir.empty()) {
            try {
                cv::Mat refFloat(g_refHeight, g_refWidth, CV_32FC1, (void *)g_refImg->intensities.data());
                cv::Mat ref8U, refColor;
                refFloat.convertTo(ref8U, CV_8UC1);
                cv::cvtColor(ref8U, refColor, cv::COLOR_GRAY2BGR);
                cv::Rect roiRect(rectX, rectY, rectWidth, rectHeight);
                roiRect = roiRect & cv::Rect(0, 0, g_refWidth, g_refHeight);
                cv::Mat meshDebug = refColor(roiRect) * 0.35; // Reduces opacity/brightness by 65%

                for (const auto &tri : affTriangles) {
                    cv::Point pt1(tri.pts[0].x - rectX, tri.pts[0].y - rectY);
                    cv::Point pt2(tri.pts[1].x - rectX, tri.pts[1].y - rectY);
                    cv::Point pt3(tri.pts[2].x - rectX, tri.pts[2].y - rectY);
                    cv::line(meshDebug, pt1, pt2, cv::Scalar(255, 255, 0), 2, cv::LINE_AA);
                    cv::line(meshDebug, pt2, pt3, cv::Scalar(255, 255, 0), 2, cv::LINE_AA);
                    cv::line(meshDebug, pt3, pt1, cv::Scalar(255, 255, 0), 2, cv::LINE_AA);
                }
                drawOutlinedText(meshDebug, "Delaunay 6-DOF Mesh", cv::Point(10, 25), 0.6);
                cv::imwrite(local_debug_dir + "/delaunay_mesh_debug.jpg", meshDebug);
            } catch (...) {}
        }
        time_delaunay = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_mesh_start).count();

        auto t_assign_start = std::chrono::high_resolution_clock::now();
        std::vector<std::vector<cv::Point2f>> triContours;
        for (const auto &tri : affTriangles) triContours.push_back({tri.pts[0], tri.pts[1], tri.pts[2]});

        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                if (resultGrid[y][x].solved) continue;
                cv::Point2f gp(rectX + x * step, rectY + y * step);
                int idx = y * gridW + x;
                for (size_t ti = 0; ti < affTriangles.size(); ++ti) {
                    const auto &tri = affTriangles[ti];
                    if (gp.x < tri.boundingBox.x || gp.x > tri.boundingBox.x + tri.boundingBox.width || gp.y < tri.boundingBox.y || gp.y > tri.boundingBox.y + tri.boundingBox.height) continue;
                    if (cv::pointPolygonTest(triContours[ti], gp, false) >= 0) {
                        guessU[idx] = (float)(tri.ux * gp.x + tri.uy * gp.y + tri.u); guessV[idx] = (float)(tri.vx * gp.x + tri.vy * gp.y + tri.v);
                        guessUx[idx] = (float)tri.ux; guessUy[idx] = (float)tri.uy; guessVx[idx] = (float)tri.vx; guessVy[idx] = (float)tri.vy;
                        inMesh[idx] = true; resultGrid[y][x].mesh_assignment_type = 1; break;
                    }
                }
            }
        }
        time_contour_assign = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_assign_start).count();

        auto t_extrap_start = std::chrono::high_resolution_clock::now();
        // 🚀 IMPLEMENTATION: Priority 4 (Only extrapolate if the mesh is fully distributed)
        if (mesh_quality == MeshQuality::FULL) {
            float EXTRAP_LIMIT = -3.0f * step;
            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    int idx = y * gridW + x;
                    if (inMesh[idx] || resultGrid[y][x].solved) continue;
                    cv::Point2f gp(rectX + x * step, rectY + y * step);
                    float best_dist = EXTRAP_LIMIT - 1.0f; int best_ti = -1;
                    for (size_t ti = 0; ti < affTriangles.size(); ++ti) {
                        const auto &tri = affTriangles[ti];
                        if (gp.x < tri.boundingBox.x + EXTRAP_LIMIT || gp.x > tri.boundingBox.x + tri.boundingBox.width - EXTRAP_LIMIT || gp.y < tri.boundingBox.y + EXTRAP_LIMIT || gp.y > tri.boundingBox.y + tri.boundingBox.height - EXTRAP_LIMIT) continue;
                        double dist = cv::pointPolygonTest(triContours[ti], gp, true);
                        if (dist >= EXTRAP_LIMIT && (float)dist > best_dist) { best_dist = (float)dist; best_ti = (int)ti; }
                    }
                    if (best_ti >= 0) {
                        const auto &tri = affTriangles[best_ti];
                        guessU[idx] = (float)(tri.ux * gp.x + tri.uy * gp.y + tri.u); guessV[idx] = (float)(tri.vx * gp.x + tri.vy * gp.y + tri.v);
                        guessUx[idx] = (float)tri.ux; guessUy[idx] = (float)tri.uy; guessVx[idx] = (float)tri.vx; guessVy[idx] = (float)tri.vy;
                        inMesh[idx] = true; resultGrid[y][x].mesh_assignment_type = 2;
                    }
                }
            }
        } else {
            LOGD("ROUTING: Mesh is SPARSE. Disabling extrapolation to prevent bad guesses.");
        }
        time_extrapolate = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_extrap_start).count();
        auto t_smooth_start = std::chrono::high_resolution_clock::now();
        auto smoothGrid = [&](std::vector<float> &grid, int radius) {
            std::vector<float> temp = grid;
            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    if (!inMesh[y * gridW + x]) continue;
                    float sum = 0.0f; int count = 0;
                    for (int dy = -radius; dy <= radius; ++dy) {
                        for (int dx = -radius; dx <= radius; ++dx) {
                            int ny = y + dy, nx = x + dx;
                            if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH && inMesh[ny * gridW + nx]) { sum += temp[ny * gridW + nx]; count++; }
                        }
                    }
                    if (count > 0) grid[y * gridW + x] = sum / count;
                }
            }
        };
        smoothGrid(guessU, 2); smoothGrid(guessV, 2); smoothGrid(guessUx, 2); smoothGrid(guessUy, 2); smoothGrid(guessVx, 2); smoothGrid(guessVy, 2);
        time_smoothing = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_smooth_start).count();
    }

    // =========================================================
    // 🚀 GLOBAL HESSIAN PRE-PASS & CONTRAST THRESHOLDING
    // =========================================================
    auto t_prepass_start = std::chrono::high_resolution_clock::now();
    std::vector<IndicVision::CachedHessianData, Eigen::aligned_allocator<IndicVision::CachedHessianData>> hessian_pool(gridW * gridH);

    // Track standard deviations to build an adaptive threshold
    double sum_std_dev = 0.0;
    int valid_std_count = 0;
    std::mutex std_mutex;

#pragma omp parallel for schedule(static) num_threads(safe_cores)
    for (int pool_idx = 0; pool_idx < gridW * gridH; ++pool_idx) {
        int gx = pool_idx % gridW, gy = pool_idx / gridW;
        if (!resultGrid[gy][gx].solved) {
            int realX = rectX + gx * step, realY = rectY + gy * step;
            hessian_pool[pool_idx] = IndicVision::SubsetPrecomputer::compute_hessian_only(*g_refImg, realX, realY, subsetSize);

            if (hessian_pool[pool_idx].valid) {
                std::lock_guard<std::mutex> lock(std_mutex);
                sum_std_dev += hessian_pool[pool_idx].std_dev;
                valid_std_count++;
            }
        }
    }

    // 🚀 SIMPLIFIED CONTRAST THRESHOLD: Absolute Minimum Floor
    // We abandon the adaptive curve because it unfairly penalizes valid, lower-contrast regions of the speckle.
    float min_allowed_std = 3.0f; // Hard floor. Anything below 3.0 is pure sensor noise / empty void.

    for (int pool_idx = 0; pool_idx < gridW * gridH; ++pool_idx) {
        int gx = pool_idx % gridW, gy = pool_idx / gridW;
        if (!resultGrid[gy][gx].solved && hessian_pool[pool_idx].valid) {
            if (hessian_pool[pool_idx].std_dev < min_allowed_std) {
                // Automatically declare this point as dead/unsolvable space
                resultGrid[gy][gx].solved = true;
                resultGrid[gy][gx].corr = 0.0f;
                total_valid_points--;
            }
        }
    }
    time_prepass = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_prepass_start).count();
    // =========================================================
    // 🚀 PATH C: SMART SEED HUNTER (Runs only if Mesh failed)
    // =========================================================
    if (execute_path_c) {
        LOGD("ROUTING: Coverage Insufficient. Switching to Path C (Seed Hunter).");
        float max_texture = -1.0f;
        int best_idx = -1;

        // Find the single grid point with the absolute highest gradient energy
        for (int i = 0; i < gridW * gridH; ++i) {
            int gx = i % gridW, gy = i / gridW;
            if (!resultGrid[gy][gx].solved && hessian_pool[i].valid) {
                float texture = hessian_pool[i].H(0,0) + hessian_pool[i].H(1,1);
                if (texture > max_texture) {
                    max_texture = texture;
                    best_idx = i;
                }
            }
        }

        if (best_idx >= 0) {
            path_c_seed_x = best_idx % gridW;
            path_c_seed_y = best_idx / gridW;
            int realX = rectX + path_c_seed_x * step;
            int realY = rectY + path_c_seed_y * step;

            IndicVision::SubsetData seed_subset;
            IndicVision::SubsetPrecomputer::precompute_subset_fast(seed_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[best_idx]);

            if (seed_subset.is_initialized) {
                IndicVision::OptimizationEngine seed_engine;
                // Brute-force template match for the single best point
                auto res = seed_engine.calculate_deformation(seed_subset, defImg, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, IndicVision::INIT_AUTO_SEARCH);

                if (res.status == 0 && res.correlation_score <= 0.15f) {
                    globalU = res.u; globalV = res.v;
                    LOGD("PATH C SUCCESS: Seed found at [%d, %d] u=%.2f, v=%.2f", realX, realY, globalU, globalV);
                } else {
                    LOGE("PATH C FAILED: Global anchor search diverged.");
                    return -1;
                }
            } else { return -1; }
        } else { return -1; }

        // Clear AKAZE points to ensure Path A (Mesh Execution) is safely skipped
        akaze_ref_pts.clear();
        akaze_def_pts.clear();
    }
    // ==========================================
    // 🚀 PATH A (DELAUNAY MESH EXECUTION)
    // ==========================================
    {
        auto t_pathA_start = std::chrono::high_resolution_clock::now();
        std::atomic<bool> omp_region_threw(false);
#pragma omp parallel num_threads(safe_cores)
        {
            int tid = omp_get_thread_num();
            IndicVision::OptimizationEngine local_engine; IndicVision::SubsetData local_subset;
            // 🚀 ENABLE LEVENBERG-MARQUARDT (TIKHONOV REGULARIZATION) - PATH A
            local_engine.lm_enabled = true;
            local_engine.lm_alpha = 0.05f; // <--- TUNE THIS VALUE
            double local_hessian = 0.0, local_wait = 0.0; int local_pts = 0;
            EngineStatFlusher flusher(local_engine, stats_pathA[tid], local_pts, local_hessian, local_wait);

#pragma omp for schedule(dynamic, 32)
            for (int idx = 0; idx < gridW * gridH; ++idx) {
                if (omp_region_threw.load(std::memory_order_relaxed)) continue;
                if (!inMesh[idx]) continue;
                int x = idx % gridW, y = idx / gridW;
                if (resultGrid[y][x].solved) continue;

                int realX = rectX + x * step, realY = rectY + y * step;
                auto th1 = std::chrono::high_resolution_clock::now();
                IndicVision::SubsetPrecomputer::precompute_subset_fast(local_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[idx]);
                local_hessian += std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - th1).count();

                if (local_subset.is_initialized) {
                    // Save the exact guess before we run
                    resultGrid[y][x].guess_u = guessU[idx];
                    resultGrid[y][x].guess_v = guessV[idx];
                    resultGrid[y][x].guess_ux = guessUx[idx];
                    resultGrid[y][x].guess_uy = guessUy[idx];
                    resultGrid[y][x].guess_vx = guessVx[idx];
                    resultGrid[y][x].guess_vy = guessVy[idx];

                    int simplex_count_before = local_engine.count_simplex;

                    auto search_flag = ALLOW_SIMPLEX_RESCUE ? IndicVision::INIT_NO_SEARCH : IndicVision::INIT_NO_SIMPLEX;
                    IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                            local_subset, defImg, guessU[idx], guessV[idx], guessUx[idx], guessUy[idx], guessVx[idx], guessVy[idx], search_flag);
                    stats_pathA[tid].icgn_iters += res.iters;
                    bool needed_rescue = (local_engine.count_simplex > simplex_count_before);

                    if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

                    if (needed_rescue) {
                        stats_pathA[tid].simplex_calls++;

                        // 🚀 FIX: Now correctly checks the REAL iterations for timeout!
                        if (res.iters >= 20) {
                            stats_pathA[tid].simplex_from_timeout++;
                        } else {
                            stats_pathA[tid].simplex_from_crash++;
                        }

                        if (res.status == 0 && res.correlation_score <= 0.15f && ALLOW_SIMPLEX_RESCUE) {
                            stats_pathA[tid].simplex_saved++;
                        } else {
                            stats_pathA[tid].simplex_dead++;
                        }
                    }

                    if (res.status == 0 && res.correlation_score <= 0.15f) {
                        int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                        // 🚀 FIX: Passed res.iters at the end instead of icgn_iters_used
                        resultGrid[y][x] = {(float)realX, (float)realY, res.u, res.v, res.ux, res.uy, res.vx, res.vy,
                                            res.correlation_score, true, tid, order, resultGrid[y][x].mesh_assignment_type,
                                            needed_rescue, res.iters};
                        global_points_solved.fetch_add(1, std::memory_order_relaxed); local_pts++;
                    } else {
                        resultGrid[y][x].solved = false;
                        resultGrid[y][x].corr = 0.0f;
                        resultGrid[y][x].used_simplex = needed_rescue;
                        // 🚀 FIX: Assign the real iterations on failure
                        resultGrid[y][x].icgn_iters = res.iters;
                    }
                }
            }
        }
        time_pathA = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_pathA_start).count();
    }


    std::unique_ptr<std::atomic<bool>[]> cell_claimed(new std::atomic<bool>[gridW * gridH]);
    for (int i = 0; i < gridW * gridH; ++i) {
        int gx = i % gridW, gy = i / gridW;
        cell_claimed[i].store(resultGrid[gy][gx].solved, std::memory_order_relaxed);
    }

    // ==========================================
    // 🚀 PATH B (GLOBAL QUEUE EXECUTION)
    // ==========================================
    {
        ScopedTimer pathB_timer(time_pathB);
        std::vector<IndicVision::SeedNode> boundary_seeds;
        std::vector<IndicVision::SeedNode> global_seeds;

        const int dx4[] = {1, -1, 0, 0}, dy4[] = {0, 0, 1, -1};
        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                if (!resultGrid[y][x].solved || resultGrid[y][x].corr <= 0.f) continue;
                bool touching = false;
                for (int k = 0; k < 4; ++k) {
                    int nx = x + dx4[k], ny = y + dy4[k];
                    if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH && !resultGrid[ny][nx].solved) { touching = true; break; }
                }
                if (touching) boundary_seeds.push_back(IndicVision::SeedNode(x, y, resultGrid[y][x].u, resultGrid[y][x].v, resultGrid[y][x].ux, resultGrid[y][x].uy, resultGrid[y][x].vx, resultGrid[y][x].vy, resultGrid[y][x].corr));
            }
        }

        if (boundary_seeds.empty()) {
            struct SeedCandidate {
                int ix, iy;
                float u_init, v_init;
                float dist_from_center;
                float displacement_mag;
            };
            std::vector<SeedCandidate> candidates;
            float grid_cx = rectX + (gridW / 2.f) * step, grid_cy = rectY + (gridH / 2.f) * step;

            for (size_t fi = 0; fi < akaze_ref_pts.size(); ++fi) {
                float fx = akaze_ref_pts[fi].x, fy = akaze_ref_pts[fi].y;
                int ix = (int)std::round((fx - rectX) / (float)step);
                int iy = (int)std::round((fy - rectY) / (float)step);
                if (ix < 0 || ix >= gridW || iy < 0 || iy >= gridH || resultGrid[iy][ix].solved) continue;
                float du = akaze_def_pts[fi].x - fx, dv = akaze_def_pts[fi].y - fy;
                float world_x = rectX + ix * step, world_y = rectY + iy * step;
                float dist_c = std::sqrt((world_x - grid_cx)*(world_x - grid_cx) + (world_y - grid_cy)*(world_y - grid_cy));
                candidates.push_back({ix, iy, du, dv, dist_c, std::sqrt(du*du + dv*dv)});
            }

            if (candidates.empty()) {
                // 🚀 PRIORITY 4: Use the intelligently found Smart Seed from Path C
                global_seeds.push_back(IndicVision::SeedNode(path_c_seed_x, path_c_seed_y, globalU, globalV, 0.f, 0.f, 0.f, 0.f, 0.f));
            } else {
                std::sort(candidates.begin(), candidates.end(), [](const SeedCandidate& a, const SeedCandidate& b) {
                    if (std::abs(a.displacement_mag - b.displacement_mag) > 1.f) return a.displacement_mag < b.displacement_mag;
                    return a.dist_from_center < b.dist_from_center;
                });
                int seeds_pushed = 0;
                for (const auto& c : candidates) {
                    if (seeds_pushed >= 5) break;
                    global_seeds.push_back(IndicVision::SeedNode(c.ix, c.iy, c.u_init, c.v_init, 0.f, 0.f, 0.f, 0.f, 0.f));
                    seeds_pushed++;
                }
            }
        }

        struct GlobalQueue {
            std::priority_queue<IndicVision::SeedNode> q;
            std::mutex mtx; std::condition_variable cv;
            int active = 0; bool done = false;
        } gq;
        std::atomic<int> seed_idx(0);
        std::mutex grid_mutex;
        const int cores_to_use = safe_cores;

        for (const auto &s : boundary_seeds) {
            gq.q.push(s);
        }

        IndicVision::OptimizationEngine prewarm_engine;
        // 🚀 ENABLE LEVENBERG-MARQUARDT - PATH B (PREWARM)
        prewarm_engine.lm_enabled = true;
        prewarm_engine.lm_alpha = 0.05f; // <--- TUNE THIS VALUE

        IndicVision::SubsetData prewarm_subset;

        while ((int)gq.q.size() < cores_to_use && seed_idx.load() < (int)global_seeds.size()) {
            int si = seed_idx.fetch_add(1, std::memory_order_relaxed);
            if (si >= (int)global_seeds.size()) break;
            const auto &seed = global_seeds[si];
            int flat = seed.y_idx * gridW + seed.x_idx;

            bool unclaimed = false;
            if (!cell_claimed[flat].compare_exchange_strong(unclaimed, true, std::memory_order_acq_rel, std::memory_order_relaxed)) continue;

            int realX = rectX + seed.x_idx * step, realY = rectY + seed.y_idx * step;
            IndicVision::SubsetPrecomputer::precompute_subset_fast(prewarm_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[flat]);
            if (!prewarm_subset.is_initialized) continue;

            int simplex_count_before = prewarm_engine.count_simplex;
            int icgn_count_before = prewarm_engine.count_icgn;

            auto search_flag = ALLOW_SIMPLEX_RESCUE ? IndicVision::INIT_NO_SEARCH : IndicVision::INIT_NO_SIMPLEX;
            IndicVision::AnalysisResult res = prewarm_engine.calculate_deformation(
                    prewarm_subset, defImg, seed.u, seed.v, 0.f, 0.f, 0.f, 0.f, search_flag);
            stats_pathB[0].icgn_iters += res.iters;
            bool needed_rescue = (prewarm_engine.count_simplex > simplex_count_before);
            int icgn_iters_used = prewarm_engine.count_icgn - icgn_count_before;

            if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

            if (needed_rescue) {
                stats_pathB[0].simplex_calls++;

                if (icgn_iters_used >= 20) {
                    stats_pathB[0].simplex_from_timeout++;
                } else {
                    stats_pathB[0].simplex_from_crash++;
                }

                if (res.status == 0 && res.correlation_score <= 0.15f && ALLOW_SIMPLEX_RESCUE) {
                    stats_pathB[0].simplex_saved++;
                } else {
                    stats_pathB[0].simplex_dead++;
                }
            }

            if (res.status == 0 && res.correlation_score <= 0.15f) {
                int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                resultGrid[seed.y_idx][seed.x_idx] = {(float)realX, (float)realY, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score, true, -1, order, resultGrid[seed.y_idx][seed.x_idx].mesh_assignment_type, needed_rescue, icgn_iters_used};                global_points_solved.fetch_add(1, std::memory_order_relaxed);
                gq.q.push(IndicVision::SeedNode(seed.x_idx, seed.y_idx, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
            } else {
                resultGrid[seed.y_idx][seed.x_idx].corr = 0.f;
                resultGrid[seed.y_idx][seed.x_idx].used_simplex = needed_rescue;
                resultGrid[seed.y_idx][seed.x_idx].icgn_iters = icgn_iters_used;
            }
        }

        if (gq.q.empty() && seed_idx.load() >= (int)global_seeds.size()) {
            gq.done = true;
        }

        std::vector<std::thread> workers;
        struct WorkerGuard { std::vector<std::thread> &ws; ~WorkerGuard() { for (auto &w : ws) if (w.joinable()) w.join(); } } wg{workers};

        for (int t = 0; t < cores_to_use; ++t) {
            workers.emplace_back([&, t]() {
                try {
                    const int tid = t;
                    const int DX[] = {1, -1, 0, 0}, DY[] = {0, 0, 1, -1};
                    IndicVision::OptimizationEngine local_engine;
                    // 🚀 ENABLE LEVENBERG-MARQUARDT - PATH B (WORKERS)
                    local_engine.lm_enabled = true;
                    local_engine.lm_alpha = .05f; // <--- TUNE THIS VALUE
                    IndicVision::SubsetData local_subset;
                    double local_hessian_ms = 0.0, local_wait_ms = 0.0;
                    int local_points_solved = 0;
                    EngineStatFlusher flusher(local_engine, stats_pathB[tid], local_points_solved, local_hessian_ms, local_wait_ms);

                    while (true) {
                        IndicVision::SeedNode cur;
                        bool has_node = false;
                        {
                            std::unique_lock<std::mutex> lk(gq.mtx);
                            auto wait_start = std::chrono::high_resolution_clock::now();
                            gq.cv.wait(lk, [&] { return !gq.q.empty() || gq.done || (gq.active == 0 && seed_idx.load(std::memory_order_relaxed) < (int)global_seeds.size()); });
                            local_wait_ms += std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - wait_start).count();
                            if (gq.done) return;
                            if (!gq.q.empty()) { cur = gq.q.top(); gq.q.pop(); gq.active++; has_node = true; } else { gq.active++; }
                        }

                        if (!has_node) {
                            bool seed_pushed = false;
                            int si = seed_idx.fetch_add(1, std::memory_order_relaxed);
                            if (si < (int)global_seeds.size()) {
                                const auto &seed = global_seeds[si];
                                int flat = seed.y_idx * gridW + seed.x_idx;
                                bool unclaimed = false;
                                if (cell_claimed[flat].compare_exchange_strong(unclaimed, true, std::memory_order_acq_rel, std::memory_order_relaxed)) {
                                    int realX = rectX + seed.x_idx * step, realY = rectY + seed.y_idx * step;
                                    auto th1 = std::chrono::high_resolution_clock::now();
                                    IndicVision::SubsetPrecomputer::precompute_subset_fast(local_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[flat]);
                                    local_hessian_ms += std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - th1).count();
                                    if (local_subset.is_initialized) {

                                        int simplex_count_before = local_engine.count_simplex;

                                        IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                                local_subset, defImg, seed.u, seed.v, 0.f, 0.f, 0.f, 0.f, IndicVision::INIT_NO_SEARCH);

                                        bool needed_rescue = (local_engine.count_simplex > simplex_count_before);

                                        if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

                                        if (needed_rescue) {
                                            stats_pathB[tid].simplex_calls++;

                                            // 🚀 FIX: Use res.iters
                                            if (res.iters >= 20) {
                                                stats_pathB[tid].simplex_from_timeout++;
                                            } else {
                                                stats_pathB[tid].simplex_from_crash++;
                                            }

                                            if (res.status == 0 && res.correlation_score <= 0.15f && ALLOW_SIMPLEX_RESCUE) {
                                                stats_pathB[tid].simplex_saved++;
                                            } else {
                                                stats_pathB[tid].simplex_dead++;
                                            }
                                        }

                                        if (res.status == 0 && res.correlation_score <= 0.15f) {
                                            int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                                            {
                                                std::lock_guard<std::mutex> lg(grid_mutex);
                                                // 🚀 FIX: Use res.iters at the end
                                                resultGrid[seed.y_idx][seed.x_idx] = {(float)realX, (float)realY, res.u, res.v, res.ux, res.uy, res.vx, res.vy,
                                                                                      res.correlation_score, true, tid, order, 0, needed_rescue, res.iters};
                                            }
                                            global_points_solved.fetch_add(1, std::memory_order_relaxed); local_points_solved++;
                                            { std::lock_guard<std::mutex> lq(gq.mtx); gq.q.push(IndicVision::SeedNode(seed.x_idx, seed.y_idx, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score)); gq.cv.notify_one(); }
                                            seed_pushed = true;
                                        } else {
                                            std::lock_guard<std::mutex> lg(grid_mutex);
                                            resultGrid[seed.y_idx][seed.x_idx].corr = 0.f;
                                            resultGrid[seed.y_idx][seed.x_idx].used_simplex = needed_rescue;
                                            // 🚀 FIX: Assign the real iterations
                                            resultGrid[seed.y_idx][seed.x_idx].icgn_iters = res.iters;
                                        }
                                    }
                                }
                            }
                            { std::lock_guard<std::mutex> lq(gq.mtx); gq.active--; bool seeds_exhausted = seed_idx.load(std::memory_order_relaxed) >= (int)global_seeds.size(); if (gq.q.empty() && gq.active == 0 && seeds_exhausted) { gq.done = true; } gq.cv.notify_all(); }
                            continue;
                        }

                        std::vector<IndicVision::SeedNode> pending_pushes;
                        pending_pushes.reserve(4);
                        for (int k = 0; k < 4; ++k) {
                            const int nx = cur.x_idx + DX[k], ny = cur.y_idx + DY[k];
                            if (nx < 0 || nx >= gridW || ny < 0 || ny >= gridH) continue;
                            const int flat = ny * gridW + nx;
                            bool was_unclaimed = false;
                            if (!cell_claimed[flat].compare_exchange_strong(was_unclaimed, true, std::memory_order_acq_rel, std::memory_order_relaxed)) continue;
                            { std::lock_guard<std::mutex> lg(grid_mutex); resultGrid[ny][nx].solved = true; }
                            const int realX = rectX + nx * step, realY = rectY + ny * step;
                            auto th1 = std::chrono::high_resolution_clock::now();
                            IndicVision::SubsetPrecomputer::precompute_subset_fast(local_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[flat]);
                            local_hessian_ms += std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - th1).count();
                            if (!local_subset.is_initialized) continue;

                            int simplex_count_before = local_engine.count_simplex;

                            // 🚀 FIRST-ORDER KINEMATIC EXPANSION (The Path B Fix)
                            // Calculate the physical distance from the solved point to the new neighbor
                            float dx = (nx - cur.x_idx) * step;
                            float dy = (ny - cur.y_idx) * step;

                            // Project the initial guess using the solved point's strain gradients
                            float guess_u = cur.u + cur.ux * dx + cur.uy * dy;
                            float guess_v = cur.v + cur.vx * dx + cur.vy * dy;

                            auto search_flag = ALLOW_SIMPLEX_RESCUE ? IndicVision::INIT_NO_SEARCH : IndicVision::INIT_NO_SIMPLEX;
                            IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                    local_subset, defImg, guess_u, guess_v, cur.ux, cur.uy, cur.vx, cur.vy, search_flag);
                            stats_pathB[tid].icgn_iters += res.iters; // 🚀 ADD THIS
                            bool needed_rescue = (local_engine.count_simplex > simplex_count_before);

                            if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

                            if (needed_rescue) {
                                stats_pathB[tid].simplex_calls++;

                                // 🚀 FIX: Use res.iters
                                if (res.iters >= 20) {
                                    stats_pathB[tid].simplex_from_timeout++;
                                } else {
                                    stats_pathB[tid].simplex_from_crash++;
                                }

                                if (res.status == 0 && res.correlation_score <= 0.15f && ALLOW_SIMPLEX_RESCUE) {
                                    stats_pathB[tid].simplex_saved++;
                                } else {
                                    stats_pathB[tid].simplex_dead++;
                                }
                            }

                            if (res.status == 0 && res.correlation_score <= 0.15f) {
                                const int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                                {
                                    std::lock_guard<std::mutex> lg(grid_mutex);
                                    // 🚀 FIX: Use res.iters at the end
                                    resultGrid[ny][nx] = {(float)realX, (float)realY, res.u, res.v, res.ux, res.uy, res.vx, res.vy,
                                                          res.correlation_score, true, tid, order, resultGrid[ny][nx].mesh_assignment_type,
                                                          needed_rescue, res.iters};
                                }
                                global_points_solved.fetch_add(1, std::memory_order_relaxed); local_points_solved++;
                                pending_pushes.push_back(IndicVision::SeedNode(nx, ny, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                            } else {
                                std::lock_guard<std::mutex> lg(grid_mutex);
                                resultGrid[ny][nx].corr = 0.f;
                                resultGrid[ny][nx].used_simplex = needed_rescue;
                                // 🚀 FIX: Assign the real iterations
                                resultGrid[ny][nx].icgn_iters = res.iters;
                            }
                        }
                        if (!pending_pushes.empty()) { std::lock_guard<std::mutex> lq(gq.mtx); for (auto &node : pending_pushes) { gq.q.push(std::move(node)); gq.cv.notify_one(); } }
                        { std::lock_guard<std::mutex> lq(gq.mtx); gq.active--; if (gq.q.empty() && gq.active == 0) { bool seeds_exhausted = seed_idx.load(std::memory_order_relaxed) >= (int)global_seeds.size(); if (seeds_exhausted) { gq.done = true; } gq.cv.notify_all(); } }
                    }
                } catch (...) {}
            });
        }
    }

    auto t_strain_start = std::chrono::high_resolution_clock::now();
    IndicVision::DisplacementField dispField;
    dispField.width = gridW; dispField.height = gridH; dispField.step = step;
    dispField.u.resize(gridW * gridH, 0.0f); dispField.v.resize(gridW * gridH, 0.0f);
    dispField.valid.resize(gridW * gridH, false);

    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            int idx = y * gridW + x;
            if (resultGrid[y][x].solved && resultGrid[y][x].corr != 0.0f) {
                dispField.u[idx] = resultGrid[y][x].u;
                dispField.v[idx] = resultGrid[y][x].v;
                dispField.valid[idx] = true;
            }
        }
    }

    IndicVision::StrainField strainField;
    if (useNlvcStrain) strainField = IndicVision::StrainCalculator::compute_nlvc_strain(dispField, strainWindow);
    else strainField = IndicVision::StrainCalculator::compute_vsg_strain(dispField, strainWindow);

    int valid_count = 0;
    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            int idx = y * gridW + x;
            if (dispField.valid[idx]) {
                int out_idx = valid_count * 8;
                output_ptr[out_idx + 0] = resultGrid[y][x].x;
                output_ptr[out_idx + 1] = resultGrid[y][x].y;
                output_ptr[out_idx + 2] = dispField.u[idx];
                output_ptr[out_idx + 3] = dispField.v[idx];
                output_ptr[out_idx + 4] = strainField.exx[idx];
                output_ptr[out_idx + 5] = strainField.eyy[idx];
                output_ptr[out_idx + 6] = strainField.exy[idx];
                output_ptr[out_idx + 7] = resultGrid[y][x].corr;
                valid_count++;
            }
        }
    }
    time_strain = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_strain_start).count();

    if (callbackObj != nullptr && methodId != nullptr && !env->ExceptionCheck()) {
        env->CallVoidMethod(callbackObj, methodId, (jint)100);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // ==========================================
    // ⏱️ AGGREGATE PROFILING METRICS
    // ==========================================
    double a_icgn = 0, a_simp = 0, b_icgn = 0, b_simp = 0, b_wait = 0;
    int a_simp_cnt = 0, b_simp_cnt = 0, total_icgn_iters = 0;

    int a_simp_calls = 0, a_simp_saved = 0, a_simp_dead = 0, a_simp_crash = 0, a_simp_timeout = 0;
    int b_simp_calls = 0, b_simp_saved = 0, b_simp_dead = 0, b_simp_crash = 0, b_simp_timeout = 0;

    double total_hessian = 0;
    int pathA_pts = 0, pathB_pts = 0;

    for (int i = 0; i < safe_cores; i++) {
        a_icgn += stats_pathA[i].icgn_time_ms; a_simp += stats_pathA[i].simplex_time_ms; a_simp_cnt += stats_pathA[i].simplex_iters;
        pathA_pts += stats_pathA[i].points_solved; total_hessian += stats_pathA[i].hessian_time_ms; total_icgn_iters += stats_pathA[i].icgn_iters;
        a_simp_calls += stats_pathA[i].simplex_calls; a_simp_saved += stats_pathA[i].simplex_saved; a_simp_dead += stats_pathA[i].simplex_dead;

        b_icgn += stats_pathB[i].icgn_time_ms; b_simp += stats_pathB[i].simplex_time_ms; b_simp_cnt += stats_pathB[i].simplex_iters;
        b_wait += stats_pathB[i].queue_wait_time_ms; pathB_pts += stats_pathB[i].points_solved; total_hessian += stats_pathB[i].hessian_time_ms;
        total_icgn_iters += stats_pathB[i].icgn_iters;
        b_simp_calls += stats_pathB[i].simplex_calls; b_simp_saved += stats_pathB[i].simplex_saved; b_simp_dead += stats_pathB[i].simplex_dead;

        a_simp_crash += stats_pathA[i].simplex_from_crash;
        a_simp_timeout += stats_pathA[i].simplex_from_timeout;

        b_simp_crash += stats_pathB[i].simplex_from_crash;
        b_simp_timeout += stats_pathB[i].simplex_from_timeout;
    }

    double time_total = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_total_start).count();

    LOGD("=== ⏱️ ADVANCED PERFORMANCE PROFILING ===");
    LOGD("Image Prep & Masking: %.2f ms", time_img_prep);
    LOGD("AKAZE & RANSAC:       %.2f ms", time_akaze + time_ransac);
    LOGD("Hessian Pre-pass:     %.2f ms (One-time Global Math)", time_prepass);

    LOGD("Delaunay Mesh Setup:  %.2f ms", time_delaunay);
    LOGD("Contour Assignment:   %.2f ms", time_contour_assign);
    LOGD("Extrapolation Pass:   %.2f ms", time_extrapolate);
    LOGD("Spatial Smoothing:    %.2f ms", time_smoothing);

    LOGD("Path A (Mesh Eval):   %.2f ms (Throughput: %.1f pts/ms)", time_pathA, (time_pathA > 0) ? pathA_pts / time_pathA : 0.0);
    if (pathA_pts > 0) {
        LOGD("  ↳ Path A ICGN Math: %.2f ms | Simplex: %.2f ms", a_icgn, a_simp);
        LOGD("      ↳ %d Calls (%d Crashes, %d Timeouts) -> %d Saved, %d Dead",
             a_simp_calls, a_simp_crash, a_simp_timeout, a_simp_saved, a_simp_dead);
    }

    if (pathB_pts > 0) {
        LOGD("  ↳ Path B ICGN Math: %.2f ms | Simplex: %.2f ms", b_icgn, b_simp);
        LOGD("      ↳ %d Calls (%d Crashes, %d Timeouts) -> %d Saved, %d Dead",
             b_simp_calls, b_simp_crash, b_simp_timeout, b_simp_saved, b_simp_dead);
        LOGD("  ↳ Path B Wait Time: %.2f ms (Thread idle/lock contention)", b_wait);
    }

    LOGD("Strain Calculation:   %.2f ms", time_strain);
    LOGD("Total JNI Execution:  %.2f ms", time_total);
    LOGD("--- ENGINE MATH & HARDWARE EFFICIENCY ---");
    LOGD("Total Points Solved:  %d (A: %d, B: %d)", valid_count, pathA_pts, pathB_pts);
    LOGD("Average ICGN Speed:   %.4f iterations / point", (valid_count > 0) ? (float)total_icgn_iters / valid_count : 0.0f);
    int tot_simp_calls = a_simp_calls + b_simp_calls;
    int tot_simp_saved = a_simp_saved + b_simp_saved;
    int tot_simp_dead = a_simp_dead + b_simp_dead;
    LOGD("Total Simplex Rescue: %.2f ms (%d calls -> %d saved, %d dead)", (a_simp + b_simp), tot_simp_calls, tot_simp_saved, tot_simp_dead);
    LOGD("=======================================");

    // =========================================================
    // 🐛 EXPORT FULL DEBUG SUITE
    // =========================================================
    if (!local_debug_dir.empty()) {
        try {
            int max_order = 1;
            float max_corr = 0.0001f, max_exx = -1e9f, min_exx = 1e9f;

            cv::Mat propMap(gridH, gridW, CV_8UC1, cv::Scalar(0));
            cv::Mat threadMap(gridH, gridW, CV_8UC3, cv::Scalar(30, 30, 30));
            cv::Mat meshAssignMap(gridH, gridW, CV_8UC3, cv::Scalar(0, 0, 0));
            cv::Mat corrMap(gridH, gridW, CV_8UC1, cv::Scalar(0));
            cv::Mat strainMap(gridH, gridW, CV_8UC1, cv::Scalar(0));
            cv::Mat simplexMap(gridH, gridW, CV_8UC3, cv::Scalar(30, 30, 30));

            static const cv::Vec3b THREAD_COLORS[12] = {
                    {60, 20, 220}, {20, 200, 20}, {200, 60, 20}, {200, 200, 20}, {20, 200, 200}, {200, 20, 200},
                    {100, 180, 255}, {255, 140, 30}, {50, 255, 180}, {180, 50, 255}, {255, 50, 130}, {130, 255, 50}};

            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    const auto &gp = resultGrid[y][x];
                    if (gp.solved && gp.corr > 0.0f) {
                        if (gp.compute_order > max_order) max_order = gp.compute_order;
                        if (gp.corr > max_corr) max_corr = gp.corr;
                        int idx = y * gridW + x;
                        if (strainField.exx[idx] > max_exx) max_exx = strainField.exx[idx];
                        if (strainField.exx[idx] < min_exx) min_exx = strainField.exx[idx];
                    }
                }
            }

            if (max_exx == min_exx) { max_exx += 0.001f; min_exx -= 0.001f; }
            max_corr = std::min(max_corr, 0.15f);

            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    const auto &gp = resultGrid[y][x];

                    bool is_masked = (!roiMask.empty() &&
                                      roiMask.at<uchar>(rectY + y * step, rectX + x * step) < 128);

                    if (!is_masked) {
                        if (gp.solved && gp.corr > 0.0f) {
                            if (!gp.used_simplex) {
                                simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 0);
                            } else if (gp.icgn_iters < 20) {
                                simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 255);
                            } else {
                                simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(255, 255, 0);
                            }
                        } else {
                            if (!gp.used_simplex) {
                                simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 0, 255);
                            } else if (gp.icgn_iters < 20) {
                                simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 165, 255);
                            } else {
                                simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(255, 0, 255);
                            }
                        }
                    }

                    if (!is_masked) {
                        if (gp.mesh_assignment_type == 1) {
                            meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 0);
                        } else if (gp.mesh_assignment_type == 2) {
                            meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 255);
                        } else if (gp.mesh_assignment_type >= 1000) {
                            int dist = gp.mesh_assignment_type - 1000;
                            int r = std::max(0, std::min(255, (dist - 127) * 2));
                            int g = 255 - std::abs(dist - 127) * 2;
                            int b = std::max(0, std::min(255, (127 - dist) * 2));
                            meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(b, g, r);
                        } else if (gp.mesh_assignment_type == 4) {
                            meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 165, 255);
                        } else {
                            meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(100, 100, 100);
                        }
                    }

                    if (!gp.solved || gp.compute_order < 0) continue;

                    propMap.at<uchar>(y, x) = (uchar) ((float) gp.compute_order / max_order * 255.0f);
                    threadMap.at<cv::Vec3b>(y, x) = THREAD_COLORS[std::max(0, std::min(gp.thread_id, 11))];
                    corrMap.at<uchar>(y, x) = (uchar) ((gp.corr / max_corr) * 255.0f);

                    int idx = y * gridW + x;
                    strainMap.at<uchar>(y, x) = (uchar) (
                            ((strainField.exx[idx] - min_exx) / (max_exx - min_exx)) * 255.0f);
                }
            }

            cv::Mat propColor, corrColor, strainColor;
            cv::applyColorMap(propMap, propColor, cv::COLORMAP_JET);
            cv::applyColorMap(corrMap, corrColor, cv::COLORMAP_JET);
            cv::applyColorMap(strainMap, strainColor, cv::COLORMAP_JET);

            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    if (!resultGrid[y][x].solved || resultGrid[y][x].compute_order < 0) {
                        propColor.at<cv::Vec3b>(y, x) = {0, 0, 0};
                        threadMap.at<cv::Vec3b>(y, x) = {30, 30, 30};
                        corrColor.at<cv::Vec3b>(y, x) = {0, 0, 0};
                        strainColor.at<cv::Vec3b>(y, x) = {0, 0, 0};
                    }
                }
            }

            cv::Mat outProp, outThread, outMesh, outCorr, outStrain, outSimplex;
            cv::Size sz(gridW * step, gridH * step);
            cv::resize(propColor, outProp, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(threadMap, outThread, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(meshAssignMap, outMesh, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(corrColor, outCorr, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(strainColor, outStrain, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(simplexMap, outSimplex, sz, 0, 0, cv::INTER_NEAREST);

            drawOutlinedText(outProp, "Propagation Debug", cv::Point(10, 25), 0.6);
            drawOutlinedText(outThread, "8-Core Thread Execution Map", cv::Point(10, 25), 0.6);
            drawOutlinedText(outMesh, "Mesh Assign (Grn=In, Yel=Ex, Gry=PathB)", cv::Point(10, 25), 0.6);
            drawOutlinedText(outCorr, "ZNSSD Quality (Blue=Perfect, Red=Marginal)", cv::Point(10, 25), 0.6);
            drawOutlinedText(outStrain, "Exx Strain (Raw Plot)", cv::Point(10, 25), 0.6);
            drawOutlinedText(outSimplex, "Grn=Perfect | Yel=Save(Crash) | Cya=Save(Time) | Org=Dead(Crash) | Pur=Dead(Time) | Red=Insta-Dead", cv::Point(10, 25), 0.4);

            cv::imwrite(local_debug_dir + "/propagation_debug.png", outProp);
            cv::imwrite(local_debug_dir + "/thread_debug.png", outThread);
            cv::imwrite(local_debug_dir + "/mesh_assignment_map.png", outMesh);
            cv::imwrite(local_debug_dir + "/correlation_heatmap.png", outCorr);
            cv::imwrite(local_debug_dir + "/strain_exx_debug.png", outStrain);
            cv::imwrite(local_debug_dir + "/simplex_health_map.png", outSimplex);

            cv::Mat outSimplexOverlap = outSimplex.clone();

            for (const auto &tri : affTriangles) {
                cv::Point pt1(tri.pts[0].x - rectX, tri.pts[0].y - rectY);
                cv::Point pt2(tri.pts[1].x - rectX, tri.pts[1].y - rectY);
                cv::Point pt3(tri.pts[2].x - rectX, tri.pts[2].y - rectY);

                cv::line(outSimplexOverlap, pt1, pt2, cv::Scalar(255, 255, 255), 2, cv::LINE_AA);
                cv::line(outSimplexOverlap, pt2, pt3, cv::Scalar(255, 255, 255), 2, cv::LINE_AA);
                cv::line(outSimplexOverlap, pt3, pt1, cv::Scalar(255, 255, 255), 2, cv::LINE_AA);
            }
            drawOutlinedText(outSimplexOverlap, "Simplex Health + Delaunay Overlap", cv::Point(10, 45), 0.4);
            cv::imwrite(local_debug_dir + "/simplex_mesh_overlap.png", outSimplexOverlap);

            std::string csvPath = local_debug_dir + "/debug_grid_data.csv";
            std::ofstream csvFile(csvPath);
            if (csvFile.is_open()) {
                csvFile << "# PIPELINE=2_HYBRID_CORE\n";
                csvFile << "RealX,RealY,GridX,GridY,ThreadID,ComputeOrder,U,V,Correlation,MeshType,UsedSimplex,ItersICGN,SolverState,GuessU,GuessV,GuessUx,GuessUy,GuessVx,GuessVy\n";
                for (int y = 0; y < gridH; ++y) {
                    for (int x = 0; x < gridW; ++x) {
                        const auto &gp = resultGrid[y][x];

                        bool is_masked = (!roiMask.empty() && roiMask.at<uchar>(rectY + y * step, rectX + x * step) < 128);
                        if (is_masked) continue;

                        int solver_state = 0;
                        if (gp.solved && gp.corr > 0.0f) {
                            if (!gp.used_simplex) solver_state = 0;
                            else if (gp.icgn_iters < 20) solver_state = 1;
                            else solver_state = 2;
                        } else {
                            if (!gp.used_simplex) solver_state = 5;
                            else if (gp.icgn_iters < 20) solver_state = 3;
                            else solver_state = 4;
                        }

                        // 🚀 UPDATE THE EXPORT LINE
                        csvFile << gp.x << "," << gp.y << "," << x << "," << y << ","
                                << gp.thread_id << "," << gp.compute_order << "," << gp.u
                                << "," << gp.v << "," << gp.corr << ","
                                << gp.mesh_assignment_type << ","
                                << (gp.used_simplex ? 1 : 0) << ","
                                << gp.icgn_iters << ","
                                << solver_state << ","
                                << gp.guess_u << "," << gp.guess_v << ","
                                << gp.guess_ux << "," << gp.guess_uy << ","
                                << gp.guess_vx << "," << gp.guess_vy << "\n";
                    }
                }
                csvFile.close();
            }

            if (!affTriangles.empty()) {
                std::string meshCsvPath = local_debug_dir + "/delaunay_mesh_data.csv";
                std::ofstream meshCsvFile(meshCsvPath);
                if (meshCsvFile.is_open()) {
                    meshCsvFile << "TriangleID,Pt1_X,Pt1_Y,Pt2_X,Pt2_Y,Pt3_X,Pt3_Y\n";
                    for (size_t i = 0; i < affTriangles.size(); ++i) {
                        const auto &tri = affTriangles[i];
                        meshCsvFile << i << ","
                                    << tri.pts[0].x << "," << tri.pts[0].y << ","
                                    << tri.pts[1].x << "," << tri.pts[1].y << ","
                                    << tri.pts[2].x << "," << tri.pts[2].y << "\n";
                    }
                    meshCsvFile.close();
                }
            }

            try {
                cv::Mat refFloat(g_refHeight, g_refWidth, CV_32FC1, (void *)g_refImg->intensities.data());
                cv::Mat ref8U, refColor;
                refFloat.convertTo(ref8U, CV_8UC1);
                cv::cvtColor(ref8U, refColor, cv::COLOR_GRAY2BGR);

                cv::Rect roiRect(rectX, rectY, rectWidth, rectHeight);
                roiRect = roiRect & cv::Rect(0, 0, g_refWidth, g_refHeight);

                cv::Mat imgAll = refColor(roiRect).clone();
                cv::Mat imgDead = imgAll.clone();

                int shiftX = roiRect.x;
                int shiftY = roiRect.y;

                cv::Scalar meshColor(214, 174, 107);
                for (const auto &tri : affTriangles) {
                    cv::Point pt1(tri.pts[0].x - shiftX, tri.pts[0].y - shiftY);
                    cv::Point pt2(tri.pts[1].x - shiftX, tri.pts[1].y - shiftY);
                    cv::Point pt3(tri.pts[2].x - shiftX, tri.pts[2].y - shiftY);

                    cv::line(imgAll, pt1, pt2, meshColor, 1, cv::LINE_AA);
                    cv::line(imgAll, pt2, pt3, meshColor, 1, cv::LINE_AA);
                    cv::line(imgAll, pt3, pt1, meshColor, 1, cv::LINE_AA);

                    cv::line(imgDead, pt1, pt2, meshColor, 1, cv::LINE_AA);
                    cv::line(imgDead, pt2, pt3, meshColor, 1, cv::LINE_AA);
                    cv::line(imgDead, pt3, pt1, meshColor, 1, cv::LINE_AA);
                }

                for (int y = 0; y < gridH; ++y) {
                    for (int x = 0; x < gridW; ++x) {
                        const auto &gp = resultGrid[y][x];
                        bool is_masked = (!roiMask.empty() && roiMask.at<uchar>(rectY + y * step, rectX + x * step) < 128);
                        if (is_masked) continue;

                        int solver_state = 0;
                        if (gp.solved && gp.corr > 0.0f) {
                            if (!gp.used_simplex) solver_state = 0;
                            else if (gp.icgn_iters < 20) solver_state = 1;
                            else solver_state = 2;
                        } else {
                            if (!gp.used_simplex) solver_state = 5;
                            else if (gp.icgn_iters < 20) solver_state = 3;
                            else solver_state = 4;
                        }

                        if (solver_state == 0) continue;

                        cv::Point pt(gp.x - shiftX, gp.y - shiftY);
                        int radius = 4;
                        int markerSize = 10;
                        int thickness = 2;

                        if (solver_state == 1) {
                            cv::circle(imgAll, pt, radius, cv::Scalar(0, 255, 255), -1, cv::LINE_AA);
                            cv::circle(imgAll, pt, radius, cv::Scalar(0, 0, 0), 1, cv::LINE_AA);
                        }
                        else if (solver_state == 2) {
                            cv::circle(imgAll, pt, radius, cv::Scalar(255, 255, 0), -1, cv::LINE_AA);
                            cv::circle(imgAll, pt, radius, cv::Scalar(0, 0, 0), 1, cv::LINE_AA);
                        }
                        else if (solver_state == 3) {
                            cv::Scalar col(0, 165, 255);
                            cv::drawMarker(imgAll, pt, col, cv::MARKER_TILTED_CROSS, markerSize, thickness, cv::LINE_AA);
                            cv::drawMarker(imgDead, pt, col, cv::MARKER_TILTED_CROSS, markerSize, thickness, cv::LINE_AA);
                        }
                        else if (solver_state == 4) {
                            cv::Scalar col(255, 0, 255);
                            cv::drawMarker(imgAll, pt, col, cv::MARKER_TILTED_CROSS, markerSize, thickness, cv::LINE_AA);
                            cv::drawMarker(imgDead, pt, col, cv::MARKER_TILTED_CROSS, markerSize, thickness, cv::LINE_AA);
                        }
                        else if (solver_state == 5) {
                            cv::Scalar col(0, 0, 255);
                            cv::drawMarker(imgAll, pt, col, cv::MARKER_TILTED_CROSS, markerSize, thickness, cv::LINE_AA);
                            cv::drawMarker(imgDead, pt, col, cv::MARKER_TILTED_CROSS, markerSize, thickness, cv::LINE_AA);
                        }
                    }
                }

                drawOutlinedText(imgAll, "ALL Simplex Interventions vs. Mesh", cv::Point(10, 25), 0.6);
                drawOutlinedText(imgDead, "ONLY Dead Points vs. Mesh", cv::Point(10, 25), 0.6);

                cv::imwrite(local_debug_dir + "/mesh_overlap_ALL_simplex.jpg", imgAll);
                cv::imwrite(local_debug_dir + "/mesh_overlap_ONLY_dead.jpg", imgDead);

            } catch (...) {
                LOGE("Failed to generate C++ Mesh Overlap plots");
            }
        } catch (...) { LOGE("Unknown Exception during Debug Export"); }
    }

    // ==========================================
    // 🚀 NEW: FULL ENGINE TELEMETRY EXPORT TO KOTLIN
    // ==========================================
    float avg_iters = 0.0f;
    if (valid_count > 0) {
        avg_iters = (float)total_icgn_iters / (float)valid_count;
    }

    if (out_metrics != nullptr) {
        // Ensure the array from Kotlin is large enough (we need 16 slots)
        if (env->GetArrayLength(out_metrics) >= 16) {
            jfloat metrics_data[16];

            // 0-4: Point Counts
            metrics_data[0] = (float)total_valid_points;      // Total Attempted
            metrics_data[1] = (float)valid_count;             // Total Solved
            metrics_data[2] = (float)(total_valid_points - valid_count); // Total Rejected
            metrics_data[3] = (float)pathA_pts;               // Solved via Mesh
            metrics_data[4] = (float)pathB_pts;               // Solved via Flood Fill

            // 5-8: Simplex Stats
            metrics_data[5] = (float)tot_simp_calls;          // Total Simplex Rescues
            metrics_data[6] = (float)tot_simp_saved;          // Rescues that Succeeded
            metrics_data[7] = (float)tot_simp_dead;           // Rescues that Failed
            metrics_data[8] = avg_iters;                      // Mean ICGN Iterations

            // 9-13: Timestamps & Performance (ms)
            metrics_data[9]  = (float)time_total;             // Total Wall Time
            metrics_data[10] = (float)(time_akaze + time_ransac); // AKAZE/RANSAC Time
            metrics_data[11] = (float)time_prepass;           // Hessian Pre-Pass Time
            metrics_data[12] = (float)time_delaunay;          // Mesh Setup Time
            metrics_data[13] = (float)time_strain;            // Strain Calc Time

            // 14-15: Ratios
            metrics_data[14] = (time_pathA > 0) ? (float)(pathA_pts / time_pathA) : 0.0f; // Throughput Pts/ms
            metrics_data[15] = (total_valid_points > 0) ? ((float)valid_count / total_valid_points) * 100.0f : 0.0f; // Convergence %

            env->SetFloatArrayRegion(out_metrics, 0, 16, metrics_data);
        } else {
            LOGE("out_metrics array from Kotlin is too small! Expected 16, got %d", env->GetArrayLength(out_metrics));
        }
    }

    defMat.release(); roiMask.release();
    return (jint)valid_count;
}
} // extern "C"