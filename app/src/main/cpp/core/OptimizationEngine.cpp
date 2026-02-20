#include "OptimizationEngine.h"
#include <chrono>
#include <algorithm>

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

    // --- PURE ICGN SOLVER (No Allocations in Loop) ---
    AnalysisResult OptimizationEngine::solve_icgn(const SubsetData& subset, const Image &def_img, double init_u, double init_v) {
        size_t n = subset.x_offsets.size();

        // Stack-allocated matrix for Warp (Very fast)
        Eigen::Matrix3d W = Eigen::Matrix3d::Identity();
        W(0, 2) = init_u;
        W(1, 2) = init_v;

        // Pre-allocate buffer ONCE outside the loop to prevent heap fragmentation
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

                // Zero-Poisoning Fix: Ignore 0.0 which means Out-Of-Bounds
                if (val > 0.0) {
                    def_vals[i] = val;
                    def_sum += val;
                    valid_pixels++;
                } else {
                    def_vals[i] = -1.0; // Marker for invalid
                }
            }

            // Early Exit: If too much of the subset is outside the image, abort.
            if (valid_pixels < n * 0.90) {
                return {W(0,2), W(1,2), W(0,0)-1.0, W(0,1), W(1,0), W(1,1)-1.0, 1, 2.0};
            }

            // 2. Exact Normalization (Only on valid pixels)
            double def_mean = def_sum / valid_pixels;
            double def_sum_sq = 0.0;
            for (size_t i = 0; i < n; ++i) {
                if (def_vals[i] >= 0.0) {
                    double diff = def_vals[i] - def_mean;
                    def_sum_sq += diff * diff;
                }
            }
            double def_std = std::sqrt(def_sum_sq / valid_pixels);
            if (def_std < 1e-5) def_std = 1.0;

            // 3. Compute Gradients & Delta
            Eigen::Matrix<double, 6, 1> dp_sum = Eigen::Matrix<double, 6, 1>::Zero();
            double error_sum_sq = 0.0;

            for (size_t i = 0; i < n; ++i) {
                if (def_vals[i] >= 0.0) {
                    double norm_ref = (subset.ref_intensities[i] - subset.mean_intensity) / subset.std_dev;
                    double norm_def = (def_vals[i] - def_mean) / def_std;
                    double diff = norm_ref - norm_def;

                    error_sum_sq += diff * diff;
                    dp_sum += subset.steepest_descent_images[i] * diff;
                }
            }

            final_score = error_sum_sq / valid_pixels;

            // Pure ICGN update: delta = -H_inv * dp_sum
            Eigen::Matrix<double, 6, 1> delta_p = -subset.H_inv * dp_sum;

            // 4. Update W (Inverse Compositional)
            Eigen::Matrix3d dW = Eigen::Matrix3d::Identity();
            dW(0,0) += delta_p(2); // ux
            dW(0,1) += delta_p(3); // uy
            dW(0,2) += delta_p(0); // u
            dW(1,0) += delta_p(4); // vx
            dW(1,1) += delta_p(5); // vy
            dW(1,2) += delta_p(1); // v

            W = W * dW.inverse();

            // 5. Convergence Check (Sub-pixel stability)
            if (delta_p.norm() < 1e-4) {
                return {W(0,2), W(1,2), W(0,0)-1.0, W(0,1), W(1,0), W(1,1)-1.0, 0, final_score};
            }
        }

        // Reached max iters without tight convergence, mark as fail
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

        if (valid_pixels < n * 0.90) return 2.0; // Heavy penalty for going OOB

        def_mean /= valid_pixels;
        double def_sum_sq = 0.0;
        for (size_t i = 0; i < n; ++i) {
            if (buffer[i] >= 0.0) {
                double diff = buffer[i] - def_mean;
                def_sum_sq += diff * diff;
            }
        }
        double def_std = std::sqrt(def_sum_sq / valid_pixels);
        if (def_std < 1e-5) def_std = 1.0;

        double znssd = 0.0;
        for (size_t i = 0; i < n; ++i) {
            if (buffer[i] >= 0.0) {
                double norm_ref = (subset.ref_intensities[i] - subset.mean_intensity) / subset.std_dev;
                double norm_def = (buffer[i] - def_mean) / def_std;
                double diff = norm_ref - norm_def;
                znssd += diff * diff;
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