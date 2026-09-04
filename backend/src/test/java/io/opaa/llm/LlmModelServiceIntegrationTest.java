package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link LlmModelService} against a real Postgres with the real, versioned Liquibase schema applied
 * (migrations 058/059, #756). Covers what a test against a mocked repository could not: that the
 * API key really is encrypted before it reaches the database, that two saves of the same key
 * produce different ciphertexts, that at most one model is ever active, and that every change
 * writes exactly one audit event of the right, distinct type.
 *
 * <p>{@code @BeforeEach}/{@code @AfterEach} clear {@code llm_models} rather than assuming it starts
 * empty: {@link LlmModelSeeder} (triggered once by {@link LlmModelSeedRunner}) seeds one row from
 * the {@code dev} profile's Ollama configuration on every fresh application context, including the
 * one this test shares with its siblings on the canonical {@link io.opaa.test.OpaaIntegrationTest}
 * signature (AGENTS.md, "Spring-Testkontexte"). Clearing the table this way doubles as the exact
 * reproduction scenario {@link #seedingNeverResumesOnceAttemptedEvenAfterEveryModelIsDeleted()}
 * needs: a Systemverwaltung deleting every managed model, followed by a restart.
 */
@OpaaIntegrationTest
class LlmModelServiceIntegrationTest {

  private static final String SECRET_IN_BASE_URL = "benutzer:geheim";
  private static final String CREDENTIALS_BASE_URL =
      "https://" + SECRET_IN_BASE_URL + "@modellserver.example.internal/v1";

  @Autowired private LlmModelService llmModelService;
  @Autowired private LlmModelSeeder llmModelSeeder;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID userId;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM llm_models");
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "LLM Test Org")).getId();
    User user = new User(UUID.randomUUID().toString(), "test-issuer", "llm@example.com", "Test");
    user.setOrganizationId(organizationId);
    userId = userRepository.save(user).getId();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM llm_models");
    userRepository.deleteById(userId);
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void seedingNeverResumesOnceAttemptedEvenAfterEveryModelIsDeleted() {
    // llm_models is empty here (cleared in @BeforeEach) - simulating a Systemverwaltung that
    // deleted every managed model. The very first application startup in this shared context
    // already attempted the takeover and wrote the permanent llm_model_seed_marker row (migration
    // 060); re-running it explicitly - twice, to prove it is not merely "the second time is a
    // coincidence" - must not resurrect a model from the environment configuration (PR #763
    // review: the original, count()-based check would have re-seeded exactly this case).
    llmModelSeeder.seedIfNeeded();
    assertThat(llmModelService.listModels()).isEmpty();

    llmModelSeeder.seedIfNeeded();
    assertThat(llmModelService.listModels()).isEmpty();
  }

  @Test
  void createsAModelWithoutAnApiKey() {
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Ollama lokal",
            "http://ollama:11434/v1",
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            null);

    assertThat(model.getApiKeyCiphertext()).isNull();
    assertThat(model.isActive()).isFalse();
    assertThat(llmModelService.listModels()).extracting(LlmModel::getId).contains(model.getId());
  }

  @Test
  void anApiKeyIsStoredEncryptedNeverInCleartext() {
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Anbieter X",
            "https://modellserver.example.internal/v1",
            "gpt-4o",
            new BigDecimal("0.70"),
            2000,
            "sk-super-secret-key");

    String storedCiphertext =
        jdbcTemplate.queryForObject(
            "SELECT api_key_ciphertext FROM llm_models WHERE id = ?", String.class, model.getId());
    assertThat(storedCiphertext).isNotNull().doesNotContain("sk-super-secret-key");
    assertThat(model.getApiKeyCiphertext()).isEqualTo(storedCiphertext);
  }

  @Test
  void savingTheSamePlaintextKeyTwiceProducesDifferentCiphertexts() {
    LlmModel first =
        llmModelService.createModel(
            organizationId,
            userId,
            "Modell A",
            "https://modellserver.example.internal/v1",
            "gpt-4o",
            new BigDecimal("0.70"),
            2000,
            "sk-same-secret");
    LlmModel second =
        llmModelService.createModel(
            organizationId,
            userId,
            "Modell B",
            "https://modellserver.example.internal/v1",
            "gpt-4o",
            new BigDecimal("0.70"),
            2000,
            "sk-same-secret");

    assertThat(first.getApiKeyCiphertext()).isNotEqualTo(second.getApiKeyCiphertext());
  }

  @Test
  void creatingAModelRecordsExactlyOneLlmModelCreatedAuditEventWithoutTheKeyItself() {
    llmModelService.createModel(
        organizationId,
        userId,
        "Anbieter X",
        "https://modellserver.example.internal/v1",
        "gpt-4o",
        new BigDecimal("0.70"),
        2000,
        "sk-super-secret-key");

    List<Map<String, Object>> entries = auditEntries(AuditEventType.LLM_MODEL_CREATED);
    assertThat(entries).hasSize(1);
    Map<String, Object> entry = entries.getFirst();
    assertThat((String) entry.get("after")).contains("apiKeySet").contains("true");
    assertThat((String) entry.get("after")).doesNotContain("sk-super-secret-key");
  }

  @Test
  void updatingAModelLeavesTheApiKeyUnchangedWhenNoneIsGiven() {
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Anbieter X",
            "https://modellserver.example.internal/v1",
            "gpt-4o",
            new BigDecimal("0.70"),
            2000,
            "sk-super-secret-key");
    String originalCiphertext = model.getApiKeyCiphertext();

    LlmModel updated =
        llmModelService.updateModel(
            organizationId,
            userId,
            model.getId(),
            "Anbieter X (umbenannt)",
            "https://modellserver.example.internal/v1",
            "gpt-4o",
            new BigDecimal("0.50"),
            1500,
            null);

    assertThat(updated.getDisplayName()).isEqualTo("Anbieter X (umbenannt)");
    assertThat(updated.getApiKeyCiphertext()).isEqualTo(originalCiphertext);
    assertThat(auditEntries(AuditEventType.LLM_MODEL_CHANGED)).hasSize(1);
  }

  @Test
  void updatingAModelWithABlankApiKeyClearsIt() {
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Anbieter X",
            "https://modellserver.example.internal/v1",
            "gpt-4o",
            new BigDecimal("0.70"),
            2000,
            "sk-super-secret-key");

    LlmModel updated =
        llmModelService.updateModel(
            organizationId,
            userId,
            model.getId(),
            model.getDisplayName(),
            model.getBaseUrl(),
            model.getModelIdentifier(),
            model.getTemperature(),
            model.getMaxTokens(),
            "");

    assertThat(updated.getApiKeyCiphertext()).isNull();
  }

  @Test
  void activatingAModelDeactivatesWhicheverWasActiveBefore() {
    LlmModel first =
        llmModelService.createModel(
            organizationId,
            userId,
            "Erstes Modell",
            "http://ollama:11434/v1",
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            null);
    LlmModel second =
        llmModelService.createModel(
            organizationId,
            userId,
            "Zweites Modell",
            "http://ollama:11434/v1",
            "llama3",
            new BigDecimal("0.70"),
            2000,
            null);

    llmModelService.activateModel(organizationId, userId, first.getId());
    llmModelService.activateModel(organizationId, userId, second.getId());

    assertThat(llmModelService.getModel(first.getId()).isActive()).isFalse();
    assertThat(llmModelService.getModel(second.getId()).isActive()).isTrue();
    assertThat(auditEntries(AuditEventType.LLM_MODEL_ACTIVATED)).hasSize(2);
  }

  @Test
  void activatingASecondModelRecordsALlmModelDeactivatedEventForTheFirst() {
    // #757 review of #763: the model that stops being active must be traceable on its own, not
    // only indirectly via whichever model replaced it.
    LlmModel first =
        llmModelService.createModel(
            organizationId,
            userId,
            "Erstes Modell",
            "http://ollama:11434/v1",
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            null);
    LlmModel second =
        llmModelService.createModel(
            organizationId,
            userId,
            "Zweites Modell",
            "http://ollama:11434/v1",
            "llama3",
            new BigDecimal("0.70"),
            2000,
            null);

    llmModelService.activateModel(organizationId, userId, first.getId());
    assertThat(auditEntries(AuditEventType.LLM_MODEL_DEACTIVATED)).isEmpty();

    llmModelService.activateModel(organizationId, userId, second.getId());

    List<Map<String, Object>> deactivations = auditEntries(AuditEventType.LLM_MODEL_DEACTIVATED);
    assertThat(deactivations).hasSize(1);
    assertThat(deactivations.getFirst().get("object_label").toString()).contains("Erstes Modell");
  }

  @Test
  void theDatabaseRejectsASecondActiveRowEvenWhenTheServiceIsBypassed() {
    // The service is the primary defense; this proves the backstop from migration 058 is real, so
    // a future write path that forgets to deactivate the previous model cannot quietly create two
    // active rows.
    LlmModel modelA =
        llmModelService.createModel(
            organizationId,
            userId,
            "Modell A",
            "http://ollama:11434/v1",
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            null);
    llmModelService.activateModel(organizationId, userId, modelA.getId());
    LlmModel modelB =
        llmModelService.createModel(
            organizationId,
            userId,
            "Modell B",
            "http://ollama:11434/v1",
            "llama3",
            new BigDecimal("0.70"),
            2000,
            null);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE llm_models SET active = true WHERE id = ?", modelB.getId()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_llm_models_single_active");
  }

  @Test
  void deletingAModelRecordsAnAuditEventAndRemovesIt() {
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Zu löschendes Modell",
            "http://ollama:11434/v1",
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            null);

    llmModelService.deleteModel(organizationId, userId, model.getId());

    assertThat(llmModelService.listModels())
        .extracting(LlmModel::getId)
        .doesNotContain(model.getId());
    assertThat(auditEntries(AuditEventType.LLM_MODEL_DELETED)).hasSize(1);
  }

  @Test
  void deletingTheActiveModelIsRejectedWithConflict() {
    // #757 review: the check and the delete are one service call/one transaction now, not a
    // getModel/deleteModel pair the controller composes itself.
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Aktives Modell",
            "http://ollama:11434/v1",
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            null);
    llmModelService.activateModel(organizationId, userId, model.getId());

    assertThatThrownBy(() -> llmModelService.deleteModel(organizationId, userId, model.getId()))
        .isInstanceOf(ConflictException.class);

    assertThat(llmModelService.listModels()).extracting(LlmModel::getId).contains(model.getId());
    assertThat(auditEntries(AuditEventType.LLM_MODEL_DELETED)).isEmpty();
  }

  /**
   * #1147: a base address carrying userinfo would otherwise be stored verbatim and end up in the
   * audit log, in every API response and thus on the administration page. The rejection happens
   * before the row is written, so neither the model nor an audit event exists afterwards.
   */
  @Test
  void aBaseUrlWithCredentialsIsRejectedAndLeavesNeitherRowNorAuditEvent() {
    assertThatThrownBy(
            () ->
                llmModelService.createModel(
                    organizationId,
                    userId,
                    "Modell mit Anmeldedaten",
                    CREDENTIALS_BASE_URL,
                    "phi3:mini",
                    new BigDecimal("0.70"),
                    2000,
                    null))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET_IN_BASE_URL));

    assertThat(llmModelService.listModels()).isEmpty();
    assertThat(auditEntries(AuditEventType.LLM_MODEL_CREATED)).isEmpty();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM llm_models WHERE base_url LIKE ?",
                Integer.class,
                "%" + SECRET_IN_BASE_URL + "%"))
        .isZero();
  }

  /** The same rule on the update path - an existing, clean entry cannot be edited into one. */
  @Test
  void updatingAModelToABaseUrlWithCredentialsIsRejectedAndWritesNoAuditEvent() {
    LlmModel model =
        llmModelService.createModel(
            organizationId,
            userId,
            "Sauberes Modell",
            "http://ollama:11434/v1",
            "phi3:mini",
            new BigDecimal("0.70"),
            2000,
            null);

    assertThatThrownBy(
            () ->
                llmModelService.updateModel(
                    organizationId,
                    userId,
                    model.getId(),
                    "Sauberes Modell",
                    CREDENTIALS_BASE_URL,
                    "phi3:mini",
                    new BigDecimal("0.70"),
                    2000,
                    null))
        .isInstanceOf(ValidationException.class)
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SECRET_IN_BASE_URL));

    assertThat(auditEntries(AuditEventType.LLM_MODEL_CHANGED)).isEmpty();
    assertThat(llmModelService.getModel(model.getId()).getBaseUrl())
        .isEqualTo("http://ollama:11434/v1");
  }

  private List<Map<String, Object>> auditEntries(AuditEventType eventType) {
    return jdbcTemplate.queryForList(
        "SELECT object_type, object_label, before, after FROM audit_log"
            + " WHERE organization_id = ? AND event_type = ? ORDER BY recorded_at",
        organizationId,
        eventType.name());
  }
}
