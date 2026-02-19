#ifndef INDICVISION_CORE_H
#define INDICVISION_CORE_H

#include <vector>
#include <memory>
#include <cmath>
#include <cstdint>
#include <android/log.h>
#include <Eigen/Dense>

#define LOG_TAG "IndicVisionNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace IndicVision {

    using scalar_t = double;
    using int_t = int;

    // We only need auto search vs no search now
    enum InitializationMode { INIT_AUTO_SEARCH = 0, INIT_NO_SEARCH = 1 };

    struct AnalysisResult {
        scalar_t u, v;       // Translation
        scalar_t ux, uy;     // Strain components
        scalar_t vx, vy;
        int status;          // 0 = Success, 1 = Fail
        double correlation_score;
    };

    struct SeedNode {
        int x_idx, y_idx;
        double u, v, ux, uy, vx, vy;
        double correlation;

        SeedNode() : x_idx(0), y_idx(0), u(0), v(0), ux(0), uy(0), vx(0), vy(0), correlation(0) {}
        SeedNode(int x, int y, double c) : x_idx(x), y_idx(y), u(0), v(0), ux(0), uy(0), vx(0), vy(0), correlation(c) {}
        SeedNode(int x, int y, double _u, double _v, double _ux, double _uy, double _vx, double _vy, double c)
                : x_idx(x), y_idx(y), u(_u), v(_v), ux(_ux), uy(_uy), vx(_vx), vy(_vy), correlation(c) {}

        bool operator<(const SeedNode& other) const {
            return correlation > other.correlation; // Min-Heap
        }
    };

    class Image {
    public:
        int_t width, height;
        std::vector<scalar_t> intensities;
        std::vector<scalar_t> grad_x;
        std::vector<scalar_t> grad_y;

        Image(int_t w, int_t h, const uint8_t* raw_pixels);

        // Only one preparation step now: Finite Difference Gradients
        void prepare_data();

        // Fast, branchless Keys 4th Order Bicubic
        inline scalar_t interpolate_bicubic(scalar_t x, scalar_t y) const;
        inline scalar_t gradient_x(scalar_t x, scalar_t y) const;
        inline scalar_t gradient_y(scalar_t x, scalar_t y) const;
    };

    class Subset {
    public:
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW
        int_t cx, cy, dim;
        std::vector<int_t> x_offsets;
        std::vector<int_t> y_offsets;

        std::vector<scalar_t> ref_intensities;
        scalar_t mean_intensity;
        scalar_t std_dev; // Precalculated to save time in evaluate_znssd

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
        AnalysisResult calculate_deformation(const Image& def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode);

    private:
        std::unique_ptr<Subset> active_subset;

        void estimate_initial_guess(const Subset& subset, const Image& def_img, scalar_t& best_u, scalar_t& best_v);
        AnalysisResult solve_icgn(const Subset& subset, const Image& def_img, double init_u, double init_v);
        AnalysisResult solve_simplex(const Subset& subset, const Image& def_img, AnalysisResult start);
        double evaluate_znssd(const Subset& subset, const Image& def_img, double u, double v, double ux, double uy, double vx, double vy, std::vector<double>& buffer);    };
}
#endif