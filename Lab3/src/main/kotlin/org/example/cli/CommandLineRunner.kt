package org.example.cli

import org.example.simulation.AlgorithmType
import org.example.simulation.PageReplacementExperiment
import org.example.simulation.SimulationConfig
import org.example.workload.WorkloadConfig
import org.example.workload.WorkloadGenerator
import kotlin.system.exitProcess

/**
 * Orchestrates the paging simulations based on command-line arguments.
 */
class CommandLineRunner(
    private val parser: CommandLineParser = CommandLineParser(),
    private val experimentFactory: (SimulationConfig) -> PageReplacementExperiment = { config ->
        PageReplacementExperiment(config)
    },
) {
    fun run(args: Array<String>) {
        if (args.contains("--help")) {
            println(parser.usage())
            return
        }

        val options = try {
            parser.parse(args)
        } catch (ex: CliParsingException) {
            println(ex.message)
            println(parser.usage())
            exitProcess(1)
        }

        println(
            "Page replacement laboratory | physical frames=${options.physicalFrames} | total accesses=${options.totalCpuAccesses}",
        )
        println("Working set sizes to evaluate: ${options.workingSetSizes.joinToString()}")
        println()

        options.workingSetSizes.forEach { workingSetSize ->
            val workloadConfig = options.toWorkloadConfig(workingSetSize)
            val trace = WorkloadGenerator(workloadConfig).generate()
            val experiment = experimentFactory(SimulationConfig(physicalPageCount = options.physicalFrames))
            val summaries = experiment.run(trace)
            val randomSummary = summaries.getValue(AlgorithmType.RANDOM)
            val clockSummary = summaries.getValue(AlgorithmType.CLOCK)

            println("=== Working set size: $workingSetSize ===")
            println(randomSummary.render())
            println(clockSummary.render())

            if (randomSummary.totalAccesses > 0) {
                val delta = randomSummary.pageFaults - clockSummary.pageFaults
                val improvement = delta.toDouble() / randomSummary.totalAccesses * 100
                println(
                    "Clock vs Random: page faults ${clockSummary.pageFaults} vs ${randomSummary.pageFaults}, " +
                        "delta=${delta}, " +
                        "improvement=${"%.2f".format(improvement)}% of accesses",
                )
            }
            println()
        }

        println("Adjust parameters via CLI flags (use --help) to explore behaviour under different workloads.")
    }
}

/**
 * Immutable view of the command-line options accepted by the laboratory application.
 */
data class CliOptions(
    val physicalFrames: Int = 8,
    val processCount: Int = 3,
    val virtualPagesPerProcess: Int = 16,
    val workingSetSizes: List<Int> = listOf(3, 6, 9),
    val workingSetChangeInterval: Int = 25,
    val totalCpuAccesses: Int = 2000,
    val localityProbability: Double = 0.9,
    val writeProbability: Double = 0.25,
    val randomSeed: Long = 42L,
) {
    fun toWorkloadConfig(workingSetSize: Int): WorkloadConfig = WorkloadConfig(
        processCount = processCount,
        virtualPagesPerProcess = virtualPagesPerProcess,
        workingSetSize = workingSetSize,
        workingSetChangeInterval = workingSetChangeInterval,
        totalCpuAccesses = totalCpuAccesses,
        localityProbability = localityProbability,
        writeProbability = writeProbability,
        randomSeed = randomSeed,
    )
}

/**
 * Parses key-value style command-line flags into strongly typed [CliOptions].
 */
class CommandLineParser {
    fun parse(rawArgs: Array<String>): CliOptions {
        var options = CliOptions()
        rawArgs.forEach { arg ->
            if (!arg.startsWith("--")) {
                throw CliParsingException("Unexpected argument: $arg")
            }
            val parts = arg.removePrefix("--").split("=", limit = 2)
            if (parts.size != 2) {
                throw CliParsingException("Expected --key=value format, got: $arg")
            }
            val key = parts[0].lowercase()
            val rawValue = parts[1]
            options = when (key) {
                "physical-frames" -> options.copy(physicalFrames = rawValue.toInt())
                "processes" -> options.copy(processCount = rawValue.toInt())
                "virtual-pages" -> options.copy(virtualPagesPerProcess = rawValue.toInt())
                "working-set-sizes" -> options.copy(
                    workingSetSizes = rawValue.split(",").map { it.trim().toInt() }.filter { it > 0 },
                )
                "working-set-change" -> options.copy(workingSetChangeInterval = rawValue.toInt())
                "total-accesses" -> options.copy(totalCpuAccesses = rawValue.toInt())
                "locality" -> options.copy(localityProbability = rawValue.toDouble())
                "write-prob" -> options.copy(writeProbability = rawValue.toDouble())
                "seed" -> options.copy(randomSeed = rawValue.toLong())
                else -> throw CliParsingException("Unknown option: --$key")
            }
        }

        validate(options)
        return options
    }

    fun usage(): String = """
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
    """.trimIndent()

    private fun validate(options: CliOptions) {
        if (options.physicalFrames <= 0) {
            throw CliParsingException("physical-frames must be positive")
        }
        if (options.processCount <= 0) {
            throw CliParsingException("processes must be positive")
        }
        if (options.virtualPagesPerProcess <= 0) {
            throw CliParsingException("virtual-pages must be positive")
        }
        if (options.workingSetSizes.isEmpty()) {
            throw CliParsingException("working-set-sizes must contain at least one positive value")
        }
        if (options.workingSetSizes.any { it > options.virtualPagesPerProcess }) {
            throw CliParsingException("working-set size cannot exceed the virtual page count")
        }
        if (options.workingSetChangeInterval <= 0) {
            throw CliParsingException("working-set-change must be positive")
        }
        if (options.totalCpuAccesses <= 0) {
            throw CliParsingException("total-accesses must be positive")
        }
        if (options.localityProbability !in 0.0..1.0) {
            throw CliParsingException("locality must be within [0, 1]")
        }
        if (options.writeProbability !in 0.0..1.0) {
            throw CliParsingException("write-prob must be within [0, 1]")
        }
    }
}

private class CliParsingException(message: String) : IllegalArgumentException(message)
