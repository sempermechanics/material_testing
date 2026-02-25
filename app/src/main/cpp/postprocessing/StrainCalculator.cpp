#include "StrainCalculator.h"
#include <Eigen/Dense>
#include <cmath>

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

                // 🚀 Changed to 32-bit float vectors
                Eigen::Matrix3f AtA = Eigen::Matrix3f::Zero();
                Eigen::Vector3f AtU = Eigen::Vector3f::Zero();
                Eigen::Vector3f AtV = Eigen::Vector3f::Zero();
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
                            Eigen::Vector3f a(1.0f, phys_dx, phys_dy);
                            AtA += a * a.transpose();
                            AtU += a * disp.u[nidx];
                            AtV += a * disp.v[nidx];
                            valid_pts++;
                        }
                    }
                }

                // 🚀 Float precision threshold (1e-6f instead of 1e-9)
                if (valid_pts >= 3 && std::abs(AtA.determinant()) > 1e-6f) {
                    Eigen::Vector3f Cu = AtA.ldlt().solve(AtU);
                    Eigen::Vector3f Cv = AtA.ldlt().solve(AtV);

                    float dudx = Cu(1);
                    float dudy = Cu(2);
                    float dvdx = Cv(1);
                    float dvdy = Cv(2);

                    strain.exx[idx] = 0.5f * (2.0f * dudx + dudx * dudx + dvdx * dvdx);
                    strain.eyy[idx] = 0.5f * (2.0f * dvdy + dudy * dudy + dvdy * dvdy);
                    strain.exy[idx] = 0.5f * (dudy + dvdx + dudx * dudy + dvdx * dvdy);
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

        const float PI = 3.14159265358979323846f;

        // 1. Setup NLVC Horizon and Kernel Parameters
        float h = horizon_pixels / 2.0f;
        float s = h / 3.0f;
        float s_sq = s * s;
        float s_sq_2 = 2.0f * s_sq;

        float norm_factor = (s <= 0.0f) ? 0.0f : 1.0f / (PI * s_sq_2);
        float patch_area = disp.step * disp.step;
        int grid_rad = std::ceil(h / disp.step);

        for (int y = 0; y < disp.height; ++y) {
            for (int x = 0; x < disp.width; ++x) {
                int idx = y * disp.width + x;
                if (!disp.valid[idx]) continue;

                float dudx = 0.0f, dudy = 0.0f, dvdx = 0.0f, dvdy = 0.0f;
                float sum_int_x = 0.0f, sum_int_y = 0.0f;
                int valid_pts = 0;

                // 2. The Spatial Integration Loop
                for (int dy = -grid_rad; dy <= grid_rad; ++dy) {
                    for (int dx = -grid_rad; dx <= grid_rad; ++dx) {
                        int nx = x + dx;
                        int ny = y + dy;

                        // Check bounds and validity
                        if (nx < 0 || nx >= disp.width || ny < 0 || ny >= disp.height) continue;
                        int nidx = ny * disp.width + nx;
                        if (!disp.valid[nidx]) continue;

                        float phys_dx = dx * disp.step;
                        float phys_dy = dy * disp.step;
                        float r_sq = phys_dx * phys_dx + phys_dy * phys_dy;

                        if (r_sq <= h * h) {
                            float exp_val = std::exp(-(r_sq / s_sq_2));

                            float kx = norm_factor * (-phys_dx / s_sq) * exp_val;
                            float ky = norm_factor * (-phys_dy / s_sq) * exp_val;

                            float ux = disp.u[nidx];
                            float uy = disp.v[nidx];

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

                if (valid_pts >= 3 && std::abs(sum_int_x) <= 0.01f && std::abs(sum_int_y) <= 0.01f) {
                    strain.exx[idx] = 0.5f * (2.0f * dudx + dudx * dudx + dvdx * dvdx);
                    strain.eyy[idx] = 0.5f * (2.0f * dvdy + dudy * dudy + dvdy * dvdy);
                    strain.exy[idx] = 0.5f * (dudy + dvdx + dudx * dudy + dvdx * dvdy);
                }
            }
        }
        return strain;
    }
}