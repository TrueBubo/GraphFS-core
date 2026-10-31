import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPInputStream

/**
 * Converts a Wikipedia clickstream dump (https://dumps.wikimedia.org/other/clickstream/) into the
 * binary form [WikiClicks] reads. Usage: WikiPrep <clickstream.tsv.gz> <output dir>.
 *
 * Each line is `prev \t curr \t type \t n`: readers went from article prev to article curr n
 * times in the month. Only type "link" is kept (both sides are articles; "external" and "other"
 * come from search engines, empty referrers and the like). Writes:
 *  - links.bin:  int count, then count records of (int prev id, int curr id, int n)
 *  - titles.txt: one title per line; line i is the title of id i
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: WikiPrep <clickstream.tsv.gz> <output dir>" }
    val dir = File(args[1]).apply { mkdirs() }
    val ids = HashMap<String, Int>(8_000_000)
    val titles = ArrayList<String>(8_000_000)
    fun id(title: String): Int = ids.getOrPut(title) { titles.add(title); titles.size - 1 }

    val tmp = File(dir, "links.bin.tmp")
    var count = 0
    var clicks = 0L
    DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 20)).use { out ->
        GZIPInputStream(FileInputStream(args[0]), 1 shl 20).bufferedReader().useLines { lines ->
            for (line in lines) {
                val t1 = line.indexOf('\t')
                val t2 = line.indexOf('\t', t1 + 1)
                val t3 = line.indexOf('\t', t2 + 1)
                if (t3 < 0 || !line.startsWith("link", t2 + 1) || t3 - t2 != 5) continue
                val prev = line.substring(0, t1)
                val curr = line.substring(t1 + 1, t2)
                if (prev == curr) continue
                val n = line.substring(t3 + 1).trim().toInt()
                out.writeInt(id(prev))
                out.writeInt(id(curr))
                out.writeInt(n)
                count++
                clicks += n
            }
        }
    }
    // Prepend the record count.
    DataOutputStream(BufferedOutputStream(File(dir, "links.bin").outputStream(), 1 shl 20)).use { out ->
        out.writeInt(count)
        tmp.inputStream().use { it.copyTo(out, 1 shl 20) }
    }
    tmp.delete()
    File(dir, "titles.txt").bufferedWriter().use { w -> for (t in titles) w.appendLine(t) }
    println("%,d link pairs, %,d clicks, %,d articles".format(count, clicks, titles.size))
}
