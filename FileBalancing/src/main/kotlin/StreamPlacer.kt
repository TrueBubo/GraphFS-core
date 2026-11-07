import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Online replica placement by the scores of streaming graph partitioning, with the central view
 * of [ReplicaPlacer] (every pair's decayed weight, every file's replicas).
 *
 * One primitive places a file: each of its k replicas goes, in turn, to the server with the best
 * score, where w(p) is the weight of the file's pairs with files that have a replica on p (with
 * [marginal], only of those not yet local to the replicas chosen before it):
 *  - [Score.LDG] (Linear Deterministic Greedy, Stanton and Kliot, KDD 2012): w(p) * (1 - load(p) / cap(p));
 *  - [Score.FENNEL] (Tsourakakis et al., WSDM 2014): w(p) - a * gamma * load(p)^(gamma - 1), with
 *    a = [fennelScale] * sqrt(n) * W / R^gamma for n servers, W the total pair weight and R the
 *    replicas, as their alpha = sqrt(k) * m / n^1.5. A larger [fennelScale] trades cut for balance.
 * Ties go to the server with the more weight by the other measure (marginal or all), then to a
 * server the file is already on, then to the least loaded (for its capacity), then at random.
 * With [marginal], a second replica after one beside a lone neighbour has no weight anywhere,
 * so with Fennel the size penalty decides where it goes, and it covers other neighbours later. Servers at their cap ((1 + [slack]) times their share of
 * the replicas, at most their capacity) are skipped, as in the other placers.
 *
 * The primitive is used three times:
 *  - admission: a new file is placed with the file it was reached from ([placeNear]) as its one
 *    neighbour, weight 1 (its pairs come with the traversal that follows); uploads cost nothing.
 *    With [nearFirst], as in [ReplicaPlacer]'s near placement, its replicas first go on that
 *    file's servers below their cap (by two), whatever the score, and only the rest by score;
 *  - [moves]: rent-or-buy as in [ReplicaPlacer]. A file's remote traversals add to its debt; when
 *    the debt reaches alpha the file is placed again, and the new placement is taken if the pair
 *    weight it makes local, less the weight it makes remote, covers alpha per copied replica;
 *  - restreaming ([restreamEvery] > 0): every so many traversals, every file is placed again in
 *    stream order (the order files came in), with the current placement of all others as context.
 *    The new placement is taken under the same rule, with alpha scaled by [restreamThreshold] (0:
 *    any gain), or always with [restreamFree], which is
 *    restreaming as in the literature (Nishimura and Ugander, KDD 2013): it ignores what moving costs.
 *
 * Costs are uniform: a traversal is local if a server holds both files, else it costs 1. An
 * evicted file's pairs are forgotten.
 */
