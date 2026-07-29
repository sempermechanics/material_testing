#ifndef INDICVISION_CANCEL_HPP
#define INDICVISION_CANCEL_HPP

namespace IndicVision {
namespace pipeline {

/** Returned by run_full_field when a solve was stopped by [request_cancel]. */
constexpr int kCancelled = -99;

/**
 * Cooperative cancellation for an in-flight solve.
 *
 * The solver polls the flag inside its point loops, so a cancel takes effect
 * within a point or two instead of at the end of the frame. Process-wide rather
 * than per-call because only one solve runs at a time — the JNI layer holds the
 * reference-cache mutex for the duration of one.
 *
 * [clear_cancel] must be called before starting a solve that is meant to run:
 * the flag survives the solve it stopped.
 *
 * Deliberately kept in a header of its own, with no OpenCV in it, so the flag
 * can be linked and tested without the rest of the pipeline.
 */
void request_cancel();
void clear_cancel();
bool cancel_requested();

} // namespace pipeline
} // namespace IndicVision

#endif
