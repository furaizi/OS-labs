package org.example.workload

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

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

/**
 * Base class for all events emitted by the workload generator. Events are strictly ordered by the
 * step number.
 */
sealed class TraceEvent(open val step: Int) {
    data class ProcessStart(
        override val step: Int,
        val pid: Int,
        val virtualPageCount: Int,
    ) : TraceEvent(step)

    data class ProcessTerminate(
        override val step: Int,
        val pid: Int,
    ) : TraceEvent(step)

    data class WorkingSetChange(
        override val step: Int,
        val pid: Int,
        val newWorkingSet: Set<Int>,
    ) : TraceEvent(step)

    data class MemoryAccess(
        override val step: Int,
        val pid: Int,
        val pageIndex: Int,
        val isWrite: Boolean,
        val currentWorkingSet: Set<Int>,
    ) : TraceEvent(step)
}

data class WorkloadTrace(
    val events: List<TraceEvent>,
    val config: WorkloadConfig,
)

private data class ProcessState(
    val pid: Int,
    val virtualPages: Int,
    var startStep: Int,
    var endStep: Int,
    var currentWorkingSet: MutableSet<Int>,
    var accessesSinceWsChange: Int = 0,
    var started: Boolean = false,
    var terminated: Boolean = false,
)

/**
 * Generates a deterministic workload trace (given a seed) that follows the specification of the
 * laboratory work. Each process exhibits locality of references: a configurable percentage of
 * accesses go to the current working set, the rest are spread across the remaining pages.
 */
class WorkloadGenerator(private val config: WorkloadConfig) {
    private val rng = Random(config.randomSeed)

    fun generate(): WorkloadTrace {
        require(config.processCount > 0) { "processCount must be positive" }
        require(config.virtualPagesPerProcess >= config.workingSetSize) {
            "virtualPagesPerProcess must be >= workingSetSize"
        }
        require(config.minLifetimeFraction in 0.0..1.0 && config.maxLifetimeFraction in 0.0..1.0) {
            "Lifetime fractions must be within [0, 1]"
        }
        require(config.minLifetimeFraction <= config.maxLifetimeFraction) {
            "minLifetimeFraction cannot exceed maxLifetimeFraction"
        }
        val events = mutableListOf<TraceEvent>()
        val processStates = createProcessStates()
        val active = mutableListOf<ProcessState>()
        var rrIndex = 0
        var step = 0
        var generatedAccesses = 0

        while (generatedAccesses < config.totalCpuAccesses) {
            // Activate processes whose start time has come.
            processStates
                .filter { !it.started && it.startStep <= step }
                .forEach {
                    it.started = true
                    active += it
                    events += TraceEvent.ProcessStart(step = step, pid = it.pid, virtualPageCount = it.virtualPages)
                    events += TraceEvent.WorkingSetChange(
                        step = step,
                        pid = it.pid,
                        newWorkingSet = it.currentWorkingSet.toSet(),
                    )
                }

            // Terminate processes whose time has elapsed.
            val terminating = active.filter { !it.terminated && it.endStep <= step }
            terminating.forEach {
                it.terminated = true
                active.remove(it)
                events += TraceEvent.ProcessTerminate(step = step, pid = it.pid)
            }

            if (active.isEmpty()) {
                // Fast forward to the next interesting point.
                val nextStart = processStates
                    .filter { it.startStep > step }
                    .minOfOrNull { it.startStep }
                    ?: break
                step = nextStart
                continue
            }

            val process = active[rrIndex % active.size]
            rrIndex++

            // Update working set if needed.
            process.accessesSinceWsChange++
            if (process.accessesSinceWsChange >= config.workingSetChangeInterval) {
                process.currentWorkingSet = pickWorkingSet(process.virtualPages)
                process.accessesSinceWsChange = 0
                events += TraceEvent.WorkingSetChange(
                    step = step,
                    pid = process.pid,
                    newWorkingSet = process.currentWorkingSet.toSet(),
                )
            }

            val access = generateAccess(process, step)
            events += access
            generatedAccesses++
            step++
        }

        // Ensure all active processes terminate for completeness.
        processStates
            .filter { it.started && !it.terminated }
            .forEach { state ->
                events += TraceEvent.ProcessTerminate(step = max(step, state.endStep), pid = state.pid)
                state.terminated = true
            }

        val ordered = events.sortedWith(compareBy<TraceEvent> { it.step }.thenBy { eventOrder(it) })
        return WorkloadTrace(events = ordered, config = config)
    }

    private fun createProcessStates(): List<ProcessState> {
        val result = mutableListOf<ProcessState>()
        var nextStartCandidate = 0
        repeat(config.processCount) { idx ->
            val pid = idx
            val lifetimeFraction = rng.nextDouble(
                config.minLifetimeFraction,
                config.maxLifetimeFraction,
            )
            val accessesForProcess = max(1, (config.totalCpuAccesses * lifetimeFraction / config.processCount).toInt())
            val duration = max(accessesForProcess, config.workingSetChangeInterval * 2)
            val startUpper = max(nextStartCandidate + 1, config.totalCpuAccesses / 2)
            val start = if (idx == 0) 0 else rng.nextInt(nextStartCandidate, startUpper)
            val end = start + duration
            nextStartCandidate = min(config.totalCpuAccesses - 1, start + config.workingSetChangeInterval)
            val workingSet = pickWorkingSet(config.virtualPagesPerProcess)

            result += ProcessState(
                pid = pid,
                virtualPages = config.virtualPagesPerProcess,
                startStep = start,
                endStep = end,
                currentWorkingSet = workingSet,
            )
        }
        return result.sortedBy { it.startStep }
    }

    private fun generateAccess(process: ProcessState, step: Int): TraceEvent.MemoryAccess {
        val isLocal = rng.nextDouble() < config.localityProbability
        val isWrite = rng.nextDouble() < config.writeProbability
        val page = if (isLocal && process.currentWorkingSet.isNotEmpty()) {
            process.currentWorkingSet.random(rng)
        } else {
            pickOutsidePage(process)
        }

        return TraceEvent.MemoryAccess(
            step = step,
            pid = process.pid,
            pageIndex = page,
            isWrite = isWrite,
            currentWorkingSet = process.currentWorkingSet.toSet(),
        )
    }

    private fun pickOutsidePage(process: ProcessState): Int {
        val available = (0 until process.virtualPages).filter { it !in process.currentWorkingSet }
        return if (available.isEmpty()) {
            process.currentWorkingSet.random(rng)
        } else {
            available.random(rng)
        }
    }

    private fun pickWorkingSet(virtualPages: Int): MutableSet<Int> {
        if (config.workingSetSize >= virtualPages) {
            return (0 until virtualPages).toMutableSet()
        }
        val result = mutableSetOf<Int>()
        while (result.size < config.workingSetSize) {
            result += rng.nextInt(virtualPages)
        }
        return result
    }

    private fun eventOrder(event: TraceEvent): Int = when (event) {
        is TraceEvent.ProcessStart -> 0
        is TraceEvent.WorkingSetChange -> 1
        is TraceEvent.MemoryAccess -> 2
        is TraceEvent.ProcessTerminate -> 3
    }
}
