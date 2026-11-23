package io.github.bropines.lens.cli

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import io.github.bropines.lens.protocol.LensClient
import io.github.bropines.lens.protocol.LensException
import io.github.bropines.lens.protocol.Region
import io.github.bropines.lens.protocol.ScanOptions
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.SearchPageOptions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

class LensCommand : SuspendingCliktCommand(name = "lens") {
    override fun help(context: Context) = "OCR and translate an image with Google Lens. `lens search <image>` searches with it instead."

    private val image by argument(help = "image file").file(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val target by option("-t", "--translate", help = "translate into this language, e.g. ru")
    private val source by option("-s", "--from", help = "source language; detected when omitted")
    private val ocrLanguage by option("-l", "--ocr-language", help = "OCR language hint").default("")
    private val json by option("--json", help = "print the full result as JSON").flag()
    private val verbose by option("-v", "--verbose", help = "log the request").flag()
    private val tiled by option("--tiled", help = "scan a tall image in full-resolution bands").flag()

    override suspend fun run() {
        Logger.setMinSeverity(if (verbose) Severity.Debug else Severity.Warn)
        val options = ScanOptions(ocrLanguage = ocrLanguage, targetLanguage = target, sourceLanguage = source)
        val result = try {
            val client = LensClient.create()
            if (tiled) client.scanTiled(image.readBytes(), options) else client.scan(image.readBytes(), options)
        } catch (e: LensException) {
            System.err.println("lens: ${e.message}")
            throw com.github.ajalt.clikt.core.ProgramResult(1)
        }
        if (json) {
            println(Json { prettyPrint = true }.encodeToString(result))
        } else {
            println(result.text)
            result.translation?.let { println("\n--- ${target ?: ""}\n$it") }
        }
    }
}

/** `lens search <image>`: uploads the image and prints the results-page URL for it. */
class SearchCommand : SuspendingCliktCommand(name = "lens search") {
    override fun help(context: Context) =
        "Search with an image: prints a Google results URL to open in your own browser."

    private val image by argument(help = "image file").file(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val region by option("--region", help = "search only this part: x,y,w,h as fractions of the image, e.g. 0.1,0.2,0.5,0.3")
        .convert { value ->
            val parts = value.split(',').map { it.trim().toFloatOrNull() ?: fail("not a number: $it") }
            if (parts.size != 4) fail("expected x,y,w,h")
            Region(parts[0], parts[1], parts[2], parts[3])
        }
    private val ai by option("--ai", help = "ask AI Mode this question about the image (or the region)")
    private val text by option("--text", help = "words to add to the image search")
    private val language by option("--hl", help = "language of the results page")
    private val json by option("--json", help = "print {url, regionText} as JSON; regionText is the region read again by Lens").flag()
    private val verbose by option("-v", "--verbose", help = "log every request and its status").flag()

    override suspend fun run() {
        Logger.setMinSeverity(if (verbose) Severity.Debug else Severity.Warn)
        val session = LensClient.create().searchSession(SearchPageOptions(uiLanguage = language))
        try {
            val scan = session.scan(image.readBytes())
            System.err.println("uploaded ${scan.imageWidth}x${scan.imageHeight}, ${scan.objects.size} objects, ${scan.paragraphs.size} paragraphs")
            val question = ai
            val part = region
            var reread: ScanResult? = null
            val url = when {
                question != null -> session.askAiUrl(question, part)
                part != null -> session.searchRegion(part, text).also { found ->
                    reread = found.text
                }.url
                text != null -> session.searchRegionUrl(Region.Whole, text)
                else -> session.searchImageUrl()
            }
            if (json) {
                val out = buildJsonObject {
                    put("url", url)
                    put("regionText", reread?.let { Json.encodeToJsonElement(it) } ?: JsonNull)
                }
                println(Json { prettyPrint = true }.encodeToString(out))
            } else {
                reread?.let { System.err.println("region text: ${it.text}") }
                println(url)
            }
        } catch (e: LensException) {
            System.err.println("lens: ${e.message}")
            throw ProgramResult(1)
        } catch (e: IllegalArgumentException) {
            System.err.println("lens: ${e.message}")
            throw ProgramResult(1)
        }
    }
}

/** `lens search ...` searches; anything else is the original OCR command, so `lens <image>` keeps working. */
suspend fun main(args: Array<String>) {
    if (args.firstOrNull() == "search") SearchCommand().main(args.drop(1)) else LensCommand().main(args)
    // OkHttp's idle threads would keep the JVM alive for another minute.
    exitProcess(0)
}
