package com.stellarideas.grooves.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScanPathValidatorTest {

    @TempDir
    Path tempDir;

    private MessageHelper msgHelper;

    @BeforeEach
    void setUp() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasename("messages");
        msgHelper = new MessageHelper(ms);
    }

    @Test
    void rejectsEmptyPath() {
        ScanPathValidator v = new ScanPathValidator(msgHelper, "");
        assertThrows(IllegalArgumentException.class, () -> v.validate(""));
        assertThrows(IllegalArgumentException.class, () -> v.validate(null));
    }

    @Test
    void rejectsNonexistentPath() {
        ScanPathValidator v = new ScanPathValidator(msgHelper, "");
        assertThrows(IllegalArgumentException.class, () -> v.validate("/nonexistent/path/xyz/abc"));
    }

    @Test
    void rejectsPathWithTraversalSequences() {
        ScanPathValidator v = new ScanPathValidator(msgHelper, "");
        assertThrows(IllegalArgumentException.class,
                () -> v.validate(tempDir + "/../../../etc"));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void rejectsSymlinkDirectory() throws IOException {
        Path real = tempDir.toRealPath().resolve("real");
        Files.createDirectory(real);
        Path link = tempDir.toRealPath().resolve("link");
        Files.createSymbolicLink(link, real);

        ScanPathValidator v = new ScanPathValidator(msgHelper, "");
        assertThrows(IllegalArgumentException.class, () -> v.validate(link.toString()));
    }

    @Test
    void acceptsRealDirectory() throws IOException {
        Path music = tempDir.toRealPath().resolve("music");
        Files.createDirectory(music);

        ScanPathValidator v = new ScanPathValidator(msgHelper, "");
        Path result = v.validate(music.toString());
        assertEquals(music, result);
    }

    @Test
    void allowlistAcceptsPathsInsideBase() throws IOException {
        Path base = tempDir.toRealPath().resolve("music");
        Files.createDirectory(base);
        Path inside = base.resolve("artist");
        Files.createDirectory(inside);

        ScanPathValidator v = new ScanPathValidator(msgHelper, base.toString());
        assertDoesNotThrow(() -> v.validate(inside.toString()));
    }

    @Test
    void allowlistRejectsPathsOutsideBase() throws IOException {
        Path allowed = tempDir.toRealPath().resolve("allowed");
        Files.createDirectory(allowed);
        Path outside = tempDir.toRealPath().resolve("outside");
        Files.createDirectory(outside);

        ScanPathValidator v = new ScanPathValidator(msgHelper, allowed.toString());
        assertThrows(IllegalArgumentException.class, () -> v.validate(outside.toString()));
    }

    @Test
    void allowlistAcceptsBaseItself() throws IOException {
        Path base = tempDir.toRealPath().resolve("base");
        Files.createDirectory(base);

        ScanPathValidator v = new ScanPathValidator(msgHelper, base.toString());
        assertDoesNotThrow(() -> v.validate(base.toString()));
    }

    @Test
    void allowlistSupportsMultipleBases() throws IOException {
        Path baseA = tempDir.toRealPath().resolve("a");
        Path baseB = tempDir.toRealPath().resolve("b");
        Files.createDirectory(baseA);
        Files.createDirectory(baseB);

        ScanPathValidator v = new ScanPathValidator(msgHelper, baseA + "," + baseB);
        assertDoesNotThrow(() -> v.validate(baseA.toString()));
        assertDoesNotThrow(() -> v.validate(baseB.toString()));
    }

    @Test
    void allowlistIgnoresInvalidEntries() throws IOException {
        Path valid = tempDir.toRealPath().resolve("valid");
        Files.createDirectory(valid);

        // Includes nonexistent entries that should be silently dropped
        ScanPathValidator v = new ScanPathValidator(msgHelper,
                "/nonexistent/xyz," + valid + ",  ");
        assertDoesNotThrow(() -> v.validate(valid.toString()));
    }
}
