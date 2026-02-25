#include "OptimizationEngine.h"
#include <chrono>
#include <algorithm>
#include <arm_neon.h>

namespace IndicVision {

    AnalysisResult OptimizationEngine::calculate_deformation(const SubsetData& subset, const Image &def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode) {
        if (eval_buffer.size() != subset.x_offsets.size()) {
            eval_buffer.resize(subset.x_offsets.size());
        }
        scalar_t u = guess_u;
        scalar_t v = guess_v;
        AnalysisResult res;

        auto run_icgn = [&](double start_u, double start_v) {
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
            if (u == 0.0 && v == 0.0) estimate_initial_guess(subset, def_img, u, v);
            AnalysisResult start_guess = {u, v, 0, 0, 0, 0, 0, 1.0};
            AnalysisResult coarse_res = run_simplex(start_guess, true);
            res = run_icgn(coarse_res.u, coarse_res.v);
        } else {
            res = run_icgn(u, v);
            if (res.status != 0 || res.correlation_score > 0.4) {
                AnalysisResult start_guess = {u, v, 0, 0, 0, 0, 0, 1.0};
                AnalysisResult rescue_res = run_simplex(start_guess, true);
                res = run_icgn(rescue_res.u, rescue_res.v);
            }
        }
        return res;
    }

