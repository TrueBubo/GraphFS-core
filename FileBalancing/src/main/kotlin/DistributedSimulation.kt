import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Central against distributed placement, on the same cache and the same traversals: does
 * placement still work when no server sees the whole graph?
 *
 * Usage: DistributedSimulation wiki <dir with links.bin> [traversals] [maxFiles] [policy] [csv] [variants]
 *        DistributedSimulation twitter <higgs-activity_time.txt.gz> [maxFiles] [policy] [csv] [variants]
 *        DistributedSimulation linux <log.txt.gz> [maxFiles] [policy] [csv] [variants]
 * With csv, appends one row per variant and window (header first if the file is new). variants
 * is a comma-separated list of the names below; by default all but the cms-* size sweep run.
 * Defaults: 10M traversals and 100,000 files for Wikipedia (half-life 1M), the whole trace and
 * 10,000 files for Twitter (half-life 20,000); eviction policy LRU (any of Eviction.kt but Belady).
 *
 * Each variant runs an adaptive placer and a static one (alpha = infinity) of its own kind:
 *  - central:   ReplicaPlacer, which sees the whole graph and the true placement;
 *  - cent-near: the same, and a new file goes on the servers of the file it was reached from;
 *  - cent-rack: central, keeping every file on two racks (4 racks of 4 servers);
 *  - near-rack: cent-near, keeping every file on two racks;
 *  - distrib:   DistributedPlacer, servers that only know their own files, gossiped loads and
 *               locations learnt from traffic; other servers learn of a move through a bounce;
 *  - notify8:   the same, telling a moved file's 8 heaviest neighbours where it went;
 *  - verify:    checking the locations a move depends on with the directory before making it;
 *  - both:      notify8 and verify;
 *  - oracle:    diagnosis only, the protocol with every location known exactly at no cost;
 *  - dist-near: notify8, and a new file goes on the servers of the file it was reached from;
 *  - dist-near-rack: dist-near, keeping every file on two racks;
 *  - dist-req, dist-near-req: notify8 and dist-near, where a server that keeps reaching a file
 *    also asks for a replica of it (requestMoves);
 *  - demand, demand-near: [DemandPlacer], where every request carries the requester's distance
 *    row and a replica whose debt reaches alpha moves to the server within the slack range that
 *    its requesters' rows favour; demand-near also places a new file near; demand-near-rack
 *    keeps every file on two racks too; demand-near-cms keeps the demand in a count-min sketch
 *    per server of 4 rows of [CMS_CELLS] cells per replica it holds; their static baseline is a
 *    DemandPlacer too;
 *  - notify8-cms, dist-near-cms: notify8 and dist-near with pairs in a count-min sketch
 *    ([SketchCounts]) of 4 rows of [CMS_CELLS] cells per replica a server holds, and 16 neighbours
 *    per replica; their static baseline is the same as for the exact ones;
 *  - cms-<c>-<m>: dist-near with <c> cells per row per replica held and <m> neighbours per replica.
 * Static placers never place near (that is part of what near is measured for), so the central
 * variants share one baseline and the distributed ones another.
 * The cost of a variant counts remote traversals and copies; the protocol's messages are listed
 * apart, per traversal.
 */
