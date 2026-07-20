#ifndef INDICVISION_TEST_IMAGE_IO_H
#define INDICVISION_TEST_IMAGE_IO_H

// Image loading for host tests, backed by OpenCV — so fixtures can be vendored
// in their ORIGINAL format (DICe ships .tif/.tiff) rather than converted, and
// we use a real decoder instead of a hand-rolled parser.
//
// IMPORTANT: this header deliberately exposes NO OpenCV types and includes NO
// OpenCV headers. The engine sources already pull OpenCV's universal-intrinsics
// headers from the git submodule; letting a *system* opencv2/ tree into the
// same translation unit would mix two header trees at potentially different
// versions. The implementation therefore lives in image_io.cpp, which is the
// only place system OpenCV is included (see CMakeLists: it is compiled into a
// separate target with the OpenCV include dirs scoped to it).
//
// OpenCV is OPTIONAL for the suite: the engine/unit tests need only a C++17
// compiler and the header-only submodules (see docs/engine/TESTING.md), so
// CMake defines DIC_HAVE_OPENCV only when OpenCV is present. Image-backed tests
// compile out otherwise, keeping the core suite buildable anywhere. CI installs
// libopencv-dev, so they always run there.

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

#endif // INDICVISION_TEST_IMAGE_IO_H
