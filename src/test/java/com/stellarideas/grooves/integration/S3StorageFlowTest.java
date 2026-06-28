package com.stellarideas.grooves.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.stellarideas.grooves.controller.LibraryController;
import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.ScanJob;
import com.stellarideas.grooves.model.User;
import com.stellarideas.grooves.repository.MusicFileRepository;
import com.stellarideas.grooves.repository.UserRepository;
import com.stellarideas.grooves.service.MusicScannerService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
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

/**
 * End-to-end: boot the app with {@code storage.type=s3} against LocalStack +
 * Mongo, scan a bucket, and confirm a track is indexed from object storage and
 * streamed via a presigned redirect. Named *Test (not *IT) so surefire runs it;
 * skipped where Docker is unavailable.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class S3StorageFlowTest {

    private static final String BUCKET = "sg-flow-test";
    private static final String KEY = "AC_DC/Test Album/track.mp3";

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

    @Container
    static final LocalStackContainer LOCALSTACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
                    .withServices(LocalStackContainer.Service.S3);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("stellar.grooves.jwtSecret", () ->
                "dGVzdC1zZWNyZXQta2V5LWZvci1pbnRlZ3JhdGlvbi10ZXN0cy1vbmx5LXBsZWFzZS1jaGFuZ2U=");
        registry.add("stellar.grooves.storage.type", () -> "s3");
        registry.add("stellar.grooves.storage.s3.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("stellar.grooves.storage.s3.region", LOCALSTACK::getRegion);
        registry.add("stellar.grooves.storage.s3.bucket", () -> BUCKET);
        registry.add("stellar.grooves.storage.s3.access-key", LOCALSTACK::getAccessKey);
        registry.add("stellar.grooves.storage.s3.secret-key", LOCALSTACK::getSecretKey);
    }

    @Autowired MusicScannerService scannerService;
    @Autowired MusicFileRepository musicFileRepository;
    @Autowired UserRepository userRepository;
    @Autowired LibraryController libraryController;

    private User user;

    @BeforeEach
    void setUp() throws Exception {
        musicFileRepository.deleteAll();
        userRepository.deleteAll();

        S3Client s3 =
                S3Client.builder()
                        .region(Region.of(LOCALSTACK.getRegion()))
                        .endpointOverride(LOCALSTACK.getEndpoint())
                        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                        .credentialsProvider(
                                StaticCredentialsProvider.create(
                                        AwsBasicCredentials.create(
                                                LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())))
                        .build();
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
            // bucket persists across tests in the shared container
        }
        byte[] mp3 = getClass().getResourceAsStream("/audio/sample.mp3").readAllBytes();
        s3.putObject(PutObjectRequest.builder().bucket(BUCKET).key(KEY).build(), RequestBody.fromBytes(mp3));

        user = new User();
        user.setUsername("s3tester");
        user.setPassword("x");
        user.setEmail("s3@test.local");
        user = userRepository.save(user);
    }

    @Test
    void scansBucketAndStreamsViaPresignedRedirect() throws Exception {
        scannerService.scanDirectorySync(user, "ignored", ScanJob.Type.MANUAL);

        List<MusicFile> files = musicFileRepository.findByUserId(user.getId());
        assertThat(files).hasSize(1);
        MusicFile track = files.get(0);
        assertThat(track.getSourceType()).isEqualTo("s3");
        assertThat(track.getStorageKey()).isEqualTo(KEY);
        assertThat(track.getTitle()).isEqualTo("Test Track");
        assertThat(track.getArtist()).isEqualTo("AC/DC");

        ResponseEntity<?> response = libraryController.streamFile(user, track.getId(), new HttpHeaders());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).contains(BUCKET).contains("X-Amz-Signature");
    }

    @Test
    void rescanSkipsAlreadyIndexedObjects() throws Exception {
        scannerService.scanDirectorySync(user, "ignored", ScanJob.Type.MANUAL);
        var second = scannerService.scanDirectorySync(user, "ignored", ScanJob.Type.MANUAL);
        assertThat(second.getSaved()).isZero();
        assertThat(second.getSkipped()).isEqualTo(1);
        assertThat(musicFileRepository.findByUserId(user.getId())).hasSize(1);
    }
}
