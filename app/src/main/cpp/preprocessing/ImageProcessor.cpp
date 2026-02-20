#include "ImageProcessor.h"
#include <cmath>

namespace IndicVision {

    Image::Image(int_t w, int_t h, const uint8_t *raw_pixels) : width(w), height(h) {
        intensities.resize(w * h);
        for (int i = 0; i < w * h; ++i) {
            intensities[i] = static_cast<scalar_t>(raw_pixels[i]);
        }
    }

    // --- OPTIONAL: DICe 7-Tap Gaussian Blur Kernel ---
    static const double gf_coeffs_7[49] = {
            3.60000000e-05,3.63600000e-04,1.45080000e-03,2.29860000e-03,1.45080000e-03,3.63600000e-04,3.60000000e-05,
            3.63600000e-04,3.67236000e-03,1.46530800e-02,2.32158600e-02,1.46530800e-02,3.67236000e-03,3.63600000e-04,
            1.45080000e-03,1.46530800e-02,5.84672400e-02,9.26335800e-02,5.84672400e-02,1.46530800e-02,1.45080000e-03,
            2.29860000e-03,2.32158600e-02,9.26335800e-02,1.46765610e-01,9.26335800e-02,2.32158600e-02,2.29860000e-03,
            1.45080000e-03,1.46530800e-02,5.84672400e-02,9.26335800e-02,5.84672400e-02,1.46530800e-02,1.45080000e-03,
            3.63600000e-04,3.67236000e-03,1.46530800e-02,2.32158600e-02,1.46530800e-02,3.67236000e-03,3.63600000e-04,
            3.60000000e-05,3.63600000e-04,1.45080000e-03,2.29860000e-03,1.45080000e-03,3.63600000e-04,3.60000000e-05
    };

    void Image::prepare_data() {
        bool use_gaussian_blur = true; // KEEP FALSE for high-quality images

        if (use_gaussian_blur) {
            std::vector<scalar_t> temp = intensities;
            int half_mask = 3;
            for (int y = half_mask; y < height - half_mask; ++y) {
                for (int x = half_mask; x < width - half_mask; ++x) {
                    double val = 0.0;
                    for (int i = 0; i < 7; ++i) {
                        for (int j = 0; j < 7; ++j) {
                            val += gf_coeffs_7[i * 7 + j] * temp[(y + (j - half_mask)) * width + x + (i - half_mask)];
                        }
                    }
                    intensities[y * width + x] = val;
                }
            }
        }

        grad_x.assign(width * height, 0.0);
        grad_y.assign(width * height, 0.0);

        for (int y = 2; y < height - 2; ++y) {
            for (int x = 2; x < width - 2; ++x) {
                int idx = y * width + x;
                grad_x[idx] = (-intensities[idx+2] + 8.0*intensities[idx+1] - 8.0*intensities[idx-1] + intensities[idx-2]) / 12.0;
                grad_y[idx] = (-intensities[idx+2*width] + 8.0*intensities[idx+width] - 8.0*intensities[idx-width] + intensities[idx-2*width]) / 12.0;
            }
        }
    }

    void get_keys_weights(double s, double& w_m1, double& w_0, double& w_1, double& w_2) {
        double s2 = s * s;
        double s3 = s2 * s;
        w_m1 = -0.5 * s3 + s2 - 0.5 * s;
        w_0  =  1.5 * s3 - 2.5 * s2 + 1.0;
        w_1  = -1.5 * s3 + 2.0 * s2 + 0.5 * s;
        w_2  =  0.5 * s3 - 0.5 * s2;
    }

    scalar_t Image::interpolate_bicubic(scalar_t x, scalar_t y) const {
        int xi = static_cast<int>(x);
        int yi = static_cast<int>(y);
        if (xi < 1 || xi >= width - 2 || yi < 1 || yi >= height - 2) return 0.0;

        double dx = x - xi;
        double dy = y - yi;

        double wx[4], wy[4];
        get_keys_weights(dx, wx[0], wx[1], wx[2], wx[3]);
        get_keys_weights(dy, wy[0], wy[1], wy[2], wy[3]);

        scalar_t val = 0.0;
        for (int j = -1; j <= 2; ++j) {
            const scalar_t* row_ptr = &intensities[(yi + j) * width + xi];
            double row_val = 0.0;
            row_val += row_ptr[-1] * wx[0];
            row_val += row_ptr[0]  * wx[1];
            row_val += row_ptr[1]  * wx[2];
            row_val += row_ptr[2]  * wx[3];
            val += row_val * wy[j + 1];
        }
        return val;
    }

    scalar_t Image::gradient_x(scalar_t x, scalar_t y) const {
        int xi = static_cast<int>(x); int yi = static_cast<int>(y);
        if (xi < 0 || xi >= width-1 || yi < 0 || yi >= height-1) return 0.0;
        double dx = x - xi; double dy = y - yi;
        double g00 = grad_x[yi*width + xi];
        double g10 = grad_x[yi*width + xi+1];
        double g01 = grad_x[(yi+1)*width + xi];
        double g11 = grad_x[(yi+1)*width + xi+1];
        return (1-dx)*(1-dy)*g00 + dx*(1-dy)*g10 + (1-dx)*dy*g01 + dx*dy*g11;
    }

    scalar_t Image::gradient_y(scalar_t x, scalar_t y) const {
        int xi = static_cast<int>(x); int yi = static_cast<int>(y);
        if (xi < 0 || xi >= width-1 || yi < 0 || yi >= height-1) return 0.0;
        double dx = x - xi; double dy = y - yi;
        double g00 = grad_y[yi*width + xi];
        double g10 = grad_y[yi*width + xi+1];
        double g01 = grad_y[(yi+1)*width + xi];
        double g11 = grad_y[(yi+1)*width + xi+1];
        return (1-dx)*(1-dy)*g00 + dx*(1-dy)*g10 + (1-dx)*dy*g01 + dx*dy*g11;
    }
}