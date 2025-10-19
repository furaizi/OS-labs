package org.example.simulation

/**
 * Describes the physical memory layout that the paging kernel should emulate.
 */
data class SimulationConfig(
    val physicalPageCount: Int,
)

/**
 * Page replacement algorithms supported by the laboratory work.
 */
enum class AlgorithmType(val displayName: String) {
    RANDOM("Random"),
    CLOCK("Clock"),
}
