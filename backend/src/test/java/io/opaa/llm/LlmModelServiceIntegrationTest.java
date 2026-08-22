package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.TestcontainersConfiguration;
import io.opaa.audit.AuditEventType;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link LlmModelService} against a real Postgres with the real, versioned Liquibase schema applied
 * (migrations 058/059, #756). Covers what a test against a mocked repository could not: that the
 * API key really is encrypted before it reaches the database, that two saves of the same key
 * produce different ciphertexts, that at most one model is ever active, and that every change
 * writes exactly one audit event of the right, distinct type.
 *
 * <p>{@code @BeforeEach}/{@code @AfterEach} clear {@code llm_models} rather than assuming it starts
 * empty: {@link LlmModelSeedRunner} seeds one row from the {@code dev} profile's Ollama
 * configuration on every fresh application context, including the one this test shares with its
 * siblings (same {@code @SpringBootTest}/{@code @Import}/{@code @ActiveProfiles} signature, see
 * {@code BrandingSettingsServiceIntegrationTest}'s own Javadoc for why that signature must not
 * change lightly - a different one gets a second ApplicationContext and Postgres container).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles({"local", "dev"})
@Testcontainers(disabledWithoutDocker = true)
class LlmModelServiceIntegrationTest {

  @Autowired private LlmModelService llmModelService;
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

  private List<Map<String, Object>> auditEntries(AuditEventType eventType) {
    return jdbcTemplate.queryForList(
        "SELECT object_type, object_label, before, after FROM audit_log"
            + " WHERE organization_id = ? AND event_type = ? ORDER BY recorded_at",
        organizationId,
        eventType.name());
  }
}
