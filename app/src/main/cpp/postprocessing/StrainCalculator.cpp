#include "StrainCalculator.h"
#include <Eigen/Dense>
#include <cmath>
#include <android/log.h>
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "IndicVisionEngine", __VA_ARGS__)

namespace IndicVision {

    StrainField StrainCalculator::compute_vsg_strain(const DisplacementField& disp, int window_pixels) {
        StrainField strain;
        int total_pts = disp.width * disp.height;
        strain.exx.assign(total_pts, 0.0f);
        strain.eyy.assign(total_pts, 0.0f);
        strain.exy.assign(total_pts, 0.0f);

        float radius = window_pixels / 2.0f;
        float radius_sq = radius * radius;
        int grid_rad = std::ceil(radius / disp.step);

        for (int y = 0; y < disp.height; ++y) {
            for (int x = 0; x < disp.width; ++x) {
                int idx = y * disp.width + x;
                if (!disp.valid[idx]) continue;

                Eigen::Matrix3d AtA = Eigen::Matrix3d::Zero();
                Eigen::Vector3d AtU = Eigen::Vector3d::Zero();
                Eigen::Vector3d AtV = Eigen::Vector3d::Zero();
                int valid_pts = 0;

                for (int dy = -grid_rad; dy <= grid_rad; ++dy) {
                    for (int dx = -grid_rad; dx <= grid_rad; ++dx) {
                        int nx = x + dx;
                        int ny = y + dy;

                        if (nx < 0 || nx >= disp.width || ny < 0 || ny >= disp.height) continue;
                        int nidx = ny * disp.width + nx;
                        if (!disp.valid[nidx]) continue;

                        float phys_dx = dx * disp.step;
                        float phys_dy = dy * disp.step;

                        if ((phys_dx * phys_dx + phys_dy * phys_dy) <= radius_sq) {
                            // STRICT DICe PARITY: Nominal geometry ONLY. No centroid shifting!
                            double d_phys_dx = static_cast<double>(phys_dx);
                            double d_phys_dy = static_cast<double>(phys_dy);
                            double d_u = static_cast<double>(disp.u[nidx]);
                            double d_v = static_cast<double>(disp.v[nidx]);

                            Eigen::Vector3d a(1.0, d_phys_dx, d_phys_dy);

                            AtA += a * a.transpose();
                            AtU += a * d_u;
                            AtV += a * d_v;
                            valid_pts++;
                        }
                    }
                }
                // 🚀 DIAGNOSTIC 3: Check Strain Erosion Guard inputs
                if (x == 185 && y == 617) {
                    // Note: expected_pts is approx (2*grid_rad+1)^2. For a 15px window (rad=2), expected is ~25.
                    // We'll just log valid_pts to see if it's artificially full.
                    LOGD("DIAGNOSTIC 3: VSG at (185,617) has valid_pts = %d", valid_pts);
                }
                // 🚀 DIAGNOSTIC 2: Measure the Asymmetry of the VSG Window
                if (x == 185 && y == 617) {
                    float sum_x = 0.0f, sum_y = 0.0f;
                    for (int dy = -grid_rad; dy <= grid_rad; ++dy) {
                        for (int dx = -grid_rad; dx <= grid_rad; ++dx) {
                            int nx = x + dx; int ny = y + dy;
                            if (nx < 0 || nx >= disp.width || ny < 0 || ny >= disp.height) continue;
                            if (!disp.valid[ny * disp.width + nx]) continue;
                            if ((dx * disp.step * dx * disp.step + dy * disp.step * dy * disp.step) <= radius_sq) {
                                sum_x += dx * disp.step; sum_y += dy * disp.step;
                            }
                        }
                    }
                    float centroid_x = (valid_pts > 0) ? (sum_x / valid_pts) : 0.0f;
                    float centroid_y = (valid_pts > 0) ? (sum_y / valid_pts) : 0.0f;
                    LOGD("DIAGNOSTIC 2: VSG at (185,617). Valid Pts = %d. Centroid Shift = (%.2f, %.2f) pixels.", valid_pts, centroid_x, centroid_y);
                }
                // DICe LAYER 3 GUARD: Evaluate GECON / Reciprocal Condition Number
                if (valid_pts >= 3) {
                    Eigen::SelfAdjointEigenSolver<Eigen::Matrix3d> eig(AtA);
                    double lambda_min = eig.eigenvalues()(0);
                    double lambda_max = eig.eigenvalues()(2);
                    double rcond = (lambda_max > 0.0) ? (lambda_min / lambda_max) : 0.0;
                    if (x == 185 && y == 617) {
                        LOGD("DIAGNOSTIC 4: VSG at (185,617) Matrix rcond = %.2e", rcond);
                    }

                    // DICe EXACT THRESHOLD: Abort if condition number > 10^12
                    if (rcond < 1e-12) {
                        continue; // Safely abort and leave strain at 0.0f
                    }

                    // DICe ROBUST SOLVER: LU Inverse (GETRI Equivalent)
                    Eigen::Matrix3d AtA_inv = AtA.inverse();
                    Eigen::Vector3d Cu = AtA_inv * AtU;
                    Eigen::Vector3d Cv = AtA_inv * AtV;

                    double dudx = Cu(1);
                    double dudy = Cu(2);
                    double dvdx = Cv(1);
                    double dvdy = Cv(2);

                    strain.exx[idx] = static_cast<float>(0.5 * (2.0 * dudx + dudx * dudx + dvdx * dvdx));
                    strain.eyy[idx] = static_cast<float>(0.5 * (2.0 * dvdy + dudy * dudy + dvdy * dvdy));
                    strain.exy[idx] = static_cast<float>(0.5 * (dudy + dvdx + dudx * dudy + dvdx * dvdy));
                }
            }
        }
        return strain;
    }

