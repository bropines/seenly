package io.github.bropines.lens.protocol

/**
 * Values that mirror Chromium's Lens overlay, so requests look like the ones
 * the server is tuned for. The image numbers come from Chromium's
 * `components/lens/lens_features.cc` and `lens_bitmap_processing.cc`, by way of
 * chrome-lens-py and chrome-lens-rs.
 */
public object LensDefaults {
    public const val CRUPLOAD_ENDPOINT: String = "https://lensfrontend-pa.googleapis.com/v1/crupload"

    /** The API key Chromium ships with for the Lens overlay. */
    public const val API_KEY: String = "AIzaSyDr2UxVnv_U85AbhhY8XSHSIavUW0DC-sY"

    public const val USER_AGENT: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * Downscale only when the image is *both* over this area and over one of
     * the side limits (`lens::ShouldDownscaleSize`). A 1600x900 screenshot is
     * sent untouched.
     */
    public const val MAX_AREA: Long = 1_500_000
    public const val MAX_SIDE: Int = 1600

    /** `kLensOverlayImageCompressionQuality`. */
    public const val JPEG_QUALITY: Int = 40

    public const val REGION: String = "US"
    public const val TIME_ZONE: String = "America/New_York"
}
