import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

fun main() {
    demo()
    gainCheck()
    gainCheck(randomDistances(5, Random(19)))
    fuzz()
    fuzz(replicaCount = 3, minReplicas = 2, grace = 300)
    fuzz(replicaCount = 3, minReplicas = 2, grace = 300, distances = randomDistances(5, Random(23)))
    rackFuzz(replicaCount = 2)
    rackFuzz(replicaCount = 3, minReplicas = 2, grace = 300)
    restoreFuzz()
    distributedFuzz()
    distributedFuzz(notify = 8, placeNear = true, racks = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3))
    distributedFuzz(verify = true)
    distributedFuzz(notify = 8, placeNear = true, pairCounts = { SketchCounts(it, 64, neighbours = 4) })
    distributedFuzz(requestMoves = true)
    distributedFuzz(notify = 8, placeNear = true, requestMoves = true, racks = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3), distances = randomDistances(8, Random(47)))
    distributedFuzz(replicaCount = 1, requestMoves = true, distances = randomDistances(8, Random(53)))
    demandFuzz()
    demandFuzz(placeNear = true, distances = randomDistances(8, Random(59)))
    demandFuzz(replicaCount = 1, distances = randomDistances(8, Random(61)))
    demandFuzz(placeNear = true, racks = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3))
    demandFuzz(placeNear = true, racks = intArrayOf(0, 0, 0, 0, 1, 1, 1, 1), distances = randomDistances(8, Random(67)))
    demandFuzz(placeNear = true, sketchCells = 64)
    demandFuzz(placeNear = true, racks = intArrayOf(0, 0, 1, 1, 2, 2, 3, 3), distances = randomDistances(8, Random(71)), sketchCells = 16)
    sketchCheck()
    simulation()
    lruSimulation()
    rackSimulation(k = 1)
    rackSimulation(k = 2)
    benchmark()
    cacheCheck()
}

private fun show(p: ReplicaPlacer, files: IntRange) = files.joinToString("  ") { "$it:${p.replicasOf(it)}" }

/** Walk-through of the API: co-location, a server going down and back, and an unavailable file. */
fun demo() {
    println("== demo: 4 servers, k = 2, alpha = 2 ==")
    val p = ReplicaPlacer(servers = IntArray(4) { 1_000 }, replicaCount = 2, alpha = 2.0, repairRate = Double.POSITIVE_INFINITY, seed = 2)
    val all = 0 until 8
    for (f in all) p.addFile(f)
    println("start: ${show(p, all)}")
    p.onChange = { println("    $it") }

    val group = 0..3
    fun localPairs() = group.sumOf { a -> (a + 1..3).count { b -> p.replicasOf(a).any { it in p.replicasOf(b) } } }
    println("start: ${localPairs()} of 6 pairs among files 0..3 share a server; now they are traversed together:")
    repeat(5) { for (a in group) for (b in a + 1..3) p.addEdge(a to b) }
    println("now:   ${show(p, all)}   local pairs ${localPairs()} of 6")

    val s = (0 until p.serverCount).maxBy { srv -> group.count { srv in p.replicasOf(it) } }
    println("taking down server $s, which holds most of them:")
    p.toggleComponent(s)
    println("now:   ${show(p, all)}   local pairs ${localPairs()} of 6")
    println("server $s back up:")
    p.toggleComponent(s)
    println("now:   ${show(p, all)}   local pairs ${localPairs()} of 6")
    p.checkInvariants()

    p.onChange = null
    val q = ReplicaPlacer(servers = IntArray(2) { 1_000 }, replicaCount = 1)
    q.addFile(0)
    q.addFile(1)
    val only = q.replicasOf(0).single()
    q.toggleComponent(only)
    println("k = 1, the only server of file 0 is down: 0-1 is ${q.addEdge(0 to 1)}")
    q.toggleComponent(only)
    println("and back up: 0-1 is ${q.addEdge(0 to 1)}")

    val r = ReplicaPlacer(servers = IntArray(6) { 1_000 }, replicaCount = 3, repairRate = 0.25, seed = 4)
    val some = 0 until 12
    for (f in some) r.addFile(f)
    fun byLive() = (1..2).joinToString("  ") { live -> "$live live: ${some.count { r.replicasOf(it).size == live }}" }
    r.toggleComponent(0)
    r.toggleComponent(1)
    println("k = 3, repairRate = 0.25, servers 0 and 1 down: ${byLive()}, ${r.pendingRepairs} files queued")
    var t = 0
    while (r.pendingRepairs > 0) {
        r.addEdge(0 to 1)
        if (++t % 4 == 0) println("  after $t traversals: ${byLive()}")
    }
    r.checkInvariants()

    val g = ReplicaPlacer(servers = IntArray(6) { 1_000 }, replicaCount = 3, minReplicas = 2, grace = 1_000, seed = 5)
    for (f in 0 until 60) g.addFile(f)
    fun traverse(times: Int) = repeat(times) { g.addEdge(it % 60 to (it * 7 + 1) % 60) }
    fun repairs() = g.stats.repairCopies
    g.toggleComponent(0)
    traverse(500)
    g.toggleComponent(0)
    println("k = 3, minReplicas = 2, grace = 1000: server 0 down for 500 traversals: ${repairs()} repair copies")
    g.toggleComponent(0)
    traverse(3_000)
    val long = repairs()
    println("server 0 down for 3000 traversals: $long repair copies (${g.load(0)} files were on it)")
    g.toggleComponent(0)
    traverse(1_000)
    g.toggleComponent(1)
    g.toggleComponent(2)
    traverse(100)
    println("servers 1 and 2 down together, 100 traversals: ${repairs() - long} repair copies, " +
        "for the files that had a copy on both and were left with one")
    g.checkInvariants()

    val h = ReplicaPlacer(servers = intArrayOf(20, 20, 40, 80), replicaCount = 2, seed = 6)
    for (f in 0 until 60) h.addFile(f)
    fun loads() = (0 until h.serverCount).joinToString("  ") {
        if (h.isUp(it)) "${h.load(it)}/${h.capacity(it)}" else "down"
    }
    println("capacities ${h.servers.toList()}, 60 files, k = 2, load/cap: ${loads()}")
    h.toggleComponent(0)
    while (h.pendingRepairs > 0) h.addEdge(0 to 1)
    println("server 0 down and repaired:                 ${loads()}")
    h.checkInvariants()
    println()
}

