import org.jetbrains.kotlinx.dataframe.api.column
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.kandy.dsl.categorical
import org.jetbrains.kotlinx.kandy.dsl.continuousPos
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.ir.Plot
import org.jetbrains.kotlinx.kandy.ir.scale.Scale
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.layers.line
import org.jetbrains.kotlinx.kandy.letsplot.layers.points
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.facetWrap
import org.jetbrains.kotlinx.kandy.letsplot.scales.Transformation
import org.jetbrains.kotlinx.kandy.letsplot.scales.guide.LegendType
import org.jetbrains.kotlinx.kandy.letsplot.settings.LineType
import org.jetbrains.kotlinx.kandy.util.color.Color
import java.io.File

/**
 * Charts of cache-size sweeps: the csv rows CacheSimulation appends, one run per maxFiles.
 * Usage: CachePlot <output dir> <sweep.csv>... For each dataset and metric (miss%, local% of the
 * adaptive placer, total cost of the adaptive and of the static placer, cost saved by adapting) one
 * chart (PDF) against the cache size (log scale).
 *
 * Every policy is drawn, so no set of hues could keep them apart; instead each policy gets a
 * panel (small multiples) where it is the one coloured line and the other policies are faint grey
 * context. The two bounds are in every panel in dark grey: Belady (offline optimum) dotted, and
 * "all" (no limit; it does not depend on the cache size, so one run is drawn flat) dashed.
 */
fun main(args: Array<String>) {
    require(args.size >= 2) { "usage: CachePlot <output dir> <sweep.csv>..." }
    val dir = File(args[0]).apply { mkdirs() }
    val rows = args.drop(1).flatMap { path ->
        val lines = File(path).readLines()
        val header = lines.first().split(',')
        lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
    }

    for ((dataset, sweep) in rows.groupBy { it.getValue("dataset") }) {
        val sizes = sweep.map { it.getValue("maxFiles").toInt() }.distinct().sorted()
        val byPolicy = sweep.groupBy { it.getValue("policy") }
        val online = byPolicy.keys.filter { it !in REFERENCES }
        val slug = dataset.lowercase().replace(Regex("[^a-z0-9]+"), "-")

        for ((column, title, yName, unit) in METRICS) {
            fun points(policy: String) = byPolicy[policy].orEmpty()
                .map { it.getValue("maxFiles").toInt() to it.getValue(column).toDouble() / unit }.sortedBy { it.first }

            /** Rows (cache, value, panel, series): in each of [panels], the series [series] gives it. */
            fun frame(panels: List<String>, series: (String) -> List<String>, values: (String) -> List<Pair<Int, Double>>) =
                panels.flatMap { panel -> series(panel).flatMap { s -> values(s).map { (c, v) -> listOf(c, v, panel, s) } } }
                    .let { r ->
                        dataFrameOf(
                            "cache" to r.map { it[0] as Int }, "value" to r.map { it[1] as Double },
                            "panel" to r.map { it[2] as String }, "series" to r.map { it[3] as String },
                        )
                    }

            val focus = frame(online, { listOf(it) }, ::points)
            val context = frame(online, { p -> online - p }, ::points)
            // "all" is one run; draw it flat over the swept sizes.
            val flat = points("all").firstOrNull()?.second
            val bounds = REFERENCES.keys.associateWith { name ->
                frame(online, { listOf(name) }) { if (name == "all") flat?.let { v -> sizes.map { it to v } }.orEmpty() else points(name) }
            }

            val chart: Plot = plot(focus) {
                withData(context) {
                    line {
                        x("cache") {
                            axis.name = "files the cache holds (log scale)"
                            // no label at the right end, where it would run into the next panel's first one
                            axis.breaks(generateSequence(1) { it * 10 }.dropWhile { it < sizes.first() }.takeWhile { it < sizes.last() }.toList())
                            scale = Scale.continuousPos<Int>(transform = Transformation.LOG10)
                        }
                        y("value") { axis.name = yName }
                        color("series") {
                            scale = categorical(range = online.map { CONTEXT }, domain = online)
                            legend.type = LegendType.None
                        }
                        width = 0.6
                    }
                }
                for ((name, type) in REFERENCES) withData(bounds.getValue(name)) {
                    line {
                        x("cache")
                        y("value")
                        color = REFERENCE
                        this.type = type
                        width = 0.9
                    }
                }
                line {
                    x("cache")
                    y("value")
                    color = FOCUS
                    width = 1.4
                }
                points {
                    x("cache")
                    y("value")
                    color = FOCUS
                    size = 2.5
                }
                facetWrap(nCol = 3) { facet(column<String>("panel")) }
                layout {
                    this.title = "$dataset: $title"
                    subtitle = "blue: the panel's policy; light grey: the other policies; " +
                        "dark dotted: Belady (offline optimum); dark dashed: no limit (all)"
                    size = 1000 to 820
                }
            }
            println(chart.savePdf("$slug-${column.removeSuffix("Pct")}", dir))
        }
    }
}

/** csv column, chart title, y axis name, and what the values are divided by. */
private data class Metric(val column: String, val title: String, val yName: String, val unit: Double = 1.0)

private const val COST = "remote traversals + 8 x copies, millions"
private val METRICS = listOf(
    Metric("missPct", "miss rate by cache size", "traversed files not cached (%)"),
    Metric("localPct", "traversals within one server, adaptive placer", "local traversals (%)"),
    Metric("adaptiveCost", "total cost of the adaptive placer", COST, 1e6),
    Metric("staticCost", "total cost of the static placer", COST, 1e6),
    Metric("savedPct", "cost saved by adapting, vs the static placer", "cost saved (%)"),
)
private val REFERENCES = linkedMapOf("Belady" to LineType.DOTTED, "all" to LineType.DASHED)
private val FOCUS: Color = Color.hex("#2a78d6")
private val CONTEXT: Color = Color.hex("#d3d2cc")
private val REFERENCE: Color = Color.hex("#52514e")
