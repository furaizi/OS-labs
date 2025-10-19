package org.example.workload

/**
 * Base class for all events emitted by the workload generator.
 * Events are strictly ordered by the step number.
 */
sealed interface TraceEvent {
    val step: Int

    data class ProcessStart(
        override val step: Int,
        val pid: Int,
        val virtualPageCount: Int,
    ) : TraceEvent

    data class ProcessTerminate(
        override val step: Int,
        val pid: Int,
    ) : TraceEvent

    data class WorkingSetChange(
        override val step: Int,
        val pid: Int,
        val newWorkingSet: Set<Int>,
    ) : TraceEvent

    data class MemoryAccess(
        override val step: Int,
        val pid: Int,
        val pageIndex: Int,
        val isWrite: Boolean,
        val currentWorkingSet: Set<Int>,
    ) : TraceEvent
}
