#ifndef INDICVISION_IMAGEPROCESSOR_H
#define INDICVISION_IMAGEPROCESSOR_H

#include "../core/Types.h"
#include <vector>
#include <cstdint>

namespace IndicVision {

    class Image {
    public:
        int_t width, height;
        std::vector<scalar_t> intensities;
        std::vector<scalar_t> grad_x;
        std::vector<scalar_t> grad_y;

        Image(int_t w, int_t h, const uint8_t* raw_pixels);

        // 🚀 ADDED: Accept the UI toggle flag directly
        void prepare_data(bool apply_dice_blur);

        // Fast, branchless Keys 4th Order Bicubic
        scalar_t interpolate_bicubic(scalar_t x, scalar_t y) const;
        scalar_t gradient_x(scalar_t x, scalar_t y) const;
        scalar_t gradient_y(scalar_t x, scalar_t y) const;
    };

}
#endif