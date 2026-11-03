import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min

/** Called with a neighbour of a file and the pair's decayed weight. */
fun interface PairAction {
    fun accept(g: Int, weight: Double)
}

/**
 * What one server of a [DistributedPlacer] remembers of the pairs traversed through its files.
 * Weights halve every half-life, given as lambda = ln 2 / half-life. There is one [PairCounts] per
 * server and one [FilePairs] per replica it holds. [ExactCounts] keeps every pair; [SketchCounts]
 * keeps a fixed amount per server and per replica, and may overstate or leave out pairs.
 */
interface PairCounts {
    /** The pairs of a replica of [file] just placed on this server: none yet. */
    fun open(file: Int): FilePairs

    /** The pairs of a replica of [file] that moves here, with their weights at time [now], from [moved]. */
    fun arrive(file: Int, moved: FilePairs, now: Long): FilePairs

    /** Bytes kept for the whole server, apart from [FilePairs.bytes]. */
    val bytes: Long
}

/** One replica's pairs. */
interface FilePairs {
    /** Records one traversal to [g] at time [now]. */
    fun add(g: Int, now: Long)

    /** Forgets the pair with [g]. */
    fun remove(g: Int)

    /** Every remembered pair, faded ones included. */
    fun forEach(now: Long, action: PairAction)

    /** Forgets the pairs that have faded (see [PRUNE]), and gives the others to [action]. */
    fun forEachLive(now: Long, action: PairAction)

    /** Pairs remembered. */
    val count: Int

    /** Bytes kept for this replica. */
    val bytes: Long
}

/** Pairs whose decayed weight fell below this are forgotten, as in [ReplicaPlacer]. */
const val PRUNE = 1e-4

/** Every pair, with its weight and the time of that weight. Memory grows with the pairs in use. */
class ExactCounts(private val lambda: Double) : PairCounts {
    private class Edge(var weight: Double, var time: Long)

    private inner class Pairs : FilePairs {
        val edges = HashMap<Int, Edge>()

        override fun add(g: Int, now: Long) {
            val e = edges.getOrPut(g) { Edge(0.0, now) }
            e.weight = weightOf(e, now) + 1.0
            e.time = now
        }

        override fun remove(g: Int) {
            edges.remove(g)
        }

        override fun forEach(now: Long, action: PairAction) {
            for ((g, e) in edges) action.accept(g, weightOf(e, now))
        }

        override fun forEachLive(now: Long, action: PairAction) {
            val it = edges.entries.iterator()
            while (it.hasNext()) {
                val (g, e) = it.next()
                val w = weightOf(e, now)
                if (w < PRUNE) it.remove() else action.accept(g, w)
            }
        }

        override val count: Int get() = edges.size

        override val bytes: Long get() = PAIRS_OBJECT + hashMapBytes(edges.size) + edges.size * ENTRY
    }

    private fun weightOf(e: Edge, now: Long): Double = e.weight * exp(-lambda * (now - e.time))

    override fun open(file: Int): FilePairs = Pairs()

    /** The map itself travels with the copy. */
    override fun arrive(file: Int, moved: FilePairs, now: Long): FilePairs = moved

    override val bytes: Long get() = 0L

    private companion object {
        const val PAIRS_OBJECT = 16L
        const val ENTRY = HASH_NODE + BOXED_INT + 32L      // the map's node, its key, the Edge
    }
}

/**
 * Pair weights in one count-min sketch per server, so memory does not grow with the pairs in use.
 * A sketch answers "how heavy is this pair?" but cannot list the pairs of a file, which an
 * evaluation needs, so each replica also keeps the ids of up to [neighbours] heavy neighbours: a
 * new neighbour takes the place of the lightest one if the sketch says it weighs more.
 *
 * The counters are 32-bit floats in forward-decay units: a traversal at t adds exp(lambda * (t -
 * base)), and a weight today is the counter over exp(lambda * (now - base)), so decay touches no
 * counter ([ReplicaPlacer] keeps its heat the same way). Updates are conservative: a counter rises
 * only as far as the new estimate, which overstates a pair far less than adding to all rows.
 * A pair's weight is never understated while the replica stays, but is overstated by collisions.
 *
 * A sketch cannot forget one pair. When a replica moves or leaves, its pairs stay in the counters
 * of the old server until they fade; they count again only if the file comes back there.
 */
class SketchCounts(private val lambda: Double, cellsPerRow: Int, private val neighbours: Int = 16) : PairCounts {
    private val rowBits = 32 - (maxOf(cellsPerRow, 2) - 1).countLeadingZeroBits()   // rounded up to a power of 2
    private val mask = (1 shl rowBits) - 1
    private val cells = FloatArray(DEPTH shl rowBits)
    private var base = 0L
    private var scaledAt = 0L
    private var scale = 1.0

    init {
        require(neighbours >= 1) { "neighbours must be >= 1" }
    }

