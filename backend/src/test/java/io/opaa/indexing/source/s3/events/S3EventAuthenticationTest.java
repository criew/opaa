package io.opaa.indexing.source.s3.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** The three token forms (ADR-0027, Entscheidung 6) and the uniform refusal without a token. */
class S3EventAuthenticationTest {

  private static final String TOKEN = "geheimes-token-42";

  private static String basic(String user, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void acceptsBearerBasicPasswordAndTheSharedSecretHeader() {
    assertThat(S3EventAuthentication.verify("Bearer " + TOKEN, null, TOKEN)).isTrue();
    assertThat(S3EventAuthentication.verify("bearer " + TOKEN, null, TOKEN)).isTrue();
    assertThat(S3EventAuthentication.verify(basic("minio", TOKEN), null, TOKEN)).isTrue();
    assertThat(S3EventAuthentication.verify(basic("", TOKEN), null, TOKEN)).isTrue();
    assertThat(S3EventAuthentication.verify(null, TOKEN, TOKEN)).isTrue();
    assertThat(S3EventAuthentication.verify("Bearer falsch", TOKEN, TOKEN))
        .as("one valid form is enough")
        .isTrue();
  }

  @Test
  void refusesEverythingElse() {
    assertThat(S3EventAuthentication.verify("Bearer " + TOKEN + "x", null, TOKEN)).isFalse();
    assertThat(S3EventAuthentication.verify("Bearer", null, TOKEN)).isFalse();
    assertThat(S3EventAuthentication.verify(basic(TOKEN, "anders"), null, TOKEN))
        .as("the token is the password, never the user name")
        .isFalse();
    assertThat(S3EventAuthentication.verify("Basic %%%", null, TOKEN)).isFalse();
    assertThat(
            S3EventAuthentication.verify(
                "Basic "
                    + Base64.getEncoder().encodeToString(TOKEN.getBytes(StandardCharsets.UTF_8)),
                null,
                TOKEN))
        .as("no user:password separator is no Basic credential")
        .isFalse();
    assertThat(S3EventAuthentication.verify("Digest " + TOKEN, null, TOKEN)).isFalse();
    assertThat(S3EventAuthentication.verify(null, "falsch", TOKEN)).isFalse();
    assertThat(S3EventAuthentication.verify(null, null, TOKEN)).isFalse();
    assertThat(S3EventAuthentication.verify("Bearer " + TOKEN, TOKEN, null))
        .as("no token stored: nothing authenticates")
        .isFalse();
    assertThat(S3EventAuthentication.verify("Bearer " + TOKEN, TOKEN, " ")).isFalse();
  }
}
