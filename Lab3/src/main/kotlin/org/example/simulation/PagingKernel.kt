package org.example.simulation

import org.example.workload.TraceEvent
import org.example.workload.WorkloadTrace

private const val MAX_PAGE_FAULT_SAMPLES = 25

/**
 * Executes a single algorithm against a trace while tracking detailed statistics.
 */
class PagingKernel(
    private val config: SimulationConfig,
    private val policyFactory: ReplacementPolicyFactory,
) {
    fun run(trace: WorkloadTrace, algorithm: AlgorithmType): SimulationSummary {
        val memory = MemoryState(config.physicalPageCount)
        val policy = policyFactory(memory.frames)
        val processes = mutableMapOf<Int, ProcessContext>()
        val counters = Counters(config.physicalPageCount)
        val pageFaultSamples = mutableListOf<PageFaultRecord>()

        trace.events.forEach { event ->
            when (event) {
                is TraceEvent.ProcessStart -> {
                    processes[event.pid] = ProcessContext(event.pid, event.virtualPageCount)
                }
                is TraceEvent.ProcessTerminate -> {
                    processes.remove(event.pid)?.let { context ->
                        memory.releaseProcess(context, policy, counters)
                    }
                }
                is TraceEvent.WorkingSetChange -> {
                    counters.workingSetChanges++
                }
                is TraceEvent.MemoryAccess -> {
                    val process = processes[event.pid] ?: return@forEach
                    handleAccess(
                        process = process,
                        event = event,
                        memory = memory,
                        policy = policy,
                        counters = counters,
                        samples = pageFaultSamples,
                    )
                }
            }
        }

        val totalAccesses = counters.totalAccesses
        val pageFaultRate = if (totalAccesses == 0) 0.0 else counters.pageFaults.toDouble() / totalAccesses
        val perProcessStats = processes.values.map { it.stats } + counters.completedProcessStats.values

        return SimulationSummary(
            algorithm = algorithm,
            physicalPages = memory.frames.size,
            totalAccesses = totalAccesses,
            pageFaults = counters.pageFaults,
            pageFaultRate = pageFaultRate,
            freeFrameFaults = counters.freeFrameFaults,
            replacements = counters.replacements,
            diskWrites = counters.diskWrites,
            cleanEvictions = counters.cleanEvictions,
            dirtyEvictions = counters.dirtyEvictions,
            workingSetChanges = counters.workingSetChanges,
            perProcess = perProcessStats.sortedBy { it.pid },
            samplePageFaults = pageFaultSamples,
        )
    }

    private fun handleAccess(
        process: ProcessContext,
        event: TraceEvent.MemoryAccess,
        memory: MemoryState,
        policy: PageReplacementPolicy,
        counters: Counters,
        samples: MutableList<PageFaultRecord>,
    ) {
        val entry = process.pageTable[event.pageIndex]
        process.stats.accesses++
        counters.totalAccesses++
        if (event.isWrite) process.stats.writeCount++

        if (entry.present) {
            entry.frame?.let { frame ->
                frame.noteAccess(event.isWrite)
                policy.onFrameAccess(frame)
            }
            return
        }

        process.stats.pageFaults++
        counters.pageFaults++

        val (frame, victim) = memory.allocate()?.let { free ->
            counters.freeFrameFaults++
            free to null
        } ?: run {
            val victimFrame = policy.chooseVictim()
            val victimInfo = memory.evictFrame(victimFrame, policy, counters)
            counters.replacements++
            victimFrame to victimInfo
        }

        if (samples.size < MAX_PAGE_FAULT_SAMPLES) {
            samples += PageFaultRecord(
                step = event.step,
                pid = process.pid,
                pageIndex = event.pageIndex,
                victim = victim,
            )
        }

        memory.loadPage(process, event, frame, policy)
    }
}

/** Internal process context used by the kernel. */
private class ProcessContext(
    val pid: Int,
    virtualPages: Int,
) {
    val pageTable: MutableList<PageTableEntry> = MutableList(virtualPages) { PageTableEntry() }
    val stats: ProcessStats = ProcessStats(pid = pid)
}

/** In-memory state and helpers for allocation/eviction. */
private class MemoryState(frameCount: Int) {
    val frames: MutableList<PhysicalFrame> = MutableList(frameCount) { PhysicalFrame(it) }
    private val freeFrames: ArrayDeque<PhysicalFrame> = ArrayDeque(frames)

    fun allocate(): PhysicalFrame? = if (freeFrames.isEmpty()) null else freeFrames.removeFirst()

    fun releaseProcess(
        process: ProcessContext,
        policy: PageReplacementPolicy,
        counters: Counters,
    ) {
        process.pageTable.forEach { entry ->
            if (!entry.present) return@forEach
            val frame = entry.frame ?: return@forEach
            if (frame.dirty) {
                counters.diskWrites++
                process.stats.dirtyEvictions++
                counters.dirtyEvictions++
            } else {
                counters.cleanEvictions++
            }
            policy.onFrameFreed(frame)
            frame.markFree()
            entry.clearMapping()
            freeFrames.addLast(frame)
        }
        counters.completedProcessStats[process.pid] = process.stats.copy()
    }

    fun evictFrame(
        frame: PhysicalFrame,
        policy: PageReplacementPolicy,
        counters: Counters,
    ): VictimInfo? {
        val victimPid = frame.ownerPid
        val victimPage = frame.virtualPage
        val dirty = frame.dirty

        if (dirty) {
            counters.diskWrites++
            counters.dirtyEvictions++
        } else {
            counters.cleanEvictions++
        }

        frame.pageTableEntry?.let { entry ->
            if (dirty) entry.ownerStats?.let { s -> s.dirtyEvictions++ }
            entry.clearMapping()
        }

        frame.markFree()
        policy.onFrameFreed(frame)

        return if (victimPid != null && victimPage != null) {
            VictimInfo(pid = victimPid, pageIndex = victimPage, wasDirty = dirty)
        } else null
    }

    fun loadPage(
        process: ProcessContext,
        event: TraceEvent.MemoryAccess,
        frame: PhysicalFrame,
        policy: PageReplacementPolicy,
    ) {
        val entry = process.pageTable[event.pageIndex]
        frame.attach(
            pid = process.pid,
            pageIndex = event.pageIndex,
            entry = entry,
            isWrite = event.isWrite,
        )
        entry.attach(frame, process.stats, event.isWrite)
        policy.onFrameLoaded(frame)
    }
}

/** Aggregated counters for a run. */
private class Counters(
    val physicalFrames: Int,
) {
    var totalAccesses: Int = 0
    var pageFaults: Int = 0
    var freeFrameFaults: Int = 0
    var replacements: Int = 0
    var diskWrites: Int = 0
    var cleanEvictions: Int = 0
    var dirtyEvictions: Int = 0
    var workingSetChanges: Int = 0
    val completedProcessStats: MutableMap<Int, ProcessStats> = mutableMapOf()
}
