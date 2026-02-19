#include "IndicVisionCore.h"
#include <algorithm>

namespace IndicVision {

    // ==========================================
    // 1. IMAGE CLASS (Pre-Processing)
    // ==========================================
    Image::Image(int_t w, int_t h, const uint8_t *raw_pixels) : width(w), height(h) {
        intensities.resize(w * h);
        for (int i = 0; i < w * h; ++i) {
            intensities[i] = static_cast<scalar_t>(raw_pixels[i]);
        }
    }

    void Image::prepare_data() {
        grad_x.assign(width * height, 0.0);
        grad_y.assign(width * height, 0.0);

        // Pre-compute 2nd order central difference gradients (avoids re-calculating them inside the subset)
        for (int y = 2; y < height - 2; ++y) {
            for (int x = 2; x < width - 2; ++x) {
                int idx = y * width + x;
                grad_x[idx] = (-intensities[idx+2] + 8.0*intensities[idx+1] - 8.0*intensities[idx-1] + intensities[idx-2]) / 12.0;
                grad_y[idx] = (-intensities[idx+2*width] + 8.0*intensities[idx+width] - 8.0*intensities[idx-width] + intensities[idx-2*width]) / 12.0;
            }
        }
    }

    // --- ULTRA-FAST BRANCHLESS KEYS BICUBIC WEIGHTS (Alpha = -0.5) ---
    inline void get_keys_weights(double s, double& w_m1, double& w_0, double& w_1, double& w_2) {
        double s2 = s * s;
        double s3 = s2 * s;
        w_m1 = -0.5 * s3 + s2 - 0.5 * s;
        w_0  =  1.5 * s3 - 2.5 * s2 + 1.0;
        w_1  = -1.5 * s3 + 2.0 * s2 + 0.5 * s;
        w_2  =  0.5 * s3 - 0.5 * s2;
    }

    inline scalar_t Image::interpolate_bicubic(scalar_t x, scalar_t y) const {
        int xi = static_cast<int>(x);
        int yi = static_cast<int>(y);

        // Fast boundary check (avoids out-of-bounds array access)
        if (xi < 1 || xi >= width - 2 || yi < 1 || yi >= height - 2) return 0.0;

        double dx = x - xi;
        double dy = y - yi;

        double wx[4], wy[4];
        get_keys_weights(dx, wx[0], wx[1], wx[2], wx[3]);
        get_keys_weights(dy, wy[0], wy[1], wy[2], wy[3]);

        scalar_t val = 0.0;
        for (int j = -1; j <= 2; ++j) {
            const scalar_t* row_ptr = &intensities[(yi + j) * width + xi];
            double row_val = 0.0;
            row_val += row_ptr[-1] * wx[0];
            row_val += row_ptr[0]  * wx[1];
            row_val += row_ptr[1]  * wx[2];
            row_val += row_ptr[2]  * wx[3];
            val += row_val * wy[j + 1];
        }
        return val;
    }

    inline scalar_t Image::gradient_x(scalar_t x, scalar_t y) const {
        int xi = static_cast<int>(x); int yi = static_cast<int>(y);
        if (xi < 0 || xi >= width-1 || yi < 0 || yi >= height-1) return 0.0;
        double dx = x - xi; double dy = y - yi;

        // Simple bilinear interpolation of the precomputed gradients
        double g00 = grad_x[yi*width + xi];
        double g10 = grad_x[yi*width + xi+1];
        double g01 = grad_x[(yi+1)*width + xi];
        double g11 = grad_x[(yi+1)*width + xi+1];
        return (1-dx)*(1-dy)*g00 + dx*(1-dy)*g10 + (1-dx)*dy*g01 + dx*dy*g11;
    }

    inline scalar_t Image::gradient_y(scalar_t x, scalar_t y) const {
        int xi = static_cast<int>(x); int yi = static_cast<int>(y);
        if (xi < 0 || xi >= width-1 || yi < 0 || yi >= height-1) return 0.0;
        double dx = x - xi; double dy = y - yi;

        double g00 = grad_y[yi*width + xi];
        double g10 = grad_y[yi*width + xi+1];
        double g01 = grad_y[(yi+1)*width + xi];
        double g11 = grad_y[(yi+1)*width + xi+1];
        return (1-dx)*(1-dy)*g00 + dx*(1-dy)*g10 + (1-dx)*dy*g01 + dx*dy*g11;
    }

    // ==========================================
    // 2. SUBSET INITIALIZATION
    // ==========================================
    Subset::Subset(int_t centroid_x, int_t centroid_y, int_t subset_size)
            : cx(centroid_x), cy(centroid_y), dim(subset_size) {
        int_t half = dim / 2;
        int expected_size = dim * dim;
        x_offsets.reserve(expected_size);
        y_offsets.reserve(expected_size);

        for (int y = -half; y <= half; ++y) {
            for (int x = -half; x <= half; ++x) {
                x_offsets.push_back(x);
                y_offsets.push_back(y);
            }
        }
    }

