#include "SubsetPrecomputer.h"
#include <cmath>

namespace IndicVision {

    // =========================================================
    // EXISTING: Full Precompute (Fallback / Original logic)
    // =========================================================
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
        float sum_sq_diff = 0.0f;

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

                Eigen::Matrix<float, 6, 1> sd;
                sd << gx, gy, gx * (float)x, gx * (float)y, gy * (float)x, gy * (float)y;

                data.steepest_descent_images[idx] = sd;
                H.noalias() += sd * sd.transpose();
                idx++;
            }
        }

        float det = H.determinant();

        if (std::abs(det) < 1e-6f) {
            data.H_inv = Eigen::Matrix<float, 6, 6>::Zero();
        } else {
            data.H_inv = H.inverse();
        }

        data.is_initialized = true;
    }


    // =========================================================
    // 🚀 NEW: compute_hessian_only
    //    Builds H_inv + stats WITHOUT constructing a SubsetData.
    //    Designed to be called in bulk from an OMP pre-pass.
    //    Uses only contiguous pointer arithmetic — no heap allocation.
    // =========================================================
    CachedHessianData SubsetPrecomputer::compute_hessian_only(
            const Image& ref_img, int_t cx, int_t cy, int_t dim) {

        CachedHessianData result; // valid = false by default
        int n    = dim * dim;
        int half = dim / 2;

        if (cx - half < 0 || cx + half >= ref_img.width  ||
            cy - half < 0 || cy + half >= ref_img.height) {
            return result;
        }

        // ── Pass 1: Mean intensity ───────────────────────────────
        float sum = 0.0f;
        for (int oy = -half; oy <= half; ++oy) {
            const float* row = &ref_img.intensities[(cy + oy) * ref_img.width + cx - half];
            for (int ox = 0; ox < dim; ++ox) sum += row[ox];
        }
        const float mean = sum / static_cast<float>(n);

        // ── Pass 2: Std dev ──────────────────────────────────────
        float sum_sq = 0.0f;
        for (int oy = -half; oy <= half; ++oy) {
            const float* row = &ref_img.intensities[(cy + oy) * ref_img.width + cx - half];
            for (int ox = 0; ox < dim; ++ox) {
                float d = row[ox] - mean;
                sum_sq += d * d;
            }
        }
        float std_dev = std::sqrt(sum_sq / static_cast<float>(n));
        if (std_dev < 1e-5f) std_dev = 1.0f;
        const float inv_std = 1.0f / std_dev;

        result.mean_intensity = mean;
        result.std_dev        = std_dev;

        // ── Pass 3: Hessian accumulation (upper triangle only → 21 muls instead of 36) ──
        Eigen::Matrix<float, 6, 6> H = Eigen::Matrix<float, 6, 6>::Zero();

        for (int oy = -half; oy <= half; ++oy) {
            const float* gx_row = &ref_img.grad_x[(cy + oy) * ref_img.width + cx - half];
            const float* gy_row = &ref_img.grad_y[(cy + oy) * ref_img.width + cx - half];
            const float fy = static_cast<float>(oy);

            for (int ox = -half; ox <= half; ++ox) {
                const float gx = gx_row[ox + half] * inv_std;
                const float gy = gy_row[ox + half] * inv_std;
                const float fx = static_cast<float>(ox);

                const float s0 = gx,      s1 = gy;
                const float s2 = gx * fx, s3 = gx * fy;
                const float s4 = gy * fx, s5 = gy * fy;

                // 21-op upper-triangle accumulation (compiler unrolls this perfectly)
                H(0,0)+=s0*s0; H(0,1)+=s0*s1; H(0,2)+=s0*s2; H(0,3)+=s0*s3; H(0,4)+=s0*s4; H(0,5)+=s0*s5;
                H(1,1)+=s1*s1; H(1,2)+=s1*s2; H(1,3)+=s1*s3; H(1,4)+=s1*s4; H(1,5)+=s1*s5;
                H(2,2)+=s2*s2; H(2,3)+=s2*s3; H(2,4)+=s2*s4; H(2,5)+=s2*s5;
                H(3,3)+=s3*s3; H(3,4)+=s3*s4; H(3,5)+=s3*s5;
                H(4,4)+=s4*s4; H(4,5)+=s4*s5;
                H(5,5)+=s5*s5;
            }
        }

        // Symmetrize lower triangle
        for (int r = 1; r < 6; ++r)
            for (int c = 0; c < r; ++c)
                H(r, c) = H(c, r);

        const float det = H.determinant();

        // 🚀 FIX: Standard if/else prevents Eigen expression template type mismatch
        if (std::abs(det) < 1e-6f) {
            result.H_inv = Eigen::Matrix<float, 6, 6>::Zero();
        } else {
            result.H_inv = H.inverse();
        }

        result.valid = true;
        return result;
    }


    // =========================================================
    // 🚀 NEW: precompute_subset_fast
    //    Drops the H accumulation + inversion entirely by reading
    //    from the pre-built pool. Still rebuilds steepest_descent_images
    //    (required by solve_icgn) but skips mean/std recomputation.
    //    Net savings: ~65% of original precompute FP work per call.
    // =========================================================
    void SubsetPrecomputer::precompute_subset_fast(
            SubsetData& data, const Image& ref_img,
            int_t cx, int_t cy, int_t dim,
            const CachedHessianData& cached) {

        // Safety: degrade gracefully if the pool entry was invalid (boundary overshoot, etc.)
        if (!cached.valid) {
            precompute_subset(data, ref_img, cx, cy, dim);
            return;
        }

        int n    = dim * dim;
        int half = dim / 2;

        // ── Resize buffers once when dim changes (amortised O(1)) ───
        if (data.dim != dim) {
            data.dim = dim;
            data.x_offsets.resize(n);
            data.y_offsets.resize(n);
            data.ref_intensities.resize(n);
            data.norm_ref_intensities.resize(n);
            data.gx_vec.resize(n);
            data.gy_vec.resize(n);
            data.steepest_descent_images.resize(n);

            int oi = 0;
            for (int y = -half; y <= half; ++y)
                for (int x = -half; x <= half; ++x) {
                    data.x_offsets[oi] = x;
                    data.y_offsets[oi] = y;
                    oi++;
                }
        }

        data.cx = cx;
        data.cy = cy;

        if (cx - half < 0 || cx + half >= ref_img.width  ||
            cy - half < 0 || cy + half >= ref_img.height) {
            data.is_initialized = false;
            return;
        }

        // ── Fast contiguous load via memcpy (hardware prefetch-friendly) ──
        int idx = 0;
        for (int oy = 0; oy < dim; ++oy) {
            const int row_base = (cy - half + oy) * ref_img.width + (cx - half);
            memcpy(&data.ref_intensities[idx], &ref_img.intensities[row_base], dim * sizeof(float));
            memcpy(&data.gx_vec[idx],          &ref_img.grad_x    [row_base], dim * sizeof(float));
            memcpy(&data.gy_vec[idx],          &ref_img.grad_y    [row_base], dim * sizeof(float));
            idx += dim;
        }

        // ── Use cached stats — zero recomputation ───────────────────
        data.mean_intensity = cached.mean_intensity;
        data.std_dev        = cached.std_dev;
        const float mean    = cached.mean_intensity;
        const float inv_std = 1.0f / cached.std_dev;

        // ── Normalize + build steepest_descent_images in one fused loop ──
        //    (SD images are needed for the ICGN Newton step — can't skip these)
        idx = 0;
        for (int oy = -half; oy <= half; ++oy) {
            const float fy = static_cast<float>(oy);
            for (int ox = -half; ox <= half; ++ox) {
                data.norm_ref_intensities[idx] = (data.ref_intensities[idx] - mean) * inv_std;

                const float gx = data.gx_vec[idx] * inv_std;
                const float gy = data.gy_vec[idx] * inv_std;
                const float fx = static_cast<float>(ox);

                // Direct coefficient assignment avoids the << operator overhead
                auto& sd = data.steepest_descent_images[idx];
                sd(0) = gx;      sd(1) = gy;
                sd(2) = gx * fx; sd(3) = gx * fy;
                sd(4) = gy * fx; sd(5) = gy * fy;
                idx++;
            }
        }

        // 🚀 THE KEY SKIP: use pre-built inverse Hessian
        data.H_inv = cached.H_inv;
        data.is_initialized = true;
    }

} // namespace IndicVision