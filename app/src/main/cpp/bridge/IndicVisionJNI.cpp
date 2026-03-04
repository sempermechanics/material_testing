#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <opencv2/features2d.hpp>
#include <queue>
#include <chrono>
#include <omp.h>
#include <atomic>
#include <thread>
#include <mutex>
#include <fstream>

// --- ARCHITECTURE HEADERS ---
#include "../preprocessing/ImageProcessor.h"
#include "../preprocessing/SubsetPrecomputer.h"
#include "../core/OptimizationEngine.h"
#include "../postprocessing/StrainCalculator.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "IndicVisionJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==========================================
// 🔴 BUG 1 FIX: JNI ON LOAD (CRITICAL OPENMP SAFETY)
// Prevents OpenCV from poisoning the LLVM OpenMP thread pool
// ==========================================
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("IndicVision Native Library Loaded.");
    // Force OpenCV to run single-threaded internally.
    // This stops it from hijacking and corrupting the OpenMP TLS pool before our main math block runs.
    cv::setNumThreads(1);
    return JNI_VERSION_1_6;
}

// ==========================================
// 🟠 BUG 2 FIX: GLOBAL REFERENCE IMAGE CACHE
// Prevents rebuilding the RefImg on every single frame of a batch
// ==========================================
static IndicVision::Image* g_refImg = nullptr;
static int g_refWidth = 0;
static int g_refHeight = 0;
static std::mutex jni_engine_mutex; // Global mutex to prevent concurrent engine calls

// ==========================================
// 🐛 DEBUG SUITE: Global State
// Set via setDebugOutputDir() from Kotlin before a frame you want to inspect.
// Automatically cleared after one frame so you don't pay the I/O cost every frame.
// ==========================================
static std::string g_debugDir = ""; // Empty = debug disabled

// ==========================================
// UTILITY: BYTES TO MAT (Zero-Copy or Decode)
// ==========================================
cv::Mat bytesToMat(JNIEnv* env, jbyteArray bytes, int expectedWidth = 0, int expectedHeight = 0) {
    if (bytes == nullptr) return cv::Mat();
    jsize len = env->GetArrayLength(bytes);
    jbyte* buf = env->GetByteArrayElements(bytes, nullptr);

    cv::Mat img;
    // Heuristic: If it looks exactly like an uncompressed ARGB_8888 buffer (from Kotlin RAW ImageDecoder)
    if (expectedWidth > 0 && expectedHeight > 0 && len == expectedWidth * expectedHeight * 4) {
        cv::Mat rawData(expectedHeight, expectedWidth, CV_8UC4, (void*)buf);
        cv::cvtColor(rawData, img, cv::COLOR_RGBA2GRAY);
    } else {
        // Standard encoded stream (JPG/PNG) via cv::imdecode
        cv::Mat rawData(1, len, CV_8UC1, (void*)buf);
        img = cv::imdecode(rawData, cv::IMREAD_GRAYSCALE);
    }

    cv::Mat result = img.clone();
    env->ReleaseByteArrayElements(bytes, buf, JNI_ABORT);
    return result;
}

// ==========================================
// UTILITY: TEXT DRAWING HELPER
// Draws highly readable text with a dark outline
// ==========================================
void drawOutlinedText(cv::Mat& img, const std::string& text, cv::Point pt, double scale = 0.5) {
    // Black outline
    cv::putText(img, text, pt, cv::FONT_HERSHEY_SIMPLEX, scale, cv::Scalar(0, 0, 0), 3, cv::LINE_AA);
    // White text
    cv::putText(img, text, pt, cv::FONT_HERSHEY_SIMPLEX, scale, cv::Scalar(255, 255, 255), 1, cv::LINE_AA);
}

