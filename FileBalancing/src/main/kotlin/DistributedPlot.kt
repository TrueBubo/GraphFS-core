import org.jetbrains.kotlinx.dataframe.api.column
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.kandy.dsl.categorical
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.letsplot.export.save
import org.jetbrains.kotlinx.kandy.letsplot.feature.CoordinatesTransformation
import org.jetbrains.kotlinx.kandy.letsplot.feature.coordinatesTransformation
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.layers.bars
import org.jetbrains.kotlinx.kandy.letsplot.layers.line
import org.jetbrains.kotlinx.kandy.letsplot.layers.points
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.ScalesSharing
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.facetWrap
import org.jetbrains.kotlinx.kandy.letsplot.scales.guide.LegendType
import org.jetbrains.kotlinx.kandy.letsplot.settings.LineType
import org.jetbrains.kotlinx.kandy.util.color.Color
import java.io.File

/**
 * Charts of the central-vs-distributed runs: the csv rows DistributedSimulation appends, one per
 * variant and window. Usage: DistributedPlot <output dir> <distributed.csv> [memory csv] [sweep csv].
 *  - per dataset and eviction policy, remote% of each variant's adaptive placer over the run, in
 *    small multiples: one panel per variant, the other variants faint grey, the panel's own static
 *    placer dark dashed;
 *  - two summaries per dataset and policy: each variant's total cost (bar) beside its static
 *    placer's (dark point), and the cost saved against that static placer;
 *  - memory.png: the peak memory of each distributed variant, for pairs and for locations;
 *  - with [memory csv], the same rows for several cache sizes: memory-scaling.png, memory and
 *    cost saved of exact pairs and the sketch as the number of files grows;
 *  - with [sweep csv], the same rows for several sketch sizes: memory-tradeoff.png, cost saved
 *    against memory.
 */
fun main(args: Array<String>) {
    require(args.size in 2..4) { "usage: DistributedPlot <output dir> <distributed.csv> [memory csv] [sweep csv]" }
    val dir = File(args[0]).apply { mkdirs() }
    val rows = readRows(args[1])
    val alpha = 8.0

    val summary = ArrayList<Triple<String, String, Double>>()      // panel, variant, saved%
    val costs = ArrayList<List<Any>>()                              // panel, variant, cost, static cost (millions)
    for ((key, run) in rows.groupBy { it.getValue("dataset") to it.getValue("policy") }) {
        val (dataset, policy) = key
        val variants = run.map { it.getValue("variant") }.distinct()
        val byVariant = run.groupBy { it.getValue("variant") }

        fun frame(panelOf: List<Pair<String, String>>, value: (Map<String, String>) -> Double) =
            panelOf.flatMap { (panel, series) ->
                byVariant.getValue(series).map { listOf(it.getValue("traversals").toLong() / 1e6, value(it), panel, series) }
            }.let { r ->
                dataFrameOf(
                    "traversals" to r.map { it[0] as Double }, "value" to r.map { it[1] as Double },
                    "panel" to r.map { it[2] as String }, "series" to r.map { it[3] as String },
                )
            }
        val remote = { r: Map<String, String> -> r.getValue("remotePct").toDouble() }
        val focus = frame(variants.map { it to it }, remote)
        val context = frame(variants.flatMap { p -> (variants - p).map { p to it } }, remote)
        val static = frame(variants.map { it to it }) { it.getValue("staticPct").toDouble() }

        val chart = plot(focus) {
            withData(context) {
                line {
                    x("traversals") { axis.name = "traversals (millions)" }
                    y("value") { axis.name = "remote traversals (%)" }
                    color("series") {
                        scale = categorical(range = variants.map { CONTEXT }, domain = variants)
                        legend.type = LegendType.None
                    }
                    width = 0.6
                }
            }
            withData(static) {
                line {
                    x("traversals")
                    y("value")
                    color = REFERENCE
                    type = LineType.DASHED
                    width = 0.9
                }
            }
            line {
                x("traversals")
                y("value")
                color = FOCUS
                width = 1.4
            }
            points {
                x("traversals")
                y("value")
                color = FOCUS
                size = 2.5
            }
            facetWrap(nCol = 3) { facet(column<String>("panel")) }
            layout {
                title = "$dataset, $policy eviction: remote traversals per window, central vs distributed placement"
                subtitle = "blue: the panel's placer (adaptive); light grey: the other placers; dark dashed: the panel's static placer"
                size = 1000 to 200 + 210 * ((variants.size + 2) / 3)
            }
        }
        val slug = "$dataset-$policy".lowercase().replace(Regex("[^a-z0-9]+"), "-")
        println(chart.save("$slug.png", path = dir.path))

        for (v in variants) {
            val (cost, base) = totalCost(byVariant.getValue(v), alpha)
            summary += Triple("$dataset, $policy", v, 100 * (1 - cost / base))
            costs += listOf("$dataset, $policy", v, cost / 1e6, base / 1e6)
        }
    }

    val df = dataFrameOf(
        "panel" to summary.map { it.first }, "variant" to summary.map { it.second }, "saved" to summary.map { it.third },
    )
    val chart = plot(df) {
        bars {
            x("variant") { axis.name = "" }
            y("saved") { axis.name = "cost saved vs the variant's static placer (%)" }
            fillColor = FOCUS
            borderLine.width = 0.0
            width = 0.6
        }
        coordinatesTransformation = CoordinatesTransformation.cartesianFlipped()   // horizontal bars: labels fit
        facetWrap(nCol = 2) { facet(column<String>("panel")) }
        layout {
            title = "Cost saved by adapting: central vs distributed placement"
            subtitle = "notify8: a moved file tells its 8 heaviest neighbours; verify: check locations first; oracle: exact locations\n" +
                "cent-near / dist-near: central / notify8, and a new file goes beside the file it was reached from\n" +
                "cent-rack / near-rack / dist-near-rack: central / cent-near / dist-near, keeping every file on two racks\n" +
                "notify8-cms / dist-near-cms: notify8 / dist-near, with pairs in a count-min sketch"
            size = 1000 to 180 + 280 * ((summary.map { it.first }.distinct().size + 1) / 2)
        }
    }
    println(chart.save("saved.png", path = dir.path))

    val cf = dataFrameOf(
        "panel" to costs.map { it[0] as String }, "variant" to costs.map { it[1] as String },
        "cost" to costs.map { it[2] as Double }, "static" to costs.map { it[3] as Double },
    )
    val costChart = plot(cf) {
        bars {
            x("variant") { axis.name = "" }
            y("cost") { axis.name = "total cost: remote traversals + 8 x copies (millions)" }
            fillColor = FOCUS
            borderLine.width = 0.0
            width = 0.6
        }
        points {
            x("variant")
            y("static")
            color = REFERENCE
            size = 3.0
        }
        coordinatesTransformation = CoordinatesTransformation.cartesianFlipped()
        facetWrap(nCol = 2) { facet(column<String>("panel")) }
        layout {
            title = "Total cost: central vs distributed placement"
            subtitle = "blue bar: the adaptive placer; dark point: the same variant's static placer (never optimises)"
            size = 1000 to 180 + 280 * ((costs.map { it[0] }.distinct().size + 1) / 2)
        }
    }
    println(costChart.save("cost.png", path = dir.path))

    memoryChart(rows, dir)
    args.getOrNull(2)?.let { scalingChart(readRows(it), alpha, dir) }
    args.getOrNull(3)?.let { tradeoffChart(readRows(it), alpha, dir) }
}

