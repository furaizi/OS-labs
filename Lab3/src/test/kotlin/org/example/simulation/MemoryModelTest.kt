package org.example.simulation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class MemoryModelTest : FunSpec({

    test("PageTableEntry attach and clearMapping should toggle flags correctly") {
        val entry = PageTableEntry()
        val stats = ProcessStats(pid = 1)
        val frame = PhysicalFrame(index = 0)

        frame.attach(pid = 1, pageIndex = 7, entry = entry, isWrite = true)
        entry.attach(frame, stats, isWrite = true)

        entry.present shouldBe true
        entry.reference shouldBe true
        entry.dirty shouldBe true
        entry.frame shouldBe frame
        entry.ownerStats shouldBe stats

        entry.clearMapping()

        entry.present shouldBe false
        entry.reference shouldBe false
        entry.dirty shouldBe false
        entry.frame.shouldBeNull()
        entry.ownerStats.shouldBeNull()
    }

    test("noteAccess should mark frame and page table entry as referenced and dirty when needed") {
        val entry = PageTableEntry()
        val stats = ProcessStats(pid = 2)
        val frame = PhysicalFrame(index = 1)

        frame.attach(pid = 2, pageIndex = 4, entry = entry, isWrite = false)
        entry.attach(frame, stats, isWrite = false)

        frame.noteAccess(isWrite = true)

        frame.reference shouldBe true
        frame.dirty shouldBe true
        entry.reference shouldBe true
        entry.dirty shouldBe true
    }

    test("clearReference should reset both frame and page table entry flags") {
        val entry = PageTableEntry()
        val stats = ProcessStats(pid = 3)
        val frame = PhysicalFrame(index = 2)

        frame.attach(pid = 3, pageIndex = 9, entry = entry, isWrite = false)
        entry.attach(frame, stats, isWrite = false)
        frame.noteAccess(isWrite = false)

        frame.clearReference()

        frame.reference shouldBe false
        entry.reference shouldBe false
    }

    test("markFree should remove ownership details from the frame") {
        val entry = PageTableEntry()
        val stats = ProcessStats(pid = 4)
        val frame = PhysicalFrame(index = 3)

        frame.attach(pid = 4, pageIndex = 5, entry = entry, isWrite = true)
        entry.attach(frame, stats, isWrite = true)

        frame.markFree()

        frame.ownerPid.shouldBeNull()
        frame.virtualPage.shouldBeNull()
        frame.pageTableEntry.shouldBeNull()
        frame.reference shouldBe false
        frame.dirty shouldBe false
    }
})