    private inner class Pairs(val file: Int) : FilePairs {
        var ids = EMPTY
        override var count = 0
            private set

        private fun index(g: Int): Int {
            for (i in 0 until count) if (ids[i] == g) return i
            return -1
        }

        private fun append(g: Int) {
            if (count == ids.size) ids = ids.copyOf(min(maxOf(2, 2 * ids.size), neighbours))
            ids[count++] = g
        }

        private fun lightest(now: Long): Int {
            var at = 0
            var least = Double.POSITIVE_INFINITY
            for (i in 0 until count) {
                val w = estimate(hash(file, ids[i]), now)
                if (w < least) {
                    least = w
                    at = i
                }
            }
            return at
        }

        override fun add(g: Int, now: Long) {
            val h = hash(file, g)
            val w = raise(h, 1.0, now)
            if (index(g) >= 0) return
            if (count < neighbours) return append(g)
            val at = lightest(now)
            if (estimate(hash(file, ids[at]), now) < w) ids[at] = g
        }

        fun adopt(g: Int, weight: Double, now: Long) {
            raise(hash(file, g), weight, now)
            append(g)
        }

        private fun drop(i: Int) {
            ids[i] = ids[--count]
        }

        override fun remove(g: Int) {
            val i = index(g)
            if (i >= 0) drop(i)
        }

        override fun forEach(now: Long, action: PairAction) {
            for (i in 0 until count) action.accept(ids[i], estimate(hash(file, ids[i]), now))
        }

        override fun forEachLive(now: Long, action: PairAction) {
            var i = count
            while (--i >= 0) {
                val g = ids[i]
                val w = estimate(hash(file, g), now)
                if (w < PRUNE) drop(i) else action.accept(g, w)
            }
        }

        override val bytes: Long get() = PAIRS_OBJECT + if (ids.isEmpty()) 0L else arrayBytes(ids.size, 4)
    }

    override fun open(file: Int): FilePairs = Pairs(file)

    /** The ids travel with the copy, and the weights are added to this server's counters. */
    override fun arrive(file: Int, moved: FilePairs, now: Long): FilePairs {
        val here = Pairs(file)
        moved.forEach(now) { g, w -> here.adopt(g, w, now) }
        return here
    }

    override val bytes: Long get() = OBJECT + arrayBytes(cells.size, 4)

    /** Forward-decay units per unit of weight at [now]; rebases first if they would overflow a float. */
    private fun scaleAt(now: Long): Double {
        if (now == scaledAt) return scale
        val age = lambda * (now - base)
        if (age > REBASE) {
            val shrink = exp(-age).toFloat()
            for (i in cells.indices) cells[i] *= shrink
            base = now
        }
        scaledAt = now
        scale = exp(lambda * (now - base))
        return scale
    }

    private fun cell(row: Int, h: Long): Int =
        (row shl rowBits) + (h.toInt() + row * (h ushr 32).toInt().or(1) and mask)

    private fun minimum(h: Long): Float {
        var least = Float.POSITIVE_INFINITY
        for (r in 0 until DEPTH) least = min(least, cells[cell(r, h)])
        return least
    }

    private fun estimate(h: Long, now: Long): Double {
        val s = scaleAt(now)
        return minimum(h) / s
    }

    /** Adds [weight] to the pair by conservative update; returns its new estimate. */
    private fun raise(h: Long, weight: Double, now: Long): Double {
        val s = scaleAt(now)
        val target = minimum(h) + (weight * s).toFloat()
        for (r in 0 until DEPTH) {
            val c = cell(r, h)
            if (cells[c] < target) cells[c] = target
        }
        return target / s
    }

    private companion object {
        const val DEPTH = 4
        val REBASE = 40 * ln(2.0)                          // units up to 2^40: a float holds them with room to spare
        val EMPTY = IntArray(0)
        const val PAIRS_OBJECT = 32L
        const val OBJECT = 72L

        /** A 64-bit mix of the directed pair; two hashes for the rows, by double hashing. */
        fun hash(f: Int, g: Int): Long {
            var x = (f.toLong() shl 32) xor (g.toLong() and 0xffffffffL)
            x = (x xor (x ushr 30)) * -0x40a7b892e31b1a47L
            x = (x xor (x ushr 27)) * -0x6b2fb644ecceee15L
            return x xor (x ushr 31)
        }
    }
}

// Object sizes on a 64-bit JVM with compressed references, for [DistributedPlacer.memory].
const val HASH_NODE = 32L
const val BOXED_INT = 16L

/** An array of [length] elements of [size] bytes, with its header and padding. */
fun arrayBytes(length: Int, size: Int): Long = (16L + length.toLong() * size + 7) / 8 * 8

/** The table of a HashMap holding [size] entries at its default load factor; 0 until the first put. */
fun hashMapBytes(size: Int): Long {
    if (size == 0) return 48L
    var table = 16
    while (table * 3L / 4 < size) table *= 2
    return 48L + arrayBytes(table, 4)
}