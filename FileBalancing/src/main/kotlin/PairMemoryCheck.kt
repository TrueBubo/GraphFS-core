import java.io.File
import java.lang.management.ManagementFactory

/**
 * Checks [DistributedPlacer.memory], which adds up object sizes, against the heap the JVM really
 * uses: dist-near alone in one JVM, every file added on its first traversal, heap after a full GC
 * before and after the replay. The heap also holds what the placer keeps anyway (replica lists,
 * which files a server holds, the heads of the maps), so it should be somewhat above the estimate.
 *
 * Usage: PairMemoryCheck twitter|linux <file.gz> exact|cms
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: PairMemoryCheck twitter|linux <file.gz> exact|cms" }
    val halfLife: Double
    val files: Int
    val traversals: Int
    val stream: () -> Pair<Int, Int>
    when (args[0]) {
        "twitter" -> Higgs.load(File(args[1])).let {
            halfLife = 20_000.0
            files = it.users
            traversals = it.size
            stream = it.stream()
        }
        "linux" -> GitHistory.load(File(args[1])).let {
            halfLife = 100_000.0
            files = it.files
            traversals = it.size
            stream = it.stream()
        }
        else -> throw IllegalArgumentException(args[0])
    }
    val n = 16
    val k = 2
    val cells = (files.toLong() * k / n).toInt() * 4
    val counts: (Double) -> PairCounts = if (args[2] == "cms") ({ SketchCounts(it, cells, 16) }) else ::ExactCounts
    val before = heap()
    val p = DistributedPlacer(IntArray(n) { files }, k, halfLife = halfLife, notify = 8, placeNear = true, pairCounts = counts, seed = 3)
    val started = System.nanoTime()
    repeat(traversals) {
        val (u, v) = stream()
        if (!p.isKnown(u)) p.addFile(u, if (p.isKnown(v)) v else -1)
        if (!p.isKnown(v)) p.addFile(v, u)
        p.addEdge(u to v)
    }
    println("%.3f us per traversal, %,d traversals".format((System.nanoTime() - started) / 1e3 / traversals, traversals))
    val used = heap() - before
    val m = p.memory
    println("%s %s: %,d pairs, estimate %.1f MB (pairs %.1f, locations %.1f), heap %.1f MB".format(
        args[0], args[2], m.pairs, (m.pairBytes + m.locationBytes) / 1e6, m.pairBytes / 1e6, m.locationBytes / 1e6, used / 1e6))
    check(p.fileCount > 0)
}

private fun heap(): Long {
    repeat(3) { System.gc() }
    return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}
