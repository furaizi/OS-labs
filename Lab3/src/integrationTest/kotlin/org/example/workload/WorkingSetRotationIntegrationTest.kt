package org.example.workload

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import kotlin.math.abs

class WorkingSetRotationIntegrationTest : FunSpec({

    val baseConfig = WorkloadConfig(
        processCount = 3,
        virtualPagesPerProcess = 18,
        workingSetSize = 4,
        workingSetChangeInterval = 6,
        totalCpuAccesses = 120,
        localityProbability = 0.85,
        writeProbability = 0.3,
    )

    listOf(1L, 7L, 42L).forEach { seed ->
        test("working set rotation behaves consistently for seed $seed") {
            val config = baseConfig.copy(randomSeed = seed)
            val trace = WorkloadGenerator(config).generate()

            val starts = mutableSetOf<Int>()
            val terminations = mutableSetOf<Int>()
            val accessesByPid = mutableMapOf<Int, Int>()
            val workingSetChangesByPid = mutableMapOf<Int, Int>()

            trace.events.forEach { event ->
                when (event) {
                    is TraceEvent.ProcessStart -> starts += event.pid
                    is TraceEvent.ProcessTerminate -> terminations += event.pid
                    is TraceEvent.MemoryAccess -> accessesByPid.merge(event.pid, 1, Int::plus)
                    is TraceEvent.WorkingSetChange -> workingSetChangesByPid.merge(event.pid, 1, Int::plus)
                }
            }

            val expectedPids = (0 until config.processCount).toList()
            starts.shouldContainExactlyInAnyOrder(expectedPids)
            terminations.shouldContainExactlyInAnyOrder(expectedPids)

            expectedPids.forEach { pid ->
                val accesses = accessesByPid[pid] ?: 0
                val changes = workingSetChangesByPid[pid] ?: 0
                val rotations = if (changes > 0) changes - 1 else 0
                val expectedRotations = accesses / config.workingSetChangeInterval
                abs(rotations - expectedRotations) shouldBeLessThanOrEqual 1
            }
        }
    }
})
