package com.stellarideas.grooves.service.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Testcontainers
class ObjectStorageFileSourceIT {

    private static final String BUCKET = "test-library";
    private static final byte[] SONG = "fake-mp3-bytes-0123456789".getBytes(StandardCharsets.UTF_8);

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.S3);

    static ObjectStorageFileSource source;

    private static StorageProperties.S3 config(long maxTagBytes) {
        StorageProperties.S3 cfg = new StorageProperties.S3();
        cfg.setEndpoint(LOCALSTACK.getEndpoint().toString());
        cfg.setRegion(LOCALSTACK.getRegion());
        cfg.setAccessKey(LOCALSTACK.getAccessKey());
        cfg.setSecretKey(LOCALSTACK.getSecretKey());
        cfg.setBucket(BUCKET);
        cfg.setPresignTtlSeconds(300);
        cfg.setMaxTagBytes(maxTagBytes);
        return cfg;
    }

    @BeforeAll
    static void seedBucket() {
        S3Client admin =
                S3Client.builder()
                        .region(Region.of(LOCALSTACK.getRegion()))
                        .endpointOverride(LOCALSTACK.getEndpoint())
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                        .build();
        admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        admin.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("AC_DC/Highway/Touch Too Much.mp3").build(),
                RequestBody.fromBytes(SONG));
        admin.putObject(
                PutObjectRequest.builder().bucket(BUCKET).key("Metallica/Ride/Fade To Black.mp3").build(),
                RequestBody.fromBytes("another-song".getBytes(StandardCharsets.UTF_8)));

        source = ObjectStorageFileSource.from(config(50_000_000));
    }

    @Test
    void listsAllObjects() {
        List<RemoteObject> objects = source.list();
        assertEquals(2, objects.size());
        RemoteObject song =
                objects.stream()
                        .filter(o -> o.key().equals("AC_DC/Highway/Touch Too Much.mp3"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(SONG.length, song.size());
        assertTrue(song.etag() != null && !song.etag().contains("\""));
    }

    @Test
    void downloadsObjectToTemp() throws IOException {
        Path tmp = source.downloadToTemp("AC_DC/Highway/Touch Too Much.mp3");
        try {
            assertArrayEquals(SONG, Files.readAllBytes(tmp));
            assertTrue(tmp.getFileName().toString().endsWith(".mp3"));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void rejectsObjectsOverMaxTagBytes() {
        ObjectStorageFileSource tiny = ObjectStorageFileSource.from(config(5));
        assertThrows(IOException.class, () -> tiny.downloadToTemp("AC_DC/Highway/Touch Too Much.mp3"));
    }

    @Test
    void presignedUrlStreamsWithRangeSupport() throws Exception {
        String url = source.presignGet("AC_DC/Highway/Touch Too Much.mp3");
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<byte[]> full =
                http.send(
                        HttpRequest.newBuilder(URI.create(url)).GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, full.statusCode());
        assertArrayEquals(SONG, full.body());

        HttpResponse<byte[]> ranged =
                http.send(
                        HttpRequest.newBuilder(URI.create(url)).header("Range", "bytes=0-3").GET().build(),
                        HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(206, ranged.statusCode());
        assertEquals(4, ranged.body().length);
    }

    @Test
    void existsReflectsPresence() {
        assertTrue(source.exists("Metallica/Ride/Fade To Black.mp3"));
        assertFalse(source.exists("nope/missing.mp3"));
    }
}
