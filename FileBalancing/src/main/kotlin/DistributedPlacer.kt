import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.random.Random

/** Protocol counters of a [DistributedPlacer]. */
data class Protocol(
    var messages: Long = 0,        // control messages: not the traversals themselves, nor the copies
    var lookups: Long = 0,         // directory lookups for a file a server had no location for
    var redirects: Long = 0,       // the cached location was stale: the request bounced first
    var verified: Long = 0,        // neighbour locations checked with the directory before a move
    var evaluations: Long = 0,     // triggers: a replica's debt reached its share of alpha
    var rejected: Long = 0,        // moves the target refused, having no room after all
    var moveRequests: Long = 0,    // servers that asked for a replica of a file they kept reaching
    var granted: Long = 0,         // moves made on request (also counted in moves)
    var moves: Long = 0,           // moves applied (a swap counts once)
    var badMoves: Long = 0,        // applied moves whose true gain did not cover their copies
    var predictedGain: Double = 0.0,
    var trueGain: Double = 0.0,
)

/** Bytes a [DistributedPlacer] keeps over all its servers to place files, by structure; [pairs] is the pairs remembered. */
data class PairMemory(val pairs: Long, val pairBytes: Long, val locationBytes: Long)

/**
 * The algorithm of [ReplicaPlacer] (rent-or-buy trigger, exact relocate/swap gains, capped
 * servers) run by the servers themselves, each knowing only what it could know in a real cluster:
 *  - the files it stores, and for each the pairs it has seen traversed and their decayed weights,
 *    kept by [pairCounts] (every pair, or a sketch). A traversal is seen by the server that served
 *    each end, so a file's pairs are spread over its replicas;
 *  - a location cache: where other files' replicas were when it last heard. Every remote
 *    traversal tells both ends where the other file is (piggybacked on the request and reply).
 *    For files it holds itself it knows exactly, as the replicas of a file know each other.
 *    Each server sweeps its own pairs and cache a few entries at a time (see [Node.sweep]);
 *  - the servers' loads, from gossip every [gossipEvery] traversals (n * (n - 1) messages).
 * With [distances] (an n x n matrix every server knows, as in [ReplicaPlacer]), a remote traversal
 * costs the distance between the two servers serving it, a server sends a request to the replica
 * nearest it as far as it knows, debts grow by the distance paid, gains are ReplicaPlacer's
 * weighted ones, and a copy costs alpha times the distance from the file's nearest replica.
 * Without, every remote traversal and copy costs 1. No server failures.
 * With [racks] (a rack id per server, which every server knows), every file keeps its replicas on
 * at least two racks: a new file's last free choices avoid the rack its other replicas are in, and
 * the coordinator (which knows its file's replicas exactly) and a swap partner's server never make
 * a move that puts a file back into one rack. The rule needs no other file's location, so it adds
 * no messages.
 *
 * Protocol
 *  - A file taken out with [evictFile] stays stored on its servers until [restoreFile]. A server
 *    whose disk holds (1 + slack) times its share of every stored replica (by the gossiped total)
 *    takes no new or restored replica, unless every server's disk is full. Moves are not limited
 *    by disk (see [ReplicaPlacer]).
 *  - A new file's home server puts each replica on the less loaded of two random servers, by its
 *    gossiped view plus its own placements since (picking the least loaded of all would send every
 *    new file of a gossip round to the same servers, which all homes then agree on). With
 *    [placeNear], a file first reached by a traversal from another goes on that file's servers
 *    instead, those that have room by their own count, so the traversal that brought it is local;
 *    the server that served the request knows where they are, being one of them.
 *  - A removed file's replicas tell the servers of its neighbours (as cached) that it is gone, so
 *    they drop their pairs with it (1 message per server reached).
 *  - A file has a home server in a directory (file mod n) that always knows its replicas. A
 *    server with no cached location asks it (2 messages); a stale cached location costs a bounce
 *    (1 message) and then the lookup.
 *  - Each replica keeps a debt of remote traversals it served. At alpha / k (the replicas share
 *    the file's debt, as one file's debt in ReplicaPlacer) its server becomes the coordinator: it
 *    pulls the pair weights and known locations from the other replicas (2 messages each) and
 *    evaluates moving any replica of the file to any server, with its own cached locations.
 *  - A move to a server with room (by the coordinator's gossiped loads) is proposed to the target,
 *    which accepts only if it really has room (2 messages). A move into a full server asks it for
 *    a swap partner from a sample of its files, judged by its own partial view (2 messages).
 *  - With [verify], before acting the coordinator asks the directory where the neighbours that the
 *    chosen move depends on really are (2 messages each), and chooses again with that.
 *  - [oracle] is for diagnosis only: decisions see every neighbour's true location at no cost,
 *    so it bounds what better location knowledge could achieve.
 *  - After a move the directory and the file's other replicas are told (1 + k - 1 messages), and
 *    the [notify] heaviest neighbours' replicas update their caches; other servers find out
 *    lazily, through a bounce. The moved replica takes its pair weights and their locations along.
 *  - With [requestMoves], the servers that reach a file also ask for it. Every request a replica
 *    serves carries the requester's distance to its second choice (its nearest other replica of
 *    the file). The replica keeps one sum and one count per replica of its file, decayed like the
 *    pairs: in its own slot the distances its requests paid, in replica j's slot the second-choice
 *    distances of the requests whose second choice was j. A traversal served locally is a request
 *    of both files by their own server, at distance 0. The requester keeps the same sums for its
 *    own requests of the file, with its cached location.
 *    Once a server has paid alpha in distance for one file and has room by its own count, it asks
 *    the replica it reaches, which coordinates (2 messages; the request carries the requester's
 *    sums and its distance to each replica). The coordinator pulls the other replicas' sums
 *    (2 messages each). Moving replica i to the requester saves the requester what it pays, and
 *    sends i's other requests to their second choice: they lose the second-choice sums minus the
 *    own-slot sum (the requester's own share taken out if i serves it; with k = 1, a request
 *    with no second choice is charged the largest distance). Requests to the other replicas are
 *    assumed not to gain from the new place, so the estimate is conservative. The best move is
 *    made if the estimate covers alpha times the copy's distance (from the replica nearest the
 *    requester); it is never a swap. After a move of replica i, its sums and slot i of the other
 *    replicas are cleared, as they were distances to its old place (the move tells them anyway).
 * The true placement ("the world") decides what each traversal costs and is used to score moves
 * ([Protocol.badMoves]); no decision reads it beyond what the server deciding would know.
 */
