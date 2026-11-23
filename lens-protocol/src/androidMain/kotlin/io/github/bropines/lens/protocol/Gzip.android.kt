package io.github.bropines.lens.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

internal actual fun gzip(bytes: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
