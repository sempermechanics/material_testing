#ifndef INDICVISION_PIPELINE_HPP
#define INDICVISION_PIPELINE_HPP

#include <indicvision/cancel.hpp>
#include <indicvision/image.hpp>
#include <opencv2/core.hpp>
#include <opencv2/features2d.hpp>
#include <functional>
#include <mutex>
#include <string>
#include <vector>

namespace IndicVision {
namespace pipeline {

using ProgressCallback = std::function<void(int percentage)>;

/** Cached reference for multi-frame solves (AKAZE + Image). Thread-safe via mutex. */
struct ReferenceCache {
    Image* ref_img = nullptr;
    int width = 0;
    int height = 0;
    cv::Mat gray;
    std::vector<cv::KeyPoint> akaze_kp;
    cv::Mat akaze_desc;
    double akaze_scale = 0.25;
    std::mutex mutex;
    std::string debug_dir;

    ~ReferenceCache() { reset(); }
    void reset();
    void set_from_gray(const cv::Mat& gray_in, const cv::Mat& roi_mask, bool apply_blur);
};

struct FullFieldParams {
    int rect_x = 0, rect_y = 0, rect_w = 0, rect_h = 0;
    int step = 0;
    int subset_size = 0;
    int strain_window = 0;
    bool use_delaunay = true;
    bool use_fallback = true;
    bool use_rgdic = true;
    bool apply_gaussian_blur = false;
    bool use_nlvc_strain = false;
    bool use_6x6_interpolator = false;
};

/**
 * Run the hybrid full-field DIC pipeline.
 * @return number of valid output points, or negative error code
 *   (-2 ROI, -3 init, [kCancelled] if cancelled mid-solve).
 * Writes packed points to output_ptr (8 floats each: x,y,u,v,exx,eyy,exy,corr).
 * If metrics != nullptr and metrics_len >= 16, fills engine telemetry (17 floats preferred).
 */
int run_full_field(
    ReferenceCache& cache,
    const cv::Mat& def_gray,
    const cv::Mat& roi_mask,
    const FullFieldParams& params,
    float* output_ptr,
    float* metrics,
    int metrics_len,
    ProgressCallback on_progress = nullptr);

} // namespace pipeline
} // namespace IndicVision

#endif
