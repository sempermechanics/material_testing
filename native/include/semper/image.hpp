#ifndef SEMPER_IMAGE_HPP
#define SEMPER_IMAGE_HPP

#include <semper/types.hpp>
#include <vector>
#include <cstdint>

namespace Semper {

    class Image {
    public:
        int_t width, height;
        std::vector<scalar_t> intensities;
        std::vector<scalar_t> grad_x;
        std::vector<scalar_t> grad_y;

        Image(int_t w, int_t h, const uint8_t* raw_pixels);

        // 🚀 ADDED: Accept the UI toggle flag directly
        void prepare_data(bool apply_dice_blur);
        // The new 6x6 DICe Parity Interpolator
        scalar_t interpolate_keys_fourth(scalar_t x, scalar_t y) const;
        // Fast, branchless Keys 4th Order Bicubic
        scalar_t interpolate_bicubic(scalar_t x, scalar_t y) const;
        // The final DICe Parity Fallback
        scalar_t interpolate_bilinear(scalar_t x, scalar_t y) const;
    };

}
#endif