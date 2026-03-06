#include <android/bitmap.h>
#include <android/log.h>
#include <atomic>
#include <chrono>
#include <fstream>
#include <jni.h>
#include <mutex>
#include <omp.h>
#include <opencv2/features2d.hpp>
#include <opencv2/calib3d.hpp>
#include <opencv2/opencv.hpp>
#include <queue>
#include <string>
#include <thread>
#include <vector>
#include <condition_variable>
#include <exception>
#include <memory>

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

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    LOGD("IndicVision Native Library Loaded.");
    cv::setNumThreads(1);
    return JNI_VERSION_1_6;
}

static IndicVision::Image *g_refImg = nullptr;
static int g_refWidth = 0;
static int g_refHeight = 0;
static std::mutex jni_engine_mutex;
static std::string g_debugDir = "";

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
bool extractAkazeFeatures(cv::Mat& ref, cv::Mat& def,
                          std::vector<cv::Point2f>& out_ref_pts,
                          std::vector<cv::Point2f>& out_def_pts,
                          float& out_bounding_box_area_ratio,
                          double& out_akaze_ms, double& out_ransac_ms,
                          const std::string& debugDir = "") {

    auto t_start_akaze = std::chrono::high_resolution_clock::now();

    const double scale = 0.25;
    cv::Mat smallRef, smallDef;
    cv::resize(ref, smallRef, cv::Size(), scale, scale, cv::INTER_NEAREST);
    cv::resize(def, smallDef, cv::Size(), scale, scale, cv::INTER_NEAREST);

    auto detector = cv::AKAZE::create();
    std::vector<cv::KeyPoint> kp1, kp2;
    cv::Mat desc1, desc2;

    detector->detectAndCompute(smallRef, cv::noArray(), kp1, desc1);
    detector->detectAndCompute(smallDef, cv::noArray(), kp2, desc2);

    if (!debugDir.empty() && !smallRef.empty() && !kp1.empty()) {
        try {
            cv::Mat refFeaturesImg;
            cv::drawKeypoints(smallRef, kp1, refFeaturesImg, cv::Scalar(0, 0, 255), cv::DrawMatchesFlags::DEFAULT);
            drawOutlinedText(refFeaturesImg, "Detected Ref Features: " + std::to_string(kp1.size()), cv::Point(10, 20), 0.6);
            cv::imwrite(debugDir + "/akaze_ref_features.jpg", refFeaturesImg);
        } catch(...) {}
    }

    if(kp1.empty() || kp2.empty()) {
        out_akaze_ms = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_start_akaze).count();
        return false;
    }

    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<std::vector<cv::DMatch>> matches;
    matcher.knnMatch(desc1, desc2, matches, 2);

    std::vector<cv::Point2f> p1, p2;
    std::vector<cv::DMatch> good_matches;
    for (auto& m : matches) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            p1.push_back(kp1[m[0].queryIdx].pt);
            p2.push_back(kp2[m[0].trainIdx].pt);
            good_matches.push_back(m[0]);
        }
    }

    auto t_end_akaze = std::chrono::high_resolution_clock::now();
    out_akaze_ms = std::chrono::duration<double, std::milli>(t_end_akaze - t_start_akaze).count();

    if (p1.size() < 10) return false;

    auto t_start_ransac = std::chrono::high_resolution_clock::now();

    std::vector<uchar> inlier_mask;
    cv::findHomography(p1, p2, cv::RANSAC, 3.0, inlier_mask);

    out_ref_pts.clear();
    out_def_pts.clear();
    std::vector<cv::KeyPoint> matched_kp2;
    std::vector<cv::DMatch> ransac_matches;

    for (size_t i = 0; i < inlier_mask.size(); ++i) {
        if (inlier_mask[i]) {
            out_ref_pts.push_back(cv::Point2f(p1[i].x / scale, p1[i].y / scale));
            out_def_pts.push_back(cv::Point2f(p2[i].x / scale, p2[i].y / scale));
            if (!debugDir.empty()) {
                matched_kp2.push_back(kp2[good_matches[i].trainIdx]);
                ransac_matches.push_back(good_matches[i]);
            }
        }
    }

    if (!out_ref_pts.empty()) {
        cv::Rect bb = cv::boundingRect(out_ref_pts);
        float bb_area = bb.width * bb.height;
        float total_area = ref.cols * ref.rows;
        out_bounding_box_area_ratio = bb_area / total_area;
    }

    auto t_end_ransac = std::chrono::high_resolution_clock::now();
    out_ransac_ms = std::chrono::duration<double, std::milli>(t_end_ransac - t_start_ransac).count();

    if (!debugDir.empty() && !smallRef.empty() && !smallDef.empty()) {
        try {
            cv::Mat defMatchesImg;
            cv::drawKeypoints(smallDef, matched_kp2, defMatchesImg, cv::Scalar(0, 255, 0), cv::DrawMatchesFlags::DEFAULT);
            drawOutlinedText(defMatchesImg, "RANSAC Matched: " + std::to_string(out_def_pts.size()), cv::Point(10, 20), 0.6);
            cv::imwrite(debugDir + "/akaze_def_matches.jpg", defMatchesImg);

            cv::Mat matchImg;
            cv::drawMatches(smallRef, kp1, smallDef, kp2, ransac_matches, matchImg,
                            cv::Scalar(0, 255, 0), cv::Scalar(255, 100, 0), std::vector<char>(),
                            cv::DrawMatchesFlags::NOT_DRAW_SINGLE_POINTS);
            cv::imwrite(debugDir + "/akaze_matches_lines.jpg", matchImg);
        } catch(...) {}
    }

    return out_ref_pts.size() >= 10;
}

