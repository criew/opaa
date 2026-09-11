package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The adapter selection of {@code opaa.upload.store} (ADR-0030, Entscheidung 1 and 9). An operator
 * who names a storage that does not exist, or the S3 storage without its required values, must find
 * out at startup - not when the first upload silently lands somewhere else.
 */
class UploadStorageConfigurationTest {

  private final UploadStorageConfiguration configuration = new UploadStorageConfiguration();

  @Test
  void anUnconfiguredStoreIsTheFilesystem() {
    UploadedOriginalStore store =
        configuration.uploadedOriginalStore(properties(null), s3Properties(null));

    assertThat(store).isInstanceOf(FilesystemUploadedOriginalStore.class);
  }

  @Test
  void theFilesystemStoreCanBeNamedExplicitly() {
    assertThat(configuration.uploadedOriginalStore(properties("filesystem"), s3Properties(null)))
        .isInstanceOf(FilesystemUploadedOriginalStore.class);
  }

  @Test
  void theS3StoreNeedsItsRequiredValues() {
    assertThatThrownBy(
            () -> configuration.uploadedOriginalStore(properties("s3"), s3Properties(null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opaa.upload.s3.bucket");
  }

  @Test
  void theS3StoreIsBuiltWithoutContactingTheStore() {
    UploadedOriginalStore store =
        configuration.uploadedOriginalStore(properties("S3"), s3Properties("ablage"));
    try {
      assertThat(store).isInstanceOf(S3UploadedOriginalStore.class);
      assertThat(configuration.uploadStoreHealthIndicator(store))
          .isInstanceOf(UploadStoreHealthIndicator.class);
    } finally {
      ((S3UploadedOriginalStore) store).close();
    }
  }

  @Test
  void anUnknownStoreRefusesTheStart() {
    assertThatThrownBy(
            () -> configuration.uploadedOriginalStore(properties("ftp"), s3Properties(null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opaa.upload.store")
        .hasMessageContaining("ftp")
        .hasMessageContaining("s3");
  }

  @Test
  void theHealthIndicatorRefusesAStoreThatIsNotS3() {
    assertThatThrownBy(
            () ->
                configuration.uploadStoreHealthIndicator(
                    new FilesystemUploadedOriginalStore(properties(null))))
        .isInstanceOf(IllegalStateException.class);
  }

  private static UploadProperties properties(String store) {
    return new UploadProperties("./uploads", store, 1024L, null, 0, 0);
  }

  private static UploadS3Properties s3Properties(String bucket) {
    return new UploadS3Properties(
        "http://localhost:1", null, bucket, null, null, "AKIA", "geheim", null, null);
  }
}
