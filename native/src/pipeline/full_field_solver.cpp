#include <indicvision/pipeline.hpp>
#include <indicvision/seeding.hpp>
#include <indicvision/solver.hpp>
#include <indicvision/strain.hpp>
#include <indicvision/subset.hpp>
#include "util/log.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <exception>
#include <fstream>
#include <memory>
#include <mutex>
#include <omp.h>
#include <opencv2/calib3d.hpp>
#include <opencv2/features2d.hpp>
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <queue>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>
#include <cmath>

#undef LOG_TAG
#define LOG_TAG "IndicVisionPipeline"

namespace IndicVision {
namespace pipeline {

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
        OptimizationEngine &engine;
        ThreadStats &bucket;
        int &local_points;
        double &local_hessian;
        double &local_wait;

        EngineStatFlusher(OptimizationEngine &e, ThreadStats &b, int &lp, double &lh, double &lw)
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

// Cooperative cancel (the flag itself lives in cancel.cpp). Polled in the hot
// point loops: the cost is one relaxed atomic load per point against a full ICGN
// solve, and a late poll only means one more point, never a wrong result.

/** How often a Path B worker parked on the queue re-checks the cancel flag. */
static constexpr int kCancelPollMs = 20;

int run_full_field(
    ReferenceCache& cache,
    const cv::Mat& def_gray,
    const cv::Mat& roi_mask,
    const FullFieldParams& params,
    float* output_ptr,
    float* metrics,
    int metrics_len,
    ProgressCallback on_progress) {

        std::string local_debug_dir = cache.debug_dir;
        cache.debug_dir.clear();

        auto t_total_start = std::chrono::high_resolution_clock::now();
        double time_img_prep = 0, time_akaze = 0, time_ransac = 0, time_delaunay = 0, time_contour_assign = 0, time_extrapolate = 0, time_smoothing = 0;
        double time_prepass = 0, time_pathA = 0, time_pathB = 0, time_strain = 0;

        // 🚀 PRIORITY 3: Return -3 for memory/init errors
        if (output_ptr == nullptr || cache.ref_img == nullptr || def_gray.empty()) return -3;
        if (cancel_requested()) return kCancelled;

        static int s_frame_count = 0; s_frame_count++;
        LOGD("=== FRAME %d computeFullFieldDirect (HYBRID CORE) START ===", s_frame_count);

        auto t_prep_start = std::chrono::high_resolution_clock::now();
        cv::Mat defMat = def_gray;
        cv::Mat roiMask;
        if (!roi_mask.empty()) {
            roiMask = roi_mask;
            if (roiMask.cols != cache.width || roiMask.rows != cache.height) {
                cv::resize(roiMask, roiMask, cv::Size(cache.width, cache.height), 0, 0, cv::INTER_NEAREST);
            }
        }

        Image defImg(defMat.cols, defMat.rows, defMat.data);
        defImg.prepare_data(params.apply_gaussian_blur);
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

        if (params.rect_w > 32 && params.rect_h > 32) {
            try {
                // Prefer the cached gray reference from initializeReference; fall
                // back to decoding refBytes only if that cache is missing.
                cv::Mat refMat = cache.gray;
                if (!refMat.empty()) {

                    // 🚀 PRIORITY 2: ADAPTIVE SCALE PYRAMID
                    // Build the scale list based on the globally established baseline for this specimen
                    std::vector<double> scales_to_try;
                    if (cache.akaze_scale <= 0.25) scales_to_try = { 0.25,0.5,1.0};
                    else if (cache.akaze_scale <= 0.5) scales_to_try = {0.5,1.0};
                    else scales_to_try = {1.0};

                    cv::Rect winning_padded_roi; // 🚀 Keep track of the offsets used for the winning scale

                    for (double current_scale : scales_to_try) {

                        int adaptive_padding = (int)(40.0 / current_scale);
                        cv::Rect padded_roi(params.rect_x - adaptive_padding, params.rect_y - adaptive_padding, params.rect_w + 2 * adaptive_padding, params.rect_h + 2 * adaptive_padding);
                        padded_roi = padded_roi & cv::Rect(0, 0, cache.width, cache.height);

                        // 🚀 FIX: Must use padded_roi here, not winning_padded_roi!
                        cv::Mat refROI = refMat(padded_roi);
                        cv::Mat defROI = defMat(padded_roi);

                        if (current_scale != cache.akaze_scale) {
                            cache.akaze_kp.clear();
                            cache.akaze_desc.release();
                            cache.akaze_scale = current_scale;
                        }

                        double iter_akaze = 0, iter_ransac = 0;
                        // 🚀 FIX: Pass padded_roi.x and padded_roi.y into the function!
                        bool success = seeding::extract_akaze_features(refROI, defROI, roiMask, current_scale, akaze_ref_pts, akaze_def_pts, inlier_bb_area_ratio, iter_akaze, iter_ransac, cache.akaze_kp, cache.akaze_desc, padded_roi.x, padded_roi.y, local_debug_dir);
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

                            seeding::draw_outlined_text(akazeRefDraw, "AKAZE Reference Features: " + std::to_string(akaze_ref_pts.size()), cv::Point(10, 25), 0.6);
                            seeding::draw_outlined_text(akazeDefDraw, "AKAZE Deformed Features", cv::Point(10, 25), 0.6);

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

        int gridW = params.rect_w / params.step;
        int gridH = params.rect_h / params.step;
        if (gridW <= 0 || gridH <= 0) return -2; // 🚀 PRIORITY 3: Return -2 for ROI Errors

        // Now that gridW and gridH exist, we can set their default fallbacks
        path_c_seed_x = gridW / 2;
        path_c_seed_y = gridH / 2;

        // Sentinel for "no valid correlation here" (skipped or failed point).
        // ZNSSD is >= 0 for every real solve, so a negative value can never be
        // confused with a genuinely perfect match (ZNSSD == 0.0), which the old
        // corr==0 convention silently discarded.
        constexpr float CORR_INVALID = -1.0f;

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

        // === 🚀 100% STRICT RULE: PURE SUBSETS ONLY ===
        // We scan the ENTIRE subset bounding box before allowing a grid point to exist.
        // If a single pixel of the subset touches the void, the image boundary, or the mask,
        // the point is discarded. This guarantees pure tracking and prevents Ghost Displacements.

        int half_subset = params.subset_size / 2;
        // DICe's required 4-pixel interpolation buffer, plus a ~15-pixel deformation buffer
        int absolute_boundary_buffer = half_subset + 4 + 15;

        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                int realX = params.rect_x + x * params.step;
                int realY = params.rect_y + y * params.step;
                bool shouldSkip = false;

                // 1. DICe Absolute Boundary Force Field (Maps exactly to DICe's ~40px edge buffer)
                if (realX - absolute_boundary_buffer < 0 || realX + absolute_boundary_buffer >= cache.width ||
                    realY - absolute_boundary_buffer < 0 || realY + absolute_boundary_buffer >= cache.height) {
                    shouldSkip = true;
                }

                // 2. The 100% ROI Strict Scan
                if (!shouldSkip) {
                    // Scan EVERY SINGLE PIXEL the subset bounding box will touch.
                    for (int dy = -half_subset; dy <= half_subset; dy += 1) {
                        for (int dx = -half_subset; dx <= half_subset; dx += 1) {
                            int checkY = realY + dy;
                            int checkX = realX + dx;

                            // Failsafe bounds check (should be caught by the absolute buffer above)
                            if (checkX < 0 || checkX >= cache.width || checkY < 0 || checkY >= cache.height) {
                                shouldSkip = true;
                                break;
                            }

                            // If ANY pixel in the subset hits the mask, kill the whole point
                            if (!roiMask.empty() && roiMask.at<uchar>(checkY, checkX) < 128) {
                                shouldSkip = true;
                                break;
                            }
                        }
                        if (shouldSkip) break;
                    }
                }

                if (!shouldSkip) total_valid_points++;

                // Note: If shouldSkip is true, the point is marked as 'solved' so the OpenMP
                // workers will ignore it entirely, just like DICe's kd-tree ignores it.
                resultGrid[y][x] = {(float)realX, (float)realY, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, CORR_INVALID, shouldSkip, -1, -1, 0, false, 0};
            }
        }

        if (total_valid_points == 0) return -2; // 🚀 PRIORITY 3: Return -2 for Empty Mask

        std::atomic<int> global_points_solved(0);
        std::atomic<int> compute_order_counter(1);

        std::vector<ThreadStats> stats_pathA(safe_cores);
        std::vector<ThreadStats> stats_pathB(safe_cores);

        
        std::mutex progress_cv_mutex;
        std::condition_variable progress_cv;
        std::atomic<bool> progress_thread_should_stop(false);
        bool progress_thread_detached = false;

        std::thread progress_thread([&]() {
            if (!on_progress) {
                std::lock_guard<std::mutex> lk(progress_cv_mutex);
                progress_thread_detached = true;
                progress_cv.notify_one();
                return;
            }
            while (!progress_thread_should_stop.load(std::memory_order_acquire)) {
                int solved = global_points_solved.load(std::memory_order_relaxed);
                int percentage = (int)((((float)solved / total_valid_points) * 80.0f) + 10.0f);
                percentage = std::max(0, std::min(100, percentage));
                try { on_progress(percentage); } catch (...) {}
                for (int i = 0; i < 10; ++i) {
                    if (progress_thread_should_stop.load(std::memory_order_acquire)) break;
                    std::this_thread::sleep_for(std::chrono::milliseconds(10));
                }
            }
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
                stop_flag.store(true, std::memory_order_release);
                {
                    std::unique_lock<std::mutex> lk(cv_mutex);
                    cv.wait_for(lk, std::chrono::seconds(2), [&]{ return detached_flag; });
                }
                if (t.joinable()) t.join();
            }
        };
        ThreadJoinGuard progressGuard{progress_thread, progress_thread_should_stop, progress_cv, progress_cv_mutex, progress_thread_detached};

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

            cv::Subdiv2D subdiv(cv::Rect(0, 0, cache.width, cache.height));
            for (size_t i = 0; i < akaze_ref_pts.size(); i++) {
                if (akaze_ref_pts[i].x > 0 && akaze_ref_pts[i].x < cache.width &&
                    akaze_ref_pts[i].y > 0 && akaze_ref_pts[i].y < cache.height) {
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
                    if (pt[0].x < 0 || pt[0].x >= cache.width || pt[1].x < 0 || pt[1].x >= cache.width || pt[2].x < 0 || pt[2].x >= cache.width) continue;
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
                    cv::Mat refFloat(cache.height, cache.width, CV_32FC1, (void *)cache.ref_img->intensities.data());
                    cv::Mat ref8U, refColor;
                    refFloat.convertTo(ref8U, CV_8UC1);
                    cv::cvtColor(ref8U, refColor, cv::COLOR_GRAY2BGR);
                    cv::Rect roiRect(params.rect_x, params.rect_y, params.rect_w, params.rect_h);
                    roiRect = roiRect & cv::Rect(0, 0, cache.width, cache.height);
                    cv::Mat meshDebug = refColor(roiRect) * 0.35; // Reduces opacity/brightness by 65%

                    for (const auto &tri : affTriangles) {
                        cv::Point pt1(tri.pts[0].x - params.rect_x, tri.pts[0].y - params.rect_y);
                        cv::Point pt2(tri.pts[1].x - params.rect_x, tri.pts[1].y - params.rect_y);
                        cv::Point pt3(tri.pts[2].x - params.rect_x, tri.pts[2].y - params.rect_y);
                        cv::line(meshDebug, pt1, pt2, cv::Scalar(255, 255, 0), 2, cv::LINE_AA);
                        cv::line(meshDebug, pt2, pt3, cv::Scalar(255, 255, 0), 2, cv::LINE_AA);
                        cv::line(meshDebug, pt3, pt1, cv::Scalar(255, 255, 0), 2, cv::LINE_AA);
                    }
                    seeding::draw_outlined_text(meshDebug, "Delaunay 6-DOF Mesh", cv::Point(10, 25), 0.6);
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
                    cv::Point2f gp(params.rect_x + x * params.step, params.rect_y + y * params.step);
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
                float EXTRAP_LIMIT = -3.0f * params.step;
                for (int y = 0; y < gridH; ++y) {
                    for (int x = 0; x < gridW; ++x) {
                        int idx = y * gridW + x;
                        if (inMesh[idx] || resultGrid[y][x].solved) continue;
                        cv::Point2f gp(params.rect_x + x * params.step, params.rect_y + y * params.step);
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
        std::vector<CachedHessianData, Eigen::aligned_allocator<CachedHessianData>> hessian_pool(gridW * gridH);

        // Track standard deviations to build an adaptive threshold
        double sum_std_dev = 0.0;
        int valid_std_count = 0;
        std::mutex std_mutex;

    #pragma omp parallel for schedule(static) num_threads(safe_cores)
        for (int pool_idx = 0; pool_idx < gridW * gridH; ++pool_idx) {
            // OpenMP forbids breaking out of a parallel for, so a cancel skips
            // the remaining iterations instead — the same shape the existing
            // exception guard in Path A uses.
            if (cancel_requested()) continue;
            int gx = pool_idx % gridW, gy = pool_idx / gridW;
            if (!resultGrid[gy][gx].solved) {
                int realX = params.rect_x + gx * params.step, realY = params.rect_y + gy * params.step;
                hessian_pool[pool_idx] = SubsetPrecomputer::compute_hessian_only(*cache.ref_img, realX, realY, params.subset_size);

                if (hessian_pool[pool_idx].valid) {
                    std::lock_guard<std::mutex> lock(std_mutex);
                    sum_std_dev += hessian_pool[pool_idx].std_dev;
                    valid_std_count++;
                }
            }
        }

        time_prepass = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_prepass_start).count();
        // =========================================================
        // 🚀 PATH C: CENTRAL SEEDING (Industry Standard Fallback)
        // =========================================================
        if (execute_path_c) {
            LOGD("ROUTING: Mesh Insufficient. Falling back to Central-Tendency Seeding.");

            int best_grid_x = -1;
            int best_grid_y = -1;

            // 1. Try the user's suggested AKAZE Central Seeding first
            if (!akaze_ref_pts.empty()) {
                float grid_cx = params.rect_x + (gridW / 2.f) * params.step;
                float grid_cy = params.rect_y + (gridH / 2.f) * params.step;
                float min_dist = 1e9f;
                int best_akaze_idx = -1;

                for (size_t i = 0; i < akaze_ref_pts.size(); ++i) {
                    float dx = akaze_ref_pts[i].x - grid_cx;
                    float dy = akaze_ref_pts[i].y - grid_cy;
                    float dist = dx*dx + dy*dy;
                    if (dist < min_dist) {
                        min_dist = dist;
                        best_akaze_idx = (int)i;
                    }
                }

                if (best_akaze_idx >= 0) {
                    best_grid_x = std::max(0, std::min(gridW - 1, (int)std::round((akaze_ref_pts[best_akaze_idx].x - params.rect_x) / params.step)));
                    best_grid_y = std::max(0, std::min(gridH - 1, (int)std::round((akaze_ref_pts[best_akaze_idx].y - params.rect_y) / params.step)));
                }
            }

            // 2. THE ZERO-AKAZE / HOLE BYPASS (Mask-Aware Center Search)
            // If AKAZE failed, OR if the chosen AKAZE point is inside a masked hole/dead zone
            if (best_grid_x == -1 || resultGrid[best_grid_y][best_grid_x].solved) {
                LOGD("PATH C: Blind searching for the nearest valid unmasked seed...");
                float min_dist = 1e9f;
                float center_x = gridW / 2.0f;
                float center_y = gridH / 2.0f;

                for (int y = 0; y < gridH; ++y) {
                    for (int x = 0; x < gridW; ++x) {
                        // 🚀 INDUSTRY STANDARD: Must be strictly inside the User's ROI and have good speckles!
                        if (!resultGrid[y][x].solved) {
                            float dx = x - center_x;
                            float dy = y - center_y;
                            float dist = dx * dx + dy * dy;
                            if (dist < min_dist) {
                                min_dist = dist;
                                best_grid_x = x;
                                best_grid_y = y;
                            }
                        }
                    }
                }
            }

            // 3. Fatal Error Check: If it's still -1, the entire ROI is masked or textureless
            if (best_grid_x == -1) {
                LOGE("PATH C FAILED: Entire ROI is masked or lacks valid speckle contrast.");
                return -2;
            }

            LOGD("PATH C: Executing NCC Brute-Force Anchor Search at grid [%d, %d]", best_grid_x, best_grid_y);
            path_c_seed_x = best_grid_x;
            path_c_seed_y = best_grid_y;

            int realX = params.rect_x + path_c_seed_x * params.step;
            int realY = params.rect_y + path_c_seed_y * params.step;
            int flat_idx = path_c_seed_y * gridW + path_c_seed_x;

            IndicVision::SubsetData seed_subset;
            SubsetPrecomputer::precompute_subset_fast(seed_subset, *cache.ref_img, realX, realY, params.subset_size, hessian_pool[flat_idx]);

            if (seed_subset.is_initialized) {
                OptimizationEngine seed_engine;
                seed_engine.use_6x6_interpolator = params.use_6x6_interpolator;
                auto res = seed_engine.calculate_deformation(seed_subset, defImg, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, INIT_AUTO_SEARCH);

                if (res.status == 0 && res.correlation_score <= 0.15f) {
                    globalU = res.u; globalV = res.v;
                    LOGD("PATH C SUCCESS: Seed locked at u=%.2f, v=%.2f", globalU, globalV);
                } else {
                    LOGE("PATH C FAILED: Central anchor search diverged.");
                    return -1;
                }
            } else { return -1; }
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
                OptimizationEngine local_engine; IndicVision::SubsetData local_subset;
                // 🚀 ENABLE LEVENBERG-MARQUARDT (TIKHONOV REGULARIZATION) - PATH A
                local_engine.lm_enabled = true;
                local_engine.lm_alpha = 0.05f; // <--- TUNE THIS VALUE
                local_engine.use_6x6_interpolator = params.use_6x6_interpolator;
                double local_hessian = 0.0, local_wait = 0.0; int local_pts = 0;
                EngineStatFlusher flusher(local_engine, stats_pathA[tid], local_pts, local_hessian, local_wait);

    #pragma omp for schedule(dynamic, 32)
                for (int idx = 0; idx < gridW * gridH; ++idx) {
                    if (omp_region_threw.load(std::memory_order_relaxed)) continue;
                    if (cancel_requested()) continue;
                    if (!inMesh[idx]) continue;
                    int x = idx % gridW, y = idx / gridW;
                    if (resultGrid[y][x].solved) continue;

                    int realX = params.rect_x + x * params.step, realY = params.rect_y + y * params.step;
                    auto th1 = std::chrono::high_resolution_clock::now();
                    SubsetPrecomputer::precompute_subset_fast(local_subset, *cache.ref_img, realX, realY, params.subset_size, hessian_pool[idx]);
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

                        auto search_flag = ALLOW_SIMPLEX_RESCUE ? INIT_NO_SEARCH : INIT_NO_SIMPLEX;
                        IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                local_subset, defImg, guessU[idx], guessV[idx], guessUx[idx], guessUy[idx], guessVx[idx], guessVy[idx], search_flag);
                        // 🚀 VSG PROTECTOR: Reject if > 5% of the subset fell into the Ghost Wall
                        float ghost_fraction = (float)res.invalid_ref_pixels / (float)(params.subset_size * params.subset_size);
                        if (ghost_fraction > 0.05f) {
                            res.correlation_score = 2.0f;
                            res.status = 1;
                        }
                        stats_pathA[tid].icgn_iters += res.iters;
                        bool needed_rescue = (local_engine.count_simplex > simplex_count_before);

                        if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

                        if (needed_rescue) {
                            stats_pathA[tid].simplex_calls++;

                            // 🚀 FIX: Now correctly checks the REAL iterations for timeout!
                            if (res.iters >= 50) {
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
                            resultGrid[y][x].corr = CORR_INVALID;
                            resultGrid[y][x].used_simplex = needed_rescue;
                            // 🚀 FIX: Assign the real iterations on failure
                            resultGrid[y][x].icgn_iters = res.iters;
                        }
                    }
                }
            }
            time_pathA = std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - t_pathA_start).count();
        }

        // Path A is done or abandoned; a cancelled field is incomplete, so stop
        // before spending anything on strain and packing.
        if (cancel_requested()) return kCancelled;


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
                    if (!resultGrid[y][x].solved || resultGrid[y][x].corr < 0.f) continue;
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
                float grid_cx = params.rect_x + (gridW / 2.f) * params.step, grid_cy = params.rect_y + (gridH / 2.f) * params.step;

                for (size_t fi = 0; fi < akaze_ref_pts.size(); ++fi) {
                    float fx = akaze_ref_pts[fi].x, fy = akaze_ref_pts[fi].y;
                    int ix = (int)std::round((fx - params.rect_x) / (float)params.step);
                    int iy = (int)std::round((fy - params.rect_y) / (float)params.step);
                    if (ix < 0 || ix >= gridW || iy < 0 || iy >= gridH || resultGrid[iy][ix].solved) continue;
                    float du = akaze_def_pts[fi].x - fx, dv = akaze_def_pts[fi].y - fy;
                    float world_x = params.rect_x + ix * params.step, world_y = params.rect_y + iy * params.step;
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

            OptimizationEngine prewarm_engine;
            // 🚀 ENABLE LEVENBERG-MARQUARDT - PATH B (PREWARM)
            prewarm_engine.lm_enabled = true;
            prewarm_engine.lm_alpha = 0.05f; // <--- TUNE THIS VALUE
            prewarm_engine.use_6x6_interpolator = params.use_6x6_interpolator;

            IndicVision::SubsetData prewarm_subset;

            while ((int)gq.q.size() < cores_to_use && seed_idx.load() < (int)global_seeds.size()) {
                int si = seed_idx.fetch_add(1, std::memory_order_relaxed);
                if (si >= (int)global_seeds.size()) break;
                const auto &seed = global_seeds[si];
                int flat = seed.y_idx * gridW + seed.x_idx;

                bool unclaimed = false;
                if (!cell_claimed[flat].compare_exchange_strong(unclaimed, true, std::memory_order_acq_rel, std::memory_order_relaxed)) continue;

                int realX = params.rect_x + seed.x_idx * params.step, realY = params.rect_y + seed.y_idx * params.step;
                SubsetPrecomputer::precompute_subset_fast(prewarm_subset, *cache.ref_img, realX, realY, params.subset_size, hessian_pool[flat]);
                if (!prewarm_subset.is_initialized) continue;

                int simplex_count_before = prewarm_engine.count_simplex;
                int icgn_count_before = prewarm_engine.count_icgn;

                auto search_flag = ALLOW_SIMPLEX_RESCUE ? INIT_NO_SEARCH : INIT_NO_SIMPLEX;
                IndicVision::AnalysisResult res = prewarm_engine.calculate_deformation(
                        prewarm_subset, defImg, seed.u, seed.v, 0.f, 0.f, 0.f, 0.f, search_flag);
                stats_pathB[0].icgn_iters += res.iters;
                bool needed_rescue = (prewarm_engine.count_simplex > simplex_count_before);
                int icgn_iters_used = prewarm_engine.count_icgn - icgn_count_before;

                if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

                if (needed_rescue) {
                    stats_pathB[0].simplex_calls++;

                    if (icgn_iters_used >= 500) {
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
                    resultGrid[seed.y_idx][seed.x_idx].corr = CORR_INVALID;
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
                        OptimizationEngine local_engine;
                        // 🚀 ENABLE LEVENBERG-MARQUARDT - PATH B (WORKERS)
                        local_engine.lm_enabled = true;
                        local_engine.lm_alpha = .05f; // <--- TUNE THIS VALUE
                        local_engine.use_6x6_interpolator = params.use_6x6_interpolator;
                        IndicVision::SubsetData local_subset;
                        double local_hessian_ms = 0.0, local_wait_ms = 0.0;
                        int local_points_solved = 0;
                        EngineStatFlusher flusher(local_engine, stats_pathB[tid], local_points_solved, local_hessian_ms, local_wait_ms);

                        while (true) {
                            if (cancel_requested()) return;
                            IndicVision::SeedNode cur;
                            bool has_node = false;
                            {
                                std::unique_lock<std::mutex> lk(gq.mtx);
                                auto wait_start = std::chrono::high_resolution_clock::now();
                                // Bounded wait: a worker parked on the queue has no
                                // one to notify it of a cancel, so it re-checks on a
                                // timer instead. A timeout is not an error — it just
                                // sends the worker round the loop again.
                                bool ready = gq.cv.wait_for(lk, std::chrono::milliseconds(kCancelPollMs), [&] { return !gq.q.empty() || gq.done || cancel_requested() || (gq.active == 0 && seed_idx.load(std::memory_order_relaxed) < (int)global_seeds.size()); });
                                local_wait_ms += std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - wait_start).count();
                                if (gq.done || cancel_requested()) return;
                                if (!ready) continue;
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
                                        int realX = params.rect_x + seed.x_idx * params.step, realY = params.rect_y + seed.y_idx * params.step;
                                        auto th1 = std::chrono::high_resolution_clock::now();
                                        SubsetPrecomputer::precompute_subset_fast(local_subset, *cache.ref_img, realX, realY, params.subset_size, hessian_pool[flat]);
                                        local_hessian_ms += std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - th1).count();
                                        if (local_subset.is_initialized) {

                                            int simplex_count_before = local_engine.count_simplex;

                                            IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                                    local_subset, defImg, seed.u, seed.v, 0.f, 0.f, 0.f, 0.f, INIT_NO_SEARCH);

                                            // 🚀 VSG PROTECTOR: Reject if > 5% of the subset fell into the Ghost Wall
                                            float ghost_fraction = (float)res.invalid_ref_pixels / (float)(params.subset_size * params.subset_size);
                                            if (ghost_fraction > 0.05f) {
                                                res.correlation_score = 2.0f;
                                                res.status = 1;
                                            }
                                            bool needed_rescue = (local_engine.count_simplex > simplex_count_before);

                                            if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

                                            if (needed_rescue) {
                                                stats_pathB[tid].simplex_calls++;

                                                // 🚀 FIX: Use res.iters
                                                if (res.iters >= 50) {
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
                                                resultGrid[seed.y_idx][seed.x_idx].corr = CORR_INVALID;
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
                                const int realX = params.rect_x + nx * params.step, realY = params.rect_y + ny * params.step;
                                auto th1 = std::chrono::high_resolution_clock::now();
                                SubsetPrecomputer::precompute_subset_fast(local_subset, *cache.ref_img, realX, realY, params.subset_size, hessian_pool[flat]);
                                local_hessian_ms += std::chrono::duration<double, std::milli>(std::chrono::high_resolution_clock::now() - th1).count();
                                if (!local_subset.is_initialized) continue;

                                int simplex_count_before = local_engine.count_simplex;

                                // 🚀 FIRST-ORDER KINEMATIC EXPANSION (The Path B Fix)
                                // Calculate the physical distance from the solved point to the new neighbor
                                float dx = (nx - cur.x_idx) * params.step;
                                float dy = (ny - cur.y_idx) * params.step;

                                // Project the initial guess using the solved point's strain gradients
                                float guess_u = cur.u + cur.ux * dx + cur.uy * dy;
                                float guess_v = cur.v + cur.vx * dx + cur.vy * dy;

                                auto search_flag = ALLOW_SIMPLEX_RESCUE ? INIT_NO_SEARCH : INIT_NO_SIMPLEX;
                                IndicVision::AnalysisResult res = local_engine.calculate_deformation(
                                        local_subset, defImg, guess_u, guess_v, cur.ux, cur.uy, cur.vx, cur.vy, search_flag);
                                // 🚀 VSG PROTECTOR: Reject if > 5% of the subset fell into the Ghost Wall
                                float ghost_fraction = (float)res.invalid_ref_pixels / (float)(params.subset_size * params.subset_size);
                                if (ghost_fraction > 0.05f) {
                                    res.correlation_score = 2.0f;
                                    res.status = 1;
                                }
                                stats_pathB[tid].icgn_iters += res.iters; // 🚀 ADD THIS
                                bool needed_rescue = (local_engine.count_simplex > simplex_count_before);

                                if (!ALLOW_SIMPLEX_RESCUE && res.status != 0) { res.correlation_score = 1.0f; }

                                if (needed_rescue) {
                                    stats_pathB[tid].simplex_calls++;

                                    // 🚀 FIX: Use res.iters
                                    if (res.iters >= 50) {
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
                                    resultGrid[ny][nx].corr = CORR_INVALID;
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

        // Path B's workers have been joined by their guard. A cancelled field is
        // partial, so there is nothing worth deriving strain from.
        if (cancel_requested()) return kCancelled;

        auto t_strain_start = std::chrono::high_resolution_clock::now();
        DisplacementField dispField;
        dispField.width = gridW; dispField.height = gridH; dispField.step = params.step;
        dispField.u.resize(gridW * gridH, 0.0f); dispField.v.resize(gridW * gridH, 0.0f);
        dispField.valid.resize(gridW * gridH, false);

        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                int idx = y * gridW + x;
                // corr >= 0 accepts a genuinely perfect solve (ZNSSD == 0.0)
                // while still rejecting skipped/failed points (CORR_INVALID).
                if (resultGrid[y][x].solved && resultGrid[y][x].corr >= 0.0f) {
                    dispField.u[idx] = resultGrid[y][x].u;
                    dispField.v[idx] = resultGrid[y][x].v;
                    dispField.valid[idx] = true;
                }
            }
        }

        StrainField strainField;
        if (params.use_nlvc_strain) strainField = StrainCalculator::compute_nlvc_strain(dispField, params.strain_window);
        else strainField = StrainCalculator::compute_vsg_strain(dispField, params.strain_window);

        int valid_count = 0;
        int dropped_by_post_filter = 0; // 🚀 NEW: Track dropped points

        for (int y = 0; y < gridH; ++y) {
            for (int x = 0; x < gridW; ++x) {
                int idx = y * gridW + x;

                if (dispField.valid[idx]) {

                    // === 🚀 ROBUST STRAIN FILTER (FAST-MATH SAFE) ===
                    // Since the compiler strips NaN, we check for our hard sentinel.
                    // Any value <= -999.0f means the Strain Calculator refused to solve it.
                    // We completely drop the point to protect the Android UI and Python analysis.
                    if (strainField.exx[idx] <= -999.0f) {
                        dropped_by_post_filter++;
                        resultGrid[y][x].solved = false; // Mark dead for debug map
                        continue;
                    }
                    // =================================================

                    float point_corr = resultGrid[y][x].corr;
                    float point_std = hessian_pool[idx].std_dev;

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

        // 🚀 DIAGNOSTIC: Print exactly how many points the filter caught
        LOGD("DIAGNOSTIC POST-FILTER: Dropped %d noisy points. Final Valid Output: %d", dropped_by_post_filter, valid_count);

        if (on_progress) { try { on_progress(100); } catch (...) {} }

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
                                          roiMask.at<uchar>(params.rect_y + y * params.step, params.rect_x + x * params.step) < 128);

                        if (!is_masked) {
                            if (gp.solved && gp.corr > 0.0f) {
                                if (!gp.used_simplex) {
                                    simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 0);
                                } else if (gp.icgn_iters < 50) {
                                    simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 255, 255);
                                } else {
                                    simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(255, 255, 0);
                                }
                            } else {
                                if (!gp.used_simplex) {
                                    simplexMap.at<cv::Vec3b>(y, x) = cv::Vec3b(0, 0, 255);
                                } else if (gp.icgn_iters < 50) {
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
                cv::Size sz(gridW * params.step, gridH * params.step);
                cv::resize(propColor, outProp, sz, 0, 0, cv::INTER_NEAREST);
                cv::resize(threadMap, outThread, sz, 0, 0, cv::INTER_NEAREST);
                cv::resize(meshAssignMap, outMesh, sz, 0, 0, cv::INTER_NEAREST);
                cv::resize(corrColor, outCorr, sz, 0, 0, cv::INTER_NEAREST);
                cv::resize(strainColor, outStrain, sz, 0, 0, cv::INTER_NEAREST);
                cv::resize(simplexMap, outSimplex, sz, 0, 0, cv::INTER_NEAREST);

                seeding::draw_outlined_text(outProp, "Propagation Debug", cv::Point(10, 25), 0.6);
                seeding::draw_outlined_text(outThread, "8-Core Thread Execution Map", cv::Point(10, 25), 0.6);
                seeding::draw_outlined_text(outMesh, "Mesh Assign (Grn=In, Yel=Ex, Gry=PathB)", cv::Point(10, 25), 0.6);
                seeding::draw_outlined_text(outCorr, "ZNSSD Quality (Blue=Perfect, Red=Marginal)", cv::Point(10, 25), 0.6);
                seeding::draw_outlined_text(outStrain, "Exx Strain (Raw Plot)", cv::Point(10, 25), 0.6);
                seeding::draw_outlined_text(outSimplex, "Grn=Perfect | Yel=Save(Crash) | Cya=Save(Time) | Org=Dead(Crash) | Pur=Dead(Time) | Red=Insta-Dead", cv::Point(10, 25), 0.4);

                cv::imwrite(local_debug_dir + "/propagation_debug.png", outProp);
                cv::imwrite(local_debug_dir + "/thread_debug.png", outThread);
                cv::imwrite(local_debug_dir + "/mesh_assignment_map.png", outMesh);
                cv::imwrite(local_debug_dir + "/correlation_heatmap.png", outCorr);
                cv::imwrite(local_debug_dir + "/strain_exx_debug.png", outStrain);
                cv::imwrite(local_debug_dir + "/simplex_health_map.png", outSimplex);

                cv::Mat outSimplexOverlap = outSimplex.clone();

                for (const auto &tri : affTriangles) {
                    cv::Point pt1(tri.pts[0].x - params.rect_x, tri.pts[0].y - params.rect_y);
                    cv::Point pt2(tri.pts[1].x - params.rect_x, tri.pts[1].y - params.rect_y);
                    cv::Point pt3(tri.pts[2].x - params.rect_x, tri.pts[2].y - params.rect_y);

                    cv::line(outSimplexOverlap, pt1, pt2, cv::Scalar(255, 255, 255), 2, cv::LINE_AA);
                    cv::line(outSimplexOverlap, pt2, pt3, cv::Scalar(255, 255, 255), 2, cv::LINE_AA);
                    cv::line(outSimplexOverlap, pt3, pt1, cv::Scalar(255, 255, 255), 2, cv::LINE_AA);
                }
                seeding::draw_outlined_text(outSimplexOverlap, "Simplex Health + Delaunay Overlap", cv::Point(10, 45), 0.4);
                cv::imwrite(local_debug_dir + "/simplex_mesh_overlap.png", outSimplexOverlap);

                std::string csvPath = local_debug_dir + "/debug_grid_data.csv";
                std::ofstream csvFile(csvPath);
                if (csvFile.is_open()) {
                    csvFile << "# PIPELINE=2_HYBRID_CORE\n";
                    csvFile << "RealX,RealY,GridX,GridY,ThreadID,ComputeOrder,U,V,Correlation,MeshType,UsedSimplex,ItersICGN,SolverState,GuessU,GuessV,GuessUx,GuessUy,GuessVx,GuessVy\n";
                    for (int y = 0; y < gridH; ++y) {
                        for (int x = 0; x < gridW; ++x) {
                            const auto &gp = resultGrid[y][x];

                            bool is_masked = (!roiMask.empty() && roiMask.at<uchar>(params.rect_y + y * params.step, params.rect_x + x * params.step) < 128);
                            if (is_masked) continue;

                            int solver_state = 0;
                            if (gp.solved && gp.corr > 0.0f) {
                                if (!gp.used_simplex) solver_state = 0;
                                else if (gp.icgn_iters < 50) solver_state = 1;
                                else solver_state = 2;
                            } else {
                                if (!gp.used_simplex) solver_state = 5;
                                else if (gp.icgn_iters < 50) solver_state = 3;
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
                    cv::Mat refFloat(cache.height, cache.width, CV_32FC1, (void *)cache.ref_img->intensities.data());
                    cv::Mat ref8U, refColor;
                    refFloat.convertTo(ref8U, CV_8UC1);
                    cv::cvtColor(ref8U, refColor, cv::COLOR_GRAY2BGR);

                    cv::Rect roiRect(params.rect_x, params.rect_y, params.rect_w, params.rect_h);
                    roiRect = roiRect & cv::Rect(0, 0, cache.width, cache.height);

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
                            bool is_masked = (!roiMask.empty() && roiMask.at<uchar>(params.rect_y + y * params.step, params.rect_x + x * params.step) < 128);
                            if (is_masked) continue;

                            int solver_state = 0;
                            if (gp.solved && gp.corr > 0.0f) {
                                if (!gp.used_simplex) solver_state = 0;
                                else if (gp.icgn_iters < 50) solver_state = 1;
                                else solver_state = 2;
                            } else {
                                if (!gp.used_simplex) solver_state = 5;
                                else if (gp.icgn_iters < 50) solver_state = 3;
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

                    seeding::draw_outlined_text(imgAll, "ALL Simplex Interventions vs. Mesh", cv::Point(10, 25), 0.6);
                    seeding::draw_outlined_text(imgDead, "ONLY Dead Points vs. Mesh", cv::Point(10, 25), 0.6);

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

        if (metrics != nullptr && metrics_len >= 16) {
            float metrics_data[17];

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
                // Throughput over the whole solve, not just Path A — when the
                // mesh phase is skipped (pathA_pts == 0) the old formula
                // reported 0.00 pts/ms even though RGDIC solved every point.
                metrics_data[14] = (time_total > 0) ? (float)(valid_count / time_total) : 0.0f; // Throughput Pts/ms
                metrics_data[15] = (total_valid_points > 0) ? ((float)valid_count / total_valid_points) * 100.0f : 0.0f; // Convergence %

                // 16: How the solve was seeded. Surfaces silently-skipped mesh
                // phases (AKAZE fail / clustered features → Path C, RGDIC-only)
                // in the report instead of just "0.0 ms / 0 points".
                //   2 = full AKAZE mesh, 1 = sparse mesh, 0 = Path C fallback
                metrics_data[16] = (mesh_quality == MeshQuality::FULL) ? 2.0f
                                 : (mesh_quality == MeshQuality::SPARSE) ? 1.0f : 0.0f;

                int ncopy = (metrics_len >= 17) ? 17 : 16;
            for (int mi = 0; mi < ncopy; ++mi) metrics[mi] = metrics_data[mi];
        }

        defMat.release(); roiMask.release();
        return valid_count;
}


} // namespace pipeline
} // namespace IndicVision
