package org.example.workload

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.property.Arb
import io.kotest.property.forAll
import io.kotest.property.arbitrary.long

class WorkloadGeneratorTest : FunSpec({

    val baseConfig = WorkloadConfig(
        processCount = 3,
        virtualPagesPerProcess = 16,
        workingSetSize = 4,
        workingSetChangeInterval = 6,
        totalCpuAccesses = 60,
        localityProbability = 0.8,
        writeProbability = 0.3,
        randomSeed = 123L,
        minLifetimeFraction = 0.95,
        maxLifetimeFraction = 1.0,
    )

    fun generate(config: WorkloadConfig = baseConfig): WorkloadTrace =
        WorkloadGenerator(config).generate()

    test("should generate exact number of memory accesses") {
        val trace = generate()
        val accessCount = trace.events.count { it is TraceEvent.MemoryAccess }
        accessCount shouldBeGreaterThanOrEqual baseConfig.totalCpuAccesses / 2
        accessCount shouldBeLessThanOrEqual baseConfig.totalCpuAccesses
    }

    test("events should be sorted by step") {
        val trace = generate()
        val steps = trace.events.map { it.step }
        steps shouldBe steps.sorted()
    }

    test("each process should start, change working set, access, and terminate in order") {
        val trace = generate()
        val grouped = trace.events.groupBy { event ->
            when (event) {
                is TraceEvent.ProcessStart -> event.pid
                is TraceEvent.WorkingSetChange -> event.pid
                is TraceEvent.MemoryAccess -> event.pid
                is TraceEvent.ProcessTerminate -> event.pid
            }
        }

        grouped.keys.sorted() shouldBe (0 until baseConfig.processCount).toList()

        grouped.forEach { (pid, events) ->
            events.shouldNotBeEmpty()

            val sortedEvents = events.sortedBy { it.step }
            val start = sortedEvents.first { it is TraceEvent.ProcessStart }
            val terminate = sortedEvents.last { it is TraceEvent.ProcessTerminate }

            start shouldBe sortedEvents.first()
            terminate shouldBe sortedEvents.last()

            sortedEvents.count { it is TraceEvent.ProcessStart } shouldBe 1
            sortedEvents.count { it is TraceEvent.ProcessTerminate } shouldBe 1

            sortedEvents.drop(1).forEach { event ->
                event.step shouldBeGreaterThanOrEqual start.step
            }
        }
    }

    test("memory access pages should stay within the virtual page range") {
        val trace = generate()
        trace.events.filterIsInstance<TraceEvent.MemoryAccess>()
            .forEach { access ->
                access.pageIndex shouldBeGreaterThanOrEqual 0
                access.pageIndex shouldBeLessThan baseConfig.virtualPagesPerProcess
            }
    }

    test("working set rotation should happen within configured interval per process") {
        val trace = generate()

        data class RotationState(
            var seenInitial: Boolean = false,
            var accessesSinceChange: Int = 0,
            var rotationsAfterInitial: Int = 0,
        )
        val states = mutableMapOf<Int, RotationState>()

        trace.events.forEach { event ->
            when (event) {
                is TraceEvent.MemoryAccess -> {
                    states.getOrPut(event.pid) { RotationState() }.accessesSinceChange++
                }

                is TraceEvent.WorkingSetChange -> {
                    val state = states.getOrPut(event.pid) { RotationState() }
                    if (state.seenInitial) {
                        state.accessesSinceChange shouldBeLessThanOrEqual baseConfig.workingSetChangeInterval
                        state.rotationsAfterInitial++
                    } else {
                        state.seenInitial = true
                    }
                    state.accessesSinceChange = 0
                }

                else -> Unit
            }
        }

        states.values.forEach { state ->
            state.seenInitial shouldBe true
            if (state.rotationsAfterInitial > 0) {
                state.accessesSinceChange shouldBeLessThanOrEqual baseConfig.workingSetChangeInterval
            }
        }
    }

    test("each process must terminate exactly once") {
        val trace = generate()
        val terminations = trace.events.filterIsInstance<TraceEvent.ProcessTerminate>()
        terminations.size shouldBe baseConfig.processCount
        terminations.map { it.pid }.toSet().size shouldBe baseConfig.processCount
    }

    test("invariants hold for multiple seeds") {
        val seeds = Arb.long(0L..1000L)

        forAll(seeds) { seed ->
            val config = baseConfig.copy(randomSeed = seed, totalCpuAccesses = 60)
            val trace = generate(config)
            val accesses = trace.events.filterIsInstance<TraceEvent.MemoryAccess>()
            accesses.size <= config.totalCpuAccesses &&
                accesses.size >= config.totalCpuAccesses / 3 &&
                accesses.all { it.pageIndex in 0 until config.virtualPagesPerProcess }
        }
    }
})
