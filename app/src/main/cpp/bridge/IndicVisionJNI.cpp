#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <opencv2/features2d.hpp>
#include <queue>
#include <chrono>

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
void computeGlobalShift(cv::Mat& ref, cv::Mat& def, double& u, double& v) {
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
        if(m.size() == 2 && m[0].distance < 0.75 * m[1].distance) {
            p1.push_back(kp1[m[0].queryIdx].pt);
            p2.push_back(kp2[m[0].trainIdx].pt);
        }
    }

    if(p1.size() > 5) {
        std::vector<double> us, vs;
        for(size_t i=0; i<p1.size(); ++i) {
            us.push_back(p2[i].x - p1[i].x);
            vs.push_back(p2[i].y - p1[i].y);
        }
        std::sort(us.begin(), us.end());
        std::sort(vs.begin(), vs.end());

        // Scale the answer back up to full resolution
        u = us[us.size()/2] * (1.0 / scale);
        v = vs[vs.size()/2] * (1.0 / scale);
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
        jfloat temp[] = {0,0,0,0,1}; // Status 1 = Fail
        env->SetFloatArrayRegion(fail, 0, 5, temp);
        return fail;
    }

    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);

    refImg.prepare_data();
    defImg.prepare_data();

    // Fix: Use correct parameters roiX and roiY
    IndicVision::SubsetData subset;
    IndicVision::SubsetPrecomputer::precompute_subset(subset, refImg, roiX, roiY, subsetSize);

    // Fix: Use 0.0 starting guess since single point doesn't run AKAZE
    IndicVision::OptimizationEngine engine;
    double startU = 0.0;
    double startV = 0.0;
    IndicVision::AnalysisResult res = engine.calculate_deformation(subset, defImg, startU, startV, IndicVision::INIT_AUTO_SEARCH);

    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5] = { (jfloat)res.u, (jfloat)res.v, 0.0f, (jfloat)res.ux, (jfloat)res.status };
    env->SetFloatArrayRegion(output, 0, 5, temp);
    return output;
}

