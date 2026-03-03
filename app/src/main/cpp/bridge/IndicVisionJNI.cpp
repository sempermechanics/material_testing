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
// UTILITY: AKAZE GLOBAL SHIFT
// ==========================================
void computeGlobalShift(cv::Mat& ref, cv::Mat& def, float& u, float& v) {
    double scale = 0.25;
    cv::Mat smallRef, smallDef;
    cv::resize(ref, smallRef, cv::Size(), scale, scale, cv::INTER_NEAREST);
    cv::resize(def, smallDef, cv::Size(), scale, scale, cv::INTER_NEAREST);

    auto detector = cv::AKAZE::create();
    std::vector<cv::KeyPoint> kp1, kp2;
    cv::Mat desc1, desc2;

    detector->detectAndCompute(smallRef, cv::noArray(), kp1, desc1);
    detector->detectAndCompute(smallDef, cv::noArray(), kp2, desc2);

    if(kp1.empty() || kp2.empty()) return;

    cv::BFMatcher matcher(cv::NORM_HAMMING);
    std::vector<std::vector<cv::DMatch>> matches;
    matcher.knnMatch(desc1, desc2, matches, 2);

    std::vector<cv::Point2f> p1, p2;
    for(auto& m : matches) {
        if(m.size() == 2 && m[0].distance < 0.75f * m[1].distance) {
            p1.push_back(kp1[m[0].queryIdx].pt);
            p2.push_back(kp2[m[0].trainIdx].pt);
        }
    }

    if(p1.size() > 5) {
        std::vector<float> us, vs;
        for(size_t i=0; i<p1.size(); ++i) {
            us.push_back(p2[i].x - p1[i].x);
            vs.push_back(p2[i].y - p1[i].y);
        }
        std::sort(us.begin(), us.end());
        std::sort(vs.begin(), vs.end());

        u = us[us.size()/2] * (1.0f / (float)scale);
        v = vs[vs.size()/2] * (1.0f / (float)scale);
        LOGD("Global Feature Match (Scaled): u=%.2f, v=%.2f", u, v);
    }
}

extern "C" {

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
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes, // Note: refBytes is mostly ignored now
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

    // ==============================================================
    // 🔍 DIAGNOSTIC: Frame counter + pointer validation
    // ==============================================================
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
                // To do AKAZE, we still need the original Reference CV Mat.
                // We'll quickly reconstruct a lightweight version of it from the cached bytes if needed.
                cv::Mat refMat = bytesToMat(env, refBytes, g_refWidth, g_refHeight);
                if (!refMat.empty()) {
                    cv::Mat refROI = refMat(roi);
                    cv::Mat defROI = defMat(roi);
                    computeGlobalShift(refROI, defROI, globalU, globalV);
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

    struct GridPoint { float x, y, u, v, corr; bool solved; };
    std::vector<std::vector<GridPoint>> resultGrid(gridH, std::vector<GridPoint>(gridW));

    int total_valid_points = 0;
    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            int realX = rectX + x * step;
            int realY = rectY + y * step;
            bool shouldSkip = (!roiMask.empty() && roiMask.at<uchar>(realY, realX) < 128);
            if (!shouldSkip) total_valid_points++;
            resultGrid[y][x] = {(float)realX, (float)realY, 0.0f, 0.0f, 0.0f, shouldSkip};
        }
    }

    if (total_valid_points == 0) return 0;

    // 5. PREPARE DYNAMIC SEEDS
    std::vector<IndicVision::SeedNode> global_seeds;
    int seedGx = gridW / 2;
    int seedGy = gridH / 2;
    int max_r = std::max(gridW, gridH) / 2;

    for (int r = 0; r <= max_r; ++r) {
        for (int i = -r; i <= r; ++i) {
            for (int j = -r; j <= r; ++j) {
                if (std::abs(i) != r && std::abs(j) != r) continue;
                int cx = seedGx + i, cy = seedGy + j;
                if (cx >= 0 && cx < gridW && cy >= 0 && cy < gridH && !resultGrid[cy][cx].solved) {
                    global_seeds.push_back(IndicVision::SeedNode(cx, cy, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f));
                }
            }
        }
    }

    if (global_seeds.empty()) return 0;

    // 6. PROGRESS REPORTING SETUP (Safe Threading)
    int total_cores = std::thread::hardware_concurrency();
    int safe_cores = std::max(1, total_cores);

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

    // ✅ FIX: Bypassing the buggy LLVM OpenMP runtime entirely.
    // The NDK libomp.so has a known bug causing SIGSEGV (SEGV_MAPERR) at
    // __kmp_invoke_microtask when executing nested parallel regions repeatedly via JNI,
    // due to corrupted internal TLS/task state. By using pure C++11 std::thread, we bypass
    // OpenMP's scheduler and use standard POSIX pthreads directly, guaranteeing stability
    // across thousands of batch frames.
    
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

                                // LOGD("  [PTHREAD %d] neighbor (%d,%d) precompute START", tid, nx, ny);
                                auto th1 = std::chrono::high_resolution_clock::now();
                                IndicVision::SubsetPrecomputer::precompute_subset(local_subset, *g_refImg, realX, realY, subsetSize);
                                auto th2 = std::chrono::high_resolution_clock::now();
                                local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                                if (!local_subset.is_initialized) {
                                    // LOGD("  [PTHREAD %d] neighbor (%d,%d) precompute FAILED", tid, nx, ny);
                                    continue;
                                }

                                // LOGD("  [PTHREAD %d] neighbor (%d,%d) calculate_deformation START", tid, nx, ny);
                                IndicVision::AnalysisResult res = local_engine.calculate_deformation(local_subset, defImg, current.u, current.v, IndicVision::INIT_NO_SEARCH);
                                // LOGD("  [PTHREAD %d] neighbor (%d,%d) calculate_deformation DONE (corr=%f)", tid, nx, ny, res.correlation_score);

                                {
                                    std::lock_guard<std::mutex> lock(grid_mutex);
                                    resultGrid[ny][nx] = {(float)realX, (float)realY, res.u, res.v, res.correlation_score, true};
                                }

                                if (res.status == 0 && res.correlation_score < 0.3f) {
                                    // LOGD("  [PTHREAD %d] pushing neighbor (%d,%d) to queue", tid, nx, ny);
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
                        // 🚀 USE THE CACHED GLOBAL REFERENCE IMAGE
                        IndicVision::SubsetPrecomputer::precompute_subset(local_subset, *g_refImg, realX, realY, subsetSize);
                        auto th2 = std::chrono::high_resolution_clock::now();
                        local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                        if (!local_subset.is_initialized) continue;

                        IndicVision::AnalysisResult res = local_engine.calculate_deformation(local_subset, defImg, globalU, globalV, IndicVision::INIT_AUTO_SEARCH);

                        {
                            std::lock_guard<std::mutex> lock(grid_mutex);
                            resultGrid[seed.y_idx][seed.x_idx] = {(float)realX, (float)realY, res.u, res.v, res.correlation_score, true};
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

    defMat.release();
    roiMask.release();

    return (jint)valid_count;
}

} // extern "C"