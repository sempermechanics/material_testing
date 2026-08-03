// Host characterization for run_full_field — mirrors EnginePipelineSmokeTest
// contract block (degenerate ROI → -2, empty def → -3, stale cancel, undersized
// buffer). Compiled only when DIC_HAVE_OPENCV is set (same gate as DICe tests).
#include "framework/test_framework.h"
#include "framework/synthetic.h"

#include <semper/cancel.hpp>
#include <semper/pipeline.hpp>

#include <algorithm>
#include <cmath>
#include <opencv2/core.hpp>
#include <vector>

using Semper::pipeline::FullFieldParams;
using Semper::pipeline::ReferenceCache;
using Semper::pipeline::clear_cancel;
using Semper::pipeline::kCancelled;
using Semper::pipeline::request_cancel;
using Semper::pipeline::run_full_field;

#if defined(DIC_HAVE_OPENCV)

namespace {

constexpr int W = 256;
constexpr int H = 256;
constexpr int STEP = 15;
constexpr int SUBSET = 21;
constexpr int STRAIN_WIN = 15;

cv::Mat gray8_from_image(const Semper::Image &img) {
    cv::Mat m(img.height, img.width, CV_8UC1);
    for (int y = 0; y < img.height; ++y) {
        for (int x = 0; x < img.width; ++x) {
            float v = img.intensities[(size_t)y * img.width + x];
            if (v < 0.f) v = 0.f;
            if (v > 255.f) v = 255.f;
            m.at<uchar>(y, x) = static_cast<uchar>(v + 0.5f);
        }
    }
    return m;
}

void make_pair(cv::Mat &ref_gray, cv::Mat &def_gray) {
    dictest::SpeckleField field(/*seed=*/11, W, H, /*blob_count=*/600);
    dictest::AffineDeformation def;
    def.u = 3.0f;
    def.v = 2.0f;
    def.cx = W / 2.0f;
    def.cy = H / 2.0f;
    const Semper::Image ref = dictest::make_reference_image(field, W, H);
    const Semper::Image deformed = dictest::make_deformed_image(field, W, H, def);
    ref_gray = gray8_from_image(ref);
    def_gray = gray8_from_image(deformed);
}

FullFieldParams params_for(int rect_w, int rect_h) {
    FullFieldParams p;
    p.rect_x = 0;
    p.rect_y = 0;
    p.rect_w = rect_w;
    p.rect_h = rect_h;
    p.step = STEP;
    p.subset_size = SUBSET;
    p.strain_window = STRAIN_WIN;
    p.use_6x6_interpolator = false;
    return p;
}

int call_solver(ReferenceCache &cache, const cv::Mat &def_gray,
                const FullFieldParams &params, int buffer_floats) {
    std::vector<float> out(std::max(1, buffer_floats), 0.f);
    float metrics[17] = {};
    metrics[16] = -1.f;
    return run_full_field(cache, def_gray, cv::Mat(), params,
                          out.data(), buffer_floats, metrics, 17, nullptr);
}

} // namespace

TEST_CASE(FullField, DegenerateRoi_ReturnsRoiError) {
    cv::Mat ref_gray, def_gray;
    make_pair(ref_gray, def_gray);
    ReferenceCache cache;
    cache.set_from_gray(ref_gray, cv::Mat());
    // rectW < step ⇒ gridW == 0 ⇒ documented ROI error (-2).
    const int code = call_solver(cache, def_gray, params_for(STEP - 1, STEP - 1),
                                 8 * 4);
    CHECK(code == -2);
}

TEST_CASE(FullField, EmptyDeformed_ReturnsInitError) {
    cv::Mat ref_gray, def_gray;
    make_pair(ref_gray, def_gray);
    ReferenceCache cache;
    cache.set_from_gray(ref_gray, cv::Mat());
    const int code = call_solver(cache, cv::Mat(), params_for(W, H), 8 * 4);
    CHECK(code == -3);
}

TEST_CASE(FullField, StaleCancelDoesNotAbortNextSolve) {
    cv::Mat ref_gray, def_gray;
    make_pair(ref_gray, def_gray);
    ReferenceCache cache;
    cache.set_from_gray(ref_gray, cv::Mat());
    request_cancel(); // stale — run_full_field must clear on entry
    const int n = call_solver(cache, def_gray, params_for(W, H),
                              8 * ((W / STEP) * (H / STEP) + 8));
    clear_cancel();
    // Contract: must not return the cancel sentinel. Packed-point count may be
    // zero on this analytic field after the strain post-filter; field quality
    // is covered by Engine.* and emulator smoke, not this cancel pin.
    CHECK(n != kCancelled);
    CHECK(n >= 0);
}

TEST_CASE(FullField, UndersizedBuffer_TruncatesWithoutOverflow) {
    cv::Mat ref_gray, def_gray;
    make_pair(ref_gray, def_gray);
    ReferenceCache cache;
    cache.set_from_gray(ref_gray, cv::Mat());
    // Room for only 4 packed points (8 floats each).
    const int n = call_solver(cache, def_gray, params_for(W, H), 8 * 4);
    CHECK(n >= 0);
    CHECK(n <= 4);
}

#else

TEST_CASE(FullField, OpenCvRequired_SkippedWithoutOpenCV) {
    // Suite still registers when OpenCV is absent so the filter surface is stable.
    CHECK(true);
}

#endif // DIC_HAVE_OPENCV
