import kotlin.random.Random

/**
 * A cache eviction policy over file ids 0 until n. The driver calls [touched] for every access, in
 * order, then [evict] while it holds more files than it may; [evict] forgets the file it returns.
 * The file returned may be one just added (for TinyLFU: an admission reject).
 */
interface Eviction {
    val name: String
    /** Files the policy holds (resident ones; ghosts not counted). */
    val size: Int
    fun touched(f: Int, added: Boolean)
    fun evict(): Int
}

/** Doubly linked lists over ids 0 until n sharing one pool; an id is in at most one list. Front = oldest. */
class ListPool(n: Int) {
    private val prev = IntArray(n)
    private val next = IntArray(n)

    inner class Lst {
        var head = -1; private set
        var size = 0; private set
        private var tail = -1

        fun pushBack(f: Int) {
            prev[f] = tail
            next[f] = -1
            if (tail >= 0) next[tail] = f else head = f
            tail = f
            size++
        }

        fun remove(f: Int) {
            val p = prev[f]
            val q = next[f]
            if (p >= 0) next[p] = q else head = q
            if (q >= 0) prev[q] = p else tail = p
            size--
        }

        fun popFront(): Int = head.also { check(it >= 0) { "pop from an empty list" }; remove(it) }

        fun moveToBack(f: Int) {
            if (f == tail) return
            remove(f)
            pushBack(f)
        }
    }
}

/** Min-heap of ids 0 until n with long keys that can change. */
class IndexedHeap(n: Int) {
    private val heap = IntArray(n)
    private val pos = IntArray(n) { -1 }
    private val key = LongArray(n)
    var size = 0; private set

    operator fun contains(f: Int) = pos[f] >= 0

    fun set(f: Int, k: Long) {
        if (pos[f] < 0) {
            heap[size] = f
            pos[f] = size++
            key[f] = k
            up(pos[f])
        } else {
            val old = key[f]
            key[f] = k
            if (k < old) up(pos[f]) else down(pos[f])
        }
    }

    fun pop(): Int {
        val f = heap[0]
        pos[f] = -1
        if (--size > 0) {
            heap[0] = heap[size]
            pos[heap[0]] = 0
            down(0)
        }
        return f
    }

    private fun up(i0: Int) {
        var i = i0
        val f = heap[i]
        while (i > 0) {
            val p = (i - 1) / 2
            if (key[heap[p]] <= key[f]) break
            heap[i] = heap[p]; pos[heap[i]] = i
            i = p
        }
        heap[i] = f; pos[f] = i
    }

    private fun down(i0: Int) {
        var i = i0
        val f = heap[i]
        while (true) {
            var c = 2 * i + 1
            if (c >= size) break
            if (c + 1 < size && key[heap[c + 1]] < key[heap[c]]) c++
            if (key[f] <= key[heap[c]]) break
            heap[i] = heap[c]; pos[heap[i]] = i
            i = c
        }
        heap[i] = f; pos[f] = i
    }
}

class Lru(n: Int) : Eviction {
    override val name = "LRU"
    private val list = ListPool(n).Lst()
    override val size get() = list.size
    override fun touched(f: Int, added: Boolean) = if (added) list.pushBack(f) else list.moveToBack(f)
    override fun evict() = list.popFront()
}

class Fifo(n: Int) : Eviction {
    override val name = "FIFO"
    private val list = ListPool(n).Lst()
    override val size get() = list.size
    override fun touched(f: Int, added: Boolean) { if (added) list.pushBack(f) }
    override fun evict() = list.popFront()
}

class RandomEviction(n: Int, seed: Int) : Eviction {
    override val name = "Random"
    private val rnd = Random(seed)
    private val items = IntArray(n)
    private val pos = IntArray(n)
    override var size = 0; private set

    override fun touched(f: Int, added: Boolean) {
        if (!added) return
        items[size] = f
        pos[f] = size++
    }

    override fun evict(): Int {
        val i = rnd.nextInt(size)
        val f = items[i]
        items[i] = items[--size]
        pos[items[i]] = i
        return f
    }
}

/** Second chance: the hand skips (and clears) files referenced since it last passed them. */
class Clock(n: Int) : Eviction {
    override val name = "CLOCK"
    private val ring = ListPool(n).Lst()
    private val referenced = BooleanArray(n)
    override val size get() = ring.size

    override fun touched(f: Int, added: Boolean) {
        if (added) ring.pushBack(f)
        referenced[f] = !added
    }

