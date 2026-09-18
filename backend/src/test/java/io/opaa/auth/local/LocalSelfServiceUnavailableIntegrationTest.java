package io.opaa.auth.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Without {@code OPAA_PUBLIC_BASE_URL} (ADR-0033, Entscheidungen 10 and 11) the two link flows do
 * not exist, whatever the settings say - and a caller without a session cannot tell them from a
 * route nobody serves (#1592): {@code forgot-password} and {@code register} answer with the same
 * status, the same body and the same headers as {@code POST /api/v1/auth/local/<unknown>}, for a
 * valid body, a syntactically broken body and another method alike. The two link endpoints stay
 * reachable meanwhile: an invitation handed over as a displayed link must still be redeemable. The
 * state of the flows stays readable through {@code GET /api/v1/auth/config} alone. Runs in the
 * shared local-auth context, which deliberately has no base URL.
 */
@OpaaLocalAuthMockMvcTest
class LocalSelfServiceUnavailableIntegrationTest {

  private static final String FORGOT_PASSWORD = "/api/v1/auth/local/forgot-password";
  private static final String REGISTER = "/api/v1/auth/local/register";

  /**
   * A method other than the one the endpoint declares. A served path answers 401 here as well -
   * only {@code POST} is ever permitted, every other method falls through to {@code /api/**} and
   * reaches no handler. The shape guards the mistake that would change that: permitting the path
   * for <em>every</em> method would let a {@code GET} reach the dispatcher, whose answer is not the
   * 401 of the authorization rule.
   */
  private static final Function<String, MockHttpServletRequestBuilder> OTHER_METHOD =
      path -> get(path);

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
  void theLinkFlowsAreIndistinguishableFromAnUnknownRouteWhileNoPublicBaseUrlIsConfigured()
      throws Exception {
    String unknown = "/api/v1/auth/local/unbekannt-" + UUID.randomUUID();
    String email = "wer-" + UUID.randomUUID() + "@stadt.example";
    String register =
        "{\"email\":\""
            + email
            + "\",\"displayName\":\"Wer\",\"password\":\"sicheres-passwort-2026\"}";

    assertIndistinguishable(FORGOT_PASSWORD, unknown, json("{\"email\":\"" + email + "\"}"));
    assertIndistinguishable(REGISTER, unknown, json(register));
    // a body the DispatcherServlet refuses with 400 before the handler runs - it must not get that
    // far, or the broken body alone would tell the two routes apart
    assertIndistinguishable(FORGOT_PASSWORD, unknown, json("{"));
    assertIndistinguishable(REGISTER, unknown, json("{"));
    assertIndistinguishable(FORGOT_PASSWORD, unknown, OTHER_METHOD);
    assertIndistinguishable(REGISTER, unknown, OTHER_METHOD);
    // the shared answer is the 401 of the resource server, not the 404 of a handler
    assertThat(answer(json("{}").apply(FORGOT_PASSWORD)).status()).isEqualTo(401);
  }

  @Test
  void theStateOfTheFlowsIsReadableThroughTheAuthConfigAlone() throws Exception {
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.localAccounts.enabled").value(true))
        .andExpect(jsonPath("$.localAccounts.passwordResetEnabled").value(false))
        .andExpect(jsonPath("$.localAccounts.selfRegistrationEnabled").value(false));
  }

  @Test
  void theLinkEndpointsStayReachableWhateverTheSwitchesSay() throws Exception {
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

  private void assertIndistinguishable(
      String switchedOff, String unknown, Function<String, MockHttpServletRequestBuilder> shape)
      throws Exception {
    assertThat(answer(shape.apply(switchedOff)))
        .as("%s has to answer exactly like %s", switchedOff, unknown)
        .isEqualTo(answer(shape.apply(unknown)));
  }

  private static Function<String, MockHttpServletRequestBuilder> json(String body) {
    return path -> post(path).contentType(MediaType.APPLICATION_JSON).content(body);
  }

  private HttpAnswer answer(MockHttpServletRequestBuilder request) throws Exception {
    return HttpAnswer.of(mockMvc, request);
  }

  private void replaceSettings(Values values) {
    LocalAuthSettings row = settings.findSingleton().orElseThrow();
    row.replace(values, null, Instant.now());
    settings.save(row);
  }
}
