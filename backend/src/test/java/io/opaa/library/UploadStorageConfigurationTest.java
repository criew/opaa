package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The adapter selection of {@code opaa.upload.store} (ADR-0030, Entscheidung 1). An operator who
 * names a storage that does not exist must find out at startup, not when the first upload silently
 * lands somewhere else.
 */
class UploadStorageConfigurationTest {

  private final UploadStorageConfiguration configuration = new UploadStorageConfiguration();

  @Test
  void anUnconfiguredStoreIsTheFilesystem() {
    UploadedOriginalStore store = configuration.uploadedOriginalStore(properties(null));

    assertThat(store).isInstanceOf(FilesystemUploadedOriginalStore.class);
  }

  @Test
  void theFilesystemStoreCanBeNamedExplicitly() {
    assertThat(configuration.uploadedOriginalStore(properties("filesystem")))
        .isInstanceOf(FilesystemUploadedOriginalStore.class);
  }

  @Test
  void anUnknownStoreRefusesTheStart() {
    assertThatThrownBy(() -> configuration.uploadedOriginalStore(properties("s3")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("opaa.upload.store")
        .hasMessageContaining("s3");
  }

  private static UploadProperties properties(String store) {
    return new UploadProperties("./uploads", store, 1024L, null, 0);
  }
}