private fun readRows(path: String): List<Map<String, String>> {
    val lines = File(path).readLines()
    val header = lines.first().split(',')
    return lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
}

/** (remote + alpha * copies, the same for the static placer) over the windows of one run. */
private fun totalCost(windows: List<Map<String, String>>, alpha: Double): Pair<Double, Double> {
    var prev = 0L
    var cost = 0.0
    var base = 0.0
    for (w in windows) {
        val t = w.getValue("traversals").toLong()
        cost += (t - prev) * w.getValue("remotePct").toDouble() / 100 + alpha * w.getValue("copies").toLong()
        base += (t - prev) * w.getValue("staticPct").toDouble() / 100 + alpha * w.getValue("staticCopies").toLong()
        prev = t
    }
    return cost to base
}

private fun bytesOf(w: Map<String, String>) = w.getValue("pairBytes").toLong() + w.getValue("locationBytes").toLong()

/** The window with the most memory in pairs and locations. */
private fun peak(windows: List<Map<String, String>>) = windows.maxBy(::bytesOf)

private fun panel(w: Map<String, String>) = "${w.getValue("dataset")}, ${w.getValue("policy")}"

/** Peak memory of each distributed variant, split into pairs and locations (bars side by side). */
private fun memoryChart(rows: List<Map<String, String>>, dir: File) {
    val out = ArrayList<List<Any>>()
    for ((key, run) in rows.filter { it.getValue("pairBytes") != "0" }.groupBy { panel(it) to it.getValue("variant") }) {
        val p = peak(run)
        out += listOf(key.first, key.second, "pairs", p.getValue("pairBytes").toLong() / 1e6)
        out += listOf(key.first, key.second, "locations", p.getValue("locationBytes").toLong() / 1e6)
    }
    val df = dataFrameOf(
        "panel" to out.map { it[0] as String }, "variant" to out.map { it[1] as String },
        "part" to out.map { it[2] as String }, "mb" to out.map { it[3] as Double },
    )
    val chart = plot(df) {
        bars {
            x("variant") { axis.name = "" }
            y("mb") { axis.name = "memory at the peak window, all 16 servers (MB)" }
            fillColor("part") {
                scale = categorical(range = listOf(FOCUS, PART), domain = listOf("pairs", "locations"))
                legend.name = ""
            }
            borderLine.width = 0.0
        }
        coordinatesTransformation = CoordinatesTransformation.cartesianFlipped()
        facetWrap(nCol = 2, scalesSharing = ScalesSharing.FREE_X) { facet(column<String>("panel")) }
        layout {
            title = "Memory of the distributed placers: pairs and location caches"
            subtitle = "pairs: what a server keeps of traversed pairs, every pair or a count-min sketch; locations: where other files are\n" +
                "estimated from object sizes on a 64-bit JVM; x axes differ between panels"
            size = 1000 to 180 + 330 * ((out.map { it[0] }.distinct().size + 1) / 2)
        }
    }
    println(chart.save("memory.png", path = dir.path))
}

