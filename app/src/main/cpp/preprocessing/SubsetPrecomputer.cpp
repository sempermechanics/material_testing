#include "SubsetPrecomputer.h"
#include <cmath>

namespace IndicVision {

    void SubsetPrecomputer::precompute_subset(SubsetData& data, const Image& ref_img, int_t cx, int_t cy, int_t dim) {
        int n = dim * dim;

        if (data.dim != dim) {
            data.dim = dim;
            data.x_offsets.resize(n);
            data.y_offsets.resize(n);
            data.ref_intensities.resize(n);
            data.norm_ref_intensities.resize(n); // NEW
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

        // 1. FAST INTEGER LOOKUPS (No Bicubic Math!)
        for (int i = 0; i < n; ++i) {
            int ix = cx + data.x_offsets[i];
            int iy = cy + data.y_offsets[i];

            // Direct 1D array access is nearly instant
            int img_idx = iy * ref_img.width + ix;

            data.ref_intensities[i] = ref_img.intensities[img_idx];
            sum += data.ref_intensities[i];

            data.gx_vec[i] = ref_img.grad_x[img_idx];
            data.gy_vec[i] = ref_img.grad_y[img_idx];
        }

        // 2. Compute Stats
        data.mean_intensity = sum / n;
        double sum_sq_diff = 0.0;
        for (int i = 0; i < n; ++i) {
            double diff = data.ref_intensities[i] - data.mean_intensity;
            sum_sq_diff += diff * diff;
        }
        data.std_dev = std::sqrt(sum_sq_diff / n);
        if (data.std_dev < 1e-5) data.std_dev = 1.0;

        // 3. PRE-CALCULATE NORMALIZED REFERENCE (Kills redundant ICGN math)
        for (int i = 0; i < n; ++i) {
            data.norm_ref_intensities[i] = (data.ref_intensities[i] - data.mean_intensity) / data.std_dev;
        }

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