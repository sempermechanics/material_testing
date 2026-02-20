#include "SubsetPrecomputer.h"
#include <cmath>

namespace IndicVision {

    void SubsetPrecomputer::precompute_subset(SubsetData& data, const Image& ref_img, int_t cx, int_t cy, int_t dim) {
        int n = dim * dim;

        // 1. ALLOCATE ONLY ONCE! If the size is the same, we reuse the existing memory.
        if (data.dim != dim) {
            data.dim = dim;
            data.x_offsets.resize(n);
            data.y_offsets.resize(n);
            data.ref_intensities.resize(n);
            data.gx_vec.resize(n);
            data.gy_vec.resize(n);
            data.steepest_descent_images.resize(n);

            int half = dim / 2;
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
        double sum = 0.0;

        // 2. Sample Reference Intensities and Gradients directly into existing memory
        for (int i = 0; i < n; ++i) {
            double px = cx + data.x_offsets[i];
            double py = cy + data.y_offsets[i];

            data.ref_intensities[i] = ref_img.interpolate_bicubic(px, py);
            sum += data.ref_intensities[i];

            data.gx_vec[i] = ref_img.gradient_x(px, py);
            data.gy_vec[i] = ref_img.gradient_y(px, py);
        }

        // 3. Compute Stats
        data.mean_intensity = sum / n;
        double sum_sq_diff = 0.0;
        for (int i = 0; i < n; ++i) {
            double diff = data.ref_intensities[i] - data.mean_intensity;
            sum_sq_diff += diff * diff;
        }
        data.std_dev = std::sqrt(sum_sq_diff / n);
        if (data.std_dev < 1e-5) data.std_dev = 1.0;

        // 4. Build Hessian
        Eigen::Matrix<double, 6, 6> H = Eigen::Matrix<double, 6, 6>::Zero();

        for (int i = 0; i < n; ++i) {
            double x = data.x_offsets[i];
            double y = data.y_offsets[i];
            double gx = data.gx_vec[i] / data.std_dev;
            double gy = data.gy_vec[i] / data.std_dev;

            Eigen::Matrix<double, 6, 1> sd;
            sd << gx, gy, gx * x, gx * y, gy * x, gy * y;

            data.steepest_descent_images[i] = sd;
            H += sd * sd.transpose();
        }

        double det = H.determinant();
        if (std::abs(det) < 1e-12) {
            data.H_inv = Eigen::Matrix<double, 6, 6>::Zero();
        } else {
            data.H_inv = H.inverse();
        }

        data.is_initialized = true;
    }
}