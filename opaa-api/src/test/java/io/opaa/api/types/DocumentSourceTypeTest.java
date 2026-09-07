package io.opaa.api.types;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The two properties every consumer derives from a source type - remote or local, run or origin
 * only - stated once here, so a consumer never enumerates the values itself.
 */
class DocumentSourceTypeTest {

  @Test
  void theConnectorSourcesAreRemoteAndTheFileSourcesAreLocal() {
    assertThat(Arrays.stream(DocumentSourceType.values()).filter(DocumentSourceType::isRemote))
        .containsExactlyInAnyOrder(
            DocumentSourceType.HTTP_DIRECTORY,
            DocumentSourceType.RSS_FEED,
            DocumentSourceType.CONFLUENCE,
            DocumentSourceType.S3);
    assertThat(DocumentSourceType.FILESYSTEM.isRemote()).isFalse();
    assertThat(DocumentSourceType.UPLOAD.isRemote()).isFalse();
  }

  @Test
  void onlyUploadHasNoIndexingRun() {
    assertThat(Arrays.stream(DocumentSourceType.values()).filter(type -> !type.hasIndexingRun()))
        .containsExactly(DocumentSourceType.UPLOAD);
  }

  @Test
  void onlyAnHttpAddressedRemoteTypeHasADeepLink() {
    // s3://bucket/key is an identity, not an address a browser opens (ADR-0027, Entscheidung 5)
    assertThat(Arrays.stream(DocumentSourceType.values()).filter(DocumentSourceType::hasDeepLink))
        .containsExactlyInAnyOrder(
            DocumentSourceType.HTTP_DIRECTORY,
            DocumentSourceType.RSS_FEED,
            DocumentSourceType.CONFLUENCE);
    assertThat(Arrays.stream(DocumentSourceType.values()).filter(DocumentSourceType::hasDeepLink))
        .allMatch(DocumentSourceType::isRemote);
  }

  @Test
  void everyRemoteTypeHasARun() {
    // the bytes of a remote document are reachable again only by a connector run
    assertThat(Arrays.stream(DocumentSourceType.values()).filter(DocumentSourceType::isRemote))
        .allMatch(DocumentSourceType::hasIndexingRun);
  }
}
