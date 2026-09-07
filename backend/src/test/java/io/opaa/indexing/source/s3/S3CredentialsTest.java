package io.opaa.indexing.source.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * {@link S3Credentials} (ADR-0027, Entscheidung 7): stored as {@code
 * accessKey:secretKey[:sessionToken]}, parsed at the first and second colon, never printed.
 */
class S3CredentialsTest {

  @Test
  void parsesAccessKeyAndSecretKey() {
    S3Credentials credentials = S3Credentials.parse("AKIAEXAMPLE:wJalrXUtnFEMI/K7MDENG+bPxRfiCY");

    assertThat(credentials.accessKey()).isEqualTo("AKIAEXAMPLE");
    assertThat(credentials.secretKey()).isEqualTo("wJalrXUtnFEMI/K7MDENG+bPxRfiCY");
    assertThat(credentials.sessionToken()).isNull();
    assertThat(credentials.hasSessionToken()).isFalse();
  }

  @Test
  void parsesAnOptionalSessionToken() {
    S3Credentials credentials = S3Credentials.parse("ASIAEXAMPLE:secret:FwoGZXIvYXdzEBYaDG==");

    assertThat(credentials.sessionToken()).isEqualTo("FwoGZXIvYXdzEBYaDG==");
    assertThat(credentials.hasSessionToken()).isTrue();
    assertThat(credentials.stored()).isEqualTo("ASIAEXAMPLE:secret:FwoGZXIvYXdzEBYaDG==");
  }

  @Test
  void rejectsBlankOrIncompleteValuesWithAGermanMessage() {
    assertThatThrownBy(() -> S3Credentials.parse(" "))
        .isInstanceOf(S3Credentials.InvalidCredentialsFormatException.class)
        .hasMessageContaining("Zugangsdaten");
    assertThatThrownBy(() -> S3Credentials.parse("AKIAEXAMPLE"))
        .isInstanceOf(S3Credentials.InvalidCredentialsFormatException.class)
        .hasMessageContaining("Access Key");
    assertThatThrownBy(() -> S3Credentials.parse("AKIAEXAMPLE:"))
        .isInstanceOf(S3Credentials.InvalidCredentialsFormatException.class);
    assertThatThrownBy(() -> S3Credentials.parse(":secret"))
        .isInstanceOf(S3Credentials.InvalidCredentialsFormatException.class);
  }

  @Test
  void neverPrintsTheSecret() {
    S3Credentials credentials = S3Credentials.parse("AKIAEXAMPLE:geheim-4711:token-0815");

    assertThat(credentials.toString())
        .doesNotContain("geheim-4711")
        .doesNotContain("token-0815")
        .doesNotContain("AKIAEXAMPLE")
        .contains("***");
  }
}
