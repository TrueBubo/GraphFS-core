import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/**
 * Online replica placement where each replica follows the servers that request it, without a
 * pair graph.
 *
 * Model:
 *  - A traversal u -> v is served by the cheapest pair of replicas, u's on server a and v's on b
 *    (ties at random), as [ReplicaPlacer] prices it; it costs d(a, b), 0 when one server holds
 *    both. Each side is a request to the other: u's replica on a requests v's on b, and the other
 *    way round.
 *  - Every request carries a distance row: for each server t, the distance from t to the nearest
 *    replica of the requesting file (the rows of its servers' distance matrix, merged by min).
 *    It is what the traversal would cost if the requested replica stood on t, since a traversal
 *    is local when any replicas of the two files share a server. A replica keeps one decayed
 *    array of size n, [Demand.row]: the sum of the rows it was sent. The array stays true when
 *    the replica moves, and a local request makes it stay put. Sending the row of the requesting
 *    server alone split a file's demand among its k servers: on Linux, 4.5% saved for `demand`
 *    and 56.5% for `demand-near`, against 9.6% and 58.2% with the merged row.
 *  - A replica also keeps a debt: the distances its requests paid since it last decided. When the
 *    debt reaches alpha / k (its share of a copy, as in [DistributedPlacer]) it is paid off and the
 *    replica decides, from its own array alone, where to
 *    be: on h now, moving to t saves row[h] - row[t] and costs alpha times the distance from f's
 *    nearest replica. It moves to the server with the best net saving, if positive, among those
 *    within the slack range: below (1 + [slack]) times their share of the replicas. The estimate
 *    is conservative: a requester nearer another replica of f than t would go there instead, and
 *    requesters of the other replicas that t would suit are left out.
 *  - Weights halve every [halfLife] traversals.
 *
 * With [sketchCells] > 0, the arrays are not kept. Each server keeps one [DecayingSketch] of
 * [sketchCells] cells per row instead, keyed by (file, server t), of the nearness of t to the
 * requesters: the farthest distance minus the row's entry, so that the best target is the
 * nearest, and with uniform costs a request raises only the k entries of the requester's servers.
 * A replica keeps its own server's nearness exactly; the gain of moving to t is t's estimate
 * minus that. An estimate can only overstate t's nearness (collisions), never understate it. A
 * replica that moves takes its estimates to the new server's sketch, raising each counter to at
 * least the estimate: adding them would count again what stale counters of an earlier stay there
 * still hold, and a file moving back and forth would inflate them without bound. The old
 * server's counters stay until they fade. Memory is then one sketch per server, whatever the number of servers.
 *
 * With [racks], every file keeps replicas on at least two racks, as in [DistributedPlacer]: a new
 * replica avoids the rack all the others are in, and a replica does not move where that would
 * leave all of them in one rack.
 *
 * Kept simple next to [DistributedPlacer]: locations are known exactly (as its oracle), and loads
 * too; the caps count live replicas only, not evicted ones still on disk.
 */
