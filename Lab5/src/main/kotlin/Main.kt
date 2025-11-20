package org.example

import java.io.BufferedReader
import java.io.InputStreamReader

fun main() {
    val fs = FileSystem()
    val reader = BufferedReader(InputStreamReader(System.`in`))

    println("In-memory FS (directories + symlinks). Type 'help' for commands, 'quit' to exit.")

    while (true) {
        print("> ")
        val line = reader.readLine() ?: break
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        if (trimmed == "quit" || trimmed == "exit") break

        val parts = trimmed.split("\\s+".toRegex())
        val cmd = parts[0]

        try {
            when (cmd) {
                "help" -> printHelp()

                // mkfs [n] – n ігноруємо (in-memory варіант)
                "mkfs" -> {
                    fs.mkfs()
                    println("File system re-initialized (in-memory)")
                }

                "ls" -> {
                    val path = parts.getOrNull(1)
                    val entries = fs.ls(path)
                    if (entries.isEmpty()) {
                        println("(empty)")
                    } else {
                        for (e in entries) {
                            println("${e.name}\t${e.type}\tinode=${e.id}")
                        }
                    }
                }

                "create" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: create pathname")
                    fs.create(path)
                    println("Created file '$path'")
                }

                "stat" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: stat pathname")
                    val st = fs.stat(path)
                    val extra = if (st.symlinkTarget != null) {
                        " target='${st.symlinkTarget}'"
                    } else {
                        ""
                    }
                    println(
                        "path=$path id=${st.id} type=${st.type} size=${st.size} " +
                                "links=${st.linkCount} open=${st.openCount} blocks=${st.blockCount}$extra"
                    )
                }

                "open" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: open pathname")
                    val fd = fs.open(path)
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

                    // як і раніше – записуємо size байт 'x'
                    val data = ByteArray(size) { 'x'.code.toByte() }
                    val written = fs.write(fd, data)
                    println("Written $written bytes")
                }

                "link" -> {
                    val oldPath = parts.getOrNull(1) ?: error("Usage: link oldPath newPath")
                    val newPath = parts.getOrNull(2) ?: error("Usage: link oldPath newPath")
                    fs.link(oldPath, newPath)
                    println("Linked '$newPath' -> '$oldPath'")
                }

                "unlink" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: unlink pathname")
                    fs.unlink(path)
                    println("Unlinked '$path'")
                }

                "truncate" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: truncate pathname size")
                    val size = parts.getOrNull(2)?.toLongOrNull() ?: error("Usage: truncate pathname size")
                    fs.truncate(path, size)
                    println("Truncated '$path' to $size bytes")
                }

                "mkdir" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: mkdir pathname")
                    fs.mkdir(path)
                    println("Directory '$path' created")
                }

                "rmdir" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: rmdir pathname")
                    fs.rmdir(path)
                    println("Directory '$path' removed")
                }

                "cd" -> {
                    val path = parts.getOrNull(1) ?: error("Usage: cd pathname")
                    fs.cd(path)
                    println("Current directory changed to '$path'")
                }

                "symlink" -> {
                    val target = parts.getOrNull(1) ?: error("Usage: symlink target pathname")
                    val linkPath = parts.getOrNull(2) ?: error("Usage: symlink target pathname")
                    fs.symlink(target, linkPath)
                    println("Symlink '$linkPath' -> '$target' created")
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
          ls [path]                 - list directory contents (current dir or given path)
          create pathname           - create regular file
          stat pathname             - show inode metadata (does NOT follow final symlink)
          open pathname             - open regular file (follows symlinks), print fd
          close fd                  - close open file descriptor
          seek fd offset            - set offset for fd
          read fd size              - read size bytes and print them as UTF-8 string
          write fd size             - write 'size' bytes of 'x' into file
          link oldPath newPath      - create hard link newPath -> oldPath (no hard links to dirs)
          unlink pathname           - remove hard link (not for directories)
          truncate pathname size    - change size of regular file (sparse growth)
          mkdir pathname            - create directory
          rmdir pathname            - remove empty directory (only '.' and '..' allowed inside)
          cd pathname               - change current working directory
          symlink target pathname   - create symbolic link 'pathname' with content 'target'
          help                      - show this help
          quit / exit               - exit program
        """.trimIndent()
    )
}
