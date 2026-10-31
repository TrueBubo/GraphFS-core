import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** What one traversal between two files cost under the current placement. */
enum class Access { LOCAL, REMOTE, UNAVAILABLE }

/** A change the storage layer has to carry out, reported through [ReplicaPlacer.onChange]. */
sealed interface Change {
    val file: Int

    /** Put a copy of [file] on server [to]: copy it from any live replica (upload it if the file is new). */
    data class Copy(override val file: Int, val to: Int) : Change

    /** Delete the copy of [file] on server [from]. */
    data class Delete(override val file: Int, val from: Int) : Change
}

/**
 * Running counters. Every copy moves one replica over the network and costs `alpha` times the
 * distance it travels; [copyDistance] sums those distances (equal to [copies] on a uniform network).
 */
data class Stats(
    var local: Long = 0,
    var remote: Long = 0,
    var unavailable: Long = 0,
    var commCost: Double = 0.0,
    var copyDistance: Double = 0.0,
    var optimizationCopies: Long = 0,
    var swaps: Long = 0,
    var repairCopies: Long = 0,
    var rebalanceCopies: Long = 0,
    var overflows: Long = 0,
) {
    val traversals: Long get() = local + remote + unavailable
    val copies: Long get() = optimizationCopies + repairCopies + rebalanceCopies

    operator fun minus(o: Stats) = Stats(
        local - o.local, remote - o.remote, unavailable - o.unavailable, commCost - o.commCost,
        copyDistance - o.copyDistance, optimizationCopies - o.optimizationCopies, swaps - o.swaps, repairCopies - o.repairCopies,
        rebalanceCopies - o.rebalanceCopies, overflows - o.overflows,
    )
}

/**
 * Online placement of file replicas on servers ("components").
 *
 * Model
 *  - [servers] holds the capacity of each server (the most replicas it can store); each server
 *    is up or down ([toggleComponent]).
 *  - Every file keeps [replicaCount] live replicas on distinct live servers (fewer if fewer servers are up).
 *  - [addEdge] records one traversal between two files. It costs 0 if they share a live server,
 *    otherwise the smallest [distances] entry between their live replicas (1 if [distances] is
 *    null: a uniform network), and is UNAVAILABLE if one of them has no live replica (the link
 *    to a dead server does not exist).
 *  - Copying a replica to server t costs [alpha] times the distance from the file's nearest live
 *    replica to t, so alpha is in units of one traversal over a link of distance 1.
 *  - A live server holds at most (1 + [slack]) times its share of the replicas, where shares are
 *    proportional to capacity, and never more than its capacity or one replica per file (a share
 *    it cannot use goes to the others); without such a cap the optimum is to put every file on
 *    the same k servers.
 *  - A file taken out with [evictFile] stays stored on its servers until [restoreFile]. Disk
 *    space is shared out the same way over all stored replicas, those of evicted files included:
 *    a new file, a restored replica that cannot stay or a repair copy goes to a server whose disk
 *    is over its share only if every server with room has a full disk. Moves are not limited by
 *    disk: when most disks are full, they would all go to the few that are not and fill their caps.
 *
 * Algorithm (rent-or-buy trigger + exact local search)
 *  - Each pair of files keeps a traversal weight that decays with half-life [halfLife] traversals.
 *  - Each file keeps a debt: remote cost paid by its traversals since it was last evaluated.
 *    When the debt reaches alpha (we have paid as much as one copy costs), we evaluate moving
 *    one of its replicas to any server, or swapping it with a file on a full server.
 *    Gains are exact, because only the edges of the moved files change.
 *  - The best option is applied only if its gain covers its copies (gain >= alpha * copy distance),
 *    so a pattern must keep up roughly alpha traversals per half-life before files move for it.
 *  - When a server goes down, its replicas are queued for repair and re-created where they help
 *    their neighbours most. Files with the fewest live replicas go first, and at most
 *    [repairRate] copies are made per traversal, so a failure does not flood the network.
 *    With [minReplicas] < k and a [grace] period, a file that still has minReplicas live
 *    replicas waits up to grace traversals for the server to come back before it is repaired,
 *    so a short outage costs no copies; below minReplicas it is repaired at once.
 *    When it comes back, over-full servers are relieved, preferably by deleting a surplus
 *    replica. Other surplus replicas are dropped lazily, when their file is next traversed.
 *    Files are assumed immutable, so replicas on a revived server are still valid.
 *  - With [placeNear], a new file reached by a traversal from another ([addFile] with near) goes
 *    on that file's servers where they are at least two below their cap.
 *  - With [racks], a file's live replicas span at least two racks whenever two racks have a live
 *    server, so one rack failing cannot take every copy. A new or repair replica avoids the rack
 *    the file is already in, moves and swaps never undo the spread, and a file left in one rack
 *    (its other rack was down, or no other rack had room) is moved out when it is next traversed.
 *  - Every copy and delete is reported through [onChange].
 *
 * References
 *  - The rent-or-buy trigger comes from Avin, Bienkowski, Loukas, Pacut, Schmid,
 *    "Dynamic Balanced Graph Partitioning", SIAM J. Discrete Math. 2020 (first published as
 *    "Online Balanced Repartitioning", DISC 2016). Only that idea is taken. Their algorithm,
 *    CREP, counts paid requests per pair of nodes, merges components whose weight reaches
 *    (|S| - 1) * alpha and moves whole components. It has no replicas and no failures, and it
 *    needs clusters with room for (2 + eps) * k nodes. Here each file keeps one debt, so a file
 *    with many sparse partners still triggers, and a trigger makes a single relocate or swap.
 *    CREP's O((1 + 1/eps) * k log k) competitive ratio does not carry over: this is a heuristic
 *    with no proven bound.
 *  - Relocate and swap are Kernighan-Lin / Fiduccia-Mattheyses-style refinement moves.
 *    Selvitopi, Turk and Aykanat extend this kind of iterative improvement to replication.
 *  - Exponentially decayed counts are standard for streams (Cohen and Strauss, PODS 2003;
 *    Cormode, Shkapenyuk, Srivastava, Xu, "Forward Decay", ICDE 2009). Applying them to pair
 *    weights, and the placement of re-created replicas, are our own choices.
 */
