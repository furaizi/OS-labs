package org.example

import java.io.BufferedReader
import java.io.InputStreamReader

fun main() {
    val fs = FileSystem()
    val reader = BufferedReader(InputStreamReader(System.`in`))

    println("In-memory FS lab (one directory). Type 'help' for commands, 'quit' to exit.")

    while (true) {
        print("> ")
        val line = reader.readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty())
            continue
        if (trimmed == "quit" || trimmed == "exit")
            break

        val parts = trimmed.split("\\s+".toRegex())
        val cmd = parts[0]

        try {
            when (cmd) {
                "help" -> printHelp()

                // mkfs n – для ФС у пам'яті параметр n ігноруємо
                "mkfs" -> {
                    fs.mkfs()
                    println("File system re-initialized (in-memory)")
                }

                "ls" -> {
                    val entries = fs.ls()
                    if (entries.isEmpty()) {
                        println("(empty)")
                    } else {
                        for (e in entries) {
                            println("${e.name}\t${e.type}\tinode=${e.id}")
                        }
                    }
                }

                "create" -> {
                    val name = parts.getOrNull(1) ?: error("Usage: create name")
                    fs.create(name)
                    println("Created file '$name'")
                }

                "stat" -> {
                    val name = parts.getOrNull(1) ?: error("Usage: stat name")
                    val st = fs.stat(name)
                    println(
                        "name=$name id=${st.id} type=${st.type} " +
                                "size=${st.size} links=${st.linkCount} " +
                                "open=${st.openCount} blocks=${st.blockCount}"
                    )
                }

                "open" -> {
                    val name = parts.getOrNull(1) ?: error("Usage: open name")
                    val fd = fs.open(name)
                    println("fd=$fd")
                }

                "close" -> {
                    val fd = parts.getOrNull(1)?.toIntOrNull() ?: error("Usage: close fd")
                    fs.close(fd)
                    println("Closed fd=$fd")
                }

                "seek" -> {
                    val fd = parts.getOrNull(1)?.toIntOrNull() ?: error("Usage: seek fd offset")
                    val offset = parts.getOrNull(2)?.toLongOrNull() ?: error("Usage: seek fd offset")
                    fs.seek(fd, offset)
                    println("Offset for fd=$fd set to $offset")
                }

                "read" -> {
                    val fd = parts.getOrNull(1)?.toIntOrNull() ?: error("Usage: read fd size")
                    val size = parts.getOrNull(2)?.toIntOrNull() ?: error("Usage: read fd size")
                    val data = fs.read(fd, size)
                    val text = data.toString(Charsets.UTF_8)
                    println("Read ${data.size} bytes: '$text'")
                }

                "write" -> {
                    val fd = parts.getOrNull(1)?.toIntOrNull() ?: error("Usage: write fd size")
                    val size = parts.getOrNull(2)?.toIntOrNull() ?: error("Usage: write fd size")
                    require(size >= 0) { "size must be non-negative" }

                    // Для простоти пишемо size байт 'x'.
                    // Якщо треба – легко замінити на читання реальних даних зі stdin.
                    val data = ByteArray(size) { 'x'.code.toByte() }
                    val written = fs.write(fd, data)
                    println("Written $written bytes")
                }

                "link" -> {
                    val name1 = parts.getOrNull(1) ?: error("Usage: link name1 name2")
                    val name2 = parts.getOrNull(2) ?: error("Usage: link name1 name2")
                    fs.link(name1, name2)
                    println("Linked '$name2' -> '$name1'")
                }

                "unlink" -> {
                    val name = parts.getOrNull(1) ?: error("Usage: unlink name")
                    fs.unlink(name)
                    println("Unlinked '$name'")
                }

                "truncate" -> {
                    val name = parts.getOrNull(1) ?: error("Usage: truncate name size")
                    val size = parts.getOrNull(2)?.toLongOrNull() ?: error("Usage: truncate name size")
                    fs.truncate(name, size)
                    println("Truncated '$name' to $size bytes")
                }

                else -> println("Unknown command '$cmd'. Type 'help' for list.")
            }
        } catch (e: Exception) {
            println("Error: ${e.message}")
        }
    }
}

private fun printHelp() {
    println(
        """
        Commands:
          mkfs                      - reinitialize file system (in-memory)
          ls                        - list hard links (name, type, inode id)
          create name               - create regular file
          stat name                 - print file metadata
          open name                 - open file, print fd
          close fd                  - close open file descriptor
          seek fd offset            - set offset for fd
          read fd size              - read size bytes and print them as UTF-8 string
          write fd size             - write 'size' bytes of 'x' into file
          link name1 name2          - create hard link name2 -> name1
          unlink name               - remove hard link
          truncate name size        - change file size (growth uses sparse zero blocks)
          help                      - show this help
          quit / exit               - exit program
        """.trimIndent()
    )
}