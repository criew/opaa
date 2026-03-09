package io.opaa.indexing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlIndexingExecutorTest {

  @Test
  void hasFileExtension_returnsTrueForUrlsWithExtension() {
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/files/report.pdf"))
        .isTrue();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/docs/readme.txt"))
        .isTrue();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/archive.tar.gz")).isTrue();
  }

  @Test
  void hasFileExtension_returnsFalseForDirectoryUrls() {
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/files/")).isFalse();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/docs")).isFalse();
  }

  @Test
  void hasFileExtension_stripsQueryStringBeforeChecking() {
    assertThat(
            UrlIndexingExecutor.hasFileExtension("https://example.com/files?sort=name&order=asc"))
        .isFalse();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/report.pdf?token=abc123"))
        .isTrue();
  }

  @Test
  void hasFileExtension_doesNotThrowOnVeryLongUrl() {
    String longParam = "x".repeat(100_000);
    String longUrl = "https://example.com/files?" + longParam;
    assertThat(UrlIndexingExecutor.hasFileExtension(longUrl)).isFalse();
  }

  @Test
  void hasFileExtension_stripsFragmentBeforeChecking() {
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/docs#section")).isFalse();
    assertThat(UrlIndexingExecutor.hasFileExtension("https://example.com/doc.pdf#page=2")).isTrue();
  }
}
