// The ONLY translation unit that includes system OpenCV.
//
// Kept separate from every engine TU on purpose: the engine compiles against
// OpenCV's universal-intrinsics headers vendored as a git submodule, so pulling
// a system opencv2/ tree into those same TUs could mix two header trees at
// different versions. CMake scopes the OpenCV include dirs to this file alone
// and links the resulting object into the test binary.

#include "framework/image_io.h"

#include <opencv2/core.hpp>
#include <opencv2/imgcodecs.hpp>

namespace dictest {

    bool load_gray(const std::string &path, GrayImage &out) {
        cv::Mat m = cv::imread(path, cv::IMREAD_GRAYSCALE);
        if (m.empty()) return false;
        if (!m.isContinuous()) m = m.clone();
        if (m.type() != CV_8UC1) return false;
        out.w = m.cols;
        out.h = m.rows;
        const size_t n = static_cast<size_t>(m.cols) * static_cast<size_t>(m.rows);
        out.px.assign(m.data, m.data + n);
        return out.px.size() == n;
    }

} // namespace dictest
