package org.example.workload

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Generates traces that exhibit locality of reference and working set evolution.
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
        val processes = createProcessStates()
        val active = mutableListOf<ProcessState>()
        var rrIndex = 0
        var step = 0
        var generatedAccesses = 0

        while (generatedAccesses < config.totalCpuAccesses) {
            activateReadyProcesses(processes, active, step, events)
            terminateElapsedProcesses(active, step, events)

            if (active.isEmpty()) {
                val nextStart = processes
                    .filter { !it.started }
                    .minOfOrNull { it.startStep }
                    ?: break
                step = nextStart
                continue
            }

            val process = active[rrIndex % active.size]
            rrIndex++

            maybeRotateWorkingSet(process, step, events)

            val access = generateAccess(process, step)
            events += access
            generatedAccesses++
            step++
        }

        forceTerminateRemaining(processes, step, events)

        val ordered = events.sortedWith(compareBy<TraceEvent> { it.step }.thenBy(::eventOrder))
        return WorkloadTrace(events = ordered, config = config)
    }

    private fun activateReadyProcesses(
        processes: List<ProcessState>,
        active: MutableList<ProcessState>,
        step: Int,
        sink: MutableList<TraceEvent>,
    ) {
        processes
            .filter { !it.started && it.startStep <= step }
            .forEach { process ->
                process.started = true
                active += process
                sink += TraceEvent.ProcessStart(step = step, pid = process.pid, virtualPageCount = process.virtualPages)
                sink += TraceEvent.WorkingSetChange(
                    step = step,
                    pid = process.pid,
                    newWorkingSet = process.currentWorkingSet.toSet(),
                )
            }
    }

    private fun terminateElapsedProcesses(
        active: MutableList<ProcessState>,
        step: Int,
        sink: MutableList<TraceEvent>,
    ) {
        val terminating = active.filter { !it.terminated && it.endStep <= step }
        terminating.forEach { process ->
            process.terminated = true
            active.remove(process)
            sink += TraceEvent.ProcessTerminate(step = step, pid = process.pid)
        }
    }

    private fun maybeRotateWorkingSet(
        process: ProcessState,
        step: Int,
        sink: MutableList<TraceEvent>,
    ) {
        process.accessesSinceWsChange++
        if (process.accessesSinceWsChange >= config.workingSetChangeInterval) {
            process.currentWorkingSet = pickWorkingSet(process.virtualPages)
            process.accessesSinceWsChange = 0
            sink += TraceEvent.WorkingSetChange(
                step = step,
                pid = process.pid,
                newWorkingSet = process.currentWorkingSet.toSet(),
            )
        }
    }

    private fun forceTerminateRemaining(
        processes: List<ProcessState>,
        currentStep: Int,
        sink: MutableList<TraceEvent>,
    ) {
        processes
            .filter { it.started && !it.terminated }
            .forEach { process ->
                val terminationStep = max(currentStep, process.endStep)
                process.terminated = true
                sink += TraceEvent.ProcessTerminate(step = terminationStep, pid = process.pid)
            }
    }

    private fun generateAccess(process: ProcessState, step: Int): TraceEvent.MemoryAccess {
        val isLocal = rng.nextDouble() < config.localityProbability
        val isWrite = rng.nextDouble() < config.writeProbability
        val targetPage = if (isLocal && process.currentWorkingSet.isNotEmpty()) {
            process.currentWorkingSet.random(rng)
        } else {
            pickOutsidePage(process)
        }

        return TraceEvent.MemoryAccess(
            step = step,
            pid = process.pid,
            pageIndex = targetPage,
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

    private fun createProcessStates(): List<ProcessState> {
        val states = mutableListOf<ProcessState>()
        var nextStartCandidate = 0
        repeat(config.processCount) { index ->
            val pid = index
            val lifetimeFraction = rng.nextDouble(config.minLifetimeFraction, config.maxLifetimeFraction)
            val accessesForProcess = max(1, (config.totalCpuAccesses * lifetimeFraction / config.processCount).toInt())
            val duration = max(accessesForProcess, config.workingSetChangeInterval * 2)
            val startUpper = max(nextStartCandidate + 1, config.totalCpuAccesses / 2)
            val start = if (index == 0) 0 else rng.nextInt(nextStartCandidate, startUpper)
            val end = start + duration
            nextStartCandidate = min(config.totalCpuAccesses - 1, start + config.workingSetChangeInterval)
            val workingSet = pickWorkingSet(config.virtualPagesPerProcess)

            states += ProcessState(
                pid = pid,
                virtualPages = config.virtualPagesPerProcess,
                startStep = start,
                endStep = end,
                currentWorkingSet = workingSet,
            )
        }

        return states.sortedBy { it.startStep }
    }

    private fun eventOrder(event: TraceEvent): Int = when (event) {
        is TraceEvent.ProcessStart -> 0
        is TraceEvent.WorkingSetChange -> 1
        is TraceEvent.MemoryAccess -> 2
        is TraceEvent.ProcessTerminate -> 3
    }
}

/** Internal per-process generator state. */
private data class ProcessState(
    val pid: Int,
    val virtualPages: Int,
    val startStep: Int,
    val endStep: Int,
    var currentWorkingSet: MutableSet<Int>,
    var accessesSinceWsChange: Int = 0,
    var started: Boolean = false,
    var terminated: Boolean = false,
)
