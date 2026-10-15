package io.opaa.connection.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reading a token endpoint's error answer: only an integer in {@code error_codes} counts as Entra
 * code, else the {@code AADSTS} prefix of the description; an answer that is no JSON yields no
 * hint; the log detail carries no value that would break the log line.
 */
class TokenEndpointRefusalTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SECRET_ID_HINT = "„Geheime ID“";

  @Test
  void anIntegerInErrorCodesIsTheEntraCode() {
    TokenEndpointRefusal refusal =
        of("{\"error\": \"invalid_client\", \"error_codes\": [7000215]}");

    assertThat(refusal.hint(null, true)).contains(SECRET_ID_HINT);
    assertThat(refusal.logDetail()).isEqualTo("invalid_client, AADSTS7000215");
  }

  @Test
  void aStringInErrorCodesIsIgnoredAndTheDescriptionPrefixDecides() {
    TokenEndpointRefusal withPrefix =
        of(
            "{\"error\": \"invalid_client\", \"error_codes\": [\"7000222\"],"
                + " \"error_description\": \"AADSTS7000215: Invalid client secret.\"}");
    TokenEndpointRefusal withoutPrefix =
        of("{\"error\": \"invalid_client\", \"error_codes\": [\"7000215\"]}");

    assertThat(withPrefix.hint(null, true)).contains(SECRET_ID_HINT);
    assertThat(withoutPrefix.hint(null, true)).isEqualTo(TokenEndpointRefusal.INVALID_CLIENT);
    assertThat(withoutPrefix.logDetail()).isEqualTo("invalid_client");
  }

  @Test
  void aCodeOutsideAnArrayIsNoEntraCode() {
    TokenEndpointRefusal refusal = of("{\"error\": \"invalid_client\", \"error_codes\": 7000215}");

    assertThat(refusal.hint(null, true)).isEqualTo(TokenEndpointRefusal.INVALID_CLIENT);
  }

  @Test
  void anAnswerWithoutJsonHasNoErrorAndNoHint() {
    TokenEndpointRefusal refusal = TokenEndpointRefusal.of(null);

    assertThat(refusal.error()).isEmpty();
    assertThat(refusal.hint("files.read", true)).isNull();
    assertThat(refusal.logDetail()).isEqualTo("no error code");
  }

  @Test
  void anIdWithALineBreakDoesNotReachTheLogDetail() {
    TokenEndpointRefusal refusal =
        of(
            "{\"error\": \"invalid_client\", \"error_codes\": [7000215],"
                + " \"trace_id\": \"abc\\nFORGED log line\","
                + " \"correlation_id\": \"6e5d4c3b-2a19-4f08-9e7d-c6b5a4938271\"}");

    assertThat(refusal.logDetail())
        .isEqualTo(
            "invalid_client, AADSTS7000215, correlation_id 6e5d4c3b-2a19-4f08-9e7d-c6b5a4938271")
        .doesNotContain("\n", "FORGED");
  }

  @Test
  void onlyAnAppOnlySignInIsPointedToTheDefaultScopeOfGraph() {
    TokenEndpointRefusal refusal = of("{\"error\": \"invalid_scope\", \"error_codes\": [70011]}");

    assertThat(refusal.hint("Files.Read", true))
        .startsWith("Der Scope „Files.Read“ des Zugangs wird nicht angenommen.")
        .contains("https://graph.microsoft.com/.default");
    assertThat(refusal.hint("Files.Read", false))
        .isEqualTo("Der Scope „Files.Read“ des Zugangs wird nicht angenommen.");
  }

  private static TokenEndpointRefusal of(String json) {
    JsonNode body = JSON.readTree(json);
    return TokenEndpointRefusal.of(body);
  }
}
