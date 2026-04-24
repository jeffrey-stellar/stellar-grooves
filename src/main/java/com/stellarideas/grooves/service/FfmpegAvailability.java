package com.stellarideas.grooves.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Probes for {@code ffmpeg} on the PATH at application startup and caches the
 * result. Transcode endpoints consult this flag instead of spawning a probe
 * process per request.
 */
@Component
public class FfmpegAvailability {

    private static final Logger log = LoggerFactory.getLogger(FfmpegAvailability.class);

    private volatile boolean available;

    @PostConstruct
    void probe() {
        available = runProbe();
        if (available) {
            log.info("ffmpeg detected on PATH; transcode endpoints enabled.");
        } else {
            log.warn("ffmpeg not found on PATH; transcode endpoints will return 503. "
                    + "Install ffmpeg on the host or bundle it in the container image.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    private boolean runProbe() {
        Process process = null;
        try {
            process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
