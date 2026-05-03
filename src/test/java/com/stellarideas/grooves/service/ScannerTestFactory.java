package com.stellarideas.grooves.service;

import com.stellarideas.grooves.repository.CoverArtRepository;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.ScanJobRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.scan.AudioMetadataReader;
import com.stellarideas.grooves.service.scan.CoverArtHandler;
import com.stellarideas.grooves.service.scan.FileHasher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Wires a {@link MusicScannerService} with the new split-component constructor for unit tests.
 * Instantiates real {@link AudioMetadataReader} / {@link CoverArtHandler} / {@link FileHasher}
 * so the scan loop behaves realistically; repositories and collaborators are passed in.
 */
final class ScannerTestFactory {

    private ScannerTestFactory() {}

    static AudioMetadataReader newMetadataReader() {
        AudioMetadataReader reader = new AudioMetadataReader();
        ReflectionTestUtils.setField(reader, "perFileTimeoutSeconds", 30);
        ReflectionTestUtils.setField(reader, "fileReaderThreads", 1);
        reader.init();
        return reader;
    }

    static CoverArtHandler newCoverArtHandler(CoverArtRepository coverArtRepository,
                                              int maxBytesPerImage, long maxBytesPerUser) {
        return newCoverArtHandler(coverArtRepository, maxBytesPerImage, maxBytesPerUser, 0L);
    }

    static CoverArtHandler newCoverArtHandler(CoverArtRepository coverArtRepository,
                                              int maxBytesPerImage, long maxBytesPerUser,
                                              long maxBytesGlobal) {
        CoverArtHandler handler = new CoverArtHandler(coverArtRepository);
        ReflectionTestUtils.setField(handler, "maxBytesPerImage", maxBytesPerImage);
        ReflectionTestUtils.setField(handler, "maxBytesPerUser", maxBytesPerUser);
        ReflectionTestUtils.setField(handler, "maxBytesGlobal", maxBytesGlobal);
        return handler;
    }

    static MusicScannerService newScanner(MusicCatalogService catalogService,
                                          MusicFileRepository musicFileRepository,
                                          ScanJobRepository scanJobRepository,
                                          UserRepository userRepository,
                                          ScanProgressEmitter progressEmitter,
                                          ScanPathValidator pathValidator,
                                          AudioMetadataReader metadataReader,
                                          CoverArtHandler coverArtHandler) {
        MusicScannerService service = new MusicScannerService(
                catalogService, musicFileRepository, scanJobRepository, userRepository,
                progressEmitter, pathValidator, metadataReader, coverArtHandler, new FileHasher(),
                new LibraryStatsCache(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "maxDepth", 20);
        ReflectionTestUtils.setField(service, "hardMaxDepth", 50);
        ReflectionTestUtils.setField(service, "batchSize", 200);
        ReflectionTestUtils.setField(service, "scanTimeoutMinutes", 5);
        ReflectionTestUtils.setField(service, "supportedExtensionsConfig", ".mp3,.m4a,.flac");
        service.init();
        return service;
    }
}
