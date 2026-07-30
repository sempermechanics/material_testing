#ifndef SEMPER_TEST_IMAGE_IO_H
#define SEMPER_TEST_IMAGE_IO_H

// Fixture image loading, so fixtures stay vendored in DICe's original .tif.
//
// This header exposes no OpenCV types on purpose: the engine sources include
// OpenCV's universal-intrinsics headers from the git submodule, and letting a
// system opencv2/ tree into those same translation units would mix two header
// trees. The implementation is confined to image_io.cpp, the only file CMake
// gives the system OpenCV includes to.
//
// OpenCV is optional. CMake defines DIC_HAVE_OPENCV only when it is present;
// image-backed tests compile out otherwise, so the rest of the suite still
// builds with just a C++17 compiler. CI installs libopencv-dev.

#include <cstdint>
#include <string>
#include <vector>

namespace dictest {

    // 8-bit single-channel image decoded from disk.
    struct GrayImage {
        int w = 0, h = 0;
        std::vector<uint8_t> px;
    };

    // Decode any format OpenCV supports (TIFF here) as single-channel 8-bit.
    // Palette and 16-bit sources are normalised to 8-bit gray by the decoder.
    // Returns false if the file is missing or undecodable.
    bool load_gray(const std::string &path, GrayImage &out);

} // namespace dictest

#endif // SEMPER_TEST_IMAGE_IO_H
