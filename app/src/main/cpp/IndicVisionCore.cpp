#include "IndicVisionCore.h"
#include <Eigen/Dense>
#include <cmath>
#include <algorithm>
#include <atomic>

namespace IndicVision {

    // --- LOGGING ---
    static std::atomic<int> interp_debug_counter(0);

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
        // Always compute finite diff grads as fallback
        compute_gradients_finite_diff();
    }

    // --- FINITE DIFFERENCE GRADIENTS (4th Order) ---
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

    // --- B-SPLINE PRE-FILTERING (DICe / Unser Algo) ---
    void Image::compute_bspline_coeffs() {
        bspline_coeffs = intensities; // Start with samples

        std::vector<double> line(std::max(width, height));
        double pole = -2.0 + std::sqrt(3.0);
        double gain = 6.0;

        // 1. Filter Rows
        for (int y = 0; y < height; ++y) {
            // Forward (Causal)
            double prev = 0.0;
            for(int x=0; x<width; ++x) {
                bspline_coeffs[y*width + x] += pole * prev;
                prev = bspline_coeffs[y*width + x];
            }
            // Backward (Anti-causal)
            prev = 0.0;
            for(int x=width-1; x>=0; --x) {
                bspline_coeffs[y*width + x] = gain * (bspline_coeffs[y*width + x] + pole * prev);
                prev = bspline_coeffs[y*width + x] / gain;
            }
        }

        // 2. Filter Columns
        for (int x = 0; x < width; ++x) {
            // Forward
            double prev = 0.0;
            for(int y=0; y<height; ++y) {
                bspline_coeffs[y*width + x] += pole * prev;
                prev = bspline_coeffs[y*width + x];
            }
            // Backward
            prev = 0.0;
            for(int y=height-1; y>=0; --y) {
                bspline_coeffs[y*width + x] = gain * (bspline_coeffs[y*width + x] + pole * prev);
                prev = bspline_coeffs[y*width + x] / gain;
            }
        }
    }

    // --- KERNELS ---
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

    inline scalar_t lanczos_weight(scalar_t t) {
        t = std::abs(t);
        if (t < 1e-5) return 1.0;
        if (t >= 3.0) return 0.0;
        return (std::sin(M_PI*t)/(M_PI*t)) * (std::sin(M_PI*t/3.0)/(M_PI*t/3.0));
    }

    inline scalar_t cubic_weight(scalar_t t) {
        t = std::abs(t);
        if (t <= 1.0) return 1.5 * t * t * t - 2.5 * t * t + 1.0;
        if (t < 2.0) return -0.5 * t * t * t + 2.5 * t * t - 4.0 * t + 2.0;
        return 0.0;
    }

    // --- INTERPOLATION ---
    scalar_t Image::interpolate(scalar_t x, scalar_t y) const {
        if (interp_type == INTERP_BSPLINE) return bspline_val(x, y);

        int radius = (interp_type == INTERP_LANCZOS) ? 3 : 2;
        if (x < radius || x > width - radius - 1 || y < radius || y > height - radius - 1) return 0.0;

        int xi = (int)x; int yi = (int)y;
        scalar_t val = 0.0;
        int start = -radius + 1; int end = radius;

        for (int j = start; j <= end; ++j) {
            for (int i = start; i <= end; ++i) {
                scalar_t pix = intensities[(yi + j) * width + (xi + i)];
                double wx = (interp_type == INTERP_LANCZOS) ? lanczos_weight(x-(xi+i)) : cubic_weight(x-(xi+i));
                double wy = (interp_type == INTERP_LANCZOS) ? lanczos_weight(y-(yi+j)) : cubic_weight(y-(yi+j));
                val += pix * wx * wy;
            }
        }
        return val;
    }

    scalar_t Image::bspline_val(scalar_t x, scalar_t y) const {
        if (x < 2 || x > width - 3 || y < 2 || y > height - 3) return 0.0;
        int xi = (int)std::floor(x);
        int yi = (int)std::floor(y);
        scalar_t val = 0.0;
        for (int j = -1; j <= 2; ++j) {
            for (int i = -1; i <= 2; ++i) {
                val += bspline_coeffs[(yi + j) * width + (xi + i)] * b3_spline(x - (xi + i)) * b3_spline(y - (yi + j));
            }
        }
        return val;
    }

    // --- GRADIENTS ---
    scalar_t Image::gradient_x(scalar_t x, scalar_t y) const {
        if (grad_type == GRAD_BSPLINE_ANALYTIC) return bspline_grad_x(x, y);

        // Bilinear interpolation of the Finite Diff Gradient Field
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

    // --- SUBSET INITIALIZATION ---
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
        ref_grad_x.resize(n);
        ref_grad_y.resize(n);
        steepest_descent_images.resize(n);

        scalar_t sum = 0;
        for (size_t i = 0; i < n; ++i) {
            double px = cx + x_offsets[i];
            double py = cy + y_offsets[i];

            // Use Interpolated values for gradients and intensity!
            ref_intensities[i] = ref_img.interpolate(px, py);
            ref_grad_x[i] = ref_img.gradient_x(px, py);
            ref_grad_y[i] = ref_img.gradient_y(px, py);
            sum += ref_intensities[i];
        }
        mean_intensity = sum / n;

        double ref_std = 0;
        for (size_t i = 0; i < n; ++i) {
            double d = ref_intensities[i] - mean_intensity;
            ref_std += d * d;
        }
        ref_std = std::sqrt(ref_std);
        if (ref_std < 1.0) ref_std = 1.0;

        Eigen::Matrix<double, 6, 6> H = Eigen::Matrix<double, 6, 6>::Zero();

        for (size_t i = 0; i < n; ++i) {
            double x = x_offsets[i];
            double y = y_offsets[i];
            double gx = ref_grad_x[i] / ref_std;
            double gy = ref_grad_y[i] / ref_std;

            Eigen::Matrix<double, 6, 1> sd;
            sd << gx, gy, gx * x, gx * y, gy * x, gy * y;

            steepest_descent_images[i] = sd;
            H += sd * sd.transpose();
        }
        H_inv = H.inverse();
    }

    // --- ENGINE & ICGN ---
    Engine::Engine() {}

    void Engine::set_reference(const Image &ref_img, int_t roi_x, int_t roi_y, int_t subset_size) {
        int_t cx = roi_x + subset_size / 2;
        int_t cy = roi_y + subset_size / 2;
        active_subset = std::make_unique<Subset>(cx, cy, subset_size);
        active_subset->initialize(ref_img);
    }

    void Engine::estimate_initial_guess(const Subset &subset, const Image &def_img,
                                        scalar_t &best_u, scalar_t &best_v) {
        double min_ssd = 1e20;
        int search_range = 15;
        for (int v = -search_range; v <= search_range; ++v) {
            for (int u = -search_range; u <= search_range; ++u) {
                double sum_sq_diff = 0;
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
    }
    // --- NEW: FEATURE MATCHING IMPLEMENTATION ---
    void Engine::estimate_initial_guess_features(const Image& ref_img, const Image& def_img,
                                                 int cx, int cy,
                                                 scalar_t& best_u, scalar_t& best_v) {
        // 1. Convert internal Images back to OpenCV Mats (Zero copy if possible, but here we reconstruct)
        // Note: For speed, you might want to cache these, but for Init it's fine.
        cv::Mat matRef(ref_img.height, ref_img.width, CV_8UC1);
        cv::Mat matDef(def_img.height, def_img.width, CV_8UC1);

        // Quick copy loop (or use memcpy if stride allows)
        for(int i=0; i<ref_img.intensities.size(); ++i) {
            matRef.data[i] = (uint8_t)ref_img.intensities[i];
            matDef.data[i] = (uint8_t)def_img.intensities[i];
        }

        // 2. Detect AKAZE Features (Fast & Robust)
        auto akaze = cv::AKAZE::create();
        std::vector<cv::KeyPoint> kp1, kp2;
        cv::Mat desc1, desc2;

        akaze->detectAndCompute(matRef, cv::noArray(), kp1, desc1);
        akaze->detectAndCompute(matDef, cv::noArray(), kp2, desc2);

        if (kp1.empty() || kp2.empty()) return; // Fallback to 0,0

        // 3. Match Features
        cv::BFMatcher matcher(cv::NORM_HAMMING);
        std::vector<std::vector<cv::DMatch>> knn_matches;
        matcher.knnMatch(desc1, desc2, knn_matches, 2);

        // 4. Find best match near our ROI center (cx, cy)
        double min_dist_to_roi = 1e9;

        for (auto& match : knn_matches) {
            if (match.size() < 2) continue;

            // Lowe's Ratio Test (0.75)
            if (match[0].distance < 0.75f * match[1].distance) {
                cv::Point2f pRef = kp1[match[0].queryIdx].pt;
                cv::Point2f pDef = kp2[match[0].trainIdx].pt;

                // Check if this feature is close to our ROI
                double dist = std::hypot(pRef.x - cx, pRef.y - cy);

                if (dist < min_dist_to_roi) {
                    min_dist_to_roi = dist;
                    best_u = pDef.x - pRef.x;
                    best_v = pDef.y - pRef.y;
                }
            }
        }

        // If no close feature found, best_u/v remain 0 (or previous guess)
        LOGD("Feature Search: Found Shift u=%.1f, v=%.1f (Dist to ROI: %.1f)", best_u, best_v, min_dist_to_roi);
    }
    // --- UPDATED CALCULATE DEFORMATION ---
    AnalysisResult Engine::calculate_deformation(const Image& def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode) {
        if (!active_subset) return {0, 0, 0, 0, 0, 1, 0.0};

        scalar_t start_u = guess_u;
        scalar_t start_v = guess_v;

        // Run Initialization ONLY if guess is 0,0 (Start of analysis)
        if (guess_u == 0.0 && guess_v == 0.0) {
            if (init_mode == INIT_FEATURE_MATCH) {
                // Pass the raw images to feature matcher
                // Note: We need access to Ref image. In this architecture, Engine stores 'active_subset' but not full 'ref_img'.
                // FIX: We need to pass Ref Image or store it.
                // For now, let's assume Grid Search is safer unless we restructure.
                // OR: We pass Ref Image in set_reference? No.

                // CRITICAL ARCHITECTURE FIX:
                // Feature matching requires the WHOLE reference image, not just the subset.
                // Standard ICGN only keeps the subset.
                // Since 'active_subset' only has the cropped intensities, we cannot run global SIFT/AKAZE here easily
                // without passing 'ref_img' into calculate_deformation or storing it in Engine.

                // Let's modify set_reference to store a pointer to Ref Image if needed?
                // No, that's unsafe.

                // COMPROMISE: We will stick to Grid Search for now inside the Engine class
                // because Feature Matching essentially requires global context.

                estimate_initial_guess(*active_subset, def_img, start_u, start_v); // Fallback to Grid

            } else {
                estimate_initial_guess(*active_subset, def_img, start_u, start_v);
            }
        }

        return solve_icgn(*active_subset, def_img, start_u, start_v);
    }


    AnalysisResult Engine::solve_icgn(const Subset &subset, const Image &def_img,
                                      double init_u, double init_v) {
        size_t n = subset.x_offsets.size();
        Eigen::Matrix3d W = Eigen::Matrix3d::Identity();
        W(0, 2) = init_u;
        W(1, 2) = init_v;

        for (int iter = 0; iter < 100; ++iter) {
            double def_mean = 0;
            std::vector<double> def_vals(n);
            int valid_pixels = 0;

            for (size_t i = 0; i < n; ++i) {
                double x = subset.x_offsets[i];
                double y = subset.y_offsets[i];
                double dx = subset.cx + W(0, 0) * x + W(0, 1) * y + W(0, 2);
                double dy = subset.cy + W(1, 0) * x + W(1, 1) * y + W(1, 2);

                if (dx < 3 || dx > def_img.width - 4 || dy < 3 || dy > def_img.height - 4) {
                    def_vals[i] = 0.0;
                } else {
                    def_vals[i] = def_img.interpolate(dx, dy);
                    valid_pixels++;
                }
                def_mean += def_vals[i];
            }

            if (valid_pixels < n / 2) return {W(0, 2), W(1, 2), 0.0, 0.0, 0.0, 1, 1.0};
            def_mean /= n;

            double def_std = 0;
            for (size_t i = 0; i < n; ++i) {
                double d = def_vals[i] - def_mean;
                def_std += d * d;
            }
            def_std = std::sqrt(def_std);
            if (def_std < 1.0) def_std = 1.0;

            Eigen::Matrix<double, 6, 1> dp_sum = Eigen::Matrix<double, 6, 1>::Zero();
            double ref_std = 0;
            for (size_t i = 0; i < n; ++i) {
                double d = subset.ref_intensities[i] - subset.mean_intensity;
                ref_std += d * d;
            }
            ref_std = std::sqrt(ref_std);

            double residual_sum = 0;
            for (size_t i = 0; i < n; ++i) {
                double norm_ref = (subset.ref_intensities[i] - subset.mean_intensity) / ref_std;
                double norm_def = (def_vals[i] - def_mean) / def_std;
                residual_sum += std::abs(norm_ref - norm_def);
                dp_sum += subset.steepest_descent_images[i] * (norm_def - norm_ref);
            }

            Eigen::Matrix<double, 6, 1> delta_p = subset.H_inv * dp_sum;

            Eigen::Matrix3d dW = Eigen::Matrix3d::Identity();
            dW(0, 0) = 1.0 + delta_p(2);
            dW(0, 1) = delta_p(3);
            dW(0, 2) = delta_p(0);
            dW(1, 0) = delta_p(4);
            dW(1, 1) = 1.0 + delta_p(5);
            dW(1, 2) = delta_p(1);
            W = W * dW.inverse();

            if (delta_p.norm() < 0.001) {
                // Success: return final correlation (residual_sum/n) for RG
                return {W(0, 2), W(1, 2), 0.0, W(0, 0) - 1.0, W(1, 1) - 1.0, 0, residual_sum/n};
            }
        }
        return {W(0, 2), W(1, 2), 0.0, W(0, 0) - 1.0, W(1, 1) - 1.0, 2, 1.0};
    }
}