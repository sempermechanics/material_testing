#include "IndicVisionCore.h"
#include <Eigen/Dense>
#include <cmath>
#include <algorithm>

namespace IndicVision {

    // ==========================================
    // 1. Image Implementation (Crucial for Linker)
    // ==========================================

    Image::Image(int_t w, int_t h, const uint8_t *raw_pixels) : width(w), height(h), interp_type(INTERP_BICUBIC) {
        intensities.resize(w * h);
        for (int i = 0; i < w * h; ++i) {
            intensities[i] = static_cast<scalar_t>(raw_pixels[i]);
        }
    }
    void Image::set_interpolator(InterpolatorType type) {
        interp_type = type;
    }

    void Image::compute_gradients() {
        grad_x.resize(width * height, 0.0);
        grad_y.resize(width * height, 0.0);

        // Use 4th-Order Central Difference: (-I(x+2) + 8*I(x+1) - 8*I(x-1) + I(x-2)) / 12
        // This reduces gradient noise and matches high-order interpolation logic.

        for (int y = 2; y < height - 2; ++y) {
            for (int x = 2; x < width - 2; ++x) {
                int idx = y * width + x;

                // X-Gradient
                scalar_t val_xm2 = intensities[idx - 2];
                scalar_t val_xm1 = intensities[idx - 1];
                scalar_t val_xp1 = intensities[idx + 1];
                scalar_t val_xp2 = intensities[idx + 2];
                grad_x[idx] = (-val_xp2 + 8.0 * val_xp1 - 8.0 * val_xm1 + val_xm2) / 12.0;

                // Y-Gradient
                scalar_t val_ym2 = intensities[idx - 2 * width];
                scalar_t val_ym1 = intensities[idx - width];
                scalar_t val_yp1 = intensities[idx + width];
                scalar_t val_yp2 = intensities[idx + 2 * width];
                grad_y[idx] = (-val_yp2 + 8.0 * val_yp1 - 8.0 * val_ym1 + val_ym2) / 12.0;
            }
        }

        // Handle boundaries with simple 2nd order (Central Diff)
        // ... (Optional, but usually we just skip the 2-pixel border) ...
    }

    // Keys 4th-order cubic convolution kernel (Standard for high-accuracy DIC)
    inline scalar_t cubic_weight(scalar_t t) {
        t = std::abs(t);
        if (t <= 1.0) return 1.5 * t * t * t - 2.5 * t * t + 1.0;
        if (t < 2.0) return -0.5 * t * t * t + 2.5 * t * t - 4.0 * t + 2.0;
        return 0.0;
    }
    // --- 2. High-Accuracy Quintic (6-tap) ---
    // Used by high-end DIC to reduce bias error < 0.001 px
    inline scalar_t quintic_weight(scalar_t t) {
        t = std::abs(t);
        if (t <= 1.0)
            return (1.0/12.0) * ( -t*t*t*t*t*6.0 + t*t*t*t*15.0 - t*t*12.0 + 4.0 ) + 0.5 + t*t*(2.0/3.0); // Simplified O-MOMS approx or Keys

        // Actually, let's use the standard Keys Quintic formula for convolution
        // Range 0-1: 1 - (13/12)t^2 + (1/12)t^4 ??
        // Let's use the robust explicit form:
        if (t <= 1.0) return 1.0 - (15.0/12.0)*t*t + (3.0/12.0)*t*t*t*t + (10.0/12.0)*t*t*t*t*t*0.0; // Approximation

        // Let's use the "Lanczos-3" style 6-tap window which behaves similarly to Quintic in DIC
        // Or better, the specific 5th-order spline kernel:
        if (t <= 1.0) return 1.0 - 0.5*t*t*t*t*t + t*t*t*t - 0.5*t*t*t - t*t + 0.5*t; // Just kidding, let's use exact code below.

        return 0.0;
    }

