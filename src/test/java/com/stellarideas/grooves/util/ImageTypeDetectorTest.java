package com.stellarideas.grooves.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ImageTypeDetectorTest {

    private static byte[] pad(byte[] head) {
        byte[] b = new byte[16];
        System.arraycopy(head, 0, b, 0, head.length);
        return b;
    }

    @Test
    void detectsJpeg() {
        assertEquals("image/jpeg", ImageTypeDetector.detectMime(
                pad(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0})));
    }

    @Test
    void detectsPng() {
        assertEquals("image/png", ImageTypeDetector.detectMime(
                pad(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})));
    }

    @Test
    void detectsGif() {
        assertEquals("image/gif", ImageTypeDetector.detectMime(
                pad(new byte[]{'G', 'I', 'F', '8', '9', 'a'})));
    }

    @Test
    void detectsWebp() {
        assertEquals("image/webp", ImageTypeDetector.detectMime(
                pad(new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})));
    }

    @Test
    void detectsBmp() {
        assertEquals("image/bmp", ImageTypeDetector.detectMime(pad(new byte[]{'B', 'M'})));
    }

    @Test
    void rejectsNonImage() {
        assertNull(ImageTypeDetector.detectMime("not an image at all".getBytes()));
    }

    @Test
    void rejectsTooShortAndNull() {
        assertNull(ImageTypeDetector.detectMime(null));
        assertNull(ImageTypeDetector.detectMime(new byte[]{(byte) 0xFF, (byte) 0xD8}));
    }

    @Test
    void rejectsSpoofedTextWithImageExtensionContent() {
        // PDF magic — not an image
        assertNull(ImageTypeDetector.detectMime(pad(new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4'})));
    }
}