// ==========================================
// UTILITY: AKAZE GLOBAL SHIFT
// debugDir: if non-empty, writes 3 debug images showing feature extraction and matching
// ==========================================
void computeGlobalShift(cv::Mat& ref, cv::Mat& def, float& u, float& v,
                        const std::string& debugDir = "") {
    const double scale = 0.25;
    cv::Mat smallRef, smallDef;
    cv::resize(ref, smallRef, cv::Size(), scale, scale, cv::INTER_NEAREST);
    cv::resize(def, smallDef, cv::Size(), scale, scale, cv::INTER_NEAREST);

    auto detector = cv::AKAZE::create();
    std::vector<cv::KeyPoint> kp1, kp2;
    cv::Mat desc1, desc2;

    detector->detectAndCompute(smallRef, cv::noArray(), kp1, desc1);
    detector->detectAndCompute(smallDef, cv::noArray(), kp2, desc2);

    // ── DEBUG VIZ 1a: Detected Features in Reference Image ───────────────────
    if (!debugDir.empty() && !smallRef.empty() && !kp1.empty()) {
        cv::Mat refFeaturesImg;
        // Draw all found features in Red
        cv::drawKeypoints(smallRef, kp1, refFeaturesImg, cv::Scalar(0, 0, 255), cv::DrawMatchesFlags::DEFAULT);
        std::string label = "Detected Ref Features: " + std::to_string(kp1.size());
        drawOutlinedText(refFeaturesImg, label, cv::Point(10, 20), 0.6);
        cv::imwrite(debugDir + "/akaze_ref_features.jpg", refFeaturesImg);
    }

    if(kp1.empty() || kp2.empty()) return;

    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<std::vector<cv::DMatch>> matches;
    matcher.knnMatch(desc1, desc2, matches, 2);

    // Lowe's ratio test — keep only good matches
    std::vector<cv::DMatch> good_matches;
    std::vector<cv::Point2f> p1, p2;
    for (auto& m : matches) {
        if (m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            good_matches.push_back(m[0]);
            p1.push_back(kp1[m[0].queryIdx].pt);
            p2.push_back(kp2[m[0].trainIdx].pt);
        }
    }

    if (p1.size() > 5) {
        std::vector<float> us, vs;
        for (size_t i = 0; i < p1.size(); ++i) {
            us.push_back(p2[i].x - p1[i].x);
            vs.push_back(p2[i].y - p1[i].y);
        }
        std::sort(us.begin(), us.end());
        std::sort(vs.begin(), vs.end());

        u = us[us.size() / 2] * (1.0f / (float)scale);
        v = vs[vs.size() / 2] * (1.0f / (float)scale);
        LOGD("Global Feature Match (Scaled): u=%.2f, v=%.2f  [%zu inliers]", u, v, p1.size());

        if (!debugDir.empty() && !smallRef.empty() && !smallDef.empty()) {

            // ── DEBUG VIZ 1b: Successfully Matched Features in Deformed Image ──
            std::vector<cv::KeyPoint> matched_kp2;
            for (auto& m : good_matches) {
                matched_kp2.push_back(kp2[m.trainIdx]);
            }
            cv::Mat defMatchesImg;
            // Draw matched features in Green
            cv::drawKeypoints(smallDef, matched_kp2, defMatchesImg, cv::Scalar(0, 255, 0), cv::DrawMatchesFlags::DEFAULT);
            std::string label_def = "Successfully Matched: " + std::to_string(matched_kp2.size());
            drawOutlinedText(defMatchesImg, label_def, cv::Point(10, 20), 0.6);
            cv::imwrite(debugDir + "/akaze_def_matches.jpg", defMatchesImg);


            // ── DEBUG VIZ 1c: AKAZE Match Connecting Lines Image ───────────────
            cv::Mat matchImg;
            cv::drawMatches(
                    smallRef, kp1, smallDef, kp2, good_matches, matchImg,
                    cv::Scalar(0, 255, 0),    // line/match color: green
                    cv::Scalar(255, 100, 0),  // single-point color: orange
                    std::vector<char>(),
                    cv::DrawMatchesFlags::NOT_DRAW_SINGLE_POINTS
            );

            // Overlay the consensus shift as a large red arrow on the right half
            float cx = (float)smallRef.cols + (smallDef.cols / 2.0f);
            float cy = (float)smallDef.rows / 2.0f;
            cv::arrowedLine(
                    matchImg,
                    cv::Point((int)cx, (int)cy),
                    cv::Point((int)(cx + u * scale), (int)(cy + v * scale)),
                    cv::Scalar(0, 0, 255), 3, cv::LINE_AA, 0, 0.3
            );
            std::string label_match = "Median shift: u=" + std::to_string((int)u)
                                      + " v=" + std::to_string((int)v)
                                      + "  (" + std::to_string(good_matches.size()) + " matches)";
            drawOutlinedText(matchImg, label_match, cv::Point(10, matchImg.rows - 10), 0.55);

            cv::imwrite(debugDir + "/akaze_matches_lines.jpg", matchImg);
            LOGD("[DEBUG] AKAZE debug images saved to: %s", debugDir.c_str());
        }
    }
}

