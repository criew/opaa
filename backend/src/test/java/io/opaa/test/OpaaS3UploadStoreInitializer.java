package io.opaa.test;

import io.opaa.indexing.source.s3.MinioFixture;
import java.util.Map;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * Points {@code opaa.upload.store} at a bucket of the JVM-wide {@link MinioFixture} (ADR-0030), so
 * the class that exercises the S3 upload adapter needs no context-splitting
 * {@code @DynamicPropertySource} of its own for the endpoint and credentials the container only
 * knows at runtime.
 *
 * <p>Runs only for {@link OpaaMockedChatModelIntegrationTest}, whose other classes never upload -
 * the adapter swap is inert for them. MinIO itself is started once per JVM and shared with the S3
 * connector's own tests, so this costs no extra container.
 */
final class OpaaS3UploadStoreInitializer
    implements ApplicationContextInitializer<ConfigurableApplicationContext> {

  @Override
  public void initialize(ConfigurableApplicationContext applicationContext) {
    MinioFixture minio = MinioFixture.get();
    applicationContext
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "opaaTestS3UploadStore",
                Map.of(
                    "opaa.upload.store", "s3",
                    "opaa.upload.s3.endpoint", minio.endpoint().toString(),
                    "opaa.upload.s3.bucket", OpaaTestUploadStore.BUCKET,
                    "opaa.upload.s3.access-key", minio.rootCredentials().accessKey(),
                    "opaa.upload.s3.secret-key", minio.rootCredentials().secretKey(),
                    "opaa.upload.s3.temp-directory",
                        OpaaTestDirectory.subdirectory("upload-s3-temp")
                            .toAbsolutePath()
                            .toString())));
  }
}
