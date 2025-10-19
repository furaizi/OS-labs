// Refactored entry point to delegate CLI concerns to a dedicated runner, keeping main focused and testable.
package org.example

import org.example.cli.CommandLineRunner

fun main(args: Array<String>) {
    CommandLineRunner().run(args)
}
