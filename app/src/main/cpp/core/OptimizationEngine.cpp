#include "OptimizationEngine.h"
#include <chrono>
#include <algorithm>
#include <arm_neon.h>

namespace IndicVision {

    AnalysisResult OptimizationEngine::calculate_deformation(const SubsetData& subset, const Image &def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode) {
        // Guarantee buffers are allocated exactly ONCE per thread when it first starts
        if (icgn_buffer.size() != subset.x_offsets.size()) {
            icgn_buffer.resize(subset.x_offsets.size());
        }
        if (simplex_buffer.size() != subset.x_offsets.size()) {
            simplex_buffer.resize(subset.x_offsets.size());
        }

        scalar_t u = guess_u;
        scalar_t v = guess_v;
        AnalysisResult res;

        auto run_icgn = [&](float start_u, float start_v) {
            auto t1 = std::chrono::high_resolution_clock::now();
            AnalysisResult r = solve_icgn(subset, def_img, start_u, start_v);
            auto t2 = std::chrono::high_resolution_clock::now();
            time_icgn_ms += std::chrono::duration<double, std::milli>(t2 - t1).count();
            count_icgn++;
            return r;
        };

        auto run_simplex = [&](AnalysisResult start_g, bool trans_only) {
            auto t1 = std::chrono::high_resolution_clock::now();
            AnalysisResult r = solve_simplex(subset, def_img, start_g, trans_only);
            auto t2 = std::chrono::high_resolution_clock::now();
            time_simplex_ms += std::chrono::duration<double, std::milli>(t2 - t1).count();
            count_simplex++;
            return r;
        };

        if (init_mode == INIT_AUTO_SEARCH) {
            if (u == 0.0f && v == 0.0f) estimate_initial_guess(subset, def_img, u, v);
            AnalysisResult start_guess = {u, v, 0.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f};
            AnalysisResult coarse_res = run_simplex(start_guess, true);
            res = run_icgn(coarse_res.u, coarse_res.v);
        } else {
            res = run_icgn(u, v);
            if (res.status != 0 || res.correlation_score > 0.4f) {
                AnalysisResult start_guess = {u, v, 0.0f, 0.0f, 0.0f, 0.0f, 0, 1.0f};
                AnalysisResult rescue_res = run_simplex(start_guess, true);
                res = run_icgn(rescue_res.u, rescue_res.v);
            }
        }
        return res;
    }

    void OptimizationEngine::estimate_initial_guess(const SubsetData& subset, const Image& def_img, scalar_t& best_u, scalar_t& best_v) {
        float min_ssd = 1e20f;
        int search_range = 15; // 15px radius initial search

        for (int v = -search_range; v <= search_range; v += 2) {
            for (int u = -search_range; u <= search_range; u += 2) {
                float sum_sq_diff = 0.0f;
                // Fast pass: Check only 1 out of every 9 pixels
                for (size_t i = 0; i < subset.x_offsets.size(); i += 9) {
                    float x = subset.cx + subset.x_offsets[i];
                    float y = subset.cy + subset.y_offsets[i];
                    float def_val = def_img.interpolate_bicubic(x + (float)u, y + (float)v);
                    float diff = subset.ref_intensities[i] - def_val;
                    sum_sq_diff += diff * diff;
                }
                if (sum_sq_diff < min_ssd) {
                    min_ssd = sum_sq_diff;
                    best_u = static_cast<scalar_t>(u);
                    best_v = static_cast<scalar_t>(v);
                }
            }
        }
    }

