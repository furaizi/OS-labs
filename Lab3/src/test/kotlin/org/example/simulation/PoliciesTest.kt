package org.example.simulation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.shouldBe

class PoliciesTest : FunSpec({

    test("RandomReplacementPolicy should only return occupied frames") {
        val frames = mutableListOf(PhysicalFrame(0), PhysicalFrame(1))
        val entry = PageTableEntry()
        val stats = ProcessStats(pid = 1)

        frames[0].attach(pid = 1, pageIndex = 0, entry = entry, isWrite = false)
        entry.attach(frames[0], stats, isWrite = false)

        val policy = RandomReplacementPolicy(frames)

        repeat(10) {
            val victim = policy.chooseVictim()
            victim.isFree().shouldBeFalse()
            victim shouldBe frames[0]
        }
    }

    test("RandomReplacementPolicy should throw when no occupied frames exist") {
        val frames = mutableListOf(PhysicalFrame(0), PhysicalFrame(1))
        val policy = RandomReplacementPolicy(frames)

        shouldThrow<IllegalArgumentException> {
            policy.chooseVictim()
        }
    }

    test("ClockReplacementPolicy should give second chances and wrap around") {
        val frames = mutableListOf(PhysicalFrame(0), PhysicalFrame(1), PhysicalFrame(2))
        val entries = List(3) { PageTableEntry() }
        val stats = List(3) { ProcessStats(pid = it) }

        frames.forEachIndexed { index, frame ->
            frame.attach(pid = index, pageIndex = index, entry = entries[index], isWrite = false)
            entries[index].attach(frame, stats[index], isWrite = false)
        }

        val policy = ClockReplacementPolicy(frames)

        // Clear reference only for the last frame; first two should be given a second chance.
        frames[2].clearReference()

        val firstVictim = policy.chooseVictim()
        firstVictim shouldBe frames[2]

        // After referencing frame 0 again, it should lose its second chance on the next pass.
        frames[0].noteAccess(isWrite = false)
        frames[1].clearReference()

        val secondVictim = policy.chooseVictim()
        secondVictim shouldBe frames[1]
        frames[0].reference shouldBe false
    }

    test("ClockReplacementPolicy should skip free frames") {
        val frames = mutableListOf(PhysicalFrame(0), PhysicalFrame(1))
        val entry = PageTableEntry()
        val stats = ProcessStats(pid = 0)

        frames[0].attach(pid = 0, pageIndex = 0, entry = entry, isWrite = false)
        entry.attach(frames[0], stats, isWrite = false)
        frames[1].markFree()

        val policy = ClockReplacementPolicy(frames)

        val victim = policy.chooseVictim()
        victim shouldBe frames[0]
    }
})