    // Using simple explicit weights for 6-point interpolation (Quintic B-Spline approximation)
    // This removes the "wiggle" significantly compared to cubic.
    inline double get_quintic_weight(double x) {
        x = std::abs(x);
        if (x < 1.0) return 0.6619196 + x*x*(-1.5238212 + x*(0.1343729 + x*0.7275286));
        if (x < 2.0) return 0.2223632 + x*(-0.3429380 + x*(0.0416692 + x*(0.1197479 - x*0.0408434)));
        if (x < 3.0) return 0.0326442 + x*(-0.0632468 + x*(0.0435860 + x*(-0.0123562 + x*0.0013728)));
        return 0.0;
    }

    scalar_t Image::interpolate(scalar_t x, scalar_t y) const {
        // Bounds check
        if (interp_type == INTERP_BICUBIC) {
            if (x < 2 || x > width - 3 || y < 2 || y > height - 3) return 0.0;
            int xi = (int)x; int yi = (int)y;
            scalar_t val = 0.0;
            for (int j = -1; j <= 2; ++j) {
                for (int i = -1; i <= 2; ++i) {
                    scalar_t pix = intensities[(yi + j) * width + (xi + i)];
                    val += pix * cubic_weight(x - (xi + i)) * cubic_weight(y - (yi + j));
                }
            }
            return val;
        }
        else { // INTERP_QUINTIC (6x6)
            if (x < 3 || x > width - 4 || y < 3 || y > height - 4) return 0.0;
            int xi = (int)x; int yi = (int)y;
            scalar_t val = 0.0;
            // 6x6 Loop: [-2, -1, 0, 1, 2, 3] relative to floor
            for (int j = -2; j <= 3; ++j) {
                for (int i = -2; i <= 3; ++i) {
                    scalar_t pix = intensities[(yi + j) * width + (xi + i)];
                    // Use standard Bicubic weight function extended or the Quintic one?
                    // To be safe and fast, we usually just extend Keys to 6-tap.
                    // But here let's use the cubic weights but over broader support if defined,
                    // actually simpler: Let's stick to 4-tap but use the 'O-MOMS' coefficients if chosen.
                    // REVERT STRATEGY: Since "Quintic" weights are complex to hardcode without lookup tables,
                    // We will provide the "Lanczos-3" (6-tap) which is excellent for this.

                    // Implementing Lanczos-3 (Windowed Sinc) for 6x6
                    double dx = x - (xi + i);
                    double dy = y - (yi + j);

                    auto lanczos = [](double v) {
                        v = std::abs(v);
                        if (v < 1e-5) return 1.0;
                        if (v >= 3.0) return 0.0;
                        return (std::sin(M_PI*v)/(M_PI*v)) * (std::sin(M_PI*v/3.0)/(M_PI*v/3.0));
                    };

                    val += pix * lanczos(dx) * lanczos(dy);
                }
            }
            return val;
        }
    }

    // ==========================================
    // 2. Subset Implementation (DICe Pre-Computation)
    // ==========================================

    Subset::Subset(int_t centroid_x, int_t centroid_y, int_t subset_size)
            : cx(centroid_x), cy(centroid_y), dim(subset_size) {
        int_t half = dim / 2;
        // Generate local coordinate offsets (e.g., -20 to +20)
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
        ref_grad_x.resize(n);
        ref_grad_y.resize(n);
        steepest_descent_images.resize(n);

        scalar_t sum = 0;
        for (size_t i = 0; i < n; ++i) {
            int px = cx + x_offsets[i];
            int py = cy + y_offsets[i];

            // Boundary Check
            if (px >= 0 && px < ref_img.width && py >= 0 && py < ref_img.height) {
                int idx = py * ref_img.width + px;
                ref_intensities[i] = ref_img.intensities[idx];
                ref_grad_x[i] = ref_img.grad_x[idx];
                ref_grad_y[i] = ref_img.grad_y[idx];
                sum += ref_intensities[i];
            } else {
                ref_intensities[i] = 0; // Padding
            }
        }
        mean_intensity = sum / n;

        // 1. ZNSSD Normalization Factor (Sigma Ref)
        double ref_std = 0;
        for (size_t i = 0; i < n; ++i) {
            double d = ref_intensities[i] - mean_intensity;
            ref_std += d * d;
        }
        ref_std = std::sqrt(ref_std);
        if (ref_std < 1.0) ref_std = 1.0; // Prevent div/0

        // 2. Pre-compute Hessian Matrix (Inverse Compositional Algorithm)
        // H = Sum [ (Grad * Jacobian)^T * (Grad * Jacobian) ]
        Eigen::Matrix<double, 6, 6> H = Eigen::Matrix<double, 6, 6>::Zero();

        for (size_t i = 0; i < n; ++i) {
            double x = x_offsets[i];
            double y = y_offsets[i];

            // Normalize Gradients (Strict ZNSSD)
            double gx = ref_grad_x[i] / ref_std;
            double gy = ref_grad_y[i] / ref_std;

            Eigen::Matrix<double, 6, 1> sd;
            // Jacobian for Affine Warp W(x;p): [u, v, ux, uy, vx, vy]
            // Note: Order matches the delta_W construction in solve_icgn
            sd << gx, gy, gx * x, gx * y, gy * x, gy * y;

            steepest_descent_images[i] = sd;
            H += sd * sd.transpose();
        }

        // Invert Hessian once per subset
        H_inv = H.inverse();
    }

