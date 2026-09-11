package io.opaa.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * #1536, ADR-0033 Entscheidung 10: {@link PublicBaseUrl} is the only source of the links OPAA mails
 * out. Without a configured value it reports itself unconfigured and produces nothing - which is
 * what keeps "Passwort vergessen" and the self-registration switched off rather than sending mail
 * nobody can act on.
 */
class PublicBaseUrlTest {

  @Test
  void reportsUnconfiguredAndProducesNoLinkWithoutAValue() {
    PublicBaseUrl base = new PublicBaseUrl(new PublicBaseUrlProperties(null));

    assertThat(base.isConfigured()).isFalse();
    assertThat(base.base()).isEmpty();
    assertThat(base.link("konto/passwort")).isEmpty();
    assertThat(base.link("konto/passwort", "token", "T")).isEmpty();
  }

  @Test
  void treatsABlankValueAsUnconfigured() {
    assertThat(new PublicBaseUrl(new PublicBaseUrlProperties("   ")).isConfigured()).isFalse();
  }

  @Test
  void buildsALinkFromTheConfiguredBase() {
    PublicBaseUrl base = new PublicBaseUrl(new PublicBaseUrlProperties("https://opaa.amt.example"));

    assertThat(base.isConfigured()).isTrue();
    assertThat(base.link("konto/passwort")).contains("https://opaa.amt.example/konto/passwort");
  }

  @Test
  void normalisesTrailingAndLeadingSlashesSoTheLinkNeverCarriesADoubleOne() {
    PublicBaseUrl base =
        new PublicBaseUrl(new PublicBaseUrlProperties("https://opaa.amt.example///"));

    assertThat(base.link("/konto/passwort/")).contains("https://opaa.amt.example/konto/passwort");
  }

  @Test
  void percentEncodesTheQueryValueSoATokenCannotBreakOutOfTheUrl() {
    PublicBaseUrl base = new PublicBaseUrl(new PublicBaseUrlProperties("https://opaa.amt.example"));

    assertThat(base.link("konto/passwort", "token", "a b&c=d"))
        .contains("https://opaa.amt.example/konto/passwort?token=a+b%26c%3Dd");
  }

  @Test
  void refusesAValueWithoutSchemeOrHostRatherThanBuildingAHalfLink() {
    assertThat(new PublicBaseUrl(new PublicBaseUrlProperties("opaa.amt.example")).isConfigured())
        .isFalse();
    assertThat(new PublicBaseUrl(new PublicBaseUrlProperties("https://")).isConfigured()).isFalse();
    assertThat(new PublicBaseUrl(new PublicBaseUrlProperties("ht tp://x")).isConfigured())
        .isFalse();
  }
}