class DemandPlacer(
    val servers: IntArray,
    val replicaCount: Int,
    val alpha: Double = 8.0,
    val halfLife: Double = 10_000.0,
    val slack: Double = 0.1,
    val placeNear: Boolean = false,
    val racks: IntArray? = null,
    val distances: Array<DoubleArray>? = null,
    val sketchCells: Int = 0,
    seed: Long = 1L,
) : Placer {
    private val n = servers.size

    init {
        require(n >= 1 && servers.all { it >= 1 }) { "every capacity must be >= 1" }
        require(replicaCount in 1..n) { "k must be in 1..n" }
        require(alpha > 0 && halfLife > 0 && slack >= 0 && sketchCells >= 0)
        racks?.let { require(it.size == n && it.all { r -> r >= 0 }) { "racks must give a rack id >= 0 per server" } }
        distances?.let { d ->
            require(d.size == n && d.all { it.size == n }) { "distances must be n x n" }
            for (a in 0 until n) for (b in 0 until n) {
                if (a == b) require(d[a][b] == 0.0) { "distances[$a][$a] must be 0" }
                else require(d[a][b] > 0 && d[a][b].isFinite()) { "distances[$a][$b] must be finite and > 0" }
                require(d[a][b] == d[b][a]) { "distances must be symmetric" }
            }
        }
    }

    /** What the replicas did: requests received, decisions, moves, and decisions that found a better server full. */
    class Protocol {
        var requests = 0L
        var evaluations = 0L
        var moves = 0L
        var full = 0L
    }

    /**
     * One replica's decayed sum of its requesters' distance rows as of [time] (without a sketch),
     * or its own server's nearness to them (with one), and its debt.
     */
    private class Demand(n: Int, exact: Boolean, var time: Long) {
        val row: DoubleArray? = if (exact) DoubleArray(n) else null
        var own = 0.0
        var debt = 0.0
    }

    override val stats = Stats()
    val protocol = Protocol()

    private val lambda = ln(2.0) / halfLife
    private val rng = Random(seed)
    private val totalCapacity = servers.sum().toLong()
    private val replicas = HashMap<Int, IntArray>()
    private val demands = HashMap<Int, Array<Demand>>()     // per file, aligned with its replicas
    private val load = IntArray(n)
    private var now = 0L
    private val spreading = racks != null && replicaCount >= 2 && racks.distinct().size >= 2
    private val sketches = if (sketchCells > 0) Array(n) { DecayingSketch(lambda, sketchCells) } else null
    private val farthest = distances?.maxOf { it.max() } ?: 1.0

    override val fileCount: Int get() = replicas.size

    val imbalance: Double get() = load.max() / load.average()

    /** Bytes kept for demand: the replicas' objects, and their arrays or the servers' sketches. */
    val demandBytes: Long
        get() = replicas.size.toLong() * replicaCount * (DEMAND + if (sketches == null) arrayBytes(n, 8) else 0L) +
            (sketches?.sumOf { it.bytes } ?: 0L)

    override fun addFile(file: Int) = addFile(file, -1)

    /** With [placeNear], replicas go first on the servers of [near] that have room. */
    override fun addFile(file: Int, near: Int) {
        admit(file, if (placeNear && near >= 0) replicas[near] ?: IntArray(0) else IntArray(0), copies = false)
    }

    /** The file stays on the [stored] servers that have room; the rest are copied (rebalancing copies). */
    override fun restoreFile(file: Int, stored: IntArray) = admit(file, stored, copies = true)

    /** The replicas forget their demand; the data stays where it was. */
    override fun evictFile(file: Int) = removeFile(file)

    override fun removeFile(file: Int) {
        val r = replicas.remove(file) ?: throw IllegalArgumentException("unknown file $file")
        demands.remove(file)
        for (s in r) load[s]--
    }

    /**
     * Places a file: first on the servers [first] below their cap, the rest on the less loaded
     * (for its capacity) of two random servers below their cap, or below their capacity if none is.
     * With racks, each replica avoids the rack that all the ones before it are in.
     */
    private fun admit(file: Int, first: IntArray, copies: Boolean) {
        require(file !in replicas) { "file $file already exists" }
        val total = load.sum() + replicaCount
        val chosen = IntArray(replicaCount) { -1 }
        var i = 0
        for (t in first) {
            if (i < replicaCount && t !in chosen && load[t] < cap(t, total) && spreads(chosen, i, t)) chosen[i++] = t
        }
        val kept = i
        while (i < replicaCount) {
            val open = (0 until n).filter { it !in chosen && load[it] < cap(it, total) && spreads(chosen, i, it) }
                .ifEmpty { (0 until n).filter { it !in chosen && load[it] < servers[it] && spreads(chosen, i, it) } }
            check(open.isNotEmpty()) { "no server has room for file $file" }
            val x = open[rng.nextInt(open.size)]
            val y = open[rng.nextInt(open.size)]
            chosen[i++] = if (load[y].toDouble() / servers[y] < load[x].toDouble() / servers[x]) y else x
        }
        if (copies) {
            stats.rebalanceCopies += replicaCount - kept
            for (j in kept until replicaCount) stats.copyDistance += if (first.isEmpty()) 1.0 else dist(chosen[j], first)
        }
        for (t in chosen) load[t]++
        replicas[file] = chosen
        demands[file] = Array(replicaCount) { Demand(n, sketches == null, now) }
    }

    override fun addEdge(edge: Pair<Int, Int>): Access {
        val (u, v) = edge
        val ru = replicas[u] ?: throw IllegalArgumentException("unknown file $u")
        val rv = replicas[v] ?: throw IllegalArgumentException("unknown file $v")
        if (u == v) return Access.LOCAL
        now++
        val shared = ru.firstOrNull { it in rv }
        if (shared != null) {
            stats.local++
            request(v, rv.indexOf(shared), ru, 0.0)
            request(u, ru.indexOf(shared), rv, 0.0)
            return Access.LOCAL
        }
        var a = -1                                         // the cheapest pair a, b; ties at random
        var b = -1
        var ties = 0
        for (x in ru) for (y in rv) {
            val c = if (a < 0) -1 else d(x, y).compareTo(d(a, b))
            if (c < 0) {
                a = x
                b = y
                ties = 1
            } else if (c == 0 && rng.nextInt(++ties) == 0) {
                a = x
                b = y
            }
        }
        val cost = d(a, b)
        stats.remote++
        stats.commCost += cost
        request(v, rv.indexOf(b), ru, cost)
        // v's replica may have moved next to u's; u's replica then has the request locally.
        request(u, ru.indexOf(a), rv, if (ru.any { it in rv }) 0.0 else cost)
        return Access.REMOTE
    }

    /**
     * f's replica [i] receives a request that paid [cost] from a file on the servers [from], with
     * their distance row: for each server, the distance to the nearest of them.
     */
    private fun request(f: Int, i: Int, from: IntArray, cost: Double) {
        protocol.requests++
        val dm = demands.getValue(f)[i]
        val decay = exp(-lambda * (now - dm.time))
        dm.time = now
        val row = dm.row
        if (row != null) {
            for (t in 0 until n) row[t] = row[t] * decay + dist(t, from)
        } else {
            val h = replicas.getValue(f)[i]
            val sketch = sketches!![h]
            dm.own = dm.own * decay + farthest - dist(h, from)
            for (t in 0 until n) {
                if (t == h) continue
                val near = farthest - dist(t, from)
                if (near > 0.0) sketch.raise(f, t, near, now)
            }
        }
        dm.debt += cost
        if (dm.debt < alpha / replicaCount) return
        dm.debt = 0.0
        decide(f, i, dm)
    }

    /** f's replica [i] moves to the server within the slack range that saves the most net of its copy, if any. */
    private fun decide(f: Int, i: Int, dm: Demand) {
        protocol.evaluations++
        val rf = replicas.getValue(f)
        val h = rf[i]
        val total = load.sum()
        var best = -1
        var bestNet = 0.0
        var bestCopy = 0.0
        var blocked = false
        for (t in 0 until n) {
            if (t in rf || !keepsSpread(rf, i, t)) continue
            val copy = dist(t, rf)
            val gain = dm.row?.let { it[h] - it[t] } ?: (sketches!![h].estimate(f, t, now) - dm.own)
            val net = gain - alpha * copy
            if (net <= bestNet) continue
            if (load[t] >= cap(t, total)) {
                blocked = true
                continue
            }
            best = t
            bestNet = net
            bestCopy = copy
        }
        if (best < 0) {
            if (blocked) protocol.full++
            return
        }
        sketches?.let { sk ->                              // the estimates go with the replica
            val own = dm.own
            dm.own = sk[h].estimate(f, best, now)
            for (x in 0 until n) {
                if (x == best) continue
                val w = if (x == h) own else sk[h].estimate(f, x, now)
                if (w >= PRUNE) sk[best].atLeast(f, x, w, now)
            }
        }
        rf[i] = best
        load[h]--
        load[best]++
        stats.optimizationCopies++
        stats.copyDistance += bestCopy
        protocol.moves++
    }

    /** The rack every server of [a] (but a[skip]) is in; -1 if they span racks, or none are left. */
    private fun soleRack(a: IntArray, skip: Int = -1): Int {
        val r = racks ?: return -1
        var rack = -1
        for (i in a.indices) {
            if (i == skip || a[i] < 0) continue
            if (rack < 0) rack = r[a[i]] else if (r[a[i]] != rack) return -1
        }
        return rack
    }

    /** Whether the [i]-th replica of a file placed on [chosen] may go on t: not in the rack of all before it. */
    private fun spreads(chosen: IntArray, i: Int, t: Int): Boolean =
        !spreading || i == 0 || soleRack(chosen.copyOf(i)).let { it < 0 || racks!![t] != it }

    /** Whether replica a[j] may move to t: with racks, the file must stay on two racks. */
    private fun keepsSpread(a: IntArray, j: Int, t: Int): Boolean {
        if (!spreading) return true
        val rest = soleRack(a, j)
        return rest < 0 || racks!![t] != rest
    }

    /** Server t's cap with [total] replicas placed: (1 + slack) times its share, at most its capacity. */
    private fun cap(t: Int, total: Int): Int =
        min(servers[t].toLong(), maxOf(1L, ceil((1.0 + slack) * total * servers[t] / totalCapacity).toLong())).toInt()

    /** Distance between servers [x] and [y]: 0 or 1 with uniform costs. */
    private fun d(x: Int, y: Int): Double = if (x == y) 0.0 else distances?.get(x)?.get(y) ?: 1.0

    /** Distance from server [t] to the nearest server in [r]. */
    private fun dist(t: Int, r: IntArray): Double {
        var best = Double.POSITIVE_INFINITY
        for (x in r) best = min(best, d(t, x))
        return best
    }

    override fun checkInvariants() {
        val counted = IntArray(n)
        for ((f, r) in replicas) {
            check(r.size == replicaCount && r.toSet().size == replicaCount) { "file $f: replicas ${r.toList()}" }
            check(!spreading || soleRack(r) < 0) { "file $f has every replica in one rack: ${r.toList()}" }
            for (s in r) counted[s]++
            val ds = demands.getValue(f)
            check(ds.size == replicaCount) { "file $f: ${ds.size} demands" }
            for (dm in ds) {
                check((dm.row == null) == (sketches != null)) { "file $f: demand kept in the wrong place" }
                check((dm.row?.all { it.isFinite() && it >= 0.0 } ?: true) && dm.own.isFinite() && dm.own >= 0.0 && dm.debt in 0.0..<alpha) {
                    "file $f: bad demand: own ${dm.own} debt ${dm.debt} row ${dm.row?.toList()}"
                }
            }
        }
        check(demands.keys == replicas.keys) { "demands and replicas differ" }
        check(counted.contentEquals(load)) { "load ${load.toList()} != counted ${counted.toList()}" }
        for (t in 0 until n) check(load[t] <= servers[t]) { "server $t over capacity" }
    }

    override fun isKnown(file: Int): Boolean = file in replicas

    override fun replicasOf(file: Int): List<Int> = replicas.getValue(file).toList()

    private companion object {
        const val DEMAND = 40L                             // the object: header, time, own, debt, the array's reference
    }
}