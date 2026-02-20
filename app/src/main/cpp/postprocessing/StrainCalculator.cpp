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
}