/** Re-checks every optimisation move against a full recomputation of the weighted cost. */
fun gainCheck(distances: Array<DoubleArray>? = null) {
    val p = ReplicaPlacer(
        servers = intArrayOf(20, 20, 30, 40, 50), replicaCount = 2, alpha = 3.0, halfLife = 2_000.0, slack = 0.0,
        distances = distances, seed = 7,
    )
    p.verifyGains = true
    val rnd = Random(11)
    val files = 60
    for (f in 0 until files) p.addFile(f)
    for (i in 1..40_000) {
        val a = rnd.nextInt(files)
        val b = if (rnd.nextDouble() < 0.8) a / 10 * 10 + rnd.nextInt(10) else rnd.nextInt(files)
        p.addEdge(a to b)
        if (i == 15_000 || i == 25_000) p.toggleComponent(3)
        if (i % 1_000 == 0) p.checkInvariants()
    }
    println("== gain check${if (distances != null) " (random distances)" else ""}: ${p.stats.optimizationCopies} copies (${p.stats.swaps} swaps), " +
        "each matched a full recomputation of the cost ==")
}

/** Random operations, including all servers down, with invariants and gains checked after every step. */
fun fuzz(replicaCount: Int = 2, minReplicas: Int = replicaCount, grace: Long = 0L, distances: Array<DoubleArray>? = null) {
    val p = ReplicaPlacer(
        servers = IntArray(5) { 1_000 }, replicaCount = replicaCount, alpha = 1.0, halfLife = 300.0, slack = 0.0,
        minReplicas = minReplicas, grace = grace, distances = distances, seed = 21,
    )
    p.verifyGains = true
    p.sweepEvery = 64                                 // pairs fade (half-life 300) and are swept all the time
    val rnd = Random(17)
    var nextFile = 0
    var unavailable = 0
    var allDown = 0
    val steps = 30_000
    for (step in 1..steps) {
        val r = rnd.nextDouble()
        val anyUp = (0 until p.serverCount).any { p.isUp(it) }
        when {
            r < 0.01 -> p.toggleComponent(rnd.nextInt(p.serverCount))
            r < 0.015 || nextFile < 2 -> if (anyUp) p.addFile(nextFile++) else p.toggleComponent(rnd.nextInt(p.serverCount))
            else -> if (p.addEdge(rnd.nextInt(nextFile) to rnd.nextInt(nextFile)) == Access.UNAVAILABLE) unavailable++
        }
        if (!anyUp) allDown++
        p.checkInvariants()
    }
    val dist = if (distances != null) ", random distances" else ""
    println("== fuzz (k = $replicaCount, minReplicas = $minReplicas, grace = $grace$dist): $steps random steps (all servers down in $allDown of them), invariants held; " +
        "${p.fileCount} files, ${p.stats.optimizationCopies} verified copies, " +
        "$unavailable unavailable traversals, ${p.stats.overflows} overflows ==")
    println()
}

/**
 * The fuzz test with 6 servers in 3 racks: invariants after every step, and at the end, with
 * every server up and every file traversed once, no file may be left in a single rack.
 */
