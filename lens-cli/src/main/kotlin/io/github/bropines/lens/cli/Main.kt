package io.github.bropines.lens.cli

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.github.ajalt.clikt.command.SuspendingCliktCommand
import com.github.ajalt.clikt.command.main
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.file
import io.github.bropines.lens.protocol.LensClient
import io.github.bropines.lens.protocol.LensException
import io.github.bropines.lens.protocol.ScanOptions
import kotlinx.serialization.json.Json

class LensCommand : SuspendingCliktCommand(name = "lens") {
    override fun help(context: Context) = "OCR and translate an image with Google Lens."

    private val image by argument(help = "image file").file(mustExist = true, canBeDir = false, mustBeReadable = true)
    private val target by option("-t", "--translate", help = "translate into this language, e.g. ru")
    private val source by option("-s", "--from", help = "source language; detected when omitted")
    private val ocrLanguage by option("-l", "--ocr-language", help = "OCR language hint").default("")
    private val json by option("--json", help = "print the full result as JSON").flag()
    private val verbose by option("-v", "--verbose", help = "log the request").flag()

    override suspend fun run() {
        Logger.setMinSeverity(if (verbose) Severity.Debug else Severity.Warn)
        val options = ScanOptions(ocrLanguage = ocrLanguage, targetLanguage = target, sourceLanguage = source)
        val result = try {
            LensClient.create().scan(image.readBytes(), options)
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

suspend fun main(args: Array<String>) = LensCommand().main(args)
