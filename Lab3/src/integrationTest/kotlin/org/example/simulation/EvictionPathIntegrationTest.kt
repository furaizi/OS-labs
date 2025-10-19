package org.example.simulation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import org.example.workload.WorkloadConfig
import org.example.workload.WorkloadGenerator

class EvictionPathIntegrationTest : FunSpec({

    test("simulation should exercise eviction path when frames are exhausted") {
        val config = WorkloadConfig(
            processCount = 1,
            virtualPagesPerProcess = 5,
            workingSetSize = 2,
            workingSetChangeInterval = 4,
            totalCpuAccesses = 60,
            localityProbability = 0.6,
            writeProbability = 0.5,
            randomSeed = 123L,
        )
        val trace = WorkloadGenerator(config).generate()
        val summaries = PageReplacementExperiment(SimulationConfig(physicalPageCount = 2)).run(trace)

        summaries.values.forEach { summary ->
            summary.replacements shouldBeGreaterThan 0
            summary.pageFaults.shouldBeGreaterThanOrEqual(summary.freeFrameFaults)
            summary.samplePageFaults.size shouldBeLessThanOrEqual 25
        }
    }
})