    override fun evict(): Int {
        while (true) {
            val f = ring.popFront()
            if (!referenced[f]) return f
            referenced[f] = false
            ring.pushBack(f)
        }
    }
}

/** Least frequently used while resident (counts restart after an eviction); ties go to the least recent. */
class Lfu(n: Int) : Eviction {
    override val name = "LFU"
    private val heap = IndexedHeap(n)
    private val count = IntArray(n)
    private var clock = 0L
    override val size get() = heap.size

    override fun touched(f: Int, added: Boolean) {
        count[f] = if (added) 1 else count[f] + 1
        heap.set(f, (count[f].toLong() shl 40) or clock++)
    }

    override fun evict() = heap.pop()
}

/**
 * Segmented LRU: new files enter probation; a hit there promotes to the protected segment (at most
 * [protectedShare] of [capacity]), whose overflow drops back to probation. Evicts from probation first.
 */
class Slru(n: Int, capacity: Int, protectedShare: Double = 0.8) : Eviction {
    override val name = "SLRU"
    private val pool = ListPool(n)
    private val probation = pool.Lst()
    private val protected = pool.Lst()
    private val inProtected = BooleanArray(n)
    private val protectedCap = (capacity * protectedShare).toInt().coerceAtLeast(1)
    override val size get() = probation.size + protected.size

    override fun touched(f: Int, added: Boolean) {
        when {
            added -> probation.pushBack(f)
            inProtected[f] -> protected.moveToBack(f)
            else -> {
                probation.remove(f)
                protected.pushBack(f)
                inProtected[f] = true
                if (protected.size > protectedCap) {
                    val d = protected.popFront()
                    inProtected[d] = false
                    probation.pushBack(d)
                }
            }
        }
    }

    override fun evict(): Int {
        if (probation.size > 0) return probation.popFront()
        return protected.popFront().also { inProtected[it] = false }
    }
}

/**
 * ARC (Megiddo & Modha 2003): T1 holds files seen once recently, T2 files seen at least twice; the
 * ghost lists B1/B2 remember what was evicted from each, and ghost hits move the target size p of T1.
 */
class Arc(n: Int, private val c: Int) : Eviction {
    override val name = "ARC"
    private val pool = ListPool(n)
    private val t1 = pool.Lst()
    private val t2 = pool.Lst()
    private val b1 = pool.Lst()
    private val b2 = pool.Lst()
    private val where = ByteArray(n)        // 0 none, 1 T1, 2 T2, 3 B1, 4 B2
    private var p = 0.0
    private var lastInB2 = false
    override val size get() = t1.size + t2.size
    val target get() = p

    private fun move(f: Int, to: ListPool.Lst, code: Int) {
        to.pushBack(f)
        where[f] = code.toByte()
    }

    override fun touched(f: Int, added: Boolean) {
        lastInB2 = false
        val was = where[f].toInt()
        check(added == (was == 0 || was >= 3)) { "ARC lost track of $f" }
        when (was) {
            1 -> { t1.remove(f); move(f, t2, 2) }
            2 -> t2.moveToBack(f)
            3 -> {
                p = minOf(c.toDouble(), p + maxOf(b2.size.toDouble() / b1.size, 1.0))
                b1.remove(f); move(f, t2, 2)
            }
            4 -> {
                p = maxOf(0.0, p - maxOf(b1.size.toDouble() / b2.size, 1.0))
                b2.remove(f); move(f, t2, 2)
                lastInB2 = true
            }
            else -> {
                if (t1.size + b1.size >= c && b1.size > 0) where[b1.popFront()] = 0
                else if (t1.size + t2.size + b1.size + b2.size >= 2 * c && b2.size > 0) where[b2.popFront()] = 0
                move(f, t1, 1)
            }
        }
    }

    override fun evict(): Int {
        val f = if (t1.size > 0 && (t1.size > p || lastInB2 && t1.size.toDouble() == p) || t2.size == 0) {
            t1.popFront().also { move(it, b1, 3) }
        } else {
            t2.popFront().also { move(it, b2, 4) }
        }
        while (b1.size > 0 && t1.size + b1.size > c) where[b1.popFront()] = 0
        while (b2.size > 0 && t1.size + t2.size + b1.size + b2.size > 2 * c) where[b2.popFront()] = 0
        return f
    }
}

