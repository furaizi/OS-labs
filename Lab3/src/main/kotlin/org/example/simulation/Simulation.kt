package org.example.simulation

import org.example.workload.TraceEvent
import org.example.workload.WorkloadTrace

data class SimulationConfig(
    val physicalPageCount: Int,
)

enum class AlgorithmType(val displayName: String) {
    RANDOM("Random"),
    CLOCK("Clock"),
}

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
    fun render(): String {
        val builder = StringBuilder()
        builder.appendLine("Algorithm: ${algorithm.displayName}")
        builder.appendLine("Physical frames: $physicalPages")
        builder.appendLine(
            "Accesses: $totalAccesses | Page faults: $pageFaults | Fault rate: ${"%.2f".format(pageFaultRate * 100)}%",
        )
        builder.appendLine(
            "Faults to free frames: $freeFrameFaults | Replacements: $replacements | Disk writes: $diskWrites | Evictions (clean/dirty): $cleanEvictions/$dirtyEvictions",
        )
        builder.appendLine("Working set changes observed: $workingSetChanges")
        builder.appendLine("Per-process statistics:")
        perProcess.forEach {
            builder.appendLine(
                "  PID ${it.pid}: accesses=${it.accesses}, faults=${it.pageFaults}, writes=${it.writeCount}, dirtyWrites=${it.dirtyEvictions}",
            )
        }
        if (samplePageFaults.isNotEmpty()) {
            builder.appendLine("Sample page fault decisions:")
            samplePageFaults.forEach { rec ->
                builder.appendLine(
                    "  [${rec.step}] PID=${rec.pid}, page=${rec.pageIndex}, victim=${rec.victim?.describe() ?: "free frame"}, dirtyWrite=${rec.victim?.wasDirty}",
                )
            }
        }
        return builder.toString()
    }
}

data class ProcessStats(
    val pid: Int,
    var accesses: Int = 0,
    var pageFaults: Int = 0,
    var writeCount: Int = 0,
    var dirtyEvictions: Int = 0,
)

data class PageFaultRecord(
    val step: Int,
    val pid: Int,
    val pageIndex: Int,
    val victim: VictimInfo?,
)

data class VictimInfo(
    val pid: Int,
    val pageIndex: Int,
    val wasDirty: Boolean,
) {
    fun describe(): String = "pid=$pid,page=$pageIndex"
}

data class PageTableEntry(
    var present: Boolean = false,
    var reference: Boolean = false,
    var dirty: Boolean = false,
    var frame: PhysicalFrame? = null,
    var ownerStats: ProcessStats? = null,
)

data class PhysicalFrame(
    val index: Int,
    var ownerPid: Int? = null,
    var virtualPage: Int? = null,
    var reference: Boolean = false,
    var dirty: Boolean = false,
    var pageTableEntry: PageTableEntry? = null,
) {
    fun isFree(): Boolean = ownerPid == null

    fun clear() {
        ownerPid = null
        virtualPage = null
        reference = false
        dirty = false
        pageTableEntry = null
    }
}

private class ProcessRuntime(
    val pid: Int,
    val virtualPages: Int,
) {
    val pageTable: MutableList<PageTableEntry> = MutableList(virtualPages) { PageTableEntry() }
    val stats = ProcessStats(pid = pid)
}

