#include "SubsetPrecomputer.h"
#include <cmath>

namespace IndicVision {

    void SubsetPrecomputer::precompute_subset(SubsetData& data, const Image& ref_img, int_t cx, int_t cy, int_t dim) {
        int n = dim * dim;
        int half = dim / 2;

        if (data.dim != dim) {
            data.dim = dim;
            data.x_offsets.resize(n);
            data.y_offsets.resize(n);
            data.ref_intensities.resize(n);
            data.norm_ref_intensities.resize(n);
            data.gx_vec.resize(n);
            data.gy_vec.resize(n);
            data.steepest_descent_images.resize(n);

            int idx = 0;
            for (int y = -half; y <= half; ++y) {
                for (int x = -half; x <= half; ++x) {
                    data.x_offsets[idx] = x;
                    data.y_offsets[idx] = y;
                    idx++;
                }
            }
        }

        data.cx = cx;
        data.cy = cy;

        if (cx - half < 0 || cx + half >= ref_img.width ||
            cy - half < 0 || cy + half >= ref_img.height) {
            data.is_initialized = false;
            return;
        }

        // 🚀 Float conversion
        float sum = 0.0f;
        int idx = 0;

        // ==========================================
        // 🚀 CONTIGUOUS MEMORY SCAN (Hardware Prefetching)
        // ==========================================
        int start_y = cy - half;
        int start_x = cx - half;

        for (int y = 0; y < dim; ++y) {
            const scalar_t* int_row = &ref_img.intensities[(start_y + y) * ref_img.width + start_x];
            const scalar_t* gx_row = &ref_img.grad_x[(start_y + y) * ref_img.width + start_x];
            const scalar_t* gy_row = &ref_img.grad_y[(start_y + y) * ref_img.width + start_x];

            for (int x = 0; x < dim; ++x) {
                data.ref_intensities[idx] = int_row[x];
                data.gx_vec[idx] = gx_row[x];
                data.gy_vec[idx] = gy_row[x];
                sum += int_row[x];
                idx++;
            }
        }

        data.mean_intensity = sum / n;
        float sum_sq_diff = 0.0f; // 🚀 Float conversion

        for (int i = 0; i < n; ++i) {
            float diff = data.ref_intensities[i] - data.mean_intensity;
            sum_sq_diff += diff * diff;
        }
        data.std_dev = std::sqrt(sum_sq_diff / n);
        if (data.std_dev < 1e-5f) data.std_dev = 1.0f;

        for (int i = 0; i < n; ++i) {
            data.norm_ref_intensities[i] = (data.ref_intensities[i] - data.mean_intensity) / data.std_dev;
        }

        // 🚀 Convert to Matrix<float>
        Eigen::Matrix<float, 6, 6> H = Eigen::Matrix<float, 6, 6>::Zero();
        idx = 0;

        // Exact Hessian Computation using direct relative coordinates
        for (int y = -half; y <= half; ++y) {
            for (int x = -half; x <= half; ++x) {
                float gx = data.gx_vec[idx] / data.std_dev;
                float gy = data.gy_vec[idx] / data.std_dev;

                // 🚀 Convert to Matrix<float>
                Eigen::Matrix<float, 6, 1> sd;
                // Cast x and y to float to prevent implicit double promotion
                sd << gx, gy, gx * (float)x, gx * (float)y, gy * (float)x, gy * (float)y;

                data.steepest_descent_images[idx] = sd;
                H.noalias() += sd * sd.transpose();
                idx++;
            }
        }

        float det = H.determinant();

        // 🚀 Relaxed threshold for float precision (1e-6 instead of 1e-12)
        if (std::abs(det) < 1e-6f) {
            data.H_inv = Eigen::Matrix<float, 6, 6>::Zero();
        } else {
            data.H_inv = H.inverse();
        }

        data.is_initialized = true;
    }
}