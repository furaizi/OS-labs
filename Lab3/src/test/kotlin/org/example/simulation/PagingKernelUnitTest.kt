package org.example.simulation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.example.workload.WorkloadConfig
import org.example.workload.WorkloadGenerator

class PagingKernelUnitTest : FunSpec({

    val baseWorkload = WorkloadConfig(
        processCount = 2,
        virtualPagesPerProcess = 10,
        workingSetSize = 4,
        workingSetChangeInterval = 5,
        totalCpuAccesses = 80,
        localityProbability = 0.8,
        writeProbability = 0.25,
        randomSeed = 2024L,
    )

    test("summary should reflect access counts and aggregated metrics") {
        val workload = WorkloadGenerator(baseWorkload).generate()
        val simConfig = SimulationConfig(physicalPageCount = 6)
        val kernel = PagingKernel(simConfig) { frames -> ClockReplacementPolicy(frames) }

        val summary = kernel.run(workload, AlgorithmType.CLOCK)

        val expectedAccesses = workload.events.filterIsInstance<org.example.workload.TraceEvent.MemoryAccess>().size

        summary.totalAccesses shouldBe expectedAccesses
        summary.pageFaultRate.shouldBeGreaterThanOrEqual(0.0)
        summary.pageFaultRate.shouldBeLessThanOrEqual(1.0)

        val perProcessFaults = summary.perProcess.sumOf { it.pageFaults }
        perProcessFaults shouldBe summary.pageFaults

        val totalEvictions = summary.cleanEvictions + summary.dirtyEvictions
        (summary.replacements <= totalEvictions).shouldBeTrue()
        summary.diskWrites shouldBe summary.dirtyEvictions

        summary.samplePageFaults.size shouldBeLessThanOrEqual 25
    }

    test("kernel should invoke replacement policy when no free frames remain") {
        val workloadConfig = baseWorkload.copy(
            processCount = 1,
            virtualPagesPerProcess = 6,
            workingSetSize = 3,
            totalCpuAccesses = 30,
            randomSeed = 77L,
        )
        val workload = WorkloadGenerator(workloadConfig).generate()
        val simConfig = SimulationConfig(physicalPageCount = 2)

        lateinit var capturedPolicy: PageReplacementPolicy
        val kernel = PagingKernel(simConfig) { frames ->
            val policy = mockk<PageReplacementPolicy>()
            every { policy.onFrameAccess(any()) } just Runs
            every { policy.onFrameLoaded(any()) } just Runs
            every { policy.onFrameFreed(any()) } just Runs
            every { policy.chooseVictim() } answers { frames.first { !it.isFree() } }
            capturedPolicy = policy
            policy
        }

        kernel.run(workload, AlgorithmType.RANDOM)

        verify(atLeast = 1) { capturedPolicy.chooseVictim() }
    }
})