fun rackFuzz(replicaCount: Int, minReplicas: Int = replicaCount, grace: Long = 0L) {
    val racks = intArrayOf(0, 0, 1, 1, 2, 2)
    val p = ReplicaPlacer(
        servers = IntArray(6) { 1_000 }, replicaCount = replicaCount, alpha = 1.0, halfLife = 300.0, slack = 0.0,
        minReplicas = minReplicas, grace = grace, racks = racks, seed = 21,
    )
    p.verifyGains = true
    p.sweepEvery = 64
    val rnd = Random(29)
    var nextFile = 0
    var maxInOneRack = 0
    val steps = 30_000
    for (step in 1..steps) {
        val r = rnd.nextDouble()
        val anyUp = (0 until p.serverCount).any { p.isUp(it) }
        when {
            r < 0.01 -> p.toggleComponent(rnd.nextInt(p.serverCount))
            r < 0.015 || nextFile < 2 -> if (anyUp) p.addFile(nextFile++) else p.toggleComponent(rnd.nextInt(p.serverCount))
            else -> p.addEdge(rnd.nextInt(nextFile) to rnd.nextInt(nextFile))
        }
        p.checkInvariants()
        maxInOneRack = maxOf(maxInOneRack, p.filesInOneRack())
    }
    for (s in 0 until p.serverCount) if (!p.isUp(s)) p.toggleComponent(s)
    for (f in 0 until nextFile) p.addEdge(f to (f + 1) % nextFile)   // repairs and re-spreads happen on traversals
    repeat(10 * nextFile) { p.addEdge(rnd.nextInt(nextFile) to rnd.nextInt(nextFile)) }
    for (f in 0 until nextFile) p.addEdge(f to (f + 1) % nextFile)
    p.checkInvariants()
    check(p.filesInOneRack() == 0) {
        val bad = (0 until nextFile).filter { f -> p.replicasOf(f).map { racks[it] }.distinct().size < 2 }
        "${bad.size} files left in one rack: " + bad.joinToString { "$it:${p.replicasOf(it)}" } +
            "; loads " + (0 until p.serverCount).joinToString { "${p.load(it)}/${p.capacity(it)}" }
    }
    check(p.lostIfRackFails().values.all { it == 0 }) { "a rack failure would lose files: ${p.lostIfRackFails()}" }
    println("== rack fuzz (k = $replicaCount, minReplicas = $minReplicas, grace = $grace, 3 racks of 2): $steps random steps, " +
        "invariants held; at most $maxInOneRack of ${p.fileCount} files in one rack during failures, 0 after recovery; " +
        "${p.stats.optimizationCopies} verified copies, ${p.stats.repairCopies} repair copies ==")
    println()
}

/**
 * Files leave the placer and come back (restoreFile) under random failures, on small servers so
 * that some come back to a full server: invariants after every step, and a file whose servers
 * are all up and below their cap must come back to exactly those servers without a copy.
 */
fun restoreFuzz() {
    val p = ReplicaPlacer(IntArray(6) { 60 }, 2, alpha = 1.0, halfLife = 300.0, slack = 0.3, racks = intArrayOf(0, 0, 1, 1, 2, 2), seed = 41)
    p.verifyGains = true
    p.sweepEvery = 64
    val rnd = Random(43)
    val live = ArrayList<Int>()
    val gone = LinkedHashMap<Int, IntArray>()
    var nextFile = 0
    var free = 0
    var moved = 0
    val steps = 30_000
    for (step in 1..steps) {
        val r = rnd.nextDouble()
        val anyUp = (0 until p.serverCount).any { p.isUp(it) }
        when {
            r < 0.005 -> p.toggleComponent(rnd.nextInt(p.serverCount))
            !anyUp -> p.toggleComponent(rnd.nextInt(p.serverCount))
            live.size < 2 || r < 0.03 && live.size < 80 -> {
                if (gone.isNotEmpty() && rnd.nextBoolean()) {
                    val f = gone.keys.elementAt(rnd.nextInt(gone.size))
                    val at = gone.remove(f)!!
                    val stays = at.size == 2 && at.all { p.isUp(it) && p.load(it) < p.capacity(it) }
                    val copies = p.stats.copies
                    p.restoreFile(f, at)
                    if (stays) {
                        check(p.replicasOf(f).sorted() == at.sorted() && p.stats.copies == copies) {
                            "file $f stored on ${at.toList()} came back to ${p.replicasOf(f)} with ${p.stats.copies - copies} copies"
                        }
                        free++
                    } else moved++
                    live.add(f)
                } else {
                    p.addFile(nextFile)
                    live.add(nextFile++)
                }
            }
            r < 0.05 -> {
                val f = live.removeAt(rnd.nextInt(live.size))
                gone[f] = p.replicasOf(f).toIntArray()
                p.evictFile(f)
            }
            else -> p.addEdge(live[rnd.nextInt(live.size)] to live[rnd.nextInt(live.size)])
        }
        p.checkInvariants()
    }
    println("== restore fuzz (6 servers of 60, slack 0.3, k = 2, 3 racks, failures): $steps random steps, invariants held; " +
        "$free files came back to their servers for free, $moved came back to a full or down server ($nextFile files) ==")
    println()
}

