#include "IndicVisionCore.h"
#include <Eigen/Dense>
#include <cmath>
#include <algorithm>
#include <atomic>

namespace IndicVision {

    // --- LOGGING UTILS ---
    static std::atomic<int> log_counter(0);

    // ==========================================
    // 1. IMAGE CLASS (Standard Setup)
    // ==========================================
    Image::Image(int_t w, int_t h, const uint8_t *raw_pixels)
            : width(w), height(h), interp_type(INTERP_BICUBIC), grad_type(GRAD_CENTRAL_DIFF) {
        intensities.resize(w * h);
        for (int i = 0; i < w * h; ++i) {
            intensities[i] = static_cast<scalar_t>(raw_pixels[i]);
        }
    }

    void Image::set_settings(InterpolatorType i_type, GradientType g_type) {
        interp_type = i_type;
        grad_type = g_type;
    }

    void Image::prepare_data() {
        if (interp_type == INTERP_BSPLINE || grad_type == GRAD_BSPLINE_ANALYTIC) {
            compute_bspline_coeffs();
        }
        compute_gradients_finite_diff();
        LOGD("Image Prepared: %dx%d", width, height);
    }

    void Image::compute_gradients_finite_diff() {
        grad_x.resize(width * height, 0.0);
        grad_y.resize(width * height, 0.0);
        for (int y = 2; y < height - 2; ++y) {
            for (int x = 2; x < width - 2; ++x) {
                int idx = y * width + x;
                grad_x[idx] = (-intensities[idx+2] + 8.0*intensities[idx+1] - 8.0*intensities[idx-1] + intensities[idx-2]) / 12.0;
                grad_y[idx] = (-intensities[idx+2*width] + 8.0*intensities[idx+width] - 8.0*intensities[idx-width] + intensities[idx-2*width]) / 12.0;
            }
        }
    }

    void Image::compute_bspline_coeffs() {
        bspline_coeffs = intensities;
        std::vector<double> line(std::max(width, height));
        double pole = -2.0 + std::sqrt(3.0);
        double gain = 6.0;

        // Filter Rows
        for (int y = 0; y < height; ++y) {
            double prev = 0.0;
            for(int x=0; x<width; ++x) {
                bspline_coeffs[y*width + x] += pole * prev;
                prev = bspline_coeffs[y*width + x];
            }
            prev = 0.0;
            for(int x=width-1; x>=0; --x) {
                bspline_coeffs[y*width + x] = gain * (bspline_coeffs[y*width + x] + pole * prev);
                prev = bspline_coeffs[y*width + x] / gain;
            }
        }
        // Filter Columns
        for (int x = 0; x < width; ++x) {
            double prev = 0.0;
            for(int y=0; y<height; ++y) {
                bspline_coeffs[y*width + x] += pole * prev;
                prev = bspline_coeffs[y*width + x];
            }
            prev = 0.0;
            for(int y=height-1; y>=0; --y) {
                bspline_coeffs[y*width + x] = gain * (bspline_coeffs[y*width + x] + pole * prev);
                prev = bspline_coeffs[y*width + x] / gain;
            }
        }
    }

    // --- KERNELS & INTERPOLATION ---
    inline double b3_spline(double t) {
        t = std::abs(t);
        if (t < 1.0) return (2.0/3.0) - t*t*(1.0 - t/2.0);
        if (t < 2.0) return (1.0/6.0) * (2.0 - t)*(2.0 - t)*(2.0 - t);
        return 0.0;
    }
    inline double b3_deriv(double t) {
        double sign = (t >= 0) ? 1.0 : -1.0;
        t = std::abs(t);
        if (t < 1.0) return sign * (-2.0*t + 1.5*t*t);
        if (t < 2.0) return sign * (-0.5 * (2.0 - t)*(2.0 - t));
        return 0.0;
    }
    inline scalar_t cubic_weight(scalar_t t) {
        t = std::abs(t);
        if (t <= 1.0) return 1.5 * t * t * t - 2.5 * t * t + 1.0;
        if (t < 2.0) return -0.5 * t * t * t + 2.5 * t * t - 4.0 * t + 2.0;
        return 0.0;
    }