class Kernel(
    private val config: SimulationConfig,
    private val policyFactory: (List<PhysicalFrame>) -> PageReplacementPolicy,
) {
    fun run(trace: WorkloadTrace, algorithm: AlgorithmType): SimulationSummary {
        val frames = MutableList(config.physicalPageCount) { PhysicalFrame(it) }
        val policy = policyFactory(frames)
        val runtimeByPid = mutableMapOf<Int, ProcessRuntime>()
        val freeFrames = ArrayDeque(frames)
        val stats = OverallStats(frames.size)
        val pageFaultSamples = mutableListOf<PageFaultRecord>()

        trace.events.sortedBy { it.step }.forEach { event ->
            when (event) {
                is TraceEvent.ProcessStart -> {
                    runtimeByPid[event.pid] = ProcessRuntime(event.pid, event.virtualPageCount)
                }

                is TraceEvent.ProcessTerminate -> {
                    runtimeByPid[event.pid]?.let { proc ->
                        releaseProcessFrames(proc, freeFrames, policy, stats)
                    }
                    runtimeByPid.remove(event.pid)
                }

                is TraceEvent.WorkingSetChange -> {
                    stats.workingSetChanges++
                }

                is TraceEvent.MemoryAccess -> {
                    val proc = runtimeByPid[event.pid]
                    if (proc != null) {
                        handleAccess(
                            process = proc,
                            event = event,
                            policy = policy,
                            frames = frames,
                            freeFrames = freeFrames,
                            stats = stats,
                            step = event.step,
                            samples = pageFaultSamples,
                        )
                    }
                }
            }
        }

        val totalAccesses = stats.totalAccesses
        val pageFaultRate = if (totalAccesses == 0) 0.0 else stats.pageFaults.toDouble() / totalAccesses
        val perProcess = runtimeByPid.values.map { it.stats } + stats.completedProcessStats.values
        return SimulationSummary(
            algorithm = algorithm,
            physicalPages = frames.size,
            totalAccesses = totalAccesses,
            pageFaults = stats.pageFaults,
            pageFaultRate = pageFaultRate,
            freeFrameFaults = stats.freeFrameFaults,
            replacements = stats.replacements,
            diskWrites = stats.diskWrites,
            cleanEvictions = stats.cleanEvictions,
            dirtyEvictions = stats.dirtyEvictions,
            workingSetChanges = stats.workingSetChanges,
            perProcess = perProcess.sortedBy { it.pid },
            samplePageFaults = pageFaultSamples,
        )
    }

    private fun handleAccess(
        process: ProcessRuntime,
        event: TraceEvent.MemoryAccess,
        policy: PageReplacementPolicy,
        frames: MutableList<PhysicalFrame>,
        freeFrames: ArrayDeque<PhysicalFrame>,
        stats: OverallStats,
        step: Int,
        samples: MutableList<PageFaultRecord>,
    ) {
        val pte = process.pageTable[event.pageIndex]
        process.stats.accesses++
        stats.totalAccesses++
        if (event.isWrite) {
            process.stats.writeCount++
        }

        if (pte.present) {
            val frame = pte.frame ?: error("Present PTE without frame")
            frame.reference = true
            frame.dirty = frame.dirty || event.isWrite
            frame.pageTableEntry?.reference = true
            frame.pageTableEntry?.dirty = frame.dirty
            policy.onFrameAccess(frame)
            return
        }

        // Page fault handling.
        process.stats.pageFaults++
        stats.pageFaults++
        val (frame, victimInfo) = if (freeFrames.isNotEmpty()) {
            stats.freeFrameFaults++
            freeFrames.removeFirst() to null
        } else {
            val victim = policy.chooseVictim()
            val info = evictFrame(victim, policy, stats)
            stats.replacements++
            victim to info
        }

        if (samples.size < 25) {
            samples += PageFaultRecord(
                step = step,
                pid = process.pid,
                pageIndex = event.pageIndex,
                victim = victimInfo,
            )
        }

        loadPage(process, event, frame, policy)
    }

    private fun loadPage(
        process: ProcessRuntime,
        event: TraceEvent.MemoryAccess,
        frame: PhysicalFrame,
        policy: PageReplacementPolicy,
    ) {
        val pte = process.pageTable[event.pageIndex]
        frame.ownerPid = process.pid
        frame.virtualPage = event.pageIndex
        frame.reference = true
        frame.dirty = event.isWrite
        frame.pageTableEntry = pte

        pte.present = true
        pte.reference = true
        pte.dirty = event.isWrite
        pte.frame = frame
        pte.ownerStats = process.stats

        policy.onFrameLoaded(frame)
    }

    private fun releaseProcessFrames(
        process: ProcessRuntime,
        freeFrames: ArrayDeque<PhysicalFrame>,
        policy: PageReplacementPolicy,
        stats: OverallStats,
    ) {
        process.pageTable.forEach { pte ->
            if (pte.present) {
                val frame = pte.frame ?: return@forEach
                if (frame.dirty) {
                    stats.diskWrites++
                    process.stats.dirtyEvictions++
                    stats.dirtyEvictions++
                } else {
                    stats.cleanEvictions++
                }
                policy.onFrameFreed(frame)
                frame.clear()
                pte.present = false
                pte.reference = false
                pte.dirty = false
                pte.frame = null
                pte.ownerStats = null
                freeFrames.add(frame)
            }
        }
        stats.completedProcessStats[process.pid] = process.stats.copy()
    }

    private fun evictFrame(
        frame: PhysicalFrame,
        policy: PageReplacementPolicy,
        stats: OverallStats,
    ): VictimInfo? {
        val victimPid = frame.ownerPid
        val victimPage = frame.virtualPage
        val dirty = frame.dirty

        if (dirty) {
            stats.diskWrites++
            stats.dirtyEvictions++
        } else {
            stats.cleanEvictions++
        }

        frame.pageTableEntry?.let { entry ->
            if (dirty) {
                entry.ownerStats?.let { stats -> stats.dirtyEvictions++ }
            }
            entry.present = false
            entry.reference = false
            entry.dirty = false
            entry.frame = null
            entry.ownerStats = null
        }
        val info = if (victimPid != null && victimPage != null) {
            VictimInfo(pid = victimPid, pageIndex = victimPage, wasDirty = dirty)
        } else {
            null
        }
        frame.clear()
        policy.onFrameFreed(frame)
        return info
    }
}