/**
 * [SketchCounts] against [ExactCounts] on the same traversals. A sketch much larger than the pairs
 * must give the exact weights, and when it is small it may overstate a pair but never understate
 * it. Only the heaviest pairs have to stay among a file's neighbours.
 */
fun sketchCheck() {
    val lambda = ln(2.0) / 1_000.0
    val big = SketchCounts(lambda, 1 shl 20, neighbours = 64)
    val small = SketchCounts(lambda, 32, neighbours = 8)
    val exact = ExactCounts(lambda)
    val files = (0 until 8).map { Triple(exact.open(it), big.open(it), small.open(it)) }
    val rnd = Random(41)
    val zipf = Zipf(32, 1.0, rnd)
    for (t in 1L..20_000L) {
        val (a, b, c) = files[rnd.nextInt(files.size)]
        val g = zipf.next()
        a.add(g, t)
        b.add(g, t)
        c.add(g, t)
    }
    val now = 20_000L
    var overstated = 0.0
    var total = 0.0
    for ((a, b, c) in files) {
        val truth = HashMap<Int, Double>()
        a.forEach(now) { g, w -> truth[g] = w }
        val seen = HashMap<Int, Double>()
        b.forEach(now) { g, w -> seen[g] = w }
        check(seen.keys == truth.keys) { "the large sketch lost pairs" }
        for ((g, w) in truth) check(abs(seen.getValue(g) - w) <= 1e-4 * (1 + w)) { "pair with $g: ${seen[g]}, really $w" }
        var count = 0
        c.forEach(now) { g, w ->
            val real = truth.getValue(g)
            check(w >= real * (1 - 1e-4)) { "small sketch understates $g: $w, really $real" }
            overstated += w - real
            total += real
            count++
        }
        check(count == c.count && count <= 8)
        val heaviest = truth.maxBy { it.value }.key
        var kept = false
        c.forEach(now) { g, _ -> if (g == heaviest) kept = true }
        check(kept) { "the heaviest neighbour $heaviest was dropped" }
    }
    println("== sketch check: exact weights with a large sketch; small sketch (4 x 32 counters, 8 neighbours) overstates kept pairs by %.1f %% in total ==".format(100 * overstated / total))
    println()
}

/**
 * DistributedPlacer under random adds, removes and traversals, with a short half-life and sweep
 * period so pairs fade and are swept all the time: invariants after every step.
 */
fun distributedFuzz(
    notify: Int = 0, verify: Boolean = false, placeNear: Boolean = false, racks: IntArray? = null,
    pairCounts: (Double) -> PairCounts = ::ExactCounts, requestMoves: Boolean = false,
    distances: Array<DoubleArray>? = null, replicaCount: Int = 2,
) {
    val p = DistributedPlacer(
        IntArray(8) { 60 }, replicaCount, alpha = 2.0, halfLife = 50.0, gossipEvery = 20, notify = notify, verify = verify,
        placeNear = placeNear, racks = racks, distances = distances, requestMoves = requestMoves, pairCounts = pairCounts, seed = 31,
    )
    p.sweepEvery = 64
    val rnd = Random(37)
    val live = ArrayList<Int>()
    val gone = LinkedHashMap<Int, IntArray>()             // removed files and the servers they stay stored on
    var nextFile = 0
    var restores = 0
    val steps = 30_000
    for (step in 1..steps) {
        val r = rnd.nextDouble()
        when {
            live.size < 2 || r < 0.02 && live.size < 150 -> {
                if (gone.isNotEmpty() && rnd.nextBoolean()) {
                    val f = gone.keys.elementAt(rnd.nextInt(gone.size))
                    p.restoreFile(f, gone.remove(f)!!)
                    live.add(f)
                    restores++
                } else {
                    p.addFile(nextFile, if (live.isEmpty()) -1 else live[rnd.nextInt(live.size)])
                    live.add(nextFile++)
                }
            }
            r < 0.03 -> {
                val f = live.removeAt(rnd.nextInt(live.size))
                gone[f] = p.replicasOf(f).toIntArray()
                p.evictFile(f)
            }
            else -> {
                // Pairs within small groups of files, so some pairs repeat and some fade.
                val u = live[rnd.nextInt(live.size)]
                val v = live.getOrNull(live.indexOf(u) + 1 + rnd.nextInt(3)) ?: live[rnd.nextInt(live.size)]
                p.addEdge(u to v)
            }
        }
        p.checkInvariants()
    }
    println("== distributed fuzz (k = $replicaCount, notify = $notify, verify = $verify, placeNear = $placeNear, racks = ${racks != null}, " +
        "requestMoves = $requestMoves, distances = ${distances != null}): $steps random steps, " +
        "invariants held; ${p.fileCount} files, ${p.protocol.moves} moves (${p.protocol.granted} of ${p.protocol.moveRequests} requests granted), " +
        "${p.stats.remote} remote traversals, " +
        "$restores restores with ${p.stats.rebalanceCopies} copies ==")
    println()
}