    // --- PURE ICGN SOLVER ---
    AnalysisResult OptimizationEngine::solve_icgn(const SubsetData& subset, const Image &def_img, float init_u, float init_v) {
        size_t n = subset.x_offsets.size();

        Eigen::Matrix3f W = Eigen::Matrix3f::Identity();
        W(0, 2) = init_u;
        W(1, 2) = init_v;

        // 🚀 FIX: Use the dedicated ICGN buffer
        std::vector<float>& def_vals = this->icgn_buffer;

        float final_score = 1.0f;

        for (int iter = 0; iter < 20; ++iter) {
            float def_sum = 0.0f;
            int valid_pixels = 0;

            // 1. Unrolled Affine Warp & Interpolation
            for (size_t i = 0; i < n; ++i) {
                float x = subset.x_offsets[i];
                float y = subset.y_offsets[i];

                float final_x = subset.cx + W(0,0)*x + W(0,1)*y + W(0,2);
                float final_y = subset.cy + W(1,0)*x + W(1,1)*y + W(1,2);

                float val = def_img.interpolate_bicubic(final_x, final_y);

                if (val > 0.0f) {
                    def_vals[i] = val;
                    def_sum += val;
                    valid_pixels++;
                } else {
                    def_vals[i] = -1.0f;
                }
            }

            if (valid_pixels < n * 0.90f) {
                return {W(0,2), W(1,2), W(0,0)-1.0f, W(0,1), W(1,0), W(1,1)-1.0f, 1, 2.0f};
            }

            float def_mean = def_sum / valid_pixels;
            float def_sum_sq = 0.0f;
            Eigen::Matrix<float, 6, 1> dp_sum = Eigen::Matrix<float, 6, 1>::Zero();
            float error_sum_sq = 0.0f;

            // 🚀 FAST-PATH: All pixels are safely inside the image boundaries
            if (valid_pixels == n) {

#if defined(__aarch64__)
                float32x4_t sum_sq_vec = vdupq_n_f32(0.0f);
                float32x4_t mean_vec = vdupq_n_f32(def_mean);
                size_t i = 0;

                for (; i + 3 < n; i += 4) {
                    float32x4_t vals = vld1q_f32(&def_vals[i]);
                    float32x4_t diff = vsubq_f32(vals, mean_vec);
                    sum_sq_vec = vaddq_f32(sum_sq_vec, vmulq_f32(diff, diff));
                }
                float lane_sums[4];
                vst1q_f32(lane_sums, sum_sq_vec);
                def_sum_sq = lane_sums[0] + lane_sums[1] + lane_sums[2] + lane_sums[3];

                for (; i < n; ++i) {
                    float diff = def_vals[i] - def_mean;
                    def_sum_sq += diff * diff;
                }

                float def_std = std::sqrt(def_sum_sq / valid_pixels);
                if (def_std < 1e-5f) def_std = 1.0f;
                float inv_std = 1.0f / def_std;

                float32x4_t err_sum_vec = vdupq_n_f32(0.0f);
                float32x4_t inv_std_vec = vdupq_n_f32(inv_std);
                i = 0;

                for (; i + 3 < n; i += 4) {
                    float32x4_t def_v = vld1q_f32(&def_vals[i]);
                    float32x4_t norm_def = vmulq_f32(vsubq_f32(def_v, mean_vec), inv_std_vec);
                    float32x4_t ref_v = vld1q_f32(&subset.norm_ref_intensities[i]);
                    float32x4_t diff = vsubq_f32(ref_v, norm_def);
                    err_sum_vec = vaddq_f32(err_sum_vec, vmulq_f32(diff, diff));

                    float diff_arr[4];
                    vst1q_f32(diff_arr, diff);
                    dp_sum += subset.steepest_descent_images[i] * diff_arr[0];
                    dp_sum += subset.steepest_descent_images[i+1] * diff_arr[1];
                    dp_sum += subset.steepest_descent_images[i+2] * diff_arr[2];
                    dp_sum += subset.steepest_descent_images[i+3] * diff_arr[3];
                }

                vst1q_f32(lane_sums, err_sum_vec);
                error_sum_sq = lane_sums[0] + lane_sums[1] + lane_sums[2] + lane_sums[3];

                for (; i < n; ++i) {
                    float norm_def = (def_vals[i] - def_mean) * inv_std;
                    float diff = subset.norm_ref_intensities[i] - norm_def;
                    error_sum_sq += diff * diff;
                    dp_sum += subset.steepest_descent_images[i] * diff;
                }
#else
                for (size_t i = 0; i < n; ++i) {
                    float diff = def_vals[i] - def_mean;
                    def_sum_sq += diff * diff;
                }
                float def_std = std::sqrt(def_sum_sq / valid_pixels);
                if (def_std < 1e-5f) def_std = 1.0f;
                float inv_std = 1.0f / def_std;

                for (size_t i = 0; i < n; ++i) {
                    float norm_def = (def_vals[i] - def_mean) * inv_std;
                    float diff = subset.norm_ref_intensities[i] - norm_def;
                    error_sum_sq += diff * diff;
                    dp_sum += subset.steepest_descent_images[i] * diff;
                }
#endif
            }
            else {
                for (size_t i = 0; i < n; ++i) {
                    if (def_vals[i] >= 0.0f) {
                        float diff = def_vals[i] - def_mean;
                        def_sum_sq += diff * diff;
                    }
                }
                float def_std = std::sqrt(def_sum_sq / valid_pixels);
                if (def_std < 1e-5f) def_std = 1.0f;

                for (size_t i = 0; i < n; ++i) {
                    if (def_vals[i] >= 0.0f) {
                        float norm_def = (def_vals[i] - def_mean) / def_std;
                        float diff = subset.norm_ref_intensities[i] - norm_def;
                        error_sum_sq += diff * diff;
                        dp_sum += subset.steepest_descent_images[i] * diff;
                    }
                }
            }

            final_score = error_sum_sq / valid_pixels;

            Eigen::Matrix<float, 6, 1> delta_p = -subset.H_inv * dp_sum;
            Eigen::Matrix3f dW = Eigen::Matrix3f::Identity();
            dW(0,0) += delta_p(2);
            dW(0,1) += delta_p(3);
            dW(0,2) += delta_p(0);
            dW(1,0) += delta_p(4);
            dW(1,1) += delta_p(5);
            dW(1,2) += delta_p(1);

            W = W * dW.inverse();

            if (delta_p.norm() < 0.001f) {
                return {W(0,2), W(1,2), W(0,0)-1.0f, W(0,1), W(1,0), W(1,1)-1.0f, 0, final_score};
            }
        }

        return {W(0,2), W(1,2), W(0,0)-1.0f, W(0,1), W(1,0), W(1,1)-1.0f, 1, final_score};
    }