fun main(args: Array<String>) {
    val usage = "usage: DistributedSimulation wiki <dir> [traversals] [maxFiles] [policy] [csv] [variants] | twitter|linux <file.gz> [maxFiles] [policy] [csv] [variants]"
    require(args.size >= 2) { usage }
    val title: String
    val files: Int
    val traversals: Long
    val maxFiles: Int
    val policy: String
    val halfLife: Double
    val stream: () -> () -> Pair<Int, Int>
    val csv: File?
    val only: List<String>?
    when (args[0]) {
        "wiki" -> {
            val clicks = Clicks.load(File(args[1], "links.bin"))
            title = "Wikipedia"
            files = clicks.articles
            traversals = args.getOrNull(2)?.toLong() ?: 10_000_000L
            maxFiles = args.getOrNull(3)?.toInt() ?: 100_000
            policy = args.getOrNull(4) ?: "LRU"
            csv = args.getOrNull(5)?.takeIf { it.isNotEmpty() }?.let(::File)
            only = args.getOrNull(6)?.split(',')
            halfLife = 1_000_000.0
            stream = {
                val rnd = Random(17)
                ({ clicks.sample(rnd) })
            }
        }
        "twitter" -> {
            val higgs = Higgs.load(File(args[1]))
            title = "Twitter Higgs"
            files = higgs.users
            traversals = higgs.size.toLong()
            maxFiles = args.getOrNull(2)?.toInt() ?: 10_000
            policy = args.getOrNull(3) ?: "LRU"
            csv = args.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let(::File)
            only = args.getOrNull(5)?.split(',')
            halfLife = 20_000.0
            stream = higgs::stream
        }
        "linux" -> {
            val history = GitHistory.load(File(args[1]))
            title = "Linux"
            files = history.files
            traversals = history.size.toLong()
            maxFiles = args.getOrNull(2)?.toInt() ?: 10_000
            policy = args.getOrNull(3) ?: "LRU"
            csv = args.getOrNull(4)?.takeIf { it.isNotEmpty() }?.let(::File)
            only = args.getOrNull(5)?.split(',')
            halfLife = 100_000.0
            stream = history::stream
        }
        else -> throw IllegalArgumentException(usage)
    }

    val n = 16
    val k = 2
    val alpha = 8.0
    val capacity = IntArray(n) { maxFiles }
    val racks = IntArray(n) { it / RACK_SIZE }
    // A server holds about k / n of the replicas in the placer; a smaller cache needs a smaller sketch.
    val heldPerServer = (minOf(maxFiles, files).toLong() * k / n).toInt()
    fun sketch(cells: Int, neighbours: Int): (Double) -> PairCounts = { SketchCounts(it, cells * heldPerServer, neighbours) }
    fun nearCms(cells: Int, neighbours: Int) = "cms-$cells-$neighbours" to { a: Double ->
        DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, placeNear = true, pairCounts = sketch(cells, neighbours), seed = 3) as Placer
    }
    val sweep = CMS_SWEEP.map { (c, m) -> nearCms(c, m) }
    val variants: List<Pair<String, (Double) -> Placer>> = listOf<Pair<String, (Double) -> Placer>>(
        "central" to { a -> ReplicaPlacer(capacity, k, alpha = a, halfLife = halfLife, seed = 3) },
        "cent-near" to { a -> ReplicaPlacer(capacity, k, alpha = a, halfLife = halfLife, placeNear = true, seed = 3) },
        "cent-rack" to { a -> ReplicaPlacer(capacity, k, alpha = a, halfLife = halfLife, racks = racks, seed = 3) },
        "near-rack" to { a -> ReplicaPlacer(capacity, k, alpha = a, halfLife = halfLife, racks = racks, placeNear = true, seed = 3) },
        "distrib" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, seed = 3) },
        "notify8" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, seed = 3) },
        "verify" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, verify = true, seed = 3) },
        "both" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, verify = true, seed = 3) },
        "oracle" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, oracle = true, seed = 3) },
        "dist-near" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, placeNear = true, seed = 3) },
        "dist-req" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, requestMoves = true, seed = 3) },
        "dist-near-req" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, placeNear = true, requestMoves = true, seed = 3) },
        "demand" to { a -> DemandPlacer(capacity, k, alpha = a, halfLife = halfLife, seed = 3) },
        "demand-near" to { a -> DemandPlacer(capacity, k, alpha = a, halfLife = halfLife, placeNear = true, seed = 3) },
        "demand-near-rack" to { a -> DemandPlacer(capacity, k, alpha = a, halfLife = halfLife, placeNear = true, racks = racks, seed = 3) },
        "demand-near-cms" to { a ->
            DemandPlacer(capacity, k, alpha = a, halfLife = halfLife, placeNear = true, sketchCells = CMS_CELLS * heldPerServer, seed = 3)
        },
        "dist-near-rack" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, placeNear = true, racks = racks, seed = 3) },
        "notify8-cms" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, pairCounts = sketch(CMS_CELLS, CMS_NEIGHBOURS), seed = 3) },
        "dist-near-cms" to { a -> DistributedPlacer(capacity, k, alpha = a, halfLife = halfLife, notify = 8, placeNear = true, pairCounts = sketch(CMS_CELLS, CMS_NEIGHBOURS), seed = 3) },
    ).let { all -> if (only == null) all else (all + sweep).filter { it.first in only } }
    println("== %s, %s eviction: central vs distributed placement; at most %,d of %,d files, %d servers, k = %d, alpha = %.0f, half-life %,.0f =="
        .format(title, policy, maxFiles, files, n, k, alpha, halfLife))
    val runs = variants.map { (label, make) ->
        val rk = if (label in RACKED) racks else null
        val static = if (label in CENTRAL) ReplicaPlacer(capacity, k, alpha = Double.POSITIVE_INFINITY, halfLife = halfLife, racks = rk, seed = 3)
            else if (label in DEMAND) DemandPlacer(capacity, k, alpha = Double.POSITIVE_INFINITY, halfLife = halfLife, racks = rk, seed = 3)
            else DistributedPlacer(capacity, k, alpha = Double.POSITIVE_INFINITY, halfLife = halfLife, racks = rk, seed = 3)
        Policy(classicEviction(policy, files, maxFiles), make(alpha), static, maxFiles, files, label)
    }
    runPolicies(title, maxFiles, traversals, alpha, runs, csv = null, stream = stream)

    if (csv != null) {
        if (!csv.exists()) csv.writeText("dataset,policy,maxFiles,variant,traversals,remotePct,staticPct,copies,staticCopies,pairs,pairBytes,locationBytes\n")
        csv.appendText(runs.joinToString("") { r ->
            r.windows.joinToString("") { w ->
                "%s,%s,%d,%s,%d,%.4f,%.4f,%d,%d,%d,%d,%d\n".format(Locale.ROOT, title, policy, maxFiles, r.name, w.end, w.remote, w.static, w.copies, w.staticCopies,
                    w.memory?.pairs ?: 0L, w.memory?.pairBytes ?: 0L, w.memory?.locationBytes ?: 0L)
            }
        })
    }
    val w = maxOf(9, runs.maxOf { it.name.length })              // column width for the names
    println("moves of the adaptive placers")
    for (r in runs) {
        val st = r.adaptive.stats
        println("  %-${w}s %,9d copies, %,9d of them in %,d swaps".format(r.name, st.optimizationCopies, 2 * st.swaps, st.swaps))
    }
    println("availability: files with every live replica in one rack of $RACK_SIZE (lost if that rack fails)")
    println("  " + "variant".padEnd(w) + "   adaptive: worst rack, all racks       static: worst rack, all racks")
    for (r in runs) {
        val a = r.adaptive
        val s = r.static
        fun lost(p: Placer): Pair<Int, Int> {
            val byRack = racks.distinct().associateWith { rack ->
                (0 until files).count { f -> p.isKnown(f) && p.replicasOf(f).let { it.isNotEmpty() && it.all { srv -> racks[srv] == rack } } }
            }
            return byRack.values.max() to byRack.values.sum()
        }
        val (aw, at) = lost(a)
        val (sw, sa) = lost(s)
        println("  %-${w}s %12.2f%% %12.2f%% %18.2f%% %12.2f%%".format(r.name,
            100.0 * aw / a.fileCount, 100.0 * at / a.fileCount, 100.0 * sw / s.fileCount, 100.0 * sa / s.fileCount))
    }
    println("memory of the adaptive distributed placers, all servers, at the end and at the peak of the windows")
    println("  " + "variant".padEnd(w) + "        pairs   pairs MB  locations MB   peak total MB")
    for (r in runs) {
        val m = (r.adaptive as? DistributedPlacer)?.memory ?: continue
        val peak = r.windows.maxOf { (it.memory?.pairBytes ?: 0L) + (it.memory?.locationBytes ?: 0L) }
        println("  %-${w}s %,12d %10.1f %13.1f %15.1f".format(r.name, m.pairs, m.pairBytes / 1e6, m.locationBytes / 1e6, peak / 1e6))
    }
    println("protocol of the adaptive distributed placers (per traversal unless noted)")
    println("  " + "variant".padEnd(w) + "  messages  lookups  bounces  verified   evaluations   moves  refused  requests  granted  bad moves  predicted gain   true gain  max/avg load")
    for (r in runs) {
        val p = (r.adaptive as? DistributedPlacer)?.protocol ?: continue
        val t = r.adaptive.stats.traversals.toDouble()
        println("  %-${w}s %10.3f %8.3f %8.3f %9.3f %13d %7d %8d %9d %8d %9.1f%% %15.0f %11.0f %13.3f".format(
            r.name, p.messages / t, p.lookups / t, p.redirects / t, p.verified / t, p.evaluations, p.moves, p.rejected,
            p.moveRequests, p.granted, pct(p.badMoves, p.moves), p.predictedGain, p.trueGain, (r.adaptive as DistributedPlacer).imbalance))
    }
    if (runs.none { it.adaptive is DemandPlacer }) return
    println("demand placers: requests per traversal, decisions, moves, decisions that found the best server full, demand MB, max/avg load")
    for (r in runs) {
        val dp = r.adaptive as? DemandPlacer ?: continue
        val p = dp.protocol
        println("  %-${w}s %8.3f %13d %7d %8d %10.1f %13.3f".format(
            r.name, p.requests / r.adaptive.stats.traversals.toDouble(), p.evaluations, p.moves, p.full, dp.demandBytes / 1e6, dp.imbalance))
    }
}

/** The variants that run ReplicaPlacer; their static baseline is a central one too. */
private val CENTRAL = setOf("central", "cent-near", "cent-rack", "near-rack")

/** The variants that run DemandPlacer; their static baseline is one too. */
private val DEMAND = setOf("demand", "demand-near", "demand-near-rack", "demand-near-cms")

/** The variants that keep each file on two racks; their static baseline does too. */
private val RACKED = setOf("cent-rack", "near-rack", "dist-near-rack", "demand-near-rack")

/** Cells per row of the sketch, per replica a server holds, and neighbours per replica, of the *-cms variants. */
private const val CMS_CELLS = 4
private const val CMS_NEIGHBOURS = 16

/** Cells per replica and neighbours per replica of the cms-<c>-<m> variants. */
private val CMS_SWEEP = listOf(1, 2, 4, 8, 16).flatMap { c -> listOf(4, 8, 16, 32).map { m -> c to m } }

/** Servers per rack, for the rack variants and the availability table. */
private const val RACK_SIZE = 4
