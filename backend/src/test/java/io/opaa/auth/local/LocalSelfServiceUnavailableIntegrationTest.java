package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.local.LocalAuthSettings.Values;
import io.opaa.test.LocalAccountFixtures;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Without {@code OPAA_PUBLIC_BASE_URL} (ADR-0033, Entscheidungen 10 and 11) the two link flows do
 * not exist, whatever the settings say: {@code forgot-password} and {@code register} answer with
 * the standard 404 body - not with what an unknown route under the same prefix answers, which is
 * 401 without a session (#1592) - while the two link endpoints stay reachable: an invitation handed
 * over as a displayed link must still be redeemable. Runs in the shared local-auth context, which
 * deliberately has no base URL.
 */
@OpaaLocalAuthMockMvcTest
class LocalSelfServiceUnavailableIntegrationTest {

  /** What GlobalExceptionHandler renders for a path nobody serves. */
  static final String STANDARD_404 =
      "{\"error\":\"Die angeforderte Ressource wurde nicht gefunden\",\"status\":404,"
          + "\"timestamp\":\"…\",\"code\":null,\"fieldErrors\":null,\"reason\":null}";

  @Autowired private MockMvc mockMvc;
  @Autowired private LocalAccountFixturesFactory fixturesFactory;
  @Autowired private LocalAuthSettingsRepository settings;

  private LocalAccountFixtures fixtures;

  @BeforeEach
  void setUp() {
    fixtures = fixturesFactory.create();
    fixtures.cleanUp();
    fixtures.localProvider(true);
    Values d = Values.defaults();
    replaceSettings(
        new Values(
            true,
            List.of("stadt.example"),
            true,
            d.passwordMinLength(),
            d.invitationTokenTtlHours(),
            d.resetTokenTtlMinutes(),
            d.defaultExpiryDays(),
            d.inactiveDays()));
  }

  @AfterEach
  void tearDown() {
    replaceSettings(Values.defaults());
    fixtures.cleanUp();
  }

  @Test
  void theLinkFlowsAnswerWithTheStandard404WhileNoPublicBaseUrlIsConfigured() throws Exception {
    // the honest baseline: an unknown route under /api answers 401 without a session
    mockMvc
        .perform(
            post("/api/v1/auth/local/unbekannt-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isUnauthorized());
    String email = "wer-" + UUID.randomUUID() + "@stadt.example";
    MvcResult forgot =
        mockMvc
            .perform(
                post("/api/v1/auth/local/forgot-password")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}"))
            .andReturn();
    MvcResult register =
        mockMvc
            .perform(
                post("/api/v1/auth/local/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"displayName\":\"Wer\",\"password\":\"sicheres-passwort-2026\"}"))
            .andReturn();
    assertThat(forgot.getResponse().getStatus()).isEqualTo(404);
    assertThat(withoutTimestamp(body(forgot))).isEqualTo(STANDARD_404);
    assertThat(register.getResponse().getStatus()).isEqualTo(404);
    assertThat(withoutTimestamp(body(register))).isEqualTo(STANDARD_404);

    mockMvc
        .perform(
            post("/api/v1/auth/local/set-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"kein-token\",\"newPassword\":\"sicheres-passwort-2026\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
    mockMvc
        .perform(
            post("/api/v1/auth/local/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"kein-token\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TOKEN_INVALID"));
  }

  private void replaceSettings(Values values) {
    LocalAuthSettings row = settings.findSingleton().orElseThrow();
    row.replace(values, null, Instant.now());
    settings.save(row);
  }

  private static String body(MvcResult result) throws Exception {
    return result.getResponse().getContentAsString();
  }

  private static String withoutTimestamp(String json) {
    return json.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"…\"");
  }
}
