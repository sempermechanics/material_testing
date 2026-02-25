#ifndef INDICVISION_OPTIMIZATIONENGINE_H
#define INDICVISION_OPTIMIZATIONENGINE_H

#include "Types.h"
#include "../preprocessing/ImageProcessor.h"
#if defined(__ARM_NEON__) || defined(__aarch64__)
#include <arm_neon.h>
#endif
namespace IndicVision {

    class OptimizationEngine {
    public:
        // Profiling trackers
        double time_icgn_ms = 0.0;
        double time_simplex_ms = 0.0;
        int count_icgn = 0;
        int count_simplex = 0;
        std::vector<double> eval_buffer;

        AnalysisResult calculate_deformation(const SubsetData& subset, const Image& def_img, scalar_t guess_u, scalar_t guess_v, InitializationMode init_mode);

    private:
        void estimate_initial_guess(const SubsetData& subset, const Image& def_img, scalar_t& best_u, scalar_t& best_v);
        AnalysisResult solve_icgn(const SubsetData& subset, const Image& def_img, double init_u, double init_v);
        AnalysisResult solve_simplex(const SubsetData& subset, const Image& def_img, AnalysisResult start, bool translation_only);
        double evaluate_znssd(const SubsetData& subset, const Image& def_img, double u, double v, double ux, double uy, double vx, double vy, std::vector<double>& buffer);
    };

}
#endif