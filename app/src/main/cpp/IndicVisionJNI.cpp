#include <jni.h>
#include <string>
#include <vector>
#include <android/bitmap.h>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <opencv2/features2d.hpp>
#include <queue>
#include <set>
#include "IndicVisionCore.h"

#ifdef LOG_TAG
#undef LOG_TAG
#endif
#define LOG_TAG "IndicVisionJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// --- Helper: Global Feature Matching (AKAZE) ---
void computeGlobalShift(cv::Mat& ref, cv::Mat& def, double& u, double& v) {
    auto detector = cv::AKAZE::create();
    std::vector<cv::KeyPoint> kp1, kp2;
    cv::Mat desc1, desc2;

    detector->detectAndCompute(ref, cv::noArray(), kp1, desc1);
    detector->detectAndCompute(def, cv::noArray(), kp2, desc2);

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
        u = us[us.size()/2];
        v = vs[vs.size()/2];
        LOGD("Global Feature Match: u=%.2f, v=%.2f", u, v);
    }
}

// Helper to convert and pre-filter images like DICe
cv::Mat bytesToMat(JNIEnv* env, jbyteArray bytes) {
    jsize len = env->GetArrayLength(bytes);
    unsigned char* buf = new unsigned char[len];
    env->GetByteArrayRegion(bytes, 0, len, reinterpret_cast<jbyte*>(buf));
    std::vector<unsigned char> data(buf, buf + len);
    cv::Mat img = cv::imdecode(data, cv::IMREAD_GRAYSCALE);
    delete[] buf;

    if (!img.empty()) {
        cv::GaussianBlur(img, img, cv::Size(0, 0), 0.8);
    }
    return img;
}

