import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Twitter users as files, users tweeting at each other as traversals: the Higgs Twitter dataset
 * (https://snap.stanford.edu/data/higgs-twitter.html), every retweet, reply and mention among
 * users tweeting about the Higgs boson announcement, 1-7 July 2012. A serving system that keeps
 * each user's data on servers wants users who interact on the same server.
 *
 * Usage: TwitterSimulation <higgs-activity_time.txt.gz> [maxFiles] [halfLife] [policies] [csv]; policies
 * is a comma list or - for all of them; csv collects one row per policy (CacheSimulation).
 * Each line is `userA userB timestamp RT|MT|RE` (A retweeted, mentioned or replied to B), in time
 * order; the events are replayed in that order, so unlike the Wikipedia run they are not
 * independent: bursts and drift are real. A user interacting with themselves is skipped.
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: TwitterSimulation <higgs-activity_time.txt.gz> [maxFiles] [halfLife] [policies] [csv]" }
    val maxFiles = args.getOrNull(1)?.toInt() ?: 10_000
    val halfLife = args.getOrNull(2)?.toDouble() ?: 20_000.0
    val names = args.getOrNull(3)?.takeIf { it != "-" }?.split(',') ?: POLICIES
    val csv = args.getOrNull(4)?.let(::File)

    val higgs = Higgs.load(File(args[0]))
    compareCaches("Twitter Higgs", higgs.users, higgs.size.toLong(), maxFiles, halfLife, names, csv, higgs::stream)
}

/** The Higgs interactions in time order, self-interactions skipped; user ids as in the file. */
internal class Higgs(private val from: IntArray, private val to: IntArray) {
    val size: Int get() = from.size
    val users: Int = maxOf(from.max(), to.max()) + 1

    /** A fresh pass over the interactions. */
    fun stream(): () -> Pair<Int, Int> {
        var i = 0
        return { (from[i] to to[i]).also { i++ } }
    }

    companion object {
        fun load(file: File): Higgs {
            val from = ArrayList<Int>()
            val to = ArrayList<Int>()
            val kinds = HashMap<String, Int>()
            var self = 0
            GZIPInputStream(FileInputStream(file)).bufferedReader().useLines { lines ->
                for (line in lines) {
                    val f = line.split(' ')
                    val a = f[0].toInt()
                    val b = f[1].toInt()
                    if (a == b) { self++; continue }
                    from += a
                    to += b
                    kinds.merge(f[3], 1, Int::plus)
                }
            }
            println("%,d interactions (%s), %,d self-interactions skipped, %,d users".format(
                from.size, kinds.entries.joinToString { "%,d %s".format(it.value, it.key) }, self, (from + to).toSet().size))
            return Higgs(from.toIntArray(), to.toIntArray())
        }
    }
}
