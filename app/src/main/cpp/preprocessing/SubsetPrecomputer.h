#ifndef INDICVISION_SUBSETPRECOMPUTER_H
#define INDICVISION_SUBSETPRECOMPUTER_H

#include "../core/Types.h"
#include "ImageProcessor.h"

namespace IndicVision {

    class SubsetPrecomputer {
    public:
        /**
         * Computes the Inverse Hessian and Steepest Descent images.
         * This only needs to happen ONCE per subset!
         */
        static void precompute_subset(SubsetData& data, const Image& ref_img, int_t cx, int_t cy, int_t dim);
    };

}
#endif