/** [distributedFuzz] for [DemandPlacer]. */
fun demandFuzz(
    placeNear: Boolean = false, racks: IntArray? = null, distances: Array<DoubleArray>? = null, replicaCount: Int = 2, sketchCells: Int = 0,
) {
    val p = DemandPlacer(
        IntArray(8) { 60 }, replicaCount, alpha = 2.0, halfLife = 50.0, slack = 0.1, placeNear = placeNear, racks = racks,
        distances = distances, sketchCells = sketchCells, seed = 31,
    )
    val rnd = Random(37)
    val live = ArrayList<Int>()
    val gone = LinkedHashMap<Int, IntArray>()             // removed files and the servers they stay stored on
    var nextFile = 0
    var restores = 0
    val steps = 30_000
    for (step in 1..steps) {
        val r = rnd.nextDouble()
        when {
            live.size < 2 || r < 0.02 && live.size < 150 -> {
                if (gone.isNotEmpty() && rnd.nextBoolean()) {
                    val f = gone.keys.elementAt(rnd.nextInt(gone.size))
                    p.restoreFile(f, gone.remove(f)!!)
                    live.add(f)
                    restores++
                } else {
                    p.addFile(nextFile, if (live.isEmpty()) -1 else live[rnd.nextInt(live.size)])
                    live.add(nextFile++)
                }
            }
            r < 0.03 -> {
                val f = live.removeAt(rnd.nextInt(live.size))
                gone[f] = p.replicasOf(f).toIntArray()
                p.evictFile(f)
            }
            else -> {
                val u = live[rnd.nextInt(live.size)]
                val v = live.getOrNull(live.indexOf(u) + 1 + rnd.nextInt(3)) ?: live[rnd.nextInt(live.size)]
                p.addEdge(u to v)
            }
        }
        p.checkInvariants()
    }
    println("== demand fuzz (k = $replicaCount, placeNear = $placeNear, racks = ${racks != null}, distances = ${distances != null}, sketchCells = $sketchCells): $steps random steps, " +
        "invariants held; ${p.fileCount} files, ${p.protocol.moves} moves of ${p.protocol.evaluations} decisions " +
        "(${p.protocol.full} found the best server full), ${p.stats.remote} remote traversals, " +
        "$restores restores with ${p.stats.rebalanceCopies} copies, max/avg load ${"%.2f".format(p.imbalance)} ==")
    println()
}

/** Files form groups that are traversed together; the groups change part way through. */
fun simulation() {
    val n = 8
    val k = 2
    val files = 400
    val groups = 20
    val alpha = 8.0
    val adaptive = ReplicaPlacer(IntArray(n) { 1_000 }, k, alpha = alpha, halfLife = 20_000.0, seed = 3)
    val static = ReplicaPlacer(IntArray(n) { 1_000 }, k, alpha = Double.POSITIVE_INFINITY, halfLife = 20_000.0, seed = 3)
    for (f in 0 until files) {
        adaptive.addFile(f)
        static.addFile(f)
    }
    val rnd = Random(5)
    var members = randomGroups(files, groups, rnd)
    val pick = { members[rnd.nextInt(members.size)] }

    println("== simulation: $n servers, k = $k, $files files in $groups groups, alpha = $alpha ==")
    println("90 % of traversals stay inside a group, 10 % are random pairs.")
    println("static = same start, never optimises (it still repairs after a failure).")
    println("traversals  remote%  static%  copies  static copies  unavailable%")
    var lastA = adaptive.stats.copy()
    var lastS = static.stats.copy()
    val window = 50_000
    for (t in 1..700_000) {
        when (t) {
            200_001 -> {
                adaptive.toggleComponent(0); static.toggleComponent(0); println("   -- server 0 down")
            }
            300_001 -> {
                adaptive.toggleComponent(0); static.toggleComponent(0); println("   -- server 0 up")
            }
            450_001 -> {
                members = randomGroups(files, groups, rnd); println("   -- groups reshuffled")
            }
        }
        val pair = nextPair(pick(), files, rnd)
        adaptive.addEdge(pair)
        static.addEdge(pair)
        if (t % window == 0) {
            val a = adaptive.stats - lastA
            val s = static.stats - lastS
            println(
                "%10d  %7.1f  %7.1f  %6d  %13d  %12.2f".format(
                    t, pct(a.remote, a.traversals), pct(s.remote, s.traversals), a.copies, s.copies,
                    pct(a.unavailable, a.traversals),
                )
            )
            lastA = adaptive.stats.copy()
            lastS = static.stats.copy()
            adaptive.checkInvariants()
            static.checkInvariants()
        }
    }
    val a = adaptive.stats
    val s = static.stats
    println("total cost = remote traversals + alpha * copies")
    println("  adaptive: %.0f + %.0f * %d = %.0f".format(a.commCost, alpha, a.copies, a.commCost + alpha * a.copies))
    println("  static:   %.0f + %.0f * %d = %.0f".format(s.commCost, alpha, s.copies, s.commCost + alpha * s.copies))
    println("adaptive copies: ${a.optimizationCopies} optimisation (${a.swaps} swaps), " +
        "${a.repairCopies} repair, ${a.rebalanceCopies} rebalance, overflows ${a.overflows}")
    println()
}