extern "C" {

JNIEXPORT void JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_setDebugOutputDir(JNIEnv *env, jobject, jstring debugDir) {
    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);
    if (debugDir == nullptr) {
        g_debugDir = "";
        return;
    }
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

JNIEXPORT jintArray JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_getImageDimensions(JNIEnv *env, jobject, jbyteArray fileData) {
    jsize len = env->GetArrayLength(fileData);
    jbyte *buf = env->GetByteArrayElements(fileData, nullptr);
    cv::Mat rawData(1, len, CV_8UC1, (void *)buf);
    cv::Mat img = cv::imdecode(rawData, cv::IMREAD_UNCHANGED);
    env->ReleaseByteArrayElements(fileData, buf, JNI_ABORT);
    jintArray result = env->NewIntArray(2);
    if (img.empty()) { jint temp[] = {0, 0}; env->SetIntArrayRegion(result, 0, 2, temp); return result; }
    jint temp[] = {img.cols, img.rows};
    env->SetIntArrayRegion(result, 0, 2, temp);
    return result;
}

JNIEXPORT void JNICALL Java_com_rafad_indicvisiondic_IndicVisionNativeLib_initializeReference(
        JNIEnv *env, jobject, jbyteArray refBytes, jint width, jint height, jboolean applyBlur) {
    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);
    if (g_refImg != nullptr) { delete g_refImg; g_refImg = nullptr; }
    if (refBytes == nullptr) return;

    cv::Mat refMat = bytesToMat(env, refBytes, width, height);
    if (refMat.empty()) return;
    if (applyBlur) cv::GaussianBlur(refMat, refMat, cv::Size(7, 7), 0);

    g_refWidth = refMat.cols;
    g_refHeight = refMat.rows;
    g_refImg = new IndicVision::Image(g_refWidth, g_refHeight, refMat.data);
    g_refImg->prepare_data();
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
    refImg.prepare_data(); defImg.prepare_data();
    IndicVision::SubsetData subset;
    IndicVision::SubsetPrecomputer::precompute_subset(subset, refImg, roiX, roiY, subsetSize);
    IndicVision::OptimizationEngine engine;
    IndicVision::AnalysisResult res = engine.calculate_deformation(
            subset, defImg, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, IndicVision::INIT_AUTO_SEARCH);
    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5] = {res.u, res.v, 0.0f, res.ux, (jfloat)res.status};
    env->SetFloatArrayRegion(output, 0, 5, temp);
    return output;
}