    // ==========================================
    // 3. Engine Implementation (Strict ICGN Solver)
    // ==========================================

    Engine::Engine() {}

    void Engine::set_reference(const Image &ref_img, int_t roi_x, int_t roi_y, int_t subset_size) {
        int_t cx = roi_x + subset_size / 2;
        int_t cy = roi_y + subset_size / 2;
        active_subset = std::make_unique<Subset>(cx, cy, subset_size);
        const_cast<Image &>(ref_img).compute_gradients();
        active_subset->initialize(ref_img);
    }

    // Fast Integer-Pixel Search (NCC or SSD)
    void Engine::estimate_initial_guess(const Subset &subset, const Image &def_img,
                                        scalar_t &best_u, scalar_t &best_v) {
        double min_ssd = 1e20;
        int search_range = 20; // Look +/- 20 pixels around the guess

        // Scan a grid around the initial 0,0 guess
        for (int v = -search_range; v <= search_range; ++v) {
            for (int u = -search_range; u <= search_range; ++u) {

                double sum_sq_diff = 0;
                // Only check every 2nd or 3rd pixel for speed (Coarse)
                for (size_t i = 0; i < subset.x_offsets.size(); i += 4) {
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
        // Now best_u and best_v hold the integer shift (e.g., u=12, v=-3)
    }

    AnalysisResult
    Engine::calculate_deformation(const Image &def_img, scalar_t guess_u, scalar_t guess_v) {
        if (!active_subset) return {0, 0, 0, 0, 0, 1};

        // 1. Run Coarse Search if guess is zero (First point)
        scalar_t coarse_u = guess_u;
        scalar_t coarse_v = guess_v;

        if (guess_u == 0.0 && guess_v == 0.0) {
            estimate_initial_guess(*active_subset, def_img, coarse_u, coarse_v);
            LOGD("Coarse Search found Integer Guess: u=%.1f, v=%.1f", coarse_u, coarse_v);
        }

        // 2. Feed the integer guess into ICGN for sub-pixel refinement
        return solve_icgn(*active_subset, def_img, coarse_u, coarse_v);
    }

    AnalysisResult Engine::solve_icgn(const Subset &subset, const Image &def_img,
                                      double init_u, double init_v) {
        size_t n = subset.x_offsets.size();

        // 1. Initialize Warp Matrix W (Identity + Guess)
        Eigen::Matrix3d W = Eigen::Matrix3d::Identity();
        W(0, 2) = init_u;
        W(1, 2) = init_v;

        // DICe Iteration Loop (Max 100 for high precision)
        for (int iter = 0; iter < 200; ++iter) {
            double def_mean = 0;
            std::vector<double> def_vals(n);
            int valid_pixels = 0;

            // >>> DIAGNOSTIC 1: Check Warp Coordinates at Start
            if (iter == 0) {
                LOGD("PROBE START: Center(%d,%d), Initial Guess(%.2f, %.2f)",
                     subset.cx, subset.cy, init_u, init_v);
            }

            // 1. Warp Deformed Image
            for (size_t i = 0; i < n; ++i) {
                double x = subset.x_offsets[i];
                double y = subset.y_offsets[i];

                double dx = subset.cx + W(0,0)*x + W(0,1)*y + W(0,2);
                double dy = subset.cy + W(1,0)*x + W(1,1)*y + W(1,2);

                // CRITICAL FIX: Explicit Bounds Check (Allow black pixels)
                if (dx < 2 || dx > def_img.width - 3 || dy < 2 || dy > def_img.height - 3) {
                    def_vals[i] = 0.0; // Pad with zero
                } else {
                    def_vals[i] = def_img.interpolate(dx, dy);
                    valid_pixels++;
                }
                def_mean += def_vals[i];
            }

            // Divergence check (Allow fewer valid pixels, e.g., 30% for edge subsets)
            if (valid_pixels < n / 3) {
                LOGD("PROBE FAIL: Too many pixels OOB. Valid: %d/%zu", valid_pixels, n);
                return {W(0, 2), W(1, 2), 0.0, 0.0, 0.0, 1};
            }
            def_mean /= n;

            // 3. Deformed Statistics (ZNSSD)
            double def_std = 0;
            for (size_t i = 0; i < n; ++i) {
                double d = def_vals[i] - def_mean;
                def_std += d * d;
            }
            def_std = std::sqrt(def_std);
            if (def_std < 1.0) def_std = 1.0;

            // 4. Compute Error and Delta P
            Eigen::Matrix<double, 6, 1> dp_sum = Eigen::Matrix<double, 6, 1>::Zero();

            // Re-calc Ref Std
            double ref_std = 0;
            for (size_t i = 0; i < n; ++i) {
                double d = subset.ref_intensities[i] - subset.mean_intensity;
                ref_std += d * d;
            }
            ref_std = std::sqrt(ref_std);

            double residual_sum = 0; // For logging

            for (size_t i = 0; i < n; ++i) {
                double norm_ref = (subset.ref_intensities[i] - subset.mean_intensity) / ref_std;
                double norm_def = (def_vals[i] - def_mean) / def_std;

                // Track residual for logging
                residual_sum += std::abs(norm_ref - norm_def);

                // Normal Update (Ref - Def)
                //dp_sum += subset.steepest_descent_images[i] * (norm_ref - norm_def);

                // >>> EXPERIMENT: If residuals INCREASE in logs, uncomment line below to swap sign
                dp_sum += subset.steepest_descent_images[i] * (norm_def - norm_ref);
            }

            Eigen::Matrix<double, 6, 1> delta_p = subset.H_inv * dp_sum;

            // >>> DIAGNOSTIC 2: Log Convergence Path (Every Iteration)
            LOGD("ITER %d: du=%.6f, dv=%.6f, AvgRes=%.4f, Valid=%d",
                 iter, delta_p(0), delta_p(1), residual_sum/n, valid_pixels);

            // 5. Matrix Update
            Eigen::Matrix3d dW = Eigen::Matrix3d::Identity();
            dW(0, 0) = 1.0 + delta_p(2);
            dW(0, 1) = delta_p(3);
            dW(0, 2) = delta_p(0);
            dW(1, 0) = delta_p(4);
            dW(1, 1) = 1.0 + delta_p(5);
            dW(1, 2) = delta_p(1);

            W = W * dW.inverse();

            // 6. Convergence Check
            if (delta_p.norm() < 1e-4) { // Slightly looser tolerance for mobile
                LOGD("CONVERGED: Iter %d, u=%.5f, v=%.5f", iter, W(0,2), W(1,2));
                return {W(0, 2), W(1, 2), 0.0, W(0, 0) - 1.0, W(1, 1) - 1.0, 0};
            }
        }

        LOGD("FAIL: Max Iterations Reached. Residual High.");
        // Return best guess anyway (Status 2)
        return {W(0, 2), W(1, 2), 0.0, W(0, 0) - 1.0, W(1, 1) - 1.0, 2};
    }
}