    float OptimizationEngine::evaluate_znssd(const SubsetData& subset, const Image &def_img,
                                             float u, float v, float ux, float uy, float vx, float vy,
                                             std::vector<float>& buffer) {
        size_t n = subset.x_offsets.size();
        float def_mean = 0.0f;
        int valid_pixels = 0;

        for (size_t i = 0; i < n; ++i) {
            float dx = subset.x_offsets[i];
            float dy = subset.y_offsets[i];

            float final_x = subset.cx + u + (1.0f + ux) * dx + uy * dy;
            float final_y = subset.cy + v + vx * dx + (1.0f + vy) * dy;

            float val = def_img.interpolate_bicubic(final_x, final_y);
            if (val > 0.0f) {
                buffer[i] = val;
                def_mean += val;
                valid_pixels++;
            } else {
                buffer[i] = -1.0f;
            }
        }

        if (valid_pixels < n * 0.90f) return 2.0f;

        def_mean /= valid_pixels;
        float def_sum_sq = 0.0f;
        float znssd = 0.0f;

        if (valid_pixels == n) {
#if defined(__aarch64__)
            float32x4_t sum_sq_vec = vdupq_n_f32(0.0f);
            float32x4_t mean_vec = vdupq_n_f32(def_mean);
            size_t i = 0;

            for (; i + 3 < n; i += 4) {
                float32x4_t vals = vld1q_f32(&buffer[i]);
                float32x4_t diff = vsubq_f32(vals, mean_vec);
                sum_sq_vec = vaddq_f32(sum_sq_vec, vmulq_f32(diff, diff));
            }
            float lane_sums[4];
            vst1q_f32(lane_sums, sum_sq_vec);
            def_sum_sq = lane_sums[0] + lane_sums[1] + lane_sums[2] + lane_sums[3];

            for (; i < n; ++i) {
                float diff = buffer[i] - def_mean;
                def_sum_sq += diff * diff;
            }

            float def_std = std::sqrt(def_sum_sq / valid_pixels);
            if (def_std < 1e-5f) def_std = 1.0f;
            float inv_std = 1.0f / def_std;

            float32x4_t znssd_vec = vdupq_n_f32(0.0f);
            float32x4_t inv_std_vec = vdupq_n_f32(inv_std);
            i = 0;

            for (; i + 3 < n; i += 4) {
                float32x4_t def_v = vld1q_f32(&buffer[i]);
                float32x4_t norm_def = vmulq_f32(vsubq_f32(def_v, mean_vec), inv_std_vec);
                float32x4_t ref_v = vld1q_f32(&subset.norm_ref_intensities[i]);
                float32x4_t diff = vsubq_f32(ref_v, norm_def);
                znssd_vec = vaddq_f32(znssd_vec, vmulq_f32(diff, diff));
            }
            vst1q_f32(lane_sums, znssd_vec);
            znssd = lane_sums[0] + lane_sums[1] + lane_sums[2] + lane_sums[3];

            for (; i < n; ++i) {
                float norm_def = (buffer[i] - def_mean) * inv_std;
                float diff = subset.norm_ref_intensities[i] - norm_def;
                znssd += diff * diff;
            }
#else
            for (size_t i = 0; i < n; ++i) {
                float diff = buffer[i] - def_mean;
                def_sum_sq += diff * diff;
            }
            float def_std = std::sqrt(def_sum_sq / valid_pixels);
            if (def_std < 1e-5f) def_std = 1.0f;
            float inv_std = 1.0f / def_std;

            for (size_t i = 0; i < n; ++i) {
                float norm_def = (buffer[i] - def_mean) * inv_std;
                float diff = subset.norm_ref_intensities[i] - norm_def;
                znssd += diff * diff;
            }
#endif
        } else {
            for (size_t i = 0; i < n; ++i) {
                if (buffer[i] >= 0.0f) {
                    float diff = buffer[i] - def_mean;
                    def_sum_sq += diff * diff;
                }
            }
            float def_std = std::sqrt(def_sum_sq / valid_pixels);
            if (def_std < 1e-5f) def_std = 1.0f;

            for (size_t i = 0; i < n; ++i) {
                if (buffer[i] >= 0.0f) {
                    float norm_def = (buffer[i] - def_mean) / def_std;
                    float diff = subset.norm_ref_intensities[i] - norm_def;
                    znssd += diff * diff;
                }
            }
        }

        return znssd / valid_pixels;
    }