// ==========================================
// 🚀 PHASE 1 & 2: ROUTING ENGINE (COMPUTE FULL FIELD)
// ==========================================
JNIEXPORT jint JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeFullFieldDirect(
        JNIEnv *env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jbyteArray maskBytes, jint rectX, jint rectY, jint rectWidth,
        jint rectHeight, jint step, jint subsetSize, jint strainWindow,
        jboolean useDelaunay, jboolean useFallback, jboolean useRGDIC,
        jboolean applyGaussianBlur, jboolean useNlvcStrain, jobject outputBuffer,
        jobject callbackObj) {

    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);

    // 🔒 FIX 5: Capture and clear g_debugDir atomically at entry.
    std::string local_debug_dir = g_debugDir;
    g_debugDir = "";

    auto t_total_start = std::chrono::high_resolution_clock::now();
    double time_img_prep = 0, time_akaze = 0, time_ransac = 0;
    double time_delaunay = 0, time_contour_assign = 0, time_extrapolate = 0, time_smoothing = 0;
    double time_prepass = 0, time_pathA = 0, time_pathB = 0, time_strain = 0;

    if (env == nullptr || defBytes == nullptr || outputBuffer == nullptr || g_refImg == nullptr) return 0;
    float *output_ptr = (float *)env->GetDirectBufferAddress(outputBuffer);
    if (!output_ptr) return 0;

    static int s_frame_count = 0; s_frame_count++;
    LOGD("=== FRAME %d computeFullFieldDirect START ===", s_frame_count);

    auto t_prep_start = std::chrono::high_resolution_clock::now();
    cv::Mat defMat = bytesToMat(env, defBytes, g_refWidth, g_refHeight);
    if (defMat.empty()) return 0;

    cv::Mat roiMask;
    if (maskBytes != nullptr && env->GetArrayLength(maskBytes) > 0) {
        roiMask = bytesToMat(env, maskBytes);
        if (!roiMask.empty() && (roiMask.cols != g_refWidth || roiMask.rows != g_refHeight)) {
            cv::resize(roiMask, roiMask, cv::Size(g_refWidth, g_refHeight), 0, 0, cv::INTER_NEAREST);
        }
    }

    if (applyGaussianBlur) cv::GaussianBlur(defMat, defMat, cv::Size(7, 7), 0);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    defImg.prepare_data();
    time_img_prep = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_prep_start).count();

    std::vector<cv::Point2f> akaze_ref_pts;
    std::vector<cv::Point2f> akaze_def_pts;
    float inlier_bb_area_ratio = 0.0f;
    bool has_good_akaze = false;
    float globalU = 0.0f, globalV = 0.0f;

    if (!useRGDIC) {
        int padding = 160;
        cv::Rect padded_roi(rectX - padding, rectY - padding, rectWidth + 2*padding, rectHeight + 2*padding);
        padded_roi = padded_roi & cv::Rect(0, 0, g_refWidth, g_refHeight);

        if (padded_roi.width > 32 && padded_roi.height > 32) {
            try {
                cv::Mat refMat = bytesToMat(env, refBytes, g_refWidth, g_refHeight);
                if (!refMat.empty()) {
                    cv::Mat refROI = refMat(padded_roi);
                    cv::Mat defROI = defMat(padded_roi);

                    if (!local_debug_dir.empty()) {
                        cv::imwrite(local_debug_dir + "/akaze_input_ROI_ref.jpg", refROI);
                        cv::imwrite(local_debug_dir + "/akaze_input_ROI_def.jpg", defROI);
                    }

                    bool success = extractAkazeFeatures(refROI, defROI, akaze_ref_pts, akaze_def_pts,
                                                        inlier_bb_area_ratio, time_akaze, time_ransac, local_debug_dir);

                    if (success) {
                        for (size_t i = 0; i < akaze_ref_pts.size(); ++i) {
                            akaze_ref_pts[i].x += padded_roi.x;
                            akaze_ref_pts[i].y += padded_roi.y;
                            akaze_def_pts[i].x += padded_roi.x;
                            akaze_def_pts[i].y += padded_roi.y;
                        }
                        std::vector<float> us, vs;
                        for(size_t i=0; i<akaze_ref_pts.size(); i++){
                            us.push_back(akaze_def_pts[i].x - akaze_ref_pts[i].x);
                            vs.push_back(akaze_def_pts[i].y - akaze_ref_pts[i].y);
                        }
                        std::sort(us.begin(), us.end());
                        std::sort(vs.begin(), vs.end());
                        globalU = us[us.size()/2];
                        globalV = vs[vs.size()/2];

                        if (akaze_ref_pts.size() >= 25 && inlier_bb_area_ratio > 0.3f) {
                            has_good_akaze = true;
                        }
                    }
                }
            } catch (const cv::Exception &e) {
                LOGE("AKAZE Exception: %s", e.what());
            }
        }
    }

    bool execute_path_A = false;
    bool execute_path_B = false;

    if (useRGDIC) {
        LOGD("ROUTING: Strict RGDIC forced. Jumping to Path B.");
        execute_path_B = true;
    } else if (useDelaunay) {
        if (has_good_akaze) {
            LOGD("ROUTING: Mesh conditions met (%zu points, %.1f%% area). Executing Hybrid Mode.", akaze_ref_pts.size(), inlier_bb_area_ratio * 100);
            execute_path_A = true;
            execute_path_B = true;
        } else {
            if (useFallback) {
                LOGD("ROUTING: AKAZE Sparse. Fallback enabled. Jumping to Path B (RGDIC).");
                execute_path_B = true;
            } else {
                LOGE("ROUTING: AKAZE Failed. Fallback disabled. Aborting.");
                defMat.release(); roiMask.release();
                return 0;
            }
        }
    } else {
        execute_path_B = true;
    }

    int gridW = rectWidth / step;
    int gridH = rectHeight / step;
    if (gridW <= 0 || gridH <= 0) return 0;

    struct GridPoint {
        float x, y, u, v, ux, uy, vx, vy, corr;
        bool solved;
        int thread_id;
        int compute_order;
        int mesh_assignment_type; // 0=None, 1=Strict Inside, 2=Extrapolated
    };
    std::vector<std::vector<GridPoint>> resultGrid(gridH, std::vector<GridPoint>(gridW));
    int total_valid_points = 0;

    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            int realX = rectX + x * step;
            int realY = rectY + y * step;
            bool shouldSkip = (!roiMask.empty() && roiMask.at<uchar>(realY, realX) < 128);
            if (!shouldSkip) total_valid_points++;
            resultGrid[y][x] = {(float)realX, (float)realY, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, shouldSkip, -1, -1, 0};
        }
    }

    if (total_valid_points == 0) return 0;

    std::atomic<int> global_points_solved(0);
    std::atomic<int> compute_order_counter(1); // Start at 1 to easily separate Path A/B logic
    int safe_cores = std::max(1, (int)std::thread::hardware_concurrency());
    std::vector<double> t_icgn_arr(safe_cores, 0.0), t_simplex_arr(safe_cores, 0.0), t_hessian_arr(safe_cores, 0.0);
    std::vector<int> c_icgn_arr(safe_cores, 0), c_simplex_arr(safe_cores, 0), c_points_arr(safe_cores, 0);

    // =========================================================
    // 🔒 RAII GUARD: Fixes Crash Bug #1 (Leaking references)
    // =========================================================
    struct JniGlobalRefGuard {
        JNIEnv* env;
        jobject& ref;
        ~JniGlobalRefGuard() {
            if (ref != nullptr) {
                env->DeleteGlobalRef(ref);
                ref = nullptr;
            }
        }
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

    // =========================================================
    // 🔒 RAII THREAD GUARD: Fixes OOM Thread Destructor crash
    // =========================================================
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
        std::thread& t;
        std::atomic<bool>& stop_flag;
        std::condition_variable& cv;
        std::mutex& cv_mutex;
        bool& detached_flag;

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

    // 🚀 Instantiated safely immediately after thread spawn
    ThreadJoinGuard progressGuard{
            progress_thread, progress_thread_should_stop,
            progress_cv, progress_cv_mutex, progress_thread_detached
    };

    std::vector<float> guessU(gridW * gridH, globalU);
    std::vector<float> guessV(gridW * gridH, globalV);
    std::vector<float> guessUx(gridW * gridH, 0.0f);
    std::vector<float> guessUy(gridW * gridH, 0.0f);
    std::vector<float> guessVx(gridW * gridH, 0.0f);
    std::vector<float> guessVy(gridW * gridH, 0.0f);
    std::vector<bool> inMesh(gridW * gridH, false);

    // ==========================================
    // 🚀 PHASE 3: PATH A (DELAUNAY MESH)
    // ==========================================
    if (execute_path_A) {
        auto t_mesh_start = std::chrono::high_resolution_clock::now();

        cv::Subdiv2D subdiv(cv::Rect(0, 0, g_refWidth, g_refHeight));
        for(size_t i=0; i<akaze_ref_pts.size(); i++) {
            if(akaze_ref_pts[i].x > 0 && akaze_ref_pts[i].x < g_refWidth &&
               akaze_ref_pts[i].y > 0 && akaze_ref_pts[i].y < g_refHeight) {
                subdiv.insert(akaze_ref_pts[i]);
            }
        }

        std::vector<cv::Vec6f> triangleList;
        subdiv.getTriangleList(triangleList);

        struct AffineTriangle {
            cv::Point2f pts[3];
            double u, v, ux, uy, vx, vy;
            cv::Rect2f boundingBox;
        };
        std::vector<AffineTriangle> affTriangles;

        auto getDefPt = [&](cv::Point2f pt) -> cv::Point2f {
            float min_dist = 1e9;
            cv::Point2f best_pt = pt;
            for(size_t i=0; i<akaze_ref_pts.size(); i++){
                float d = (akaze_ref_pts[i].x - pt.x)*(akaze_ref_pts[i].x - pt.x) +
                          (akaze_ref_pts[i].y - pt.y)*(akaze_ref_pts[i].y - pt.y);
                if(d < min_dist) { min_dist = d; best_pt = akaze_def_pts[i]; }
            }
            return best_pt;
        };

        try {
            for(size_t i = 0; i < triangleList.size(); i++) {
                cv::Vec6f t = triangleList[i];
                cv::Point2f pt[3];
                pt[0] = cv::Point2f(t[0], t[1]);
                pt[1] = cv::Point2f(t[2], t[3]);
                pt[2] = cv::Point2f(t[4], t[5]);

                if (pt[0].x < 0 || pt[0].x >= g_refWidth || pt[1].x < 0 || pt[1].x >= g_refWidth || pt[2].x < 0 || pt[2].x >= g_refWidth) continue;

                cv::Point2f dst[3];
                dst[0] = getDefPt(pt[0]); dst[1] = getDefPt(pt[1]); dst[2] = getDefPt(pt[2]);

                cv::Mat warp_mat = cv::getAffineTransform(pt, dst);
                AffineTriangle at;
                at.pts[0] = pt[0]; at.pts[1] = pt[1]; at.pts[2] = pt[2];

                at.u = warp_mat.at<double>(0,2);
                at.v = warp_mat.at<double>(1,2);
                at.ux = warp_mat.at<double>(0,0) - 1.0;
                at.uy = warp_mat.at<double>(0,1);
                at.vx = warp_mat.at<double>(1,0);
                at.vy = warp_mat.at<double>(1,1) - 1.0;

                float minX = std::min({pt[0].x, pt[1].x, pt[2].x});
                float maxX = std::max({pt[0].x, pt[1].x, pt[2].x});
                float minY = std::min({pt[0].y, pt[1].y, pt[2].y});
                float maxY = std::max({pt[0].y, pt[1].y, pt[2].y});
                at.boundingBox = cv::Rect2f(minX - 15.0f, minY - 15.0f, (maxX - minX) + 30.0f, (maxY - minY) + 30.0f);
                affTriangles.push_back(at);
            }
        } catch (const cv::Exception& e) {
            LOGE("Delaunay Warp Exception: %s", e.what());
        }

        if (!local_debug_dir.empty()) {
            try {
                cv::Mat refFloat(g_refHeight, g_refWidth, CV_32FC1, (void*)g_refImg->intensities.data());
                cv::Mat ref8U, refColor;
                refFloat.convertTo(ref8U, CV_8UC1);
                cv::cvtColor(ref8U, refColor, cv::COLOR_GRAY2BGR);
                cv::Rect roiRect(rectX, rectY, rectWidth, rectHeight);
                roiRect = roiRect & cv::Rect(0, 0, g_refWidth, g_refHeight);
                cv::Mat meshDebug = refColor(roiRect).clone();
                for (const auto& tri : affTriangles) {
                    cv::Point pt1(tri.pts[0].x - rectX, tri.pts[0].y - rectY);
                    cv::Point pt2(tri.pts[1].x - rectX, tri.pts[1].y - rectY);
                    cv::Point pt3(tri.pts[2].x - rectX, tri.pts[2].y - rectY);
                    cv::line(meshDebug, pt1, pt2, cv::Scalar(255, 255, 0), 1, cv::LINE_AA);
                    cv::line(meshDebug, pt2, pt3, cv::Scalar(255, 255, 0), 1, cv::LINE_AA);
                    cv::line(meshDebug, pt3, pt1, cv::Scalar(255, 255, 0), 1, cv::LINE_AA);
                }
                for(const auto& pt : akaze_ref_pts) {
                    cv::Point local_pt(pt.x - rectX, pt.y - rectY);
                    cv::circle(meshDebug, local_pt, 4, cv::Scalar(0, 0, 255), -1, cv::LINE_AA);
                }
                drawOutlinedText(meshDebug, "Delaunay 6-DOF Mesh (" + std::to_string(affTriangles.size()) + " Triangles)", cv::Point(10, 25), 0.6);
                cv::imwrite(local_debug_dir + "/delaunay_mesh_debug.jpg", meshDebug);
            } catch (...) {}
        }

        time_delaunay = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_mesh_start).count();

        auto t_assign_start = std::chrono::high_resolution_clock::now();
        std::vector<std::vector<cv::Point2f>> triContours;
        triContours.reserve(affTriangles.size());
        for (const auto& tri : affTriangles) {
            triContours.push_back({ tri.pts[0], tri.pts[1], tri.pts[2] });
        }

        // Pass 1: Exact interior
        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                if (resultGrid[y][x].solved) continue;
                cv::Point2f gp(rectX + x * step, rectY + y * step);
                int idx = y * gridW + x;
                for (size_t ti = 0; ti < affTriangles.size(); ++ti) {
                    const auto& tri = affTriangles[ti];
                    if (gp.x < tri.boundingBox.x || gp.x > tri.boundingBox.x + tri.boundingBox.width  ||
                        gp.y < tri.boundingBox.y || gp.y > tri.boundingBox.y + tri.boundingBox.height) continue;

                    if (cv::pointPolygonTest(triContours[ti], gp, false) >= 0) {
                        guessU[idx]  = (float)(tri.ux * gp.x + tri.uy * gp.y + tri.u);
                        guessV[idx]  = (float)(tri.vx * gp.x + tri.vy * gp.y + tri.v);
                        guessUx[idx] = (float)tri.ux; guessUy[idx] = (float)tri.uy;
                        guessVx[idx] = (float)tri.vx; guessVy[idx] = (float)tri.vy;
                        inMesh[idx]  = true;
                        resultGrid[y][x].mesh_assignment_type = 1;
                        break;
                    }
                }
            }
        }
        time_contour_assign = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_assign_start).count();

        // Pass 2: Bounded Extrapolation
        auto t_extrap_start = std::chrono::high_resolution_clock::now();
        constexpr float EXTRAP_LIMIT = -15.0f;
        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                int idx = y * gridW + x;
                if (inMesh[idx] || resultGrid[y][x].solved) continue;

                cv::Point2f gp(rectX + x * step, rectY + y * step);
                float best_dist = EXTRAP_LIMIT - 1.0f;
                int   best_ti   = -1;

                for (size_t ti = 0; ti < affTriangles.size(); ++ti) {
                    const auto& tri = affTriangles[ti];
                    if (gp.x < tri.boundingBox.x + EXTRAP_LIMIT || gp.x > tri.boundingBox.x + tri.boundingBox.width  - EXTRAP_LIMIT ||
                        gp.y < tri.boundingBox.y + EXTRAP_LIMIT || gp.y > tri.boundingBox.y + tri.boundingBox.height - EXTRAP_LIMIT) continue;

                    double dist = cv::pointPolygonTest(triContours[ti], gp, true);
                    if (dist >= EXTRAP_LIMIT && (float)dist > best_dist) {
                        best_dist = (float)dist;
                        best_ti   = (int)ti;
                    }
                }

                if (best_ti >= 0) {
                    const auto& tri = affTriangles[best_ti];
                    guessU[idx]  = (float)(tri.ux * gp.x + tri.uy * gp.y + tri.u);
                    guessV[idx]  = (float)(tri.vx * gp.x + tri.vy * gp.y + tri.v);
                    guessUx[idx] = (float)tri.ux; guessUy[idx] = (float)tri.uy;
                    guessVx[idx] = (float)tri.vx; guessVy[idx] = (float)tri.vy;
                    inMesh[idx]  = true;
                    resultGrid[y][x].mesh_assignment_type = 2;
                }
            }
        }
        time_extrapolate = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_extrap_start).count();

        auto t_smooth_start = std::chrono::high_resolution_clock::now();
        auto smoothGrid = [&](std::vector<float>& grid, int radius) {
            std::vector<float> temp = grid;
            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    if (!inMesh[y * gridW + x]) continue;
                    float sum = 0.0f; int count = 0;
                    for (int dy = -radius; dy <= radius; ++dy) {
                        for (int dx = -radius; dx <= radius; ++dx) {
                            int ny = y + dy, nx = x + dx;
                            if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                                if (inMesh[ny * gridW + nx]) {
                                    sum += temp[ny * gridW + nx];
                                    count++;
                                }
                            }
                        }
                    }
                    if (count > 0) grid[y * gridW + x] = sum / count;
                }
            }
        };

        smoothGrid(guessU, 2); smoothGrid(guessV, 2);
        smoothGrid(guessUx, 2); smoothGrid(guessUy, 2);
        smoothGrid(guessVx, 2); smoothGrid(guessVy, 2);
        time_smoothing = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_smooth_start).count();
    }

    // =========================================================
    // 🚀 GLOBAL HESSIAN PRE-PASS
    // =========================================================
    auto t_prepass_start = std::chrono::high_resolution_clock::now();
    std::vector<IndicVision::CachedHessianData, Eigen::aligned_allocator<IndicVision::CachedHessianData>> hessian_pool(gridW * gridH);

