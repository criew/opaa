package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.ToolCapability;
import io.opaa.api.types.ToolCapabilityBasis;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.common.ConflictException;
import io.opaa.common.ValidationException;
import io.opaa.security.SettingsEncryptor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The tool capability of a chat model: three values, a basis instead of a person, a date, and a
 * reset to {@link ToolCapability#UNVERIFIED} whenever the combination it was made for changes.
 */
class LlmModelToolCapabilityTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();
  private static final UUID ACTOR = UUID.randomUUID();
  private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");
  private static final String RUN = "Lauf 2026-11-09, qwen2.5:14b auf Ollama 0.6";

  private final LlmModelRepository repository = mock(LlmModelRepository.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final LlmModelService service =
      new LlmModelService(
          repository,
          mock(SettingsEncryptor.class),
          audit,
          mock(ApplicationEventPublisher.class),
          ModelAddressPolicy.disabled(),
          mock(PlatformTransactionManager.class),
          Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aNewModelStartsUnverifiedWithoutBasisDateOrRun() {
    LlmModel model = stored();

    assertThat(model.getToolCapability()).isEqualTo(ToolCapability.UNVERIFIED);
    assertUnverified(model);
  }

  @Test
  void aManualStatementCarriesItsBasisAndDateButNoRun() {
    LlmModel model = stored();

    set(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MANUAL, null);

    assertThat(model.getToolCapability()).isEqualTo(ToolCapability.CAPABLE);
    assertThat(model.getToolCapabilityBasis()).isEqualTo(ToolCapabilityBasis.MANUAL);
    assertThat(model.getToolCapabilityAssessedAt()).isEqualTo(NOW);
    assertThat(model.getToolCapabilityMeasurementRun()).isNull();
  }

  @Test
  void aMeasuredStatementCarriesItsMeasurementRun() {
    LlmModel model = stored();

    set(model, ToolCapability.NOT_CAPABLE, ToolCapabilityBasis.MEASURED, "  " + RUN + " ");

    assertThat(model.getToolCapability()).isEqualTo(ToolCapability.NOT_CAPABLE);
    assertThat(model.getToolCapabilityBasis()).isEqualTo(ToolCapabilityBasis.MEASURED);
    assertThat(model.getToolCapabilityMeasurementRun()).isEqualTo(RUN);
    assertThat(model.getToolCapabilityAssessedAt()).isEqualTo(NOW);
  }

  @Test
  void resettingToUnverifiedClearsBasisRunAndDate() {
    LlmModel model = stored();
    set(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MEASURED, RUN);

    set(model, ToolCapability.UNVERIFIED, null, null);

    assertUnverified(model);
  }

  @Test
  void inconsistentStatementsAreRefusedAndChangeNothing() {
    LlmModel model = stored();

    assertRefused(model, ToolCapability.CAPABLE, null, null, "Grundlage");
    assertRefused(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MEASURED, null, "Messlauf");
    assertRefused(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MEASURED, "  ", "Messlauf");
    assertRefused(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MANUAL, RUN, "Messlauf");
    assertRefused(model, ToolCapability.UNVERIFIED, ToolCapabilityBasis.MANUAL, null, "ungeprüft");
    assertRefused(model, ToolCapability.UNVERIFIED, null, RUN, "ungeprüft");
    assertRefused(model, null, null, null, "Werkzeugfähigkeit");

    assertUnverified(model);
    verify(repository, never()).save(model);
  }

  @Test
  void aStatementForAnOutdatedCombinationIsAConflictAndChangesNothing() {
    LlmModel model = stored();

    assertThatThrownBy(
            () ->
                service.setToolCapability(
                    ORGANIZATION,
                    ACTOR,
                    model.getId(),
                    "https://vorher.example/v1",
                    model.getModelIdentifier(),
                    ToolCapability.CAPABLE,
                    ToolCapabilityBasis.MANUAL,
                    null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("neu laden");
    assertThatThrownBy(
            () ->
                service.setToolCapability(
                    ORGANIZATION,
                    ACTOR,
                    model.getId(),
                    model.getBaseUrl(),
                    "qwen2.5:7b",
                    ToolCapability.CAPABLE,
                    ToolCapabilityBasis.MANUAL,
                    null))
        .isInstanceOf(ConflictException.class);

    assertUnverified(model);
    verify(repository, never()).save(model);
  }

  @Test
  void theCombinationIsReadUnderARowLock() {
    LlmModel model = stored();

    set(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MANUAL, null);
    update(model, model.getBaseUrl(), model.getModelIdentifier());

    verify(repository, times(2)).findLockedById(model.getId());
  }

  @Test
  void aChangeIsAuditedAsAChangeOfTheModelWithoutAPersonInTheState() {
    LlmModel model = stored();

    set(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MANUAL, null);

    ArgumentCaptor<AuditEvent> event = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).recordUserAction(event.capture());
    assertThat(event.getValue().eventType()).isEqualTo(AuditEventType.LLM_MODEL_CHANGED);
    assertThat(event.getValue().before()).containsEntry("toolCapability", "UNVERIFIED");
    assertThat(event.getValue().after())
        .containsEntry("toolCapability", "CAPABLE")
        .containsEntry("toolCapabilityBasis", "MANUAL")
        .containsEntry("toolCapabilityAssessedAt", NOW.toString())
        .doesNotContainKeys("toolCapabilityMeasurementRun");
  }

  @Test
  void aChangedEndpointResetsTheStatement() {
    LlmModel model = assessed();

    update(model, "https://anderer.example/v1", model.getModelIdentifier());

    assertUnverified(model);
  }

  @Test
  void aChangedModelIdentifierResetsTheStatement() {
    LlmModel model = assessed();

    update(model, model.getBaseUrl(), "qwen2.5:32b");

    assertUnverified(model);
  }

  @Test
  void anEditKeepingEndpointAndModelIdentifierKeepsTheStatement() {
    LlmModel model = assessed();

    service.updateModel(
        ORGANIZATION,
        ACTOR,
        model.getId(),
        "Neuer Anzeigename",
        model.getBaseUrl(),
        model.getModelIdentifier(),
        new BigDecimal("0.20"),
        4000,
        null);

    assertThat(model.getToolCapability()).isEqualTo(ToolCapability.CAPABLE);
    assertThat(model.getToolCapabilityBasis()).isEqualTo(ToolCapabilityBasis.MEASURED);
    assertThat(model.getToolCapabilityMeasurementRun()).isEqualTo(RUN);
  }

  private LlmModel stored() {
    LlmModel model =
        new LlmModel(
            "Ollama",
            "https://ollama.example/v1",
            "qwen2.5:14b",
            new BigDecimal("0.70"),
            2000,
            null);
    when(repository.findById(model.getId())).thenReturn(Optional.of(model));
    when(repository.findLockedById(model.getId())).thenReturn(Optional.of(model));
    return model;
  }

  private LlmModel assessed() {
    LlmModel model = stored();
    set(model, ToolCapability.CAPABLE, ToolCapabilityBasis.MEASURED, RUN);
    return model;
  }

  private void set(
      LlmModel model, ToolCapability capability, ToolCapabilityBasis basis, String run) {
    service.setToolCapability(
        ORGANIZATION,
        ACTOR,
        model.getId(),
        model.getBaseUrl(),
        model.getModelIdentifier(),
        capability,
        basis,
        run);
  }

  private void update(LlmModel model, String baseUrl, String modelIdentifier) {
    service.updateModel(
        ORGANIZATION,
        ACTOR,
        model.getId(),
        model.getDisplayName(),
        baseUrl,
        modelIdentifier,
        model.getTemperature(),
        model.getMaxTokens(),
        null);
  }

  private void assertRefused(
      LlmModel model,
      ToolCapability capability,
      ToolCapabilityBasis basis,
      String run,
      String messagePart) {
    assertThatThrownBy(() -> set(model, capability, basis, run))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining(messagePart);
  }

  private static void assertUnverified(LlmModel model) {
    assertThat(model.getToolCapability()).isEqualTo(ToolCapability.UNVERIFIED);
    assertThat(model.getToolCapabilityBasis()).isNull();
    assertThat(model.getToolCapabilityAssessedAt()).isNull();
    assertThat(model.getToolCapabilityMeasurementRun()).isNull();
  }
}
