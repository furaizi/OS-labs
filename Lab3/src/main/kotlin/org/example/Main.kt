package org.example

import org.example.simulation.AlgorithmType
import org.example.simulation.ClockReplacementPolicy
import org.example.simulation.Kernel
import org.example.simulation.RandomReplacementPolicy
import org.example.simulation.SimulationConfig
import org.example.workload.WorkloadConfig
import org.example.workload.WorkloadGenerator
import kotlin.system.exitProcess

private data class CliConfig(
    val physicalFrames: Int = 8,
    val processCount: Int = 3,
    val virtualPagesPerProcess: Int = 16,
    val workingSetSizes: List<Int> = listOf(3, 6, 9),
    val workingSetChangeInterval: Int = 25,
    val totalCpuAccesses: Int = 2000,
    val localityProbability: Double = 0.9,
    val writeProbability: Double = 0.25,
    val randomSeed: Long = 42L,
)

fun main(args: Array<String>) {
    val cli = try {
        parseArgs(args)
    } catch (ex: IllegalArgumentException) {
        println(ex.message)
        printUsage()
        exitProcess(1)
    }

    println("Page replacement laboratory | physical frames=${cli.physicalFrames} | total accesses=${cli.totalCpuAccesses}")
    println("Working set sizes to evaluate: ${cli.workingSetSizes.joinToString()}")
    println()

    cli.workingSetSizes.forEach { workingSetSize ->
        val workloadConfig = WorkloadConfig(
            processCount = cli.processCount,
            virtualPagesPerProcess = cli.virtualPagesPerProcess,
            workingSetSize = workingSetSize,
            workingSetChangeInterval = cli.workingSetChangeInterval,
            totalCpuAccesses = cli.totalCpuAccesses,
            localityProbability = cli.localityProbability,
            writeProbability = cli.writeProbability,
            randomSeed = cli.randomSeed,
        )
        val trace = WorkloadGenerator(workloadConfig).generate()
        val simConfig = SimulationConfig(physicalPageCount = cli.physicalFrames)

        println("=== Working set size: $workingSetSize ===")

        val randomSummary = Kernel(simConfig) { frames -> RandomReplacementPolicy(frames) }
            .run(trace, AlgorithmType.RANDOM)
        println(randomSummary.render())

        val clockSummary = Kernel(simConfig) { frames -> ClockReplacementPolicy(frames) }
            .run(trace, AlgorithmType.CLOCK)
        println(clockSummary.render())

        if (randomSummary.totalAccesses > 0) {
            val improvement = (randomSummary.pageFaults - clockSummary.pageFaults).toDouble() /
                randomSummary.totalAccesses * 100
            println(
                "Clock vs Random: page faults ${clockSummary.pageFaults} vs ${randomSummary.pageFaults}, " +
                    "delta=${randomSummary.pageFaults - clockSummary.pageFaults}, " +
                    "improvement=${"%.2f".format(improvement)}% of accesses",
            )
        }
        println()
    }

    println("Adjust parameters via CLI flags (use --help) to explore behaviour under different workloads.")
}

private fun parseArgs(args: Array<String>): CliConfig {
    if (args.contains("--help")) {
        printUsage()
        exitProcess(0)
    }

    var cfg = CliConfig()
    args.forEach { arg ->
        if (!arg.startsWith("--")) {
            throw IllegalArgumentException("Unexpected argument: $arg")
        }
        val (key, rawValue) = arg.removePrefix("--").split("=", limit = 2).let {
            if (it.size != 2) throw IllegalArgumentException("Expected --key=value format, got: $arg")
            it[0] to it[1]
        }
        when (key.lowercase()) {
            "physical-frames" -> cfg = cfg.copy(physicalFrames = rawValue.toInt())
            "processes" -> cfg = cfg.copy(processCount = rawValue.toInt())
            "virtual-pages" -> cfg = cfg.copy(virtualPagesPerProcess = rawValue.toInt())
            "working-set-sizes" -> cfg = cfg.copy(
                workingSetSizes = rawValue.split(",").map { it.trim().toInt() }.filter { it > 0 },
            )
            "working-set-change" -> cfg = cfg.copy(workingSetChangeInterval = rawValue.toInt())
            "total-accesses" -> cfg = cfg.copy(totalCpuAccesses = rawValue.toInt())
            "locality" -> cfg = cfg.copy(localityProbability = rawValue.toDouble())
            "write-prob" -> cfg = cfg.copy(writeProbability = rawValue.toDouble())
            "seed" -> cfg = cfg.copy(randomSeed = rawValue.toLong())
            else -> throw IllegalArgumentException("Unknown option: --$key")
        }
    }

    if (cfg.physicalFrames <= 0) {
        throw IllegalArgumentException("physical-frames must be positive")
    }
    if (cfg.virtualPagesPerProcess <= 0) {
        throw IllegalArgumentException("virtual-pages must be positive")
    }
    if (cfg.workingSetSizes.isEmpty()) {
        throw IllegalArgumentException("working-set-sizes must contain at least one positive value")
    }
    if (cfg.workingSetSizes.any { it > cfg.virtualPagesPerProcess }) {
        throw IllegalArgumentException("working-set size cannot exceed the virtual page count")
    }
    if (cfg.workingSetChangeInterval <= 0) {
        throw IllegalArgumentException("working-set-change must be positive")
    }
    if (cfg.totalCpuAccesses <= 0) {
        throw IllegalArgumentException("total-accesses must be positive")
    }
    if (cfg.localityProbability !in 0.0..1.0) {
        throw IllegalArgumentException("locality must be within [0, 1]")
    }
    if (cfg.writeProbability !in 0.0..1.0) {
        throw IllegalArgumentException("write-prob must be within [0, 1]")
    }

    return cfg
}

private fun printUsage() {
    println(
        """
        Usage: java -jar Lab3.jar [options]
        Options (all use --key=value):
          --physical-frames=N          Number of physical frames (default 8)
          --processes=N                Number of concurrent processes (default 3)
          --virtual-pages=N            Virtual pages per process (default 16)
          --working-set-sizes=a,b,c    Working set sizes to evaluate (default 3,6,9)
          --working-set-change=N       Accesses between working set changes (default 25)
          --total-accesses=N           Total CPU memory accesses to generate (default 2000)
          --locality=P                 Probability of hitting the working set (default 0.9)
          --write-prob=P               Probability that an access is a write (default 0.25)
          --seed=N                     Seed for workload generation (default 42)
          --help                       Show this message
        """.trimIndent(),
    )
}
