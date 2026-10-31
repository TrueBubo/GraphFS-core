import java.io.File
import java.util.Locale

/**
 * Compares eviction policies for a placer used as a cache of at most [maxFiles] of [files] files.
 * [stream] makes a fresh source of traversals; each policy runs in its own thread with its own
 * source, so all of them must yield the same sequence. Policies ([POLICIES]):
 *  - "weight": the file whose pairs weigh least in total (ReplicaPlacer.lightestFile). A just-added
 *    file weighs 1, so it can be the one removed.
 *  - the classic policies in Eviction.kt: LRU, FIFO, Random, CLOCK, LFU, SLRU, ARC, W-TinyLFU,
 *    and Belady's offline optimum (fewest misses possible; it reads the whole trace in advance).
 *  - "all": no limit, every file ever traversed stays (servers sized for all files), as the
 *    reference the limited caches are measured against.
 * A traversal of a file that is not in the placer adds it (a load: an upload, not a copy). Each
 * policy runs an adaptive placer and a static one (alpha = infinity: never optimises); both see
 * the same files come and go, so they differ only in where replicas go. With [csv], appends one
 * row of totals per policy to it (header first if the file is new).
 */
internal fun compareCaches(
    title: String, files: Int, traversals: Long, maxFiles: Int, halfLife: Double,
    names: List<String>, csv: File? = null, stream: () -> () -> Pair<Int, Int>,
) {
    for (name in names) require(name in POLICIES) { "unknown policy $name; known: ${POLICIES.joinToString(",")}" }
    val n = 16
    val k = 2
    val alpha = 8.0
    val next = if ("Belady" in names) {
        require(traversals <= Int.MAX_VALUE / 2) { "Belady needs traversals <= ${Int.MAX_VALUE / 2}" }
        val pairs = stream()
        val trace = IntArray((2 * traversals).toInt())
        for (t in 0 until traversals.toInt()) {
            val (a, b) = pairs()
            trace[2 * t] = a
            trace[2 * t + 1] = b
        }
        Belady.nextUse(trace, files)
    } else null

    println("== %s: at most %,d of %,d files in the placer; %d servers, k = %d, alpha = %.0f, half-life %,.0f traversals =="
        .format(title, maxFiles, files, n, k, alpha, halfLife))
    fun placer(alpha: Double, limit: Int) = ReplicaPlacer(IntArray(n) { limit }, k, alpha = alpha, halfLife = halfLife, seed = 3)
    val runs = names.map { name ->
        val limit = if (name == "all") files else maxFiles
        val adaptive = placer(alpha, limit)
        val static = placer(Double.POSITIVE_INFINITY, limit)
        val eviction = when (name) {
            "weight" -> WeightEviction(adaptive, static)
            "all" -> NoEviction()
            else -> classicEviction(name, files, maxFiles, next)
        }
        Policy(eviction, adaptive, static, limit, files)
    }

    runPolicies(title, maxFiles, traversals, alpha, runs, csv, stream)
}

/**
 * Runs each policy (with its placers) over its own copy of [stream] in its own thread, then prints
 * the per-window tables and the totals, and appends the totals to [csv] if given.
 */
