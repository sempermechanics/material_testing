#ifndef SEMPER_ASSERT_HPP
#define SEMPER_ASSERT_HPP

#include <cassert>

// Debug-only invariant checks. Compiles to nothing under NDEBUG (Release /
// host Perf builds). Do NOT place SEMPER_ASSERT inside ICGN inner loops — even
// empty macros can perturb inlining under -O3 when the condition is non-trivial.
#ifndef NDEBUG
#define SEMPER_ASSERT(cond) assert(cond)
#else
#define SEMPER_ASSERT(cond) ((void)0)
#endif

#endif // SEMPER_ASSERT_HPP
