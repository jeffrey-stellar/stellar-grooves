package com.stellarideas.grooves.util;

/**
 * Detects an image's MIME type from its leading "magic" bytes, independent of the
 * client-supplied Content-Type or file extension (both of which are easily spoofed).
 * Used to validate uploaded cover art is a real, supported image before storing it.
 */
public final class ImageTypeDetector {

    private ImageTypeDetector() {}

    /**
     * @return the detected image MIME type (e.g. {@code "image/jpeg"}), or {@code null}
     *         if the bytes don't match a supported image format.
     */
    public static String detectMime(byte[] data) {
        if (data == null || data.length < 12) return null;

        // JPEG: FF D8 FF
        if (u(data[0]) == 0xFF && u(data[1]) == 0xD8 && u(data[2]) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (u(data[0]) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
                && u(data[4]) == 0x0D && u(data[5]) == 0x0A && u(data[6]) == 0x1A && u(data[7]) == 0x0A) {
            return "image/png";
        }
        // GIF: "GIF87a" / "GIF89a"
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8'
                && (data[4] == '7' || data[4] == '9') && data[5] == 'a') {
            return "image/gif";
        }
        // WEBP: "RIFF" .... "WEBP"
        if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        // BMP: "BM"
        if (data[0] == 'B' && data[1] == 'M') {
            return "image/bmp";
        }
        return null;
    }

    private static int u(byte b) {
        return b & 0xFF;
    }
}
