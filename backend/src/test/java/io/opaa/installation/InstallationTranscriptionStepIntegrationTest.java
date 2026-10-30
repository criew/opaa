package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.llm.ModelRoleAdministration;
import io.opaa.security.EncryptedColumn;
import io.opaa.security.SettingsEncryptor;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthLinkTest;
import java.util.List;
import java.util.Map;
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
 * The optional transcription model step of the installation end to end (ADR-0060, Entscheidung 16),
 * with speech input on as by default: skipped, speech input stays off and the completion names the
 * step among the skipped ones; checked with the probe of the model administration against a local
 * endpoint, the completion switches the role on through the write path of the model administration,
 * audited under the installation, with the key saved in the step. The answers take the same part.
 * The model role's row is removed before and after each test.
 */
@OpaaLocalAuthLinkTest
class InstallationTranscriptionStepIntegrationTest {

  private static final String BASE = "/api/v1/installation";
  private static final String KEY = "sk-transkription-0123456789";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private ModelRoleAdministration modelRoles;
  @Autowired private SettingsEncryptor encryptor;

  private InstallationFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    deleteRole();
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
  }

  @AfterEach
  void tearDown() {
    fixture.close();
    deleteRole();
  }

  @Test
  void aSkippedTranscriptionStepLeavesSpeechInputOff() throws Exception {
    completeMandatorySteps();
    mockMvc
        .perform(authorized(get(BASE)))
        .andExpect(jsonPath("$.steps[?(@.step == 'TRANSCRIPTION_MODEL')].mandatory").value(false))
        .andExpect(jsonPath("$.steps[?(@.step == 'TRANSCRIPTION_MODEL')].saved").value(false));
    saveTranscription(transcriptionJson(fixture.chatBaseUrl(), KEY, "de"))
        .andExpect(status().isOk());

    mockMvc
        .perform(authorized(post(BASE + "/steps/TRANSCRIPTION_MODEL/skip")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saved").value(false))
        .andExpect(jsonPath("$.transcriptionModel").doesNotExist());
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM transcription_model", Integer.class))
        .isZero();
    assertThat(modelRoles.transcription().enabled()).isFalse();
    assertThat(auditTypes()).doesNotContain("TRANSCRIPTION_MODEL_CHANGED");
    assertThat(auditAfter("INSTALLATION_STEP_SKIPPED"))
        .contains("\"TRANSCRIPTION_MODEL\"")
        .contains("\"discarded\":true");
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"TRANSCRIPTION_MODEL\"");
  }

  @Test
  void aCheckedTranscriptionStepSwitchesTheRoleOnThroughTheModelAdministration() throws Exception {
    completeMandatorySteps();
    saveTranscription(transcriptionJson(fixture.chatBaseUrl(), KEY, " EN "))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transcriptionModel.baseUrl").value(fixture.chatBaseUrl()))
        .andExpect(jsonPath("$.transcriptionModel.apiKeySet").value(true))
        .andExpect(jsonPath("$.transcriptionModel.language").value("en"))
        .andExpect(jsonPath("$.transcriptionModel.apiKey").doesNotExist())
        .andExpect(jsonPath("$.checked").value(false));
    mockMvc
        .perform(authorized(post(BASE + "/steps/TRANSCRIPTION_MODEL/check")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.checked").value(true))
        .andExpect(
            jsonPath("$.checkMessage")
                .value("Verbindung erfolgreich, der Endpunkt hat geantwortet."));
    assertThat(fixture.lastModelsAuthorization()).isEqualTo("Bearer " + KEY);

    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());

    Map<String, Object> row = jdbc.queryForMap("SELECT * FROM transcription_model");
    assertThat(row.get("enabled")).isEqualTo(true);
    assertThat(row.get("base_url")).isEqualTo(fixture.chatBaseUrl());
    assertThat(row.get("model_identifier")).isEqualTo("whisper-1");
    assertThat(row.get("language")).isEqualTo("en");
    assertThat(
            encryptor.decrypt(
                (String) row.get("api_key_ciphertext"),
                EncryptedColumn.settings("transcription_model", "api_key_ciphertext").row(1)))
        .isEqualTo(KEY);
    assertThat(auditAfter("TRANSCRIPTION_MODEL_CHANGED"))
        .contains("\"enabled\":true")
        .contains("\"apiKeyChange\":\"SET\"")
        .doesNotContain(KEY);
    assertThat(auditAfter("INSTALLATION_STEP_SAVED"))
        .contains("\"language\":\"en\"")
        .contains("\"apiKeySet\":true")
        .doesNotContain(KEY);
    assertThat(auditAfter("INSTALLATION_COMPLETED")).doesNotContain("\"TRANSCRIPTION_MODEL\"");
  }

  @Test
  void theTranscriptionStepListsTheModelsOfItsEndpoint() throws Exception {
    mockMvc
        .perform(
            authorized(post(BASE + "/steps/TRANSCRIPTION_MODEL/discover"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseUrl\":\"" + fixture.chatBaseUrl() + "\",\"apiKey\":\"\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  void aSavedButUncheckedTranscriptionStepHoldsUpTheCompletion() throws Exception {
    completeMandatorySteps();
    saveTranscription("{\"baseUrl\":\"ftp://stt\",\"model\":\"\",\"language\":\"deutsch\"}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.fieldErrors[0].field").value("transcriptionModel.baseUrl"))
        .andExpect(jsonPath("$.fieldErrors[2].field").value("transcriptionModel.language"));

    saveTranscription(transcriptionJson(fixture.chatBaseUrl(), null, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.transcriptionModel.language").value("de"));
    mockMvc.perform(authorized(get(BASE))).andExpect(jsonPath("$.completable").value(false));
    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isConflict());
  }

  @Test
  void anAnswerWithATranscriptionModelSwitchesTheRoleOn() throws Exception {
    applyAnswers(transcriptionJson(fixture.chatBaseUrl(), KEY, "")).andExpect(status().isOk());

    assertThat(modelRoles.transcription().enabled()).isTrue();
    assertThat(modelRoles.transcription().model()).isEqualTo("whisper-1");
    assertThat(modelRoles.transcription().language()).isEmpty();
    assertThat(modelRoles.transcription().apiKeySet()).isTrue();
  }

  @Test
  void anAnswerWithoutTranscriptionModelDiscardsTheSavedStep() throws Exception {
    saveTranscription(transcriptionJson(fixture.chatBaseUrl(), KEY, "de"))
        .andExpect(status().isOk());

    applyAnswers(null).andExpect(status().isOk());

    assertThat(jdbc.queryForObject("SELECT count(*) FROM transcription_model", Integer.class))
        .isZero();
    assertThat(auditAfter("INSTALLATION_COMPLETED")).contains("\"TRANSCRIPTION_MODEL\"");
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

  private static String transcriptionJson(String baseUrl, String apiKey, String language) {
    return "{\"baseUrl\":\""
        + baseUrl
        + "\",\"model\":\"whisper-1\""
        + (apiKey == null ? "" : ",\"apiKey\":\"" + apiKey + "\"")
        + (language == null ? "" : ",\"language\":\"" + language + "\"")
        + "}";
  }

  private ResultActions saveTranscription(String json) throws Exception {
    return mockMvc.perform(
        authorized(put(BASE + "/steps/TRANSCRIPTION_MODEL"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"transcriptionModel\":" + json + "}"));
  }

  private ResultActions applyAnswers(String transcriptionJson) throws Exception {
    return mockMvc.perform(
        authorized(post(BASE + "/answer-file"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"firstAdmin\":"
                    + firstAdminJson()
                    + ",\"signIn\":{\"mode\":\"LOCAL_ONLY\"}"
                    + (transcriptionJson == null
                        ? ""
                        : ",\"transcriptionModel\":" + transcriptionJson)
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

  private void deleteRole() {
    jdbc.update("DELETE FROM transcription_model");
    jdbc.update("DELETE FROM model_role_observations WHERE base_url LIKE 'http://127.0.0.1:%'");
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
