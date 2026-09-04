package io.opaa.indexing.source.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.ConfluenceEdition;
import org.junit.jupiter.api.Test;

class ConfluenceConnectionTest {

  @Test
  void cloudAddressLosesWikiSuffixAndTrailingSlash() {
    assertThat(
            ConfluenceConnection.normalizeBaseUrl(
                    "HTTPS://Site.atlassian.net/wiki/", ConfluenceEdition.CLOUD)
                .toString())
        .isEqualTo("https://site.atlassian.net");
    assertThat(
            ConfluenceConnection.normalizeBaseUrl(
                    "https://docs.example.org", ConfluenceEdition.CLOUD)
                .toString())
        .isEqualTo("https://docs.example.org");
  }

  @Test
  void dataCenterAddressKeepsItsContextPath() {
    assertThat(
            ConfluenceConnection.normalizeBaseUrl(
                    "https://wiki.example.org:8443/confluence/", ConfluenceEdition.DATA_CENTER)
                .toString())
        .isEqualTo("https://wiki.example.org:8443/confluence");
    assertThat(
            ConfluenceConnection.normalizeBaseUrl(
                    "https://wiki.example.org/wiki", ConfluenceEdition.DATA_CENTER)
                .toString())
        .as("for Data Center, /wiki is an ordinary context path")
        .isEqualTo("https://wiki.example.org/wiki");
  }

  @Test
  void rejectsUnusableAddresses() {
    assertThatThrownBy(() -> ConfluenceConnection.normalizeBaseUrl("", ConfluenceEdition.CLOUD))
        .isInstanceOf(ConfluenceConnection.InvalidBaseUrlException.class)
        .hasMessageContaining("erforderlich");
    assertThatThrownBy(
            () ->
                ConfluenceConnection.normalizeBaseUrl(
                    "ftp://wiki.example.org", ConfluenceEdition.CLOUD))
        .isInstanceOf(ConfluenceConnection.InvalidBaseUrlException.class)
        .hasMessageContaining("http");
    assertThatThrownBy(
            () ->
                ConfluenceConnection.normalizeBaseUrl(
                    "https://a:b@wiki.example.org", ConfluenceEdition.CLOUD))
        .isInstanceOf(ConfluenceConnection.InvalidBaseUrlException.class)
        .hasMessageContaining("Zugangsdaten");
    assertThatThrownBy(
            () -> ConfluenceConnection.normalizeBaseUrl("https://", ConfluenceEdition.DATA_CENTER))
        .isInstanceOf(ConfluenceConnection.InvalidBaseUrlException.class);
  }
}