class DistributedPlacer(
    val servers: IntArray,
    val replicaCount: Int,
    val alpha: Double = 8.0,
    val halfLife: Double = 10_000.0,
    val slack: Double = 0.1,
    val gossipEvery: Long = 1_000,
    val notify: Int = 0,
    val verify: Boolean = false,
    val oracle: Boolean = false,
    val placeNear: Boolean = false,
    val racks: IntArray? = null,
    val distances: Array<DoubleArray>? = null,
    val requestMoves: Boolean = false,
    private val pairCounts: (lambda: Double) -> PairCounts = ::ExactCounts,
    seed: Long = 1L,
) : Placer {
    private val n = servers.size

    init {
        require(n >= 1 && servers.all { it >= 1 }) { "every capacity must be >= 1" }
        require(replicaCount in 1..n) { "k must be in 1..n" }
        require(alpha > 0 && halfLife > 0 && slack >= 0 && gossipEvery >= 1 && notify >= 0)
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

    override val stats = Stats()
    val protocol = Protocol()

    /** Traversals in which the sweep visits every held file and cached location once; tests shorten it. */
    internal var sweepEvery = SWEEP_EVERY

    /**
     * One replica's view of its file: the pairs this server saw, its share of the debt, and (with
     * requestMoves) the sums of the requests it served.
     */
    private class Local(val file: Int, var index: Int, var pairs: FilePairs) {
        var debt = 0.0
        var served: Served? = null
    }

    /**
     * Decayed sums of the requests one replica served, one slot per replica of the file (see the
     * class comment): [sum] of the distances, [count] of the requests, both as of [time].
     */
    private class Served(k: Int, var time: Long) {
        val sum = DoubleArray(k)
        val count = DoubleArray(k)
    }

    /**
     * What a server has paid to reach a file it does not hold, decayed as of [time]: the distances,
     * the second-choice distances, and the requests with and without a second choice; and its
     * [debt] since it last asked for the file.
     */
    private class Want(var time: Long) {
        var paid = 0.0
        var second = 0.0
        var count = 0.0
        var seconds = 0.0
        var debt = 0.0
    }

    /**
     * Where another file's replicas were last heard to be, the sweep's last pass that found a pair
     * naming it, and (with requestMoves) what this server paid to reach it.
     */
    private class Cached(val file: Int, var replicas: IntArray, var seen: Int, var index: Int) {
        var want: Want? = null
    }

    /** A swap partner on the full server: its gain, and the distance its copy travels. */
    private class Swap(val file: Int, val gain: Double, val dist: Double)

    private inner class Node {
        val counts = pairCounts(lambda)           // the pairs of the files held here
        val held = HashMap<Int, Local>()          // files with a replica here
        val list = ArrayList<Local>()             // the same, for random picks and the sweep; Local.index is the position
        val cache = HashMap<Int, Cached>()        // file -> its replicas, as last heard
        val cacheList = ArrayList<Cached>()       // the same, for the sweep; Cached.index is the position
        val loads = IntArray(n)                   // as of the last gossip, plus own placements since
        val disks = IntArray(n)                   // the same, evicted files stored there included
        var replicaTotal = 0L                     // as of the last gossip
        var diskTotal = 0L                        // the same, evicted files included
        private var pass = 0                      // passes the sweep has made over the held files
        private var nextFile = 0                  // where the sweep goes on in list
        private var nextCached = 0                // and in cacheList
        private var fileCredit = 0.0              // files and cached locations the sweep may visit now
        private var cacheCredit = 0.0

        fun add(f: Int, l: Local) {
            l.index = list.size
            list.add(l)
            held[f] = l
        }

        fun remove(f: Int): Local {
            val l = held.remove(f)!!
            val last = list.removeAt(list.size - 1)
            if (last !== l) {
                list[l.index] = last
                last.index = l.index
            }
            return l
        }

        fun cached(g: Int): IntArray? = cache[g]?.replicas

        fun cache(g: Int, r: IntArray) {
            val c = cache[g]
            if (c != null) c.replicas = r
            else {
                val c = Cached(g, r, pass, cacheList.size)
                cache[g] = c
                cacheList.add(c)
            }
        }

        fun cacheIfAbsent(g: Int, r: IntArray) {
            if (g !in cache) cache(g, r)
        }

        /**
         * One turn of the sweep, which visits each held file and each cached location once in
         * [period] of the server's turns: forgets faded pairs, and marks the cached locations the
         * remaining pairs name. A location no pass found named in two passes is forgotten (two, as
         * a file moved in during a pass may be missed by it).
         */
        fun sweep(period: Double) {
            fileCredit += list.size / period
            while (fileCredit >= 1.0 && list.isNotEmpty()) {
                fileCredit -= 1.0
                if (nextFile >= list.size) {
                    nextFile = 0
                    pass++
                }
                list[nextFile++].pairs.forEachLive(now) { g, _ -> cache[g]?.seen = pass }
            }
            cacheCredit += cacheList.size / period
            while (cacheCredit >= 1.0 && cacheList.isNotEmpty()) {
                cacheCredit -= 1.0
                if (nextCached >= cacheList.size) nextCached = 0
                val c = cacheList[nextCached]
                if (pass - c.seen < 2) {
                    nextCached++
                    continue
                }
                cache.remove(c.file)
                val last = cacheList.removeAt(cacheList.size - 1)
                if (last !== c) {
                    cacheList[c.index] = last
                    last.index = c.index
                }
            }
        }

        /**
         * Bytes of the pairs (and request sums), and of the location cache (its replica arrays
         * counted as this server's own, and the requester's sums with it).
         */
        val pairBytes: Long
            get() = counts.bytes + arrayBytes(list.size, 4) + list.sumOf { l ->
                l.pairs.bytes + (l.served?.let { SERVED + 2 * arrayBytes(replicaCount, 8) } ?: 0L)
            }
        val locationBytes: Long
            get() = hashMapBytes(cache.size) + arrayBytes(cacheList.size, 4) +
                cache.size * (HASH_NODE + BOXED_INT + CACHED + arrayBytes(replicaCount, 4)) +
                cacheList.sumOf { if (it.want != null) WANT else 0L }
    }

    // The world. Replica arrays are replaced, never changed, so caches can share them.
    private val replicas = HashMap<Int, IntArray>()
    private val load = IntArray(n)
    // Files that left the placer (evictFile) stay stored on their servers and take disk space there.
    private val evicted = HashMap<Int, IntArray>()            // file -> the servers it is stored on
    private val cold = IntArray(n)                            // server -> replicas of evicted files on it
    private val lambda = ln(2.0) / halfLife
    private val nodes = Array(n) { Node() }
    private val rng = Random(seed)
    private val totalCapacity = servers.sumOf { it.toLong() }
    private var now = 0L
    private val minLink = distances?.let { d ->              // shortest link between two servers
        (0 until n).minOfOrNull { a -> (0 until n).filter { it != a }.minOfOrNull { d[a][it] } ?: 1.0 }
    } ?: 1.0
    private val farthest = distances?.maxOf { it.max() } ?: 1.0

    override val fileCount: Int get() = replicas.size

    /** What the servers keep for the pairs and locations right now; O(files held), for measurements. */
    val memory: PairMemory
        get() = PairMemory(
            nodes.sumOf { node -> node.list.sumOf { it.pairs.count.toLong() } },
            nodes.sumOf { it.pairBytes }, nodes.sumOf { it.locationBytes },
        )

    override fun addFile(file: Int) = addFile(file, -1)

    /**
     * Adds a file reached from [near] (-1: none). With [placeNear], replicas go first on near's
     * servers that have room; the rest on the less loaded of two random servers by the home's view.
     */
    override fun addFile(file: Int, near: Int) {
        admit(file, if (placeNear && near >= 0) replicas[near] ?: IntArray(0) else IntArray(0), copies = false)
    }

    /**
     * A file that left the placer but is still stored on [stored] stays there; only replicas
     * whose server is full now are copied elsewhere (rebalancing copies).
     */
    override fun restoreFile(file: Int, stored: IntArray) {
        evicted.remove(file)?.forEach { cold[it]-- }       // kept below, or moved off (the old copy deleted)
        admit(file, stored, copies = true)
    }

    /** The replicas forget the file's pairs as in [removeFile], but its data stays on them, taking disk space. */
    override fun evictFile(file: Int) {
        val at = replicas[file] ?: throw IllegalArgumentException("unknown file $file")
        removeFile(file)
        evicted[file] = at
        for (t in at) cold[t]++
    }

    /**
     * Places a file: first on the servers [first] that have room by their own count, the rest on
     * the less loaded of two random servers by the home's view; with [copies], each of the rest
     * is a copy of data already stored (a restored file), else an upload of a new file.
     */
    private fun admit(file: Int, first: IntArray, copies: Boolean) {
        require(file !in replicas) { "file $file already exists" }
        val home = nodes[home(file)]
        val chosen = IntArray(replicaCount) { -1 }
        var i = 0
        for (t in first) {
            // t knows its own load; a restored replica it keeps takes no new disk space
            if (i < replicaCount && t !in chosen && load[t] < cap(nodes[t], t) && (copies || !diskFull(t))) chosen[i++] = t
        }
        val kept = i
        if (copies) {
            stats.rebalanceCopies += replicaCount - i
            if (distances == null) stats.copyDistance += (replicaCount - i).toDouble()
        }
        while (i < replicaCount) {
            // With racks, a file whose replicas so far are in one rack takes the next one elsewhere.
            val avoid = if (spreading) soleRack(chosen.copyOf(i)) else -1
            fun open(t: Int) = t !in chosen && (avoid < 0 || racks!![t] != avoid)
            // Servers whose disk is full by the home's view are skipped, unless all of them are.
            fun roomy(t: Int) = home.disks[t] < diskCap(home, t)
            val skipFull = evicted.isNotEmpty() && (0 until n).any { open(it) && roomy(it) }
            fun pick(): Int {
                while (true) rng.nextInt(n).let { t -> if (open(t) && (!skipFull || roomy(t))) return t }
            }
            val x = pick()
            val y = if (n - i > 1) pick() else x
            chosen[i++] = if (home.loads[y] < home.loads[x]) y else x
        }
        // A copied replica comes from the nearest stored one.
        if (copies && distances != null) for (j in kept until replicaCount) stats.copyDistance += dist(chosen[j], first)
        for ((j, t) in chosen.withIndex()) {
            home.loads[t]++
            if (!copies || j >= kept) home.disks[t]++
            load[t]++
            nodes[t].add(file, Local(file, 0, nodes[t].counts.open(file)))
        }
        replicas[file] = chosen
        protocol.messages += replicaCount                  // upload (or keep) orders
    }

    /** The home server tells the replicas to delete; they tell the servers of the file's neighbours. */
    override fun removeFile(file: Int) {
        val r = replicas.remove(file) ?: throw IllegalArgumentException("unknown file $file")
        protocol.messages += r.size
        val told = HashSet<Int>()
        for (s in r) {
            nodes[s].held.getValue(file).pairs.forEach(now) { g, w ->
                if (requestMoves) nodes[s].cache[g]?.want?.let { forget(it, w) }   // its requests will not come again
                for (h in known(s, g) ?: return@forEach) {
                    nodes[h].held[g]?.pairs?.remove(file)
                    if (told.add(h)) protocol.messages++
                }
            }
        }
        for (s in r) {
            nodes[s].remove(file)
            load[s]--
        }
    }

    override fun addEdge(edge: Pair<Int, Int>): Access {
        val (u, v) = edge
        val ru = replicas[u] ?: throw IllegalArgumentException("unknown file $u")
        val rv = replicas[v] ?: throw IllegalArgumentException("unknown file $v")
        if (u == v) return Access.LOCAL
        now++
        if (now % gossipEvery == 0L) gossip()
        nodes[(now % n).toInt()].sweep(sweepEvery.toDouble() / n)   // the servers take turns
        val shared = ru.firstOrNull { it in rv }
        if (shared != null) {                             // served by one server holding both
            observe(shared, u, v)
            observe(shared, v, u)
            if (requestMoves) {                           // both files were needed there
                serve(shared, u, shared, ru)
                serve(shared, v, shared, rv)
            }
            stats.local++
            return Access.LOCAL
        }
        val a = ru[rng.nextInt(ru.size)]                  // the replica of u serving the request
        val b = locate(a, v, rv)
        val cost = d(a, b)
        observe(a, u, v)
        observe(b, v, u)
        nodes[a].cache(v, rv)                             // the reply says where v's replicas are
        nodes[b].cache(u, ru)                             // and the request where u's are
        stats.remote++
        stats.commCost += cost
        if (requestMoves) serve(b, v, a, rv)
        charge(a, u, cost)
        charge(b, v, cost)
        if (requestMoves) want(a, u, b, v, rv, cost)
        return Access.REMOTE
    }

    /**
     * The server of v that a request from server [from] reaches, counting lookups and bounces: the
     * one it believes nearest (with uniform costs, the first it knows of).
     */
    private fun locate(from: Int, v: Int, rv: IntArray): Int {
        val cached = nodes[from].cached(v)
        if (cached != null) {
            val to = nearest(from, cached)
            if (to in rv) return to
            protocol.redirects++
            protocol.messages++                            // "not here any more"
        }
        protocol.lookups++
        if (home(v) != from) protocol.messages += 2
        return if (distances == null) rv[rng.nextInt(rv.size)] else nearest(from, rv)
    }

    private fun observe(s: Int, f: Int, g: Int) {
        nodes[s].held[f]?.pairs?.add(g, now)
    }

    private fun charge(s: Int, f: Int, cost: Double) {
        val l = nodes[s].held[f] ?: return                // a move just took it away
        l.debt += cost
        if (l.debt < alpha / replicaCount) return
        l.debt = 0.0
        improve(s, f)
    }

    /** Multiplies [x] by [decay] in place. */
    private fun scale(x: DoubleArray, decay: Double) {
        for (i in x.indices) x[i] *= decay
    }

    /** The second choice of a request from server [s] to f's replica index [i] of [rf]: the nearest other replica's index, -1 if none. */
    private fun secondChoice(s: Int, i: Int, rf: IntArray): Int {
        var j = -1
        for (x in rf.indices) if (x != i && (j < 0 || d(s, rf[x]) < d(s, rf[j]))) j = x
        return j
    }

    /** f's replica on [h] serves a request from server [s], which sends its distance to its second choice. */
    private fun serve(h: Int, f: Int, s: Int, rf: IntArray) {
        val l = nodes[h].held[f] ?: return
        val i = rf.indexOf(h)
        val sv = l.served ?: Served(replicaCount, now).also { l.served = it }
        val decay = exp(-lambda * (now - sv.time))
        scale(sv.sum, decay)
        scale(sv.count, decay)
        sv.time = now
        sv.sum[i] += d(s, h)
        sv.count[i] += 1.0
        val j = secondChoice(s, i, rf)
        if (j >= 0) {
            sv.sum[j] += d(s, rf[j])
            sv.count[j] += 1.0
        }
    }

    /**
     * A neighbour of f with pair weight [w] arrived on [h] or left it: f's replica on h, if [forget],
     * forgets up to w of the requests it served (all slots in proportion; their sources are not kept)
     * and, if [local], adds w local requests, as [serve] would have recorded them.
     */
    private fun requeue(h: Int, f: Int, w: Double, forget: Boolean, local: Boolean) {
        val l = nodes[h].held[f] ?: return
        val rf = replicas.getValue(f)
        val i = rf.indexOf(h)
        val sv = l.served ?: if (local) Served(replicaCount, now).also { l.served = it } else return
        val decay = exp(-lambda * (now - sv.time))
        scale(sv.sum, decay)
        scale(sv.count, decay)
        sv.time = now
        if (forget && sv.count[i] > 0.0) {
            val keep = 1.0 - min(1.0, w / sv.count[i])
            scale(sv.sum, keep)
            scale(sv.count, keep)
        }
        if (!local) return
        sv.count[i] += w
        val j = secondChoice(h, i, rf)
        if (j >= 0) {
            sv.sum[j] += w * d(h, rf[j])
            sv.count[j] += w
        }
    }

    /** A file that reached g with pair weight [w] left the server of [want]: forget up to w of its requests, in proportion. */
    private fun forget(want: Want, w: Double) {
        val decay = exp(-lambda * (now - want.time))
        want.time = now
        val count = want.count * decay
        val keep = decay * if (count > 0.0) 1.0 - min(1.0, w / count) else 1.0
        want.paid *= keep
        want.second *= keep
        want.count *= keep
        want.seconds *= keep
    }

    /**
     * Server [a] (holding u) paid [cost] to reach v's replica on [b]. Once it has paid alpha for v
     * since it last asked, and has room by its own count, it asks b for a replica of v.
     */
    private fun want(a: Int, u: Int, b: Int, v: Int, rv: IntArray, cost: Double) {
        val c = nodes[a].cache[v] ?: return
        val w = c.want ?: Want(now).also { c.want = it }
        val decay = exp(-lambda * (now - w.time))
        w.paid = w.paid * decay + cost
        w.second *= decay
        w.count = w.count * decay + 1.0
        w.seconds *= decay
        w.time = now
        val j = secondChoice(a, rv.indexOf(b), rv)
        if (j >= 0) {
            w.second += d(a, rv[j])
            w.seconds += 1.0
        }
        w.debt += cost
        if (w.debt < alpha) return
        w.debt = 0.0
        // A move just made for this traversal may have changed what the request would be for.
        if (u !in nodes[a].held || v in nodes[a].held || v !in nodes[b].held) return
        if (load[a] >= cap(nodes[a], a)) return            // a knows it has no room
        requestMove(a, b, v, w)
    }

    /**
     * Server [c], holding v, decides on [a]'s request for a replica of v, with a's sums [w]: moves
     * the replica whose move saves the most by the estimate in the class comment, if that covers its copy.
     */
    private fun requestMove(a: Int, c: Int, v: Int, w: Want) {
        protocol.moveRequests++
        protocol.messages += 2                             // the request, with a's sums and distances; the answer
        val rv = replicas.getValue(v)
        val sums = arrayOfNulls<Served>(rv.size)
        for ((i, h) in rv.withIndex()) {
            if (h != c) protocol.messages += 2             // pull the replica's sums
            val sv = nodes[h].held.getValue(v).served ?: continue
            val decay = exp(-lambda * (now - sv.time))
            scale(sv.sum, decay)
            scale(sv.count, decay)
            sv.time = now
            sums[i] = sv
        }
        // a's sums decayed to now; a's requests are taken to all have gone to c, the replica it reaches.
        val decay = exp(-lambda * (now - w.time))
        val paid = w.paid * decay
        val copy = dist(a, rv)                             // from the replica nearest a
        var bestJ = -1
        var bestGain = 0.0
        for (i in rv.indices) {
            if (!keepsSpread(rv, i, a)) continue
            var loss = 0.0                                 // what i's other requests lose going to their second choice
            sums[i]?.let { sv ->
                var own = sv.sum[i]
                var ownCount = sv.count[i]
                var second = 0.0
                var seconds = 0.0
                for (j in rv.indices) if (j != i) {
                    second += sv.sum[j]
                    seconds += sv.count[j]
                }
                if (rv[i] == c && paid > 0.0) {            // a goes to its new replica, not to its second choice
                    val r = min(1.0, own / paid)           // the part of a's requests that c can have recorded
                    own = maxOf(0.0, own - r * paid)
                    ownCount = maxOf(0.0, ownCount - r * w.count * decay)
                    second = maxOf(0.0, second - r * w.second * decay)
                    seconds = maxOf(0.0, seconds - r * w.seconds * decay)
                }
                loss = second + maxOf(0.0, ownCount - seconds) * farthest - own
            }
            val gain = paid - loss
            if (bestJ < 0 || better(gain, copy, bestGain, copy)) {
                bestJ = i
                bestGain = gain
            }
        }
        if (bestJ < 0 || bestGain - alpha * copy < 0) return
        // The move is scored against the world by f's pairs, as any other move; gathering them is free.
        val weight = HashMap<Int, Double>()
        for (h in rv) nodes[h].held.getValue(v).pairs.forEach(now) { g, x -> weight.merge(g, x, Double::plus) }
        protocol.granted++
        apply(c, v, rv, bestJ, a, bestGain, weight, swapFile = -1, copyDist = copy)
    }

    /** Server [s], holding f, evaluates moving one of f's replicas, as ReplicaPlacer.bestOption. */
    private fun improve(s: Int, f: Int) {
        protocol.evaluations++
        val a = replicas.getValue(f)                       // replicas of a file know each other
        val weight = HashMap<Int, Double>()
        val where = HashMap<Int, IntArray>()
        for (h in listOf(s) + a.filter { it != s }) {      // own view first: its locations win
            if (h != s) protocol.messages += 2
            val l = nodes[h].held[f] ?: continue
            l.pairs.forEachLive(now) { g, w ->             // faded pairs go now, not at the sweep
                weight.merge(g, w, Double::plus)
                if (g !in where) known(h, g)?.let { where[g] = it }
            }
        }
        if (oracle) {                                      // diagnosis: every location exact, for free
            for (g in weight.keys.toList()) replicas[g]?.let { where[g] = it } ?: run { weight.remove(g); where.remove(g) }
        }
        val view = nodes[s]
        val cd = DoubleArray(n) { t -> dist(t, a) }        // a copy to t comes from f's nearest replica
        var checked = !verify
        var freeJ: Int; var freeT: Int; var freeGain: Double
        var fullJ: Int; var fullT: Int; var fullGain: Double
        while (true) {
            val nbrs = weight.keys.filter { it in where }
            if (nbrs.isEmpty()) return
            val ws = DoubleArray(nbrs.size) { weight.getValue(nbrs[it]) }
            val na = Array(nbrs.size) { where.getValue(nbrs[it]) }
            val g = gains(a, ws, na)
            freeJ = -1; freeT = -1; freeGain = 0.0
            fullJ = -1; fullT = -1; fullGain = 0.0
            for (j in a.indices) for (t in 0 until n) {
                val gt = g[j][t]
                if (gt == Double.NEGATIVE_INFINITY || !keepsSpread(a, j, t)) continue
                if (view.loads[t] < cap(view, t)) {
                    if (freeJ < 0 || better(gt, cd[t], freeGain, cd[freeT])) { freeJ = j; freeT = t; freeGain = gt }
                } else if (fullJ < 0 || better(gt, cd[t], fullGain, cd[fullT])) { fullJ = j; fullT = t; fullGain = gt }
            }
            if (checked) break
            // Before acting, ask the directory where the neighbours that the best move depends on
            // really are (or whether they are gone), then choose again with what it says.
            val swapPlan = if (fullJ < 0) Double.NEGATIVE_INFINITY else fullGain - alpha * (cd[fullT] + minLink)
            val freePlan = if (freeJ < 0) Double.NEGATIVE_INFINITY else freeGain - alpha * cd[freeT]
            val swap = swapPlan > freePlan
            val (j, t) = if (swap) fullJ to fullT else freeJ to freeT
            if (j < 0 || (if (swap) swapPlan else freePlan) < 0) return
            for (i in nbrs.indices) {
                if (!changes(a, j, t, na[i])) continue
                val nb = nbrs[i]
                protocol.verified++
                if (home(nb) != s) protocol.messages += 2
                val real = replicas[nb]
                if (real == null) {
                    weight.remove(nb)
                    where.remove(nb)
                } else {
                    where[nb] = real
                    view.cache(nb, real)
                }
            }
            checked = true
        }
        val freeNet = if (freeJ < 0) Double.NEGATIVE_INFINITY else freeGain - alpha * cd[freeT]
        val fullNet = if (fullJ < 0) Double.NEGATIVE_INFINITY else fullGain - alpha * cd[fullT]
        if (fullNet - alpha * minLink > freeNet) {         // a swap pays a second copy, over at least the shortest link
            val after = a.copyOf().also { it[fullJ] = fullT }
            val swap = bestSwap(fullT, a[fullJ], f, after)
            if (swap != null && fullGain + swap.gain - alpha * (cd[fullT] + swap.dist) > maxOf(freeNet, 0.0)) {
                apply(s, f, a, fullJ, fullT, fullGain + swap.gain, weight, swapFile = swap.file, copyDist = cd[fullT], swapDist = swap.dist)
                return
            }
        }
        if (freeNet < 0) return
        protocol.messages += 2                              // propose, accept or refuse
        val target = nodes[freeT]
        if (load[freeT] >= cap(target, freeT)) {           // the target knows its own load
            protocol.rejected++
            return
        }
        apply(s, f, a, freeJ, freeT, freeGain, weight, swapFile = -1, copyDist = cd[freeT])
    }

    /**
     * Whether a move of [gain] with a copy over [dist] beats one of [than] over [thanDist]: by net
     * gain, then gain. With uniform costs every copy costs alpha, so this is by gain alone.
     */
    private fun better(gain: Double, dist: Double, than: Double, thanDist: Double): Boolean {
        val net = gain - alpha * dist
        val thanNet = than - alpha * thanDist
        return net > thanNet || net == thanNet && gain > than
    }

    /**
     * Server [t] (full) picks one of its files to move to [from] in exchange for f, judging each
     * sampled file by its own pairs and cached locations (f's at its new place).
     */
    private fun bestSwap(t: Int, from: Int, f: Int, fAfter: IntArray): Swap? {
        protocol.messages += 2                              // ask t, its answer
        val node = nodes[t]
        var best: Swap? = null
        repeat(minOf(SWAP_SAMPLE, node.list.size)) {
            val x = node.list[rng.nextInt(node.list.size)].file
            val ax = replicas.getValue(x)
            if (x == f || from in ax || !keepsSpread(ax, ax.indexOf(t), from)) return@repeat
            val ws = ArrayList<Double>()
            val na = ArrayList<IntArray>()
            node.held.getValue(x).pairs.forEach(now) { g, w ->
                val at = if (g == f) fAfter else known(t, g) ?: return@forEach
                ws.add(w)
                na.add(at)
            }
            if (ws.isEmpty()) return@repeat
            val gx = gains(ax, ws.toDoubleArray(), na.toTypedArray())[ax.indexOf(t)][from]
            if (gx == Double.NEGATIVE_INFINITY) return@repeat
            val dx = dist(from, ax)                         // x's copy comes from its replica nearest from
            if (best == null || better(gx, dx, best!!.gain, best!!.dist)) best = Swap(x, gx, dx)
        }
        return best
    }

    /**
     * Moves replica a[j] of f to t (and, for a swap, swapFile from t to a[j]), the copies
     * travelling [copyDist] and [swapDist]; scores it against the world.
     */
    private fun apply(
        s: Int, f: Int, a: IntArray, j: Int, t: Int, predicted: Double, weight: Map<Int, Double>, swapFile: Int,
        copyDist: Double, swapDist: Double = 0.0,
    ) {
        val from = a[j]
        val fAfter = a.copyOf().also { it[j] = t }
        var actual = trueGain(f, a, fAfter, weight, ignore = swapFile)
        if (from != s) protocol.messages++                  // tell the replica's holder to move it
        move(f, from, t, fAfter, weight)
        var copies = 1
        if (swapFile >= 0) {
            val ax = replicas.getValue(swapFile)
            val xAfter = ax.copyOf().also { it[ax.indexOf(t)] = from }
            val xWeight = LinkedHashMap<Int, Double>()
            nodes[t].held.getValue(swapFile).pairs.forEach(now) { g, w -> xWeight[g] = w }
            actual += trueGain(swapFile, ax, xAfter, xWeight, ignore = -1)
            move(swapFile, t, from, xAfter, xWeight)
            stats.swaps++
            copies = 2
        }
        stats.optimizationCopies += copies
        stats.copyDistance += copyDist + swapDist
        protocol.moves++
        protocol.predictedGain += predicted
        protocol.trueGain += actual
        if (actual < alpha * (copyDist + swapDist)) protocol.badMoves++
        // The heaviest neighbours learn the new place, so their next request goes straight there.
        if (notify > 0) {
            for (g in weight.entries.sortedByDescending { it.value }.take(notify).map { it.key }) {
                val rg = replicas[g] ?: continue
                for (h in rg) nodes[h].cache(f, fAfter)
                protocol.messages += rg.size
            }
        }
    }

    /** Moves f's replica from [from] to [to]; [weight] is f's pairs, as gathered for the move. */
    private fun move(f: Int, from: Int, to: Int, after: IntArray, weight: Map<Int, Double>) {
        val l = nodes[from].remove(f)
        l.debt = 0.0
        // Request sums are distances to the old place: the moved replica's, and its slot at the others.
        l.served = null
        val j = after.indexOf(to)
        for (h in after) if (h != to) nodes[h].held.getValue(f).served?.let { it.sum[j] = 0.0; it.count[j] = 0.0 }
        val dst = nodes[to]
        l.pairs.forEach(now) { g, _ -> known(from, g)?.let { dst.cacheIfAbsent(g, it) } }  // travel with the copy
        l.pairs = dst.counts.arrive(f, l.pairs, now)
        dst.add(f, l)
        replicas[f] = after
        if (requestMoves) {
            // f's requests to its neighbours here are local from now on, and no longer come to them at the
            // old place; f's own sums start from those neighbours. The move carries f's pairs, so this is free.
            for ((g, w) in weight) {
                nodes[from].cache[g]?.want?.let { forget(it, w) }
                if (g in nodes[from].held) requeue(from, g, w, forget = true, local = false)
                if (g in dst.held) {
                    requeue(to, g, w, forget = true, local = true)
                    requeue(to, f, w, forget = false, local = true)
                }
            }
        }
        load[from]--
        load[to]++
        nodes[from].loads[from]--
        nodes[from].disks[from]--
        dst.loads[to]++
        dst.disks[to]++
        protocol.messages += 1 + (after.size - 1)          // the directory, the other replicas
    }

    /** Drop in traversal cost of f's pairs ([weight]) when its replicas go from [a] to [b], by the world. */
    private fun trueGain(f: Int, a: IntArray, b: IntArray, weight: Map<Int, Double>, ignore: Int): Double {
        var gain = 0.0
        for ((g, w) in weight) {
            if (g == ignore) continue
            val rg = replicas[g] ?: continue
            gain += w * (between(a, rg) - between(b, rg))
        }
        return gain
    }

    /** Whether moving a[j] to t changes the cost to a neighbour with replicas [ni]. */
    private fun changes(a: IntArray, j: Int, t: Int, ni: IntArray): Boolean {
        if (distances != null) return between(a, ni) != between(a.copyOf().also { it[j] = t }, ni)
        val before = a.any { it in ni }
        val after = t in ni || a.withIndex().any { (i, x) -> i != j && x in ni }
        return before != after
    }

    /** Distance between servers [x] and [y]: 0 or 1 with uniform costs. */
    private fun d(x: Int, y: Int): Double = if (x == y) 0.0 else distances?.get(x)?.get(y) ?: 1.0

    /** Distance from server [t] to the nearest server in [r]; +inf if r is empty. */
    private fun dist(t: Int, r: IntArray): Double {
        var best = Double.POSITIVE_INFINITY
        for (x in r) best = min(best, d(t, x))
        return best
    }

    /** Cheapest traversal between replica sets [a] and [b]; with uniform costs, 0 if they share a server, else 1. */
    private fun between(a: IntArray, b: IntArray): Double {
        if (distances == null) return if (a.any { it in b }) 0.0 else 1.0
        var best = Double.POSITIVE_INFINITY
        for (x in a) best = min(best, dist(x, b))
        return best
    }

    /** The server of [r] nearest [from]; with uniform costs, the first. */
    private fun nearest(from: Int, r: IntArray): Int = if (distances == null) r[0] else r.minBy { d(from, it) }

    /**
     * out[j][t] = drop of the weighted remote cost of a file with replicas [a] whose neighbours
     * have weights [ws] and (believed) replicas [na], if a[j] moves to t; -inf if t is in a.
     */
    private fun gains(a: IntArray, ws: DoubleArray, na: Array<IntArray>): Array<DoubleArray> {
        if (distances != null) return weightedGains(a, ws, na)
        val out = Array(a.size) { DoubleArray(n) { t -> if (t in a) Double.NEGATIVE_INFINITY else 0.0 } }
        for (i in ws.indices) {
            val w = ws[i]
            val ni = na[i]
            var shared = 0
            for (x in a) if (x in ni) shared++
            val before = if (shared > 0) 0.0 else 1.0
            for (j in a.indices) {
                val without = shared - (if (a[j] in ni) 1 else 0)
                val o = out[j]
                for (t in 0 until n) {
                    if (o[t] == Double.NEGATIVE_INFINITY) continue
                    val after = if (without > 0 || t in ni) 0.0 else 1.0
                    o[t] += w * (before - after)
                }
            }
        }
        return out
    }

    /**
     * [gains] with [distances], as ReplicaPlacer.weightedGains: per neighbour, the cost c through
     * its closest replica a[j*] and `second` without it; moving a[j] to t makes the cost
     * min(c, or second if j = j*, distance from t).
     */
    private fun weightedGains(a: IntArray, ws: DoubleArray, na: Array<IntArray>): Array<DoubleArray> {
        val out = Array(a.size) { DoubleArray(n) { t -> if (t in a) Double.NEGATIVE_INFINITY else 0.0 } }
        val toT = DoubleArray(n)
        for (i in ws.indices) {
            val ni = na[i]
            var closest = -1
            var c = Double.POSITIVE_INFINITY
            var second = Double.POSITIVE_INFINITY
            for (j in a.indices) {
                val m = dist(a[j], ni)
                if (closest < 0 || m < c) {
                    second = c
                    c = m
                    closest = j
                } else second = min(second, m)
            }
            for (t in 0 until n) toT[t] = dist(t, ni)
            for (j in a.indices) {
                val without = if (j == closest) second else c
                val o = out[j]
                for (t in 0 until n) if (o[t] != Double.NEGATIVE_INFINITY) o[t] += ws[i] * (c - min(without, toT[t]))
            }
        }
        return out
    }

    /** Server t's cap as [view] sees it: (1 + slack) times its share of the replicas it knows of. */
    private fun cap(view: Node, t: Int): Int =
        min(servers[t].toLong(), maxOf(1L, ceil((1.0 + slack) * view.replicaTotal * servers[t] / totalCapacity).toLong())).toInt()

    /**
     * Server t's disk share as [view] sees it: (1 + slack) times its share of every stored replica,
     * those of evicted files included. The caps only limit the files in the placer, so without
     * this, evicted files could pile up on whichever server keeps freeing cap.
     */
    private fun diskCap(view: Node, t: Int): Int =
        if (view.diskTotal == 0L) Int.MAX_VALUE              // no gossip yet
        else maxOf(1L, ceil((1.0 + slack) * view.diskTotal * servers[t] / totalCapacity).toLong()).toInt()

    /** Whether server t, which knows its own disk, is at its share; never without evicted files. */
    private fun diskFull(t: Int): Boolean = evicted.isNotEmpty() && load[t] + cold[t] >= diskCap(nodes[t], t)

    private fun gossip() {
        val total = load.sumOf { it.toLong() }
        val disk = total + cold.sumOf { it.toLong() }
        for (node in nodes) {
            load.copyInto(node.loads)
            for (t in 0 until n) node.disks[t] = load[t] + cold[t]
            node.replicaTotal = total
            node.diskTotal = disk
        }
        protocol.messages += n.toLong() * (n - 1)
    }

    /** Where server s believes g's replicas are: exact if it holds g (replicas know each other), else cached. */
    private fun known(s: Int, g: Int): IntArray? = if (g in nodes[s].held) replicas[g] else nodes[s].cached(g)

    private fun home(f: Int): Int = Math.floorMod(f, n)

    /** Replicas on the fullest server over the average per server (1 = perfectly even). */
    val imbalance: Double get() = load.max() / load.average()

    override fun replicasOf(file: Int): List<Int> = replicas.getValue(file).toList()

    override fun isKnown(file: Int): Boolean = file in replicas

    /** Racks are kept apart only if there are two of them and a file has two replicas. */
    private val spreading = racks != null && replicaCount >= 2 && racks.distinct().size >= 2

    /** The rack every server of [a] (but a[skip]) is in; -1 if they span racks, or none are left. */
    private fun soleRack(a: IntArray, skip: Int = -1): Int {
        val r = racks ?: return -1
        var rack = -1
        for (i in a.indices) {
            if (i == skip) continue
            if (rack < 0) rack = r[a[i]] else if (r[a[i]] != rack) return -1
        }
        return rack
    }

    /** Whether replica a[j] may move to t: with racks, the file must stay on two racks. */
    private fun keepsSpread(a: IntArray, j: Int, t: Int): Boolean {
        if (!spreading) return true
        val rest = soleRack(a, j)
        return rest < 0 || racks!![t] != rest
    }

    override fun checkInvariants() {
        var total = 0
        for ((f, r) in replicas) {
            check(r.size == replicaCount && r.toSet().size == r.size) { "file $f has replicas ${r.toList()}" }
            check(!spreading || soleRack(r) < 0) { "file $f has every replica in one rack: ${r.toList()}" }
            for (s in r) check(f in nodes[s].held) { "server $s does not know it holds $f" }
            total += r.size
        }
        for (s in 0 until n) {
            val node = nodes[s]
            check(node.held.size == load[s] && node.list.size == load[s]) { "server $s holds ${node.held.size}, load ${load[s]}" }
            for ((i, l) in node.list.withIndex()) check(node.held[l.file] === l && l.index == i) { "server $s lost track of ${l.file}" }
            for (l in node.list) l.served?.let { sv ->
                check(requestMoves && sv.sum.all { it >= 0 && it.isFinite() } && sv.count.all { it >= 0 && it.isFinite() }) {
                    "server $s has request sums ${sv.sum.toList()} / ${sv.count.toList()} for ${l.file}"
                }
            }
            for (f in node.held.keys) check(s in replicas.getValue(f)) { "server $s holds $f, which is not placed there" }
            check(node.cacheList.size == node.cache.size && node.cache.all { (g, c) -> c.file == g && node.cacheList[c.index] === c }) { "server $s lost track of its cache" }
            check(load[s] <= servers[s]) { "server $s holds ${load[s]} over its capacity ${servers[s]}" }
        }
        check(total == load.sum()) { "$total replicas, loads sum to ${load.sum()}" }
        check(cold.sum() == evicted.values.sumOf { it.size } && cold.all { it >= 0 }) { "evicted replicas ${cold.toList()}" }
    }

    companion object {
        const val SWEEP_EVERY = 1L shl 18                  // traversals in which the sweep visits everything once
        const val SWAP_SAMPLE = 16
        const val CACHED = 32L                             // bytes of a Cached object
        const val SERVED = 24L                             // bytes of a Served object, without its arrays
        const val WANT = 56L                               // bytes of a Want object
    }
}
