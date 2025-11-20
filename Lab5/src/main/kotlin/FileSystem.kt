package org.example

const val BLOCK_SIZE: Int = 4096
const val MAX_NAME_LEN: Int = 255
const val MAX_SYMLINK_DEPTH: Int = 40

enum class FileType {
    REGULAR,
    DIRECTORY,
    SYMLINK
}

/**
 * Дескриптор файлу (inode).
 *
 * Для DIRECTORY використовується dirEntries + parent.
 * Для REGULAR використовується blocks.
 * Для SYMLINK використовується symlinkTarget (рядок-ціль).
 */
data class Inode(
    val id: Int,
    val type: FileType,
    var size: Long = 0L,
    var linkCount: Int = 0,
    var openCount: Int = 0,
    val blocks: MutableMap<Long, ByteArray> = mutableMapOf(),
    var symlinkTarget: String? = null,
    val dirEntries: MutableMap<String, Inode> = mutableMapOf(),
    var parent: Inode? = null
)


data class OpenFile(
    val fd: Int,
    val inode: Inode,
    var offset: Long
)

/**
 * In-memory FS: дерево директорій + symlink-и.
 */
class FileSystem {

    data class Stat(
        val id: Int,
        val type: FileType,
        val size: Long,
        val linkCount: Int,
        val openCount: Int,
        val blockCount: Int,
        val symlinkTarget: String?
    )

    data class LsEntry(
        val name: String,
        val id: Int,
        val type: FileType
    )

    private var nextInodeId: Int = 1

    private fun allocateInodeId(): Int = nextInodeId++

    private val inodes: MutableMap<Int, Inode> = mutableMapOf()

    private val openFiles: MutableMap<Int, OpenFile> = mutableMapOf()

    private lateinit var root: Inode
    private lateinit var cwd: Inode

    init {
        mkfs()
    }

    /**
     * Ініціалізувати ФС: створити кореневу директорію "/" з "." і "..".
     * Параметр n з умови лабораторної роботи тут не потрібен (in-memory варіант).
     */
    fun mkfs() {
        inodes.clear()
        openFiles.clear()
        nextInodeId = 1

        val rootInode = Inode(
            id = allocateInodeId(),
            type = FileType.DIRECTORY,
            size = 0L,
            linkCount = 2, // "." і ".."
            openCount = 0
        )

        rootInode.parent = rootInode
        rootInode.dirEntries["."] = rootInode
        rootInode.dirEntries[".."] = rootInode

        inodes[rootInode.id] = rootInode
        root = rootInode
        cwd = rootInode
    }

