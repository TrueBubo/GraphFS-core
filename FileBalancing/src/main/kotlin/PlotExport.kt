import org.jetbrains.kotlinx.kandy.ir.Plot
import org.jetbrains.kotlinx.kandy.letsplot.export.save
import java.io.File

/**
 * Saves the chart as <name>.pdf in [dir] and returns its path. Kandy cannot write PDF, so the PDF
 * is converted from its SVG export by rsvg-convert (librsvg), which keeps it vector.
 */
fun Plot.savePdf(name: String, dir: File): String {
    val svg = File(save("$name.svg", path = dir.path))
    val pdf = File(dir, "$name.pdf")
    val process = ProcessBuilder("rsvg-convert", "-f", "pdf", "-o", pdf.path, svg.path).inheritIO().start()
    check(process.waitFor() == 0) { "rsvg-convert failed on $svg" }
    svg.delete()
    return pdf.path
}
