package io.opaa.indexing.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import io.opaa.test.SourceTypes;
import org.junit.jupiter.api.Test;

/**
 * A source address never carries credentials: it is stored and logged in clear text, the secret
 * belongs into the encrypted credentials of the source.
 */
class ConnectorChecksUserInfoTest {

  private static final String WITH_USER_INFO = "https://petra:GEHEIM@feeds.example.org/rss.xml";

  // regression guard for #3338: user:pass@host in an RSS/HTTP_DIRECTORY address is refused
  @Test
  void aConfigurationWithCredentialsInTheAddressIsRefusedWithoutEchoingThem() {
    assertThatThrownBy(
            () ->
                ConnectorChecks.validateUrlBasedConfiguration(
                    SourceTypes.RSS_FEED, null, WITH_USER_INFO))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("darf keine Zugangsdaten enthalten")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("GEHEIM", "petra"));
  }

  @Test
  void aConnectionTestWithCredentialsInTheAddressIsRefused() {
    assertThatThrownBy(
            () ->
                ConnectorChecks.requireHttpUrl(
                    SourceTypes.HTTP_DIRECTORY,
                    new SourceSettings(null, "http://nutzer@intranet.example/akten/", null, null)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("darf keine Zugangsdaten enthalten");
  }

  @Test
  void credentialsBeforeAHostUriCannotParseAreRefusedToo() {
    assertThatThrownBy(
            () ->
                ConnectorChecks.validateUrlBasedConfiguration(
                    SourceTypes.RSS_FEED, null, "http://user:pass@host_name/feed"))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("darf keine Zugangsdaten enthalten")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("pass@"));
  }

  @Test
  void anAddressWithoutCredentialsPasses() {
    ConnectorChecks.validateUrlBasedConfiguration(
        SourceTypes.RSS_FEED, null, "https://feeds.example.org/rss.xml");
    assertThat(
            ConnectorChecks.requireHttpUrl(
                SourceTypes.HTTP_DIRECTORY,
                new SourceSettings(null, "http://intranet.example/akten/", null, null)))
        .isEqualTo("http://intranet.example/akten/");
  }
}
