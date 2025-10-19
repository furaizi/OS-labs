package org.example.cli

import org.example.workload.WorkloadConfig

/**
 * Immutable view of the command-line options accepted by the laboratory application.
 */
data class CliOptions(
    val physicalFrames: Int = 8,
    val processCount: Int = 3,
    val virtualPagesPerProcess: Int = 16,
    val workingSetSizes: List<Int> = listOf(3, 6, 9),
    val workingSetChangeInterval: Int = 25,
    val totalCpuAccesses: Int = 2000,
    val localityProbability: Double = 0.9,
    val writeProbability: Double = 0.25,
    val randomSeed: Long = 42L,
) {
    fun toWorkloadConfig(workingSetSize: Int): WorkloadConfig = WorkloadConfig(
        processCount = processCount,
        virtualPagesPerProcess = virtualPagesPerProcess,
        workingSetSize = workingSetSize,
        workingSetChangeInterval = workingSetChangeInterval,
        totalCpuAccesses = totalCpuAccesses,
        localityProbability = localityProbability,
        writeProbability = writeProbability,
        randomSeed = randomSeed,
    )
}