internal fun runPolicies(
    title: String, maxFiles: Int, traversals: Long, alpha: Double, runs: List<Policy>, csv: File?,
    stream: () -> () -> Pair<Int, Int>,
) {
    val window = maxOf(1L, (traversals + 9) / 10)
    val w = maxOf(10, runs.maxOf { it.name.length } + 1)       // column width for the names
    val start = System.nanoTime()
    val threads = runs.map { r ->
        Thread {
            val pairs = stream()
            val t0 = System.nanoTime()
            for (t in 1..traversals) {
                r.traverse(pairs())
                if (t % window == 0L || t == traversals) {
                    r.window()
                    r.check()
                }
            }
            println("  %-${w - 1}s done in %.0f s".format(r.name, (System.nanoTime() - t0) / 1e9))
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> r.failure = e }
            start()
        }
    }
    threads.forEach { it.join() }
    runs.firstNotNullOfOrNull { it.failure }?.let { throw IllegalStateException("${runs.first { r -> r.failure != null }.name} failed", it) }
    val seconds = (System.nanoTime() - start) / 1e9

    fun matrix(title: String, format: String = "%10.1f", value: (Window) -> Number) {
        println(title)
        println("   traversals |" + runs.joinToString("") { it.name.padStart(w) })
        for (i in runs[0].windows.indices) {
            println("%,13d |".format(runs[0].windows[i].end) + runs.joinToString("") { format.format(value(it.windows[i])).padStart(w) })
        }
    }
    matrix("miss% per window (traversed files not in the placer)") { it.miss }
    matrix("remote% per window, adaptive placer") { it.remote }
    matrix("remote% per window, static placer") { it.static }
    matrix("copies per window (optimisation, rebalancing, restoring to a full server), adaptive placer", "%,10d") { it.copies }

    println("totals over %,d traversals; cost = remote traversals + alpha * copies; a load adds a file to the graph (free), new or back on its servers".format(traversals))
    println("  " + "policy".padEnd(w) + "    miss%        loads   new files  local%   adaptive cost     static cost  saved%")
    for (r in runs.sortedBy { it.adaptiveCost(alpha) }) {
        val a = r.adaptive.stats
        val s = r.static.stats
        val ac = r.adaptiveCost(alpha)
        val sc = r.staticCost(alpha)
        println("  %-${w}s %7.2f %,12d %,11d %7.2f %,15.0f %,15.0f %7.1f".format(
            r.name, pct(r.loads, r.touches), r.loads, r.newFiles, pct(a.local, a.traversals), ac, sc, 100 * (1 - ac / sc)))
        check(a.unavailable == 0L && s.unavailable == 0L)
    }
    println("%.0f s wall, %d policies in parallel".format(seconds, runs.size))
    println("replicas stored per server, files in the placer and evicted ones: max / average")
    for (r in runs) {
        fun skew(i: Int) = r.disk(i).let { it.max() / it.average() }
        println("  %-${w}s adaptive %6.2f   static %6.2f".format(r.name, skew(0), skew(1)))
    }
    println("copies of the adaptive placer: optimisation (swaps), rebalancing (restoring included), repair; times a cap was exceeded")
    for (r in runs) {
        val a = r.adaptive.stats
        println("  %-${w}s %,12d (%,d) %,12d %,10d %,10d".format(r.name, a.optimizationCopies, a.swaps, a.rebalanceCopies, a.repairCopies, a.overflows))
    }
    if (csv != null) {
        if (!csv.exists()) csv.writeText("dataset,maxFiles,policy,missPct,loads,localPct,adaptiveCost,staticCost,savedPct\n")
        csv.appendText(runs.joinToString("") { r ->
            val a = r.adaptive.stats
            val ac = r.adaptiveCost(alpha)
            val sc = r.staticCost(alpha)
            "%s,%d,%s,%.4f,%d,%.4f,%.1f,%.1f,%.4f\n".format(Locale.ROOT, title, maxFiles, r.name, pct(r.loads, r.touches),
                r.loads, pct(a.local, a.traversals), ac, sc, 100 * (1 - ac / sc))
        })
    }
}

/** A policy of Eviction.kt by name; Belady needs the trace's [next] uses. */
internal fun classicEviction(name: String, files: Int, maxFiles: Int, next: IntArray? = null): Eviction = when (name) {
    "LRU" -> Lru(files)
    "FIFO" -> Fifo(files)
    "Random" -> RandomEviction(files, seed = 5)
    "CLOCK" -> Clock(files)
    "LFU" -> Lfu(files)
    "SLRU" -> Slru(files, maxFiles)
    "ARC" -> Arc(files, maxFiles)
    "W-TinyLFU" -> TinyLfu(files, maxFiles)
    "Belady" -> Belady(files, requireNotNull(next) { "Belady needs the trace" })
    else -> throw IllegalArgumentException("unknown policy $name")
}

internal val POLICIES = listOf("weight", "LRU", "FIFO", "Random", "CLOCK", "LFU", "SLRU", "ARC", "W-TinyLFU", "Belady", "all")

/**
 * The file whose pairs weigh least in the adaptive placer. The static placer follows, so both hold
 * the same files; their weights can differ by the residues of forgotten pairs (each below 1e-4),
 * since the adaptive placer forgets pairs at other moments, so only check closeness.
 */
private class WeightEviction(val adaptive: ReplicaPlacer, val static: ReplicaPlacer) : Eviction {
    override val name = "weight"
    override val size get() = adaptive.fileCount
    override fun touched(f: Int, added: Boolean) {}

    override fun evict(): Int {
        val victim = adaptive.lightestFile()!!
        val gap = static.fileWeight(victim) - static.fileWeight(static.lightestFile()!!)
        check(gap <= 0.01) { "victim $victim weighs $gap more than the lightest file in the static placer" }
        return victim
    }