    /**
     * create pathname – створити звичайний файл.
     */
    fun create(path: String) {
        val (parent, name) = resolveParentAndName(path)
        if (parent.dirEntries.containsKey(name)) {
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
        parent.dirEntries[name] = inode
    }

    /**
     * stat pathname – дані inode.
     * ВАЖЛИВО: останній symlink НЕ розкривається (аналог lstat).
     */
    fun stat(path: String): Stat {
        val inode = resolvePath(path, followFinalSymlink = false)
        return Stat(
            id = inode.id,
            type = inode.type,
            size = inode.size,
            linkCount = inode.linkCount,
            openCount = inode.openCount,
            blockCount = inode.blocks.size,
            symlinkTarget = inode.symlinkTarget
        )
    }

    /**
     * ls [pathname] – список жорстких посилань у директорії.
     * Якщо pathname не заданий – використовується поточна директорія.
     */
    fun ls(path: String? = null): List<LsEntry> {
        val dirInode = if (path == null || path.isEmpty()) {
            cwd
        } else {
            val inode = resolvePath(path, followFinalSymlink = true)
            if (inode.type != FileType.DIRECTORY) {
                error("Not a directory: $path")
            }
            inode
        }

        return dirInode.dirEntries.entries
            .asSequence()
            .filter { (name, _) -> name != "." && name != ".." }
            .sortedBy { it.key }
            .map { (name, inode) ->
                LsEntry(name = name, id = inode.id, type = inode.type)
            }
            .toList()
    }

    /**
     * open pathname – відкрити звичайний файл (symlink-и розкриваються).
     */
    fun open(path: String): Int {
        val inode = resolvePath(path, followFinalSymlink = true)
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

    fun seek(fd: Int, offset: Long) {
        require(offset >= 0) { "Offset must be non-negative" }
        val of = openFiles[fd] ?: error("Invalid fd $fd")
        of.offset = offset
    }

    fun read(fd: Int, size: Int): ByteArray {
        require(size >= 0) { "size must be non-negative" }
        val of = openFiles[fd] ?: error("Invalid fd $fd")
        require(of.inode.type == FileType.REGULAR) { "Can only read regular files" }

        val data = readFromInode(of.inode, of.offset, size)
        of.offset += data.size
        return data
    }

    fun write(fd: Int, data: ByteArray): Int {
        val of = openFiles[fd] ?: error("Invalid fd $fd")
        require(of.inode.type == FileType.REGULAR) { "Can only write regular files" }

        val written = writeToInode(of.inode, of.offset, data)
        of.offset += written.toLong()
        return written
    }

    /**
     * link oldPath newPath – створити жорстке посилання на файл.
     * Останній компонент oldPath, якщо це symlink, НЕ розкривається (працюємо з самим symlink-ом).
     * На директорії жорсткі посилання створювати не можна.
     */
    fun link(oldPath: String, newPath: String) {
        val target = resolvePath(oldPath, followFinalSymlink = false)
        if (target.type == FileType.DIRECTORY) {
            error("link: cannot create hard link to directory")
        }
        val (parent, name) = resolveParentAndName(newPath)
        if (parent.dirEntries.containsKey(name)) {
            error("Entry '$name' already exists")
        }
        parent.dirEntries[name] = target
        target.linkCount++
    }

    /**
     * unlink pathname – знищити жорстке посилання.
     * На директорії працювати не повинен (для них є rmdir).
     * Якщо останній компонент – symlink, видаляється саме symlink.
     */
    fun unlink(path: String) {
        val (parent, name) = resolveParentAndName(path)
        val inode = parent.dirEntries[name] ?: error("No such entry '$path'")
        if (inode.type == FileType.DIRECTORY) {
            error("unlink: cannot remove directory (use rmdir)")
        }
        parent.dirEntries.remove(name)
        inode.linkCount--
        maybeDeleteInode(inode)
    }

    /**
     * truncate pathname size – змінити розмір звичайного файлу (symlink-и розкриваються).
     * При збільшенні – робимо sparse-файл (блоки з нулями не створюємо).
     */
    fun truncate(path: String, newSize: Long) {
        require(newSize >= 0) { "newSize must be non-negative" }
        val inode = resolvePath(path, followFinalSymlink = true)
        require(inode.type == FileType.REGULAR) { "Can only truncate regular files" }

        val oldSize = inode.size
        if (newSize == oldSize) return

        if (newSize < oldSize) {
            val newLastBlockIndex = if (newSize == 0L) -1L else (newSize - 1) / BLOCK_SIZE

            // Видалити зайві блоки
            val iterator = inode.blocks.keys.iterator()
            while (iterator.hasNext()) {
                val idx = iterator.next()
                if (idx > newLastBlockIndex) {
                    iterator.remove()
                }
            }

            // Обрізати хвіст останнього блоку
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
            // Збільшуємо – лише міняємо size, блоки не створюємо (дірки = нулі)
            inode.size = newSize
        }
    }

    /**
     * mkdir pathname – створити директорію (з "." і "..").
     */
    fun mkdir(path: String) {
        val (parent, name) = resolveParentAndName(path)
        if (parent.dirEntries.containsKey(name)) {
            error("Entry '$name' already exists")
        }

        val dir = Inode(
            id = allocateInodeId(),
            type = FileType.DIRECTORY,
            size = 0L,
            linkCount = 0,
            openCount = 0
        )

        dir.parent = parent
        dir.dirEntries["."] = dir
        dir.dirEntries[".."] = parent

        // Запис у батьківську директорію
        parent.dirEntries[name] = dir

        // Лічильники посилань:
        // у каталогу: "." + entry в parent
        dir.linkCount = 2
        // у parent: збільшується через ".." у дочірньому каталозі
        parent.linkCount++

        inodes[dir.id] = dir
    }

    /**
     * rmdir pathname – видалити порожню директорію.
     * Вміст повинен мати тільки "." і "..".
     */
    fun rmdir(path: String) {
        val (parent, name) = resolveParentAndName(path)
        val dir = parent.dirEntries[name] ?: error("No such entry '$path'")
        if (dir.type != FileType.DIRECTORY) {
            error("rmdir: not a directory")
        }
        if (dir === root) {
            error("rmdir: cannot remove root directory")
        }

        val hasOtherEntries = dir.dirEntries.keys.any { it != "." && it != ".." }
        if (hasOtherEntries) {
            error("rmdir: directory not empty")
        }

        // Оновлюємо лічильники
        parent.linkCount--
        dir.linkCount -= 1 // за entry у parent

        // Видаляємо запис з батьківської директорії
        parent.dirEntries.remove(name)

        // Фізично видаляємо інод директорії
        inodes.remove(dir.id)
        dir.dirEntries.clear()
        dir.parent = null
        dir.size = 0L
        dir.linkCount = 0
    }

    /**
     * cd pathname – змінити поточну робочу директорію.
     * Symlink-и розкриваються.
     */
    fun cd(path: String) {
        val inode = resolvePath(path, followFinalSymlink = true)
        if (inode.type != FileType.DIRECTORY) {
            error("cd: not a directory")
        }
        cwd = inode
    }

    /**
     * symlink str pathname – створити символічне посилання.
     * str – довільний рядок (ціль), довжина <= BLOCK_SIZE байт.
     */
    fun symlink(target: String, linkPath: String) {
        val targetBytes = target.toByteArray(Charsets.UTF_8)
        require(targetBytes.size <= BLOCK_SIZE) {
            "Symlink target is too long (max $BLOCK_SIZE bytes)"
        }

        val (parent, name) = resolveParentAndName(linkPath)
        if (parent.dirEntries.containsKey(name)) {
            error("Entry '$name' already exists")
        }

        val inode = Inode(
            id = allocateInodeId(),
            type = FileType.SYMLINK,
            size = targetBytes.size.toLong(),
            linkCount = 1,
            openCount = 0,
            symlinkTarget = target
        )

        parent.dirEntries[name] = inode
        inodes[inode.id] = inode
    }


    private fun requireValidNameComponent(name: String) {
        require(name.isNotEmpty()) {
            "Empty name is not allowed"
        }

        require(name.length <= MAX_NAME_LEN) {
            "Name too long (max $MAX_NAME_LEN)"
        }

        require(name != "." && name != "..") {
            "Names '.' and '..' are reserved"
        }
    }

    private fun splitPath(path: String): MutableList<String> =
        path.split('/')
            .filter { it.isNotEmpty() }
            .toMutableList()

    /**
     * Розв'язати pathname до inode.
     * followFinalSymlink = false – не розкривати останній symlink (для stat/link/unlink).
     */
    private fun resolvePath(path: String, followFinalSymlink: Boolean): Inode {
        if (path.isEmpty()) {
            return cwd
        }

        val isAbsolute = path.startsWith("/")
        var currentInode: Inode = if (isAbsolute) root else cwd
        var components = splitPath(path)
        if (components.isEmpty()) {
            return currentInode
        }

        var index = 0
        var symlinkCount = 0

        while (true) {
            if (index >= components.size) {
                return currentInode
            }

            val comp = components[index]
            when (comp) {
                "", "." -> {
                    index++
                }
                ".." -> {
                    if (currentInode.type != FileType.DIRECTORY) {
                        error("Not a directory when processing '..'")
                    }
                    val parent = currentInode.parent ?: currentInode
                    currentInode = parent
                    index++
                }
                else -> {
                    if (currentInode.type != FileType.DIRECTORY) {
                        error("Not a directory: component '$comp'")
                    }
                    val dirEntries = currentInode.dirEntries
                    val child = dirEntries[comp] ?: error("No such file or directory: $comp")

                    val isLast = (index == components.size - 1)
                    if (child.type == FileType.SYMLINK && (!isLast || followFinalSymlink)) {
                        symlinkCount++
                        if (symlinkCount > MAX_SYMLINK_DEPTH) {
                            error("Too many symbolic links")
                        }

                        val target = child.symlinkTarget ?: ""
                        val targetIsAbsolute = target.startsWith("/")
                        val targetComponents = splitPath(target)
                        val remainingComponents = if (isLast) {
                            emptyList()
                        } else {
                            components.subList(index + 1, components.size).toList()
                        }

                        if (targetIsAbsolute) {
                            currentInode = root
                        } else {
                            // Відносний шлях – від директорії, де лежить symlink
                            // (currentInode – це якраз ця директорія)
                        }

                        components = (targetComponents + remainingComponents).toMutableList()
                        index = 0
                    } else {
                        currentInode = child
                        index++
                    }
                }
            }
        }
    }

    /**
     * Розв'язати шлях до (батьківська директорія, ім'я останнього компонента).
     * Використовується для create/mkdir/symlink/link(new)/unlink/rmdir.
     * Батьківська директорія – з розкриттям symlink-ів.
     */
    private fun resolveParentAndName(path: String): Pair<Inode, String> {
        if (path.isEmpty()) {
            error("Empty path")
        }

        val normalized = path.trimEnd('/')
        if (normalized.isEmpty()) {
            error("Path refers to root directory")
        }

        val lastSlash = normalized.lastIndexOf('/')
        val parentPath: String?
        val name: String

        if (lastSlash < 0) {
            parentPath = null
            name = normalized
        } else if (lastSlash == 0) {
            parentPath = "/"
            name = normalized.substring(1)
        } else {
            parentPath = normalized.substring(0, lastSlash)
            name = normalized.substring(lastSlash + 1)
        }

        requireValidNameComponent(name)

        val parentInode = if (parentPath == null) {
            cwd
        } else {
            val inode = resolvePath(parentPath, followFinalSymlink = true)
            if (inode.type != FileType.DIRECTORY) {
                error("Parent path is not a directory: $parentPath")
            }
            inode
        }

        return parentInode to name
    }

    private fun allocateFd(): Int {
        var fd = 0
        while (openFiles.containsKey(fd)) {
            fd++
        }
        return fd
    }

    /**
     * Якщо на файл не вказує жодне посилання і він не відкритий – звільняємо.
     * Для директорій не використовується (для них є rmdir).
     */
    private fun maybeDeleteInode(inode: Inode) {
        if (inode.type == FileType.DIRECTORY) return
        if (inode.linkCount <= 0 && inode.openCount <= 0) {
            inodes.remove(inode.id)
            inode.blocks.clear()
            inode.symlinkTarget = null
            inode.dirEntries.clear()
            inode.size = 0L
        }
    }

    private fun readFromInode(inode: Inode, offset: Long, size: Int): ByteArray {
        require(inode.type == FileType.REGULAR) { "Can only read from regular file" }
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
            remaining -= toCopy
            filePos += toCopy
            destPos += toCopy
        }

        return result
    }

    private fun writeToInode(inode: Inode, offset: Long, data: ByteArray): Int {
        require(inode.type == FileType.REGULAR) { "Can only write to regular file" }
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