extern "C" {

// ==========================================
// 🐛 DEBUG: Set output directory for one-shot debug frame
// Call from Kotlin: IndicVisionNativeLib.setDebugOutputDir(cacheDir + "/dic_debug")
// Pass empty string "" to disable debug output.
// ==========================================
JNIEXPORT void JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_setDebugOutputDir(
        JNIEnv* env, jobject, jstring debugDir) {
    if (debugDir == nullptr) {
        g_debugDir = "";
        LOGD("[DEBUG] Debug output DISABLED.");
        return;
    }
    const char* dir = env->GetStringUTFChars(debugDir, nullptr);
    g_debugDir = std::string(dir);
    env->ReleaseStringUTFChars(debugDir, dir);
    LOGD("[DEBUG] Debug output dir set to: %s", g_debugDir.c_str());
}

// ==========================================
// UI PREVIEW GENERATOR
// ==========================================
JNIEXPORT jobject JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_getPreviewFromBytes(
        JNIEnv* env, jobject, jbyteArray fileData, jint targetWidth) {

    jsize len = env->GetArrayLength(fileData);
    jbyte* buf = env->GetByteArrayElements(fileData, nullptr);
    cv::Mat rawData(1, len, CV_8UC1, (void*)buf);
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

    void* pixels;
    if (AndroidBitmap_lockPixels(env, jBitmap, &pixels) < 0) return nullptr;
    cv::cvtColor(resizedImg, resizedImg, cv::COLOR_BGR2RGBA);
    memcpy(pixels, resizedImg.data, resizedImg.total() * resizedImg.elemSize());
    AndroidBitmap_unlockPixels(env, jBitmap);
    return jBitmap;
}

// ==========================================
// IMAGE DIMENSIONS
// ==========================================
JNIEXPORT jintArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_getImageDimensions(
        JNIEnv* env, jobject, jbyteArray fileData) {

    jsize len = env->GetArrayLength(fileData);
    jbyte* buf = env->GetByteArrayElements(fileData, nullptr);
    cv::Mat rawData(1, len, CV_8UC1, (void*)buf);
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

// ==========================================
// 🟠 BUG 2 FIX: INITIALIZE REFERENCE ONCE
// Call this from Kotlin BEFORE the batch loop starts
// ==========================================
JNIEXPORT void JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_initializeReference(
        JNIEnv* env, jobject, jbyteArray refBytes, jint width, jint height, jboolean applyBlur) {

    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);

    // Free the old image if it exists
    if (g_refImg != nullptr) {
        delete g_refImg;
        g_refImg = nullptr;
    }

    if (refBytes == nullptr) return;

    cv::Mat refMat = bytesToMat(env, refBytes, width, height);
    if (refMat.empty()) return;

    if (applyBlur) {
        cv::GaussianBlur(refMat, refMat, cv::Size(7, 7), 0);
    }

    g_refWidth = refMat.cols;
    g_refHeight = refMat.rows;

    // Allocate on the heap and prepare (7-tap Gaussian, Gradients, etc.)
    g_refImg = new IndicVision::Image(g_refWidth, g_refHeight, refMat.data);
    g_refImg->prepare_data();

    LOGD("Reference Image Initialized and Cached in Native Memory. [%dx%d]", g_refWidth, g_refHeight);
}


// ==========================================
// ANALYZE SINGLE POINT (1D / LIVE MODE)
// ==========================================
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_analyzeRawBytes(
        JNIEnv* env, jobject,
        jbyteArray refBytes, jbyteArray defBytes,
        jint roiX, jint roiY, jint subsetSize,
        jint originalWidth, jint originalHeight) {

    cv::Mat refMat = bytesToMat(env, refBytes, originalWidth, originalHeight);
    cv::Mat defMat = bytesToMat(env, defBytes, originalWidth, originalHeight);

    if (refMat.empty() || defMat.empty()) {
        jfloatArray fail = env->NewFloatArray(5);
        jfloat temp[] = {0.0f,0.0f,0.0f,0.0f,1.0f};
        env->SetFloatArrayRegion(fail, 0, 5, temp);
        return fail;
    }

    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);

    refImg.prepare_data();
    defImg.prepare_data();

    IndicVision::SubsetData subset;
    IndicVision::SubsetPrecomputer::precompute_subset(subset, refImg, roiX, roiY, subsetSize);

    IndicVision::OptimizationEngine engine;
    float startU = 0.0f;
    float startV = 0.0f;
    IndicVision::AnalysisResult res = engine.calculate_deformation(subset, defImg, startU, startV, IndicVision::INIT_AUTO_SEARCH);

    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5] = { res.u, res.v, 0.0f, res.ux, (jfloat)res.status };
    env->SetFloatArrayRegion(output, 0, 5, temp);
    return output;
}

