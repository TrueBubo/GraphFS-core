import java.io.File
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * Scaling benchmark: how time and memory grow with the number of files and of traversals.
 *
 * Usage: Scaling <files> <events> <csv>. One run loads <files> files, then makes <events>
 * traversals, and appends a row to <csv> at every checkpoint (1, 2, 5 times a power of ten):
 *  - phase "load":   after that many addFile calls; time is per addFile since the last checkpoint.
 *  - phase "events": after that many traversals; time is per traversal since the last checkpoint.
 * Heap is measured after a full GC, which is left out of the timing. Run each file count in its
 * own JVM so heap and JIT state do not carry over.
 *
 * Workload as in the benchmark: 64 servers, k = 3, groups of 20 files picked with Zipf
 * popularity, 90 % of traversals inside a group, 10 % between random files. No failures.
 */
fun main(args: Array<String>) {
    require(args.size == 3) { "usage: Scaling <files> <events> <csv>" }
    val files = args[0].toInt()
    val events = args[1].toLong()
    val out = File(args[2])
    out.parentFile?.mkdirs()
    if (!out.exists()) out.writeText("files,phase,count,ns_per_op,heap_bytes,pairs,remote_pct,copies\n")

    val n = 64
    val k = 3
    val p = ReplicaPlacer(IntArray(n) { (files.toLong() * k * 3 / 2 / n + 1).toInt() }, k, alpha = 8.0, halfLife = 300_000.0, seed = 9)
    val rnd = Random(13)
    val members = randomGroups(files, files / 20, rnd)
    val zipf = Zipf(members.size, 1.0, rnd)
    val generatorNs = generatorCost(members, files)

    fun row(phase: String, count: Long, nsPerOp: Double, window: Stats) {
        val heap = heapAfterGc()
        out.appendText("$files,$phase,$count,%.2f,$heap,${p.pairCount},%.2f,${p.stats.copies}\n"
            .format(nsPerOp, pct(window.remote, window.traversals)))
        println("files %,d  %-6s %,15d  %8.1f ns/op  heap %,6d MB  pairs %,11d  remote %5.1f %%".format(
            files, phase, count, nsPerOp, heap shr 20, p.pairCount, pct(window.remote, window.traversals)))
    }

    var done = 0L
    var last = 0L
    for (c in checkpoints(files.toLong())) {
        val start = System.nanoTime()
        while (done < c) p.addFile((done++).toInt())
        row("load", c, (System.nanoTime() - start).toDouble() / (c - last), Stats())
        last = c
    }

    done = 0L
    last = 0L
    var before = p.stats.copy()
    for (c in checkpoints(events)) {
        val start = System.nanoTime()
        while (done < c) {
            p.addEdge(nextPair(members[zipf.next()], files, rnd))
            done++
        }
        val t = System.nanoTime() - start
        row("events", c, t.toDouble() / (c - last) - generatorNs, p.stats - before)
        before = p.stats.copy()
        last = c
    }
    p.checkInvariants()
}

/** 1, 2, 5 times each power of ten from 1,000 up to [max], and [max] itself. */
private fun checkpoints(max: Long): List<Long> {
    val out = ArrayList<Long>()
    var base = 1_000L
    while (base <= max) {
        for (m in longArrayOf(1, 2, 5)) if (base * m < max) out.add(base * m)
        base *= 10
    }
    out.add(max)
    return out
}

/** Nanoseconds the workload generator alone takes per traversal; subtracted from the timings. */
private fun generatorCost(members: List<IntArray>, files: Int): Double {
    val rnd = Random(99)
    val zipf = Zipf(members.size, 1.0, rnd)
    var sink = 0L
    val reps = 20_000_000
    repeat(reps / 10) { sink += nextPair(members[zipf.next()], files, rnd).first } // warm-up
    val start = System.nanoTime()
    repeat(reps) { sink += nextPair(members[zipf.next()], files, rnd).first }
    val ns = (System.nanoTime() - start).toDouble() / reps
    if (sink == 42L) println()
    println("workload generator: %.1f ns per traversal (subtracted)".format(ns))
    return ns
}

private fun heapAfterGc(): Long {
    System.gc()
    return ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
}
