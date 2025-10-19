package org.example.cli

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class CommandLineRunnerIntegrationTest : FunSpec({

    fun captureOutput(block: () -> Unit): String {
        val baos = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(baos))
        return try {
            block()
            baos.toString()
        } finally {
            System.setOut(original)
        }
    }

    test("command line runner should print summaries for provided scenarios") {
        val args = arrayOf(
            "--physical-frames=6",
            "--processes=2",
            "--virtual-pages=12",
            "--working-set-sizes=3,4",
            "--working-set-change=5",
            "--total-accesses=80",
            "--locality=0.9",
            "--write-prob=0.25",
            "--seed=7",
        )

        val output = captureOutput { CommandLineRunner().run(args) }

        output.shouldContain("physical frames=6")
        output.shouldContain("total accesses=80")
        output.shouldContain("=== Working set size: 3 ===")
        output.shouldContain("=== Working set size: 4 ===")
        output.shouldContain("Algorithm: Random")
        output.shouldContain("Algorithm: Clock")
        output.shouldContain("Clock vs Random: page faults")
    }
})
