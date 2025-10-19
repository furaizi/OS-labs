package org.example.simulation

import org.example.workload.TraceEvent
import org.example.workload.WorkloadTrace

private const val MAX_PAGE_FAULT_SAMPLES = 25

/**
 * Describes the physical memory layout that the paging kernel should emulate.
 */
data class SimulationConfig(
    val physicalPageCount: Int,
)

/**
 * Page replacement algorithms supported by the laboratory work.
 */
enum class AlgorithmType(val displayName: String) {
    RANDOM("Random"),
    CLOCK("Clock"),
}

/**
 * Summary of a single simulation run. The [render] function mirrors the original textual report.
 */
data class SimulationSummary(
    val algorithm: AlgorithmType,
    val physicalPages: Int,
    val totalAccesses: Int,
    val pageFaults: Int,
    val pageFaultRate: Double,
    val freeFrameFaults: Int,
    val replacements: Int,
    val diskWrites: Int,
    val cleanEvictions: Int,
    val dirtyEvictions: Int,
    val workingSetChanges: Int,
    val perProcess: List<ProcessStats>,
    val samplePageFaults: List<PageFaultRecord>,
) {
    /**
     * Produces the legacy textual report detailing the simulation results.
     */
    fun render(): String = buildString {
        appendLine("Algorithm: ${algorithm.displayName}")
        appendLine("Physical frames: $physicalPages")
        appendLine(
            "Accesses: $totalAccesses | Page faults: $pageFaults | Fault rate: ${
                "%.2f".format(pageFaultRate * 100)
            }%",
        )
        appendLine(
            "Faults to free frames: $freeFrameFaults | Replacements: $replacements | Disk writes: $diskWrites | Evictions (clean/dirty): $cleanEvictions/$dirtyEvictions",
        )
        appendLine("Working set changes observed: $workingSetChanges")
        appendLine("Per-process statistics:")
        perProcess.forEach { stats ->
            appendLine(
                "  PID ${stats.pid}: accesses=${stats.accesses}, faults=${stats.pageFaults}, writes=${stats.writeCount}, dirtyWrites=${stats.dirtyEvictions}",
            )
        }
        if (samplePageFaults.isNotEmpty()) {
            appendLine("Sample page fault decisions:")
            samplePageFaults.forEach { record ->
                appendLine(
                    "  [${record.step}] PID=${record.pid}, page=${record.pageIndex}, victim=${record.victim?.describe() ?: "free frame"}, dirtyWrite=${record.victim?.wasDirty}",
                )
            }
        }
    }
}

/**
 * Per-process counters that are aggregated across the simulation.
 */
data class ProcessStats(
    val pid: Int,
    var accesses: Int = 0,
    var pageFaults: Int = 0,
    var writeCount: Int = 0,
    var dirtyEvictions: Int = 0,
)

/**
 * Captures details about a specific page fault for reporting purposes.
 */
data class PageFaultRecord(
    val step: Int,
    val pid: Int,
    val pageIndex: Int,
    val victim: VictimInfo?,
)

/**
 * Describes the victim page that was evicted during a page fault.
 */
data class VictimInfo(
    val pid: Int,
    val pageIndex: Int,
    val wasDirty: Boolean,
) {
    fun describe(): String = "pid=$pid,page=$pageIndex"
}

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
        if (event.isWrite) {
            process.stats.writeCount++
        }

        if (entry.present) {
            entry.frame?.let { frame ->
                frame.noteAccess(event.isWrite)
                policy.onFrameAccess(frame)
            }
            return
        }

        process.stats.pageFaults++
        counters.pageFaults++

        val (frame, victim) = memory.allocate()?.let { freeFrame ->
            counters.freeFrameFaults++
            freeFrame to null
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

typealias ReplacementPolicyFactory = (MutableList<PhysicalFrame>) -> PageReplacementPolicy

private class ProcessContext(
    val pid: Int,
    virtualPages: Int,
) {
    val pageTable: MutableList<PageTableEntry> = MutableList(virtualPages) { PageTableEntry() }
    val stats: ProcessStats = ProcessStats(pid = pid)
}

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
            if (dirty) {
                entry.ownerStats?.let { stats -> stats.dirtyEvictions++ }
            }
            entry.clearMapping()
        }

        frame.markFree()
        policy.onFrameFreed(frame)

        return if (victimPid != null && victimPage != null) {
            VictimInfo(pid = victimPid, pageIndex = victimPage, wasDirty = dirty)
        } else {
            null
        }
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

