#include "StrainCalculator.h"
#include <Eigen/Dense>
#include <cmath>

namespace IndicVision {

    StrainField StrainCalculator::compute_vsg_strain(const DisplacementField& disp, int window_pixels) {
        StrainField strain;
        int total_pts = disp.width * disp.height;
        strain.exx.assign(total_pts, 0.0);
        strain.eyy.assign(total_pts, 0.0);
        strain.exy.assign(total_pts, 0.0);

        double radius = window_pixels / 2.0;
        double radius_sq = radius * radius;
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

                        double phys_dx = dx * disp.step;
                        double phys_dy = dy * disp.step;

                        if ((phys_dx * phys_dx + phys_dy * phys_dy) <= radius_sq) {
                            Eigen::Vector3d a(1.0, phys_dx, phys_dy);
                            AtA += a * a.transpose();
                            AtU += a * disp.u[nidx];
                            AtV += a * disp.v[nidx];
                            valid_pts++;
                        }
                    }
                }

                if (valid_pts >= 3 && std::abs(AtA.determinant()) > 1e-9) {
                    Eigen::Vector3d Cu = AtA.ldlt().solve(AtU);
                    Eigen::Vector3d Cv = AtA.ldlt().solve(AtV);

                    double dudx = Cu(1);
                    double dudy = Cu(2);
                    double dvdx = Cv(1);
                    double dvdy = Cv(2);

                    strain.exx[idx] = 0.5 * (2.0 * dudx + dudx * dudx + dvdx * dvdx);
                    strain.eyy[idx] = 0.5 * (2.0 * dvdy + dudy * dudy + dvdy * dvdy);
                    strain.exy[idx] = 0.5 * (dudy + dvdx + dudx * dudy + dvdx * dvdy);
                }
            }
        }
        return strain;
    }
    StrainField StrainCalculator::compute_nlvc_strain(const DisplacementField& disp, int horizon_pixels) {
        StrainField strain;
        int total_pts = disp.width * disp.height;
        strain.exx.assign(total_pts, 0.0);
        strain.eyy.assign(total_pts, 0.0);
        strain.exy.assign(total_pts, 0.0);

        const double PI = 3.14159265358979323846;

        // 1. Setup NLVC Horizon and Kernel Parameters
        double h = horizon_pixels / 2.0;
        double s = h / 3.0;
        double s_sq = s * s;
        double s_sq_2 = 2.0 * s_sq;

        // Prevent division by zero if horizon is too small
        double norm_factor = (s <= 0.0) ? 0.0 : 1.0 / (PI * s_sq_2);

        // In a uniform grid, the patch area assigned to each point is exactly step * step
        double patch_area = disp.step * disp.step;

        int grid_rad = std::ceil(h / disp.step);

        for (int y = 0; y < disp.height; ++y) {
            for (int x = 0; x < disp.width; ++x) {
                int idx = y * disp.width + x;
                if (!disp.valid[idx]) continue;

                double dudx = 0.0, dudy = 0.0, dvdx = 0.0, dvdy = 0.0;
                double sum_int_x = 0.0, sum_int_y = 0.0;
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

                        double phys_dx = dx * disp.step;
                        double phys_dy = dy * disp.step;
                        double r_sq = phys_dx * phys_dx + phys_dy * phys_dy;

                        // Check if neighbor is inside the circular horizon
                        if (r_sq <= h * h) {
                            double exp_val = std::exp(-(r_sq / s_sq_2));

                            // Analytical derivative of the Gaussian kernel
                            double kx = norm_factor * (-phys_dx / s_sq) * exp_val;
                            double ky = norm_factor * (-phys_dy / s_sq) * exp_val;

                            double ux = disp.u[nidx];
                            double uy = disp.v[nidx];

                            // Track kernel balance (detects edge-of-ROI asymmetric boundaries)
                            sum_int_x += kx * patch_area;
                            sum_int_y += ky * patch_area;

                            // Integrate displacement gradients
                            dudx -= ux * kx * patch_area;
                            dudy -= ux * ky * patch_area;
                            dvdx -= uy * kx * patch_area;
                            dvdy -= uy * ky * patch_area;

                            valid_pts++;
                        }
                    }
                }

                // 3. Boundary Check and Green-Lagrange Strain Calculation
                // If sum_int > 0.01, the integration circle was cut off by the edge of the ROI,
                // which causes false strain. We discard those boundary pixels.
                if (valid_pts >= 3 && std::abs(sum_int_x) <= 0.01 && std::abs(sum_int_y) <= 0.01) {
                    strain.exx[idx] = 0.5 * (2.0 * dudx + dudx * dudx + dvdx * dvdx);
                    strain.eyy[idx] = 0.5 * (2.0 * dvdy + dudy * dudy + dvdy * dvdy);
                    strain.exy[idx] = 0.5 * (dudy + dvdx + dudx * dudy + dvdx * dvdy);
                }
            }
        }
        return strain;
    }
}