    void OptimizationEngine::estimate_initial_guess(const SubsetData& subset, const Image& def_img, scalar_t& best_u, scalar_t& best_v) {
        double min_ssd = 1e20;
        int search_range = 15; // 15px radius initial search

        for (int v = -search_range; v <= search_range; v += 2) {
            for (int u = -search_range; u <= search_range; u += 2) {
                double sum_sq_diff = 0;
                // Fast pass: Check only 1 out of every 9 pixels
                for (size_t i = 0; i < subset.x_offsets.size(); i += 9) {
                    double x = subset.cx + subset.x_offsets[i];
                    double y = subset.cy + subset.y_offsets[i];
                    double def_val = def_img.interpolate_bicubic(x + u, y + v);
                    double diff = subset.ref_intensities[i] - def_val;
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
    AnalysisResult OptimizationEngine::solve_icgn(const SubsetData& subset, const Image &def_img, double init_u, double init_v) {
        size_t n = subset.x_offsets.size();

        Eigen::Matrix3d W = Eigen::Matrix3d::Identity();
        W(0, 2) = init_u;
        W(1, 2) = init_v;

        std::vector<double> def_vals(n, 0.0);
        double final_score = 1.0;

        for (int iter = 0; iter < 20; ++iter) {
            double def_sum = 0.0;
            int valid_pixels = 0;

            // 1. Unrolled Affine Warp & Interpolation
            for (size_t i = 0; i < n; ++i) {
                double x = subset.x_offsets[i];
                double y = subset.y_offsets[i];

                double final_x = subset.cx + W(0,0)*x + W(0,1)*y + W(0,2);
                double final_y = subset.cy + W(1,0)*x + W(1,1)*y + W(1,2);

                double val = def_img.interpolate_bicubic(final_x, final_y);

                if (val > 0.0) {
                    def_vals[i] = val;
                    def_sum += val;
                    valid_pixels++;
                } else {
                    def_vals[i] = -1.0;
                }
            }

            if (valid_pixels < n * 0.90) {
                return {W(0,2), W(1,2), W(0,0)-1.0, W(0,1), W(1,0), W(1,1)-1.0, 1, 2.0};
            }

            double def_mean = def_sum / valid_pixels;
            double def_sum_sq = 0.0;
            Eigen::Matrix<double, 6, 1> dp_sum = Eigen::Matrix<double, 6, 1>::Zero();
            double error_sum_sq = 0.0;

            // 🚀 FAST-PATH: All pixels are safely inside the image boundaries
            if (valid_pixels == n) {

#if defined(__aarch64__)
                // ==========================================
                // 1A. 64-BIT ARM NEON VECTOR MATH
                // ==========================================
                float64x2_t sum_sq_vec = vdupq_n_f64(0.0);
                float64x2_t mean_vec = vdupq_n_f64(def_mean);
                size_t i = 0;

                for (; i + 1 < n; i += 2) {
                    float64x2_t vals = vld1q_f64(&def_vals[i]);
                    float64x2_t diff = vsubq_f64(vals, mean_vec);
                    sum_sq_vec = vaddq_f64(sum_sq_vec, vmulq_f64(diff, diff));
                }
                double lane_sums[2];
                vst1q_f64(lane_sums, sum_sq_vec);
                def_sum_sq = lane_sums[0] + lane_sums[1];

                for (; i < n; ++i) {
                    double diff = def_vals[i] - def_mean;
                    def_sum_sq += diff * diff;
                }

                double def_std = std::sqrt(def_sum_sq / valid_pixels);
                if (def_std < 1e-5) def_std = 1.0;
                double inv_std = 1.0 / def_std;

                float64x2_t err_sum_vec = vdupq_n_f64(0.0);
                float64x2_t inv_std_vec = vdupq_n_f64(inv_std);
                i = 0;

                for (; i + 1 < n; i += 2) {
                    float64x2_t def_v = vld1q_f64(&def_vals[i]);
                    float64x2_t norm_def = vmulq_f64(vsubq_f64(def_v, mean_vec), inv_std_vec);
                    float64x2_t ref_v = vld1q_f64(&subset.norm_ref_intensities[i]);
                    float64x2_t diff = vsubq_f64(ref_v, norm_def);
                    err_sum_vec = vaddq_f64(err_sum_vec, vmulq_f64(diff, diff));

                    double diff_arr[2];
                    vst1q_f64(diff_arr, diff);
                    dp_sum += subset.steepest_descent_images[i] * diff_arr[0];
                    dp_sum += subset.steepest_descent_images[i+1] * diff_arr[1];
                }

                vst1q_f64(lane_sums, err_sum_vec);
                error_sum_sq = lane_sums[0] + lane_sums[1];

                for (; i < n; ++i) {
                    double norm_def = (def_vals[i] - def_mean) * inv_std;
                    double diff = subset.norm_ref_intensities[i] - norm_def;
                    error_sum_sq += diff * diff;
                    dp_sum += subset.steepest_descent_images[i] * diff;
                }
#else
                // ==========================================
                // 1B. 32-BIT SCALAR FAST MATH (Fallback)
                // ==========================================
                for (size_t i = 0; i < n; ++i) {
                    double diff = def_vals[i] - def_mean;
                    def_sum_sq += diff * diff;
                }
                double def_std = std::sqrt(def_sum_sq / valid_pixels);
                if (def_std < 1e-5) def_std = 1.0;
                double inv_std = 1.0 / def_std;

                for (size_t i = 0; i < n; ++i) {
                    double norm_def = (def_vals[i] - def_mean) * inv_std;
                    double diff = subset.norm_ref_intensities[i] - norm_def;
                    error_sum_sq += diff * diff;
                    dp_sum += subset.steepest_descent_images[i] * diff;
                }
#endif
            }
            else {
                // ==========================================
                // 🐌 2. SLOW-PATH (Subset is hitting the edge)
                // ==========================================
                for (size_t i = 0; i < n; ++i) {
                    if (def_vals[i] >= 0.0) {
                        double diff = def_vals[i] - def_mean;
                        def_sum_sq += diff * diff;
                    }
                }
                double def_std = std::sqrt(def_sum_sq / valid_pixels);
                if (def_std < 1e-5) def_std = 1.0;

                for (size_t i = 0; i < n; ++i) {
                    if (def_vals[i] >= 0.0) {
                        double norm_def = (def_vals[i] - def_mean) / def_std;
                        double diff = subset.norm_ref_intensities[i] - norm_def;
                        error_sum_sq += diff * diff;
                        dp_sum += subset.steepest_descent_images[i] * diff;
                    }
                }
            }

            final_score = error_sum_sq / valid_pixels;

            // Update step
            Eigen::Matrix<double, 6, 1> delta_p = -subset.H_inv * dp_sum;
            Eigen::Matrix3d dW = Eigen::Matrix3d::Identity();
            dW(0,0) += delta_p(2);
            dW(0,1) += delta_p(3);
            dW(0,2) += delta_p(0);
            dW(1,0) += delta_p(4);
            dW(1,1) += delta_p(5);
            dW(1,2) += delta_p(1);

            W = W * dW.inverse();

            if (delta_p.norm() < 0.001) {
                return {W(0,2), W(1,2), W(0,0)-1.0, W(0,1), W(1,0), W(1,1)-1.0, 0, final_score};
            }
        }

        return {W(0,2), W(1,2), W(0,0)-1.0, W(0,1), W(1,0), W(1,1)-1.0, 1, final_score};
    }

    // --- HIGH-SPEED ZNSSD FOR SIMPLEX ---
    double OptimizationEngine::evaluate_znssd(const SubsetData& subset, const Image &def_img,
                                              double u, double v, double ux, double uy, double vx, double vy,
                                              std::vector<double>& buffer) {
        size_t n = subset.x_offsets.size();
        double def_mean = 0.0;
        int valid_pixels = 0;

        for (size_t i = 0; i < n; ++i) {
            double dx = subset.x_offsets[i];
            double dy = subset.y_offsets[i];

            double final_x = subset.cx + u + (1.0 + ux) * dx + uy * dy;
            double final_y = subset.cy + v + vx * dx + (1.0 + vy) * dy;

            double val = def_img.interpolate_bicubic(final_x, final_y);
            if (val > 0.0) {
                buffer[i] = val;
                def_mean += val;
                valid_pixels++;
            } else {
                buffer[i] = -1.0;
            }
        }

        if (valid_pixels < n * 0.90) return 2.0;

        def_mean /= valid_pixels;
        double def_sum_sq = 0.0;
        double znssd = 0.0;

        // 🚀 FAST-PATH: All pixels valid
        if (valid_pixels == n) {

#if defined(__aarch64__)
            // ==========================================
            // 1A. 64-BIT ARM NEON VECTOR MATH
            // ==========================================
            float64x2_t sum_sq_vec = vdupq_n_f64(0.0);
            float64x2_t mean_vec = vdupq_n_f64(def_mean);
            size_t i = 0;

            for (; i + 1 < n; i += 2) {
                float64x2_t vals = vld1q_f64(&buffer[i]);
                float64x2_t diff = vsubq_f64(vals, mean_vec);
                sum_sq_vec = vaddq_f64(sum_sq_vec, vmulq_f64(diff, diff));
            }
            double lane_sums[2];
            vst1q_f64(lane_sums, sum_sq_vec);
            def_sum_sq = lane_sums[0] + lane_sums[1];

            for (; i < n; ++i) {
                double diff = buffer[i] - def_mean;
                def_sum_sq += diff * diff;
            }

            double def_std = std::sqrt(def_sum_sq / valid_pixels);
            if (def_std < 1e-5) def_std = 1.0;
            double inv_std = 1.0 / def_std;

            float64x2_t znssd_vec = vdupq_n_f64(0.0);
            float64x2_t inv_std_vec = vdupq_n_f64(inv_std);
            i = 0;

            for (; i + 1 < n; i += 2) {
                float64x2_t def_v = vld1q_f64(&buffer[i]);
                float64x2_t norm_def = vmulq_f64(vsubq_f64(def_v, mean_vec), inv_std_vec);
                float64x2_t ref_v = vld1q_f64(&subset.norm_ref_intensities[i]);
                float64x2_t diff = vsubq_f64(ref_v, norm_def);
                znssd_vec = vaddq_f64(znssd_vec, vmulq_f64(diff, diff));
            }
            vst1q_f64(lane_sums, znssd_vec);
            znssd = lane_sums[0] + lane_sums[1];

            for (; i < n; ++i) {
                double norm_def = (buffer[i] - def_mean) * inv_std;
                double diff = subset.norm_ref_intensities[i] - norm_def;
                znssd += diff * diff;
            }
#else
            // ==========================================
            // 1B. 32-BIT SCALAR FAST MATH (Fallback)
            // ==========================================
            for (size_t i = 0; i < n; ++i) {
                double diff = buffer[i] - def_mean;
                def_sum_sq += diff * diff;
            }
            double def_std = std::sqrt(def_sum_sq / valid_pixels);
            if (def_std < 1e-5) def_std = 1.0;
            double inv_std = 1.0 / def_std;

            for (size_t i = 0; i < n; ++i) {
                double norm_def = (buffer[i] - def_mean) * inv_std;
                double diff = subset.norm_ref_intensities[i] - norm_def;
                znssd += diff * diff;
            }
#endif
        }
        else {
            // ==========================================
            // 🐌 2. SLOW-PATH (Edge of image)
            // ==========================================
            for (size_t i = 0; i < n; ++i) {
                if (buffer[i] >= 0.0) {
                    double diff = buffer[i] - def_mean;
                    def_sum_sq += diff * diff;
                }
            }
            double def_std = std::sqrt(def_sum_sq / valid_pixels);
            if (def_std < 1e-5) def_std = 1.0;

            for (size_t i = 0; i < n; ++i) {
                if (buffer[i] >= 0.0) {
                    double norm_def = (buffer[i] - def_mean) / def_std;
                    double diff = subset.norm_ref_intensities[i] - norm_def;
                    znssd += diff * diff;
                }
            }
        }

        return znssd / valid_pixels;
    }

    // --- SIMPLEX RESCUE METHOD ---
    // --- SIMPLEX RESCUE METHOD (DICe-Style with Translation-Only Support) ---
    AnalysisResult OptimizationEngine::solve_simplex(const SubsetData& subset, const Image &def_img, AnalysisResult start, bool translation_only) {
        LOGD("[Simplex] Activated (TransOnly: %d, Start cost: %.4f)", translation_only, start.correlation_score);

        // If translation only, we only optimize 2 dimensions (u, v). Otherwise, 6 (Affine).
        const int DIM = translation_only ? 2 : 6;
        int n_pts = DIM + 1;

        std::vector<std::vector<double>> p(n_pts, std::vector<double>(DIM));
        std::vector<double> y(n_pts);

        // WIDENED NET: Look up to 2.0 pixels away to jump out of local traps
        double scale[] = {2.0, 2.0, 0.01, 0.01, 0.01, 0.01};

        // Single pre-allocated buffer for the hundreds of ZNSSD checks
        std::vector<double> eval_buffer(subset.dim * subset.dim, 0.0);

        // Helper lambda to cleanly evaluate cost function based on mode
        auto eval_pt = [&](const std::vector<double>& pt) {
            if (translation_only) return evaluate_znssd(subset, def_img, pt[0], pt[1], 0, 0, 0, 0, eval_buffer);
            return evaluate_znssd(subset, def_img, pt[0], pt[1], pt[2], pt[3], pt[4], pt[5], eval_buffer);
        };

        // Initialize Vertex 0
        if (translation_only) {
            p[0] = {start.u, start.v};
        } else {
            p[0] = {start.u, start.v, start.ux, start.uy, start.vx, start.vy};
        }
        y[0] = eval_pt(p[0]);

        // Create initial simplex shape
        for (int i = 1; i < n_pts; ++i) {
            p[i] = p[0];
            p[i][i-1] += scale[i-1];
            y[i] = eval_pt(p[i]);
        }

        // Nelder-Mead Loop
        const double alpha=1.0, gamma=2.0, rho=0.5, sigma=0.5;
        for (int iter = 0; iter < 80; ++iter) {
            std::vector<int> idx(n_pts);
            for(int k=0; k<n_pts; ++k) idx[k] = k;
            std::sort(idx.begin(), idx.end(), [&](int a, int b){ return y[a] < y[b]; });

            // Convergence check
            if (std::abs(y[idx[0]] - y[idx[n_pts-1]]) < 1e-5) break;

            std::vector<double> p_bar(DIM, 0.0);
            for (int i = 0; i < DIM; ++i)
                for (int j = 0; j < DIM; ++j) p_bar[j] += p[idx[i]][j];
            for (int j = 0; j < DIM; ++j) p_bar[j] /= DIM;

            std::vector<double> p_r(DIM);
            for (int j = 0; j < DIM; ++j) p_r[j] = p_bar[j] + alpha * (p_bar[j] - p[idx[n_pts-1]][j]);
            double y_r = eval_pt(p_r);

            if (y[idx[0]] <= y_r && y_r < y[idx[n_pts-2]]) {
                p[idx[n_pts-1]] = p_r; y[idx[n_pts-1]] = y_r;
            } else if (y_r < y[idx[0]]) {
                std::vector<double> p_e(DIM);
                for (int j = 0; j < DIM; ++j) p_e[j] = p_bar[j] + gamma * (p_r[j] - p_bar[j]);
                double y_e = eval_pt(p_e);
                if (y_e < y_r) { p[idx[n_pts-1]] = p_e; y[idx[n_pts-1]] = y_e; }
                else           { p[idx[n_pts-1]] = p_r; y[idx[n_pts-1]] = y_r; }
            } else {
                std::vector<double> p_c(DIM);
                bool outside = (y_r < y[idx[n_pts-1]]);
                std::vector<double>& base_p = outside ? p_r : p[idx[n_pts-1]];
                for (int j = 0; j < DIM; ++j) p_c[j] = p_bar[j] + rho * (base_p[j] - p_bar[j]);
                double y_c = eval_pt(p_c);
                if (y_c < std::min(y_r, y[idx[n_pts-1]])) {
                    p[idx[n_pts-1]] = p_c; y[idx[n_pts-1]] = y_c;
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

        int final_status = (y[best] > 0.1) ? -2 : 0;

        if (translation_only) {
            return {p[best][0], p[best][1], 0, 0, 0, 0, final_status, y[best]};
        }
        return {p[best][0], p[best][1], p[best][2], p[best][3], p[best][4], p[best][5], final_status, y[best]};
    }

}