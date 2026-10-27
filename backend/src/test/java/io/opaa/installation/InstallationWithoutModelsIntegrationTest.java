package io.opaa.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.indexing.chunk.EmbeddingIndexReconciler;
import io.opaa.knowledge.VectorTable;
import io.opaa.test.LocalAccountFixturesFactory;
import io.opaa.test.OpaaLocalAuthMockMvcTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * An installation without chat and embedding model (ADR-0060, Entscheidungen 6 and 10): both model
 * steps are optional, the completion succeeds without them and creates neither model, a saved but
 * unchecked model step still blocks until it is checked or skipped, an answer file may leave both
 * out, and a start of such an installation is a regular state - no error in the log, the health
 * group of the vector store {@code UNKNOWN} instead of {@code DOWN}.
 */
@OpaaLocalAuthMockMvcTest
@ExtendWith(OutputCaptureExtension.class)
class InstallationWithoutModelsIntegrationTest {

  private static final String BASE = "/api/v1/installation";
  private static final String KEPT_TABLE = "vector_store_kept_without_models";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private InstallationService installation;
  @Autowired private DatabaseInstallationStatus status;
  @Autowired private LocalAccountFixturesFactory accountsFactory;
  @Autowired private LocalCredentialsRepository credentials;
  @Autowired private InstallationStartup startup;
  @Autowired private EmbeddingIndexReconciler reconciler;
  @Autowired private HealthEndpoint healthEndpoint;
  @Autowired private VectorTable vectorTable;

