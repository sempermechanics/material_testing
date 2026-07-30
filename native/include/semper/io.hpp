#ifndef SEMPER_IO_HPP
#define SEMPER_IO_HPP

#include <opencv2/core.hpp>
#include <cstddef>
#include <cstdint>

namespace Semper {
namespace io {

/** Decode grayscale image from encoded bytes or raw RGBA/ALPHA_8 buffer. */
cv::Mat decode_gray(const uint8_t* data, size_t len,
                    int expected_width = 0, int expected_height = 0);

/** Decode BGR (for preview thumbnails). Empty on failure. */
cv::Mat decode_bgr(const uint8_t* data, size_t len);

/** Image width/height without full decode when possible; zeros on failure. */
void image_dimensions(const uint8_t* data, size_t len, int& out_w, int& out_h);

} // namespace io
} // namespace Semper

#endif
