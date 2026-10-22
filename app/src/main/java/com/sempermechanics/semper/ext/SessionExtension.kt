package com.sempermechanics.semper.ext

/**
 * What a product adds to a saved analysis (ADR-025).
 * Its data rides on [com.sempermechanics.semper.data.session.SessionRecord.extras], one
 * JSON object that shared code passes along and never reads.
 *
 * Every member's default does what Semper does on its own, so Semper installs
 * none ([SessionExtension.None]).
 */
interface SessionExtension {

    /**
     * Top-level keys an older build of the product wrote on each `index.json`
     * row, before its data moved under `extras`. The index reader moves them
     * into `extras` before decoding, so they survive the next write instead of
     * being dropped as unknown keys. Empty: the reader works as it always has.
     */
    val legacyKeys: Set<String> get() = emptySet()

    /** Semper's own: no extra data, no legacy keys. */
    object None : SessionExtension
}
