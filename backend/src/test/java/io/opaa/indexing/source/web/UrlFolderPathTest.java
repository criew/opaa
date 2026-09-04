package io.opaa.indexing.source.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit coverage of the URL-to-folder-path derivation #1277 mirrors a web directory with. */
class UrlFolderPathTest {

  private static final String START = "http://host/dokumente/";

  @Test
  void derivesTheSegmentsBetweenTheStartUrlAndTheFileName() {
    UrlFolderPath path = UrlFolderPath.of(START, START + "2025/protokolle/a.pdf");

    assertThat(path.segments()).containsExactly("2025", "protokolle");
    assertThat(path.rejected()).isFalse();
  }

  @Test
  void aFileDirectlyBelowTheStartUrlBelongsToTheRoot() {
    assertThat(UrlFolderPath.of(START, START + "a.pdf").segments()).isEmpty();
  }

  @Test
  void aStartUrlWithoutATrailingSlashIsTreatedAsADirectoryPrefix() {
    // AutoindexCrawlerService#resolveUrl appends to the start URL the same way.
    UrlFolderPath path = UrlFolderPath.of("http://host/dokumente", START + "2025/a.pdf");

    assertThat(path.segments()).containsExactly("2025");
  }

  @Test
  void queryStringAndFragmentAreNotPartOfThePath() {
    UrlFolderPath path = UrlFolderPath.of(START, START + "2025/a.pdf?version=2#seite3");

    assertThat(path.segments()).containsExactly("2025");
  }

  @Test
  void aQueryStringOnTheStartUrlIsStrippedToo() {
    UrlFolderPath path = UrlFolderPath.of(START + "?C=N;O=D", START + "2025/a.pdf");

    assertThat(path.segments()).containsExactly("2025");
  }

  @Test
  void percentEncodedSegmentsAreDecoded() {
    UrlFolderPath path = UrlFolderPath.of(START, START + "Verg%C3%BCtung/lohn.pdf");

    assertThat(path.segments()).containsExactly("Vergütung");
  }

  @Test
  void aLiteralPlusStaysAPlusInsteadOfBecomingASpace() {
    // A path segment is not application/x-www-form-urlencoded - "+" carries no space meaning here.
    UrlFolderPath path = UrlFolderPath.of(START, START + "bericht+final/a.pdf");

    assertThat(path.segments()).containsExactly("bericht+final");
  }

  @Test
  void aRelativeTraversalIsCollapsedBeforeComparing() {
    // AutoindexCrawlerService#resolveUrl concatenates naively, so "../" reaches this method intact.
    UrlFolderPath path = UrlFolderPath.of(START, START + "2025/../2024/a.pdf");

    assertThat(path.segments()).containsExactly("2024");
  }

  @Test
  void anEntryOutsideTheStartUrlBelongsToTheRootWithoutBeingRejected() {
    UrlFolderPath path = UrlFolderPath.of(START, "http://host/anderes/a.pdf");

    assertThat(path.segments()).isEmpty();
    assertThat(path.rejected()).isFalse();
  }

  @Test
  void aDoubleSlashIsCollapsedRatherThanBecomingAnEmptySegment() {
    UrlFolderPath path = UrlFolderPath.of(START, START + "2025//a.pdf");

    assertThat(path.segments()).containsExactly("2025");
    assertThat(path.rejected()).isFalse();
  }

  @Test
  void aSegmentDecodingToWhitespaceOnlyIsRejected() {
    UrlFolderPath path = UrlFolderPath.of(START, START + "%20/a.pdf");

    assertThat(path.rejected()).isTrue();
    assertThat(path.segments()).isEmpty();
  }

  @Test
  void aSegmentDecodingToATraversalIsRejected() {
    UrlFolderPath path = UrlFolderPath.of(START, START + "%2E%2E/a.pdf");

    assertThat(path.rejected()).isTrue();
    assertThat(path.rejectedSegment()).isEqualTo("%2E%2E");
    assertThat(path.segments()).isEmpty();
  }

  @Test
  void aSegmentDecodingToASingleDotIsRejected() {
    assertThat(UrlFolderPath.of(START, START + "%2E/a.pdf").rejected()).isTrue();
  }

  @Test
  void aSegmentDecodingToAPathSeparatorIsRejected() {
    assertThat(UrlFolderPath.of(START, START + "a%2Fb/x.pdf").rejected()).isTrue();
    assertThat(UrlFolderPath.of(START, START + "a%5Cb/x.pdf").rejected()).isTrue();
  }

  @Test
  void aSegmentDecodingToANulByteIsRejected() {
    assertThat(UrlFolderPath.of(START, START + "a%00b/x.pdf").rejected()).isTrue();
  }

  @Test
  void aSegmentLongerThanTheColumnWidthIsRejected() {
    // library_folders.name holds 255 characters - a longer segment would fail the insert and abort
    // the whole entry instead of leaving one document at the root.
    String tooLong = "a".repeat(256);

    assertThat(UrlFolderPath.of(START, START + tooLong + "/x.pdf").rejected()).isTrue();
    assertThat(UrlFolderPath.of(START, START + "a".repeat(255) + "/x.pdf").segments())
        .containsExactly("a".repeat(255));
  }

  @Test
  void aRejectionDiscardsTheAlreadyDerivedSegmentsToo() {
    // Half a path is worse than none: the entry belongs to the root, not into "2025".
    UrlFolderPath path = UrlFolderPath.of(START, START + "2025/%2E%2E/a.pdf");

    assertThat(path.rejected()).isTrue();
    assertThat(path.segments()).isEqualTo(List.of());
  }
}