    scalar_t Image::interpolate(scalar_t x, scalar_t y) const {
        if (interp_type == INTERP_BSPLINE) return bspline_val(x, y);
        if (x < 2 || x > width - 3 || y < 2 || y > height - 3) return 0.0; // Fast boundary check

        int xi = (int)x; int yi = (int)y;
        scalar_t val = 0.0;
        for (int j = -1; j <= 2; ++j) {
            for (int i = -1; i <= 2; ++i) {
                scalar_t pix = intensities[(yi + j) * width + (xi + i)];
                double wx = cubic_weight(x-(xi+i));
                double wy = cubic_weight(y-(yi+j));
                val += pix * wx * wy;
            }
        }
        return val;
    }

    scalar_t Image::bspline_val(scalar_t x, scalar_t y) const {
        if (x < 2 || x > width - 3 || y < 2 || y > height - 3) return 0.0;
        int xi = (int)std::floor(x); int yi = (int)std::floor(y);
        scalar_t val = 0.0;
        for (int j = -1; j <= 2; ++j) {
            for (int i = -1; i <= 2; ++i) {
                val += bspline_coeffs[(yi + j) * width + (xi + i)] * b3_spline(x - (xi + i)) * b3_spline(y - (yi + j));
            }
        }
        return val;
    }

    scalar_t Image::gradient_x(scalar_t x, scalar_t y) const {
        if (grad_type == GRAD_BSPLINE_ANALYTIC) return bspline_grad_x(x, y);
        int xi = (int)x; int yi = (int)y;
        if (xi < 0 || xi >= width-1 || yi < 0 || yi >= height-1) return 0.0;
        double dx = x - xi; double dy = y - yi;
        double g00 = grad_x[yi*width + xi];
        double g10 = grad_x[yi*width + xi+1];
        double g01 = grad_x[(yi+1)*width + xi];
        double g11 = grad_x[(yi+1)*width + xi+1];
        return (1-dx)*(1-dy)*g00 + dx*(1-dy)*g10 + (1-dx)*dy*g01 + dx*dy*g11;
    }

    scalar_t Image::gradient_y(scalar_t x, scalar_t y) const {
        if (grad_type == GRAD_BSPLINE_ANALYTIC) return bspline_grad_y(x, y);
        int xi = (int)x; int yi = (int)y;
        if (xi < 0 || xi >= width-1 || yi < 0 || yi >= height-1) return 0.0;
        double dx = x - xi; double dy = y - yi;
        double g00 = grad_y[yi*width + xi];
        double g10 = grad_y[yi*width + xi+1];
        double g01 = grad_y[(yi+1)*width + xi];
        double g11 = grad_y[(yi+1)*width + xi+1];
        return (1-dx)*(1-dy)*g00 + dx*(1-dy)*g10 + (1-dx)*dy*g01 + dx*dy*g11;
    }

    scalar_t Image::bspline_grad_x(scalar_t x, scalar_t y) const {
        if (x < 2 || x > width - 3 || y < 2 || y > height - 3) return 0.0;
        int xi = (int)std::floor(x); int yi = (int)std::floor(y);
        scalar_t val = 0.0;
        for (int j = -1; j <= 2; ++j) {
            for (int i = -1; i <= 2; ++i) {
                val += bspline_coeffs[(yi + j) * width + (xi + i)] * b3_deriv(x - (xi + i)) * b3_spline(y - (yi + j));
            }
        }
        return val;
    }

    scalar_t Image::bspline_grad_y(scalar_t x, scalar_t y) const {
        if (x < 2 || x > width - 3 || y < 2 || y > height - 3) return 0.0;
        int xi = (int)std::floor(x); int yi = (int)std::floor(y);
        scalar_t val = 0.0;
        for (int j = -1; j <= 2; ++j) {
            for (int i = -1; i <= 2; ++i) {
                val += bspline_coeffs[(yi + j) * width + (xi + i)] * b3_spline(x - (xi + i)) * b3_deriv(y - (yi + j));
            }
        }
        return val;
    }

