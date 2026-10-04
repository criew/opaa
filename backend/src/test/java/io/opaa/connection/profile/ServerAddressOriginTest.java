package io.opaa.connection.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The one origin rule a stored secret follows ({@link ServerAddress#sameOrigin}). Among others the
 * underscore-hostname case: {@link java.net.URI#getHost()} returns {@code null} for it, and a plain
 * {@code Objects.equals} of the hosts would call two unrelated servers the same origin.
 */
class ServerAddressOriginTest {

  @Test
  void sameSchemeHostAndPortAreTheSameOrigin() {
    assertThat(
            ServerAddress.sameOrigin(
                "https://files.example.com/documents/", "https://files.example.com/other/"))
        .isTrue();
  }

  @Test
  void anExplicitPortIsNormalizedAgainstTheSchemeDefault() {
    assertThat(
            ServerAddress.sameOrigin(
                "https://files.example.com/documents/", "https://files.example.com:443/other/"))
        .isTrue();
  }

  @Test
  void aDifferentHostIsADifferentOrigin() {
    assertThat(
            ServerAddress.sameOrigin(
                "https://files.example.com/documents/", "https://attacker.example.com/documents/"))
        .isFalse();
  }

  @Test
  void aDifferentPortIsADifferentOrigin() {
    assertThat(
            ServerAddress.sameOrigin(
                "https://files.example.com/documents/",
                "https://files.example.com:8443/documents/"))
        .isFalse();
  }

  @Test
  void twoUnrelatedUnderscoreHostnamesAreNotTheSameOrigin() {
    // #615 review, finding 1: java.net.URI does not recognize an underscore as a valid reg-name
    // character, so URI.create(...).getHost() returns null for both of these - a comparison that
    // only checked Objects.equals(hostA, hostB) would wrongly call this "the same origin" because
    // both sides are null, letting a caller reuse a stored credential against a completely
    // unrelated server. Sanity check first: URI really does parse both hosts as null, otherwise
    // this test would not exercise the case it claims to.
    assertThat(java.net.URI.create("https://my_internal_host/documents/").getHost()).isNull();
    assertThat(java.net.URI.create("https://attacker_controlled_host/documents/").getHost())
        .isNull();

    assertThat(
            ServerAddress.sameOrigin(
                "https://my_internal_host/documents/",
                "https://attacker_controlled_host/documents/"))
        .isFalse();
  }

  @Test
  void theSameUnderscoreHostnameIsStillRejectedSinceUriCannotParseItAtAll() {
    // Deliberately conservative (mirrors the class's own "unparseable is different origin"
    // Javadoc): even the identical underscore-hostname string on both sides must not match,
    // since URI never actually confirmed it is the same host - only that it could not parse
    // either one.
    assertThat(
            ServerAddress.sameOrigin(
                "https://my_internal_host/documents/", "https://my_internal_host/other/"))
        .isFalse();
  }

  @Test
  void hostComparisonIsCaseInsensitive() {
    assertThat(
            ServerAddress.sameOrigin(
                "https://Files.Example.com/documents/", "https://files.example.com/other/"))
        .isTrue();
  }

  @Test
  void eitherUrlBeingNullIsADifferentOrigin() {
    assertThat(ServerAddress.sameOrigin(null, "https://files.example.com/")).isFalse();
    assertThat(ServerAddress.sameOrigin("https://files.example.com/", null)).isFalse();
  }

  @Test
  void anUnparsableUrlIsADifferentOrigin() {
    assertThat(ServerAddress.sameOrigin("https://files.example.com/", "not a url")).isFalse();
  }

  // The cases below are where the rule of the profile and the rule of the library used to differ;
  // each takes the stricter reading, so a secret is rather asked for again than sent elsewhere.

  @Test
  void aPaddedAddressIsUnreadable() {
    assertThat(
            ServerAddress.sameOrigin(" https://files.example.com/a", "https://files.example.com/b"))
        .isFalse();
    assertThat(
            ServerAddress.sameOrigin("https://files.example.com/a", "https://files.example.com/b "))
        .isFalse();
  }

  @Test
  void anOmittedPortIsTheDefaultOnlyForHttpAndHttps() {
    assertThat(
            ServerAddress.sameOrigin("http://files.example.com/", "http://files.example.com:80/"))
        .isTrue();
    assertThat(ServerAddress.sameOrigin("smb://fs.example.com/a", "smb://fs.example.com:445/a"))
        .isFalse();
    assertThat(ServerAddress.sameOrigin("ftp://fs.example.com/", "ftp://fs.example.com:80/"))
        .isFalse();
    assertThat(ServerAddress.sameOrigin("smb://fs.example.com/a", "smb://fs.example.com/b"))
        .isTrue();
  }

  @Test
  void anAddressWithoutAReadableHostNeverMatches() {
    assertThat(
            ServerAddress.sameOrigin(
                "https://files.example.com:abc/", "https://files.example.com:abc/"))
        .isFalse();
    assertThat(ServerAddress.sameOrigin("//files.example.com/a", "//files.example.com/a"))
        .isFalse();
    assertThat(ServerAddress.sameOrigin("mailto:a@example.com", "mailto:a@example.com")).isFalse();
  }
}
