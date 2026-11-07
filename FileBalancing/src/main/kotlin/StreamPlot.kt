import org.jetbrains.kotlinx.dataframe.api.column
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.kandy.dsl.categorical
import org.jetbrains.kotlinx.kandy.dsl.continuous
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.letsplot.export.save
import org.jetbrains.kotlinx.kandy.letsplot.feature.CoordinatesTransformation
import org.jetbrains.kotlinx.kandy.letsplot.feature.Position
import org.jetbrains.kotlinx.kandy.letsplot.feature.position
import org.jetbrains.kotlinx.kandy.letsplot.feature.coordinatesTransformation
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.layers.bars
import org.jetbrains.kotlinx.kandy.letsplot.layers.hLine
import org.jetbrains.kotlinx.kandy.letsplot.layers.line
import org.jetbrains.kotlinx.kandy.letsplot.layers.points
import org.jetbrains.kotlinx.kandy.letsplot.layers.text
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.OrderDirection
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.ScalesSharing
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.facetGrid
import org.jetbrains.kotlinx.kandy.letsplot.multiplot.facet.facetWrap
import org.jetbrains.kotlinx.kandy.letsplot.scales.Transformation
import org.jetbrains.kotlinx.kandy.letsplot.scales.guide.LegendType
import org.jetbrains.kotlinx.kandy.letsplot.settings.LineType
import org.jetbrains.kotlinx.kandy.util.color.Color
import java.io.File

