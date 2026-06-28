package com.stellarideas.grooves.service.storage;

import com.stellarideas.grooves.model.MusicFile;
import com.stellarideas.grooves.model.User;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * Reads a music library from an S3-compatible bucket (AWS S3, Backblaze B2,
 * Wasabi, MinIO). Provides the three capabilities the rest of the app needs:
 *
 * <ul>
 *   <li>{@link #list()} — enumerate objects under the configured prefix (scanning),</li>
 *   <li>{@link #downloadToTemp(String)} — fetch one object to a temp file for tag
 *       reading (the caller deletes it),</li>
 *   <li>{@link #presignGet(String)} — a short-lived presigned URL so a browser
 *       streams the bytes straight from the bucket (never through our servers).</li>
 * </ul>
 *
 * <p>This class is not yet wired into request handling; the scanner and streaming
 * endpoint adopt it in later phases. Build one with {@link #from(StorageProperties.S3)}.</p>
 */
public class ObjectStorageFileSource implements FileSource, AutoCloseable {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final String prefix;
    private final Duration presignTtl;
    private final long maxTagBytes;

    public ObjectStorageFileSource(
            S3Client s3,
            S3Presigner presigner,
            String bucket,
            String prefix,
            Duration presignTtl,
            long maxTagBytes) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : prefix;
        this.presignTtl = presignTtl;
        this.maxTagBytes = maxTagBytes;
    }

    /** Build a source from configuration, honoring a custom endpoint (B2/Wasabi/MinIO). */
    public static ObjectStorageFileSource from(StorageProperties.S3 cfg) {
        StaticCredentialsProvider creds = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey()));
        Region region = Region.of(cfg.getRegion());

        var clientBuilder = S3Client.builder().region(region).credentialsProvider(creds);
        var presignerBuilder = S3Presigner.builder().region(region).credentialsProvider(creds);

        if (cfg.getEndpoint() != null && !cfg.getEndpoint().isBlank()) {
            URI endpoint = URI.create(cfg.getEndpoint());
            // Path-style access is required by most non-AWS S3-compatible providers.
            S3Configuration pathStyle = S3Configuration.builder().pathStyleAccessEnabled(true).build();
            clientBuilder.endpointOverride(endpoint).serviceConfiguration(pathStyle);
            presignerBuilder.endpointOverride(endpoint).serviceConfiguration(pathStyle);
        }

        return new ObjectStorageFileSource(
                clientBuilder.build(),
                presignerBuilder.build(),
                cfg.getBucket(),
                cfg.getPrefix(),
                Duration.ofSeconds(cfg.getPresignTtlSeconds()),
                cfg.getMaxTagBytes());
    }

    /** List every object under the configured prefix (paginated). Skips folder markers. */
    public List<RemoteObject> list() {
        ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket);
        if (!prefix.isBlank()) {
            req.prefix(prefix);
        }
        List<RemoteObject> out = new ArrayList<>();
        s3.listObjectsV2Paginator(req.build())
                .contents()
                .forEach(
                        (S3Object o) -> {
                            if (o.key().endsWith("/")) {
                                return; // pseudo-directory marker
                            }
                            out.add(new RemoteObject(o.key(), o.size(), stripQuotes(o.eTag()), o.lastModified()));
                        });
        return out;
    }

    /**
     * Download an object to a temp file for tag reading. The caller is responsible
     * for deleting the returned path. Objects larger than {@code maxTagBytes} are
     * rejected (we only need the tag regions, not gigabytes of audio).
     */
    public Path downloadToTemp(String key) throws IOException {
        HeadObjectResponse head =
                s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        if (head.contentLength() != null && head.contentLength() > maxTagBytes) {
            throw new IOException(
                    "Object exceeds max tag-read size (" + head.contentLength() + " > " + maxTagBytes + "): " + key);
        }
        Path tmp = Files.createTempFile("sg-tag-", suffixOf(key));
        try (ResponseInputStream<GetObjectResponse> in =
                s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        return tmp;
    }

    /** A short-lived presigned GET URL the browser fetches directly (range-capable). */
    public String presignGet(String key) {
        GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
        GetObjectPresignRequest presign =
                GetObjectPresignRequest.builder().signatureDuration(presignTtl).getObjectRequest(get).build();
        return presigner.presignGetObject(presign).url().toString();
    }

    /** Whether an object exists. */
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    /**
     * Stream a track by handing the browser a short-lived presigned URL — the
     * bytes flow straight from the bucket, never through this server. The track's
     * owner was already verified by the caller; there is no per-user path scoping
     * for object storage.
     */
    @Override
    public StreamResolution resolveStream(MusicFile file, User user) {
        String key = file.getStorageKey();
        if (key == null || key.isBlank()) {
            return StreamResolution.notFound();
        }
        return StreamResolution.redirect(presignGet(key));
    }

    @Override
    public boolean usesObjectKeys() {
        return true;
    }

    /** Release the underlying S3 client + presigner. */
    @Override
    public void close() {
        s3.close();
        presigner.close();
    }

    private static String stripQuotes(String etag) {
        if (etag == null) {
            return null;
        }
        return etag.replace("\"", "");
    }

    private static String suffixOf(String key) {
        int dot = key.lastIndexOf('.');
        int slash = key.lastIndexOf('/');
        return (dot > slash && dot >= 0) ? key.substring(dot) : "";
    }
}