/**
 * Two racks of 4 servers; a traversal or copy within a rack costs 1, across racks [far]. The same
 * workload runs on a placer that knows the distances and on one that assumes a uniform network;
 * both are charged the real distances. With k = 1 a group of 100 is too big for one server's cap.
 */
fun rackSimulation(k: Int, far: Double = 4.0, groups: Int = 4) {
    val n = 8
    val files = 400
    val alpha = 8.0
    val distances = racks(n, rackSize = 4, far = far)
    val aware = ReplicaPlacer(IntArray(n) { 1_000 }, k, alpha = alpha, halfLife = 20_000.0, distances = distances, seed = 3)
    val uniform = ReplicaPlacer(IntArray(n) { 1_000 }, k, alpha = alpha, halfLife = 20_000.0, seed = 3)
    val ledgers = listOf(aware, uniform).map { Ledger(it, distances) }
    for (f in 0 until files) for (p in listOf(aware, uniform)) p.addFile(f)
    for (l in ledgers) l.reset()
    val rnd = Random(5)
    val members = randomGroups(files, groups, rnd)
    val events = 400_000
    for (t in 1..events) {
        if (t == events / 2) for (p in listOf(aware, uniform)) p.toggleComponent(0)
        val pair = nextPair(members[rnd.nextInt(groups)], files, rnd)
        for (l in ledgers) l.traverse(pair)
    }
    for (p in listOf(aware, uniform)) p.checkInvariants()
    println("== racks: $n servers in 2 racks, distance 1 within a rack and $far across, k = $k, " +
        "$files files in $groups groups of ${files / groups}, alpha = $alpha, server 0 down half way ==")
    println("cost = traversal distance + alpha * copy distance, both at the real distances")
    for ((name, l) in listOf("aware", "uniform").zip(ledgers)) {
        println("  %-8s %.0f + %.0f * %.0f = %.0f  (%d copies, %.1f %% of traversals cross racks)".format(
            name, l.comm, alpha, l.copyDist, l.comm + alpha * l.copyDist, l.copies, pct(l.crossRack, events.toLong())))
    }
    println()
}

/**
 * Charges a placer's traversals and copies at [distances], whatever distances the placer itself
 * assumes. It mirrors the placement from [ReplicaPlacer.onChange]: a copy is reported before the
 * matching delete, so it travels from the nearest live copy known at that moment.
 */
private class Ledger(val p: ReplicaPlacer, val distances: Array<DoubleArray>) {
    private val copiesOf = HashMap<Int, MutableSet<Int>>()
    var comm = 0.0
    var copyDist = 0.0
    var copies = 0
    var crossRack = 0L

    init {
        p.onChange = { c ->
            val have = copiesOf.getOrPut(c.file) { HashSet() }
            when (c) {
                is Change.Copy -> {
                    val from = have.filter { p.isUp(it) }
                    if (from.isNotEmpty()) {
                        copyDist += from.minOf { distances[it][c.to] }
                        copies++
                    }
                    have.add(c.to)
                }
                is Change.Delete -> have.remove(c.from)
            }
        }
    }

    fun reset() {
        comm = 0.0
        copyDist = 0.0
        copies = 0
    }

    /** Charges the traversal at the placement it meets, before addEdge moves anything for it. */
    fun traverse(pair: Pair<Int, Int>) {
        val a = p.replicasOf(pair.first)
        val b = p.replicasOf(pair.second)
        p.addEdge(pair)
        if (a.isEmpty() || b.isEmpty()) return
        val d = a.minOf { x -> b.minOf { y -> distances[x][y] } }
        comm += d
        if (d > 1.0) crossRack++
    }
}

private fun racks(n: Int, rackSize: Int, far: Double) =
    Array(n) { a -> DoubleArray(n) { b -> if (a == b) 0.0 else if (a / rackSize == b / rackSize) 1.0 else far } }

/** Symmetric distances between 1 and 5, with a zero diagonal. */
private fun randomDistances(n: Int, rnd: Random): Array<DoubleArray> {
    val d = Array(n) { DoubleArray(n) }
    for (a in 0 until n) for (b in a + 1 until n) {
        d[a][b] = 1.0 + 4.0 * rnd.nextDouble()
        d[b][a] = d[a][b]
    }
    return d
}

/**
 * The simulation's workload, but at most [maxFiles] files are in the placer at once. A traversal
 * of a file that is not in adds it (an upload, not a copy), after removing the least recently
 * traversed file if the placer is full. Both placers see the same files come and go.
 */