    /** lightestFile against a full scan. */
    fun check(resident: BooleanArray) {
        val lightest = adaptive.lightestFile()!!
        var min = Double.POSITIVE_INFINITY
        for (f in resident.indices) if (resident[f]) min = minOf(min, adaptive.fileWeight(f))
        check(adaptive.fileWeight(lightest) <= min * (1 + 1e-12)) {
            "lightestFile weighs ${adaptive.fileWeight(lightest)}, the lightest file $min"
        }
    }
}

internal class Window(val end: Long, val miss: Double, val remote: Double, val static: Double, val copies: Long, val staticCopies: Long)

/** Keeps everything; the driver never has to evict. */
private class NoEviction : Eviction {
    override val name = "all"
    override var size = 0; private set
    override fun touched(f: Int, added: Boolean) { if (added) size++ }
    override fun evict(): Int = error("the unlimited cache never evicts")
}

/** One eviction policy with its two placers, which always hold the same files. */
internal class Policy(
    val eviction: Eviction, val adaptive: Placer, val static: Placer,
    val maxFiles: Int, files: Int, val name: String = eviction.name,
) {
    private val placers = listOf(adaptive, static)
    private val resident = BooleanArray(files)
    private var residents = 0
    var loads = 0L
    var newFiles = 0L
    // Each placer's servers of the files that left it: the data stays there, so a file that comes
    // back is restored on them instead of being placed as new.
    private val stored = placers.map { arrayOfNulls<IntArray>(files) }
    var touches = 0L
    var failure: Throwable? = null
    val windows = ArrayList<Window>()
    private var lastLoads = 0L
    private var lastTouches = 0L
    private var lastA = adaptive.stats.copy()     // stats is live; keep a snapshot
    private var lastS = static.stats.copy()

    fun adaptiveCost(alpha: Double) = adaptive.stats.commCost + alpha * adaptive.stats.copies

    /** The static placer never optimises, but restoring a file to a full server copies it too. */
    fun staticCost(alpha: Double) = static.stats.commCost + alpha * static.stats.copies

    fun traverse(pair: Pair<Int, Int>) {
        val (a, b) = pair
        touch(a, if (!resident[a] && resident[b]) b else -1)   // a new file is reached from the other
        touch(b, a)
        for (p in placers) p.addEdge(pair)
        while (residents > maxFiles) {
            val victim = eviction.evict()
            check(resident[victim]) { "$name evicted $victim, which is not in the placer" }
            for ((i, p) in placers.withIndex()) {
                stored[i][victim] = p.replicasOf(victim).toIntArray()
                p.evictFile(victim)
            }
            resident[victim] = false
            residents--
        }
    }

    /** Accesses f; if it is new, it is added as reached from [near] (-1: from nowhere in the placer). */
    private fun touch(f: Int, near: Int) {
        touches++
        val added = !resident[f]
        if (added) {
            resident[f] = true
            residents++
            for ((i, p) in placers.withIndex()) {
                val at = stored[i][f]
                when {
                    at != null -> p.restoreFile(f, at)
                    near >= 0 -> p.addFile(f, near)
                    else -> p.addFile(f)
                }
            }
            if (stored[0][f] == null) newFiles++
            loads++
        }
        eviction.touched(f, added)
    }

    /** Replicas per server of every file seen: on its placer servers if resident, else where it was left. */
    fun disk(i: Int): IntArray {
        var load = IntArray(0)
        fun add(s: Int) {
            if (s >= load.size) load = load.copyOf(s + 1)
            load[s]++
        }
        for (f in resident.indices) {
            if (resident[f]) placers[i].replicasOf(f).forEach(::add) else stored[i][f]?.forEach(::add)
        }
        return load
    }

    /** Closes the current window. */
    fun window() {
        val a = adaptive.stats - lastA
        val s = static.stats - lastS
        windows += Window(adaptive.stats.traversals, pct(loads - lastLoads, touches - lastTouches),
            pct(a.remote, a.traversals), pct(s.remote, s.traversals), a.copies, s.copies)
        lastA = adaptive.stats.copy()
        lastS = static.stats.copy()
        lastLoads = loads
        lastTouches = touches
    }

    /** Invariants of both placers, and that the policy holds exactly the placer's files. */
    fun check() {
        for (p in placers) p.checkInvariants()
        check(adaptive.fileCount == residents && static.fileCount == residents && residents <= maxFiles) {
            "$name: ${adaptive.fileCount} files in the placer, $residents resident"
        }
        check(eviction.size == residents) { "$name holds ${eviction.size} files, the placer $residents" }
        (eviction as? WeightEviction)?.check(resident)
    }
}