    // ==========================================
    // 2. SUBSET INITIALIZATION (With Matrix Math)
    // ==========================================
    Subset::Subset(int_t centroid_x, int_t centroid_y, int_t subset_size)
            : cx(centroid_x), cy(centroid_y), dim(subset_size) {
        int_t half = dim / 2;
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

        scalar_t sum = 0;
        // Temporary gradient storage
        std::vector<scalar_t> gx_vec(n);
        std::vector<scalar_t> gy_vec(n);

        // --- CHANGE 1: Calculate Mean AND Standard Deviation ---
        for (size_t i = 0; i < n; ++i) {
            double px = cx + x_offsets[i];
            double py = cy + y_offsets[i];

            ref_intensities[i] = ref_img.interpolate(px, py);
            sum += ref_intensities[i];

            // Calculate gradients at reference position
            gx_vec[i] = ref_img.gradient_x(px, py);
            gy_vec[i] = ref_img.gradient_y(px, py);
        }
        mean_intensity = sum / n;

        // Calculate Sum of Squared Differences for Std Dev
        double sum_sq_diff = 0.0;
        for(double val : ref_intensities) {
            sum_sq_diff += (val - mean_intensity) * (val - mean_intensity);
        }
        double ref_std = std::sqrt(sum_sq_diff / n); // Population std dev

        // Safety: Prevent division by zero if image is purely flat black/white
        if (ref_std < 1e-5) ref_std = 1.0;

        // --- END CHANGE 1 ---

        // Build Hessian
        Eigen::Matrix<double, 6, 6> H = Eigen::Matrix<double, 6, 6>::Zero();

        for (size_t i = 0; i < n; ++i) {
            double x = x_offsets[i]; // Local centered
            double y = y_offsets[i];

            // --- CHANGE 2: Normalize Gradients ---
            // We divide raw gradients by the subset's standard deviation
            // This effectively converts units from "Intensity" to "Sigmas"
            double gx = gx_vec[i] / ref_std;
            double gy = gy_vec[i] / ref_std;
            // --- END CHANGE 2 ---

            // 6-DOF Affine Jacobian [u, v, ux, uy, vx, vy]
            // W(x) = [ (1+ux)x + uy*y + u ]
            //        [ vx*x + (1+vy)y + v ]

            Eigen::Matrix<double, 6, 1> sd;
            sd(0) = gx;       // du
            sd(1) = gy;       // dv
            sd(2) = gx * x;   // dux
            sd(3) = gx * y;   // duy
            sd(4) = gy * x;   // dvx
            sd(5) = gy * y;   // dvy

            steepest_descent_images[i] = sd;
            H += sd * sd.transpose();
        }
        H_inv = H.inverse();

        // --- CHANGE 3: Better Debugging ---
        // Log the Std Dev so we can confirm the fix works
        LOGD("Subset Init @ (%d,%d) | StdDev: %.4f | H_inv norm: %.4f", cx, cy, ref_std, H_inv.norm());
        // --- END CHANGE 3 ---
    }

    // ==========================================
    // 3. ENGINE & SOLVERS
    // ==========================================
    Engine::Engine() {}

    void Engine::set_reference(const Image &ref_img, int_t roi_x, int_t roi_y, int_t subset_size) {
        int_t cx = roi_x + subset_size / 2;
        int_t cy = roi_y + subset_size / 2;
        active_subset = std::make_unique<Subset>(cx, cy, subset_size);
        active_subset->initialize(ref_img);
        LOGD("Engine Reference Set: ROI %d,%d Size %d", roi_x, roi_y, subset_size);
    }

    void Engine::estimate_initial_guess(const Subset &subset, const Image &def_img,
                                        scalar_t &best_u, scalar_t &best_v) {
        double min_ssd = 1e20;
        int search_range = 15;
        for (int v = -search_range; v <= search_range; ++v) {
            for (int u = -search_range; u <= search_range; ++u) {
                double sum_sq_diff = 0;
                for (size_t i = 0; i < subset.x_offsets.size(); i += 8) { // Skip some pixels for speed
                    double x = subset.cx + subset.x_offsets[i];
                    double y = subset.cy + subset.y_offsets[i];
                    double def_val = def_img.interpolate(x + u, y + v);
                    double diff = subset.ref_intensities[i] - def_val;
                    sum_sq_diff += diff * diff;
                }
                if (sum_sq_diff < min_ssd) {
                    min_ssd = sum_sq_diff;
                    best_u = (scalar_t) u;
                    best_v = (scalar_t) v;
                }
            }
        }
        LOGD("Initial Guess Found: u=%.2f, v=%.2f (Cost: %.2f)", best_u, best_v, min_ssd);
    }

