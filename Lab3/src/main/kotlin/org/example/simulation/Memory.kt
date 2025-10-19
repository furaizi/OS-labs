package org.example.simulation

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
        if (isWrite) dirty = true
        pageTableEntry?.let { entry ->
            entry.reference = true
            if (isWrite) entry.dirty = true
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
