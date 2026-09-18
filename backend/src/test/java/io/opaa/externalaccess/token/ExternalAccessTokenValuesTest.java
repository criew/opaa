package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The shape of a raw access token (ADR-0035, Entscheidung 2). */
class ExternalAccessTokenValuesTest {

  @Test
  void carriesTheFixedPrefixAnd256BitsOfRandomness() {
    String value = ExternalAccessTokenValues.generate();

    assertThat(value).startsWith("opaa_pat_");
    byte[] random =
        Base64.getUrlDecoder()
            .decode(value.substring(ExternalAccessTokenValues.VALUE_PREFIX.length()));
    assertThat(random).hasSize(32);
  }

  @Test
  void generatesADistinctValueEveryTime() {
    Set<String> values = new HashSet<>();
    for (int i = 0; i < 500; i++) {
      values.add(ExternalAccessTokenValues.generate());
    }

    assertThat(values).hasSize(500);
  }

  @Test
  void recognisesOnlyValuesCarryingThePrefixAndSomethingBehindIt() {
    assertThat(ExternalAccessTokenValues.looksLikeAccessToken(ExternalAccessTokenValues.generate()))
        .isTrue();
    assertThat(ExternalAccessTokenValues.looksLikeAccessToken("opaa_pat_")).isFalse();
    assertThat(ExternalAccessTokenValues.looksLikeAccessToken("eyJhbGciOiJIUzI1NiJ9.x.y"))
        .isFalse();
    assertThat(ExternalAccessTokenValues.looksLikeAccessToken(null)).isFalse();
  }

  @Test
  void keepsTheHeadOfTheValueAsItsStoredPrefix() {
    String value = ExternalAccessTokenValues.generate();

    String prefix = ExternalAccessTokenValues.prefixOf(value);

    assertThat(prefix)
        .hasSize(ExternalAccessTokenValues.STORED_PREFIX_LENGTH)
        .isEqualTo(value.substring(0, ExternalAccessTokenValues.STORED_PREFIX_LENGTH));
    assertThat(value).doesNotEndWith(prefix);
  }
}
