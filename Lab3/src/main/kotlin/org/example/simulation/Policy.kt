package org.example.simulation

/**
 * Strategy interface for page replacement algorithms.
 */
interface PageReplacementPolicy {
    fun onFrameAccess(frame: PhysicalFrame) = Unit
    fun onFrameLoaded(frame: PhysicalFrame) = Unit
    fun onFrameFreed(frame: PhysicalFrame) = Unit
    fun chooseVictim(): PhysicalFrame
}

/** Factory for policies, given the frame list. */
typealias ReplacementPolicyFactory = (MutableList<PhysicalFrame>) -> PageReplacementPolicy

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

            if (frame.isFree()) continue
            if (frame.reference) {
                frame.clearReference()
                continue
            }
            return frame
        }
        return frames.first { !it.isFree() }
    }
}