/**
 * W-TinyLFU (Einziger, Friedman & Manes 2017): a small LRU window in front of an SLRU main area.
 * A file leaving the window enters the main area only if a count-min sketch of recent access
 * frequency rates it above the main area's next victim; otherwise it is the one evicted.
 */
class TinyLfu(n: Int, capacity: Int, windowShare: Double = 0.01) : Eviction {
    override val name = "W-TinyLFU"
    private val pool = ListPool(n)
    private val window = pool.Lst()
    private val probation = pool.Lst()
    private val protected = pool.Lst()
    private val where = ByteArray(n)       // 0 none, 1 window, 2 probation, 3 protected
    private val windowCap = (capacity * windowShare).toInt().coerceAtLeast(1)
    private val mainCap = capacity - windowCap
    private val protectedCap = (mainCap * 0.8).toInt().coerceAtLeast(1)
    private val sketch = Sketch(capacity)
    override val size get() = window.size + probation.size + protected.size

    private fun move(f: Int, to: ListPool.Lst, code: Int) {
        to.pushBack(f)
        where[f] = code.toByte()
    }

    override fun touched(f: Int, added: Boolean) {
        sketch.add(f)
        when (where[f].toInt()) {
            0 -> move(f, window, 1)
            1 -> window.moveToBack(f)
            2 -> {
                probation.remove(f); move(f, protected, 3)
                if (protected.size > protectedCap) move(protected.popFront(), probation, 2)
            }
            3 -> protected.moveToBack(f)
        }
    }

    override fun evict(): Int {
        while (true) {
            if (window.size > windowCap) {
                val cand = window.popFront()
                if (probation.size + protected.size < mainCap) { move(cand, probation, 2); continue }
                val victimList = if (probation.size > 0) probation else protected
                val victim = victimList.head
                if (sketch.frequency(cand) > sketch.frequency(victim)) {
                    victimList.remove(victim)
                    move(cand, probation, 2)
                    where[victim] = 0
                    return victim
                }
                where[cand] = 0
                return cand
            }
            val f = if (probation.size > 0) probation.popFront() else protected.popFront()
            where[f] = 0
            return f
        }
    }

    /** Count-min sketch, 4 rows of 4-bit counters (kept in bytes); all counters halve every 10 * capacity adds. */
    private class Sketch(capacity: Int) {
        private val width = Integer.highestOneBit(maxOf(16, capacity) * 2 - 1)
        private val rows = Array(4) { ByteArray(width) }
        private val resetAt = 10L * capacity
        private var adds = 0L

        private fun index(f: Int, row: Int): Int {
            var h = (f.toLong() + SEEDS[row]) * -0x61c8864680b583ebL
            h = h xor (h ushr 29)
            return (h.toInt()) and (width - 1)
        }

        fun add(f: Int) {
            for (r in 0 until 4) {
                val i = index(f, r)
                if (rows[r][i] < 15) rows[r][i]++
            }
            if (++adds >= resetAt) {
                adds /= 2
                for (row in rows) for (i in row.indices) row[i] = (row[i].toInt() shr 1).toByte()
            }
        }

        fun frequency(f: Int): Int {
            var m = 15
            for (r in 0 until 4) m = minOf(m, rows[r][index(f, r)].toInt())
            return m
        }

        companion object {
            private val SEEDS = longArrayOf(0x2545F491L, 0x7A1C3B5DL, 0x5BD1E995L, 0x1B873593L)
        }
    }
}

/**
 * Belady's OPT: evicts the file whose next access is furthest away (never accessed again first).
 * Offline: [next] holds, for the i-th access of the trace, the position of the next access to the
 * same file (Int.MAX_VALUE if none); the driver must call [touched] in trace order.
 */
class Belady(n: Int, private val next: IntArray) : Eviction {
    override val name = "Belady"
    private val heap = IndexedHeap(n)
    private var i = 0
    override val size get() = heap.size

    override fun touched(f: Int, added: Boolean) = heap.set(f, -next[i++].toLong())
    override fun evict() = heap.pop()

    companion object {
        /** next[] for a trace of file ids over 0 until n. */
        fun nextUse(trace: IntArray, n: Int): IntArray {
            val last = IntArray(n) { Int.MAX_VALUE }
            val next = IntArray(trace.size)
            for (i in trace.indices.reversed()) {
                next[i] = last[trace[i]]
                last[trace[i]] = i
            }
            return next
        }
    }
}
