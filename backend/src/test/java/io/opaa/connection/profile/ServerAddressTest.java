package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ValidationException;
import org.junit.jupiter.api.Test;

class ServerAddressTest {

  @Test
  void normalizesSchemeHostAndTrailingSlashes() {
    assertThat(ServerAddress.normalize(" HTTPS://Cloud.Example.org:8443/nextcloud// "))
        .isEqualTo("https://cloud.example.org:8443/nextcloud");
    assertThat(ServerAddress.normalize("http://wiki.example.org"))
        .isEqualTo("http://wiki.example.org");
  }

  @Test
  void refusesAnythingButAPlainHttpAddress() {
    assertThatThrownBy(() -> ServerAddress.normalize("ftp://example.org"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> ServerAddress.normalize("https://nutzer:pw@example.org"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> ServerAddress.normalize("https://example.org/?a=b"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> ServerAddress.normalize("/relativ"))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> ServerAddress.normalize(" ")).isInstanceOf(ValidationException.class);
  }

  @Test
  void aTargetLiesUnderTheAddressOnlyOnTheSameOriginAndPath() {
    String address = "https://cloud.example.org/nextcloud";

    assertThat(ServerAddress.covers(address, "https://cloud.example.org/nextcloud")).isTrue();
    assertThat(ServerAddress.covers(address, "https://CLOUD.example.org:443/nextcloud/a?x=1"))
        .isTrue();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org/nextcloud2")).isFalse();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org/")).isFalse();
    assertThat(ServerAddress.covers(address, "http://cloud.example.org/nextcloud")).isFalse();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org:8443/nextcloud")).isFalse();
    assertThat(ServerAddress.covers(address, "https://evil.example.org/nextcloud")).isFalse();
    assertThat(ServerAddress.covers(address, null)).isFalse();
    assertThat(ServerAddress.covers("https://cloud.example.org", "https://cloud.example.org/x"))
        .isTrue();
  }

  @Test
  void dotSegmentsCannotLeaveTheAddress() {
    String address = "https://cloud.example.org/base";

    assertThat(ServerAddress.covers(address, "https://cloud.example.org/base/../admin")).isFalse();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org/base/a/../../x")).isFalse();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org/base/%2e%2e/admin"))
        .isFalse();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org/../base/a")).isFalse();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org/base/a/../b")).isTrue();
    assertThat(ServerAddress.covers(address, "https://cloud.example.org/base/./a")).isTrue();
  }

  @Test
  void rebaseKeepsWhatLiesBelowTheOldAddress() {
    assertThat(
            ServerAddress.rebase(
                "https://alt.example.org/wiki/spaces/A?x=1",
                "https://alt.example.org/wiki",
                "https://neu.example.org"))
        .isEqualTo("https://neu.example.org/spaces/A?x=1");
    assertThatThrownBy(
            () ->
                ServerAddress.rebase(
                    "https://fremd.example.org/a", "https://alt.example.org", "https://neu.org"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sameOriginComparesSchemeHostAndPort() {
    assertThat(ServerAddress.sameOrigin("https://a.example.org/x", "https://A.example.org:443/y"))
        .isTrue();
    assertThat(ServerAddress.sameOrigin("https://a.example.org", "http://a.example.org")).isFalse();
    assertThat(ServerAddress.sameOrigin(null, "https://a.example.org")).isFalse();
  }
}
