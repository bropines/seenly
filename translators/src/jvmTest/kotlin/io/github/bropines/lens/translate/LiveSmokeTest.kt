package io.github.bropines.lens.translate

import io.ktor.client.HttpClient
import io.ktor.client.call.save
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Calls every keyless engine once against the real service, to check the
 * parsers against today's responses. It does nothing unless `LENS_LIVE=1` is
 * in the environment:
 *
 *     LENS_LIVE=1 ./gradlew :translators:jvmTest --tests '*LiveSmokeTest*' --rerun
 *
 * The outcome and the first part of every response body go to
 * `translators/build/live-report.txt`. `LENS_LIVE_ONLY=bing,lara` limits the
 * run to some engines.
 */
class LiveSmokeTest {
    @Test
    fun keylessEnginesAnswer() {
        if (System.getenv("LENS_LIVE") != "1") return
        val only = System.getenv("LENS_LIVE_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val report = StringBuilder()
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 20_000 }
        }
        client.plugin(HttpSend).intercept { request ->
            val call = execute(request).save()
            report.appendLine("    ${request.method.value} ${request.url.buildString().take(160)} -> ${call.response.status.value}")
            report.appendLine("      " + call.response.bodyAsText().replace(Regex("\\s+"), " ").take(600))
            call
        }
        val registry = TranslatorRegistry()
        val keyless = listOf("google", "bing", "yandex", "deepl-web", "lara", "modernmt", "mymemory", "lingva")
        runBlocking {
            for (id in keyless) {
                if (only != null && id !in only) continue
                val provider = registry[id]!!
                report.appendLine("== $id")
                val outcome = try {
                    val request = TranslationRequest(
                        text = "The weather is nice today.",
                        target = "ru",
                        // MyMemory cannot detect.
                        source = if (Capability.AutoDetect in provider.capabilities) null else "en",
                    )
                    val result = provider.translate(request, SimpleProviderHost(client))
                    "OK text=${result.text} detected=${result.detectedSource} translit=${result.transliteration}"
                } catch (e: TranslationException) {
                    "FAILED ${e.code.id} status=${e.httpStatus}: ${e.message}"
                } catch (e: Exception) {
                    "CRASHED ${e::class.simpleName}: ${e.message}"
                }
                report.appendLine("  $outcome")
            }
        }
        client.close()
        File("build").mkdirs()
        File("build/live-report.txt").writeText(report.toString())
        println(report)
    }
}
