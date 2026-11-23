package io.github.bropines.lens.desktop

import io.github.bropines.lens.app.platform.PlatformServices
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.JFileChooser

class DesktopPlatformServices : PlatformServices {
    override val dataDir: String = run {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base = when {
            "win" in os -> System.getenv("APPDATA") ?: "$home/AppData/Roaming"
            "mac" in os -> "$home/Library/Application Support"
            else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
        }
        File(base, "lens-kmp").apply { mkdirs() }.absolutePath
    }

    override fun copyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    /** The desktop has no share sheet; the clipboard is the closest thing. */
    override fun shareText(text: String) = copyText(text)

    override fun shareImage(bytes: ByteArray, fileName: String) {
        val chooser = JFileChooser().apply { selectedFile = File(fileName) }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.writeBytes(bytes)
    }
}
