#ifndef INDICVISION_CORE_H
#define INDICVISION_CORE_H

#include <vector>
#include <memory>
#include <cmath>
#include <cstdint>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <Eigen/Dense>
#include <queue>

#define LOG_TAG "IndicVisionNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace IndicVision {

    using scalar_t = double;
    using int_t = int;

    // --- ENUMS FOR SELECTION ---
    enum InterpolatorType {
        INTERP_BICUBIC = 0, // Fast, Standard
        INTERP_LANCZOS = 1, // High Accuracy (Convolution)
        INTERP_BSPLINE = 2  // Ultra Accuracy (Recursive Filter + Spline)
    };
    enum InitializationMode {
        INIT_GRID_SEARCH = 0,  // Current +/- 15px scan
        INIT_FEATURE_MATCH = 1 // New SIFT/AKAZE matching
    };

    enum GradientType {
        GRAD_CENTRAL_DIFF = 0, // Standard Finite Difference
        GRAD_BSPLINE_ANALYTIC = 1 // Exact derivative of the Spline surface
    };

    struct AnalysisResult {
        scalar_t u, v, theta, ux, vy;
        int status;
        double correlation_score; // Added for Reliability Guided
    };

    // --- RELIABILITY GUIDED NODE ---
    struct SeedNode {
        int x_idx; // Index in the line profile (0, 1, 2...)
        double u, v;
        double correlation; // Lower is better (Residual)

        // Priority Queue sorts by highest correlation (lowest residual)
        // We want the *smallest* residual to be at the TOP.
        // std::priority_queue uses std::less by default (max-heap).
        // So we need operator< to return TRUE if "this" is "worse" than "other"
        bool operator<(const SeedNode& other) const {
            return correlation > other.correlation;
        }
    };

    class Image {
    public:
        int_t width, height;
        std::vector<scalar_t> intensities;

        // Direct Gradients (for Bicubic/Lanczos)
        std::vector<scalar_t> grad_x;
        std::vector<scalar_t> grad_y;

        // B-Spline Coefficients (Computed once per image)
        std::vector<scalar_t> bspline_coeffs;

        InterpolatorType interp_type;
        GradientType grad_type;

        Image(int_t w, int_t h, const uint8_t* raw_pixels);

        void set_settings(InterpolatorType i_type, GradientType g_type);

        // Pre-compute based on selection
        void prepare_data();

        // Dual-mode accessors
        scalar_t interpolate(scalar_t x, scalar_t y) const;
        scalar_t gradient_x(scalar_t x, scalar_t y) const;
        scalar_t gradient_y(scalar_t x, scalar_t y) const;

    private:
        void compute_gradients_finite_diff();
        void compute_bspline_coeffs();

        // Spline kernels
        scalar_t bspline_val(scalar_t x, scalar_t y) const;
        scalar_t bspline_grad_x(scalar_t x, scalar_t y) const;
        scalar_t bspline_grad_y(scalar_t x, scalar_t y) const;
    };

    class Subset {
    public:
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW
        int_t cx, cy, dim;
        std::vector<int_t> x_offsets;
        std::vector<int_t> y_offsets;

        // Reference data (Cached)
        std::vector<scalar_t> ref_intensities;
        std::vector<scalar_t> ref_grad_x;
        std::vector<scalar_t> ref_grad_y;
        scalar_t mean_intensity;

        Eigen::Matrix<double, 6, 6> H_inv;
        std::vector<Eigen::Matrix<double, 6, 1>> steepest_descent_images;

        Subset(int_t centroid_x, int_t centroid_y, int_t subset_size);
        void initialize(const Image& ref_img);
    };

    class Engine {
    public:
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW
        Engine();

        void set_reference(const Image& ref_img, int_t roi_x, int_t roi_y, int_t subset_size);

        // Updated to accept Init Mode
        AnalysisResult calculate_deformation(const Image& def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode);

    private:
        std::unique_ptr<Subset> active_subset;

        // Existing
        void estimate_initial_guess(const Subset& subset, const Image& def_img, scalar_t& best_u, scalar_t& best_v);

        // NEW: Feature Matching
        void estimate_initial_guess_features(const Image& ref_img, const Image& def_img, int cx, int cy, scalar_t& best_u, scalar_t& best_v);

        AnalysisResult solve_icgn(const Subset& subset, const Image& def_img, double init_u, double init_v);
    };
}
#endif