    void Subset::initialize(const Image &ref_img) {
        size_t n = x_offsets.size();
        ref_intensities.resize(n);
        steepest_descent_images.resize(n);

        double sum = 0.0;
        std::vector<double> gx_vec(n);
        std::vector<double> gy_vec(n);

        // 1. Calculate Intensities and Raw Gradients
        for (size_t i = 0; i < n; ++i) {
            double px = cx + x_offsets[i];
            double py = cy + y_offsets[i];

            ref_intensities[i] = ref_img.interpolate_bicubic(px, py);
            sum += ref_intensities[i];

            gx_vec[i] = ref_img.gradient_x(px, py);
            gy_vec[i] = ref_img.gradient_y(px, py);
        }

        // 2. Compute Mean & Std Dev
        mean_intensity = sum / n;
        double sum_sq_diff = 0.0;
        for (size_t i = 0; i < n; ++i) {
            double diff = ref_intensities[i] - mean_intensity;
            sum_sq_diff += diff * diff;
        }
        std_dev = std::sqrt(sum_sq_diff / n);

        if (std_dev < 1e-5) std_dev = 1.0; // Prevent divide by zero

        // 3. Build Hessian Matrix
        Eigen::Matrix<double, 6, 6> H = Eigen::Matrix<double, 6, 6>::Zero();

        for (size_t i = 0; i < n; ++i) {
            double x = x_offsets[i];
            double y = y_offsets[i];

            // Normalize gradients by the image std dev (crucial for ZNSSD mathematical stability)
            double gx = gx_vec[i] / std_dev;
            double gy = gy_vec[i] / std_dev;

            // Affine Jacobian
            Eigen::Matrix<double, 6, 1> sd;
            sd << gx, gy, gx * x, gx * y, gy * x, gy * y;

            steepest_descent_images[i] = sd;
            H += sd * sd.transpose();
        }

        // We REMOVED the permanent damping (H(k,k) *= 1.001) from here.
        // It belongs in the solver, not baked into the inverse permanently!

        double det = H.determinant();
        if (std::abs(det) < 1e-12) {
            H_inv = Eigen::Matrix<double, 6, 6>::Zero();
        } else {
            H_inv = H.inverse();
        }
    }

    // ==========================================
    // 3. ENGINE & SOLVERS (Ultra-Optimized)
    // ==========================================
    Engine::Engine() {}

    void Engine::set_reference(const Image &ref_img, int_t roi_x, int_t roi_y, int_t subset_size) {
        int_t cx = roi_x + subset_size / 2;
        int_t cy = roi_y + subset_size / 2;
        active_subset = std::make_unique<Subset>(cx, cy, subset_size);
        active_subset->initialize(ref_img);
    }

    void Engine::estimate_initial_guess(const Subset &subset, const Image &def_img, scalar_t &best_u, scalar_t &best_v) {
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
    AnalysisResult Engine::solve_icgn(const Subset &subset, const Image &def_img, double init_u, double init_v) {
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
    double Engine::evaluate_znssd(const Subset &subset, const Image &def_img,
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
    AnalysisResult Engine::solve_simplex(const Subset &subset, const Image &def_img, AnalysisResult start, bool translation_only) {
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

    // --- DICe-STYLE SMART GATEKEEPING ---
    AnalysisResult Engine::calculate_deformation(const Image &def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode) {
        if (!active_subset) return {0,0,0,0,0,0, 1, 1.0};

        scalar_t u = guess_u;
        scalar_t v = guess_v;
        AnalysisResult res;

        // SCENARIO 1: Seed Point Initialization
        if (init_mode == INIT_AUTO_SEARCH) {
            // Do a coarse grid search if we have absolutely no idea where we are
            if (u == 0.0 && v == 0.0) {
                estimate_initial_guess(*active_subset, def_img, u, v);
            }

            // Run Translation-Only Simplex to firmly establish the seed neighborhood
            AnalysisResult start_guess = {u, v, 0, 0, 0, 0, 0, 1.0};
            AnalysisResult coarse_res = solve_simplex(*active_subset, def_img, start_guess, true);

            // Now feed the robust u,v back to ICGN to solve for high-accuracy strains
            res = solve_icgn(*active_subset, def_img, coarse_res.u, coarse_res.v);
        }
            // SCENARIO 2: Standard Propagation
        else {
            // Trust the neighbor completely. Go straight to ICGN.
            res = solve_icgn(*active_subset, def_img, u, v);

            // CATASTROPHIC RESCUE ONLY
            // ZNSSD > 0.4 means the subset is completely lost (e.g. edge of a hole or heavy glare).
            if (res.status != 0 || res.correlation_score > 0.4) {
                LOGW("Catastrophic failure at %d, %d. Attempting Simplex Rescue...", active_subset->cx, active_subset->cy);
                AnalysisResult start_guess = {u, v, 0, 0, 0, 0, 0, 1.0};
                AnalysisResult rescue_res = solve_simplex(*active_subset, def_img, start_guess, true);
                res = solve_icgn(*active_subset, def_img, rescue_res.u, rescue_res.v);
            }
        }

        return res;
    }
}