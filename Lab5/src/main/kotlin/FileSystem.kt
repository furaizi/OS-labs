package org.example

const val BLOCK_SIZE: Int = 4096
const val MAX_NAME_LEN: Int = 255

enum class FileType {
    REGULAR,
    DIRECTORY
}

/**
 * Дескриптор файлу (inode).
 */
data class Inode(
    val id: Int,
    val type: FileType,
    var size: Long = 0L,
    var linkCount: Int = 0,
    var openCount: Int = 0,
    val blocks: MutableMap<Long, ByteArray> = mutableMapOf()
)

/**
 * Відкритий файл (для числового дескриптора fd).
 */
data class OpenFile(
    val fd: Int,
    val inode: Inode,
    var offset: Long
)

/**
 * Спрощена in-memory файловa система з однією директорією.
 */
class FileSystem {

    data class Stat(
        val id: Int,
        val type: FileType,
        val size: Long,
        val linkCount: Int,
        val openCount: Int,
        val blockCount: Int
    )

    data class LsEntry(
        val name: String,
        val id: Int,
        val type: FileType
    )

    private var nextInodeId: Int = 1

    private fun allocateInodeId(): Int = nextInodeId++

    private val inodes: MutableMap<Int, Inode> = mutableMapOf()

    private val directory: MutableMap<String, Inode> = mutableMapOf()

    private val openFiles: MutableMap<Int, OpenFile> = mutableMapOf()

    init {
        mkfs()
    }

    /**
     * Ініціалізація файлової системи (in-memory) – створюємо тільки кореневу директорію.
     * Параметр n з умови нам не потрібен, тому mkfs() аргументи не приймає.
     */
    fun mkfs() {
        inodes.clear()
        directory.clear()
        openFiles.clear()
        nextInodeId = 1

        // Коренева директорія як окремий інод
        val root = Inode(
            id = allocateInodeId(),
            type = FileType.DIRECTORY,
            size = 0L,
            linkCount = 1,
            openCount = 0
        )
        inodes[root.id] = root
        // В цій реалізації directory – вміст root директорії,
        // сам root по імені не доступний, бо директорія одна.
    }


    private fun requireValidName(name: String) {
        require(name.isNotEmpty()) {
            "Empty name is not allowed"
        }

        require(name.length <= MAX_NAME_LEN) {
            "Name too long (max $MAX_NAME_LEN)"
        }

        require(!name.contains('/')) {
            "File name must not contain '/'"
        }
    }

    /**
     * Створити звичайний файл та жорстке посилання на нього у директорії.
     */
    fun create(name: String) {
        requireValidName(name)
        if (directory.containsKey(name)) {
            error("Entry '$name' already exists")
        }
        val inode = Inode(
            id = allocateInodeId(),
            type = FileType.REGULAR,
            size = 0L,
            linkCount = 1,
            openCount = 0
        )
        inodes[inode.id] = inode
        directory[name] = inode
    }

    /**
     * Отримати інформацію про файл (аналог stat name).
     */
    fun stat(name: String): Stat {
        val inode = directory[name] ?: error("No such entry '$name'")
        return Stat(
            id = inode.id,
            type = inode.type,
            size = inode.size,
            linkCount = inode.linkCount,
            openCount = inode.openCount,
            blockCount = inode.blocks.size
        )
    }

    /**
     * Список жорстких посилань у директорії
     */
    fun ls(): List<LsEntry> =
        directory.entries
            .sortedBy { it.key }
            .map { (name, inode) ->
                LsEntry(name = name, id = inode.id, type = inode.type)
            }

    /**
     * Відкрити файл за ім’ям, повернути найменший вільний fd
     */
    fun open(name: String): Int {
        val inode = directory[name] ?: error("No such entry '$name'")
        if (inode.type != FileType.REGULAR) {
            error("Can only open regular files")
        }
        val fd = allocateFd()
        inode.openCount++
        openFiles[fd] = OpenFile(fd = fd, inode = inode, offset = 0L)
        return fd
    }

    fun close(fd: Int) {
        val of = openFiles.remove(fd) ?: error("Invalid fd $fd")
        val inode = of.inode
        inode.openCount--
        maybeDeleteInode(inode)
    }

    /**
     * Змінити зміщення відкритого файлу
     */
    fun seek(fd: Int, offset: Long) {
        require(offset >= 0) { "Offset must be non-negative" }
        val of = openFiles[fd] ?: error("Invalid fd $fd")
        of.offset = offset
    }

    /**
     * Прочитати size байт з відкритого файлу,
     * зміщення збільшується на фактично прочитану кількість.
     */
    fun read(fd: Int, size: Int): ByteArray {
        require(size >= 0) { "size must be non-negative" }
        val of = openFiles[fd] ?: error("Invalid fd $fd")
        val data = readFromInode(of.inode, of.offset, size)
        of.offset += data.size
        return data
    }

