package org.example.simulation

/**
 * Per-process counters that are aggregated across the simulation.
 */
data class ProcessStats(
    val pid: Int,
    var accesses: Int = 0,
    var pageFaults: Int = 0,
    var writeCount: Int = 0,
    var dirtyEvictions: Int = 0,
)

/**
 * Captures details about a specific page fault for reporting purposes.
 */
data class PageFaultRecord(
    val step: Int,
    val pid: Int,
    val pageIndex: Int,
    val victim: VictimInfo?,
)

/**
 * Describes the victim page that was evicted during a page fault.
 */
data class VictimInfo(
    val pid: Int,
    val pageIndex: Int,
    val wasDirty: Boolean,
) {
    fun describe(): String = "pid=$pid,page=$pageIndex"
}

/**
 * Summary of a single simulation run. The [render] function mirrors the original textual report.
 */
data class SimulationSummary(
    val algorithm: AlgorithmType,
    val physicalPages: Int,
    val totalAccesses: Int,
    val pageFaults: Int,
    val pageFaultRate: Double,
    val freeFrameFaults: Int,
    val replacements: Int,
    val diskWrites: Int,
    val cleanEvictions: Int,
    val dirtyEvictions: Int,
    val workingSetChanges: Int,
    val perProcess: List<ProcessStats>,
    val samplePageFaults: List<PageFaultRecord>,
) {
    /**
     * Produces the legacy textual report detailing the simulation results.
     */
    fun render(): String = buildString {
        appendLine("Algorithm: ${algorithm.displayName}")
        appendLine("Physical frames: $physicalPages")
        appendLine(
            "Accesses: $totalAccesses | Page faults: $pageFaults | Fault rate: ${
                "%.2f".format(pageFaultRate * 100)
            }%",
        )
        appendLine(
            "Faults to free frames: $freeFrameFaults | Replacements: $replacements | Disk writes: $diskWrites | Evictions (clean/dirty): $cleanEvictions/$dirtyEvictions",
        )
        appendLine("Working set changes observed: $workingSetChanges")
        appendLine("Per-process statistics:")
        perProcess.forEach { stats ->
            appendLine(
                "  PID ${stats.pid}: accesses=${stats.accesses}, faults=${stats.pageFaults}, writes=${stats.writeCount}, dirtyWrites=${stats.dirtyEvictions}",
            )
        }
        if (samplePageFaults.isNotEmpty()) {
            appendLine("Sample page fault decisions:")
            samplePageFaults.forEach { record ->
                appendLine(
                    "  [${record.step}] PID=${record.pid}, page=${record.pageIndex}, victim=${record.victim?.describe() ?: "free frame"}, dirtyWrite=${record.victim?.wasDirty}",
                )
            }
        }
    }
}