#pragma omp parallel for schedule(static) num_threads(safe_cores)
    for (int pool_idx = 0; pool_idx < gridW * gridH; ++pool_idx) {
        try {
            int gx = pool_idx % gridW, gy = pool_idx / gridW;
            if (resultGrid[gy][gx].solved) continue;
            int realX = rectX + gx * step, realY = rectY + gy * step;
            hessian_pool[pool_idx] = IndicVision::SubsetPrecomputer::compute_hessian_only(*g_refImg, realX, realY, subsetSize);
        } catch (...) {}
    }
    time_prepass = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_prepass_start).count();

    if (execute_path_A) {
        auto t_pathA_start = std::chrono::high_resolution_clock::now();
        std::atomic<bool> omp_region_threw(false);

#pragma omp parallel num_threads(safe_cores)
        {
            int tid = omp_get_thread_num();
            IndicVision::OptimizationEngine local_engine;
            IndicVision::SubsetData local_subset;
            double local_hessian = 0.0;
            int local_pts = 0;

#pragma omp for schedule(dynamic, 32)
            for (int idx = 0; idx < gridW * gridH; ++idx) {
                try {
                    if (omp_region_threw.load(std::memory_order_relaxed)) continue;
                    if (!inMesh[idx]) continue;
                    int x = idx % gridW, y = idx / gridW;
                    if (resultGrid[y][x].solved) continue;

                    int realX = rectX + x * step, realY = rectY + y * step;

                    auto th1 = std::chrono::high_resolution_clock::now();
                    IndicVision::SubsetPrecomputer::precompute_subset_fast(local_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[idx]);
                    auto th2 = std::chrono::high_resolution_clock::now();
                    local_hessian += std::chrono::duration<double, std::milli>(th2 - th1).count();

                    if (local_subset.is_initialized) {
                        IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                local_subset, defImg, guessU[idx], guessV[idx],
                                guessUx[idx], guessUy[idx], guessVx[idx], guessVy[idx],
                                IndicVision::INIT_NO_SEARCH);

                        if (res.status == 0 && res.correlation_score <= 0.15f) {
                            int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                            resultGrid[y][x] = {(float)realX, (float)realY, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score, true, tid, order, resultGrid[y][x].mesh_assignment_type};
                            global_points_solved.fetch_add(1, std::memory_order_relaxed);
                            local_pts++;
                        } else {
                            resultGrid[y][x].solved = false; resultGrid[y][x].corr = 0.0f;
                        }
                    }
                } catch (...) {
                    omp_region_threw.store(true, std::memory_order_relaxed);
                }
            }
            t_icgn_arr[tid] = local_engine.time_icgn_ms;
            t_simplex_arr[tid] = local_engine.time_simplex_ms;
            t_hessian_arr[tid] = local_hessian;
            c_icgn_arr[tid] = local_engine.count_icgn;
            c_simplex_arr[tid] = local_engine.count_simplex;
            c_points_arr[tid] = local_pts;
        }
        time_pathA = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_pathA_start).count();
    }

    std::unique_ptr<std::atomic<bool>[]> cell_claimed(new std::atomic<bool>[gridW * gridH]);
    for (int i = 0; i < gridW * gridH; ++i) {
        int gx = i % gridW, gy = i / gridW;
        cell_claimed[i].store(resultGrid[gy][gx].solved, std::memory_order_relaxed);
    }

    if (execute_path_B) {
        auto t_pathB_start = std::chrono::high_resolution_clock::now();
        std::vector<IndicVision::SeedNode> boundary_seeds;
        std::vector<IndicVision::SeedNode> global_seeds;

        if (execute_path_A && execute_path_B) {
            int dx[] = {1, -1, 0, 0};
            int dy[] = {0, 0, 1, -1};
            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    if (resultGrid[y][x].solved && resultGrid[y][x].corr > 0.0f) {
                        bool touching_blank = false;
                        for (int k = 0; k < 4; ++k) {
                            int nx = x + dx[k], ny = y + dy[k];
                            if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH && !resultGrid[ny][nx].solved) {
                                touching_blank = true; break;
                            }
                        }
                        if (touching_blank) {
                            boundary_seeds.push_back(IndicVision::SeedNode(x, y, resultGrid[y][x].u, resultGrid[y][x].v, resultGrid[y][x].ux, resultGrid[y][x].uy, resultGrid[y][x].vx, resultGrid[y][x].vy, resultGrid[y][x].corr));
                        }
                    }
                }
            }
        } else {
            int seedGx = gridW / 2, seedGy = gridH / 2;
            int max_r = std::max(gridW, gridH) / 2;
            for (int r = 0; r <= max_r; ++r) {
                for (int i = -r; i <= r; ++i) {
                    for (int j = -r; j <= r; ++j) {
                        if (std::abs(i) != r && std::abs(j) != r) continue;
                        int cx = seedGx + i, cy = seedGy + j;
                        if (cx >= 0 && cx < gridW && cy >= 0 && cy < gridH && !resultGrid[cy][cx].solved) {
                            global_seeds.push_back(IndicVision::SeedNode(cx, cy, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f, 0.f));
                        }
                    }
                }
            }
        }

        if (!local_debug_dir.empty()) {
            try {
                cv::Mat seedDebug(gridH, gridW, CV_8UC3, cv::Scalar(0, 0, 0));
                for (int y = 0; y < gridH; ++y)
                    for (int x = 0; x < gridW; ++x)
                        if (resultGrid[y][x].solved)
                            seedDebug.at<cv::Vec3b>(y, x) = cv::Vec3b(50, 50, 50);

                for (const auto& s : boundary_seeds)
                    if (s.x_idx >= 0 && s.x_idx < gridW && s.y_idx >= 0 && s.y_idx < gridH)
                        seedDebug.at<cv::Vec3b>(s.y_idx, s.x_idx) = cv::Vec3b(0, 255, 0);

                for (const auto& s : global_seeds)
                    if (s.x_idx >= 0 && s.x_idx < gridW && s.y_idx >= 0 && s.y_idx < gridH)
                        seedDebug.at<cv::Vec3b>(s.y_idx, s.x_idx) = cv::Vec3b(0, 0, 255);

                cv::Mat seedBig;
                cv::resize(seedDebug, seedBig, cv::Size(gridW * step, gridH * step), 0, 0, cv::INTER_NEAREST);
                drawOutlinedText(seedBig, "Path B Seeds (Green=Boundary, Red=Spiral, Gray=PathA)", cv::Point(10, 25), 0.6);
                cv::imwrite(local_debug_dir + "/seed_debug.png", seedBig);
            } catch (...) {}
        }

        std::atomic<int> seed_index(0);
        std::mutex grid_mutex;
        std::vector<std::thread> workers;

        // 🔒 Worker Guard to prevent vector destructor crash if exception occurs
        struct WorkerGuard {
            std::vector<std::thread>& ws;
            ~WorkerGuard() {
                for (auto& w : ws) if (w.joinable()) w.join();
            }
        } wg{workers};

        for (int t = 0; t < safe_cores; ++t) {
            workers.emplace_back([&, t]() {
                try {
                    int tid = t;
                    IndicVision::OptimizationEngine local_engine;
                    IndicVision::SubsetData local_subset;
                    std::priority_queue<IndicVision::SeedNode> local_queue;
                    double local_hessian_ms = 0.0;
                    int local_points_solved = 0;
                    int dx[] = {1, -1, 0, 0};
                    int dy[] = {0, 0, 1, -1};

                    for (size_t i = t; i < boundary_seeds.size(); i += safe_cores) {
                        local_queue.push(boundary_seeds[i]);
                    }

                    while (true) {
                        while (!local_queue.empty()) {
                            IndicVision::SeedNode current = local_queue.top();
                            local_queue.pop();

                            for (int k = 0; k < 4; ++k) {
                                int nx = current.x_idx + dx[k], ny = current.y_idx + dy[k];

                                if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                                    int flat = ny * gridW + nx;

                                    bool was_unclaimed = false;
                                    if (!cell_claimed[flat].compare_exchange_strong(
                                            was_unclaimed, true, std::memory_order_acq_rel, std::memory_order_relaxed)) {
                                        continue;
                                    }

                                    {
                                        std::lock_guard<std::mutex> lock(grid_mutex);
                                        resultGrid[ny][nx].solved = true;
                                    }

                                    int realX = rectX + nx * step, realY = rectY + ny * step;

                                    auto th1 = std::chrono::high_resolution_clock::now();
                                    IndicVision::SubsetPrecomputer::precompute_subset_fast(local_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[flat]);
                                    auto th2 = std::chrono::high_resolution_clock::now();
                                    local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                                    if (!local_subset.is_initialized) continue;

                                    IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                            local_subset, defImg, current.u, current.v, current.ux, current.uy, current.vx, current.vy, IndicVision::INIT_NO_SEARCH);

                                    if (res.status == 0 && res.correlation_score <= 0.15f) {
                                        std::lock_guard<std::mutex> lock(grid_mutex);
                                        int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                                        resultGrid[ny][nx] = {(float)realX, (float)realY, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score, true, tid, order, 0};
                                        local_queue.push(IndicVision::SeedNode(nx, ny, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                                        local_points_solved++;
                                        global_points_solved.fetch_add(1, std::memory_order_relaxed);
                                    } else {
                                        std::lock_guard<std::mutex> lock(grid_mutex);
                                        resultGrid[ny][nx].corr = 0.0f;
                                    }
                                }
                            }
                        }

                        if (global_seeds.empty()) break;

                        int chunk_size = 64;
                        int start_idx = seed_index.fetch_add(chunk_size, std::memory_order_relaxed);
                        if (start_idx >= (int)global_seeds.size()) break;
                        int end_idx = std::min(start_idx + chunk_size, (int)global_seeds.size());

                        for (int current_idx = start_idx; current_idx < end_idx; ++current_idx) {
                            IndicVision::SeedNode seed = global_seeds[current_idx];
                            int flat_gs = seed.y_idx * gridW + seed.x_idx;

                            bool was_unclaimed_gs = false;
                            if (!cell_claimed[flat_gs].compare_exchange_strong(
                                    was_unclaimed_gs, true, std::memory_order_acq_rel, std::memory_order_relaxed)) {
                                continue;
                            }

                            {
                                std::lock_guard<std::mutex> lock(grid_mutex);
                                resultGrid[seed.y_idx][seed.x_idx].solved = true;
                            }

                            int realX = rectX + seed.x_idx * step, realY = rectY + seed.y_idx * step;

                            auto th1 = std::chrono::high_resolution_clock::now();
                            IndicVision::SubsetPrecomputer::precompute_subset_fast(local_subset, *g_refImg, realX, realY, subsetSize, hessian_pool[flat_gs]);
                            auto th2 = std::chrono::high_resolution_clock::now();
                            local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                            if (!local_subset.is_initialized) continue;

                            IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                    local_subset, defImg, globalU, globalV, 0.0f, 0.0f, 0.0f, 0.0f, IndicVision::INIT_AUTO_SEARCH);

                            if (res.status == 0 && res.correlation_score <= 0.15f) {
                                std::lock_guard<std::mutex> lock(grid_mutex);
                                int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                                resultGrid[seed.y_idx][seed.x_idx] = {(float)realX, (float)realY, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score, true, tid, order, 0};
                                local_queue.push(IndicVision::SeedNode(seed.x_idx, seed.y_idx, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                                local_points_solved++;
                                global_points_solved.fetch_add(1, std::memory_order_relaxed);
                            } else {
                                std::lock_guard<std::mutex> lock(grid_mutex);
                                resultGrid[seed.y_idx][seed.x_idx].corr = 0.0f;
                            }
                        }
                    }

                    t_icgn_arr[tid] += local_engine.time_icgn_ms;
                    t_simplex_arr[tid] += local_engine.time_simplex_ms;
                    t_hessian_arr[tid] += local_hessian_ms;
                    c_icgn_arr[tid] += local_engine.count_icgn;
                    c_simplex_arr[tid] += local_engine.count_simplex;
                    c_points_arr[tid] += local_points_solved;

                } catch (...) {}
            });
        }
        // WorkerGuard destructor ensures threads are perfectly joined here
        time_pathB = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_pathB_start).count();
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

    double total_icgn = 0, total_simplex = 0, total_hessian = 0;
    int total_icgn_iters = 0, total_simplex_iters = 0, pathA_pts = 0, pathB_pts = 0;
    for(int i=0; i<safe_cores; i++) {
        total_icgn += t_icgn_arr[i]; total_simplex += t_simplex_arr[i]; total_hessian += t_hessian_arr[i];
        total_icgn_iters += c_icgn_arr[i]; total_simplex_iters += c_simplex_arr[i];
    }

    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            if (resultGrid[y][x].solved && resultGrid[y][x].corr > 0.0f) {
                if (resultGrid[y][x].mesh_assignment_type > 0) pathA_pts++;
                else pathB_pts++;
            }
        }
    }

    double time_total = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_total_start).count();

    LOGD("=== ⏱️ ADVANCED PERFORMANCE PROFILING ===");
    LOGD("Image Prep & Masking: %.2f ms", time_img_prep);
    LOGD("AKAZE & RANSAC:       %.2f ms", time_akaze + time_ransac);
    LOGD("Delaunay Mesh Setup:  %.2f ms", time_delaunay);
    LOGD("Contour Assignment:   %.2f ms", time_contour_assign);
    LOGD("Extrapolation Pass:   %.2f ms", time_extrapolate);
    LOGD("Spatial Smoothing:    %.2f ms", time_smoothing);
    LOGD("Hessian Pre-pass:     %.2f ms (One-time Global Math)", time_prepass);
    LOGD("Path A (Mesh Eval):   %.2f ms (Throughput: %.1f pts/ms)", time_pathA, (time_pathA > 0) ? pathA_pts / time_pathA : 0);
    LOGD("Path B (RGDIC Fill):  %.2f ms (Throughput: %.1f pts/ms)", time_pathB, (time_pathB > 0) ? pathB_pts / time_pathB : 0);
    LOGD("Strain Calculation:   %.2f ms", time_strain);
    LOGD("Total JNI Execution:  %.2f ms", time_total);
    LOGD("--- ENGINE MATH & HARDWARE EFFICIENCY ---");
    LOGD("Total Points Solved:  %d (Path A: %d, Path B: %d)", valid_count, pathA_pts, pathB_pts);
    LOGD("Average ICGN Speed:   %.4f iterations / point", (valid_count > 0) ? (float)total_icgn_iters / valid_count : 0.0f);
    LOGD("Residual Precompute:  %.2f ms (Saved ~65%% via Pre-pass)", total_hessian);
    LOGD("Total Simplex Rescue: %.2f ms (%d fails)", total_simplex, total_simplex_iters);
    LOGD("=======================================");

    // =========================================================
    // 🐛 EXPORT FULL DEBUG SUITE (SHIELDED FROM OOM)
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

            static const cv::Vec3b THREAD_COLORS[12] = {
                    {60, 20, 220}, {20, 200, 20}, {200, 60, 20}, {200, 200, 20},
                    {20, 200, 200}, {200, 20, 200}, {100, 180, 255}, {255, 140, 30},
                    {50, 255, 180}, {180, 50, 255}, {255, 50, 130}, {130, 255, 50}};

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
                    if (!gp.solved || gp.compute_order < 0) continue;

                    propMap.at<uchar>(y, x) = (uchar)((float)gp.compute_order / max_order * 255.0f);
                    threadMap.at<cv::Vec3b>(y, x) = THREAD_COLORS[std::max(0, std::min(gp.thread_id, 11))];
                    corrMap.at<uchar>(y, x) = (uchar)((gp.corr / max_corr) * 255.0f);

                    int idx = y * gridW + x;
                    strainMap.at<uchar>(y, x) = (uchar)(((strainField.exx[idx] - min_exx) / (max_exx - min_exx)) * 255.0f);

                    if (gp.mesh_assignment_type == 1) meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 0);
                    else if (gp.mesh_assignment_type == 2) meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 255);
                    else meshAssignMap.at<cv::Vec3b>(y, x) = cv::Vec3b(100, 100, 100);
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

            cv::Mat outProp, outThread, outMesh, outCorr, outStrain;
            cv::Size sz(gridW * step, gridH * step);
            cv::resize(propColor, outProp, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(threadMap, outThread, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(meshAssignMap, outMesh, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(corrColor, outCorr, sz, 0, 0, cv::INTER_NEAREST);
            cv::resize(strainColor, outStrain, sz, 0, 0, cv::INTER_NEAREST);

            drawOutlinedText(outProp, "Path B RGDIC Propagation", cv::Point(10, 25), 0.6);
            drawOutlinedText(outThread, "8-Core Thread Execution Map", cv::Point(10, 25), 0.6);
            drawOutlinedText(outMesh, "Mesh Assign (Green=In, Yel=Extrap, Gry=PathB)", cv::Point(10, 25), 0.6);
            drawOutlinedText(outCorr, "ZNSSD Quality (Blue=Perfect, Red=Marginal)", cv::Point(10, 25), 0.6);
            drawOutlinedText(outStrain, "Exx Strain (Raw Plot)", cv::Point(10, 25), 0.6);

            cv::imwrite(local_debug_dir + "/propagation_debug.png", outProp);
            cv::imwrite(local_debug_dir + "/thread_debug.png", outThread);
            cv::imwrite(local_debug_dir + "/mesh_assignment_map.png", outMesh);
            cv::imwrite(local_debug_dir + "/correlation_heatmap.png", outCorr);
            cv::imwrite(local_debug_dir + "/strain_exx_debug.png", outStrain);

            std::string csvPath = local_debug_dir + "/debug_grid_data.csv";
            std::ofstream csvFile(csvPath);
            if (csvFile.is_open()) {
                csvFile << "RealX,RealY,GridX,GridY,ThreadID,ComputeOrder,U,V,Correlation,MeshType\n";
                for (int y = 0; y < gridH; ++y) {
                    for (int x = 0; x < gridW; ++x) {
                        const auto &gp = resultGrid[y][x];
                        if (gp.solved && gp.compute_order >= 0) {
                            csvFile << gp.x << "," << gp.y << "," << x << "," << y << ","
                                    << gp.thread_id << "," << gp.compute_order << "," << gp.u
                                    << "," << gp.v << "," << gp.corr << "," << gp.mesh_assignment_type << "\n";
                        }
                    }
                }
                csvFile.close();
            }
        } catch (const std::exception& e) {
            LOGE("OOM or Exception during Debug Export: %s", e.what());
        } catch (...) {
            LOGE("Unknown Exception during Debug Export");
        }
    }

    defMat.release(); roiMask.release();
    return (jint)valid_count;
}

} // extern "C"