interface PageReplacementPolicy {
    fun onFrameAccess(frame: PhysicalFrame) = Unit
    fun onFrameLoaded(frame: PhysicalFrame) = Unit
    fun onFrameFreed(frame: PhysicalFrame) = Unit
    fun chooseVictim(): PhysicalFrame
}

class RandomReplacementPolicy(private val frames: List<PhysicalFrame>) : PageReplacementPolicy {
    private val rng = java.util.Random(0L)

    override fun chooseVictim(): PhysicalFrame {
        val occupied = frames.filter { !it.isFree() }
        require(occupied.isNotEmpty()) { "No occupied frames to evict" }
        return occupied[rng.nextInt(occupied.size)]
    }
}

class ClockReplacementPolicy(private val frames: List<PhysicalFrame>) : PageReplacementPolicy {
    private var hand: Int = 0

    override fun chooseVictim(): PhysicalFrame {
        val total = frames.size
        var iterations = 0
        while (iterations < total * 2) { // safety bound
            val frame = frames[hand]
            hand = (hand + 1) % total

            if (frame.isFree()) {
                iterations++
                continue
            }

            val referenced = frame.reference
            if (referenced) {
                frame.reference = false
                frame.pageTableEntry?.reference = false
                iterations++
            } else {
                return frame
            }
        }
        // Fallback: choose the first occupied frame if all were referenced.
        return frames.first { !it.isFree() }
    }
}

private data class OverallStats(
    val physicalFrames: Int,
    var totalAccesses: Int = 0,
    var pageFaults: Int = 0,
    var freeFrameFaults: Int = 0,
    var replacements: Int = 0,
    var diskWrites: Int = 0,
    var cleanEvictions: Int = 0,
    var dirtyEvictions: Int = 0,
    var workingSetChanges: Int = 0,
    val completedProcessStats: MutableMap<Int, ProcessStats> = mutableMapOf(),
)
