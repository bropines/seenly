package io.github.bropines.lens.app

import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.bropines.lens.app.data.history.LensDatabase
import io.github.bropines.lens.app.platform.PlatformServices
import io.github.bropines.lens.protocol.Line
import io.github.bropines.lens.protocol.Paragraph
import io.github.bropines.lens.protocol.ParagraphTranslation
import io.github.bropines.lens.protocol.ScanResult
import io.github.bropines.lens.protocol.TextAlignment
import io.github.bropines.lens.protocol.WritingDirection
import java.io.File
import java.nio.file.Files

/** The app's services over a scratch directory; the database is a real file in it. */
class TestServices(name: String = "seenly-test") : PlatformServices {
    val dir: File = Files.createTempDirectory(name).toFile()
    override val dataDir: String = dir.path
    val copied = mutableListOf<String>()

    override fun copyText(text: String) {
        copied += text
    }

    override fun shareText(text: String) {}
    override fun shareImage(bytes: ByteArray, fileName: String) {}
    override fun openUrl(url: String) {}
    override fun databaseBuilder(): RoomDatabase.Builder<LensDatabase> =
        Room.databaseBuilder<LensDatabase>(name = File(dir, "lens.db").path)
}

/** A one-paragraph scan: [original] recognised and, with [translation], translated into [target]. */
fun scanResult(original: String, translation: String? = "[$original]", target: String = "en"): ScanResult = ScanResult(
    paragraphs = listOf(
        Paragraph(
            text = original,
            lines = listOf(Line(original, emptyList(), null, translation, null)),
            box = null,
            language = "ja",
            writingDirection = WritingDirection.LeftToRight,
            translation = translation?.let {
                ParagraphTranslation(it, "ja", target, WritingDirection.LeftToRight, TextAlignment.Start, false)
            },
        ),
    ),
    language = "ja",
    imageWidth = 600,
    imageHeight = 900,
)
