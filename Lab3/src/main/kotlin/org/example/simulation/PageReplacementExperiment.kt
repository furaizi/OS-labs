package org.example.simulation

import org.example.workload.WorkloadTrace

/**
 * Executes a suite of algorithms against a pre-generated trace while sharing the same physical memory configuration.
 */
class PageReplacementExperiment(
    private val config: SimulationConfig,
) {
    fun run(trace: WorkloadTrace): Map<AlgorithmType, SimulationSummary> {
        return AlgorithmType.entries.associateWith { algorithm ->
            val kernel = PagingKernel(config, policyFactory(algorithm))
            kernel.run(trace, algorithm)
        }
    }

    private fun policyFactory(algorithm: AlgorithmType): ReplacementPolicyFactory = when (algorithm) {
        AlgorithmType.RANDOM -> { frames -> RandomReplacementPolicy(frames) }
        AlgorithmType.CLOCK -> { frames -> ClockReplacementPolicy(frames) }
    }
}
