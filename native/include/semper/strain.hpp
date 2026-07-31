#ifndef SEMPER_STRAIN_HPP
#define SEMPER_STRAIN_HPP

#include <semper/types.hpp>
#include <vector>

namespace Semper {

    struct DisplacementField {
        int width = 0, height = 0, step = 0;
        std::vector<float> u, v; // 🚀 Changed to float
        std::vector<bool> valid;
    };

    struct StrainField {
        std::vector<float> exx, eyy, exy; // 🚀 Changed to float
    };

    class StrainCalculator {
    public:
        // Implements DICe's Standard VSG (Linear Least Squares Plane Fit)
        static StrainField compute_vsg_strain(const DisplacementField& disp, int window_pixels);
    };

}
#endif