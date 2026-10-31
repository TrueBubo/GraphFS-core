import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Source files as files, files changed in the same commit as traversals: the Linux kernel history.
 * A build or an editor working on a change reads the files of that change together, so a storage
 * system wants files that change together on the same server.
 *
 * Usage: LinuxSimulation <log.txt.gz> [maxFiles] [halfLife] [policies] [csv]; policies is a comma
 * list or - for all of them; csv collects one row per policy (CacheSimulation). The log is
 *   git log --reverse --no-merges --no-renames --name-only --format='commit %ct'
 * (./gradlew linuxLog), oldest commit first, and it is replayed in that order.
 *
 * A commit of m files becomes the m - 1 traversals between neighbours in path order, so files in
 * one directory are linked first and a commit weighs in proportion to its size, not its square.
 * Commits of one file have no pair; commits of more than [MAX_COMMIT] files (tree-wide renames,
 * API sweeps, merges of whole drivers) are skipped, as nobody works on those files together.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: LinuxSimulation <log.txt.gz> [maxFiles] [halfLife] [policies] [csv]" }
    val maxFiles = args.getOrNull(1)?.toInt() ?: 10_000
    val halfLife = args.getOrNull(2)?.toDouble() ?: 100_000.0
    val names = args.getOrNull(3)?.takeIf { it != "-" }?.split(',') ?: POLICIES
    val csv = args.getOrNull(4)?.let(::File)

    val history = GitHistory.load(File(args[0]))
    compareCaches("Linux", history.files, history.size.toLong(), maxFiles, halfLife, names, csv, history::stream)
}

/** Commits of at most this many files become traversals. */
internal const val MAX_COMMIT = 32

/** The co-change traversals of a git log, in commit order; file ids in order of first appearance. */
internal class GitHistory(private val from: IntArray, private val to: IntArray, val files: Int) {
    val size: Int get() = from.size

    /** A fresh pass over the traversals. */
    fun stream(): () -> Pair<Int, Int> {
        var i = 0
        return { (from[i] to to[i]).also { i++ } }
    }

    companion object {
        fun load(file: File): GitHistory {
            val ids = HashMap<String, Int>()
            val from = IntList()
            val to = IntList()
            var commits = 0
            var single = 0
            var large = 0
            var largeFiles = 0L
            val sizes = IntArray(MAX_COMMIT + 1)
            val paths = ArrayList<String>()

            fun commit() {
                if (paths.isEmpty()) return
                commits++
                when {
                    paths.size == 1 -> single++
                    paths.size > MAX_COMMIT -> { large++; largeFiles += paths.size }
                    else -> {
                        sizes[paths.size]++
                        paths.sort()
                        val f = paths.map { ids.getOrPut(it) { ids.size } }
                        for (i in 1 until f.size) { from.add(f[i - 1]); to.add(f[i]) }
                    }
                }
                paths.clear()
            }

            GZIPInputStream(FileInputStream(file)).bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("commit ")) commit()
                    else if (line.isNotEmpty()) paths += line
                }
            }
            commit()
            val paired = sizes.sum()
            println("%,d commits: %,d of one file, %,d of more than %d files (%,d files in all) skipped; %,d commits -> %,d traversals over %,d files"
                .format(commits, single, large, MAX_COMMIT, largeFiles, paired, from.size, ids.size))
            println("files per paired commit: " + (2..MAX_COMMIT).filter { sizes[it] > 0 }
                .joinToString(" ") { "$it:%.1f%%".format(100.0 * sizes[it] / paired) })
            return GitHistory(from.toArray(), to.toArray(), ids.size)
        }
    }
}

/** A growable IntArray. */
internal class IntList {
    private var a = IntArray(1 shl 16)
    var size = 0; private set
    fun add(x: Int) {
        if (size == a.size) a = a.copyOf(2 * size)
        a[size++] = x
    }
    fun toArray(): IntArray = a.copyOf(size)
}
