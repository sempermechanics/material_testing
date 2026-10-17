package com.sempermechanics.semper.report

/**
 * All-zero stats with mesh seeding unknown: what [EngineStats.fromArray] gives
 * for an array shorter than the core slots, and what the viewer's report used
 * when a session carried no stats.
 */
val EngineStats.Companion.EMPTY: EngineStats
    get() = EngineStats(0, 0, 0, 0, 0, 0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

/**
 * Stored stats (a session record's list) as a report reads them, the cloud
 * bundle's rule: every stored slot up to [EngineStats.SLOT_COUNT] is kept,
 * and a shorter list is padded with zeros to the core slots, never past what
 * was stored (a padded legacy 16-slot list would claim mesh quality 0, not
 * unknown). Null or empty is [EMPTY].
 */
fun EngineStats.Companion.fromList(stats: List<Float>?): EngineStats {
    val stored = stats.orEmpty()
    val size = stored.size.coerceIn(CORE_SLOT_COUNT, SLOT_COUNT)
    return fromArray(FloatArray(size) { stored.getOrElse(it) { 0f } })
}

/**
 * A fresh telemetry array for one solve: every slot the engine can write,
 * with mesh seeding preset to unknown so an engine that writes only the core
 * slots leaves it meaning "unknown" rather than "fallback".
 */
fun EngineStats.Companion.newMetrics(size: Int = SLOT_COUNT): FloatArray =
    FloatArray(size).also { it[SLOT_MESH_SEEDING] = MESH_SEEDING_UNKNOWN.toFloat() }

/**
 * Points ICGN accepted on a frame, before the strain filter: path A, path B
 * and, when [metrics] reaches it, the anchor lattice ([SLOT_ANCHOR_POINTS]).
 * Since engine 0.3.0 anchor-solved points are in neither path slot, so a
 * shorter array undercounts them.
 */
fun EngineStats.Companion.correlatedPoints(metrics: FloatArray): Int {
    val anchors = if (metrics.size > SLOT_ANCHOR_POINTS) metrics[SLOT_ANCHOR_POINTS] else 0f
    return (metrics[SLOT_PATH_A_POINTS] + metrics[SLOT_PATH_B_POINTS] + anchors).toInt()
}