  private InstallationFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    fixture = new InstallationFixture(jdbc, installation, status, accountsFactory.create());
  }

  @AfterEach
  void tearDown() {
    fixture.close();
  }

  // regression guard: a completion without model steps was refused with 409 naming both of them
  @Test
  void theCompletionSucceedsWithoutModelStepsAndCreatesNeitherModel() throws Exception {
    mockMvc
        .perform(authorized(get(BASE)))
        .andExpect(jsonPath("$.steps[?(@.step == 'CHAT_MODEL')].mandatory").value(false))
        .andExpect(jsonPath("$.steps[?(@.step == 'EMBEDDING_MODEL')].mandatory").value(false));
    saveAndCheckTheMandatorySteps();
    mockMvc.perform(authorized(get(BASE))).andExpect(jsonPath("$.completable").value(true));

    mockMvc
        .perform(authorized(post(BASE + "/complete")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pending").value(false));

    assertThat(credentials.findByBootstrapTrue()).isPresent();
    assertThat(installationChatModels()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_model", Integer.class)).isZero();
    assertThat(completedAudit()).contains("CHAT_MODEL").contains("EMBEDDING_MODEL");
  }

  @Test
  void aSavedButUncheckedModelStepBlocksUntilItIsSkipped() throws Exception {
    saveAndCheckTheMandatorySteps();
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/CHAT_MODEL"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"chatModel\":{\"baseUrl\":\"http://127.0.0.1:1/v1\",\"model\":\"m\"}}"))
        .andExpect(status().isOk());
    check("CHAT_MODEL").andExpect(jsonPath("$.checked").value(false));
    mockMvc.perform(authorized(get(BASE))).andExpect(jsonPath("$.completable").value(false));
    mockMvc
        .perform(authorized(post(BASE + "/complete")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("CHAT_MODEL")));

    mockMvc
        .perform(authorized(post(BASE + "/steps/CHAT_MODEL/skip")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.saved").value(false));

    mockMvc.perform(authorized(post(BASE + "/complete"))).andExpect(status().isOk());
    assertThat(installationChatModels()).isZero();
  }

  @Test
  void aMandatoryStepCannotBeSkipped() throws Exception {
    mockMvc
        .perform(authorized(post(BASE + "/steps/FIRST_ADMIN/skip")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void anAnswerFileWithoutModelsInstalls() throws Exception {
    mockMvc
        .perform(
            authorized(post(BASE + "/answer-file"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"firstAdmin\":"
                        + firstAdminJson()
                        + ",\"signIn\":{\"mode\":\"LOCAL_ONLY\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pending").value(false));

    assertThat(credentials.findByBootstrapTrue()).isPresent();
    assertThat(installationChatModels()).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM embedding_model", Integer.class)).isZero();
  }

  // regression guard: a start without an embedding model wrote an error and reported the vector
  // store DOWN, although the installation may skip its embedding model
  @Test
  void aStartWithoutAnEmbeddingModelIsARegularState(CapturedOutput output) throws Exception {
    completedWithoutEmbeddingModel();
    withoutVectorTable(
        () -> {
          int before = output.getAll().length();

          startup.run();
          reconciler.start();

          String startLog = output.getAll().substring(before);
          assertThat(startLog.lines().filter(line -> line.contains(" ERROR ")).toList()).isEmpty();
          assertThat(startLog).contains("No embedding model is set up yet");
          assertThat(healthEndpoint.healthForPath("vector-store").getStatus())
              .isEqualTo(Status.UNKNOWN);
          mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        });
  }

  @Test
  void chunksWithoutAModelRowAreAnErrorAtTheStart(CapturedOutput output) throws Exception {
    completedWithoutEmbeddingModel();
    withoutVectorTable(
        () -> {
          jdbc.execute(
              "CREATE TABLE "
                  + vectorTable.qualifiedName()
                  + " (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text, metadata json,"
                  + " embedding vector(3))");
          jdbc.update(
              "INSERT INTO "
                  + vectorTable.qualifiedName()
                  + " (content, metadata) VALUES ('Altbestand', '{}')");
          int before = output.getAll().length();

          startup.run();

          String startLog = output.getAll().substring(before);
          assertThat(startLog.lines().filter(line -> line.contains(" ERROR ")))
              .anyMatch(line -> line.contains("holds chunks"));
          assertThat(healthEndpoint.healthForPath("vector-store").getStatus())
              .isEqualTo(Status.DOWN);
        });
  }

  /** A completed installation without an embedding model, however it got there. */
  private void completedWithoutEmbeddingModel() {
    jdbc.update(
        "UPDATE installation_state SET status = 'COMPLETED', completion_source = 'INSTALLATION_API',"
            + " completed_at = clock_timestamp(), token_hash = NULL, token_source = NULL"
            + " WHERE id = 1");
    status.reload();
  }

  /** Runs {@code check} while the vector table of the context is moved aside, then puts it back. */
  private void withoutVectorTable(Check check) throws Exception {
    jdbc.execute("ALTER TABLE " + vectorTable.qualifiedName() + " RENAME TO " + KEPT_TABLE);
    try {
      check.run();
    } finally {
      jdbc.execute("DROP TABLE IF EXISTS " + vectorTable.qualifiedName());
      jdbc.execute(
          "ALTER TABLE "
              + vectorTable.schema()
              + "."
              + KEPT_TABLE
              + " RENAME TO "
              + vectorTable.tableName());
    }
  }

  @FunctionalInterface
  private interface Check {
    void run() throws Exception;
  }

  private void saveAndCheckTheMandatorySteps() throws Exception {
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/FIRST_ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstAdmin\":" + firstAdminJson() + "}"))
        .andExpect(status().isOk());
    check("FIRST_ADMIN").andExpect(jsonPath("$.checked").value(true));
    mockMvc
        .perform(
            authorized(put(BASE + "/steps/SIGN_IN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"signIn\":{\"mode\":\"LOCAL_ONLY\"}}"))
        .andExpect(status().isOk());
    check("SIGN_IN").andExpect(jsonPath("$.checked").value(true));
  }

  private ResultActions check(String step) throws Exception {
    return mockMvc
        .perform(authorized(post(BASE + "/steps/" + step + "/check")))
        .andExpect(status().isOk());
  }

  private int installationChatModels() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM llm_models WHERE display_name = ?",
            Integer.class,
            InstallationService.CHAT_MODEL_DISPLAY_NAME);
    return count == null ? 0 : count;
  }

  private String completedAudit() {
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "SELECT after::text AS after FROM audit_log WHERE actor_ref = ? AND event_type ="
                + " 'INSTALLATION_COMPLETED'",
            InstallationService.SYSTEM_ACTOR);
    return rows.isEmpty() ? "" : String.valueOf(rows.getFirst().get("after"));
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
}
