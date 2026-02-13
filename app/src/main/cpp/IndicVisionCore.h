#ifndef INDICVISION_CORE_H
#define INDICVISION_CORE_H

#include <vector>
#include <memory>
#include <cmath>
#include <cstdint>
#include <android/log.h>
#include <opencv2/opencv.hpp>
#include <Eigen/Dense>

#define LOG_TAG "IndicVisionNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace IndicVision {

    using scalar_t = double;
    using int_t = int;

    // 1. Define Interpolator Types
    enum InterpolatorType {
        INTERP_BICUBIC = 0, // Standard (Fast)
        INTERP_QUINTIC = 1  // High Accuracy (6-tap)
    };

    struct AnalysisResult {
        scalar_t u, v, theta, ux, vy;
        int status;
    };

    class Image {
    public:
        int_t width, height;
        std::vector<scalar_t> intensities;
        std::vector<scalar_t> grad_x;
        std::vector<scalar_t> grad_y;

        // Store the chosen method
        InterpolatorType interp_type;

        Image(int_t w, int_t h, const uint8_t* raw_pixels);
        void compute_gradients();

        // Set the method
        void set_interpolator(InterpolatorType type);

        scalar_t interpolate(scalar_t x, scalar_t y) const;
    };

    class Subset {
    public:
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW
        int_t cx, cy, dim;
        std::vector<int_t> x_offsets;
        std::vector<int_t> y_offsets;
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

        // Added interpolatorType to set_reference signature
        void set_reference(const Image& ref_img, int_t roi_x, int_t roi_y, int_t subset_size);

        AnalysisResult calculate_deformation(const Image& def_img, scalar_t guess_u, scalar_t guess_v);

    private:
        std::unique_ptr<Subset> active_subset;
        void estimate_initial_guess(const Subset& subset, const Image& def_img, scalar_t& best_u, scalar_t& best_v);
        AnalysisResult solve_icgn(const Subset& subset, const Image& def_img, double init_u, double init_v);
    };
}
#endif