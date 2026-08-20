package io.opaa.library;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit-level coverage of {@link SourceOriginMatcher} - in particular the underscore-hostname case
 * from #615 review, finding 1: {@link java.net.URI#getHost()} returns {@code null} for a hostname
 * containing an underscore, and an implementation comparing hosts with plain {@code Objects.equals}
 * would then treat two completely unrelated underscore-hostname URLs as the same origin, since both
 * sides evaluate to {@code null}.
 */
class SourceOriginMatcherTest {

  @Test
  void sameSchemeHostAndPortAreTheSameOrigin() {
    assertThat(
            SourceOriginMatcher.sameOrigin(
                "https://files.example.com/documents/", "https://files.example.com/other/"))
        .isTrue();
  }

  @Test
  void anExplicitPortIsNormalizedAgainstTheSchemeDefault() {
    assertThat(
            SourceOriginMatcher.sameOrigin(
                "https://files.example.com/documents/", "https://files.example.com:443/other/"))
        .isTrue();
  }

  @Test
  void aDifferentHostIsADifferentOrigin() {
    assertThat(
            SourceOriginMatcher.sameOrigin(
                "https://files.example.com/documents/", "https://attacker.example.com/documents/"))
        .isFalse();
  }

  @Test
  void aDifferentPortIsADifferentOrigin() {
    assertThat(
            SourceOriginMatcher.sameOrigin(
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
            SourceOriginMatcher.sameOrigin(
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
            SourceOriginMatcher.sameOrigin(
                "https://my_internal_host/documents/", "https://my_internal_host/other/"))
        .isFalse();
  }

  @Test
  void hostComparisonIsCaseInsensitive() {
    assertThat(
            SourceOriginMatcher.sameOrigin(
                "https://Files.Example.com/documents/", "https://files.example.com/other/"))
        .isTrue();
  }

  @Test
  void eitherUrlBeingNullIsADifferentOrigin() {
    assertThat(SourceOriginMatcher.sameOrigin(null, "https://files.example.com/")).isFalse();
    assertThat(SourceOriginMatcher.sameOrigin("https://files.example.com/", null)).isFalse();
  }

  @Test
  void anUnparsableUrlIsADifferentOrigin() {
    assertThat(SourceOriginMatcher.sameOrigin("https://files.example.com/", "not a url")).isFalse();
  }
}