/** Pair memory, total memory and cost saved as the cache grows, one column of panels per dataset. */
private fun scalingChart(rows: List<Map<String, String>>, alpha: Double, dir: File) {
    val out = ArrayList<List<Any>>()
    for ((key, run) in rows.groupBy { Triple(it.getValue("dataset"), it.getValue("maxFiles").toLong(), it.getValue("variant")) }) {
        val (dataset, _, variant) = key
        val p = peak(run)
        val (cost, base) = totalCost(run, alpha)
        val files = key.second / 1e3
        val series = if (variant.endsWith("cms")) "sketch" else "exact"
        out += listOf("$dataset: pairs (MB)", series, files, p.getValue("pairBytes").toLong() / 1e6)
        out += listOf("$dataset: pairs + locations (MB)", series, files, bytesOf(p) / 1e6)
        out += listOf("$dataset: cost saved (%)", series, files, 100 * (1 - cost / base))
    }
    val df = dataFrameOf(
        "panel" to out.map { it[0] as String }, "series" to out.map { it[1] as String },
        "files" to out.map { it[2] as Double }, "value" to out.map { it[3] as Double },
    )
    val chart = plot(df) {
        line {
            x("files") { axis.name = "files in the placer (thousands)" }
            y("value") { axis.name = "" }
            color("series") {
                scale = categorical(range = listOf(REFERENCE, FOCUS), domain = listOf("exact", "sketch"))
                legend.name = ""
            }
            width = 1.4
        }
        points {
            x("files")
            y("value")
            color("series")
            size = 2.5
        }
        facetWrap(nCol = 3, scalesSharing = ScalesSharing.FREE) { facet(column<String>("panel")) }
        layout {
            title = "dist-near with exact pairs and with a count-min sketch, as the cache grows (LRU)"
            subtitle = "memory at the peak window, all 16 servers"
            size = 1000 to 760
        }
    }
    println(chart.save("memory-scaling.png", path = dir.path))
}

/** Cost saved against pair memory for each sketch size, with the exact pairs as the reference. */
private fun tradeoffChart(rows: List<Map<String, String>>, alpha: Double, dir: File) {
    val out = ArrayList<List<Any>>()
    for ((key, run) in rows.groupBy { it.getValue("dataset") to it.getValue("variant") }) {
        val (dataset, variant) = key
        val (cost, base) = totalCost(run, alpha)
        val neighbours = if (variant.startsWith("cms-")) variant.substringAfterLast('-') + " neighbours" else "exact"
        out += listOf(dataset, neighbours, peak(run).getValue("pairBytes").toLong() / 1e6, 100 * (1 - cost / base))
    }
    val domain = out.map { it[1] as String }.distinct().sortedBy { it.substringBefore(' ').toIntOrNull() ?: Int.MAX_VALUE }
    val df = dataFrameOf(
        "panel" to out.map { it[0] as String }, "series" to out.map { it[1] as String },
        "mb" to out.map { it[2] as Double }, "saved" to out.map { it[3] as Double },
    )
    val chart = plot(df) {
        points {
            x("mb") { axis.name = "pair memory at the peak window (MB)" }
            y("saved") { axis.name = "cost saved vs static (%)" }
            color("series") {
                scale = categorical(range = SERIES.take(domain.size), domain = domain)
                legend.name = ""
            }
            size = 4.0
        }
        facetWrap(nCol = 3, scalesSharing = ScalesSharing.FREE) { facet(column<String>("panel")) }
        layout {
            title = "What a smaller sketch costs: dist-near, sketch sizes against exact pairs"
            subtitle = "each dot is a sketch size (1 to 16 cells per row per replica held) and a neighbour limit; dark: exact pairs"
            size = 1000 to 460
        }
    }
    println(chart.save("memory-tradeoff.png", path = dir.path))
}

private val FOCUS: Color = Color.hex("#2a78d6")
private val CONTEXT: Color = Color.hex("#d3d2cc")
private val REFERENCE: Color = Color.hex("#52514e")
private val PART: Color = Color.hex("#e0a030")
private val SERIES: List<Color> = listOf("#7fb8ee", "#2a78d6", "#e0a030", "#c8502a", "#52514e").map(Color::hex)