class StreamPlacer(
    val servers: IntArray,
    val replicaCount: Int,
    val alpha: Double = 8.0,
    val halfLife: Double = 10_000.0,
    val slack: Double = 0.1,
    val score: Score = Score.LDG,
    val gamma: Double = 1.5,
    val fennelScale: Double = 1.0,
    val placeNear: Boolean = true,
    val nearFirst: Boolean = false,
    val moves: Boolean = true,
    val restreamEvery: Long = 0,
    val restreamFree: Boolean = false,
    val restreamThreshold: Double = 1.0,
    val marginal: Boolean = false,
    seed: Long = 1L,
) : Placer {
    enum class Score { LDG, FENNEL }

    private val n = servers.size

    init {
        require(n >= 1 && servers.all { it >= 1 }) { "every capacity must be >= 1" }
        require(replicaCount in 1..n) { "k must be in 1..n" }
        require(alpha > 0 && halfLife > 0 && slack >= 0 && gamma > 1 && fennelScale >= 0 && restreamEvery >= 0 && restreamThreshold >= 0)
    }

    /** Placements made, taken, and their copies: by debt triggers and by restreaming passes. */
    class Protocol {
        var triggers = 0L
        var triggerMoves = 0L
        var passes = 0L
        var restreamed = 0L
        var restreamMoves = 0L
        var restreamCopies = 0L
    }

    /** A pair's weight as of [time]; one object in both files' maps. */
    private class Edge(var weight: Double, var time: Long)

    override val stats = Stats()
    val protocol = Protocol()

    private val lambda = ln(2.0) / halfLife
    private val rng = Random(seed)
    private val totalCapacity = servers.sum().toLong()
    private val replicas = LinkedHashMap<Int, IntArray>()  // in stream order
    private val pairs = HashMap<Int, HashMap<Int, Edge>>()
    private val debt = HashMap<Int, Double>()
    private val load = IntArray(n)
    private var now = 0L
    private var totalWeight = 0.0                          // all pairs' weight as of totalTime
    private var totalTime = 0L

    override val fileCount: Int get() = replicas.size

    val imbalance: Double get() = load.max() / load.average()

    override fun addFile(file: Int) = addFile(file, -1)

    override fun addFile(file: Int, near: Int) {
        require(file !in replicas) { "file $file already exists" }
        val context = if (placeNear) replicas[near]?.let { listOf(it to 1.0) } ?: emptyList() else emptyList()
        val total = load.sum() + replicaCount
        // One slot short of the cap, as in ReplicaPlacer: caps shrink when files leave.
        val fixed = if (nearFirst) context.flatMap { it.first.asList() }.filter { load[it] + 1 < cap(it, total) }.take(replicaCount).toIntArray() else IntArray(0)
        admit(file, if (fixed.size == replicaCount) fixed else choose(context, fixed, total))
    }

    /** The file stays on the [stored] servers below their cap; the rest are placed by score (rebalancing copies). */
    override fun restoreFile(file: Int, stored: IntArray) {
        require(file !in replicas) { "file $file already exists" }
        val total = load.sum() + replicaCount
        val kept = stored.distinct().filter { load[it] < cap(it, total) }.take(replicaCount).toIntArray()
        val chosen = if (kept.size == replicaCount) kept else choose(emptyList(), kept, total)
        stats.rebalanceCopies += replicaCount - kept.size
        stats.copyDistance += (replicaCount - kept.size).toDouble()
        admit(file, chosen)
    }

    private fun admit(file: Int, chosen: IntArray) {
        for (t in chosen) load[t]++
        replicas[file] = chosen
    }

    override fun evictFile(file: Int) = removeFile(file)

    override fun removeFile(file: Int) {
        val r = replicas.remove(file) ?: throw IllegalArgumentException("unknown file $file")
        for (s in r) load[s]--
        debt.remove(file)
        pairs.remove(file)?.forEach { (g, e) ->
            pairs[g]?.remove(file)
            totalWeight -= weight(e) * exp(lambda * (now - totalTime))   // in totalTime's units
        }
        if (totalWeight < 0) totalWeight = 0.0
    }

    override fun addEdge(edge: Pair<Int, Int>): Access {
        val (u, v) = edge
        val ru = replicas[u] ?: throw IllegalArgumentException("unknown file $u")
        val rv = replicas[v] ?: throw IllegalArgumentException("unknown file $v")
        if (u == v) return Access.LOCAL
        now++
        val e = pairs.getOrPut(u) { HashMap() }.getOrPut(v) { Edge(0.0, now) }
        pairs.getOrPut(v) { HashMap() }[u] = e
        e.weight = weight(e) + 1.0
        e.time = now
        totalWeight = totalWeight * exp(-lambda * (now - totalTime)) + 1.0
        totalTime = now
        val access = if (ru.any { it in rv }) {
            stats.local++
            Access.LOCAL
        } else {
            stats.remote++
            stats.commCost += 1.0
            if (moves) {
                charge(u)
                charge(v)
            }
            Access.REMOTE
        }
        if (restreamEvery > 0 && now % restreamEvery == 0L) restream()
        return access
    }

    private fun charge(f: Int) {
        val d = (debt[f] ?: 0.0) + 1.0
        if (d < alpha) {
            debt[f] = d
            return
        }
        debt[f] = 0.0
        protocol.triggers++
        if (replace(f, free = false)) protocol.triggerMoves++
    }

    /** One pass over every file in stream order, each placed again with the others as they are now. */
    private fun restream() {
        protocol.passes++
        for (f in replicas.keys.toList()) {
            protocol.restreamed++
            val before = stats.optimizationCopies
            if (replace(f, free = restreamFree, threshold = restreamThreshold)) {
                protocol.restreamMoves++
                protocol.restreamCopies += stats.optimizationCopies - before
            }
        }
    }

    /**
     * Places [f] again by score, its own replicas taken out of the loads. Takes the new placement
     * if it copies something and, unless [free], the pair weight it gains is positive and covers
     * [threshold] times alpha per copy.
     */
    private fun replace(f: Int, free: Boolean, threshold: Double = 1.0): Boolean {
        val rf = replicas.getValue(f)
        val context = neighbours(f)
        for (s in rf) load[s]--
        val next = choose(context, IntArray(0), load.sum() + replicaCount, rf)
        for (s in rf) load[s]++
        val copies = next.count { it !in rf }
        if (copies == 0) return false
        if (!free) {
            val gain = local(next, context) - local(rf, context)
            if (gain <= 0.0 || gain < threshold * alpha * copies) return false
        }
        for (s in rf) load[s]--
        for (s in next) load[s]++
        replicas[f] = next
        stats.optimizationCopies += copies
        stats.copyDistance += copies.toDouble()
        return true
    }

    /**
     * k servers by score for a file whose neighbours are on the given servers with the given
     * weights, after the servers [fixed]; [total] replicas placed in all, the file's included;
     * [own] the servers it is on now (preferred on ties).
     */
    private fun choose(context: List<Pair<IntArray, Double>>, fixed: IntArray, total: Int, own: IntArray = IntArray(0)): IntArray {
        val chosen = IntArray(replicaCount) { if (it < fixed.size) fixed[it] else -1 }
        val placed = load.copyOf()
        for (t in fixed) placed[t]++
        val covered = BooleanArray(context.size) { i -> context[i].first.any { it in fixed } }
        val all = DoubleArray(n)
        for ((r, w) in context) for (s in r) all[s] += w
        val fresh = DoubleArray(n)
        val a = if (score == Score.FENNEL) fennelScale * sqrt(n.toDouble()) * totalWeight * exp(-lambda * (now - totalTime)) /
            maxOf(1.0, total.toDouble()).pow(gamma) else 0.0
        for (i in fixed.size until replicaCount) {
            fresh.fill(0.0)
            for ((c, pair) in context.withIndex()) if (!covered[c]) for (s in pair.first) fresh[s] += pair.second
            var open = (0 until n).filter { it !in chosen && placed[it] < cap(it, total) }
            if (open.isEmpty()) open = (0 until n).filter { it !in chosen && placed[it] < servers[it] }
            check(open.isNotEmpty()) { "no server has room" }
            fun value(w: Double, t: Int) =
                if (score == Score.LDG) w * (1.0 - placed[t].toDouble() / cap(t, total)) else w - a * gamma * placed[t].toDouble().pow(gamma - 1)
            var best = -1
            var ties = 0
            for (t in open) {
                if (best < 0) {
                    best = t
                    ties = 1
                    continue
                }
                val (first, second) = if (marginal) fresh to all else all to fresh
                var c = value(first[t], t).compareTo(value(first[best], best))
                if (c == 0) c = value(second[t], t).compareTo(value(second[best], best))
                if (c == 0) c = (t in own).compareTo(best in own)
                if (c == 0) c = (placed[best].toDouble() / servers[best]).compareTo(placed[t].toDouble() / servers[t])
                if (c > 0 || c == 0 && rng.nextInt(++ties) == 0) {
                    if (c > 0) ties = 1
                    best = t
                }
            }
            chosen[i] = best
            placed[best]++
            for ((c, pair) in context.withIndex()) if (best in pair.first) covered[c] = true
        }
        return chosen
    }

    /** f's neighbours' replicas and pair weights now; faded pairs are forgotten on the way. */
    private fun neighbours(f: Int): List<Pair<IntArray, Double>> {
        val m = pairs[f] ?: return emptyList()
        val out = ArrayList<Pair<IntArray, Double>>(m.size)
        val it = m.entries.iterator()
        while (it.hasNext()) {
            val (g, e) = it.next()
            val w = weight(e)
            if (w < PRUNE) {
                it.remove()
                pairs[g]?.remove(f)
                continue
            }
            out += replicas.getValue(g) to w
        }
        return out
    }

    /** The pair weight that is local with f on [r]. */
    private fun local(r: IntArray, context: List<Pair<IntArray, Double>>): Double =
        context.sumOf { (rg, w) -> if (rg.any { it in r }) w else 0.0 }

    private fun weight(e: Edge) = e.weight * exp(-lambda * (now - e.time))

    private fun cap(t: Int, total: Int): Int =
        min(servers[t].toLong(), maxOf(1L, ceil((1.0 + slack) * total * servers[t] / totalCapacity).toLong())).toInt()

    override fun checkInvariants() {
        val counted = IntArray(n)
        for ((f, r) in replicas) {
            check(r.size == replicaCount && r.toSet().size == replicaCount) { "file $f: replicas ${r.toList()}" }
            for (s in r) counted[s]++
        }
        check(counted.contentEquals(load)) { "load ${load.toList()} != counted ${counted.toList()}" }
        for (t in 0 until n) check(load[t] <= servers[t]) { "server $t over capacity" }
        for ((f, m) in pairs) {
            check(f in replicas) { "pairs of unknown file $f" }
            for ((g, e) in m) check(pairs[g]?.get(f) === e) { "pair $f-$g is not symmetric" }
        }
        check(totalWeight.isFinite() && totalWeight >= 0.0)
    }

    override fun isKnown(file: Int): Boolean = file in replicas

    override fun replicasOf(file: Int): List<Int> = replicas.getValue(file).toList()

    private companion object {
        const val PRUNE = 1e-4
    }
}