    StrainField StrainCalculator::compute_nlvc_strain(const DisplacementField& disp, int horizon_pixels) {
        StrainField strain;
        int total_pts = disp.width * disp.height;
        strain.exx.assign(total_pts, 0.0f);
        strain.eyy.assign(total_pts, 0.0f);
        strain.exy.assign(total_pts, 0.0f);

        const double PI = 3.14159265358979323846;

        // 1. Setup NLVC Horizon and Kernel Parameters in 64-bit
        double h = horizon_pixels / 2.0;
        double s = h / 3.0;
        double s_sq = s * s;
        double s_sq_2 = 2.0 * s_sq;

        double norm_factor = (s <= 0.0) ? 0.0 : 1.0 / (PI * s_sq_2);
        double patch_area = static_cast<double>(disp.step * disp.step);
        int grid_rad = std::ceil(horizon_pixels / 2.0f / disp.step);

        for (int y = 0; y < disp.height; ++y) {
            for (int x = 0; x < disp.width; ++x) {
                int idx = y * disp.width + x;
                if (!disp.valid[idx]) continue;

                // Promote math to 64-bit
                double dudx = 0.0, dudy = 0.0, dvdx = 0.0, dvdy = 0.0;
                double sum_int_x = 0.0, sum_int_y = 0.0;
                int valid_pts = 0;

                // 2. The Spatial Integration Loop
                for (int dy = -grid_rad; dy <= grid_rad; ++dy) {
                    for (int dx = -grid_rad; dx <= grid_rad; ++dx) {
                        int nx = x + dx;
                        int ny = y + dy;

                        if (nx < 0 || nx >= disp.width || ny < 0 || ny >= disp.height) continue;
                        int nidx = ny * disp.width + nx;
                        if (!disp.valid[nidx]) continue;

                        double phys_dx = static_cast<double>(dx * disp.step);
                        double phys_dy = static_cast<double>(dy * disp.step);
                        double r_sq = phys_dx * phys_dx + phys_dy * phys_dy;

                        if (r_sq <= h * h) {
                            double exp_val = std::exp(-(r_sq / s_sq_2));

                            double kx = norm_factor * (-phys_dx / s_sq) * exp_val;
                            double ky = norm_factor * (-phys_dy / s_sq) * exp_val;

                            double ux = static_cast<double>(disp.u[nidx]);
                            double uy = static_cast<double>(disp.v[nidx]);

                            sum_int_x += kx * patch_area;
                            sum_int_y += ky * patch_area;

                            dudx -= ux * kx * patch_area;
                            dudy -= ux * ky * patch_area;
                            dvdx -= uy * kx * patch_area;
                            dvdy -= uy * ky * patch_area;

                            valid_pts++;
                        }
                    }
                }

                // Tighten integration check using 64-bit threshold
                if (valid_pts >= 3 && std::abs(sum_int_x) <= 0.01 && std::abs(sum_int_y) <= 0.01) {
                    strain.exx[idx] = static_cast<float>(0.5 * (2.0 * dudx + dudx * dudx + dvdx * dvdx));
                    strain.eyy[idx] = static_cast<float>(0.5 * (2.0 * dvdy + dudy * dudy + dvdy * dvdy));
                    strain.exy[idx] = static_cast<float>(0.5 * (dudy + dvdx + dudx * dudy + dvdx * dvdy));
                }
            }
        }
        return strain;
    }
}