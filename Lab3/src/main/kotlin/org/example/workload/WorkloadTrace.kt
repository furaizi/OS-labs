package org.example.workload

/**
 * Collection of workload events alongside the configuration that produced them.
 */
data class WorkloadTrace(
    val events: List<TraceEvent>,
    val config: WorkloadConfig,
)