class ReplicaPlacer(
    val servers: IntArray,
    val replicaCount: Int,
    val alpha: Double = 8.0,
    val halfLife: Double = 10_000.0,
    val slack: Double = 0.1,
    val repairRate: Double = 0.1,
    val minReplicas: Int = replicaCount,
    val grace: Long = 0L,
    val distances: Array<DoubleArray>? = null,
    val racks: IntArray? = null,
    val placeNear: Boolean = false,
    seed: Long = 1L,
) : Placer {
    val serverCount: Int get() = servers.size

    init {
        require(serverCount >= 1) { "n must be >= 1" }
        require(servers.all { it >= 1 }) { "every capacity must be >= 1" }
        require(replicaCount in 1..serverCount) { "k must be in 1..n" }
        require(alpha > 0) { "alpha must be > 0" }
        require(halfLife > 0) { "halfLife must be > 0" }
        require(slack >= 0) { "slack must be >= 0" }
        require(repairRate > 0) { "repairRate must be > 0" }
        require(minReplicas in 1..replicaCount) { "minReplicas must be in 1..k" }
        require(grace >= 0) { "grace must be >= 0" }
        distances?.let { d ->
            require(d.size == serverCount && d.all { it.size == serverCount }) { "distances must be n x n" }
            for (a in 0 until serverCount) for (b in 0 until serverCount) {
                if (a == b) require(d[a][b] == 0.0) { "distances[$a][$a] must be 0" }
                else require(d[a][b] > 0 && d[a][b].isFinite()) { "distances[$a][$b] must be finite and > 0" }
                require(d[a][b] == d[b][a]) { "distances must be symmetric" }
            }
        }
        racks?.let { require(it.size == serverCount && it.all { r -> r >= 0 }) { "racks must give a rack id >= 0 per server" } }
    }

    override val stats = Stats()

    /** Called for every copy and delete the placement makes, so the storage layer can mirror it. */
    var onChange: ((Change) -> Unit)? = null

    /** Re-checks every optimisation move against a full cost recomputation. Slow; for tests. */
    internal var verifyGains = false

    /** Traversals in which the sweep visits every file once; tests shorten it. */
    internal var sweepEvery = SWEEP_EVERY

    private class FileState(val id: Int) {
        var servers = EMPTY               // servers holding a copy, live or dead; replaced, never changed in place
        var slots = EMPTY                 // slots[i]: this file's index in onServer[servers[i]]
        var live = EMPTY                  // the live part of servers, valid while liveStamp == aliveStamp
        var liveStamp = -1
        var nbrs: EdgeMap? = null         // neighbour -> shared edge
        var debt = 0.0                    // remote cost paid since the file was last evaluated
        var heat = 0.0                    // total weight of its pairs, in forward units (see heatScale)
        var residue = 0.0                 // the part of heat left by forgotten pairs (each below PRUNE), same units
        var heatVersion = 0               // bumped when heat drops; older heap entries are then void
        var index = -1                    // position in all, for the sweep
    }

    /** A heap entry: st's heat was [key] (or more, since traversals only add) at [version]. */
    private class HeatEntry(val key: Double, val st: FileState, val version: Int) : Comparable<HeatEntry> {
        override fun compareTo(other: HeatEntry): Int {
            val c = key.compareTo(other.key)
            return if (c != 0) c else st.id.compareTo(other.st.id)
        }
    }

    /** The pair of files [a] and [b]: its weight as of [time]. */
    private class Edge(var weight: Double, var time: Long, val a: FileState, val b: FileState) {
        fun other(st: FileState): FileState = if (a === st) b else a
    }

    private val rng = Random(seed)
    private val alive = BooleanArray(serverCount) { true }
    private var aliveStamp = 0                                // bumped when a server goes up or down
    private var aliveCount = serverCount
    private var liveRacksStamp = -1                           // see liveRacks()
    private var liveRacksCount = 0
    private var aliveCapacity = servers.sumOf { it.toLong() }  // total capacity of the live servers
    private var capCache: IntArray? = null                    // see capacities()
    private val capMemoKey = LongArray(CAP_MEMO) { -1L }      // (file count, aliveStamp) of recent caps
    private val capMemo = arrayOfNulls<IntArray>(CAP_MEMO)
    private var capMemoNext = 0
    private var capOrder = IntArray(serverCount) { it }       // water-filling order last time
    private val files = HashMap<Int, FileState>()
    private val all = ArrayList<FileState>()                  // the same files, in the order the sweep visits them
    private var sweepNext = 0                                 // the next file the sweep visits
    private var sweepCredit = 0.0                             // files the sweep may visit now
    private val onServer = Array(serverCount) { ServerFiles(it) }       // server -> files with a copy there
    // Files that left the placer (evictFile) stay stored on their servers and take disk space there.
    private val evicted = HashMap<Int, IntArray>()            // file -> the servers it is stored on
    private val cold = IntArray(serverCount)                  // server -> replicas of evicted files on it
    private var now = 0L                                      // traversals seen so far
    private val pending = Array(replicaCount) { LinkedHashSet<Int>() } // live count -> files to repair
    private val queuedAt = HashMap<Int, Int>()                // file -> its bucket in pending (may be stale, see pumpRepairs)
    private val stranded = HashSet<Int>()                     // files with no live replica
    private val inGrace = BooleanArray(serverCount)           // down for less than grace traversals
    private val downSince = LongArray(serverCount)
    private val graceQueue = ArrayDeque<Int>()                // servers in grace, oldest first
    private var repairBudget = 0.0                            // copies repair may make right now
    private val lambda = ln(2.0) / halfLife
    // Files by total pair weight. All weights decay at the same rate, so the order changes only
    // when weight is added or a pair is removed. heat is kept in forward-decay units,
    // exp(lambda * (t - heatBase)) per traversal at t, and today's weight is heat / heatScale(now);
    // heatBase moves up now and then so the units do not overflow. The heap is lazy: a traversal
    // only raises heat, leaving the file's entry understated, which lightestFile() fixes when the
    // entry reaches the top. Only a drop in heat pushes a new entry.
    private var lightest = PriorityQueue<HeatEntry>()
    private var heatBase = 0L
    private val minLink = distances?.let { d ->                // shortest link between two servers
        (0 until serverCount).minOfOrNull { a -> (0 until serverCount).filter { it != a }.minOfOrNull { d[a][it] } ?: 1.0 }
    } ?: 1.0

    // ------------------------------------------------------------------ public API

    /** Registers a new file and puts its replicas on the least loaded live servers. */
    override fun addFile(file: Int) = addFile(file, -1)

    /**
     * Registers a new file reached by a traversal from [near] (-1: none). With [placeNear], its
     * replicas go first on near's live servers that are below their cap (by two), so that traversal is
     * local; the rest, as without it, on the least loaded live servers.
     */
    override fun addFile(file: Int, near: Int) {
        register(file)
        var placed = 0
        if (placeNear && near != file) files[near]?.let { liveOf(it) }?.let { servers ->
            val cap = capacities()
            for (t in servers) {
                // One slot short of the cap: caps shrink when files leave, and a server left over
                // its cap would cost a rebalancing copy.
                if (placed < target() && onServer[t].size + 1 < cap[t] && !diskFull(t)) {
                    place(file, t)
                    placed++
                }
            }
        }
        repeat(target() - placed) { addReplica(file, counted = false) }
    }

    /**
     * Re-registers a file that left the placer but is still stored on [stored]: its replicas stay
     * on those servers, at no cost, where the server is live and below its cap. A replica that
     * cannot stay is copied from a stored one to a server chosen as for a repair (a rebalancing
     * copy, alpha * distance), like any other move of data; replicas it was short of when it left
     * (a server was down) are repair copies.
     */
    override fun restoreFile(file: Int, stored: IntArray) {
        evicted.remove(file)?.forEach { cold[it]-- }  // kept below, or moved off (the old copy deleted)
        val st = register(file)
        val cap = capacities()
        for (t in stored) if (st.servers.size < target() && alive[t] && t !in st.servers && onServer[t].size < cap[t]) place(file, t)
        var moved = minOf(stored.size, target()) - st.servers.size   // stored replicas that could not stay
        while (st.servers.size < target()) {
            val before = st.servers.size
            addReplica(file, counted = false)
            if (st.servers.size == before) break                  // no live server left without a copy
            val t = st.servers.last()
            if (moved-- > 0) stats.rebalanceCopies++ else stats.repairCopies++
            stats.copyDistance += stored.minOfOrNull { if (it == t) 0.0 else distances?.get(it)?.get(t) ?: 1.0 } ?: 1.0
        }
    }

    private fun register(file: Int): FileState {
        require(file !in files) { "file $file already exists" }
        check(aliveCount > 0) { "no live server to store file $file" }
        val st = FileState(file)
        files[file] = st
        st.index = all.size
        all.add(st)
        lightest.add(HeatEntry(0.0, st, 0))
        capCache = null
        return st
    }

    /**
     * Takes a file out of the placer but leaves its live replicas stored where they are, taking
     * disk space ([diskFull]) until [restoreFile] brings it back.
     */
    override fun evictFile(file: Int) {
        val at = liveReplicas(file).copyOf()
        removeFile(file)
        evicted[file] = at
        for (t in at) cold[t]++
    }

    /**
     * Removes a file: deletes all its copies (live or not), forgets its traversal weights and
     * takes it off the repair queue. The caps shrink with the file count, so servers now above
     * theirs are relieved as after a server returns.
     */
    override fun removeFile(file: Int) {
        val st = files[file] ?: throw IllegalArgumentException("unknown file $file")
        for (s in st.servers) removeCopy(st, s)      // iterates the old array; removeCopy replaces it
        queuedAt.remove(file)?.let { pending[it].remove(file) }
        stranded.remove(file)
        val scale = heatScale(now)
        st.nbrs?.forEach { e ->
            val other = e.other(st)
            val m = other.nbrs ?: return@forEach
            m.remove(file)
            if (m.isEmpty()) other.nbrs = null
            coolDown(other, weightOf(e) * scale)      // the neighbour loses the weight of this pair
        }
        st.nbrs = null
        files.remove(file)                            // its heap entries are void from now on
        val last = all.removeAt(all.size - 1)
        if (last !== st) {
            all[st.index] = last
            last.index = st.index
        }
        capCache = null
        rebalance()
    }

    /** Records one traversal between two known files and returns what it cost. */
    override fun addEdge(edge: Pair<Int, Int>): Access {
        val (u, v) = edge
        val su = files[u] ?: throw IllegalArgumentException("unknown file $u")
        val sv = files[v] ?: throw IllegalArgumentException("unknown file $v")
        if (u == v) return Access.LOCAL
        now++
        sweep()
        while (graceQueue.isNotEmpty() && now - downSince[graceQueue.first()] >= grace) expire(graceQueue.removeFirst())
        if (queuedAt.isNotEmpty()) {
            repairBudget = min(repairBudget + repairRate, maxOf(1.0, repairRate))
            pumpRepairs()
        }
        val e = edgeOf(su, sv)
        e.weight = weightOf(e) + 1.0
        e.time = now
        val scale = heatScale(now)
        su.heat += scale                              // the heap entries catch up lazily
        sv.heat += scale
        val au = liveOf(su)
        val av = liveOf(sv)
        val d = dist(au, av)
        // Surplus replicas left by a returning server are dropped when the file is next traversed.
        if (au.size > target()) trim(u)
        if (av.size > target()) trim(v)
        if (racks != null) {                          // a file left in one rack moves out of it
            if (!spread(liveOf(su))) respread(su)
            if (!spread(liveOf(sv))) respread(sv)
        }
        if (d == Double.POSITIVE_INFINITY) {
            stats.unavailable++
            return Access.UNAVAILABLE
        }
        if (d == 0.0) {
            stats.local++
            return Access.LOCAL
        }
        stats.remote++
        stats.commCost += d
        charge(su, d)
        charge(sv, d)
        return Access.REMOTE
    }

    /** Takes server [component] down or brings it back; returns true if it is up afterwards. */
    fun toggleComponent(component: Int): Boolean {
        require(component in 0 until serverCount) { "unknown component $component" }
        capCache = null
        aliveStamp++
        if (alive[component]) {
            alive[component] = false
            aliveCount--
            aliveCapacity -= servers[component]
            val othersInGrace = graceQueue.isNotEmpty()
            if (grace > 0) {
                inGrace[component] = true
                downSince[component] = now
                graceQueue.addLast(component)
            }
            // A file that had k live replicas keeps k - 1 >= minReplicas and waits out the grace
            // period. So unless that does not hold, or another server is also in grace, only
            // files that were already short can need a repair now; they are all in the queue.
            val onlyQueued = grace > 0 && minReplicas < replicaCount && aliveCount >= replicaCount && !othersInGrace
            val affected = if (onlyQueued && queuedAt.size < onServer[component].size) {
                queuedAt.keys.filter { component in files.getValue(it).servers }
            } else if (onlyQueued) {
                onServer[component].ids().filter { it in queuedAt }
            } else {
                onServer[component].ids()
            }
            for (f in affected) schedule(f)
            if (aliveCount < replicaCount) {             // the target shrank: some queued files are done
                for (f in queuedAt.keys.toList()) schedule(f)
            }
        } else {
            alive[component] = true
            aliveCount++
            aliveCapacity += servers[component]
            if (inGrace[component]) {
                inGrace[component] = false
                graceQueue.remove(component)
            }
            // Files on this server gained a live replica, but are not visited here: a surplus
            // replica is dropped lazily (next traversal, or eviction), and a stale queue entry is
            // fixed when it is popped. Only files that had no live replica must be queued now.
            // If the target rose (fewer than k servers were up), any file may be short.
            val affected = if (aliveCount <= replicaCount) files.keys.toList() else stranded.toList()
            for (f in affected) schedule(f)
            rebalance()
        }
        pumpRepairs()
        return alive[component]
    }

    fun isUp(component: Int): Boolean = alive[component]

    override fun isKnown(file: Int): Boolean = file in files

    /** Live replicas of [file]; empty while all of its servers are down. */
    override fun replicasOf(file: Int): List<Int> = liveReplicas(file).toList()

    /** Number of copies on server [component], including copies on a server that is down. */
    fun load(component: Int): Int = onServer[component].size

    override val fileCount: Int get() = files.size

    /** Total decayed weight of [file]'s pairs, i.e. the sum of their weights now; O(1). */
    fun fileWeight(file: Int): Double = files.getValue(file).heat / heatScale(now)

    /**
     * The file whose pairs weigh least in total (ties: lowest id); null if there are no files.
     * Amortised O(log F): entries left understated by traversals are re-queued on the way.
     */
    fun lightestFile(): Int? {
        while (true) {
            val e = lightest.peek() ?: return null
            val st = e.st
            if (files[st.id] !== st || e.version != st.heatVersion) {
                lightest.poll()                       // removed file, or superseded by a drop in heat
            } else if (e.key < st.heat) {
                lightest.poll()
                lightest.add(HeatEntry(st.heat, st, e.version))
            } else {
                return st.id
            }
        }
    }

    /** Files whose live replicas are all in one rack although two racks are up; O(F·k), for measurements. */
    fun filesInOneRack(): Int = files.values.count { !spread(liveOf(it)) }

    /**
     * Files a failure of one whole rack would leave without a live replica (all copies in that
     * rack, or on servers already down), counted for each rack; O(F·k), for measurements.
     */
    fun lostIfRackFails(): Map<Int, Int> {
        val r = racks ?: return emptyMap()
        return r.distinct().associateWith { rack -> files.values.count { st -> liveOf(st).all { r[it] == rack } } }
    }

    /** Pairs of files with a remembered traversal weight; O(F) to count, for measurements. */
    val pairCount: Long get() = files.values.sumOf { it.nbrs?.size?.toLong() ?: 0L } / 2

    /**
     * Files queued for a repair copy. A file a returning server has already completed stays
     * counted until the queue reaches it, so this is an upper bound until the queue drains.
     */
    val pendingRepairs: Int get() = queuedAt.size

    /**
     * Maximum number of replicas on live server [component]: (1 + slack) times its share of all
     * replicas, shares being proportional to capacity, rounded up. A server can hold at most its
     * capacity, and at most one replica per file; a share it cannot use goes to the others.
     */
    fun capacity(component: Int): Int = capacities()[component]

    /** Per-server caps (see [capacity]); cached until a file is added or a server is toggled. */
    private fun capacities(): IntArray {
        capCache?.let { return it }
        // The caps depend only on the file count and the live servers. Files coming and going
        // (removeFile, then addFile) revisit the same few counts, so recent results are kept.
        val key = (files.size.toLong() shl 32) or (aliveStamp.toLong() and 0xffffffffL)
        for (i in 0 until CAP_MEMO) if (capMemoKey[i] == key) return capMemo[i]!!.also { capCache = it }
        val cap = IntArray(serverCount)
        val usable = IntArray(serverCount) { min(servers[it], files.size) }  // distinct replicas only
        var total = (1.0 + slack) * files.size.toLong() * target()          // replicas to share out
        var weight = aliveCapacity.toDouble()
        // Water-filling: servers whose share exceeds what they can use are capped first, in order
        // of usable / capacity (ties: lower index). Insertion sort from the last order, which
        // rarely changes, so this is usually O(n).
        val ratio = DoubleArray(serverCount) { usable[it].toDouble() / servers[it] }
        val order = capOrder
        for (i in 1 until serverCount) {
            val x = order[i]
            var j = i - 1
            while (j >= 0 && (ratio[order[j]] > ratio[x] || ratio[order[j]] == ratio[x] && order[j] > x)) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = x
        }
        for (t in order) {
            if (!alive[t]) continue
            val share = total * servers[t] / weight
            if (usable[t] <= share) {
                cap[t] = usable[t]
                total -= usable[t]
                weight -= servers[t]
            } else {
                cap[t] = min(usable[t], maxOf(1, ceil(share).toInt()))
            }
        }
        capCache = cap
        capMemoKey[capMemoNext] = key
        capMemo[capMemoNext] = cap
        capMemoNext = (capMemoNext + 1) % CAP_MEMO
        return cap
    }

    /** Fraction of server t's capacity in use; "least loaded" compares this. */
    private fun fill(t: Int): Double = onServer[t].size.toDouble() / servers[t]

    /**
     * Whether server t's disk is full: it stores (1 + slack) times its share of every stored
     * replica, those of evicted files included. The caps only limit the files in the placer, so
     * without this, evicted files could pile up on whichever server keeps freeing cap. Only with
     * evicted files; otherwise the caps already bound the disk.
     */
    private fun diskFull(t: Int): Boolean {
        if (evicted.isEmpty()) return false
        val share = (1.0 + slack) * (files.size + evicted.size).toLong() * target() * servers[t] / aliveCapacity
        return onServer[t].size + cold[t] >= maxOf(1, ceil(share).toInt())
    }

    /** Whether a new, restored or repair replica may go on server t: below its cap and its disk not full. */
    private fun hasRoom(t: Int, cap: IntArray): Boolean = onServer[t].size < cap[t] && !diskFull(t)

    /** Sum over known pairs of decayed weight * current cost (unavailable pairs are skipped). */
    fun weightedCost(): Double {
        var sum = 0.0
        for (st in files.values) {
            val m = st.nbrs ?: continue
            val au = liveOf(st)
            m.forEach { e ->
                val other = e.other(st)
                if (st.id >= other.id) return@forEach
                val d = dist(au, liveOf(other))
                if (d.isFinite()) sum += weightOf(e) * d
            }
        }
        return sum
    }

    /** Throws if the internal state is inconsistent. */
    override fun checkInvariants() {
        val valid = lightest.filter { files[it.st.id] === it.st && it.version == it.st.heatVersion }
        check(valid.size == files.size && valid.all { it.key <= it.st.heat }) {
            "${valid.size} valid heap entries for ${files.size} files"
        }
        for (st in files.values) {
            var sum = st.residue / heatScale(now)     // forgotten pairs leave their last weight behind
            st.nbrs?.let { m ->
                check(!m.isEmpty()) { "file ${st.id} keeps an empty neighbour map" }
                var count = 0
                m.forEach { e ->
                    val g = e.other(st).id
                    check(m[g] === e && e.other(st).nbrs?.get(st.id) === e) { "pair ${st.id}-$g is one-sided" }
                    sum += weightOf(e)
                    count++
                }
                check(count == m.size) { "file ${st.id}: ${m.size} neighbours counted, $count found" }
            }
            check(abs(fileWeight(st.id) - sum) <= 1e-9 * (1.0 + sum)) { "file ${st.id} weighs ${fileWeight(st.id)}, its pairs $sum" }
        }
        check(all.size == files.size && all.withIndex().all { (i, st) -> st.index == i && files[st.id] === st }) {
            "the sweep order lists ${all.size} files, ${files.size} exist"
        }
        val cap = capacities()
        for ((f, st) in files) {
            val r = st.servers
            check(r.size == r.toSet().size) { "file $f has duplicate replicas ${r.toList()}" }
            check(r.size == st.slots.size) { "file $f has ${r.size} replicas and ${st.slots.size} slots" }
            for (s in r) check(st in onServer[s]) { "file $f missing in the index of server $s" }
            val live = liveReplicas(f).size
            val queued = queuedAt[f]
            check(!needsRepair(f, live) || queued != null) {
                "file $f has $live live replicas, expected ${target()}, and is not queued"
            }
            check(queued == null || queued in 1 until target() && queued <= live) {
                "file $f is queued at $queued with $live live replicas"
            }
            check((live == 0) == (f in stranded)) { "file $f has $live live replicas, stranded: ${f in stranded}" }
        }
        for (s in 0 until serverCount) {
            for (f in onServer[s].ids()) check(s in files.getValue(f).servers) { "server $s lists file $f wrongly" }
            if (alive[s] && stats.overflows == 0L) {
                check(onServer[s].size <= cap[s]) { "server $s holds ${onServer[s].size} > capacity ${cap[s]}" }
            }
        }
        check(cold.sum() == evicted.values.sumOf { it.size } && cold.all { it >= 0 }) { "evicted replicas ${cold.toList()}" }
        for (f in evicted.keys) check(f !in files) { "file $f is both evicted and in the placer" }
    }

    // ------------------------------------------------------------------ cost model

    /**
     * Cost of one traversal between two live servers. Without [distances] it is 0 or 1, which
     * [gains] and [losses] use for a faster formula; otherwise they use [weightedGains].
     */
    private fun serverCost(a: Int, b: Int): Double {
        val d = distances ?: return if (a == b) 0.0 else 1.0
        return d[a][b]
    }

    /** Cheapest traversal from server t to any server in [b]. */
    private fun dist(t: Int, b: IntArray): Double {
        if (distances == null) return if (b.isEmpty()) Double.POSITIVE_INFINITY else if (t in b) 0.0 else 1.0
        var best = Double.POSITIVE_INFINITY
        for (y in b) best = min(best, serverCost(t, y))
        return best
    }

    /** Distance a copy of f to server t travels: from f's nearest live replica; 0 for a new file. */
    private fun copyDist(f: Int, t: Int): Double = liveReplicas(f).let { if (it.isEmpty()) 0.0 else dist(t, it) }

    /** alpha times [d]; a free copy (a new file's upload) stays free even when alpha is infinite. */
    private fun copyCost(d: Double): Double = if (d == 0.0) 0.0 else alpha * d

    /** Cheapest traversal between replica sets [a] (optionally without a[skip]) and [b]; +inf if none. */
    private fun dist(a: IntArray, b: IntArray, skip: Int = -1): Double {
        if (distances == null) {                      // 0/1 costs: do they share a server?
            if (b.isEmpty()) return Double.POSITIVE_INFINITY
            var any = false
            for (i in a.indices) {
                if (i == skip) continue
                if (a[i] in b) return 0.0
                any = true
            }
            return if (any) 1.0 else Double.POSITIVE_INFINITY
        }
        var best = Double.POSITIVE_INFINITY
        for (i in a.indices) {
            if (i == skip) continue
            for (y in b) best = min(best, serverCost(a[i], y))
        }
        return best
    }

    private fun target(): Int = min(replicaCount, aliveCount)

    /** Racks with a live server, cached until a server is toggled. */
    private fun liveRacks(): Int {
        if (liveRacksStamp != aliveStamp) {
            liveRacksCount = racks?.let { r -> (0 until serverCount).filter { alive[it] }.map { r[it] }.distinct().size } ?: 0
            liveRacksStamp = aliveStamp
        }
        return liveRacksCount
    }

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

    /** Whether live replicas [a] are spread as [racks] asks: over two racks, or that is impossible. */
    private fun spread(a: IntArray): Boolean = racks == null || a.size < 2 || liveRacks() < 2 || soleRack(a) < 0

    /** Whether replica a[j] may move to t: it may not put a spread file back into one rack. */
    private fun keepsSpread(a: IntArray, j: Int, t: Int): Boolean {
        val r = racks ?: return true
        if (a.size < 2 || liveRacks() < 2 || soleRack(a) >= 0) return true
        val rest = soleRack(a, j)
        return rest < 0 || r[t] != rest
    }

    private fun liveReplicas(f: Int): IntArray = files[f]?.let { liveOf(it) } ?: EMPTY

    /** st's live replicas, cached until its replicas change or a server is toggled. Do not modify. */
    private fun liveOf(st: FileState): IntArray {
        if (st.liveStamp == aliveStamp) return st.live
        val r = st.servers
        var count = 0
        for (s in r) if (alive[s]) count++
        st.live = if (count == r.size) r else IntArray(count).also { out ->
            var i = 0
            for (s in r) if (alive[s]) out[i++] = s
        }
        st.liveStamp = aliveStamp
        return st.live
    }

    /** Replaces st's replica list (and slots) with new arrays; never changes them in place. */
    private fun setServers(st: FileState, servers: IntArray, slots: IntArray) {
        st.servers = servers
        st.slots = slots
        st.liveStamp = -1
    }

    // ------------------------------------------------------------------ traversal weights

    private fun edgeOf(su: FileState, sv: FileState): Edge {
        val mu = su.nbrs ?: EdgeMap().also { su.nbrs = it }
        mu[sv.id]?.let { return it }
        val e = Edge(0.0, now, su, sv)
        mu[sv.id] = e
        (sv.nbrs ?: EdgeMap().also { sv.nbrs = it })[su.id] = e
        return e
    }

    private fun weightOf(e: Edge): Double = e.weight * exp(-lambda * (now - e.time))

    /** Forward-decay units per unit of weight at time t; rebases first if they would get too large. */
    private fun heatScale(t: Long): Double {
        if (lambda * (t - heatBase) > HEAT_REBASE) {
            val shrink = exp(-lambda * (t - heatBase))
            for (st in files.values) {
                st.heat *= shrink
                st.residue *= shrink
            }
            heatBase = t
            rebuildHeap()
        }
        return exp(lambda * (t - heatBase))
    }

    /** Takes [delta] forward units off st's total weight (never below 0) and re-queues it. */
    private fun coolDown(st: FileState, delta: Double) {
        st.heat = max(0.0, st.heat - delta)
        st.heatVersion++
        lightest.add(HeatEntry(st.heat, st, st.heatVersion))
        if (lightest.size > 2 * files.size + 1_024) rebuildHeap()   // drop the void entries
    }

    /** One fresh entry per file. */
    private fun rebuildHeap() {
        lightest = PriorityQueue(files.values.map { HeatEntry(it.heat, it, ++it.heatVersion) })  // heapify, O(F)
    }

    /**
     * Forgets pairs whose weight has decayed to nothing, so memory stays bounded. A few files per
     * traversal, in turn, so every file is visited once per [sweepEvery] traversals and no single
     * traversal pays for a pass over the whole graph.
     */
    private fun sweep() {
        sweepCredit += files.size.toDouble() / sweepEvery
        while (sweepCredit >= 1.0 && all.isNotEmpty()) {
            sweepCredit -= 1.0
            if (sweepNext >= all.size) sweepNext = 0
            val st = all[sweepNext++]
            val m = st.nbrs ?: continue
            var stale: ArrayList<Edge>? = null
            m.forEach { e -> if (weightOf(e) < PRUNE) (stale ?: ArrayList<Edge>().also { stale = it }).add(e) }
            stale?.let { forget(st, it) }
        }
    }

    /** Drops st's [stale] pairs on both sides; their last weight stays in heat as residue. */
    private fun forget(st: FileState, stale: List<Edge>) {
        val m = st.nbrs ?: return
        val scale = heatScale(now)
        for (e in stale) {
            val other = e.other(st)
            val r = weightOf(e) * scale
            m.remove(other.id)
            st.residue += r
            other.nbrs?.let {
                if (it.remove(st.id) != null) other.residue += r
                if (it.isEmpty()) other.nbrs = null
            }
        }
        if (m.isEmpty()) st.nbrs = null
    }

    /** Live replicas of one file plus its reachable neighbours and their current edge costs. */
    private class Ctx(
        val a: IntArray,          // live replicas of the file
        val w: DoubleArray,       // decayed weight of each neighbour edge
        val na: Array<IntArray>,  // live replicas of each neighbour
        val c: DoubleArray,       // current cost of each neighbour edge
    )

    private fun context(f: Int): Ctx? {
        val st = files[f] ?: return null
        val a = liveOf(st)
        if (a.isEmpty()) return null
        val m = st.nbrs ?: return Ctx(a, DoubleArray(0), emptyArray(), DoubleArray(0))
        val w = DoubleArray(m.size)
        val na = arrayOfNulls<IntArray>(m.size)
        val c = DoubleArray(m.size)
        var n = 0
        var stale: ArrayList<Edge>? = null
        for (i in 0 until m.slots) {
            val e = m.at(i) ?: continue
            val we = weightOf(e)
            if (we < PRUNE) {
                (stale ?: ArrayList<Edge>().also { stale = it }).add(e)
                continue
            }
            val ga = liveOf(e.other(st))
            if (ga.isEmpty()) continue        // unreachable neighbour: its cost cannot change
            w[n] = we
            na[n] = ga
            c[n] = dist(a, ga)
            n++
        }
        stale?.let { forget(st, it) }
        @Suppress("UNCHECKED_CAST")
        return Ctx(a, w.copyOf(n), (na.copyOf(n) as Array<IntArray>), c.copyOf(n))
    }

    /**
     * out[j][t] = drop of the file's weighted edge cost if its replica ctx.a[sources[j]] moves to
     * server t; sources[j] == -1 means a new replica is added at t instead.
     * -inf where t cannot take a replica (down, or already holds one).
     *
     * With 0/1 costs a neighbour's edge changes only if it is remote and gains a shared server, or
     * is local through the moved replica alone and does not share t. So per neighbour:
     * out[j][t] = pull[t] - loss[j] + keep[j][t], built in one pass over the neighbours' replicas.
     */
    private fun gains(ctx: Ctx, sources: IntArray, onlyTarget: Int = -1): Array<DoubleArray> {
        if (distances != null) return weightedGains(ctx, sources, onlyTarget)
        val a = ctx.a
        val pull = DoubleArray(serverCount)                    // remote weight that becomes local at t
        val loss = DoubleArray(a.size)                         // local weight that hangs on a[j] alone
        val keep = arrayOfNulls<DoubleArray>(a.size)           // ...of which is still local if a[j] goes to t
        for (j in sources) if (j >= 0) keep[j] = DoubleArray(serverCount)
        for (i in ctx.w.indices) {
            val w = ctx.w[i]
            val na = ctx.na[i]
            if (ctx.c[i] > 0.0) {
                for (s in na) pull[s] += w
                continue
            }
            val j = soleShared(a, na)
            if (j < 0) continue                                // stays local whichever replica moves
            loss[j] += w
            val kj = keep[j] ?: continue
            for (s in na) kj[s] += w
        }
        return Array(sources.size) { sj ->
            val j = sources[sj]
            val out = DoubleArray(serverCount) { Double.NEGATIVE_INFINITY }
            for (t in 0 until serverCount) {
                if (onlyTarget >= 0 && t != onlyTarget) continue
                if (!alive[t] || t in a) continue
                out[t] = if (j < 0) pull[t] else pull[t] - loss[j] + keep[j]!![t]
            }
            out
        }
    }

    /**
     * [gains] for a general distance matrix. Per neighbour: the edge costs c (its closest replica
     * a[j*]) now, and `second` without a[j*]; toT[t] = distance from t to the neighbour. Moving a[j]
     * to t makes the edge cost min(c, or second if j = j*, toT[t]). O(k^2 + n·k) per neighbour.
     */
    private fun weightedGains(ctx: Ctx, sources: IntArray, onlyTarget: Int): Array<DoubleArray> {
        val a = ctx.a
        val targets = (if (onlyTarget >= 0) intArrayOf(onlyTarget) else IntArray(serverCount) { it })
            .filter { alive[it] && it !in a }
        val out = Array(sources.size) { DoubleArray(serverCount) { Double.NEGATIVE_INFINITY } }
        for (o in out) for (t in targets) o[t] = 0.0
        val toT = DoubleArray(serverCount)
        for (i in ctx.w.indices) {
            val w = ctx.w[i]
            val na = ctx.na[i]
            val c = ctx.c[i]
            val (closest, second) = closest(a, na)
            for (t in targets) toT[t] = dist(t, na)
            for (sj in sources.indices) {
                val j = sources[sj]
                val without = if (j == closest) second else c  // a new replica (j = -1) keeps c
                val o = out[sj]
                for (t in targets) o[t] += w * (c - min(without, toT[t]))
            }
        }
        return out
    }

    /** Index of the replica in [a] closest to [b], and the cost from [b] to the others (+inf if none). */
    private fun closest(a: IntArray, b: IntArray): Pair<Int, Double> {
        var j = -1
        var best = Double.POSITIVE_INFINITY
        var second = Double.POSITIVE_INFINITY
        for (i in a.indices) {
            val m = dist(a[i], b)
            if (j < 0 || m < best) {
                second = best
                best = m
                j = i
            } else {
                second = min(second, m)
            }
        }
        return j to second
    }

    /** losses[j] = rise of the file's weighted edge cost if its replica ctx.a[j] is dropped. */
    private fun losses(ctx: Ctx): DoubleArray {
        val loss = DoubleArray(ctx.a.size)
        if (distances != null) {
            // Only dropping the closest replica changes the edge: it then costs the second closest.
            for (i in ctx.w.indices) {
                val (j, second) = closest(ctx.a, ctx.na[i])
                if (j >= 0 && second.isFinite()) loss[j] += ctx.w[i] * (second - ctx.c[i])
            }
            return loss
        }
        for (i in ctx.w.indices) {
            if (ctx.c[i] > 0.0) continue                       // already remote: dropping a replica cannot hurt
            val j = soleShared(ctx.a, ctx.na[i])
            if (j >= 0) loss[j] += ctx.w[i]
        }
        return loss
    }

    /** Index of the only replica in [a] that [b] shares; -1 if they share none or several. */
    private fun soleShared(a: IntArray, b: IntArray): Int {
        var only = -1
        for (j in a.indices) {
            if (a[j] !in b) continue
            if (only >= 0) return -1
            only = j
        }
        return only
    }

    // ------------------------------------------------------------------ optimisation moves

    /** A move; [dist] is the total distance its copies travel. */
    private class Option(val file: Int, val from: Int, val to: Int, val gain: Double, val dist: Double, val swapFile: Int = -1) {
        val copies: Int get() = if (swapFile >= 0) 2 else 1
    }

    private fun net(o: Option): Double = o.gain - copyCost(o.dist)

    /** Higher net gain wins; equal nets (e.g. all -inf with an infinite alpha) fall back to the gain. */
    private fun better(o: Option, than: Option?): Boolean =
        than == null || net(o) > net(than) || net(o) == net(than) && o.gain > than.gain

    /** Rent-or-buy: once a file has paid alpha in remote traversals, look for a better place for it. */
    private fun charge(st: FileState, cost: Double) {
        st.debt += cost
        if (st.debt < alpha) return
        st.debt = 0.0
        improve(st.id)
    }

    private fun improve(f: Int) {
        val o = bestOption(f, capacities()) ?: return
        if (net(o) < 0) return                       // the copies would cost more than they save
        val before = if (verifyGains) weightedCost() else 0.0
        relocate(o.file, o.from, o.to)
        if (o.swapFile >= 0) {
            relocate(o.swapFile, o.to, o.from)
            stats.swaps++
        }
        stats.optimizationCopies += o.copies
        stats.copyDistance += o.dist
        if (verifyGains) {
            val actual = before - weightedCost()
            check(abs(actual - o.gain) <= 1e-6 * (1.0 + abs(before))) {
                "predicted gain ${o.gain}, measured $actual"
            }
        }
    }

    /** Best relocation of one replica of f: to a server with room, or a swap into a full one. */
    private fun bestOption(f: Int, cap: IntArray): Option? {
        val ctx = context(f) ?: return null
        if (ctx.w.isEmpty()) return null
        val g = gains(ctx, IntArray(ctx.a.size) { it })
        val cd = DoubleArray(serverCount) { t -> if (alive[t]) dist(t, ctx.a) else 0.0 }
        // The best move to a server with room and into a full one, by net gain, then gain (as in
        // better()); kept as indices so that no Option is made for the candidates that lose.
        var freeJ = -1
        var freeT = -1
        var freeNet = 0.0
        var freeGain = 0.0
        var fullJ = -1
        var fullT = -1
        var fullNet = 0.0
        var fullGain = 0.0
        for (j in ctx.a.indices) {
            val gj = g[j]
            for (t in 0 until serverCount) {
                val gt = gj[t]
                if (gt == Double.NEGATIVE_INFINITY || !keepsSpread(ctx.a, j, t)) continue
                val nt = gt - copyCost(cd[t])
                if (onServer[t].size < cap[t]) {
                    if (freeJ < 0 || nt > freeNet || nt == freeNet && gt > freeGain) {
                        freeJ = j; freeT = t; freeNet = nt; freeGain = gt
                    }
                } else if (fullJ < 0 || nt > fullNet || nt == fullNet && gt > fullGain) {
                    fullJ = j; fullT = t; fullNet = nt; fullGain = gt
                }
            }
        }
        val free = if (freeJ < 0) null else Option(f, ctx.a[freeJ], freeT, freeGain, cd[freeT])
        val full = if (fullJ < 0) null else Option(f, ctx.a[fullJ], fullT, fullGain, cd[fullT])
        var best = free
        // A swap pays for a second copy too, over at least the shortest link.
        if (full != null && (best == null || net(full) - copyCost(minLink) > net(best))) {
            val swap = bestSwap(full)
            if (swap != null && (best == null || net(swap) > net(best))) best = swap
        }
        return best
    }

    /** o.to is full: find a file x there that moves to o.from, maximising the combined gain. */
    private fun bestSwap(o: Option): Option? {
        val st = files.getValue(o.file)
        val rf = st.servers
        val slots = st.slots
        setServers(st, rf.copyOf().also { it[it.indexOf(o.from)] = o.to }, slots)  // tentative move, so x's gain sees it
        var best: Option? = null
        try {
            for (x in sample(onServer[o.to], SWAP_SAMPLE)) {
                if (x == o.file || o.from in files.getValue(x).servers) continue
                val ctx = context(x) ?: continue
                val j = ctx.a.indexOf(o.to)
                if (j < 0 || !keepsSpread(ctx.a, j, o.from)) continue
                val gx = gains(ctx, intArrayOf(j), onlyTarget = o.from)[0][o.from]
                if (gx == Double.NEGATIVE_INFINITY) continue
                val swap = Option(o.file, o.from, o.to, o.gain + gx, o.dist + dist(o.from, ctx.a), x)
                if (better(swap, best)) best = swap
            }
        } finally {
            setServers(st, rf, slots)
        }
        return best
    }

    // ------------------------------------------------------------------ replicas, failures, capacity

    /** Adds one live replica of f where it helps f's neighbours most (ties: least loaded server). */
    private fun addReplica(f: Int, counted: Boolean) {
        val cap = capacities()
        val have = files.getValue(f).servers
        val g = context(f)?.let { gains(it, intArrayOf(-1))[0] }
        var best = -1
        var bestNet = Double.NEGATIVE_INFINITY
        var bestGain = Double.NEGATIVE_INFINITY
        val start = rng.nextInt(serverCount)
        // With racks, a file in one rack takes its next replica elsewhere if any other rack has room.
        var avoid = if (racks != null && liveRacks() >= 2) soleRack(liveOf(files.getValue(f))) else -1
        if (avoid >= 0 && (0 until serverCount).none { alive[it] && racks!![it] != avoid && it !in have && hasRoom(it, cap) }) avoid = -1
        for (i in 0 until serverCount) {
            val t = (start + i) % serverCount
            if (!alive[t] || t in have || !hasRoom(t, cap) || avoid >= 0 && racks!![t] == avoid) continue
            val gt = g?.get(t) ?: 0.0
            // A repair copy from a nearby replica is cheaper; a new file's replicas are uploads.
            val nt = if (counted) gt - copyCost(copyDist(f, t)) else gt
            if (best < 0 || nt > bestNet + EPS || nt >= bestNet - EPS &&
                (gt > bestGain + EPS || gt > bestGain - EPS && fill(t) < fill(best))
            ) {
                best = t
                bestNet = nt
                bestGain = gt
            }
        }
        // Every server below its cap has a full disk: the emptiest disk among them. Refusing them
        // all would leave the free slots on full disks for good (an evicted file's data stays
        // until it comes back), and every new replica would then have to make room by moving another.
        if (best < 0) best = (0 until serverCount).filter { alive[it] && it !in have && onServer[it].size < cap[it] }
            .minByOrNull { (onServer[it].size + cold[it]).toDouble() / servers[it] } ?: -1
        if (best < 0) best = makeRoom(f, cap)
        if (best < 0) {                               // nothing can be freed: exceed the cap
            best = (0 until serverCount).filter { alive[it] && it !in have }.minByOrNull { fill(it) } ?: return
            stats.overflows++
        }
        if (counted) {
            stats.repairCopies++
            stats.copyDistance += copyDist(f, best)
        }
        place(f, best)
    }

    /**
     * Queues f for repair under its live replica count, or takes it off the queue if it is not
     * missing any replica (or all its copies are on dead servers: it waits for one to come back).
     */
    private fun schedule(f: Int) {
        queuedAt.remove(f)?.let { pending[it].remove(f) }
        val live = liveReplicas(f).size
        if (live == 0) stranded.add(f) else stranded.remove(f)
        if (!needsRepair(f, live)) return
        pending[live].add(f)
        queuedAt[f] = live
    }

    /**
     * f (with [live] live replicas) needs a repair copy if it is below the target and either below
     * minReplicas or short even when counting its copies on servers still in their grace period.
     */
    private fun needsRepair(f: Int, live: Int): Boolean {
        if (live == 0 || live >= target()) return false
        if (live < minReplicas) return true
        val waiting = files.getValue(f).servers.count { inGrace[it] }
        return live + waiting < target()
    }

    /** Server s has been down for grace traversals: its files stop waiting for it. */
    private fun expire(s: Int) {
        inGrace[s] = false
        for (f in onServer[s].ids()) schedule(f)
    }

    /**
     * Makes repair copies while the budget allows, always for a file with the fewest live replicas.
     * A returning server can raise a queued file's live count without updating its bucket; such a
     * file is moved to its real bucket (or off the queue) when popped, at no cost to the budget.
     * Buckets only understate live counts, so the most urgent files still go first.
     */
    private fun pumpRepairs() {
        while (repairRate.isInfinite() || repairBudget >= 1.0) {
            val f = pending.firstOrNull { it.isNotEmpty() }?.first() ?: return
            val live = liveReplicas(f).size
            if (live != queuedAt[f] || !needsRepair(f, live)) {
                schedule(f)
                continue
            }
            pending[queuedAt.remove(f)!!].remove(f)
            repairBudget -= 1.0
            addReplica(f, counted = true)
            schedule(f)
        }
    }

    /** Drops surplus live replicas of f (left by a server that came back), keeping the most useful ones. */
    private fun trim(f: Int) {
        while (liveReplicas(f).size > target()) {
            val ctx = context(f) ?: return
            if (ctx.a.size <= target()) return
            val all = losses(ctx)
            // With racks, drop a replica whose loss keeps the file spread, if there is one.
            val keeps = BooleanArray(ctx.a.size) { spread(ctx.a.without(it)) }
            val mustKeep = keeps.any { it }
            var bestJ = -1
            var bestLoss = Double.POSITIVE_INFINITY
            for (j in ctx.a.indices) {
                if (mustKeep && !keeps[j]) continue
                val loss = all[j]
                if (bestJ < 0 || loss < bestLoss - EPS ||
                    (loss < bestLoss + EPS && fill(ctx.a[j]) > fill(ctx.a[bestJ]))
                ) {
                    bestJ = j
                    bestLoss = loss
                }
            }
            removeCopy(files.getValue(f), ctx.a[bestJ])
        }
    }

    /** Relieves servers above capacity (capacity shrinks when a server comes back). */
    private fun rebalance() {
        while (true) {
            val cap = capacities()
            var o = -1
            for (t in 0 until serverCount) {
                val over = onServer[t].size - cap[t]
                if (alive[t] && over > 0 && (o < 0 || over > onServer[o].size - cap[o])) o = t
            }
            if (o < 0) return
            if (!evictOne(o, cap) && !trimEverywhere()) {
                stats.overflows++
                return
            }
        }
    }

    /** Trims every file with surplus replicas; O(F·r). Only before giving up on the cap. */
    private fun trimEverywhere(): Boolean {
        val surplus = files.keys.filter { liveReplicas(it).size > target() }
        for (f in surplus) trim(f)
        return surplus.isNotEmpty()
    }

    /** Frees a slot on a live server that does not hold f yet; returns that server or -1. */
    private fun makeRoom(f: Int, cap: IntArray): Int {
        val have = files.getValue(f).servers
        val candidates = (0 until serverCount).filter { alive[it] && it !in have }.sortedBy { fill(it) }
        for (t in candidates) {
            while (onServer[t].size >= cap[t] && evictOne(t, cap)) Unit
            if (onServer[t].size < cap[t]) return t
        }
        return -1
    }

    /**
     * Makes progress towards a free slot on server o, losing as little as possible. If sampled
     * files on o have surplus replicas, they are trimmed (free deletes, though the least useful
     * replica may be on another server); otherwise one replica moves to a live server with room.
     * Returns false if neither is possible.
     */
    private fun evictOne(o: Int, cap: IntArray): Boolean {
        var best: Option? = null
        val surplus = ArrayList<Int>()
        for (x in sample(onServer[o], EVICT_SAMPLE)) {
            val ctx = context(x) ?: continue
            val j = ctx.a.indexOf(o)
            if (j < 0) continue
            if (ctx.a.size > target()) {
                surplus.add(x)
                continue
            }
            if (surplus.isNotEmpty()) continue
            val g = gains(ctx, intArrayOf(j))[0]
            for (r in 0 until serverCount) {
                if (g[r] == Double.NEGATIVE_INFINITY || onServer[r].size >= cap[r] || !keepsSpread(ctx.a, j, r)) continue
                val move = Option(x, o, r, g[r], dist(r, ctx.a))
                if (better(move, best)) best = move
            }
        }
        if (surplus.isEmpty() && best == null) {   // last resort: any surplus file on o, not just the sample
            onServer[o].ids().filterTo(surplus) { liveReplicas(it).size > target() }
        }
        if (surplus.isNotEmpty()) {
            for (x in surplus) trim(x)
            return true
        }
        val b = best ?: return false
        relocate(b.file, b.from, b.to)
        stats.rebalanceCopies++
        stats.copyDistance += b.dist
        return true
    }

    /**
     * st's live replicas are all in one rack although another rack is up: moves the replica whose
     * move loses least to the best server in another rack with room (counted as a repair copy).
     * If every server in the other racks is full, swaps with a file there instead (two copies);
     * does nothing if no swap partner keeps its own spread.
     */
    private fun respread(st: FileState) {
        val ctx = context(st.id) ?: return
        val rack = soleRack(ctx.a)
        if (rack < 0) return
        val cap = capacities()
        val g = gains(ctx, IntArray(ctx.a.size) { it })
        var bestJ = -1
        var bestT = -1
        var bestNet = Double.NEGATIVE_INFINITY
        for (j in ctx.a.indices) for (t in 0 until serverCount) {
            if (!alive[t] || racks!![t] == rack || g[j][t] == Double.NEGATIVE_INFINITY || onServer[t].size >= cap[t]) continue
            val nt = g[j][t] - copyCost(dist(t, ctx.a))
            if (bestJ < 0 || nt > bestNet + EPS || nt > bestNet - EPS && fill(t) < fill(bestT)) {
                bestJ = j; bestT = t; bestNet = nt
            }
        }
        if (bestJ >= 0) {
            stats.repairCopies++
            stats.copyDistance += dist(bestT, ctx.a)
            relocate(st.id, ctx.a[bestJ], bestT)
            return
        }
        val full = ArrayList<Option>()
        for (j in ctx.a.indices) for (t in 0 until serverCount) {
            if (alive[t] && racks!![t] != rack && g[j][t] != Double.NEGATIVE_INFINITY) full += Option(st.id, ctx.a[j], t, g[j][t], dist(t, ctx.a))
        }
        for (o in full.sortedByDescending { net(it) }) {
            val swap = bestSwap(o) ?: continue
            relocate(swap.file, swap.from, swap.to)
            relocate(swap.swapFile, swap.to, swap.from)
            stats.repairCopies += 2
            stats.copyDistance += swap.dist
            return
        }
    }

    private fun place(f: Int, t: Int) {
        val st = files.getValue(f)
        setServers(st, st.servers + t, st.slots + 0)
        onServer[t].add(st, st.servers.size - 1)
        onChange?.invoke(Change.Copy(f, t))
    }

    private fun relocate(f: Int, from: Int, to: Int) {
        val st = files.getValue(f)
        val i = st.servers.indexOf(from)
        onServer[from].remove(st, i)
        setServers(st, st.servers.copyOf().also { it[i] = to }, st.slots.copyOf())
        onServer[to].add(st, i)
        onChange?.invoke(Change.Copy(f, to))
        onChange?.invoke(Change.Delete(f, from))
    }

    private fun removeCopy(st: FileState, s: Int) {
        val f = st.id
        val i = st.servers.indexOf(s)
        onServer[s].remove(st, i)
        setServers(st, st.servers.without(i), st.slots.without(i))
        onChange?.invoke(Change.Delete(f, s))
    }

    /** A copy of this array without index [i]. */
    private fun IntArray.without(i: Int): IntArray = copyOfRange(0, i) + copyOfRange(i + 1, size)

    private fun sample(set: ServerFiles, m: Int): List<Int> {
        if (set.size <= m) return set.ids()
        val picked = LinkedHashSet<Int>()
        while (picked.size < m) picked.add(set.random(rng).id)
        return picked.toList()
    }

    /**
     * A file's neighbours: neighbour id -> shared edge. Open addressing with linear probing over
     * an IntArray of keys, so nothing is boxed; deletion shifts later entries back instead of
     * leaving tombstones. Iteration goes by slot, so its order depends on the keys and history.
     */
    private class EdgeMap {
        private var keys = IntArray(4)
        private var edges = arrayOfNulls<Edge>(4)
        var size = 0
            private set

        /** Number of slots, for iterating with [at]. */
        val slots: Int get() = edges.size

        fun at(i: Int): Edge? = edges[i]

        fun isEmpty(): Boolean = size == 0

        private fun home(k: Int): Int {
            val h = k * -0x61c88647                   // Fibonacci hashing
            return (h xor (h ushr 16)) and (edges.size - 1)
        }

        operator fun get(k: Int): Edge? {
            val mask = edges.size - 1
            var i = home(k)
            while (true) {
                val e = edges[i] ?: return null
                if (keys[i] == k) return e
                i = (i + 1) and mask
            }
        }

        operator fun set(k: Int, e: Edge) {
            if (4 * (size + 1) > 3 * edges.size) grow()
            val mask = edges.size - 1
            var i = home(k)
            while (true) {
                if (edges[i] == null) {
                    keys[i] = k
                    edges[i] = e
                    size++
                    return
                }
                if (keys[i] == k) {
                    edges[i] = e
                    return
                }
                i = (i + 1) and mask
            }
        }

        fun remove(k: Int): Edge? {
            val mask = edges.size - 1
            var i = home(k)
            while (true) {
                val e = edges[i] ?: return null
                if (keys[i] == k) break
                i = (i + 1) and mask
            }
            val removed = edges[i]
            // Shift back every later entry of the run that may live in the gap.
            var gap = i
            var j = i
            while (true) {
                j = (j + 1) and mask
                val e = edges[j] ?: break
                if ((j - home(keys[j])) and mask >= (j - gap) and mask) {
                    keys[gap] = keys[j]
                    edges[gap] = e
                    gap = j
                }
            }
            edges[gap] = null
            size--
            return removed
        }

        inline fun forEach(action: (Edge) -> Unit) {
            for (i in 0 until slots) action(at(i) ?: continue)
        }

        private fun grow() {
            val oldKeys = keys
            val oldEdges = edges
            keys = IntArray(2 * oldKeys.size)
            edges = arrayOfNulls(2 * oldEdges.size)
            size = 0
            for (i in oldEdges.indices) oldEdges[i]?.let { set(oldKeys[i], it) }
        }
    }

    /**
     * The files with a copy on one server, with O(1) add, remove and uniform random pick. Each
     * file remembers its index here in FileState.slots, so nothing is boxed or hashed. Removing
     * moves the last file into the gap.
     */
    private class ServerFiles(val server: Int) {
        private var items = arrayOfNulls<FileState>(16)
        var size = 0
            private set

        /** Adds st, whose replica number [i] is on this server. */
        fun add(st: FileState, i: Int) {
            if (size == items.size) items = items.copyOf(2 * size)
            items[size] = st
            st.slots[i] = size
            size++
        }

        /** Removes st, whose replica number [i] is on this server. */
        fun remove(st: FileState, i: Int) {
            val p = st.slots[i]
            size--
            val last = items[size]!!
            items[size] = null
            if (p < size) {
                items[p] = last
                last.slots[last.servers.indexOf(server)] = p
            }
        }

        operator fun contains(st: FileState): Boolean {
            val i = st.servers.indexOf(server)
            return i >= 0 && st.slots[i] < size && items[st.slots[i]] === st
        }

        fun random(rng: Random): FileState = items[rng.nextInt(size)]!!

        fun ids(): List<Int> = List(size) { items[it]!!.id }
    }

    private companion object {
        val EMPTY = IntArray(0)
        const val EPS = 1e-9
        const val CAP_MEMO = 4            // recent cap arrays kept by capacities()
        const val HEAT_REBASE = 300.0     // rebase forward-decay units beyond exp(300)
        const val PRUNE = 1e-4            // forget pairs whose decayed weight fell below this
        const val SWEEP_EVERY = 1L shl 20 // traversals in which the sweep visits every file once
        const val SWAP_SAMPLE = 16        // files examined on a full server when looking for a swap
        const val EVICT_SAMPLE = 16       // files examined when a server must give up one replica
    }
}
