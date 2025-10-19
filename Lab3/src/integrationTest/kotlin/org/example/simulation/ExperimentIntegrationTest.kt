package org.example.simulation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import org.example.workload.WorkloadConfig
import org.example.workload.WorkloadGenerator

class ExperimentIntegrationTest : FunSpec({

    test("experiment should produce summaries for both algorithms with consistent metrics") {
        val workloadConfig = WorkloadConfig(
            processCount = 2,
            virtualPagesPerProcess = 12,
            workingSetSize = 3,
            workingSetChangeInterval = 5,
            totalCpuAccesses = 150,
            localityProbability = 0.9,
            writeProbability = 0.25,
            randomSeed = 7L,
        )
        val trace = WorkloadGenerator(workloadConfig).generate()

        val summaries = PageReplacementExperiment(SimulationConfig(physicalPageCount = 6)).run(trace)

        summaries shouldContainKey AlgorithmType.RANDOM
        summaries shouldContainKey AlgorithmType.CLOCK

        AlgorithmType.entries.forEach { algorithm ->
            val summary = summaries[algorithm] ?: error("missing summary for $algorithm")
            summary.pageFaultRate.shouldBeGreaterThanOrEqual(0.0)
            summary.pageFaultRate.shouldBeLessThanOrEqual(1.0)
            val totalEvictions = summary.cleanEvictions + summary.dirtyEvictions
            summary.diskWrites shouldBe summary.dirtyEvictions
            (summary.cleanEvictions + summary.dirtyEvictions) shouldBe totalEvictions
            summary.perProcess.sumOf { it.pageFaults } shouldBe summary.pageFaults
        }

        val randomFaults = summaries.getValue(AlgorithmType.RANDOM).pageFaults
        val clockFaults = summaries.getValue(AlgorithmType.CLOCK).pageFaults
        clockFaults shouldBeLessThanOrEqual randomFaults + 5
    }
})
