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
// 1. UTILITY: AKAZE GLOBAL SHIFT
// ==========================================
// 🚀 Converted parameters to float
void computeGlobalShift(cv::Mat& ref, cv::Mat& def, float& u, float& v) {
    double scale = 0.25; // Scale down 4x for extreme speed
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
        std::vector<float> us, vs; // 🚀 Float vectors
        for(size_t i=0; i<p1.size(); ++i) {
            us.push_back(p2[i].x - p1[i].x);
            vs.push_back(p2[i].y - p1[i].y);
        }
        std::sort(us.begin(), us.end());
        std::sort(vs.begin(), vs.end());

        // Scale the answer back up to full resolution
        u = us[us.size()/2] * (1.0f / (float)scale);
        v = vs[vs.size()/2] * (1.0f / (float)scale);
        LOGD("Global Feature Match (Scaled): u=%.2f, v=%.2f", u, v);
    }
}

// ==========================================
// 2. UTILITY: BYTES TO MAT
// ==========================================
cv::Mat bytesToMat(JNIEnv* env, jbyteArray bytes) {
    jsize len = env->GetArrayLength(bytes);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(buf));
    std::vector<unsigned char> data(buf, buf + len);

    cv::Mat img = cv::imdecode(data, cv::IMREAD_GRAYSCALE);
    delete[] buf;
    return img;
}

extern "C" {

// ==========================================
// 3. UI PREVIEW GENERATOR
// ==========================================
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
// 4. IMAGE DIMENSIONS
// ==========================================
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

// ==========================================
// 5. ANALYZE SINGLE POINT (1D / LIVE MODE)
// ==========================================
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_analyzeRawBytes(
        JNIEnv* env, jobject,
        jbyteArray refBytes, jbyteArray defBytes,
        jint roiX, jint roiY, jint subsetSize,
        jint originalWidth, jint originalHeight) {

    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);

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
    float startU = 0.0f; // 🚀 Float
    float startV = 0.0f; // 🚀 Float
    IndicVision::AnalysisResult res = engine.calculate_deformation(subset, defImg, startU, startV, IndicVision::INIT_AUTO_SEARCH);

    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5] = { res.u, res.v, 0.0f, res.ux, (jfloat)res.status };
    env->SetFloatArrayRegion(output, 0, 5, temp);
    return output;
}

// 6. COMPUTE FULL FIELD (2D HEATMAP & STRAIN)
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeFullField(
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jbyteArray maskBytes,
        jint rectX, jint rectY, jint rectWidth, jint rectHeight,
        jint step, jint subsetSize, jint strainWindow,
        jboolean useReliabilityGuided, jboolean useFeatureMatching,
        jboolean applyGaussianBlur, jboolean useNlvcStrain,
        jobject callbackObj) {

    auto start_total = std::chrono::high_resolution_clock::now();

    // --- 1. JNI & DECODING ---
    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);
    if (refMat.empty() || defMat.empty()) return nullptr;

    cv::Mat roiMask;
    if (maskBytes != nullptr) {
        jsize maskLen = env->GetArrayLength(maskBytes);
        if (maskLen > 0) {
            roiMask = bytesToMat(env, maskBytes);
            if (!roiMask.empty() && (roiMask.cols != refMat.cols || roiMask.rows != refMat.rows)) {
                cv::resize(roiMask, roiMask, refMat.size(), 0, 0, cv::INTER_NEAREST);
            }
        }
    }

    if (applyGaussianBlur) {
        cv::GaussianBlur(refMat, refMat, cv::Size(7, 7), 0);
        cv::GaussianBlur(defMat, defMat, cv::Size(7, 7), 0);
    }

    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    refImg.prepare_data();
    defImg.prepare_data();

    // --- 2. GLOBAL AKAZE SHIFT ---
    float globalU = 0.0f, globalV = 0.0f; // 🚀 Float
    if (useFeatureMatching) {
        cv::Rect roi(rectX, rectY, rectWidth, rectHeight);
        roi = roi & cv::Rect(0, 0, refMat.cols, refMat.rows);
        if (roi.width > 32 && roi.height > 32) {
            try {
                cv::Mat refROI = refMat(roi); cv::Mat defROI = defMat(roi);
                computeGlobalShift(refROI, defROI, globalU, globalV);
            } catch (const cv::Exception& e) {
                LOGE("AKAZE Exception. Ignoring shift.");
            }
        }
    }

    // --- 3. GRID ALLOCATION ---
    int gridW = rectWidth / step;
    int gridH = rectHeight / step;
    if (gridW <= 0 || gridH <= 0) return nullptr;

    struct GridPoint { float x, y, u, v, corr; bool solved; };
    std::vector<std::vector<GridPoint>> resultGrid(gridH, std::vector<GridPoint>(gridW));

    int total_valid_points = 0;

    for (int y = 0; y < gridH; ++y) {
        for (int x = 0; x < gridW; ++x) {
            int realX = rectX + x * step;
            int realY = rectY + y * step;
            bool shouldSkip = false;

            if (!roiMask.empty() && roiMask.at<uchar>(realY, realX) < 128) {
                shouldSkip = true;
            } else {
                total_valid_points++;
            }
            resultGrid[y][x] = {(float)realX, (float)realY, 0.0f, 0.0f, 0.0f, shouldSkip};
        }
    }

    if (total_valid_points == 0) return nullptr;

    // --- 4. PREPARE DYNAMIC SEEDS ---
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

    if (global_seeds.empty()) return nullptr;

    // --- 5. PARALLEL EXECUTION & PROGRESS REPORTING ---
    int total_cores = std::thread::hardware_concurrency();
    int safe_cores = std::max(1, total_cores);

    // Trackers stay double to hold absolute MS precision
    std::vector<double> t_icgn_arr(safe_cores, 0.0), t_simplex_arr(safe_cores, 0.0), t_hessian_arr(safe_cores, 0.0);
    std::vector<int> c_icgn_arr(safe_cores, 0), c_simplex_arr(safe_cores, 0), c_points_arr(safe_cores, 0);

    std::atomic<int> seed_index(0);
    std::atomic<int> global_points_solved(0);
    std::atomic<bool> computation_running(true);
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

        while (computation_running) {
            int solved = global_points_solved.load(std::memory_order_relaxed);
            int percentage = (int)((((float)solved / total_valid_points) * 80.0f) + 10.0f);
            pEnv->CallVoidMethod(globalCallbackObj, methodId, (jint)percentage);
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
        jvm->DetachCurrentThread();
    });

    auto start_track = std::chrono::high_resolution_clock::now();

