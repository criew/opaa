package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.indexing.source.ClientAuthentication;
import io.opaa.indexing.source.Endpoint;
import io.opaa.indexing.source.OAuthAuth;
import io.opaa.indexing.source.Revocation;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The comparison of an authorization response's {@code iss} (RFC 9207, 2.4). */
class ResponseIssuerTest {

  private static final String ISSUER = "https://as.example.org/realms/opaa";

  @Test
  void anAnnouncedIssuerMustBeNamedExactly() {
    ResponseIssuer announced = new ResponseIssuer(ISSUER, true);

    assertThat(announced.accepts(ISSUER)).isTrue();
    assertThat(announced.accepts(null)).isFalse();
    assertThat(announced.accepts("")).isFalse();
    assertThat(announced.accepts(ISSUER + "/")).isFalse();
    assertThat(announced.accepts("https://AS.example.org/realms/opaa")).isFalse();
    assertThat(announced.accepts("https://other.example.org")).isFalse();
  }

  @Test
  void withoutAnnouncementTheIssuerMayBeMissingButNotDiffer() {
    ResponseIssuer known = new ResponseIssuer(ISSUER, false);

    assertThat(known.accepts(null)).isTrue();
    assertThat(known.accepts(ISSUER)).isTrue();
    assertThat(known.accepts("https://other.example.org")).isFalse();
  }

  @Test
  void withoutAKnownIssuerNothingIsCompared() {
    assertThat(new ResponseIssuer(null, false).accepts("https://other.example.org")).isTrue();
  }

  @Test
  void aSignInDeclaresItsIssuerAndAnAnnouncementNeedsOne() {
    assertThat(ResponseIssuer.of(oauth(ISSUER, true))).isEqualTo(new ResponseIssuer(ISSUER, true));
    assertThat(ResponseIssuer.of(oauth(" ", false))).isEqualTo(new ResponseIssuer(null, false));
    assertThatThrownBy(() -> oauth(null, true)).isInstanceOf(IllegalArgumentException.class);
  }

  private static OAuthAuth oauth(String issuer, boolean announced) {
    return new OAuthAuth(
        new Endpoint.FromProfile(),
        new Endpoint.FromProfile(),
        new Revocation.None(),
        null,
        Map.of(),
        ClientAuthentication.CLIENT_SECRET_BASIC,
        issuer,
        announced);
  }
}
