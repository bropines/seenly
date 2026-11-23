package io.github.bropines.lens.protocol

/** Decoded pixels, ARGB, row by row. */
public class Raster(public val width: Int, public val height: Int, public val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "bad raster ${width}x$height/${argb.size}" }
    }
}

/** The platform's codecs. Everything that decides *what* to upload is common, in [ImagePreparer]. */
internal expect object PlatformImages {
    /** Decodes any format the platform knows, applying EXIF orientation where it can. */
    fun decode(bytes: ByteArray): Raster
    fun scale(raster: Raster, width: Int, height: Int): Raster
    /** [raster] is opaque by the time it gets here. */
    fun encodeJpeg(raster: Raster, quality: Int): ByteArray
}

/**
 * Prepares an image the way Chromium's Lens overlay does before upload:
 * downscale only when big on both counts, flatten real transparency, JPEG at
 * quality 40.
 */
public object ImagePreparer {
    public fun shouldDownscale(width: Int, height: Int): Boolean =
        width.toLong() * height > LensDefaults.MAX_AREA &&
            (width > LensDefaults.MAX_SIDE || height > LensDefaults.MAX_SIDE)

    /** Fits inside MAX_SIDE x MAX_SIDE, keeping the aspect ratio. */
    public fun preferredSize(width: Int, height: Int): Pair<Int, Int> {
        val scale = minOf(LensDefaults.MAX_SIDE.toDouble() / width, LensDefaults.MAX_SIDE.toDouble() / height)
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }

    public fun prepare(bytes: ByteArray, alphaMode: AlphaMode = AlphaMode.Contrast): PreparedImage {
        val raster = try {
            PlatformImages.decode(bytes)
        } catch (e: LensException) {
            throw e
        } catch (e: Exception) {
            throw LensException.UnreadableImage(e.message ?: e::class.simpleName.orEmpty(), e)
        }
        return prepare(raster, alphaMode)
    }

    public fun prepare(raster: Raster, alphaMode: AlphaMode = AlphaMode.Contrast): PreparedImage {
        val scaled = if (shouldDownscale(raster.width, raster.height)) {
            val (w, h) = preferredSize(raster.width, raster.height)
            PlatformImages.scale(raster, w, h)
        } else {
            raster
        }
        val opaque = flatten(scaled, alphaMode)
        return PreparedImage(PlatformImages.encodeJpeg(opaque, LensDefaults.JPEG_QUALITY), opaque.width, opaque.height)
    }

    /** The raster unchanged if nothing is transparent, else composited onto a solid background. */
    internal fun flatten(raster: Raster, mode: AlphaMode): Raster {
        val pixels = raster.argb
        if (pixels.all { it ushr 24 == 0xFF }) return raster
        val background = when (mode) {
            AlphaMode.White -> 0xFFFFFF
            AlphaMode.Contrast -> contrastingBackground(pixels)
        }
        val br = background shr 16 and 0xFF
        val bg = background shr 8 and 0xFF
        val bb = background and 0xFF
        val out = IntArray(pixels.size) { i ->
            val p = pixels[i]
            val a = (p ushr 24) / 255.0
            val r = ((p shr 16 and 0xFF) * a + br * (1 - a) + 0.5).toInt()
            val g = ((p shr 8 and 0xFF) * a + bg * (1 - a) + 0.5).toInt()
            val b = ((p and 0xFF) * a + bb * (1 - a) + 0.5).toInt()
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Raster(raster.width, raster.height, out)
    }

    /** White under dark content, black under light, judged on the clearly visible pixels (BT.601 luma). */
    private fun contrastingBackground(pixels: IntArray): Int {
        var total = 0.0
        var counted = 0
        for (p in pixels) {
            if (p ushr 24 > 128) {
                total += ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000.0
                counted++
            }
        }
        return if (counted == 0 || total / counted < 128) 0xFFFFFF else 0x000000
    }
}