    /**
     * Низькорівнева операція запису.
     * CLI обгортає її і генерує масив потрібного розміру.
     */
    fun write(fd: Int, data: ByteArray): Int {
        val of = openFiles[fd] ?: error("Invalid fd $fd")
        val written = writeToInode(of.inode, of.offset, data)
        of.offset += written.toLong()
        return written
    }

    /**
     * Створити ще одне жорстке посилання на той самий файл.
     */
    fun link(name1: String, name2: String) {
        requireValidName(name2)
        val inode = directory[name1] ?: error("No such entry '$name1'")
        if (directory.containsKey(name2)) {
            error("Entry '$name2' already exists")
        }
        directory[name2] = inode
        inode.linkCount++
    }

    /**
     * Видалити жорстке посилання.
     */
    fun unlink(name: String) {
        val inode = directory.remove(name) ?: error("No such entry '$name'")
        inode.linkCount--
        maybeDeleteInode(inode)
    }

    /**
     * Змінити розмір файлу.
     * Якщо збільшуємо розмір – тільки змінюємо size, блоки не створюємо
     * (логічно вони вважаються заповненими нулями).
     */
    fun truncate(name: String, newSize: Long) {
        require(newSize >= 0) { "newSize must be non-negative" }
        val inode = directory[name] ?: error("No such entry '$name'")
        require(inode.type == FileType.REGULAR) { "Can only truncate regular files" }

        val oldSize = inode.size
        if (newSize == oldSize) return

        if (newSize < oldSize) {
            // Зменшуємо розмір – відрізаємо зайві блоки
            val newLastBlockIndex = if (newSize == 0L) -1L else (newSize - 1) / BLOCK_SIZE

            // Видалити повністю зайві блоки
            val it = inode.blocks.keys.iterator()
            while (it.hasNext()) {
                val idx = it.next()
                if (idx > newLastBlockIndex) {
                    it.remove()
                }
            }

            // Обрізати останній блок, якщо треба
            if (newSize > 0) {
                val lastBlock = inode.blocks[newLastBlockIndex]
                if (lastBlock != null) {
                    val bytesInLastBlock = (newSize % BLOCK_SIZE).toInt().let {
                        if (it == 0) BLOCK_SIZE else it
                    }
                    if (bytesInLastBlock < BLOCK_SIZE) {
                        java.util.Arrays.fill(
                            lastBlock,
                            bytesInLastBlock,
                            BLOCK_SIZE,
                            0.toByte()
                        )
                    }
                }
            }
            inode.size = newSize
        } else {
            inode.size = newSize
        }
    }


    private fun allocateFd(): Int {
        var fd = 0
        while (openFiles.containsKey(fd)) {
            fd++
        }
        return fd
    }

    /**
     * Якщо на файл не вказує жодне посилання і він не відкритий – видаляємо
     */
    private fun maybeDeleteInode(inode: Inode) {
        if (inode.type == FileType.DIRECTORY) return // root не чіпаємо
        if (inode.linkCount <= 0 && inode.openCount <= 0) {
            inodes.remove(inode.id)
            inode.blocks.clear()
            inode.size = 0L
        }
    }

    /**
     * Читання з інода [inode], починаючи з [offset], не більше ніж [size] байт.
     * Відсутні блоки вважаються заповненими нулями.
     */
    private fun readFromInode(inode: Inode, offset: Long, size: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        if (offset >= inode.size) return ByteArray(0)

        val maxAvailable = (inode.size - offset).coerceAtMost(size.toLong())
        val result = ByteArray(maxAvailable.toInt())

        var remaining = result.size
        var filePos = offset
        var destPos = 0

        while (remaining > 0) {
            val blockIndex = filePos / BLOCK_SIZE
            val blockOffset = (filePos % BLOCK_SIZE).toInt()
            val block = inode.blocks[blockIndex]

            val toCopy = minOf(remaining, BLOCK_SIZE - blockOffset)
            if (block != null) {
                System.arraycopy(block, blockOffset, result, destPos, toCopy)
            }
            // якщо block == null – у результаті вже нулі

            remaining -= toCopy
            filePos += toCopy
            destPos += toCopy
        }

        return result
    }

    /**
     * Запис у [inode], починаючи з [offset], байтів з [data].
     * Повертає кількість записаних байт.
     */
    private fun writeToInode(inode: Inode, offset: Long, data: ByteArray): Int {
        if (data.isEmpty()) return 0
        var filePos = offset
        var srcPos = 0
        var remaining = data.size

        while (remaining > 0) {
            val blockIndex = filePos / BLOCK_SIZE
            val blockOffset = (filePos % BLOCK_SIZE).toInt()
            val toCopy = minOf(remaining, BLOCK_SIZE - blockOffset)

            val block = inode.blocks.getOrPut(blockIndex) { ByteArray(BLOCK_SIZE) }
            System.arraycopy(data, srcPos, block, blockOffset, toCopy)

            remaining -= toCopy
            srcPos += toCopy
            filePos += toCopy
        }

        val endPos = offset + data.size
        if (endPos > inode.size) {
            inode.size = endPos
        }

        return data.size
    }
}