extern "C" {

// 2. GET PREVIEW
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

// 3. ANALYZE RAW BYTES (Single Point)
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_analyzeRawBytes(
        JNIEnv* env, jobject,
        jbyteArray refBytes, jbyteArray defBytes,
        jint roiX, jint roiY, jint subsetSize,
        jint originalWidth, jint originalHeight, jint interpId) {

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

    if (interpId == 2) {
        refImg.set_settings(IndicVision::INTERP_BSPLINE, IndicVision::GRAD_BSPLINE_ANALYTIC);
        defImg.set_settings(IndicVision::INTERP_BSPLINE, IndicVision::GRAD_BSPLINE_ANALYTIC);
    } else if (interpId == 1) {
        refImg.set_settings(IndicVision::INTERP_LANCZOS, IndicVision::GRAD_CENTRAL_DIFF);
        defImg.set_settings(IndicVision::INTERP_LANCZOS, IndicVision::GRAD_CENTRAL_DIFF);
    } else {
        refImg.set_settings(IndicVision::INTERP_BICUBIC, IndicVision::GRAD_CENTRAL_DIFF);
        defImg.set_settings(IndicVision::INTERP_BICUBIC, IndicVision::GRAD_CENTRAL_DIFF);
    }

    refImg.prepare_data();
    defImg.prepare_data();

    auto engine = std::make_unique<IndicVision::Engine>();
    engine->set_reference(refImg, roiX, roiY, subsetSize);

    // FIXED: Added 4th param INIT_GRID_SEARCH
    IndicVision::AnalysisResult result = engine->calculate_deformation(defImg, 0.0, 0.0, IndicVision::INIT_GRID_SEARCH);

    jfloatArray output = env->NewFloatArray(5);
    jfloat temp[5];
    temp[0] = static_cast<jfloat>(result.u);
    temp[1] = static_cast<jfloat>(result.v);
    temp[2] = static_cast<jfloat>(0.0);
    temp[3] = static_cast<jfloat>(result.ux);
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
// In IndicVisionJNI.cpp
struct CompareSeedNode {
    bool operator()(const IndicVision::SeedNode& a, const IndicVision::SeedNode& b) {
        // Change 'correlation_score' to 'correlation'
        return a.correlation > b.correlation;
    }
};
// 5. COMPUTE LINE PROFILE
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeLineProfile(
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jint startX, jint endX, jint y, jint step, jint subsetSize,
        jint interpId,
        jboolean useReliabilityGuided,
        jboolean useFeatureMatching,
        jobject callbackObj) {

    // 1. Prepare Images
    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);
    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);

    LOGD("--- STARTING LINE PROFILE ---");
    LOGD("Scan Line Y=%d, Range X[%d - %d], Step=%d", y, startX, endX);

    // 2. Interpolator Settings
    if (interpId == 2) {
        refImg.set_settings(IndicVision::INTERP_BSPLINE, IndicVision::GRAD_BSPLINE_ANALYTIC);
        defImg.set_settings(IndicVision::INTERP_BSPLINE, IndicVision::GRAD_BSPLINE_ANALYTIC);
    } else if (interpId == 1) {
        refImg.set_settings(IndicVision::INTERP_LANCZOS, IndicVision::GRAD_CENTRAL_DIFF);
        defImg.set_settings(IndicVision::INTERP_LANCZOS, IndicVision::GRAD_CENTRAL_DIFF);
    } else {
        refImg.set_settings(IndicVision::INTERP_BICUBIC, IndicVision::GRAD_CENTRAL_DIFF);
        defImg.set_settings(IndicVision::INTERP_BICUBIC, IndicVision::GRAD_CENTRAL_DIFF);
    }

    refImg.prepare_data();
    defImg.prepare_data();

    // 3. Global Initialization (Feature Matching)
    double globalU = 0.0;
    double globalV = 0.0;
    if (useFeatureMatching) {
        computeGlobalShift(refMat, defMat, globalU, globalV);
    }

    auto engine = std::make_unique<IndicVision::Engine>();

    // 4. Result Storage
    int numPoints = (endX - startX) / step;
    std::vector<float> results(numPoints, -999.0f);
    std::vector<bool> processed(numPoints, false);

    jclass callbackClass = env->GetObjectClass(callbackObj);
    jmethodID methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");

    if (useReliabilityGuided) {
        // --- RELIABILITY GUIDED SCAN ---
        // Priority Queue (Min-Heap based on Correlation Score)
        std::priority_queue<IndicVision::SeedNode, std::vector<IndicVision::SeedNode>, CompareSeedNode> queue;

        // A. Start at Center
        int centerIdx = numPoints / 2;
        int centerX = startX + centerIdx * step;

        LOGD("Calculated Center Seed: Index=%d, X=%d, Y=%d", centerIdx, centerX, y);

        engine->set_reference(refImg, centerX, y, subsetSize);
        // Use Global Feature Match as guess for the very first point
        IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, globalU, globalV, IndicVision::INIT_GRID_SEARCH);

        if (res.status == 0) {
            results[centerIdx] = (float)res.u;
            processed[centerIdx] = true;

            // Push Seed to Queue (9 Arguments)
            queue.push({
                               centerIdx, 0,           // x_idx, y_idx
                               res.u, res.v,           // u, v
                               res.ux, res.uy,         // ux, uy
                               res.vx, res.vy,         // vx, vy
                               res.correlation_score   // correlation
                       });

            LOGD("SEED SUCCESS: U=%.4f, Score=%.5f", res.u, res.correlation_score);
        } else {
            LOGE("SEED FAILED: Status=%d. Aborting scan.", res.status);
        }

        // B. Propagate
        int count = 0;
        int lastPercent = -1;

        while(!queue.empty()) {
            IndicVision::SeedNode seed = queue.top();
            queue.pop();

            int neighbors[] = {seed.x_idx - 1, seed.x_idx + 1};

            for (int idx: neighbors) {
                // Check bounds and if already processed
                if (idx >= 0 && idx < numPoints && !processed[idx]) {
                    int realX = startX + idx * step;

                    // >>> DIAGNOSTIC LOG: What guess are we passing? <<<
                    // This helps identify if we are passing 0.0 or the previous result
                    // Only log every 5th point to keep logcat somewhat readable but useful
                    if (idx % 5 == 0) {
                        LOGD("Processing X=%d. Using Guess from Neighbor: U=%.6f, V=%.6f", realX, seed.u, seed.v);
                    }

                    engine->set_reference(refImg, realX, y, subsetSize);

                    // PASS NEIGHBOR'S SOLUTION AS GUESS
                    IndicVision::AnalysisResult nRes = engine->calculate_deformation(
                            defImg, seed.u, seed.v, IndicVision::INIT_GRID_SEARCH
                    );

                    if (nRes.status == 0) {
                        results[idx] = (float) nRes.u;

                        // Push Neighbor to Queue (9 Arguments)
                        queue.push({
                                           idx, 0,                 // x_idx (The Neighbor!), y_idx
                                           nRes.u, nRes.v,         // u, v (The New Result!)
                                           nRes.ux, nRes.uy,       // ux, uy
                                           nRes.vx, nRes.vy,       // vx, vy
                                           nRes.correlation_score  // correlation
                                   });

                        // LOGD("  > Solved X=%d: U=%.4f", realX, nRes.u);
                    } else {
                        // LOGD("  > Failed X=%d", realX);
                    }

                    processed[idx] = true;
                    count++;

                    // Throttle UI Updates (Every 1% change only)
                    int percent = (count * 100) / numPoints;
                    if (percent != lastPercent) {
                        env->CallVoidMethod(callbackObj, methodId, (jint)percent);
                        lastPercent = percent;
                    }
                }
            }
        }
        LOGD("Reliability Guided Loop Finished. Processed %d points.", count);

    } else {
        // --- STANDARD SCAN (Left-to-Right) ---
        double lastU = globalU;
        double lastV = globalV;

        for (int i=0; i<numPoints; ++i) {
            int x = startX + i * step;
            engine->set_reference(refImg, x, y, subsetSize);

            IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, lastU, lastV, IndicVision::INIT_GRID_SEARCH);

            if(res.status==0) {
                results[i] = (float)res.u;
                lastU = res.u; // Update guess for next point
                lastV = res.v;
            }
            // Simple UI update
            env->CallVoidMethod(callbackObj, methodId, (jint)((i * 100) / numPoints));
        }
    }

    jfloatArray output = env->NewFloatArray(results.size());
    env->SetFloatArrayRegion(output, 0, results.size(), results.data());
    return output;
}
// 6. COMPUTE FULL FIELD (2D Heatmap)
JNIEXPORT jfloatArray JNICALL
Java_com_rafad_indicvisiondic_IndicVisionNativeLib_computeFullField(
        JNIEnv* env, jobject, jbyteArray refBytes, jbyteArray defBytes,
        jint rectX, jint rectY, jint rectWidth, jint rectHeight,
        jint step, jint subsetSize,
        jint interpId,
        jboolean useReliabilityGuided,
        jboolean useFeatureMatching,
        jobject callbackObj) {

    cv::Mat refMat = bytesToMat(env, refBytes);
    cv::Mat defMat = bytesToMat(env, defBytes);
    IndicVision::Image refImg(refMat.cols, refMat.rows, refMat.data);
    IndicVision::Image defImg(defMat.cols, defMat.rows, defMat.data);

    // Settings (Same as 1D)
    if (interpId == 2) {
        refImg.set_settings(IndicVision::INTERP_BSPLINE, IndicVision::GRAD_BSPLINE_ANALYTIC);
        defImg.set_settings(IndicVision::INTERP_BSPLINE, IndicVision::GRAD_BSPLINE_ANALYTIC);
    } else if (interpId == 1) {
        refImg.set_settings(IndicVision::INTERP_LANCZOS, IndicVision::GRAD_CENTRAL_DIFF);
        defImg.set_settings(IndicVision::INTERP_LANCZOS, IndicVision::GRAD_CENTRAL_DIFF);
    } else {
        refImg.set_settings(IndicVision::INTERP_BICUBIC, IndicVision::GRAD_CENTRAL_DIFF);
        defImg.set_settings(IndicVision::INTERP_BICUBIC, IndicVision::GRAD_CENTRAL_DIFF);
    }

    refImg.prepare_data();
    defImg.prepare_data();

    // Global Match
    double globalU = 0.0, globalV = 0.0;
    if (useFeatureMatching) {
        computeGlobalShift(refMat, defMat, globalU, globalV);
    }

    auto engine = std::make_unique<IndicVision::Engine>();

    // Grid Dimensions
    int gridW = rectWidth / step;
    int gridH = rectHeight / step;
    int totalPoints = gridW * gridH;

    // Output Storage: [x, y, u, v, corr] per point
    // We flatten it later.
    std::vector<std::vector<float>> resultGrid(gridH, std::vector<float>(gridW * 5, 0.0f));
    std::vector<std::vector<bool>> processed(gridH, std::vector<bool>(gridW, false));

    jclass callbackClass = env->GetObjectClass(callbackObj);
    jmethodID methodId = env->GetMethodID(callbackClass, "onProgressUpdate", "(I)V");

    // --- SEED INITIALIZATION (Center of ROI) ---
    std::priority_queue<IndicVision::SeedNode> queue;

    int seedGx = gridW / 2;
    int seedGy = gridH / 2;
    int seedX = rectX + seedGx * step;
    int seedY = rectY + seedGy * step;

    engine->set_reference(refImg, seedX, seedY, subsetSize);
    IndicVision::AnalysisResult seedRes = engine->calculate_deformation(defImg, globalU, globalV, IndicVision::INIT_GRID_SEARCH);

    // ... inside computeFullField, after calculating seed ...
    if (seedRes.status == 0) {
        // UPDATE THIS LINE: Provide 9 arguments
        queue.push({
                           seedGx, seedGy,             // x_idx, y_idx
                           seedRes.u, seedRes.v,       // u, v
                           seedRes.ux, seedRes.uy,     // ux, uy
                           seedRes.vx, seedRes.vy,     // vx, vy
                           seedRes.correlation_score   // correlation
                   });

        processed[seedGy][seedGx] = true;
        // ... store results ...

        // Store Result
        resultGrid[seedGy][seedGx*5 + 0] = (float)seedX;
        resultGrid[seedGy][seedGx*5 + 1] = (float)seedY;
        resultGrid[seedGy][seedGx*5 + 2] = (float)seedRes.u;
        resultGrid[seedGy][seedGx*5 + 3] = (float)seedRes.v;
        resultGrid[seedGy][seedGx*5 + 4] = (float)seedRes.correlation_score;
    }

    // --- 2D FLOOD FILL ---
    int count = 0;
    // Neighbors: Right, Left, Down, Up
    int dx[] = {1, -1, 0, 0};
    int dy[] = {0, 0, 1, -1};

    while(!queue.empty()) {
        IndicVision::SeedNode current = queue.top();
        queue.pop();

        for(int k=0; k<4; ++k) {
            int nx = current.x_idx + dx[k];
            int ny = current.y_idx + dy[k];

            // Boundary Check
            if(nx >= 0 && nx < gridW && ny >= 0 && ny < gridH && !processed[ny][nx]) {

                int realX = rectX + nx * step;
                int realY = rectY + ny * step;

                engine->set_reference(refImg, realX, realY, subsetSize);

                // Use Neighbor's U/V as guess (Reliability Guided)
                IndicVision::AnalysisResult res = engine->calculate_deformation(defImg, current.u, current.v, IndicVision::INIT_GRID_SEARCH);

                // ... inside the 2D flood fill loop ...
                if (res.status == 0) {
                    // UPDATE THIS LINE: Provide 9 arguments
                    queue.push({
                                       nx, ny,                   // x_idx, y_idx
                                       res.u, res.v,             // u, v
                                       res.ux, res.uy,           // ux, uy
                                       res.vx, res.vy,           // vx, vy
                                       res.correlation_score     // correlation
                               });

                    // ... store results ...

                    resultGrid[ny][nx*5 + 0] = (float)realX;
                    resultGrid[ny][nx*5 + 1] = (float)realY;
                    resultGrid[ny][nx*5 + 2] = (float)res.u;
                    resultGrid[ny][nx*5 + 3] = (float)res.v;
                    resultGrid[ny][nx*5 + 4] = (float)res.correlation_score;
                }

                processed[ny][nx] = true;
                count++;
                if(count % 50 == 0) env->CallVoidMethod(callbackObj, methodId, (jint)((count * 100) / totalPoints));
            }
        }
    }

    // --- FLATTEN RESULTS ---
    // Only return Valid points to save memory
    std::vector<float> flatOutput;
    for(int y=0; y<gridH; ++y) {
        for(int x=0; x<gridW; ++x) {
            if (processed[y][x] && resultGrid[y][x*5+4] != 0.0f) { // Check if valid
                flatOutput.push_back(resultGrid[y][x*5+0]); // x
                flatOutput.push_back(resultGrid[y][x*5+1]); // y
                flatOutput.push_back(resultGrid[y][x*5+2]); // u
                flatOutput.push_back(resultGrid[y][x*5+3]); // v
                flatOutput.push_back(resultGrid[y][x*5+4]); // corr
            }
        }
    }

    jfloatArray output = env->NewFloatArray(flatOutput.size());
    env->SetFloatArrayRegion(output, 0, flatOutput.size(), flatOutput.data());
    return output;
}
} // extern C