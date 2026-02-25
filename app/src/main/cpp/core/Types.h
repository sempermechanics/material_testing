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
    // 🚀 THE BIG SHIFT: All core math is now 32-bit float
    using scalar_t = float;
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
        float correlation_score; // Changed to float
    };

    // Seed node for reliability-guided propagation
    struct SeedNode {
        EIGEN_MAKE_ALIGNED_OPERATOR_NEW

        int x_idx, y_idx;
        float u, v, ux, uy, vx, vy; // Changed to float
        float correlation_score;    // Changed to float

        SeedNode() : x_idx(0), y_idx(0), u(0.0f), v(0.0f), ux(0.0f), uy(0.0f), vx(0.0f), vy(0.0f), correlation_score(0.0f) {}

        SeedNode(int x, int y, float _u, float _v, float _ux, float _uy, float _vx, float _vy, float c)
                : x_idx(x), y_idx(y), u(_u), v(_v), ux(_ux), uy(_uy), vx(_vx), vy(_vy), correlation_score(c) {}

        bool operator<(const SeedNode& other) const {
            return correlation_score > other.correlation_score;
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

        // 🚀 Matrix memory footprint cut in half!
        Eigen::Matrix<float, 6, 6> H_inv;
        std::vector<Eigen::Matrix<float, 6, 1>> steepest_descent_images;

        bool is_initialized = false; // Flag to skip re-computation
    };
}
#endif