    // --- ICGN SOLVER (Pure Matrix Logic) ---
    AnalysisResult Engine::solve_icgn(const Subset &subset, const Image &def_img,
                                      double init_u, double init_v) {
        size_t n = subset.x_offsets.size();

        // --- DEBUG TRIGGER: Only log for the center of the image ---
        // Assuming image is ~2048x589, center is approx 1024, 294
        // We use a small range (+- 10 pixels) to catch the seed point
        bool debug_mode = (std::abs(subset.cx - 1024) < 10) && (std::abs(subset.cy - 294) < 10);

        if (debug_mode) {
            LOGD(">>> DEBUG TRACE: Starting ICGN at X=%d, Y=%d", subset.cx, subset.cy);
            LOGD(">>> Initial Guess: u=%.6f, v=%.6f", init_u, init_v);
        }

        // Matrix W is the warp.
        // W = [ 1+ux  uy  u ]
        //     [ vx  1+vy  v ]
        //     [ 0     0   1 ]

        Eigen::Matrix3d W = Eigen::Matrix3d::Identity();
        W(0, 2) = init_u;
        W(1, 2) = init_v;

        double final_score = 1.0;

        for (int iter = 0; iter < 25; ++iter) {
            double def_mean = 0;
            std::vector<double> def_vals(n);
            int valid_pixels = 0;

            // 1. Warp Pixels
            for (size_t i = 0; i < n; ++i) {
                double x = subset.x_offsets[i];
                double y = subset.y_offsets[i];

                // Global Coordinate = Center + Warp(Local Offset)
                double wx = W(0,0)*x + W(0,1)*y + W(0,2);
                double wy = W(1,0)*x + W(1,1)*y + W(1,2);

                double final_x = subset.cx + wx;
                double final_y = subset.cy + wy;

                // Boundary check
                if (final_x < 2.0 || final_x > def_img.width - 3.0 ||
                    final_y < 2.0 || final_y > def_img.height - 3.0) {
                    def_vals[i] = 0.0;
                } else {
                    def_vals[i] = def_img.interpolate(final_x, final_y);
                    valid_pixels++;
                }
                def_mean += def_vals[i];
            }

            if (valid_pixels < n / 2) {
                if (debug_mode) LOGE("ICGN OOB: Valid Pixels %d/%d (Iter %d)", valid_pixels, (int)n, iter);
                return {0,0,0,0,0,0, 1, 1.0};
            }

            // 2. Normalization
            def_mean /= n;
            double def_sum_sq = 0;
            for(double v : def_vals) def_sum_sq += (v - def_mean)*(v - def_mean);
            double def_std = std::sqrt(def_sum_sq);
            if(def_std < 1e-5) def_std = 1.0;

            double ref_mean = subset.mean_intensity;
            double ref_sum_sq = 0;
            for(double v : subset.ref_intensities) ref_sum_sq += (v - ref_mean)*(v - ref_mean);
            double ref_std = std::sqrt(ref_sum_sq);
            if(ref_std < 1e-5) ref_std = 1.0;

            // 3. Compute Gradients & Delta
            Eigen::Matrix<double, 6, 1> dp_sum = Eigen::Matrix<double, 6, 1>::Zero();
            double residual_sum = 0;

            for (size_t i = 0; i < n; ++i) {
                double norm_ref = (subset.ref_intensities[i] - ref_mean) / ref_std;
                double norm_def = (def_vals[i] - def_mean) / def_std;
                double diff = norm_ref - norm_def;
                residual_sum += diff * diff;
                dp_sum += subset.steepest_descent_images[i] * (norm_def - norm_ref);
            }

            final_score = residual_sum / n;

            // Delta P = -H_inv * Sum(SD * Error)
            Eigen::Matrix<double, 6, 1> delta_p = -subset.H_inv * dp_sum;

            // 4. Update W (Inverse Compositional)
            // dW is constructed from delta_p
            Eigen::Matrix3d dW = Eigen::Matrix3d::Identity();
            dW(0,0) += delta_p(2); // ux
            dW(0,1) += delta_p(3); // uy
            dW(0,2) += delta_p(0); // u
            dW(1,0) += delta_p(4); // vx
            dW(1,1) += delta_p(5); // vy
            dW(1,2) += delta_p(1); // v

            W = W * dW.inverse();

            // --- DEBUG LOGGING (TRACE MODE) ---
            if (debug_mode) {
                LOGD("   Iter %d | Score: %.8f | Delta U: %.6f | Delta V: %.6f",
                     iter, final_score, delta_p(0), delta_p(1));
            }

            // 5. Convergence
            // Tightened tolerance to 1e-5 for Sample 14 high precision
            if (delta_p.norm() < 1e-5) {
                if (debug_mode) LOGD(">>> ICGN CONVERGED. Final U=%.6f", W(0,2));
                return {
                        W(0,2), W(1,2),       // u, v
                        W(0,0)-1.0, W(0,1),   // ux, uy
                        W(1,0), W(1,1)-1.0,   // vx, vy
                        0, final_score
                };
            }
        }

        if (debug_mode) LOGD(">>> ICGN MAX ITERS REACHED. Final U=%.6f", W(0,2));
        return {
                W(0,2), W(1,2),
                W(0,0)-1.0, W(0,1),
                W(1,0), W(1,1)-1.0,
                1, final_score
        };
    }

