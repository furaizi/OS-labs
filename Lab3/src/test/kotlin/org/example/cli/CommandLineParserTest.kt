package org.example.cli

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class CommandLineParserTest : FunSpec({

    val parser = CommandLineParser()

    test("should parse all CLI options correctly") {
        val args = arrayOf(
            "--physical-frames=12",
            "--processes=5",
            "--virtual-pages=32",
            "--working-set-sizes=4,8,12",
            "--working-set-change=40",
            "--total-accesses=500",
            "--locality=0.85",
            "--write-prob=0.42",
            "--seed=99",
        )

        val options = parser.parse(args)

        options shouldBe CliOptions(
            physicalFrames = 12,
            processCount = 5,
            virtualPagesPerProcess = 32,
            workingSetSizes = listOf(4, 8, 12),
            workingSetChangeInterval = 40,
            totalCpuAccesses = 500,
            localityProbability = 0.85,
            writeProbability = 0.42,
            randomSeed = 99,
        )
    }

    listOf(
        "missing equals" to arrayOf("--physical-frames"),
        "non numeric frames" to arrayOf("--physical-frames=abc"),
        "non numeric processes" to arrayOf("--processes=1.2"),
        "non numeric virtual pages" to arrayOf("--virtual-pages=text"),
        "non numeric working set" to arrayOf("--working-set-sizes=5,a"),
        "non numeric change interval" to arrayOf("--working-set-change=foo"),
        "non numeric total accesses" to arrayOf("--total-accesses=ten"),
        "non numeric locality" to arrayOf("--locality=two"),
        "non numeric write probability" to arrayOf("--write-prob=lot"),
        "non numeric seed" to arrayOf("--seed=NaN"),
        "unknown flag" to arrayOf("--unknown=1"),
        "unexpected switch" to arrayOf("-p"),
    ).forEach { (description, args) ->
        test("should reject $description") {
            shouldThrow<CliParsingException> {
                parser.parse(args)
            }
        }
    }

    test("should reject negative or zero values via validation") {
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--physical-frames=0"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--processes=-1"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--virtual-pages=-2"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--working-set-sizes=0"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--working-set-sizes=5", "--virtual-pages=4"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--working-set-change=0"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--total-accesses=0"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--locality=2.0"))
        }
        shouldThrow<CliParsingException> {
            parser.parse(arrayOf("--write-prob=-0.1"))
        }
    }

    test("toWorkloadConfig should map values exactly") {
        val options = CliOptions(
            physicalFrames = 16,
            processCount = 4,
            virtualPagesPerProcess = 20,
            workingSetSizes = listOf(5, 10),
            workingSetChangeInterval = 12,
            totalCpuAccesses = 250,
            localityProbability = 0.73,
            writeProbability = 0.41,
            randomSeed = 815,
        )

        val config = options.toWorkloadConfig(workingSetSize = 10)

        config.processCount shouldBe options.processCount
        config.virtualPagesPerProcess shouldBe options.virtualPagesPerProcess
        config.workingSetSize shouldBe 10
        config.workingSetChangeInterval shouldBe options.workingSetChangeInterval
        config.totalCpuAccesses shouldBe options.totalCpuAccesses
        config.localityProbability shouldBe options.localityProbability
        config.writeProbability shouldBe options.writeProbability
        config.randomSeed shouldBe options.randomSeed
    }
})