fun lruSimulation(maxFiles: Int = 100) {
    val n = 8
    val k = 2
    val files = 400
    val groups = 20
    val alpha = 8.0
    val adaptive = ReplicaPlacer(IntArray(n) { 1_000 }, k, alpha = alpha, halfLife = 20_000.0, seed = 3)
    val static = ReplicaPlacer(IntArray(n) { 1_000 }, k, alpha = Double.POSITIVE_INFINITY, halfLife = 20_000.0, seed = 3)
    val placers = listOf(adaptive, static)
    val recent = LinkedHashMap<Int, Unit>(16, 0.75f, true)          // files in the placers, least recent first
    var loads = 0L
    var evictions = 0L
    fun touch(f: Int) {
        if (recent.put(f, Unit) != null) return                     // already in: now the most recent
        if (recent.size > maxFiles) {
            val lru = recent.keys.first()
            recent.remove(lru)
            for (p in placers) p.removeFile(lru)
            evictions++
        }
        for (p in placers) p.addFile(f)
        loads++
    }
    val rnd = Random(5)
    var members = randomGroups(files, groups, rnd)
    val pick = { members[rnd.nextInt(members.size)] }

    println("== LRU simulation: at most $maxFiles of $files files in the placer, least recently used removed first; " +
        "$n servers, k = $k, $groups groups, alpha = $alpha ==")
    println("traversals  remote%  static%  copies  static copies  loads  evictions")
    var lastA = adaptive.stats.copy()
    var lastS = static.stats.copy()
    var lastLoads = 0L
    var lastEvictions = 0L
    val window = 50_000
    for (t in 1..700_000) {
        when (t) {
            200_001 -> {
                for (p in placers) p.toggleComponent(0); println("   -- server 0 down")
            }
            300_001 -> {
                for (p in placers) p.toggleComponent(0); println("   -- server 0 up")
            }
            450_001 -> {
                members = randomGroups(files, groups, rnd); println("   -- groups reshuffled")
            }
        }
        val pair = nextPair(pick(), files, rnd)
        touch(pair.first)
        touch(pair.second)
        for (p in placers) p.addEdge(pair)
        if (t % window == 0) {
            val a = adaptive.stats - lastA
            val s = static.stats - lastS
            println(
                "%10d  %7.1f  %7.1f  %6d  %13d  %5d  %9d".format(
                    t, pct(a.remote, a.traversals), pct(s.remote, s.traversals), a.copies, s.copies,
                    loads - lastLoads, evictions - lastEvictions,
                )
            )
            lastA = adaptive.stats.copy()
            lastS = static.stats.copy()
            lastLoads = loads
            lastEvictions = evictions
            for (p in placers) {
                check(p.fileCount <= maxFiles) { "${p.fileCount} files in the placer" }
                p.checkInvariants()
            }
        }
    }
    val a = adaptive.stats
    val s = static.stats
    println("total cost = remote traversals + alpha * copies (loads are uploads, the same for both)")
    println("  adaptive: %.0f + %.0f * %d = %.0f".format(a.commCost, alpha, a.copies, a.commCost + alpha * a.copies))
    println("  static:   %.0f + %.0f * %d = %.0f".format(s.commCost, alpha, s.copies, s.commCost + alpha * s.copies))
    println("adaptive copies: ${a.optimizationCopies} optimisation (${a.swaps} swaps), " +
        "${a.repairCopies} repair, ${a.rebalanceCopies} rebalance, overflows ${a.overflows}; " +
        "static: ${s.repairCopies} repair, ${s.rebalanceCopies} rebalance; $loads loads, $evictions evictions")
    println()
}

/** Speed on a bigger instance with skewed (Zipf) group popularity. */
fun benchmark() {
    val n = 64
    val k = 3
    val files = 50_000
    val events = 3_000_000
    val p = ReplicaPlacer(IntArray(n) { 10_000 }, k, alpha = 8.0, halfLife = 300_000.0, seed = 9)
    for (f in 0 until files) p.addFile(f)
    val rnd = Random(13)
    val members = randomGroups(files, files / 20, rnd)
    val zipf = Zipf(members.size, 1.0, rnd)
    var first: Stats? = null
    var beforeLast = p.stats.copy()
    val start = System.nanoTime()
    for (t in 1..events) {
        if (t == events / 2) p.toggleComponent(5)
        p.addEdge(nextPair(members[zipf.next()], files, rnd))
        if (t == events / 10) first = p.stats.copy()
        if (t == events - events / 10) beforeLast = p.stats.copy()
    }
    val ms = (System.nanoTime() - start) / 1e6
    p.checkInvariants()
    val f = first!!
    val last = p.stats - beforeLast
    println("== benchmark: $n servers, k = $k, $files files in groups of 20 (Zipf popularity), $events traversals ==")
    println("%.0f ms, %.2f us per traversal; remote %.1f %% in the first 10 %%, %.1f %% in the last 10 %%; %d copies".format(
        ms, ms * 1000 / events, pct(f.remote, f.traversals), pct(last.remote, last.traversals), p.stats.copies))
}

