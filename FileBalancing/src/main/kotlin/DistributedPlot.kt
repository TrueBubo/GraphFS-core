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
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.facetWrap
import org.jetbrains.kotlinx.kandy.letsplot.scales.guide.LegendType
import org.jetbrains.kotlinx.kandy.letsplot.settings.LineType
import org.jetbrains.kotlinx.kandy.util.color.Color
import java.io.File

/**
 * Charts of the central-vs-distributed runs: the csv rows DistributedSimulation appends, one per
 * variant and window. Usage: DistributedPlot <output dir> <distributed.csv>.
 *  - per dataset and eviction policy, remote% of each variant's adaptive placer over the run, in
 *    small multiples: one panel per variant, the other variants faint grey, the panel's own static
 *    placer dark dashed;
 *  - two summaries per dataset and policy: each variant's total cost (bar) beside its static
 *    placer's (dark point), and the cost saved against that static placer.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: DistributedPlot <output dir> <distributed.csv>" }
    val dir = File(args[0]).apply { mkdirs() }
    val lines = File(args[1]).readLines()
    val header = lines.first().split(',')
    val rows = lines.drop(1).map { line -> header.zip(line.split(',')).toMap() }
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
                size = 1000 to 620
            }
        }
        val slug = "$dataset-$policy".lowercase().replace(Regex("[^a-z0-9]+"), "-")
        println(chart.save("$slug.png", path = dir.path))

        // Saved = 1 - (remote + alpha * copies) / (static remote + alpha * static copies), over the whole run.
        for (v in variants) {
            var prev = 0L
            var cost = 0.0
            var base = 0.0
            for (w in byVariant.getValue(v)) {
                val t = w.getValue("traversals").toLong()
                cost += (t - prev) * w.getValue("remotePct").toDouble() / 100 + alpha * w.getValue("copies").toLong()
                base += (t - prev) * w.getValue("staticPct").toDouble() / 100 + alpha * w.getValue("staticCopies").toLong()
                prev = t
            }
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
                "cent-rack / near-rack / dist-near-rack: central / cent-near / dist-near, keeping every file on two racks"
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
}

private val FOCUS: Color = Color.hex("#2a78d6")
private val CONTEXT: Color = Color.hex("#d3d2cc")
private val REFERENCE: Color = Color.hex("#52514e")