    // --- EVALUATE ZNSSD (Cost Function) ---
    double Engine::evaluate_znssd(const Subset &subset, const Image &def_img,
                                  double u, double v, double ux, double uy, double vx, double vy) {
        size_t n = subset.x_offsets.size();
        double def_mean = 0.0;
        double def_sum_sq = 0.0;
        std::vector<double> def_vals(n);
        int valid_pixels = 0;

        for (size_t i = 0; i < n; ++i) {
            double dx = subset.x_offsets[i];
            double dy = subset.y_offsets[i];

            // Pure Affine Warp
            double final_x = subset.cx + u + (1.0 + ux) * dx + uy * dy;
            double final_y = subset.cy + v + vx * dx + (1.0 + vy) * dy;

            if (final_x < 2 || final_x > def_img.width - 3 ||
                final_y < 2 || final_y > def_img.height - 3) {
                def_vals[i] = 0.0;
            } else {
                def_vals[i] = def_img.interpolate(final_x, final_y);
                valid_pixels++;
            }
            def_mean += def_vals[i];
        }

        if (valid_pixels < n / 2) return 2.0;

        def_mean /= n;
        for (double val : def_vals) def_sum_sq += (val - def_mean)*(val - def_mean);
        double def_std = std::sqrt(def_sum_sq);
        if (def_std < 1e-5) def_std = 1.0;

        // Recalc Reference (could be cached)
        double ref_mean = subset.mean_intensity;
        double ref_sum_sq = 0;
        for (double val : subset.ref_intensities) ref_sum_sq += (val - ref_mean)*(val - ref_mean);
        double ref_std = std::sqrt(ref_sum_sq);
        if (ref_std < 1e-5) ref_std = 1.0;

        double znssd = 0.0;
        for (size_t i = 0; i < n; ++i) {
            double norm_ref = (subset.ref_intensities[i] - ref_mean) / ref_std;
            double norm_def = (def_vals[i] - def_mean) / def_std;
            double diff = norm_ref - norm_def;
            znssd += diff * diff;
        }
        return znssd / n;
    }

