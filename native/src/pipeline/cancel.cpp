#include <indicvision/cancel.hpp>

#include <atomic>

namespace IndicVision {
namespace pipeline {

// Its own translation unit, free of OpenCV and threads, so the host test suite
// can link the cancel contract without pulling in the whole solver.
//
// Relaxed ordering throughout: the solver polls this once per point against a
// full ICGN solve, and a poll that reads a stale `false` only costs one more
// point. Nothing downstream depends on ordering with other memory.
static std::atomic<bool> g_cancel_requested(false);

void request_cancel() { g_cancel_requested.store(true, std::memory_order_relaxed); }

void clear_cancel() { g_cancel_requested.store(false, std::memory_order_relaxed); }

bool cancel_requested() { return g_cancel_requested.load(std::memory_order_relaxed); }

} // namespace pipeline
} // namespace IndicVision