/**
 * Charts of the [StreamPlacer] runs: the rows DistributedSimulation appends with -DtotalsCsv, one
 * per dataset and variant. Usage: StreamPlot <output dir> <stream.csv>.
 *  - stream-cost.png: total cost of the main variants, per dataset, with the change against cent-near;
 *  - stream-fennel.png: Fennel's size penalty scale against cost (relative to cent-near) and balance;
 *  - stream-restream.png: restreaming by how much of a copy a re-placement must gain, against no restreaming;
 *  - stream-near.png: each variant against its -near version (a new file on its near file's servers first).
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: StreamPlot <output dir> <stream.csv>" }
    val dir = File(args[0]).apply { mkdirs() }
    val lines = File(args[1]).readLines()
    val header = lines.first().split(',')
    val rows = lines.drop(1).map { header.zip(it.split(',')).toMap() }.filter { it.getValue("policy") == "LRU" }
    val datasets = DATASETS.filter { d -> rows.any { it.getValue("dataset") == d } }
    val byDataset = datasets.associateWith { d -> rows.filter { it.getValue("dataset") == d }.associateBy { it.getValue("variant") } }
    fun cost(d: String, v: String) = byDataset.getValue(d)[v]?.getValue("cost")?.toDouble()

    // Total cost of the main variants, coloured by score.
    val bars = datasets.flatMap { d ->
        val base = cost(d, "cent-near")!!
        val shown = MAIN.filter { cost(d, it) != null }
        val max = shown.maxOf { cost(d, it)!! } / 1e6
        shown.map { v ->
            val c = cost(d, v)!!
            val label = if (v == "cent-near") "" else "%+.1f %%".format(100 * (c / base - 1))
            // A label just past its bar's end; an empty one far out, so the panel has room for the others.
            listOf(d, v, familyOf(v), c / 1e6, label, if (label.isEmpty()) 1.5 * max else c / 1e6 + 0.32 * max)
        }
    }
    val df = dataFrameOf(
        "dataset" to bars.map { it[0] as String }, "variant" to bars.map { it[1] as String }, "family" to bars.map { it[2] as String },
        "cost" to bars.map { it[3] as Double }, "label" to bars.map { it[4] as String }, "labelAt" to bars.map { it[5] as Double },
    )
    val costChart = plot(df) {
        bars {
            x("variant") {
                axis.name = ""
                scale = categorical(MAIN.reversed())
            }
            y("cost") { axis.name = "total cost: remote traversals + 8 x copies (millions)" }
            fillColor("family") {
                scale = categorical(range = FAMILIES.values.toList(), domain = FAMILIES.keys.toList())
                legend.name = ""
            }
            borderLine.width = 0.0
            width = 0.6
        }
        text {
            x("variant")
            y("labelAt")
            label("label")
            font.color = TEXT
            font.size = 6.0
        }
        coordinatesTransformation = CoordinatesTransformation.cartesianFlipped()
        facetWrap(nCol = 3, scalesSharing = ScalesSharing.FREE_Y) { facet(column<String>("dataset")) }
        layout {
            title = "Streaming partitioning scores against our placer (16 servers, k = 2, LRU)"
            subtitle = "cent-near: ReplicaPlacer with near placement; ldg / fennel: StreamPlacer, a new file placed by the score\n" +
                "-moves: placed again when its debt reaches alpha, if the gain covers the copies\n" +
                "fennel-<s>: -moves with the size penalty scaled by s; -m: a replica scored by the pairs the ones before it leave remote\n" +
                "-restream-<t>: also a pass over every file each half-life, keeping gains >= t * alpha per copy; labels: change against cent-near"
            size = 1100 to 560
        }
    }
    println(costChart.save("stream-cost.png", path = dir.path))

    // Fennel's scale: cost relative to cent-near, and balance, for all and marginal pairs.
    val sweep = ArrayList<List<Any>>()
    for (d in datasets) {
        val base = cost(d, "cent-near")!!
        for ((pairs, prefix) in listOf("all pairs" to "fennel", "marginal pairs (-m)" to "fennel-m")) {
            for (sc in SCALES) {
                val v = if (sc == 1.0) "$prefix-moves" else "$prefix-$sc"
                val r = byDataset.getValue(d)[v] ?: continue
                sweep += listOf(d, COST, pairs, sc, r.getValue("cost").toDouble() / base)
                sweep += listOf(d, LOAD, pairs, sc, r.getValue("imbalance").toDouble())
            }
        }
    }
    val sf = dataFrameOf(
        "dataset" to sweep.map { it[0] as String }, "metric" to sweep.map { it[1] as String }, "pairs" to sweep.map { it[2] as String },
        "scale" to sweep.map { it[3] as Double }, "value" to sweep.map { it[4] as Double },
    )
    val pairsDomain = listOf("all pairs", "marginal pairs (-m)")
    val fennelChart = plot(sf) {
        hLine {
            yIntercept.constant(1.0)
            color = REFERENCE
            type = LineType.DOTTED
            width = 0.8
        }
        line {
            x("scale") {
                axis.name = "Fennel size penalty scale (1: the published alpha)"
                scale = continuous(transform = Transformation.LOG10)
            }
            y("value") { axis.name = "" }
            color("pairs") {
                scale = categorical(range = listOf(FENNEL, FENNEL), domain = pairsDomain)
                legend.name = ""
            }
            type("pairs") {
                scale = categorical(range = listOf(LineType.SOLID, LineType.DASHED), domain = pairsDomain)
                legend.name = ""
            }
            width = 1.4
        }
        points {
            x("scale")
            y("value")
            color("pairs") { legend.type = LegendType.None }
            size = 3.0
        }
        facetGrid(x = column<String>("dataset"), y = column<String>("metric"), scalesSharing = ScalesSharing.FREE_Y, yOrder = OrderDirection.DESCENDING)
        layout {
            title = "Fennel: the size penalty trades cut for balance (fennel-moves, LRU)"
            subtitle = "top: total cost as a multiple of cent-near's (dotted: equal); bottom: most loaded server against the average (dotted: perfect balance)\n" +
                "the hard cap keeps every placer within 1.1 of the average whatever the scale"
            size = 1100 to 620
        }
    }
    println(fennelChart.save("stream-fennel.png", path = dir.path))

    // Restreaming: cost against the same score without restreaming, by the bar a re-placement must clear.
    val steps = listOf("none" to "-moves", "1" to "-restream", "0.5" to "-restream-0.5", "0.25" to "-restream-0.25", "0" to "-restream-0.0", "free" to "-restream-free")
    val pts = ArrayList<List<Any>>()
    for (d in datasets) for (score in SCORES.keys) {
        val base = cost(d, "$score-moves") ?: continue
        for ((label, suffix) in steps) cost(d, score + suffix)?.let { pts += listOf(d, score, label, it / base) }
    }
    val rf = dataFrameOf(
        "dataset" to pts.map { it[0] as String }, "score" to pts.map { it[1] as String },
        "bar" to pts.map { it[2] as String }, "ratio" to pts.map { it[3] as Double },
    )
    val restreamChart = plot(rf) {
        hLine {
            yIntercept.constant(1.0)
            color = REFERENCE
            type = LineType.DOTTED
            width = 0.8
        }
        line {
            x("bar") {
                axis.name = "restreaming every half-life: gain a re-placement must reach, in copies' cost (alpha per copy)"
                scale = categorical(steps.map { it.first })
            }
            y("ratio") {
                axis.name = "total cost / the same score without restreaming (log)"
                scale = continuous(transform = Transformation.LOG10)
            }
            color("score") {
                scale = categorical(range = SCORES.values.map { it.first }, domain = SCORES.keys.toList())
                legend.name = ""
            }
            type("score") {
                scale = categorical(range = SCORES.values.map { it.second }, domain = SCORES.keys.toList())
                legend.name = ""
            }
            width = 1.4
        }
        facetWrap(nCol = 3) { facet(column<String>("dataset")) }
        layout {
            title = "Restreaming pays only with a bar on what a move must gain (LRU)"
            subtitle = "none: debt-triggered moves only (-moves, the reference at 1); free: every pass keeps every re-placement, as restreaming is published\n" +
                "below 1 is cheaper; dashed: replicas scored by marginal pairs (-m)"
            size = 1100 to 440
        }
    }
    println(restreamChart.save("stream-restream.png", path = dir.path))

    // Near placement: each variant's cost against cent-near's, with the score alone and near first.
    val pairs = datasets.flatMap { d ->
        val base = cost(d, "cent-near")!!
        NEAR.flatMap { v ->
            val score = v.substringBefore('-')
            listOf(v to SCORE_ONLY, v.replaceFirst(score, "$score-near") to NEAR_FIRST).mapNotNull { (name, admission) ->
                cost(d, name)?.let { listOf(d, v, admission, 100 * (it / base - 1)) }
            }
        }
    }
    val nf = dataFrameOf(
        "dataset" to pairs.map { it[0] as String }, "variant" to pairs.map { it[1] as String },
        "admission" to pairs.map { it[2] as String }, "change" to pairs.map { it[3] as Double },
    )
    val nearChart = plot(nf) {
        bars {
            x("variant") {
                axis.name = ""
                scale = categorical(NEAR.reversed())
            }
            y("change") { axis.name = "total cost against cent-near (%)" }
            fillColor("admission") {
                scale = categorical(range = listOf(FENNEL, LDG), domain = listOf(SCORE_ONLY, NEAR_FIRST))
                legend.name = ""
            }
            position = Position.dodge()
            borderLine.width = 0.0
            width = 0.7
        }
        coordinatesTransformation = CoordinatesTransformation.cartesianFlipped()
        facetWrap(nCol = 3, scalesSharing = ScalesSharing.FREE_Y) { facet(column<String>("dataset")) }
        layout {
            title = "Near placement in StreamPlacer: a new file on the servers of the file it was reached from (LRU)"
            subtitle = "score only: the near file counts as one neighbour of weight 1, so a size penalty can outweigh it\n" +
                "near first: its servers below their cap take the replicas whatever the score, as in cent-near; 0: as cheap as cent-near"
            size = 1100 to 520
        }
    }
    println(nearChart.save("stream-near.png", path = dir.path))
}

private fun familyOf(v: String) = when {
    v.startsWith("ldg") -> "LDG (StreamPlacer)"
    v.startsWith("fennel") -> "Fennel (StreamPlacer)"
    else -> "ReplicaPlacer"
}

private val DATASETS = listOf("Linux", "Twitter Higgs", "Wikipedia")

/** Bars from top to bottom. */
private val MAIN = listOf(
    "cent-near", "ldg", "ldg-moves", "ldg-m-moves", "ldg-m-restream-0.5", "ldg-m-restream-0.25", "fennel-moves", "fennel-16.0", "fennel-64.0", "fennel-m-16.0",
)

