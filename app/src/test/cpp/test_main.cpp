// =====================================================================
// NATIVE TEST RUNNER — see docs/TESTING.md for the full test catalog.
//
// Usage:
//   dic_tests                 run all suites
//   dic_tests SimdKernels     run one suite
//   dic_tests Engine.PureTranslation_Subpixel   run a single test
// =====================================================================
#include "framework/test_framework.h"

int main(int argc, char **argv) {
    return dictest::run_all(argc, argv);
}