internal fun pct(x: Long, total: Long) = if (total == 0L) 0.0 else 100.0 * x / total

internal fun randomGroups(files: Int, groups: Int, rnd: Random): List<IntArray> =
    (0 until files).shuffled(rnd).chunked(files / groups).map { it.toIntArray() }

/** 90 %: two files of [group]; 10 %: two random files. */
internal fun nextPair(group: IntArray, files: Int, rnd: Random): Pair<Int, Int> {
    if (rnd.nextDouble() < 0.9) {
        val a = group[rnd.nextInt(group.size)]
        var b = group[rnd.nextInt(group.size)]
        while (b == a) b = group[rnd.nextInt(group.size)]
        return a to b
    }
    val a = rnd.nextInt(files)
    var b = rnd.nextInt(files)
    while (b == a) b = rnd.nextInt(files)
    return a to b
}

internal class Zipf(size: Int, s: Double, private val rnd: Random) {
    private val cdf = DoubleArray(size)

    init {
        var sum = 0.0
        for (i in 0 until size) {
            sum += 1.0 / (i + 1.0).pow(s)
            cdf[i] = sum
        }
        for (i in 0 until size) cdf[i] /= sum
    }

    fun next(): Int {
        val i = cdf.binarySearch(rnd.nextDouble())
        return min(if (i >= 0) i else -i - 1, cdf.size - 1)
    }
}

/** Runs [trace] through [e] with room for [capacity] files; the evicted files in order. */
internal fun replay(e: Eviction, trace: IntArray, capacity: Int, n: Int): List<Int> {
    val resident = BooleanArray(n)
    var count = 0
    val evicted = ArrayList<Int>()
    for (f in trace) {
        val added = !resident[f]
        if (added) { resident[f] = true; count++ }
        e.touched(f, added)
        while (count > capacity) {
            val v = e.evict()
            check(resident[v]) { "${e.name} evicted $v, which is not resident" }
            resident[v] = false
            count--
            evicted += v
        }
        check(e.size == count) { "${e.name} holds ${e.size} files, $count resident" }
    }
    return evicted
}

/** Eviction policies on small traces with known answers, and Belady against all of them. */
fun cacheCheck() {
    println("== cache policies ==")
    fun expect(e: Eviction, trace: List<Int>, capacity: Int, evicted: List<Int>) {
        val got = replay(e, trace.toIntArray(), capacity, 100)
        check(got == evicted) { "${e.name} on $trace evicted $got, expected $evicted" }
    }
    expect(Lru(100), listOf(1, 2, 3, 1, 4), 3, listOf(2))
    expect(Fifo(100), listOf(1, 2, 3, 1, 4), 3, listOf(1))
    expect(Clock(100), listOf(1, 2, 3, 1, 4), 3, listOf(2))
    expect(Lfu(100), listOf(1, 1, 2, 3, 2, 4), 3, listOf(3))
    expect(Lru(100), listOf(1, 1, 2, 3, 4, 5), 3, listOf(1, 2))
    expect(Slru(100, 3), listOf(1, 1, 2, 3, 4, 5), 3, listOf(2, 3))
    val arc = Arc(100, 2)
    expect(arc, listOf(1, 1, 2, 3, 2), 2, listOf(2, 1))      // 2 comes back from the ghost list B1
    check(arc.target == 1.0) { "ARC target ${arc.target} after a B1 hit, expected 1" }
    expect(Belady(100, Belady.nextUse(intArrayOf(1, 2, 3, 4, 1, 2, 3), 100)), listOf(1, 2, 3, 4, 1, 2, 3), 3, listOf(4))

    // A one-off scan must not flush hot files from W-TinyLFU; it does from LRU.
    val scan = List(200) { it % 10 } + (1000 until 1500)
    for (e in listOf(TinyLfu(5000, 100), Lru(5000))) {
        val evicted = replay(e, scan.toIntArray(), 100, 5000).toSet()
        val kept = (0 until 10).count { it !in evicted }
        println("  scan of 500 one-hit files: ${e.name} keeps $kept of 10 hot files")
        if (e is TinyLfu) check(kept == 10)
    }

    // Belady has the fewest misses on a skewed trace.
    val n = 2_000
    val zipf = Zipf(n, 0.9, Random(31))
    val trace = IntArray(200_000) { zipf.next() }
    val next = Belady.nextUse(trace, n)
    val capacity = 100
    val policies = listOf(Belady(n, next), Lru(n), Fifo(n), RandomEviction(n, 5), Clock(n), Lfu(n),
        Slru(n, capacity), Arc(n, capacity), TinyLfu(n, capacity))
    val misses = policies.associate { it.name to replay(it, trace, capacity, n).size + it.size }
    println("  Zipf(0.9) over %,d files, room for %d: misses %s".format(n, capacity,
        misses.entries.joinToString { "${it.key} %.1f%%".format(100.0 * it.value / trace.size) }))
    check(misses.values.all { it >= misses.getValue("Belady") })
}
