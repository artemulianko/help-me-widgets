import com.android.ide.common.vectordrawable.Svg2Vector
import java.io.File
fun main(args: Array<String>) {
    val inDir = File(args[0]); val outDir = File(args[1]); outDir.mkdirs()
    var failed = 0
    inDir.listFiles { f -> f.extension == "svg" }!!.sorted().forEach { f ->
        val out = File(outDir, f.nameWithoutExtension + ".xml")
        val err = out.outputStream().use { Svg2Vector.parseSvgToXml(f.toPath(), it) }
        if (!err.isNullOrBlank()) failed++
        println("${f.name}: ${if (err.isNullOrBlank()) "ok" else err.lines().joinToString(" | ")}")
    }
    if (failed > 0) { System.err.println("$failed icon(s) failed to convert"); kotlin.system.exitProcess(1) }
}
