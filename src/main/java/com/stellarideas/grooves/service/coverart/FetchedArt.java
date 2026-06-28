package com.stellarideas.grooves.service.coverart;

/**
 * An image fetched from an external cover-art provider: the raw bytes and their MIME type.
 */
public record FetchedArt(byte[] data, String mimeType) {}
