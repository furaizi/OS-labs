package org.example.workload

/**
 * Configuration parameters that control the shape of the generated workload.
 */
data class WorkloadConfig(
    val processCount: Int,
    val virtualPagesPerProcess: Int,
    val workingSetSize: Int,
    val workingSetChangeInterval: Int,
    val totalCpuAccesses: Int,
    val localityProbability: Double = 0.9,
    val writeProbability: Double = 0.3,
    val minLifetimeFraction: Double = 0.5,
    val maxLifetimeFraction: Double = 0.9,
    val randomSeed: Long = 42L,
)
