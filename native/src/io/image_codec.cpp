#include <semper/io.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>

namespace Semper {
namespace io {

cv::Mat decode_gray(const uint8_t* data, size_t len,
                    int expected_width, int expected_height) {
    if (data == nullptr || len == 0) return cv::Mat();
    cv::Mat img;
    if (expected_width > 0 && expected_height > 0) {
        if (len == (size_t)expected_width * expected_height * 4) {
            cv::Mat rawData(expected_height, expected_width, CV_8UC4, (void*)data);
            cv::cvtColor(rawData, img, cv::COLOR_RGBA2GRAY);
        } else if (len == (size_t)expected_width * expected_height) {
            cv::Mat rawData(expected_height, expected_width, CV_8UC1, (void*)data);
            img = rawData.clone();
        } else {
            cv::Mat rawData(1, (int)len, CV_8UC1, (void*)data);
            img = cv::imdecode(rawData, cv::IMREAD_ANYDEPTH | cv::IMREAD_ANYCOLOR);
            if (!img.empty() && img.channels() > 1) cv::cvtColor(img, img, cv::COLOR_BGR2GRAY);
            if (!img.empty() && img.depth() != CV_8U) img.convertTo(img, CV_8U);
        }
    } else {
        cv::Mat rawData(1, (int)len, CV_8UC1, (void*)data);
        img = cv::imdecode(rawData, cv::IMREAD_ANYDEPTH | cv::IMREAD_ANYCOLOR);
        if (!img.empty() && img.channels() > 1) cv::cvtColor(img, img, cv::COLOR_BGR2GRAY);
        if (!img.empty() && img.depth() != CV_8U) img.convertTo(img, CV_8U);
    }
    return img.empty() ? cv::Mat() : img.clone();
}

cv::Mat decode_bgr(const uint8_t* data, size_t len) {
    if (data == nullptr || len == 0) return cv::Mat();
    cv::Mat rawData(1, (int)len, CV_8UC1, (void*)data);
    return cv::imdecode(rawData, cv::IMREAD_COLOR);
}

void image_dimensions(const uint8_t* data, size_t len, int& out_w, int& out_h) {
    out_w = 0;
    out_h = 0;
    cv::Mat img = decode_bgr(data, len);
    if (img.empty()) {
        // try unchanged
        if (data == nullptr || len == 0) return;
        cv::Mat rawData(1, (int)len, CV_8UC1, (void*)data);
        img = cv::imdecode(rawData, cv::IMREAD_UNCHANGED);
    }
    if (!img.empty()) {
        out_w = img.cols;
        out_h = img.rows;
    }
}

} // namespace io
} // namespace Semper
