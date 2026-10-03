package io.opaa.indexing.source.smb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which listed entries are links or offline files, from their attributes and reparse tag. */
class SmbShareClientItemTest {

  private static final long REPARSE_POINT = 0x400;
  private static final long DIRECTORY = 0x10;

  @Test
  void symbolicLinksJunctionsAndDfsLinksAreLinks() {
    assertThat(item(REPARSE_POINT, 0xA000000CL).link()).as("symbolic link").isTrue();
    assertThat(item(REPARSE_POINT | DIRECTORY, 0xA0000003L).link()).as("junction").isTrue();
    assertThat(item(REPARSE_POINT | DIRECTORY, 0x8000000AL).link()).as("DFS link").isTrue();
  }

  @Test
  void otherReparsePointsAreReadLikeFiles() {
    assertThat(item(REPARSE_POINT, 0x80000013L).link()).as("deduplicated file").isFalse();
    assertThat(item(0x20, 0).link()).isFalse();
    // without the attribute the field is the extended-attribute size, not a tag
    assertThat(item(0x20, 0xA000000CL).link()).isFalse();
  }

  @Test
  void offlineAndCloudPlaceholderFilesAreOffline() {
    assertThat(item(0x1000, 0).offline()).as("offline").isTrue();
    assertThat(item(0x00400000, 0).offline()).as("recall on data access").isTrue();
    assertThat(item(0x00040000, 0).offline()).as("recall on open").isTrue();
    assertThat(item(0x20, 0).offline()).isFalse();
  }

  private static SmbShareClient.Item item(long attributes, long reparseTag) {
    return new SmbShareClient.Item(
        "x", (attributes & DIRECTORY) != 0, 1, 1, attributes, reparseTag, 7);
  }
}
