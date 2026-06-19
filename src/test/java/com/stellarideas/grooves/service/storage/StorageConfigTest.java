package com.stellarideas.grooves.service.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StorageConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(StorageConfig.class);

    @Test
    void usesLocalFileSourceByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(FileSource.class);
            assertThat(ctx.getBean(FileSource.class)).isInstanceOf(LocalFileSource.class);
        });
    }

    @Test
    void usesLocalFileSourceWhenTypeLocal() {
        runner.withPropertyValues("stellar.grooves.storage.type=local").run(ctx -> {
            assertThat(ctx).hasSingleBean(FileSource.class);
            assertThat(ctx.getBean(FileSource.class)).isInstanceOf(LocalFileSource.class);
        });
    }

    @Test
    void s3TypeWithBucketUsesObjectStorage() {
        runner.withPropertyValues(
                        "stellar.grooves.storage.type=s3",
                        "stellar.grooves.storage.s3.bucket=my-bucket",
                        "stellar.grooves.storage.s3.access-key=ak",
                        "stellar.grooves.storage.s3.secret-key=sk")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FileSource.class);
                    assertThat(ctx.getBean(FileSource.class)).isInstanceOf(ObjectStorageFileSource.class);
                    assertThat(ctx.getBean(FileSource.class).usesObjectKeys()).isTrue();
                });
    }

    @Test
    void s3TypeWithoutBucketFailsFast() {
        runner.withPropertyValues("stellar.grooves.storage.type=s3").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
        });
    }

    @Test
    void bindsS3Properties() {
        runner.withPropertyValues(
                        "stellar.grooves.storage.type=local",
                        "stellar.grooves.storage.s3.bucket=my-bucket",
                        "stellar.grooves.storage.s3.region=us-west-2",
                        "stellar.grooves.storage.s3.presign-ttl-seconds=120")
                .run(ctx -> {
                    StorageProperties props = ctx.getBean(StorageProperties.class);
                    assertThat(props.getType()).isEqualTo("local");
                    assertThat(props.getS3().getBucket()).isEqualTo("my-bucket");
                    assertThat(props.getS3().getRegion()).isEqualTo("us-west-2");
                    assertThat(props.getS3().getPresignTtlSeconds()).isEqualTo(120L);
                });
    }
}
