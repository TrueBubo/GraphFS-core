import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import kotlin.random.Random

/**
 * Wikipedia articles as files, reader clicks between them as traversals, at most [maxFiles]
 * articles in the placer at once: the placer is a cache of articles, and this compares eviction
 * policies for it.
 *
 * Usage: WikiSimulation <dir with links.bin> [traversals] [maxFiles] [policies] [csv]. links.bin comes
 * from WikiPrep; policies is a comma list or - for all of them; csv collects one row per policy. The clickstream has monthly totals per (from, to) pair and no order, so traversals
 * are drawn independently, each pair with probability proportional to its clicks.
 *
 * The policies are those of [compareCaches]; they run in parallel threads, each drawing the same
 * traversals from Random(17).
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: WikiSimulation <dir with links.bin> [traversals] [maxFiles] [policies] [csv]" }
    val traversals = args.getOrNull(1)?.toLong() ?: 50_000_000L
    val maxFiles = args.getOrNull(2)?.toInt() ?: 100_000
    val names = args.getOrNull(3)?.takeIf { it != "-" }?.split(',') ?: POLICIES
    val csv = args.getOrNull(4)?.let(::File)
    val clicks = Clicks.load(File(args[0], "links.bin"))
    println("%,d link pairs, %,d clicks, %,d articles".format(clicks.size, clicks.total, clicks.articles))

    compareCaches("Wikipedia", clicks.articles, traversals, maxFiles, 1_000_000.0, names, csv) {
        val rnd = Random(17)
        ({ clicks.sample(rnd) })
    }
}

/** Click counts per (from, to) article pair, sampled with Vose's alias method in O(1). */
internal class Clicks(val from: IntArray, val to: IntArray, val total: Long, val articles: Int, counts: IntArray) {
    val size: Int get() = from.size
    private val prob = DoubleArray(size)
    private val alias = IntArray(size)

    init {
        val scaled = DoubleArray(size) { counts[it].toDouble() * size / total }
        val small = IntArray(size)
        val large = IntArray(size)
        var ns = 0
        var nl = 0
        for (i in 0 until size) if (scaled[i] < 1.0) small[ns++] = i else large[nl++] = i
        while (ns > 0 && nl > 0) {
            val s = small[--ns]
            val l = large[--nl]
            prob[s] = scaled[s]
            alias[s] = l
            scaled[l] = scaled[l] + scaled[s] - 1.0
            if (scaled[l] < 1.0) small[ns++] = l else large[nl++] = l
        }
        while (nl > 0) prob[large[--nl]] = 1.0
        while (ns > 0) prob[small[--ns]] = 1.0          // rounding leftovers
    }

    fun sample(rnd: Random): Pair<Int, Int> {
        val i = rnd.nextInt(size)
        val j = if (rnd.nextDouble() < prob[i]) i else alias[i]
        return from[j] to to[j]
    }

    companion object {
        fun load(file: File): Clicks = DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 20)).use { inp ->
            val m = inp.readInt()
            val from = IntArray(m)
            val to = IntArray(m)
            val n = IntArray(m)
            var total = 0L
            var articles = 0
            for (i in 0 until m) {
                from[i] = inp.readInt()
                to[i] = inp.readInt()
                n[i] = inp.readInt()
                total += n[i]
                articles = maxOf(articles, from[i] + 1, to[i] + 1)
            }
            Clicks(from, to, total, articles, n)
        }
    }
}
