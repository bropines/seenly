package io.github.bropines.lens.app.ui.camera

/** The desktop reads no codes. */
actual suspend fun readCode(image: ByteArray): ScannedCode? = null
