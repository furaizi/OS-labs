package org.example.cli

import org.example.simulation.AlgorithmType
import org.example.simulation.PageReplacementExperiment
import org.example.simulation.SimulationConfig
import org.example.workload.WorkloadGenerator
import kotlin.system.exitProcess

/**
 * Orchestrates the paging simulations based on command-line arguments.
 * - Keeps I/O (println) only here; core logic is delegated to helpers.
 * - No behavior changes vs. the previous version.
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
            eprintln(ex.message)
            eprintln(parser.usage())
            exitProcess(1)
        }

        printHeader(options)

        options.workingSetSizes.forEach { ws ->
            runScenario(options, ws)
            println()
        }

        println("Adjust parameters via CLI flags (use --help) to explore behaviour under different workloads.")
    }

    private fun runScenario(options: CliOptions, workingSetSize: Int) {
        val trace = WorkloadGenerator(options.toWorkloadConfig(workingSetSize)).generate()
        val experiment = experimentFactory(SimulationConfig(physicalPageCount = options.physicalFrames))
        val summaries = experiment.run(trace)

        // We always run both algorithms; if behavior changes later, this is a single point to adjust.
        val randomSummary = summaries.getValue(AlgorithmType.RANDOM)
        val clockSummary = summaries.getValue(AlgorithmType.CLOCK)

        println("=== Working set size: $workingSetSize ===")
        println(randomSummary.render())
        println(clockSummary.render())
        printComparison(randomSummary, clockSummary)
    }

    private fun printHeader(options: CliOptions) {
        println(
            "Page replacement laboratory | physical frames=${options.physicalFrames} | total accesses=${options.totalCpuAccesses}",
        )
        println("Working set sizes to evaluate: ${options.workingSetSizes.joinToString()}")
        println()
    }

    private fun printComparison(randomSummary: org.example.simulation.SimulationSummary,
                                clockSummary: org.example.simulation.SimulationSummary) {
        if (randomSummary.totalAccesses <= 0) return
        val delta = randomSummary.pageFaults - clockSummary.pageFaults
        val improvement = delta.toDouble() / randomSummary.totalAccesses * 100
        println(
            "Clock vs Random: page faults ${clockSummary.pageFaults} vs ${randomSummary.pageFaults}, " +
                    "delta=$delta, improvement=${"%.2f".format(improvement)}% of accesses",
        )
    }

    private fun eprintln(msg: String?) {
        if (msg != null) System.err.println(msg)
    }
}
