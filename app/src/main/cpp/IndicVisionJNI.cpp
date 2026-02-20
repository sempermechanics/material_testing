#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <opencv2/features2d.hpp>
#include <queue>
#include "IndicVisionCore.h"
#include <chrono>

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "IndicVisionJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- OPTIMIZED AKAZE GLOBAL SHIFT ---
void computeGlobalShift(cv::Mat& ref, cv::Mat& def, double& u, double& v) {
    // 1. Scale images down by a factor of 4 for speed!
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

        // 2. Scale the answer BACK UP to full resolution!
        u = us[us.size()/2] * (1.0 / scale);
        v = vs[vs.size()/2] * (1.0 / scale);
        LOGD("Global Feature Match (Scaled): u=%.2f, v=%.2f", u, v);
    }
}

cv::Mat bytesToMat(JNIEnv* env, jbyteArray bytes) {
    jsize len = env->GetArrayLength(bytes);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(buf));
    std::vector<unsigned char> data(buf, buf + len);

    // Just decode the raw bytes. No physics manipulation here!
    cv::Mat img = cv::imdecode(data, cv::IMREAD_GRAYSCALE);
    delete[] buf;

    return img;
}

extern "C" {

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
        jfloat temp[] = {0,0,0,0,1};
        env->SetFloatArrayRegion(fail, 0, 5, temp);
        return fail;
    }

    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);

    refImg.prepare_data();
    defImg.prepare_data();

    auto engine = std::make_unique<IndicVision::Engine>();
    engine->set_reference(refImg, roiX, roiY, subsetSize);

    IndicVision::AnalysisResult result = engine->calculate_deformation(defImg, 0.0, 0.0, IndicVision::INIT_AUTO_SEARCH);

    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5] = { (jfloat)result.u, (jfloat)result.v, 0.0f, (jfloat)result.ux, (jfloat)result.status };
    env->SetFloatArrayRegion(output, 0, 5, temp);
    return output;
}

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
        jint startX, jint endX, jint y, jint step, jint subsetSize,
        jboolean useReliabilityGuided, jboolean useFeatureMatching,
        jobject callbackObj) {

    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);
    if (refMat.empty() || defMat.empty()) return nullptr;

    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    refImg.prepare_data(); defImg.prepare_data();

    double globalU = 0.0, globalV = 0.0;
    if (useFeatureMatching) computeGlobalShift(refMat, defMat, globalU, globalV);

    auto engine = std::make_unique<IndicVision::Engine>();
    int numPoints = (endX - startX) / step;
    if (numPoints <= 0) return nullptr;

    std::vector<float> results(numPoints, -999.0f);
    std::vector<bool> processed(numPoints, false);

    jclass callbackClass = env->GetObjectClass(callbackObj);
    jmethodID methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");

    if (useReliabilityGuided) {
        std::priority_queue<IndicVision::SeedNode> queue;
        int centerIdx = numPoints / 2;
        int centerX = startX + centerIdx * step;

        engine->set_reference(refImg, centerX, y, subsetSize);
        IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, globalU, globalV, IndicVision::INIT_AUTO_SEARCH);

        if (res.status == 0) {
            results[centerIdx] = (float)res.u;
            processed[centerIdx] = true;
            queue.push(IndicVision::SeedNode(centerIdx, 0, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
        }

        int count = 0, lastPercent = -1;
        while(!queue.empty()) {
            IndicVision::SeedNode seed = queue.top();
            queue.pop();
            int neighbors[] = {seed.x_idx - 1, seed.x_idx + 1};

            for (int idx: neighbors) {
                if (idx >= 0 && idx < numPoints && !processed[idx]) {
                    engine->set_reference(refImg, startX + idx * step, y, subsetSize);
                    IndicVision::AnalysisResult nRes = engine->calculate_deformation(defImg, seed.u, seed.v, IndicVision::INIT_NO_SEARCH);

                    if (nRes.status == 0) {
                        results[idx] = (float) nRes.u;
                        queue.push(IndicVision::SeedNode(idx, 0, nRes.u, nRes.v, nRes.ux, nRes.uy, nRes.vx, nRes.vy, nRes.correlation_score));
                    }
                    processed[idx] = true;
                    count++;
                    int percent = (count * 100) / numPoints;
                    if (percent != lastPercent) { env->CallVoidMethod(callbackObj, methodId, (jint)percent); lastPercent = percent; }
                }
            }
        }
    } else {
        double lastU = globalU, lastV = globalV;
        for (int i=0; i<numPoints; ++i) {
            engine->set_reference(refImg, startX + i * step, y, subsetSize);
            IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, lastU, lastV, IndicVision::INIT_AUTO_SEARCH);
            if(res.status==0) { results[i] = (float)res.u; lastU = res.u; lastV = res.v; }
            env->CallVoidMethod(callbackObj, methodId, (jint)((i * 100) / numPoints));
        }
    }

    jfloatArray output = env->NewFloatArray(results.size());
    env->SetFloatArrayRegion(output, 0, results.size(), results.data());
    return output;
}