    // --- SIMPLEX RESCUE METHOD ---
    AnalysisResult OptimizationEngine::solve_simplex(const SubsetData& subset, const Image &def_img, AnalysisResult start, bool translation_only) {

        const int DIM = translation_only ? 2 : 6;
        int n_pts = DIM + 1;

        float p[7][6] = {0.0f};
        float y[7] = {0.0f};

        float scale[] = {2.0f, 2.0f, 0.01f, 0.01f, 0.01f, 0.01f};

        // 🚀 FIX: Use the dedicated Simplex buffer
        auto eval_pt = [&](const float* pt) {
            if (translation_only) return evaluate_znssd(subset, def_img, pt[0], pt[1], 0.0f, 0.0f, 0.0f, 0.0f, this->simplex_buffer);
            return evaluate_znssd(subset, def_img, pt[0], pt[1], pt[2], pt[3], pt[4], pt[5], this->simplex_buffer);
        };

        p[0][0] = start.u; p[0][1] = start.v;
        if (!translation_only) {
            p[0][2] = start.ux; p[0][3] = start.uy;
            p[0][4] = start.vx; p[0][5] = start.vy;
        }
        y[0] = eval_pt(p[0]);

        for (int i = 1; i < n_pts; ++i) {
            for(int j=0; j<DIM; ++j) p[i][j] = p[0][j]; // copy base
            p[i][i-1] += scale[i-1];
            y[i] = eval_pt(p[i]);
        }

        const float alpha=1.0f, gamma=2.0f, rho=0.5f, sigma=0.5f;
        for (int iter = 0; iter < 80; ++iter) {
            int idx[7] = {0, 1, 2, 3, 4, 5, 6};
            std::sort(idx, idx + n_pts, [&](int a, int b){ return y[a] < y[b]; });

            if (std::abs(y[idx[0]] - y[idx[n_pts-1]]) < 1e-5f) break;

            float p_bar[6] = {0.0f};
            for (int i = 0; i < DIM; ++i)
                for (int j = 0; j < DIM; ++j) p_bar[j] += p[idx[i]][j];
            for (int j = 0; j < DIM; ++j) p_bar[j] /= DIM;

            float p_r[6] = {0.0f};
            for (int j = 0; j < DIM; ++j) p_r[j] = p_bar[j] + alpha * (p_bar[j] - p[idx[n_pts-1]][j]);
            float y_r = eval_pt(p_r);

            if (y[idx[0]] <= y_r && y_r < y[idx[n_pts-2]]) {
                for(int j=0; j<DIM; ++j) p[idx[n_pts-1]][j] = p_r[j];
                y[idx[n_pts-1]] = y_r;
            } else if (y_r < y[idx[0]]) {
                float p_e[6] = {0.0f};
                for (int j = 0; j < DIM; ++j) p_e[j] = p_bar[j] + gamma * (p_r[j] - p_bar[j]);
                float y_e = eval_pt(p_e);
                if (y_e < y_r) {
                    for(int j=0; j<DIM; ++j) p[idx[n_pts-1]][j] = p_e[j];
                    y[idx[n_pts-1]] = y_e;
                } else {
                    for(int j=0; j<DIM; ++j) p[idx[n_pts-1]][j] = p_r[j];
                    y[idx[n_pts-1]] = y_r;
                }
            } else {
                float p_c[6] = {0.0f};
                bool outside = (y_r < y[idx[n_pts-1]]);
                float* base_p = outside ? p_r : p[idx[n_pts-1]];
                for (int j = 0; j < DIM; ++j) p_c[j] = p_bar[j] + rho * (base_p[j] - p_bar[j]);
                float y_c = eval_pt(p_c);
                if (y_c < std::min(y_r, y[idx[n_pts-1]])) {
                    for(int j=0; j<DIM; ++j) p[idx[n_pts-1]][j] = p_c[j];
                    y[idx[n_pts-1]] = y_c;
                } else {
                    for (int i = 1; i < n_pts; ++i) {
                        for (int j = 0; j < DIM; ++j) p[idx[i]][j] = p[idx[0]][j] + sigma * (p[idx[i]][j] - p[idx[0]][j]);
                        y[idx[i]] = eval_pt(p[idx[i]]);
                    }
                }
            }
        }

        int best = 0;
        for(int k=1; k<n_pts; ++k) if(y[k] < y[best]) best = k;
        int final_status = (y[best] > 0.1f) ? -2 : 0;

        if (translation_only) {
            return {p[best][0], p[best][1], 0.0f, 0.0f, 0.0f, 0.0f, final_status, y[best]};
        }
        return {p[best][0], p[best][1], p[best][2], p[best][3], p[best][4], p[best][5], final_status, y[best]};
    }

}