package io.github.bropines.wechatqr;

import android.graphics.Bitmap;

/**
 * WeChat's QR decoder (Tencent's, from opencv_contrib), built with the bits of OpenCV it needs and
 * without its neural networks. It reads codes other open decoders miss, such as one photographed
 * off a monitor with its moiré.
 */
public final class WeChatQr {
    static {
        System.loadLibrary("wechatqr");
    }

    private WeChatQr() {}

    /**
     * The QR codes in an ARGB_8888 bitmap: each one's contents as raw bytes (the code does not say
     * its text encoding; it is usually UTF-8), or null when the bitmap could not be read.
     *
     * @param corners filled with 8 floats per code, the x and y in bitmap pixels of its four
     *     corners (top-left, top-right, bottom-right, bottom-left as the code is drawn), for as many
     *     codes as it has room for; may be null.
     */
    public static native byte[][] decode(Bitmap bitmap, float[] corners);
}