// 6. COMPUTE FULL FIELD (2D Heatmap & Strain) - FULLY PROFILED
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeFullField(
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jint rectX, jint rectY, jint rectWidth, jint rectHeight,
        jint step, jint subsetSize, jint strainWindow,
        jboolean useReliabilityGuided, jboolean useFeatureMatching,
        jobject callbackObj) {

    auto start_total = std::chrono::high_resolution_clock::now();

    // --- PHASE 1: OpenCV Image Decoding ---
    auto start_decode = std::chrono::high_resolution_clock::now();
    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);
    if (refMat.empty() || defMat.empty()) return nullptr;
    auto end_decode = std::chrono::high_resolution_clock::now();

    // --- PHASE 2: Image Object Allocation ---
    auto start_alloc = std::chrono::high_resolution_clock::now();
    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);
    auto end_alloc = std::chrono::high_resolution_clock::now();

    // --- PHASE 3: Gradient Preprocessing ---
    auto start_prep = std::chrono::high_resolution_clock::now();
    refImg.prepare_data();
    defImg.prepare_data();
    auto end_prep = std::chrono::high_resolution_clock::now();

    // --- PHASE 4: AKAZE Feature Matching ---
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

    auto engine = std::make_unique<IndicVision::Engine>();
    int gridW = rectWidth / step;
    int gridH = rectHeight / step;
    if (gridW * gridH <= 0) return nullptr;

    struct GridPoint { float x, y, u, v, corr; bool solved; };
    std::vector<std::vector<GridPoint>> resultGrid(gridH, std::vector<GridPoint>(gridW, {0,0,0,0,0,false}));
    std::priority_queue<IndicVision::SeedNode> queue;

    jclass callbackClass = env->GetObjectClass(callbackObj);
    jmethodID methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");

    // --- PHASE 5: Initial Seed Search ---
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
                    engine->set_reference(refImg, seedX, seedY, subsetSize);

                    IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, globalU, globalV, IndicVision::INIT_AUTO_SEARCH);

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

    int count = 1, totalPoints = gridW * gridH;
    int dx[] = {1, -1, 0, 0}; int dy[] = {0, 0, 1, -1};

    // --- PHASE 6: PROPAGATION TRACKING ---
    auto start_track = std::chrono::high_resolution_clock::now();
    while(!queue.empty()) {
        IndicVision::SeedNode current = queue.top(); queue.pop();
        for(int k=0; k<4; ++k) {
            int nx = current.x_idx + dx[k], ny = current.y_idx + dy[k];

            if(nx >= 0 && nx < gridW && ny >= 0 && ny < gridH && !resultGrid[ny][nx].solved) {
                int realX = rectX + nx * step, realY = rectY + ny * step;
                engine->set_reference(refImg, realX, realY, subsetSize);
                IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, current.u, current.v, IndicVision::INIT_NO_SEARCH);

                resultGrid[ny][nx] = {(float)realX, (float)realY, (float)res.u, (float)res.v, (float)res.correlation_score, true};
                if (res.status == 0 && res.correlation_score < 0.3) {
                    queue.push(IndicVision::SeedNode(nx, ny, res.u, res.v, res.ux, res.uy, res.vx, res.vy, res.correlation_score));
                }
                if(++count % 50 == 0) env->CallVoidMethod(callbackObj, methodId, (jint)((count * 100) / totalPoints));
            }
        }
    }
    auto end_track = std::chrono::high_resolution_clock::now();

    // --- PHASE 7: STRAIN POST-PROCESSING ---
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

    // Change to compute_vsg_strain if you used Snippet B from earlier!
    IndicVision::StrainField strainField = IndicVision::StrainCalculator::compute_nlvc_strain(dispField, strainWindow);
    auto end_post = std::chrono::high_resolution_clock::now();

    // --- PHASE 8: JNI FLATTENING & MEMORY ALLOCATION ---
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

    // --- PRINT DETAILED PROFILE LOGS ---
    double t_decode = std::chrono::duration<double, std::milli>(end_decode - start_decode).count();
    double t_alloc = std::chrono::duration<double, std::milli>(end_alloc - start_alloc).count();
    double t_prep = std::chrono::duration<double, std::milli>(end_prep - start_prep).count();
    double t_akaze = std::chrono::duration<double, std::milli>(end_akaze - start_akaze).count();
    double t_seed = std::chrono::duration<double, std::milli>(end_seed - start_seed).count();
    double t_track = std::chrono::duration<double, std::milli>(end_track - start_track).count();
    double t_post = std::chrono::duration<double, std::milli>(end_post - start_post).count();
    double t_jni = std::chrono::duration<double, std::milli>(end_jni - start_jni).count();
    double t_total = std::chrono::duration<double, std::milli>(end_total - start_total).count();

    // Calculate internal overhead inside the tracking loop (Queue logic + Subset creation)
    double internal_overhead = t_track - engine->time_icgn_ms - engine->time_simplex_ms;

    LOGD("=== DETAILED DIC PERFORMANCE PROFILE ===");
    LOGD("1. OpenCV Bytes Decoding : %.2f ms", t_decode);
    LOGD("2. Raw Image Allocation  : %.2f ms", t_alloc);
    LOGD("3. Preprocessing (Grads) : %.2f ms", t_prep);
    LOGD("4. AKAZE Feature Match   : %.2f ms", t_akaze);
    LOGD("5. Initial Seed Search   : %.2f ms", t_seed);
    LOGD("6. Propagation Tracking  : %.2f ms", t_track);
    LOGD("     -> ICGN Time        : %.2f ms (Count: %d)", engine->time_icgn_ms, engine->count_icgn);
    LOGD("     -> Simplex Time     : %.2f ms (Count: %d)", engine->time_simplex_ms, engine->count_simplex);
    LOGD("     -> Engine Overhead  : %.2f ms", internal_overhead);
    LOGD("7. Strain Post-Process   : %.2f ms", t_post);
    LOGD("8. JNI Memory/Flattening : %.2f ms", t_jni);
    LOGD("----------------------------------------");
    LOGD("TOTAL JNI EXECUTION TIME : %.2f ms", t_total);
    LOGD("========================================");

    return output;
}
}

 // extern C