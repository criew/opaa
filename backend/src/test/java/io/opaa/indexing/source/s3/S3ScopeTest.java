package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link S3Scope} (ADR-0027, Entscheidung 2): bucket names follow the AWS rules MinIO and Ceph
 * adopt, the prefix is normalised to a folder boundary, and two scopes of one library must not
 * overlap - the same key under two scopes would collide on {@code uk_documents_library_path}.
 */
class S3ScopeTest {

  @Test
  void normalizesThePrefixToAFolderBoundary() {
    assertThat(S3Scope.of("dokumente", "2025/protokolle").prefix()).isEqualTo("2025/protokolle/");
    assertThat(S3Scope.of("dokumente", "/2025/protokolle/").prefix()).isEqualTo("2025/protokolle/");
    assertThat(S3Scope.of("dokumente", "  ").prefix()).isEmpty();
    assertThat(S3Scope.of("dokumente", null).prefix()).isEmpty();
    assertThat(S3Scope.of("dokumente", "/").prefix()).isEmpty();
  }

  @Test
  void keyNamesBucketAndPrefix() {
    assertThat(S3Scope.of("dokumente", "2025/").key()).isEqualTo("dokumente/2025/");
    assertThat(S3Scope.of("dokumente", "").key()).isEqualTo("dokumente");
  }

  @Test
  void containsIsAPrefixMatchOnTheFolderBoundary() {
    S3Scope scope = S3Scope.of("dokumente", "2025/protokolle");
    assertThat(scope.contains("2025/protokolle/sitzung.pdf")).isTrue();
    assertThat(scope.contains("2025/protokolle-alt/sitzung.pdf")).isFalse();
    assertThat(scope.contains("2024/protokolle/sitzung.pdf")).isFalse();
    assertThat(S3Scope.of("dokumente", "").contains("beliebig.pdf")).isTrue();
  }

  @Test
  void overlappingScopesOfTheSameBucketAreRejectedTogether() {
    S3Scope whole = S3Scope.of("dokumente", "");
    S3Scope nested = S3Scope.of("dokumente", "2025/");
    S3Scope sibling = S3Scope.of("dokumente", "2024/");
    S3Scope otherBucket = S3Scope.of("satzungen", "2025/");

    assertThat(whole.overlaps(nested)).isTrue();
    assertThat(nested.overlaps(whole)).isTrue();
    assertThat(nested.overlaps(sibling)).isFalse();
    assertThat(nested.overlaps(otherBucket)).isFalse();
    assertThat(nested.overlaps(S3Scope.of("dokumente", "2025/"))).isTrue();

    assertThatThrownBy(() -> S3Scope.requireDisjoint(List.of(sibling, whole)))
        .isInstanceOf(S3Scope.InvalidS3ScopeException.class)
        .hasMessageContaining("dokumente/2024/")
        .hasMessageContaining("dokumente");
    S3Scope.requireDisjoint(List.of(nested, sibling, otherBucket));
  }

  @Test
  void aSelectionNeedsOneToFiftyDisjointScopes() {
    assertThatThrownBy(() -> S3Scope.requireValidSelection(List.of()))
        .isInstanceOf(S3Scope.InvalidS3ScopeException.class)
        .hasMessageContaining("Mindestens ein");
    List<S3Scope> tooMany = new java.util.ArrayList<>();
    for (int i = 0; i <= S3Scope.MAX_PER_LIBRARY; i++) {
      tooMany.add(S3Scope.of("dokumente", "p" + i + "/"));
    }
    assertThatThrownBy(() -> S3Scope.requireValidSelection(tooMany))
        .isInstanceOf(S3Scope.InvalidS3ScopeException.class)
        .hasMessageContaining("50");
    S3Scope.requireValidSelection(tooMany.subList(0, S3Scope.MAX_PER_LIBRARY));
    assertThatThrownBy(
            () ->
                S3Scope.requireValidSelection(
                    List.of(S3Scope.of("dokumente", "a/"), S3Scope.of("dokumente", "a/b/"))))
        .isInstanceOf(S3Scope.InvalidS3ScopeException.class)
        .hasMessageContaining("überschneiden");
  }

  @ParameterizedTest
  @ValueSource(strings = {"dokumente", "abc", "my.bucket-1", "a1b2c3"})
  void acceptsValidBucketNames(String bucket) {
    assertThat(S3Scope.of(bucket, "").bucket()).isEqualTo(bucket);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "ab",
        "Dokumente",
        "dok_umente",
        "-dokumente",
        "dokumente-",
        "192.168.0.1",
        "a..b",
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      })
  void rejectsInvalidBucketNamesWithAGermanMessage(String bucket) {
    assertThatThrownBy(() -> S3Scope.of(bucket, ""))
        .isInstanceOf(S3Scope.InvalidS3ScopeException.class)
        .hasMessageContaining("Bucket");
  }

  @Test
  void rejectsAPrefixThatCannotBeAKey() {
    assertThatThrownBy(() -> S3Scope.of("dokumente", "a\u0000b"))
        .isInstanceOf(S3Scope.InvalidS3ScopeException.class);
    assertThatThrownBy(() -> S3Scope.of("dokumente", "x".repeat(1025)))
        .isInstanceOf(S3Scope.InvalidS3ScopeException.class)
        .hasMessageContaining("1024");
  }
}