// 6. COMPUTE FULL FIELD (2D HEATMAP & STRAIN)
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeFullField(
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jint rectX, jint rectY, jint rectWidth, jint rectHeight,
        jint step, jint subsetSize, jint strainWindow,
        jboolean useReliabilityGuided, jboolean useFeatureMatching,
        jobject callbackObj) {

    auto start_total = std::chrono::high_resolution_clock::now();

    auto start_decode = std::chrono::high_resolution_clock::now();
    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);
    if (refMat.empty() || defMat.empty()) return nullptr;
    auto end_decode = std::chrono::high_resolution_clock::now();

    auto start_alloc = std::chrono::high_resolution_clock::now();
    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    auto end_alloc = std::chrono::high_resolution_clock::now();

    auto start_prep = std::chrono::high_resolution_clock::now();
    refImg.prepare_data();
    defImg.prepare_data();
    auto end_prep = std::chrono::high_resolution_clock::now();

    auto start_akaze = std::chrono::high_resolution_clock::now();
    double globalU = 0.0, globalV = 0.0;
    if (useFeatureMatching) {
        cv::Rect roi(rectX, rectY, rectWidth, rectHeight);
        roi = roi & cv::Rect(0, 0, refMat.cols, refMat.rows);
        if (roi.width > 0 && roi.height > 0) {
            cv::Mat refROI = refMat(roi); cv::Mat defROI = defMat(roi);
            computeGlobalShift(refROI, defROI, globalU, globalV);
        }
    }
    auto end_akaze = std::chrono::high_resolution_clock::now();

    int gridW = rectWidth / step;
    int gridH = rectHeight / step;
    if (gridW * gridH <= 0) return nullptr;

    struct GridPoint { float x, y, u, v, corr; bool solved; };
    std::vector<std::vector<GridPoint>> resultGrid(gridH, std::vector<GridPoint>(gridW, {0,0,0,0,0,false}));
    std::priority_queue<IndicVision::SeedNode> queue;

    jclass callbackClass = env->GetObjectClass(callbackObj);
    jmethodID methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");

    // The single instances that will be recycled!
    IndicVision::OptimizationEngine engine;
    IndicVision::SubsetData shared_subset;

    // -----------------------------------------------------------
    // INITIAL SEED SEARCH
    // -----------------------------------------------------------
    auto start_seed = std::chrono::high_resolution_clock::now();
    int seedGx = gridW / 2, seedGy = gridH / 2;
    bool seedFound = false;
    for (int r = 0; r <= std::max(gridW, gridH) / 2; ++r) {
        for (int i = -r; i <= r; ++i) {
            for (int j = -r; j <= r; ++j) {
                if (std::abs(i) != r && std::abs(j) != r) continue;
                int cx = seedGx + i, cy = seedGy + j;
                if (cx >= 0 && cx < gridW && cy >= 0 && cy < gridH) {

                    int seedX = rectX + cx * step;
                    int seedY = rectY + cy * step;

                    // Calculate Hessian ON THE FLY
                    IndicVision::SubsetPrecomputer::precompute_subset(shared_subset, refImg, seedX, seedY, subsetSize);

                    if (!shared_subset.is_initialized) continue; // Boundary safety

                    IndicVision::AnalysisResult res = engine.calculate_deformation(shared_subset, defImg, globalU, globalV, IndicVision::INIT_AUTO_SEARCH);

                    if (res.status == 0 && res.correlation_score < 0.15) {
                        resultGrid[cy][cx] = {(float)seedX, (float)seedY, (float)res.u, (float)res.v, (float)res.correlation_score, true};
                        queue.push(IndicVision::SeedNode(cx, cy, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                        seedFound = true;
                        goto seed_loop_end;
                    }
                }
            }
        }
    }
    seed_loop_end:
    if (!seedFound) return nullptr;
    auto end_seed = std::chrono::high_resolution_clock::now();

    // -----------------------------------------------------------
    // PROPAGATION TRACKING
    // -----------------------------------------------------------
    int count = 1, totalPoints = gridW * gridH;
    int dx[] = {1, -1, 0, 0}; int dy[] = {0, 0, 1, -1};

    double time_hessian_ms = 0.0;
    auto start_track = std::chrono::high_resolution_clock::now();
    while(!queue.empty()) {
        IndicVision::SeedNode current = queue.top(); queue.pop();
        for(int k=0; k<4; ++k) {
            int nx = current.x_idx + dx[k], ny = current.y_idx + dy[k];

            if(nx >= 0 && nx < gridW && ny >= 0 && ny < gridH && !resultGrid[ny][nx].solved) {

                int realX = rectX + nx * step;
                int realY = rectY + ny * step;

                // Calculate Hessian ON THE FLY
                auto th1 = std::chrono::high_resolution_clock::now();
                IndicVision::SubsetPrecomputer::precompute_subset(shared_subset, refImg, realX, realY, subsetSize);
                auto th2 = std::chrono::high_resolution_clock::now();
                time_hessian_ms += std::chrono::duration<double, std::milli>(th2 - th1).count();
                if (!shared_subset.is_initialized) continue; // Boundary safety

                IndicVision::AnalysisResult res = engine.calculate_deformation(shared_subset, defImg, current.u, current.v, IndicVision::INIT_NO_SEARCH);

                resultGrid[ny][nx] = {(float)realX, (float)realY, (float)res.u, (float)res.v, (float)res.correlation_score, true};
                if (res.status == 0 && res.correlation_score < 0.3) {
                    queue.push(IndicVision::SeedNode(nx, ny, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                }
                if(++count % 50 == 0) env->CallVoidMethod(callbackObj, methodId, (jint)((count * 100) / totalPoints));
            }
        }
    }
    auto end_track = std::chrono::high_resolution_clock::now();

    // -----------------------------------------------------------
    // STRAIN POST-PROCESSING
    // -----------------------------------------------------------
    auto start_post = std::chrono::high_resolution_clock::now();
    IndicVision::DisplacementField dispField;
    dispField.width = gridW;
    dispField.height = gridH;
    dispField.step = step;
    dispField.u.resize(gridW * gridH, 0.0);
    dispField.v.resize(gridW * gridH, 0.0);
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

    IndicVision::StrainField strainField = IndicVision::StrainCalculator::compute_vsg_strain(dispField, strainWindow);
    auto end_post = std::chrono::high_resolution_clock::now();

    // -----------------------------------------------------------
    // JNI FLATTENING
    // -----------------------------------------------------------
    auto start_jni = std::chrono::high_resolution_clock::now();
    std::vector<float> flatOutput;
    flatOutput.reserve(count * 8);

    for(int y=0; y<gridH; ++y) {
        for(int x=0; x<gridW; ++x) {
            int idx = y * gridW + x;
            if (dispField.valid[idx]) {
                flatOutput.push_back(resultGrid[y][x].x);
                flatOutput.push_back(resultGrid[y][x].y);
                flatOutput.push_back((float)dispField.u[idx]);
                flatOutput.push_back((float)dispField.v[idx]);
                flatOutput.push_back((float)strainField.exx[idx]);
                flatOutput.push_back((float)strainField.eyy[idx]);
                flatOutput.push_back((float)strainField.exy[idx]);
                flatOutput.push_back(resultGrid[y][x].corr);
            }
        }
    }

    jfloatArray output = env->NewFloatArray(flatOutput.size());
    env->SetFloatArrayRegion(output, 0, flatOutput.size(), flatOutput.data());
    auto end_jni = std::chrono::high_resolution_clock::now();

    auto end_total = std::chrono::high_resolution_clock::now();

    // -----------------------------------------------------------
    // PROFILING LOGS
    // -----------------------------------------------------------
    double t_decode = std::chrono::duration<double, std::milli>(end_decode - start_decode).count();
    double t_alloc = std::chrono::duration<double, std::milli>(end_alloc - start_alloc).count();
    double t_prep = std::chrono::duration<double, std::milli>(end_prep - start_prep).count();
    double t_akaze = std::chrono::duration<double, std::milli>(end_akaze - start_akaze).count();
    double t_seed = std::chrono::duration<double, std::milli>(end_seed - start_seed).count();
    double t_track = std::chrono::duration<double, std::milli>(end_track - start_track).count();
    double internal_queue_overhead = t_track - engine.time_icgn_ms - engine.time_simplex_ms - time_hessian_ms;
    double t_post = std::chrono::duration<double, std::milli>(end_post - start_post).count();
    double t_jni = std::chrono::duration<double, std::milli>(end_jni - start_jni).count();
    double t_total = std::chrono::duration<double, std::milli>(end_total - start_total).count();

    double internal_overhead = t_track - engine.time_icgn_ms - engine.time_simplex_ms;

    LOGD("=== DETAILED DIC PERFORMANCE PROFILE ===");
    LOGD("1. OpenCV Bytes Decoding : %.2f ms", t_decode);
    LOGD("2. Raw Image Allocation  : %.2f ms", t_alloc);
    LOGD("3. Preprocessing (Grads) : %.2f ms", t_prep);
    LOGD("4. AKAZE Feature Match   : %.2f ms", t_akaze);
    LOGD("5. Initial Seed Search   : %.2f ms", t_seed);
    LOGD("6. Propagation Tracking  : %.2f ms", t_track);
    LOGD("     -> ICGN Math Time   : %.2f ms (Count: %d)", engine.time_icgn_ms, engine.count_icgn);
    LOGD("     -> Simplex Time     : %.2f ms (Count: %d)", engine.time_simplex_ms, engine.count_simplex);
    LOGD("     -> Hessian Setup    : %.2f ms", time_hessian_ms);
    LOGD("     -> Queue Overhead   : %.2f ms", internal_queue_overhead);
    LOGD("7. Strain Post-Process   : %.2f ms", t_post);
    LOGD("8. JNI Memory/Flattening : %.2f ms", t_jni);
    LOGD("----------------------------------------");
    LOGD("TOTAL JNI EXECUTION TIME : %.2f ms", t_total);
    LOGD("========================================");

    return output;
}

} // extern "C"