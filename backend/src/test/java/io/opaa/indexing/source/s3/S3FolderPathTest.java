package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link S3FolderPath} (ADR-0027, Entscheidung 5): the key's folder segments below the scope
 * prefix, under a segment chain of bucket and prefix segments when the library has more than one
 * scope, never a composite {@code bucket/prefix} name; a segment no folder row can carry sends the
 * object to the root, a chain deeper than the folder limit is cut at the deepest allowed folder.
 */
class S3FolderPathTest {

  private static final S3Scope SCOPE = S3Scope.of("dokumente", "2025/protokolle/");

  @Test
  void aSingleScopeMakesItsPrefixTheRoot() {
    assertThat(S3FolderPath.of(SCOPE, "2025/protokolle/q1/sitzung.pdf", false).segments())
        .containsExactly("q1");
    assertThat(S3FolderPath.of(SCOPE, "2025/protokolle/sitzung.pdf", false).segments()).isEmpty();
    assertThat(S3FolderPath.of(S3Scope.of("dokumente", ""), "a/b/c.pdf", false).segments())
        .containsExactly("a", "b");
  }

  @Test
  void severalScopesPutBucketAndPrefixSegmentsAboveTheKeysFolders() {
    S3FolderPath path = S3FolderPath.of(SCOPE, "2025/protokolle/q1/sitzung.pdf", true);

    assertThat(path.segments()).containsExactly("dokumente", "2025", "protokolle", "q1");
    assertThat(S3FolderPath.of(S3Scope.of("satzungen", ""), "haupt.pdf", true).segments())
        .containsExactly("satzungen");
  }

  @Test
  void aSegmentNoFolderRowCanCarrySendsTheObjectToTheRoot() {
    S3FolderPath backslash = S3FolderPath.of(SCOPE, "2025/protokolle/a\\b/x.pdf", false);
    assertThat(backslash.segments()).isEmpty();
    assertThat(backslash.rejectedSegment()).isEqualTo("a\\b");
    assertThat(S3FolderPath.of(SCOPE, "2025/protokolle/../x.pdf", false).rejectedSegment())
        .isEqualTo("..");
    assertThat(
            S3FolderPath.of(SCOPE, "2025/protokolle/" + "x".repeat(256) + "/y.pdf", false)
                .rejected())
        .isTrue();
    // an empty segment ("//") is skipped, not rejected - S3 keys carry no normalisation
    assertThat(S3FolderPath.of(SCOPE, "2025/protokolle/a//b/x.pdf", false).segments())
        .containsExactly("a", "b");
  }

  @Test
  void aChainDeeperThanTheFolderLimitIsCutAtTheDeepestAllowedFolder() {
    String key =
        "2025/protokolle/"
            + String.join("/", List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"))
            + "/x.pdf";

    S3FolderPath path = S3FolderPath.of(SCOPE, key, false);

    assertThat(path.segments()).hasSize(S3FolderPath.MAX_DEPTH).startsWith("a", "b").endsWith("j");
    assertThat(path.truncated()).isTrue();
    assertThat(S3FolderPath.of(SCOPE, "2025/protokolle/a/x.pdf", false).truncated()).isFalse();
  }
}
