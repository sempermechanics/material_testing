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
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace IndicVision {

    using scalar_t = double;
    using int_t = int;

    enum InterpolatorType { INTERP_BICUBIC = 0, INTERP_LANCZOS = 1, INTERP_BSPLINE = 2 };
    enum InitializationMode { INIT_GRID_SEARCH = 0, INIT_FEATURE_MATCH = 1 };
    enum GradientType { GRAD_CENTRAL_DIFF = 0, GRAD_BSPLINE_ANALYTIC = 1 };

    // --- PURE MATRIX RESULT STRUCT ---
    // Stores the 6 affine matrix elements directly.
    struct AnalysisResult {
        scalar_t u, v;       // Translation (W02, W12)
        scalar_t ux, uy;     // Row 1: (1+ux), uy
        scalar_t vx, vy;     // Row 2: vx, (1+vy)

        int status;          // 0 = Success, 1 = Fail
        double correlation_score; // ZNSSD (0.0 is perfect)
    };

    struct SeedNode {
        int x_idx, y_idx;
        double u, v, ux, uy, vx, vy;
        double correlation;

        bool operator<(const SeedNode& other) const {
            return correlation > other.correlation;
        }
    };

    class Image {
    public:
        int_t width, height;
        std::vector<scalar_t> intensities;
        std::vector<scalar_t> grad_x;
        std::vector<scalar_t> grad_y;
        std::vector<scalar_t> bspline_coeffs;
        InterpolatorType interp_type;
        GradientType grad_type;

        Image(int_t w, int_t h, const uint8_t* raw_pixels);
        void set_settings(InterpolatorType i_type, GradientType g_type);
        void prepare_data();
        scalar_t interpolate(scalar_t x, scalar_t y) const;
        scalar_t gradient_x(scalar_t x, scalar_t y) const;
        scalar_t gradient_y(scalar_t x, scalar_t y) const;

    private:
        void compute_gradients_finite_diff();
        void compute_bspline_coeffs();
        scalar_t bspline_val(scalar_t x, scalar_t y) const;
        scalar_t bspline_grad_x(scalar_t x, scalar_t y) const;
        scalar_t bspline_grad_y(scalar_t x, scalar_t y) const;
    };

    class Subset {
    public:
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW
        int_t cx, cy, dim;
        std::vector<int_t> x_offsets; // Local coordinates relative to center
        std::vector<int_t> y_offsets;

        // Reference data (Cached)
        std::vector<scalar_t> ref_intensities;
        scalar_t mean_intensity;

        // Pre-computed Hessian Inverse for ICGN
        Eigen::Matrix<double, 6, 6> H_inv;
        // Pre-computed Steepest Descent Images
        std::vector<Eigen::Matrix<double, 6, 1>> steepest_descent_images;

        Subset(int_t centroid_x, int_t centroid_y, int_t subset_size);
        void initialize(const Image& ref_img);
    };

    class Engine {
    public:
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW
        Engine();
        void set_reference(const Image& ref_img, int_t roi_x, int_t roi_y, int_t subset_size);
        AnalysisResult calculate_deformation(const Image& def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode);

    private:
        std::unique_ptr<Subset> active_subset;

        void estimate_initial_guess(const Subset& subset, const Image& def_img, scalar_t& best_u, scalar_t& best_v);
        void estimate_initial_guess_features(const Image& ref_img, const Image& def_img, int cx, int cy, scalar_t& best_u, scalar_t& best_v);

        // Core Solvers (Matrix Based)
        AnalysisResult solve_icgn(const Subset& subset, const Image& def_img, double init_u, double init_v);
        AnalysisResult solve_simplex(const Subset& subset, const Image& def_img, AnalysisResult start);

        // Pure Matrix Cost Function
        double evaluate_znssd(const Subset& subset, const Image& def_img,
                              double u, double v, double ux, double uy, double vx, double vy);
    };
}
#endif