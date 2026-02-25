#ifndef INDICVISION_STRAINCALCULATOR_H
#define INDICVISION_STRAINCALCULATOR_H

#include "../core/Types.h"
#include <vector>

namespace IndicVision {

    struct DisplacementField {
        int width, height, step;
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
        static StrainField compute_nlvc_strain(const DisplacementField& disp, int horizon_pixels);
    };

}
#endif