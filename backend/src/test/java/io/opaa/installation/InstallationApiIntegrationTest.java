package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The installation mode end to end under the production {@code oidc} chain (ADR-0060): every other
 * endpoint answers {@code 503} while health and public configuration do not, the API wants its
 * token, a password and a key never come back, the completion needs every checked step, creates the
 * emergency account that then signs in, is audited, and afterwards every operation answers {@code
 * 404}.
 */
@OpaaLocalAuthMockMvcTest
class InstallationApiIntegrationTest {

  private static final String BASE = "/api/v1/installation";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private UserRepository users;
  @Autowired private LocalCredentialsRepository credentials;

  private InstallationFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
  }

  @AfterEach
  void tearDown() {
    fixture.close();
  }

  @Test
  void whilePendingEveryOtherApiAnswers503ButHealthAndPublicConfigurationDoNot() throws Exception {
    mockMvc
        .perform(get("/api/v1/spaces"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("INSTALLATION_PENDING"));
    mockMvc
        .perform(post("/api/v1/auth/local/login").contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isServiceUnavailable());
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.installationPending").value(true));
    mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/branding")).andExpect(status().isOk());
  }

  @Test
  void theApiRefusesAMissingAndAWrongToken() throws Exception {
    mockMvc
        .perform(get(BASE))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error").value("Das Installations-Token fehlt oder ist ungültig."));
    mockMvc
        .perform(get(BASE).header("Authorization", "Bearer " + InstallationFixture.TOKEN + "x"))
        .andExpect(status().isUnauthorized());
    mockMvc.perform(authorized(get(BASE))).andExpect(status().isOk());
  }

  @Test
  void theTokenIsStoredOnlyAsItsHash() {
    String stored =
        jdbc.queryForObject("SELECT token_hash FROM installation_state WHERE id = 1", String.class);

    assertThat(stored).isEqualTo(InstallationTokens.hash(InstallationFixture.TOKEN));
    assertThat(stored).doesNotContain(InstallationFixture.TOKEN);
  }

  @Test
  void theCompletionNeedsEveryCheckedStep() throws Exception {
    saveFirstAdmin().andExpect(status().isOk());

    mockMvc
        .perform(authorized(post(BASE + "/complete")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("FIRST_ADMIN")));
  }

  @Test
  void aStepIsSavedWithoutItsSecretAndASavingDiscardsTheCheck() throws Exception {
    saveFirstAdmin()
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.firstAdmin.passwordSet").value(true))
        .andExpect(jsonPath("$.firstAdmin.password").doesNotExist());
    check("FIRST_ADMIN").andExpect(jsonPath("$.checked").value(true));
    String payload =
        jdbc.queryForObject(
            "SELECT payload::text FROM installation_steps WHERE step = 'FIRST_ADMIN'",
            String.class);
    String secret =
        jdbc.queryForObject(
            "SELECT secret FROM installation_steps WHERE step = 'FIRST_ADMIN'", String.class);
    assertThat(payload).doesNotContain(InstallationFixture.ADMIN_PASSWORD);
    assertThat(secret).doesNotContain(InstallationFixture.ADMIN_PASSWORD).startsWith("{bcrypt}");

    saveFirstAdmin().andExpect(jsonPath("$.checked").value(false));
  }

  @Test
  void aWeakPasswordIsAFieldError() throws Exception {
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/FIRST_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"firstAdmin\":{\"email\":\"it@stadt.example\",\"displayName\":\"IT\","
                        + "\"password\":\"kurz\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("firstAdmin.password"));
  }

  @Test
  void theCompletedInstallationCreatesTheEmergencyAccountThatSignsInAndAnswers404Afterwards()
      throws Exception {
    saveFirstAdmin().andExpect(status().isOk());
    check("FIRST_ADMIN").andExpect(jsonPath("$.checked").value(true));
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/SIGN_IN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signIn\":{\"mode\":\"LOCAL_ONLY\"}}"))
        .andExpect(status().isOk());
    check("SIGN_IN").andExpect(jsonPath("$.checked").value(true));
    saveChatModel().andExpect(jsonPath("$.chatModel.apiKeySet").value(false));
    check("CHAT_MODEL")
        .andExpect(jsonPath("$.checked").value(true))
        .andExpect(jsonPath("$.checkMessage").exists());

    mockMvc
        .perform(authorized(post(BASE + "/complete")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pending").value(false));

    User admin =
        users.findAll().stream()
            .filter(user -> InstallationFixture.ADMIN_EMAIL.equals(user.getEmail()))
            .findFirst()
            .orElseThrow();
    assertThat(admin.getSystemRole()).isEqualTo(SystemRole.SYSTEM_ADMIN);
    LocalCredentials row = credentials.findByBootstrapTrue().orElseThrow();
    assertThat(row.getUserId()).isEqualTo(admin.getId());
    assertThat(row.isPasswordChangeRequired()).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM llm_models WHERE active AND display_name = ?",
                Integer.class,
                InstallationService.CHAT_MODEL_DISPLAY_NAME))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status || '/' || coalesce(token_hash, 'none') FROM installation_state",
                String.class))
        .isEqualTo("COMPLETED/none");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM installation_steps", Integer.class))
        .isZero();
    assertThat(auditTypes())
        .contains(
            "INSTALLATION_STEP_SAVED",
            "INSTALLATION_STEP_CHECKED",
            "LOCAL_ADMIN_SEEDED",
            "LLM_MODEL_CREATED",
            "INSTALLATION_COMPLETED");
    assertThat(auditPayloads())
        .noneMatch(payload -> payload.contains(InstallationFixture.ADMIN_EMAIL));

    mockMvc
        .perform(
            post("/api/v1/auth/local/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\""
                        + InstallationFixture.ADMIN_EMAIL
                        + "\",\"password\":\""
                        + InstallationFixture.ADMIN_PASSWORD
                        + "\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(authorized(get(BASE))).andExpect(status().isNotFound());
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isNotFound());
    mockMvc.perform(post(BASE + "/answer-file")).andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/auth/config"))
        .andExpect(jsonPath("$.installationPending").value(false));
  }

  @Test
  void anAnswerWhoseCheckFailsSavesNothing() throws Exception {
    mockMvc
        .perform(
            authorized(post(BASE + "/answer-file"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"firstAdmin\":"
                        + firstAdminJson()
                        + ",\"signIn\":{\"mode\":\"LOCAL_ONLY\"},"
                        + "\"chatModel\":{\"baseUrl\":\"http://127.0.0.1:1/v1\","
                        + "\"model\":\"qwen2.5:7b\"}}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("CHAT_MODEL")));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM installation_steps", Integer.class))
        .isZero();
    assertThat(credentials.findByBootstrapTrue()).isEmpty();
  }

  @Test
  void anAnswerInstallsWithoutABrowser() throws Exception {
    mockMvc
        .perform(
            authorized(post(BASE + "/answer-file"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"firstAdmin\":"
                        + firstAdminJson()
                        + ",\"signIn\":{\"mode\":\"LOCAL_ONLY\"},"
                        + "\"chatModel\":{\"baseUrl\":\""
                        + fixture.chatBaseUrl()
                        + "\",\"model\":\"qwen2.5:7b\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pending").value(false));

    assertThat(credentials.findByBootstrapTrue()).isPresent();
    assertThat(
            jdbc.queryForObject(
                "SELECT completion_source FROM installation_state WHERE id = 1", String.class))
        .isEqualTo("ANSWER_FILE_API");
  }

  private ResultActions saveFirstAdmin() throws Exception {
    return mockMvc.perform(
        authorized(put(BASE + "/steps/FIRST_ADMIN"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"firstAdmin\":" + firstAdminJson() + "}"));
  }

  private ResultActions saveChatModel() throws Exception {
    return mockMvc.perform(
        authorized(put(BASE + "/steps/CHAT_MODEL"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"chatModel\":{\"baseUrl\":\""
                    + fixture.chatBaseUrl()
                    + "\",\"model\":\"qwen2.5:7b\"}}"));
  }

  private ResultActions check(String step) throws Exception {
    return mockMvc
        .perform(authorized(post(BASE + "/steps/" + step + "/check")))
        .andExpect(status().isOk());
  }

  private static String firstAdminJson() {
    return "{\"email\":\""
        + InstallationFixture.ADMIN_EMAIL
        + "\",\"displayName\":\"IT-Betrieb\",\"password\":\""
        + InstallationFixture.ADMIN_PASSWORD
        + "\"}";
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
      authorized(
          org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + InstallationFixture.TOKEN);
  }

  private List<String> auditTypes() {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE actor_ref = ? ORDER BY recorded_at",
        String.class,
        InstallationService.SYSTEM_ACTOR);
  }

  private List<String> auditPayloads() {
    return jdbc.queryForList(
        "SELECT coalesce(before, '') || coalesce(after, '') FROM audit_log WHERE actor_ref = ?",
        String.class,
        InstallationService.SYSTEM_ACTOR);
  }

  // regression guard: the door is decided before the body is read
  @Test
  void aLargeBodyWithoutTokenIsRefusedBeforeItIsReadAndOneWithTokenIsTooLarge() throws Exception {
    String large = "{\"chatModel\":{\"model\":\"" + "x".repeat(200_000) + "\"}}";

    mockMvc
        .perform(
            put(BASE + "/steps/CHAT_MODEL").contentType(MediaType.APPLICATION_JSON).content(large))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/CHAT_MODEL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(large))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Die Anfrage ist zu groß."));
  }

  @Test
  void anOverlongValueIsAFieldError() throws Exception {
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/CHAT_MODEL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"chatModel\":{\"baseUrl\":\""
                        + fixture.chatBaseUrl()
                        + "\",\"model\":\""
                        + "m".repeat(StepRules.MAX_NAME + 1)
                        + "\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("chatModel.model"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("TOO_LONG"));
  }
}