// ==========================================
// 🚀 COMPUTE FULL FIELD DIRECT (BATCH OPTIMIZED)
// ==========================================
JNIEXPORT jint JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeFullFieldDirect(
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jbyteArray maskBytes,
        jint rectX, jint rectY, jint rectWidth, jint rectHeight,
        jint step, jint subsetSize, jint strainWindow,
        jboolean useReliabilityGuided, jboolean useFeatureMatching,
        jboolean applyGaussianBlur, jboolean useNlvcStrain,
        jobject outputBuffer,
        jobject callbackObj) {

    std::lock_guard<std::mutex> engine_lock(jni_engine_mutex);

    auto start_total = std::chrono::high_resolution_clock::now();

    // 1. SAFETY CHECKS
    if (env == nullptr || defBytes == nullptr || outputBuffer == nullptr) {
        LOGE("FATAL: Null JNI parameters passed to engine");
        return 0;
    }

    if (g_refImg == nullptr) {
        LOGE("FATAL: Reference image was not initialized before calling computeFullFieldDirect!");
        return 0;
    }

    float* output_ptr = (float*)env->GetDirectBufferAddress(outputBuffer);
    if (!output_ptr) return 0;

    static int s_frame_count = 0;
    s_frame_count++;
    LOGD("=== FRAME %d computeFullFieldDirect START ===", s_frame_count);
    LOGD("  g_refImg ptr = %p  (w=%d h=%d)", (void*)g_refImg, g_refWidth, g_refHeight);

    // 2. LOAD DEFORMED IMAGE ONLY (Memory Saved!)
    cv::Mat defMat = bytesToMat(env, defBytes, g_refWidth, g_refHeight);
    if (defMat.empty()) {
        LOGE("DIAGNOSTIC FRAME %d: defMat is EMPTY — image decode failed!", s_frame_count);
        return 0;
    }
    LOGD("  defMat size = %dx%d", defMat.cols, defMat.rows);

    cv::Mat roiMask;
    if (maskBytes != nullptr && env->GetArrayLength(maskBytes) > 0) {
        roiMask = bytesToMat(env, maskBytes);
        if (!roiMask.empty() && (roiMask.cols != g_refWidth || roiMask.rows != g_refHeight)) {
            cv::resize(roiMask, roiMask, cv::Size(g_refWidth, g_refHeight), 0, 0, cv::INTER_NEAREST);
        }
    }

    if (applyGaussianBlur) {
        cv::GaussianBlur(defMat, defMat, cv::Size(7, 7), 0);
    }

    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    defImg.prepare_data();

    // 3. GLOBAL AKAZE SHIFT
    float globalU = 0.0f, globalV = 0.0f;
    if (useFeatureMatching) {
        cv::Rect roi(rectX, rectY, rectWidth, rectHeight);
        roi = roi & cv::Rect(0, 0, g_refWidth, g_refHeight);
        if (roi.width > 32 && roi.height > 32) {
            try {
                cv::Mat refMat = bytesToMat(env, refBytes, g_refWidth, g_refHeight);
                if (!refMat.empty()) {
                    cv::Mat refROI = refMat(roi);
                    cv::Mat defROI = defMat(roi);
                    // Pass the whole directory so it can generate multiple images
                    std::string debugDirStr = g_debugDir.empty() ? "" : g_debugDir;
                    computeGlobalShift(refROI, defROI, globalU, globalV, debugDirStr);
                }
            } catch (const cv::Exception& e) {
                LOGE("AKAZE Exception. Ignoring shift.");
            }
        }
    }

    // 4. GRID ALLOCATION
    int gridW = rectWidth / step;
    int gridH = rectHeight / step;
    if (gridW <= 0 || gridH <= 0) return 0;

    struct GridPoint {
        float x, y, u, v, corr;
        bool solved;
        int thread_id;     // 🐛 DEBUG
        int compute_order; // 🐛 DEBUG
    };
    std::vector<std::vector<GridPoint>> resultGrid(gridH, std::vector<GridPoint>(gridW));

    std::atomic<int> compute_order_counter(0);

    int total_valid_points = 0;
    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            int realX = rectX + x * step;
            int realY = rectY + y * step;
            bool shouldSkip = (!roiMask.empty() && roiMask.at<uchar>(realY, realX) < 128);
            if (!shouldSkip) total_valid_points++;
            resultGrid[y][x] = {(float)realX, (float)realY, 0.0f, 0.0f, 0.0f,
                                shouldSkip, -1, -1};
        }
    }

    if (total_valid_points == 0) return 0;

    // 5. SEED SELECTION — Deterministic Center of ROI
    int seedGx = gridW / 2;
    int seedGy = gridH / 2;

    LOGD("Seed: grid=(%d,%d) real=(%d,%d) (Geometric Centre)",
         seedGx, seedGy,
         rectX + seedGx * step, rectY + seedGy * step);

    // Build seed list radiating outward from the chosen seed point
    std::vector<IndicVision::SeedNode> global_seeds;
    {
        int max_r = std::max(gridW, gridH) / 2;
        for (int r = 0; r <= max_r; ++r) {
            for (int i = -r; i <= r; ++i) {
                for (int j = -r; j <= r; ++j) {
                    if (std::abs(i) != r && std::abs(j) != r) continue;
                    int cx = seedGx + i, cy = seedGy + j;
                    if (cx >= 0 && cx < gridW && cy >= 0 && cy < gridH
                        && !resultGrid[cy][cx].solved) {
                        global_seeds.push_back(
                                IndicVision::SeedNode(cx,cy,0.f,0.f,0.f,0.f,0.f,0.f,0.f));
                    }
                }
            }
        }
    }
    if (global_seeds.empty()) return 0;

    // ── DEBUG VIZ 2: Seed Location ──────────────────────────────────────────
    if (!g_debugDir.empty()) {
        cv::Mat refGray(g_refHeight, g_refWidth, CV_32FC1,
                        (void*)g_refImg->intensities.data());
        cv::Mat refDbg;
        refGray.convertTo(refDbg, CV_8UC1);
        cv::cvtColor(refDbg, refDbg, cv::COLOR_GRAY2BGR);

        // All seed ring points — tiny grey dots
        for (auto& s : global_seeds) {
            cv::circle(refDbg,
                       cv::Point(rectX + s.x_idx * step, rectY + s.y_idx * step),
                       2, cv::Scalar(80,80,80), -1, cv::LINE_AA);
        }
        // Chosen geometric center seed — large bright green
        cv::circle(refDbg,
                   cv::Point(rectX + seedGx * step, rectY + seedGy * step),
                   12, cv::Scalar(0,255,0), 2, cv::LINE_AA);

        // ROI boundary
        cv::rectangle(refDbg, cv::Point(rectX, rectY),
                      cv::Point(rectX + rectWidth - 1, rectY + rectHeight - 1),
                      cv::Scalar(0,200,255), 2);
        drawOutlinedText(refDbg, "Green=Center Seed", cv::Point(rectX + 4, rectY + 20), 0.5);

        cv::imwrite(g_debugDir + "/seed_debug.jpg", refDbg);
        LOGD("[DEBUG] Seed viz saved.");
    }

    // 6. PROGRESS REPORTING SETUP (Safe Threading)
    int total_cores = std::thread::hardware_concurrency();
    int safe_cores = std::max(1,total_cores);

    std::vector<double> t_icgn_arr(safe_cores, 0.0), t_simplex_arr(safe_cores, 0.0), t_hessian_arr(safe_cores, 0.0);
    std::vector<int> c_icgn_arr(safe_cores, 0), c_simplex_arr(safe_cores, 0), c_points_arr(safe_cores, 0);

    std::atomic<int> seed_index(0);
    std::atomic<int> global_points_solved(0);
    std::atomic<bool> progress_thread_should_stop(false);
    std::mutex grid_mutex;

    jclass callbackClass = nullptr;
    jmethodID methodId = nullptr;
    jobject globalCallbackObj = nullptr;
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);

    if (callbackObj != nullptr) {
        globalCallbackObj = env->NewGlobalRef(callbackObj);
        callbackClass = env->GetObjectClass(callbackObj);
        methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");
    }

    std::thread progress_thread([&]() {
        if (jvm == nullptr || globalCallbackObj == nullptr || methodId == nullptr) return;
        JNIEnv* pEnv = nullptr;
        if (jvm->AttachCurrentThread(&pEnv, nullptr) != JNI_OK) return;
        if (pEnv == nullptr) { jvm->DetachCurrentThread(); return; }

        while (!progress_thread_should_stop.load(std::memory_order_acquire)) {
            if (globalCallbackObj == nullptr) break;

            int solved = global_points_solved.load(std::memory_order_relaxed);
            int percentage = (int)((((float)solved / total_valid_points) * 80.0f) + 10.0f);
            percentage = std::max(0, std::min(100, percentage));

            if (pEnv->ExceptionCheck()) { pEnv->ExceptionClear(); break; }
            pEnv->CallVoidMethod(globalCallbackObj, methodId, (jint)percentage);
            if (pEnv->ExceptionCheck()) { pEnv->ExceptionClear(); break; }

            for (int i = 0; i < 10 && !progress_thread_should_stop.load(std::memory_order_acquire); ++i) {
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
        }
        jvm->DetachCurrentThread();
    });

    auto start_track = std::chrono::high_resolution_clock::now();

    LOGD("DIAGNOSTIC FRAME %d: grid=%dx%d valid_pts=%d, going into OpenMP block...", s_frame_count, gridW, gridH, total_valid_points);

    std::vector<std::thread> workers;
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

                while (true) {
                    while (!local_queue.empty()) {
                        IndicVision::SeedNode current = local_queue.top();
                        local_queue.pop();

                        for (int k = 0; k < 4; ++k) {
                            int nx = current.x_idx + dx[k];
                            int ny = current.y_idx + dy[k];

                            if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                                bool claimed = false;
                                {
                                    std::lock_guard<std::mutex> lock(grid_mutex);
                                    if (!resultGrid[ny][nx].solved) {
                                        resultGrid[ny][nx].solved = true;
                                        claimed = true;
                                    }
                                }
                                if (!claimed) continue;

                                int realX = rectX + nx * step;
                                int realY = rectY + ny * step;

                                auto th1 = std::chrono::high_resolution_clock::now();
                                IndicVision::SubsetPrecomputer::precompute_subset(local_subset, *g_refImg, realX, realY, subsetSize);
                                auto th2 = std::chrono::high_resolution_clock::now();
                                local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                                if (!local_subset.is_initialized) {
                                    continue;
                                }

                                IndicVision::AnalysisResult res = local_engine.calculate_deformation(local_subset, defImg, current.u, current.v, IndicVision::INIT_NO_SEARCH);

                                {
                                    std::lock_guard<std::mutex> lock(grid_mutex);
                                    int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                                    resultGrid[ny][nx] = {(float)realX, (float)realY,
                                                          res.u, res.v, res.correlation_score,
                                                          true, tid, order};
                                }

                                if (res.status == 0 && res.correlation_score < 0.3f) {
                                    local_queue.push(IndicVision::SeedNode(nx, ny, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                                }
                                local_points_solved++;
                                global_points_solved.fetch_add(1, std::memory_order_relaxed);
                            }
                        }
                    }

                    int chunk_size = 64;
                    int start_idx = seed_index.fetch_add(chunk_size, std::memory_order_relaxed);
                    if (start_idx >= (int)global_seeds.size()) break;

                    int end_idx = std::min(start_idx + chunk_size, (int)global_seeds.size());

                    for (int current_idx = start_idx; current_idx < end_idx; ++current_idx) {
                        IndicVision::SeedNode seed = global_seeds[current_idx];
                        bool claimed = false;

                        {
                            std::lock_guard<std::mutex> lock(grid_mutex);
                            if (!resultGrid[seed.y_idx][seed.x_idx].solved) {
                                resultGrid[seed.y_idx][seed.x_idx].solved = true;
                                claimed = true;
                            }
                        }
                        if (!claimed) continue;

                        int realX = rectX + seed.x_idx * step;
                        int realY = rectY + seed.y_idx * step;

                        auto th1 = std::chrono::high_resolution_clock::now();
                        IndicVision::SubsetPrecomputer::precompute_subset(local_subset, *g_refImg, realX, realY, subsetSize);
                        auto th2 = std::chrono::high_resolution_clock::now();
                        local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                        if (!local_subset.is_initialized) continue;

                        IndicVision::AnalysisResult res = local_engine.calculate_deformation(local_subset, defImg, globalU, globalV, IndicVision::INIT_AUTO_SEARCH);

                        {
                            std::lock_guard<std::mutex> lock(grid_mutex);
                            int order = compute_order_counter.fetch_add(1, std::memory_order_relaxed);
                            resultGrid[seed.y_idx][seed.x_idx] = {(float)realX, (float)realY,
                                                                  res.u, res.v, res.correlation_score,
                                                                  true, tid, order};
                        }

                        if (res.status == 0 && res.correlation_score < 0.15f) {
                            local_queue.push(IndicVision::SeedNode(seed.x_idx, seed.y_idx, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                            local_points_solved++;
                            global_points_solved.fetch_add(1, std::memory_order_relaxed);
                        }
                    }
                }

                t_icgn_arr[tid] = local_engine.time_icgn_ms;
                t_simplex_arr[tid] = local_engine.time_simplex_ms;
                t_hessian_arr[tid] = local_hessian_ms;
                c_icgn_arr[tid] = local_engine.count_icgn;
                c_simplex_arr[tid] = local_engine.count_simplex;
                c_points_arr[tid] = local_points_solved;

            } catch (const std::exception& e) {
                LOGE("Native Thread %d Crashed due to memory/math error: %s", t, e.what());
            } catch (...) {
                LOGE("Native Thread %d Crashed with unknown error!", t);
            }
        });
    }

    // Wait for all worker threads to finish
    for (auto& worker : workers) {
        if (worker.joinable()) worker.join();
    }

    auto end_track = std::chrono::high_resolution_clock::now();

    // 8. SAFE THREAD TEARDOWN
    progress_thread_should_stop.store(true, std::memory_order_release);
    std::this_thread::sleep_for(std::chrono::milliseconds(50));

    if (progress_thread.joinable()) {
        progress_thread.join();
    }

    if (globalCallbackObj != nullptr) {
        env->DeleteGlobalRef(globalCallbackObj);
        globalCallbackObj = nullptr;
    }

    // 9. STRAIN POST-PROCESSING
    IndicVision::DisplacementField dispField;
    dispField.width = gridW;
    dispField.height = gridH;
    dispField.step = step;
    dispField.u.resize(gridW * gridH, 0.0f);
    dispField.v.resize(gridW * gridH, 0.0f);
    dispField.valid.resize(gridW * gridH, false);

    for(int y=0; y<gridH; ++y) {
        for(int x=0; x<gridW; ++x) {
            int idx = y * gridW + x;
            if (resultGrid[y][x].solved && resultGrid[y][x].corr != 0.0f) {
                dispField.u[idx] = resultGrid[y][x].u;
                dispField.v[idx] = resultGrid[y][x].v;
                dispField.valid[idx] = true;
            }
        }
    }

    IndicVision::StrainField strainField;
    if (useNlvcStrain) {
        strainField = IndicVision::StrainCalculator::compute_nlvc_strain(dispField, strainWindow);
    } else {
        strainField = IndicVision::StrainCalculator::compute_vsg_strain(dispField, strainWindow);
    }

    if (callbackObj != nullptr && methodId != nullptr && !env->ExceptionCheck()) {
        env->CallVoidMethod(callbackObj, methodId, (jint)95);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    // 10. DIRECT BUFFER FLATTENING (ZERO-COPY)
    int valid_count = 0;
    for(int y=0; y<gridH; ++y) {
        for(int x=0; x<gridW; ++x) {
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

    if (callbackObj != nullptr && methodId != nullptr && !env->ExceptionCheck()) {
        env->CallVoidMethod(callbackObj, methodId, (jint)100);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    auto end_total = std::chrono::high_resolution_clock::now();

    double t_track = std::chrono::duration<double, std::milli>(end_track - start_track).count();
    LOGD("=== OPENMP RGDIC PERFORMANCE PROFILE ===");
    LOGD("OpenMP Wall Time         : %.2f ms", t_track);
    LOGD("Total Points Solved      : %d", global_points_solved.load());
    for(int i=0; i<safe_cores; i++) {
        LOGD(" Thread %d: Pts=%d, Hessian=%.1fms, ICGN=%.1fms, Simplex=%.1fms", i, c_points_arr[i], t_hessian_arr[i], t_icgn_arr[i], t_simplex_arr[i]);
    }
    LOGD("Total JNI Execution Time : %.2f ms", std::chrono::duration<double, std::milli>(end_total - start_total).count());
    LOGD("========================================");

    // ── DEBUG VIZ 3 & 4: Propagation Order Map + Thread Map ────────────────
    if (!g_debugDir.empty()) {
        int max_order = 1;
        for (int y = 0; y < gridH; ++y)
            for (int x = 0; x < gridW; ++x)
                if (resultGrid[y][x].compute_order > max_order)
                    max_order = resultGrid[y][x].compute_order;

        cv::Mat propMap(gridH, gridW, CV_8UC1, cv::Scalar(0));
        cv::Mat threadMap(gridH, gridW, CV_8UC3, cv::Scalar(30, 30, 30));

        static const cv::Vec3b THREAD_COLORS[12] = {
                {60,  20,  220}, {20,  200,  20}, {200, 60,   20},
                {200, 200,  20}, {20,  200, 200}, {200,  20, 200},
                {100, 180, 255}, {255, 140,  30}, {50,  255, 180},
                {180,  50, 255}, {255,  50, 130}, {130, 255,  50}
        };

        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                const auto& gp = resultGrid[y][x];
                if (!gp.solved || gp.compute_order < 0) continue;
                propMap.at<uchar>(y, x) =
                        (uchar)((float)gp.compute_order / max_order * 255.0f);
                int tid_clamped = std::max(0, std::min(gp.thread_id, 11));
                threadMap.at<cv::Vec3b>(y, x) = THREAD_COLORS[tid_clamped];
            }
        }

        cv::Mat propColor;
        cv::applyColorMap(propMap, propColor, cv::COLORMAP_JET);

        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                if (!resultGrid[y][x].solved || resultGrid[y][x].compute_order < 0) {
                    propColor.at<cv::Vec3b>(y, x)  = {0, 0, 0};
                    threadMap.at<cv::Vec3b>(y, x)  = {30, 30, 30};
                }
            }
        }

        cv::Mat propBig, threadBig;
        cv::resize(propColor, propBig,   cv::Size(gridW * step, gridH * step),
                   0, 0, cv::INTER_NEAREST);
        cv::resize(threadMap, threadBig, cv::Size(gridW * step, gridH * step),
                   0, 0, cv::INTER_NEAREST);

        for (int tid = 0; tid < safe_cores && tid < 12; ++tid) {
            cv::Vec3b col = THREAD_COLORS[tid];
            int ly = 15 + tid * 18;
            cv::rectangle(threadBig, cv::Point(5, ly - 10), cv::Point(22, ly + 4),
                          cv::Scalar(col[0], col[1], col[2]), -1);

            std::string label = "T" + std::to_string(tid) + " (" + std::to_string(c_points_arr[tid]) + " pts)";
            drawOutlinedText(threadBig, label, cv::Point(27, ly + 3), 0.42);
        }

        drawOutlinedText(propBig, "BLUE = solved first   RED = solved last", cv::Point(6, propBig.rows - 8), 0.5);

        cv::imwrite(g_debugDir + "/propagation_debug.png", propBig);
        cv::imwrite(g_debugDir + "/thread_debug.png",      threadBig);
        LOGD("[DEBUG] Propagation map + Thread map saved to %s", g_debugDir.c_str());

        // ── DEBUG VIZ 5: The Ultimate CSV Dump ──────────────────────
        std::string csvPath = g_debugDir + "/debug_grid_data.csv";
        std::ofstream csvFile(csvPath);
        if (csvFile.is_open()) {
            csvFile << "RealX,RealY,GridX,GridY,ThreadID,ComputeOrder,U,V,Correlation\n";
            for (int y = 0; y < gridH; ++y) {
                for (int x = 0; x < gridW; ++x) {
                    const auto& gp = resultGrid[y][x];
                    if (gp.solved && gp.compute_order >= 0) {
                        csvFile << gp.x << ","
                                << gp.y << ","
                                << x << ","
                                << y << ","
                                << gp.thread_id << ","
                                << gp.compute_order << ","
                                << gp.u << ","
                                << gp.v << ","
                                << gp.corr << "\n";
                    }
                }
            }
            csvFile.close();
            LOGD("[DEBUG] Raw CSV data dumped to %s", csvPath.c_str());
        } else {
            LOGE("[DEBUG] Failed to open CSV file for writing!");
        }
        g_debugDir = "";
    }

    defMat.release();
    roiMask.release();

    return (jint)valid_count;
}

} // extern "C"