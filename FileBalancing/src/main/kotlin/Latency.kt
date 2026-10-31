import java.io.File
import kotlin.random.Random

/**
 * The time of each addEdge call on the Wikipedia stream with no file limit (every file is added
 * on its first traversal), to see the slowest calls, not only the mean.
 *
 * Usage: Latency <dir with links.bin> central|distributed <traversals> [sweepEvery]
 * The distributed placer is dist-near (notify 8, placeNear); sweepEvery overrides its sweep period.
 */
fun main(args: Array<String>) {
    val clicks = Clicks.load(File(args[0], "links.bin"))
    val central = args[1] == "central"
    val traversals = args[2].toInt()
    val n = 16
    val p: Placer = if (central) {
        ReplicaPlacer(IntArray(n) { 10_000_000 }, 2, halfLife = 1_000_000.0, seed = 3)
    } else {
        DistributedPlacer(IntArray(n) { 10_000_000 }, 2, halfLife = 1_000_000.0, notify = 8, placeNear = true, seed = 3)
            .also { d -> args.getOrNull(3)?.let { d.sweepEvery = it.toLong() } }
    }
    val rnd = Random(17)
    val times = LongArray(traversals)
    var total = 0L
    for (t in 0 until traversals) {
        val (u, v) = clicks.sample(rnd)
        if (!p.isKnown(u)) p.addFile(u)
        if (!p.isKnown(v)) p.addFile(v, u)
        val start = System.nanoTime()
        p.addEdge(u to v)
        val d = System.nanoTime() - start
        times[t] = d
        total += d
    }
    val sorted = times.sortedArray()
    fun q(x: Double) = sorted[((traversals - 1) * x).toInt()] / 1000.0
    val slow = times.withIndex().filter { it.value > 5_000_000 }.joinToString { "%d:%.0fms".format(it.index + 1, it.value / 1e6) }
    println("%s %,d traversals, %,d files: mean %.2f us, p99 %.1f us, p99.99 %.0f us, max %.1f ms; over 5 ms: %s".format(
        args[1], traversals, p.fileCount, total / 1e3 / traversals, q(0.99), q(0.9999), sorted.last() / 1e6, slow))
}