    // --- SIMPLEX SOLVER ---
    AnalysisResult Engine::solve_simplex(const Subset &subset, const Image &def_img, AnalysisResult start) {

        // Safety Valve
        //if (start.correlation_score < 0.0005) {
            //LOGD("Skipping Simplex: Score %.6f is good enough", start.correlation_score);
            //return start;
        //}
        //if (start.status == 1) {
            //LOGE("Skipping Simplex: ICGN failed status.");
            //return start;
        //}

        LOGD("Starting Simplex... Init Cost: %.6f", start.correlation_score);

        const double alpha = 1.0, gamma = 2.0, rho = 0.5, sigma = 0.5;
        const int DIM = 6;
        int n_pts = DIM + 1;

        // Params: u, v, ux, uy, vx, vy
        std::vector<std::vector<double>> p(n_pts, std::vector<double>(DIM));
        std::vector<double> y(n_pts);

        // Scales: 0.05px translation, 0.002 strain
        double scale[] = {0.005, 0.005, 0.001, 0.001, 0.001, 0.001};

        // Vertex 0
        p[0] = {start.u, start.v, start.ux, start.uy, start.vx, start.vy};
        y[0] = evaluate_znssd(subset, def_img, p[0][0], p[0][1], p[0][2], p[0][3], p[0][4], p[0][5]);

        for (int i = 1; i < n_pts; ++i) {
            p[i] = p[0];
            p[i][i-1] += scale[i-1];
            y[i] = evaluate_znssd(subset, def_img, p[i][0], p[i][1], p[i][2], p[i][3], p[i][4], p[i][5]);
        }

        for (int iter = 0; iter < 100; ++iter) {
            std::vector<int> idx(n_pts);
            for(int k=0; k<n_pts; ++k) idx[k] = k;
            std::sort(idx.begin(), idx.end(), [&](int a, int b){ return y[a] < y[b]; });

            //if (std::abs(y[idx[0]] - y[idx[n_pts-1]]) < 1e-6) break;
            if (std::abs(y[idx[0]] - y[idx[n_pts-1]]) < 1e-8) break;

            std::vector<double> p_bar(DIM, 0.0);
            for (int i = 0; i < DIM; ++i)
                for (int j = 0; j < DIM; ++j)
                    p_bar[j] += p[idx[i]][j];
            for (int j = 0; j < DIM; ++j) p_bar[j] /= DIM;

            // Reflection
            std::vector<double> p_r(DIM);
            for (int j = 0; j < DIM; ++j) p_r[j] = p_bar[j] + alpha * (p_bar[j] - p[idx[n_pts-1]][j]);
            double y_r = evaluate_znssd(subset, def_img, p_r[0], p_r[1], p_r[2], p_r[3], p_r[4], p_r[5]);

            if (y[idx[0]] <= y_r && y_r < y[idx[n_pts-2]]) {
                p[idx[n_pts-1]] = p_r;
                y[idx[n_pts-1]] = y_r;
            } else if (y_r < y[idx[0]]) {
                // Expansion
                std::vector<double> p_e(DIM);
                for (int j = 0; j < DIM; ++j) p_e[j] = p_bar[j] + gamma * (p_r[j] - p_bar[j]);
                double y_e = evaluate_znssd(subset, def_img, p_e[0], p_e[1], p_e[2], p_e[3], p_e[4], p_e[5]);
                if (y_e < y_r) { p[idx[n_pts-1]] = p_e; y[idx[n_pts-1]] = y_e; }
                else           { p[idx[n_pts-1]] = p_r; y[idx[n_pts-1]] = y_r; }
            } else {
                // Contraction
                std::vector<double> p_c(DIM);
                bool outside = (y_r < y[idx[n_pts-1]]);
                std::vector<double>& base_p = outside ? p_r : p[idx[n_pts-1]];

                for (int j = 0; j < DIM; ++j) p_c[j] = p_bar[j] + rho * (base_p[j] - p_bar[j]);
                double y_c = evaluate_znssd(subset, def_img, p_c[0], p_c[1], p_c[2], p_c[3], p_c[4], p_c[5]);

                if (y_c < std::min(y_r, y[idx[n_pts-1]])) {
                    p[idx[n_pts-1]] = p_c;
                    y[idx[n_pts-1]] = y_c;
                } else {
                    // Shrink
                    for (int i = 1; i < n_pts; ++i) {
                        for (int j = 0; j < DIM; ++j)
                            p[idx[i]][j] = p[idx[0]][j] + sigma * (p[idx[i]][j] - p[idx[0]][j]);
                        y[idx[i]] = evaluate_znssd(subset, def_img, p[idx[i]][0], p[idx[i]][1], p[idx[i]][2], p[idx[i]][3], p[idx[i]][4], p[idx[i]][5]);
                    }
                }
            }
        }

        int best = 0;
        for(int k=1; k<n_pts; ++k) if(y[k] < y[best]) best = k;

        LOGD("Simplex Finished. Final Cost: %.6f", y[best]);

        return {p[best][0], p[best][1], p[best][2], p[best][3], p[best][4], p[best][5], 0, y[best]};
    }

    AnalysisResult Engine::calculate_deformation(const Image &def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode) {
        if (!active_subset) return {0,0,0,0,0,0, 1, 1.0};

        scalar_t u = guess_u;
        scalar_t v = guess_v;

        if (u == 0.0 && v == 0.0) {
            // Placeholder: In a real "Pure Matrix" setup, you might want AKAZE/Feature match here
            // reusing your existing grid search logic:
            estimate_initial_guess(*active_subset, def_img, u, v);
        }

        AnalysisResult res = solve_icgn(*active_subset, def_img, u, v);
        res = solve_simplex(*active_subset, def_img, res);

        return res;
    }
}