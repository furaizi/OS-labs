package org.example.cli

/**
 * Parses key-value style command-line flags into strongly typed [CliOptions].
 * Expected format: --key=value
 */
class CommandLineParser {

    fun parse(rawArgs: Array<String>): CliOptions {
        var options = CliOptions()

        for (arg in rawArgs) {
            if (!arg.startsWith("--")) {
                throw CliParsingException("Unexpected argument: $arg")
            }

            val (key, raw) = splitKeyValue(arg)
            options = when (key) {
                "physical-frames"   -> options.copy(physicalFrames = raw.toIntStrict("--$key"))
                "processes"         -> options.copy(processCount = raw.toIntStrict("--$key"))
                "virtual-pages"     -> options.copy(virtualPagesPerProcess = raw.toIntStrict("--$key"))
                "working-set-sizes" -> options.copy(
                    workingSetSizes = raw.split(",")
                        .map { it.trim().toIntStrict("--$key") }
                        .filter { it > 0 },
                )
                "working-set-change"-> options.copy(workingSetChangeInterval = raw.toIntStrict("--$key"))
                "total-accesses"    -> options.copy(totalCpuAccesses = raw.toIntStrict("--$key"))
                "locality"          -> options.copy(localityProbability = raw.toDoubleStrict("--$key"))
                "write-prob"        -> options.copy(writeProbability = raw.toDoubleStrict("--$key"))
                "seed"              -> options.copy(randomSeed = raw.toLongStrict("--$key"))
                "help"              -> options // ignore here; handled in runner
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

    private fun validate(o: CliOptions) {
        require(o.physicalFrames > 0) { "physical-frames must be positive" }
        require(o.processCount > 0) { "processes must be positive" }
        require(o.virtualPagesPerProcess > 0) { "virtual-pages must be positive" }
        require(o.workingSetSizes.isNotEmpty()) { "working-set-sizes must contain at least one positive value" }
        require(o.workingSetSizes.none { it > o.virtualPagesPerProcess }) {
            "working-set size cannot exceed the virtual page count"
        }
        require(o.workingSetChangeInterval > 0) { "working-set-change must be positive" }
        require(o.totalCpuAccesses > 0) { "total-accesses must be positive" }
        require(o.localityProbability in 0.0..1.0) { "locality must be within [0, 1]" }
        require(o.writeProbability in 0.0..1.0) { "write-prob must be within [0, 1]" }
    }

    private fun splitKeyValue(arg: String): Pair<String, String> {
        val parts = arg.removePrefix("--").split("=", limit = 2)
        if (parts.size != 2) {
            throw CliParsingException("Expected --key=value format, got: $arg")
        }
        return parts[0].lowercase() to parts[1]
    }


    private fun String.toIntStrict(flag: String): Int =
        toIntOrNull() ?: throw CliParsingException("Invalid integer for $flag: '$this'")

    private fun String.toLongStrict(flag: String): Long =
        toLongOrNull() ?: throw CliParsingException("Invalid long for $flag: '$this'")

    private fun String.toDoubleStrict(flag: String): Double =
        toDoubleOrNull() ?: throw CliParsingException("Invalid double for $flag: '$this'")
}