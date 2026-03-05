#ifndef INDICVISION_OPTIMIZATIONENGINE_H
#define INDICVISION_OPTIMIZATIONENGINE_H

#include "../preprocessing/ImageProcessor.h"
#include "Types.h"

#if defined(__ARM_NEON__) || defined(__aarch64__)
#include <arm_neon.h>
#endif
namespace IndicVision {

    class OptimizationEngine {
    public:
        // Profiling trackers (kept as double for timing precision)
        double time_icgn_ms = 0.0;
        double time_simplex_ms = 0.0;
        int count_icgn = 0;
        int count_simplex = 0;

        // 🟡 BUG 3 FIX: Separated Buffers
        // Prevents memory aliasing and silent data corruption between the two solvers
        std::vector<float> icgn_buffer;
        std::vector<float> simplex_buffer;

        // 🚀 UPDATED SIGNATURE: Now accepts full 6-DOF guess
        AnalysisResult calculate_deformation(const SubsetData &subset,
                                             const Image &def_img, float guess_u,
                                             float guess_v, float guess_ux,
                                             float guess_uy, float guess_vx, float guess_vy,
                                             InitializationMode init_mode);

    private:
        void estimate_initial_guess(const SubsetData &subset, const Image &def_img,
                                    scalar_t &best_u, scalar_t &best_v);

        // 🚀 UPDATED SIGNATURE: ICGN now initializes with full strain tensor
        AnalysisResult solve_icgn(const SubsetData &subset, const Image &def_img,
                                  float init_u, float init_v, float init_ux,
                                  float init_uy, float init_vx, float init_vy);

        AnalysisResult solve_simplex(const SubsetData &subset, const Image &def_img,
                                     AnalysisResult start, bool translation_only);

        float evaluate_znssd(const SubsetData &subset, const Image &def_img, float u,
                             float v, float ux, float uy, float vx, float vy,
                             std::vector<float> &buffer);
    };

} // namespace IndicVision
#endif