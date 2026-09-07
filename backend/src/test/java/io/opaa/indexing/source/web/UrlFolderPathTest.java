package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.source.SourceFolderPath;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The URL-to-folder-path derivation a web directory mirror uses: which part of the URL becomes
 * segments, how they are decoded, and that a segment is judged decoded but reported raw. Which
 * names are rejected is {@link SourceFolderPath}'s own contract.
 */
class UrlFolderPathTest {

  private static final String START = "http://host/dokumente/";

  @Test
  void derivesTheSegmentsBetweenTheStartUrlAndTheFileName() {
    SourceFolderPath path = UrlFolderPath.of(START, START + "2025/protokolle/a.pdf");

    assertThat(path.segments()).containsExactly("2025", "protokolle");
    assertThat(path.rejected()).isFalse();
    assertThat(path.truncated()).isFalse();
  }

  @Test
  void aFileDirectlyBelowTheStartUrlBelongsToTheRoot() {
    assertThat(UrlFolderPath.of(START, START + "a.pdf").segments()).isEmpty();
  }

  @Test
  void aStartUrlWithoutATrailingSlashIsTreatedAsADirectoryPrefix() {
    // AutoindexCrawlerService#resolveUrl appends to the start URL the same way.
    SourceFolderPath path = UrlFolderPath.of("http://host/dokumente", START + "2025/a.pdf");

    assertThat(path.segments()).containsExactly("2025");
  }

  @Test
  void queryStringAndFragmentAreNotPartOfThePath() {
    SourceFolderPath path = UrlFolderPath.of(START, START + "2025/a.pdf?version=2#seite3");

    assertThat(path.segments()).containsExactly("2025");
  }

  @Test
  void aQueryStringOnTheStartUrlIsStrippedToo() {
    SourceFolderPath path = UrlFolderPath.of(START + "?C=N;O=D", START + "2025/a.pdf");

    assertThat(path.segments()).containsExactly("2025");
  }

  @Test
  void percentEncodedSegmentsAreDecoded() {
    SourceFolderPath path = UrlFolderPath.of(START, START + "Verg%C3%BCtung/lohn.pdf");

    assertThat(path.segments()).containsExactly("Vergütung");
  }

  @Test
  void aLiteralPlusStaysAPlusInsteadOfBecomingASpace() {
    // A path segment is not application/x-www-form-urlencoded - "+" carries no space meaning here.
    SourceFolderPath path = UrlFolderPath.of(START, START + "bericht+final/a.pdf");

    assertThat(path.segments()).containsExactly("bericht+final");
  }

  @Test
  void aRelativeTraversalIsCollapsedBeforeComparing() {
    // AutoindexCrawlerService#resolveUrl concatenates naively, so "../" reaches this method intact.
    SourceFolderPath path = UrlFolderPath.of(START, START + "2025/../2024/a.pdf");

    assertThat(path.segments()).containsExactly("2024");
  }

  @Test
  void anEntryOutsideTheStartUrlBelongsToTheRootWithoutBeingRejected() {
    SourceFolderPath path = UrlFolderPath.of(START, "http://host/anderes/a.pdf");

    assertThat(path.segments()).isEmpty();
    assertThat(path.rejected()).isFalse();
  }

  @Test
  void aDoubleSlashIsCollapsedRatherThanBecomingAnEmptySegment() {
    SourceFolderPath path = UrlFolderPath.of(START, START + "2025//a.pdf");

    assertThat(path.segments()).containsExactly("2025");
    assertThat(path.rejected()).isFalse();
  }

  @Test
  void aSegmentIsJudgedDecodedButReportedRaw() {
    // "%2E%2E" is a harmless literal until decoded; the rejection names it as the URL carried it.
    SourceFolderPath path = UrlFolderPath.of(START, START + "2025/%2E%2E/a.pdf");

    assertThat(path.rejected()).isTrue();
    assertThat(path.rejectedSegment()).isEqualTo("%2E%2E");
    // Half a path is worse than none: the entry belongs to the root, not into "2025".
    assertThat(path.segments()).isEqualTo(List.of());
  }

  @Test
  void aSegmentDecodingToASeparatorANulByteOrWhitespaceIsRejected() {
    assertThat(UrlFolderPath.of(START, START + "a%2Fb/x.pdf").rejected()).isTrue();
    assertThat(UrlFolderPath.of(START, START + "a%5Cb/x.pdf").rejected()).isTrue();
    assertThat(UrlFolderPath.of(START, START + "a%00b/x.pdf").rejected()).isTrue();
    assertThat(UrlFolderPath.of(START, START + "%20/a.pdf").rejected()).isTrue();
  }

  @Test
  void aDirectoryTreeIsNotCappedAtTheFolderLimit() {
    String deep = "e/".repeat(SourceFolderPath.MAX_DEPTH + 3);

    SourceFolderPath path = UrlFolderPath.of(START, START + deep + "a.pdf");

    assertThat(path.segments()).hasSize(SourceFolderPath.MAX_DEPTH + 3);
    assertThat(path.truncated()).isFalse();
  }
}
