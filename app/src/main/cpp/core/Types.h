#ifndef INDICVISION_TYPES_H
#define INDICVISION_TYPES_H

#include <vector>
#include <Eigen/Dense>
#include <android/log.h>
// Make sure your LOGD macro has a tag defined if it doesn't already!
#ifndef LOG_TAG
#define LOG_TAG "IndicVisionDIC"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
namespace IndicVision {
    using scalar_t = double;
    using int_t = int;

    // Initialization modes
    enum InitializationMode {
        INIT_AUTO_SEARCH = 0,
        INIT_NO_SEARCH = 1
    };

    // DIC analysis result (6-DOF + status)
    struct AnalysisResult {
        scalar_t u, v;
        scalar_t ux, uy;
        scalar_t vx, vy;
        int status;
        double correlation_score;
    };

    // Seed node for reliability-guided propagation
    struct SeedNode {
        int x_idx, y_idx;
        double u, v, ux, uy, vx, vy;
        double correlation;

        SeedNode() : x_idx(0), y_idx(0), u(0), v(0), ux(0), uy(0), vx(0), vy(0), correlation(0) {}

        SeedNode(int x, int y, double _u, double _v, double _ux, double _uy, double _vx, double _vy, double c)
                : x_idx(x), y_idx(y), u(_u), v(_v), ux(_ux), uy(_uy), vx(_vx), vy(_vy), correlation(c) {}

        bool operator<(const SeedNode& other) const {
            return correlation > other.correlation; // Min-heap behavior
        }
    };

    // Replaces the old "Subset" class. Now just a pure data struct.
    struct SubsetData {
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW
        int_t cx, cy, dim;
        std::vector<int_t> x_offsets, y_offsets;
        std::vector<scalar_t> ref_intensities;
        std::vector<scalar_t> norm_ref_intensities;
        std::vector<scalar_t> gx_vec, gy_vec; // Pre-sampled gradients
        scalar_t mean_intensity;
        scalar_t std_dev;
        Eigen::Matrix<double, 6, 6> H_inv;
        std::vector<Eigen::Matrix<double, 6, 1>> steepest_descent_images;
        bool is_initialized = false; // Flag to skip re-computation
    };
}
#endif