#pragma omp parallel num_threads(safe_cores)
    {
        int tid = omp_get_thread_num();
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
                        IndicVision::SubsetPrecomputer::precompute_subset(local_subset, refImg, realX, realY, subsetSize);
                        auto th2 = std::chrono::high_resolution_clock::now();
                        local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                        if (!local_subset.is_initialized) continue;

                        IndicVision::AnalysisResult res = local_engine.calculate_deformation(local_subset, defImg, current.u, current.v, IndicVision::INIT_NO_SEARCH);

                        {
                            std::lock_guard<std::mutex> lock(grid_mutex);
                            resultGrid[ny][nx] = {(float)realX, (float)realY, res.u, res.v, res.correlation_score, true};
                        }

                        if (res.status == 0 && res.correlation_score < 0.3f) {
                            IndicVision::SeedNode childNode(nx, ny, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score);
                            local_queue.push(childNode);
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
                IndicVision::SubsetPrecomputer::precompute_subset(local_subset, refImg, realX, realY, subsetSize);
                auto th2 = std::chrono::high_resolution_clock::now();
                local_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();

                if (!local_subset.is_initialized) continue;

                IndicVision::AnalysisResult res = local_engine.calculate_deformation(local_subset, defImg, globalU, globalV, IndicVision::INIT_AUTO_SEARCH);

                {
                    std::lock_guard<std::mutex> lock(grid_mutex);
                    resultGrid[seed.y_idx][seed.x_idx] = {(float)realX, (float)realY, res.u, res.v, res.correlation_score, true};
                }

                if (res.status == 0 && res.correlation_score < 0.15f) {
                    IndicVision::SeedNode newSeed(seed.x_idx, seed.y_idx, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score);
                    local_queue.push(newSeed);

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
    }

    auto end_track = std::chrono::high_resolution_clock::now();

    computation_running = false;
    if (progress_thread.joinable()) {
        progress_thread.join();
    }

    if (globalCallbackObj != nullptr) {
        env->DeleteGlobalRef(globalCallbackObj);
    }

    // --- 6. STRAIN POST-PROCESSING ---
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

    if (callbackObj != nullptr && methodId != nullptr) {
        env->CallVoidMethod(callbackObj, methodId, (jint)95);
    }

    // --- 7. JNI FLATTENING ---
    std::vector<float> flatOutput;
    flatOutput.reserve(global_points_solved.load() * 8);

    for(int y=0; y<gridH; ++y) {
        for(int x=0; x<gridW; ++x) {
            int idx = y * gridW + x;
            if (dispField.valid[idx]) {
                flatOutput.push_back(resultGrid[y][x].x);
                flatOutput.push_back(resultGrid[y][x].y);
                flatOutput.push_back(dispField.u[idx]);
                flatOutput.push_back(dispField.v[idx]);
                flatOutput.push_back(strainField.exx[idx]);
                flatOutput.push_back(strainField.eyy[idx]);
                flatOutput.push_back(strainField.exy[idx]);
                flatOutput.push_back(resultGrid[y][x].corr);
            }
        }
    }

    jfloatArray output = env->NewFloatArray(flatOutput.size());
    env->SetFloatArrayRegion(output, 0, flatOutput.size(), flatOutput.data());

    if (callbackObj != nullptr && methodId != nullptr) {
        env->CallVoidMethod(callbackObj, methodId, (jint)100);
    }

    auto end_total = std::chrono::high_resolution_clock::now();

    double t_track = std::chrono::duration<double, std::milli>(end_track - start_track).count();
    LOGD("=== OPENMP RGDIC PERFORMANCE PROFILE ===");
    LOGD("OpenMP Wall Time         : %.2f ms", t_track);
    LOGD("Total Points Solved      : %d", global_points_solved.load());
    for(int i=0; i<safe_cores; i++) {
        LOGD(" Thread %d: Pts=%d, Hessian=%.1fms, ICGN=%.1fms, Simplex=%.1fms",
             i, c_points_arr[i], t_hessian_arr[i], t_icgn_arr[i], t_simplex_arr[i]);
    }
    LOGD("Total JNI Execution Time : %.2f ms", std::chrono::duration<double, std::milli>(end_total - start_total).count());
    LOGD("========================================");

    return output;
}
} // extern "C"