/**
 * Mutable descriptor of a single page table entry.
 */
class PageTableEntry {
    var present: Boolean = false
    var reference: Boolean = false
    var dirty: Boolean = false
    var frame: PhysicalFrame? = null
    var ownerStats: ProcessStats? = null

    fun attach(frame: PhysicalFrame, stats: ProcessStats, isWrite: Boolean) {
        present = true
        reference = true
        dirty = isWrite
        this.frame = frame
        ownerStats = stats
    }

    fun clearMapping() {
        present = false
        reference = false
        dirty = false
        frame = null
        ownerStats = null
    }
}

/**
 * Mutable descriptor of a physical frame that keeps track of ownership and attributes.
 */
class PhysicalFrame(
    val index: Int,
) {
    var ownerPid: Int? = null
        private set
    var virtualPage: Int? = null
        private set
    var reference: Boolean = false
        private set
    var dirty: Boolean = false
        private set
    var pageTableEntry: PageTableEntry? = null
        private set

    fun isFree(): Boolean = ownerPid == null

    fun attach(pid: Int, pageIndex: Int, entry: PageTableEntry, isWrite: Boolean) {
        ownerPid = pid
        virtualPage = pageIndex
        reference = true
        dirty = isWrite
        pageTableEntry = entry
    }

    fun noteAccess(isWrite: Boolean) {
        reference = true
        if (isWrite) {
            dirty = true
        }
        pageTableEntry?.let { entry ->
            entry.reference = true
            if (isWrite) {
                entry.dirty = true
            }
        }
    }

    fun clearReference() {
        reference = false
        pageTableEntry?.reference = false
    }

    fun markFree() {
        ownerPid = null
        virtualPage = null
        reference = false
        dirty = false
        pageTableEntry = null
    }
}

/**
 * Strategy interface for page replacement algorithms.
 */
interface PageReplacementPolicy {
    fun onFrameAccess(frame: PhysicalFrame) = Unit
    fun onFrameLoaded(frame: PhysicalFrame) = Unit
    fun onFrameFreed(frame: PhysicalFrame) = Unit
    fun chooseVictim(): PhysicalFrame
}

/**
 * Random replacement policy used as the baseline algorithm.
 */
class RandomReplacementPolicy(
    private val frames: List<PhysicalFrame>,
) : PageReplacementPolicy {
    private val rng = java.util.Random(0L)

    override fun chooseVictim(): PhysicalFrame {
        val occupied = frames.filter { !it.isFree() }
        require(occupied.isNotEmpty()) { "No occupied frames available for eviction." }
        return occupied[rng.nextInt(occupied.size)]
    }
}

/**
 * Clock replacement policy (second-chance) implementation.
 */
class ClockReplacementPolicy(
    private val frames: List<PhysicalFrame>,
) : PageReplacementPolicy {
    private var hand: Int = 0

    override fun chooseVictim(): PhysicalFrame {
        val total = frames.size
        var iterations = 0
        while (iterations < total * 2) {
            val frame = frames[hand]
            hand = (hand + 1) % total
            iterations++

            if (frame.isFree()) {
                continue
            }

            if (frame.reference) {
                frame.clearReference()
                continue
            }

            return frame
        }
        return frames.first { !it.isFree() }
    }
}