/** Variants of stream-near.png, from top to bottom, each also run as <score>-near-... */
private val NEAR = listOf(
    "ldg", "ldg-moves", "ldg-m-moves", "ldg-m-restream-0.5", "ldg-m-restream-0.25", "fennel-moves", "fennel-16.0", "fennel-64.0", "fennel-m-moves", "fennel-m-16.0",
)

private const val SCORE_ONLY = "score only"
private const val NEAR_FIRST = "near first (-near)"

/** Fennel scales of the sweep; 1 is fennel(-m)-moves. */
private val SCALES = listOf(0.25, 1.0, 4.0, 16.0, 64.0, 256.0, 1024.0)

private const val COST = "total cost / cent-near's"
private const val LOAD = "max / average load"

private val TEXT: Color = Color.hex("#0b0b0b")
private val REFERENCE: Color = Color.hex("#52514e")
private val LDG: Color = Color.hex("#2a78d6")
private val FENNEL: Color = Color.hex("#eb6834")

private val FAMILIES = linkedMapOf("ReplicaPlacer" to REFERENCE, "LDG (StreamPlacer)" to LDG, "Fennel (StreamPlacer)" to FENNEL)

/** Lines of stream-restream.png: colour by score, dashed for marginal pairs. */
private val SCORES = linkedMapOf(
    "ldg" to (LDG to LineType.SOLID),
    "ldg-m" to (LDG to LineType.DASHED),
    "fennel" to (FENNEL to LineType.SOLID),
    "fennel-m" to (FENNEL to LineType.DASHED),
)
