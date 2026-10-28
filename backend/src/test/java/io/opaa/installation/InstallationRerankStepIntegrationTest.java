package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.llm.ModelRoleAdministration;
import io.opaa.llm.ModelRoleEndpoints;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthLinkTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The optional rerank step of the installation end to end: skipped, reranking stays off and the
 * completion names it among the skipped steps; checked with the probe of the rerank model
 * administration, the completion stores it there, switched on and audited under the installation,
 * with the key encrypted and never in an answer. The answer file takes the same part, and works
 * without it. The step does not depend on the chat or the embedding model.
 */
@OpaaLocalAuthLinkTest
class InstallationRerankStepIntegrationTest {

  private static final String BASE = "/api/v1/installation";
  private static final String KEY = "sk-rerank-der-installation-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private ModelRoleAdministration modelRoles;
  @Autowired private ModelRoleEndpoints endpoints;

  private InstallationFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    deleteRerankRows();
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
  }

  @AfterEach
  void tearDown() {
    fixture.close();
    deleteRerankRows();
  }

  @Test
  void aSkippedRerankStepLeavesRerankingOff() throws Exception {
    completeMandatorySteps();
    mockMvc
        .perform(authorized(get(BASE)))
        .andExpect(jsonPath("$.steps[4].step").value("RERANK_MODEL"))
        .andExpect(jsonPath("$.steps[4].mandatory").value(false))
        .andExpect(jsonPath("$.steps[4].saved").value(false));
    saveRerank(rerankJson(KEY)).andExpect(status().isOk());

    mockMvc
        .perform(authorized(post(BASE + "/steps/RERANK_MODEL/skip")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saved").value(false))
        .andExpect(jsonPath("$.rerankModel").doesNotExist());
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM rerank_model", Integer.class)).isZero();
    assertThat(modelRoles.rerank().enabled()).isFalse();
    assertThat(auditTypes()).doesNotContain("RERANK_MODEL_CHANGED");
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"RERANK_MODEL\"");
  }

  @Test
  void aCheckedRerankStepIsTakenOverIntoTheModelAdministrationAndSwitchedOn() throws Exception {
    // Neither chat nor embedding model: the rerank step stands on its own.
    completeMandatorySteps();
    saveRerank(rerankJson(KEY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rerankModel.baseUrl").value(fixture.chatBaseUrl()))
        .andExpect(jsonPath("$.rerankModel.model").value("bge-reranker-v2-m3"))
        .andExpect(jsonPath("$.rerankModel.apiKeySet").value(true))
        .andExpect(jsonPath("$.rerankModel.apiKey").doesNotExist())
        .andExpect(jsonPath("$.checked").value(false));
    String checked =
        mockMvc
            .perform(authorized(post(BASE + "/steps/RERANK_MODEL/check")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checked").value(true))
            .andExpect(
                jsonPath("$.checkMessage")
                    .value("Verbindung erfolgreich, der Endpunkt hat die Probe bewertet."))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(checked).doesNotContain(KEY);
    assertThat(fixture.lastRerankAuthorization()).isEqualTo("Bearer " + KEY);
    assertThat(
            jdbc.queryForObject(
                "SELECT secret FROM installation_steps WHERE step = 'RERANK_MODEL'", String.class))
        .isNotNull()
        .doesNotContain(KEY);

    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    // No manual invalidation: the completion itself drops this instance's role cache.
    assertThat(endpoints.rerank().enabled()).isTrue();
    assertThat(endpoints.rerank().model()).isEqualTo("bge-reranker-v2-m3");
    ModelRoleAdministration.RerankModelView view = modelRoles.rerank();
    assertThat(view.enabled()).isTrue();
    assertThat(view.baseUrl()).isEqualTo(fixture.chatBaseUrl());
    assertThat(view.model()).isEqualTo("bge-reranker-v2-m3");
    assertThat(view.apiKeySet()).isTrue();
    assertThat(jdbc.queryForObject("SELECT api_key_ciphertext FROM rerank_model", String.class))
        .doesNotContain(KEY);
    assertThat(auditTypes()).contains("RERANK_MODEL_CHANGED");
    assertThat(auditAfter("RERANK_MODEL_CHANGED"))
        .contains("\"enabled\":true")
        .contains("\"apiKeyChange\":\"SET\"")
        .doesNotContain(KEY);
    assertThat(auditAfter("INSTALLATION_STEP_SAVED"))
        .contains("\"step\":\"RERANK_MODEL\"")
        .contains("\"apiKeySet\":true")
        .doesNotContain(KEY);
    assertThat(auditAfter("INSTALLATION_COMPLETED")).doesNotContain("\"RERANK_MODEL\"");
  }

  @Test
  void aFailedProbeHoldsUpTheCompletionUntilTheStepIsSkipped() throws Exception {
    completeMandatorySteps();
    saveRerank(
            "{\"baseUrl\":\""
                + fixture.chatBaseUrl()
                + "/nirgends\",\"model\":\"bge-reranker-v2-m3\"}")
        .andExpect(status().isOk());
    mockMvc
        .perform(authorized(post(BASE + "/steps/RERANK_MODEL/check")))
        .andExpect(jsonPath("$.checked").value(false))
        .andExpect(jsonPath("$.checkMessage").value(org.hamcrest.Matchers.containsString("404")));

    mockMvc.perform(authorized(get(BASE))).andExpect(jsonPath("$.completable").value(false));
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isConflict());
    mockMvc.perform(authorized(post(BASE + "/steps/RERANK_MODEL/skip"))).andExpect(status().isOk());
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM rerank_model", Integer.class)).isZero();
  }

  @Test
  void aRefusedAddressIsAFieldErrorOfTheStep() throws Exception {
    saveRerank("{\"baseUrl\":\"http://169.254.169.254\",\"model\":\"bge\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("rerankModel.baseUrl"))
        .andExpect(jsonPath("$.fieldErrors[0].code").value("BLOCKED"));
    saveRerank("{\"baseUrl\":\"\",\"model\":\"\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("rerankModel.baseUrl"))
        .andExpect(jsonPath("$.fieldErrors[1].field").value("rerankModel.model"));
  }

  @Test
  void theModelsOfTheRerankEndpointAreListedWithTheSavedKeyForItsOwnEndpoint() throws Exception {
    saveRerank(rerankJson(KEY)).andExpect(status().isOk());

    mockMvc
        .perform(
            authorized(post(BASE + "/steps/RERANK_MODEL/discover"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"" + fixture.chatBaseUrl() + "\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));

    assertThat(fixture.lastModelsAuthorization()).isEqualTo("Bearer " + KEY);
  }

  @Test
  void anAnswerWithoutRerankModelStaysValidAndLeavesRerankingOff() throws Exception {
    saveRerank(rerankJson(KEY)).andExpect(status().isOk());

    applyAnswers(null).andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM rerank_model", Integer.class)).isZero();
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"RERANK_MODEL\"");
  }

  @Test
  void anAnswerWithRerankModelIsCheckedAndTakenOver() throws Exception {
    applyAnswers(rerankJson(KEY)).andExpect(status().isOk());

    assertThat(fixture.lastRerankAuthorization()).isEqualTo("Bearer " + KEY);
    endpoints.invalidateAll();
    assertThat(modelRoles.rerank().enabled()).isTrue();
    assertThat(modelRoles.rerank().model()).isEqualTo("bge-reranker-v2-m3");
  }

  @Test
  void anAnswerWhoseRerankProbeFailsIsRefusedAsAWhole() throws Exception {
    applyAnswers(
            "{\"baseUrl\":\"" + fixture.chatBaseUrl() + "/nirgends\",\"model\":\"bge-reranker\"}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("RERANK_MODEL")));

    assertThat(jdbc.queryForObject("SELECT count(*) FROM rerank_model", Integer.class)).isZero();
    mockMvc.perform(authorized(get(BASE))).andExpect(status().isOk());
  }

  private String rerankJson(String key) {
    return "{\"baseUrl\":\""
        + fixture.chatBaseUrl()
        + "\",\"model\":\"bge-reranker-v2-m3\",\"apiKey\":\""
        + key
        + "\"}";
  }

  private void completeMandatorySteps() throws Exception {
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/FIRST_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstAdmin\":" + firstAdminJson() + "}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(authorized(post(BASE + "/steps/FIRST_ADMIN/check")))
        .andExpect(jsonPath("$.checked").value(true));
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/SIGN_IN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signIn\":{\"mode\":\"LOCAL_ONLY\"}}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(authorized(post(BASE + "/steps/SIGN_IN/check")))
        .andExpect(jsonPath("$.checked").value(true));
  }

  private ResultActions saveRerank(String json) throws Exception {
    return mockMvc.perform(
        authorized(put(BASE + "/steps/RERANK_MODEL"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rerankModel\":" + json + "}"));
  }

  private ResultActions applyAnswers(String rerankJson) throws Exception {
    return mockMvc.perform(
        authorized(post(BASE + "/answer-file"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"firstAdmin\":"
                    + firstAdminJson()
                    + ",\"signIn\":{\"mode\":\"LOCAL_ONLY\"}"
                    + (rerankJson == null ? "" : ",\"rerankModel\":" + rerankJson)
                    + "}"));
  }

  private static String firstAdminJson() {
    return "{\"email\":\""
        + InstallationFixture.ADMIN_EMAIL
        + "\",\"displayName\":\"IT-Betrieb\",\"password\":\""
        + InstallationFixture.ADMIN_PASSWORD
        + "\"}";
  }

  private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", "Bearer " + InstallationFixture.TOKEN);
  }

  private void deleteRerankRows() {
    jdbc.update("DELETE FROM rerank_model");
    jdbc.update("DELETE FROM model_role_observations WHERE base_url LIKE 'http://127.0.0.1:%'");
    endpoints.invalidateAll();
  }

  private List<String> auditTypes() {
    return jdbc.queryForList(
        "SELECT event_type FROM audit_log WHERE actor_ref = ? ORDER BY recorded_at",
        String.class,
        InstallationService.SYSTEM_ACTOR);
  }

  private String auditAfter(String type) {
    return jdbc.queryForObject(
        "SELECT after::text FROM audit_log WHERE actor_ref = ? AND event_type = ?"
            + " ORDER BY recorded_at DESC LIMIT 1",
        String.class,
        InstallationService.SYSTEM_ACTOR,
        type);
  }
}
