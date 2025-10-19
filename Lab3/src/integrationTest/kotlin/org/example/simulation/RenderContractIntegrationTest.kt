package org.example.simulation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import org.example.workload.WorkloadConfig
import org.example.workload.WorkloadGenerator

class RenderContractIntegrationTest : FunSpec({

    test("rendered summaries should include required report sections") {
        val config = WorkloadConfig(
            processCount = 2,
            virtualPagesPerProcess = 10,
            workingSetSize = 3,
            workingSetChangeInterval = 5,
            totalCpuAccesses = 100,
            localityProbability = 0.85,
            writeProbability = 0.2,
            randomSeed = 9L,
        )
        val trace = WorkloadGenerator(config).generate()
        val summaries = PageReplacementExperiment(SimulationConfig(physicalPageCount = 5)).run(trace)

        summaries.values.forEach { summary ->
            val rendered = summary.render()
            rendered.shouldContain("Algorithm:")
            rendered.shouldContain("Physical frames:")
            rendered.shouldContain("Accesses:")
            rendered.shouldContain("Page faults:")
            rendered.shouldContain("Fault rate:")
            rendered.shouldContain("Replacements:")
            rendered.shouldContain("Disk writes:")
            rendered.shouldContain("Per-process statistics:")
            rendered.shouldContain("Evictions (clean/dirty):")
            rendered.shouldContain("Working set changes observed:")
        }
    }
})
