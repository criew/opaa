package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.opaa.common.ConflictException;
import io.opaa.common.InstallationStatus;
import io.opaa.knowledge.VectorTableCreator;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The takeover of an older installation's embedding model and the installation of a new one: an
 * existing vector table is taken over only for the model its index records, always with the size of
 * the existing column and without a probe; without a table a probe decides; credentials in the
 * address, a failed probe, a missing settings key and a pending installation take over nothing.
 */
class EmbeddingModelServiceTest {

  private static final String KEY = "c2V0dGluZ3NkZXZrZXkwMHNldHRpbmdzZGV2a2V5MDE=";

  private final EmbeddingModelSettingsRepository repository =
      mock(EmbeddingModelSettingsRepository.class);
  private final EmbeddingProbe probe = mock(EmbeddingProbe.class);
  private final VectorTableCreator table = mock(VectorTableCreator.class);
  private final RecordedEmbeddingIndex index = mock(RecordedEmbeddingIndex.class);
  private final InstallationStatus status = mock(InstallationStatus.class);
  private final EmbeddingGenerations generations = mock(EmbeddingGenerations.class);
  private final MockEnvironment environment =
      new MockEnvironment()
          .withProperty(DevelopmentEmbeddingConfiguration.MODE_PROPERTY, "dev")
          .withProperty(DevelopmentEmbeddingConfiguration.BASE_URL, "http://ollama:11434/v1")
          .withProperty(DevelopmentEmbeddingConfiguration.MODEL, "bge-m3")
          .withProperty(DevelopmentEmbeddingConfiguration.DIMENSIONS, "1024");

  @BeforeEach
  void setUp() {
    when(repository.findInstalled()).thenReturn(Optional.empty());
    when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
    when(table.maxDimensions()).thenReturn(2000);
    when(status.isPending()).thenReturn(false);
  }

  // regression guard: the size of an existing table comes from its column, not the environment
  @Test
  void anExistingTableIsTakenOverForTheRecordedModelWithTheSizeOfItsColumnAndWithoutAProbe() {
    givenTable(768, null);
    when(index.recorded())
        .thenReturn(Optional.of(new RecordedEmbeddingIndex.Recorded("bge-m3", null, 1, null)));

    assertThat(service(KEY).adoptIfMissing()).isTrue();

    assertThat(saved().getDimensions()).isEqualTo(768);
    assertThat(saved().getModelIdentifier()).isEqualTo("bge-m3");
    verifyNoInteractions(probe);
    verify(table, never()).createIfAbsent(anyInt());
  }

  // regression guard: an environment naming another model than the index takes over nothing
  @Test
  void anEnvironmentThatNamesAnotherModelThanTheIndexTakesOverNothing() {
    givenTable(768, null);
    when(index.recorded())
        .thenReturn(
            Optional.of(new RecordedEmbeddingIndex.Recorded("nomic-embed-text", null, 1, null)));

    assertThat(service(KEY).adoptIfMissing()).isFalse();

    verify(repository, never()).save(any());
    verifyNoInteractions(probe);
  }

  @Test
  void theModelOfARunningReEmbeddingIsTakenOverWithTheSizeOfTheTargetColumn() {
    givenTable(768, 1024);
    when(index.recorded())
        .thenReturn(
            Optional.of(new RecordedEmbeddingIndex.Recorded("nomic-embed-text", "bge-m3", 1, 2L)));

    assertThat(service(KEY).adoptIfMissing()).isTrue();

    assertThat(saved().getDimensions()).isEqualTo(1024);
  }

  @Test
  void withoutATableAProbeDecidesTheSizeAndTheTableIsCreated() {
    when(table.exists()).thenReturn(false);
    when(probe.probe(any(), any()))
        .thenReturn(new EmbeddingModelService.ProbeOutcome(true, null, 384));

    assertThat(service(KEY).adoptIfMissing()).isTrue();

    verify(table).createIfAbsent(384);
    assertThat(saved().getDimensions()).isEqualTo(384);
  }

  @Test
  void anotherModeThanDevInstallsNothingFromTheConfiguration() {
    environment.setProperty(DevelopmentEmbeddingConfiguration.MODE_PROPERTY, "oidc");
    when(table.exists()).thenReturn(false);

    assertThat(service(KEY).adoptIfMissing()).isFalse();

    verifyNoInteractions(probe);
    verify(repository, never()).save(any());
  }

  @Test
  void aFailedProbeTakesOverNothing() {
    when(table.exists()).thenReturn(false);
    when(probe.probe(any(), any()))
        .thenReturn(new EmbeddingModelService.ProbeOutcome(false, "nicht erreichbar", null));

    assertThat(service(KEY).adoptIfMissing()).isFalse();

    verify(repository, never()).save(any());
    verify(table, never()).createIfAbsent(anyInt());
  }

  @Test
  void anAddressWithCredentialsIsNeitherProbedNorTakenOver() {
    environment.setProperty(
        DevelopmentEmbeddingConfiguration.BASE_URL, "https://nutzer:geheim@llm.example/v1");

    assertThat(service(KEY).adoptIfMissing()).isFalse();

    verifyNoInteractions(probe);
    verify(repository, never()).save(any());
  }

  @Test
  void aKeyWithoutTheSettingsKeyTakesOverNothing() {
    givenTable(1024, null);
    when(index.recorded())
        .thenReturn(Optional.of(new RecordedEmbeddingIndex.Recorded("bge-m3", null, 1, null)));
    environment.setProperty(DevelopmentEmbeddingConfiguration.API_KEY, "sk-geheim");

    assertThat(service(null).adoptIfMissing()).isFalse();

    verify(repository, never()).save(any());
  }

  @Test
  void aPendingInstallationTakesOverNothing() {
    when(status.isPending()).thenReturn(true);

    assertThat(service(KEY).adoptIfMissing()).isFalse();

    verifyNoInteractions(probe, table);
  }

  @Test
  void anInstallationReplacesAnEmptyTableOfAnotherSizeAndRefusesOneWithChunks() {
    EmbeddingEndpoint endpoint =
        new EmbeddingEndpoint("http://ollama:11434/v1", "bge-m3", null, 2048);
    when(table.embeddingDimensions()).thenReturn(1024);
    when(table.hasRows()).thenReturn(false);

    service(KEY).install(endpoint, 768);

    verify(table).dropEmpty();
    verify(table).createIfAbsent(768);

    when(table.hasRows()).thenReturn(true);
    assertThatThrownBy(() -> service(KEY).install(endpoint, 768))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void aProbeOfMoreDimensionsThanTheIndexTakesFails() {
    when(probe.probe(any(), any()))
        .thenReturn(new EmbeddingModelService.ProbeOutcome(true, null, 3072));

    EmbeddingModelService.ProbeOutcome outcome =
        service(KEY)
            .probe(
                new EmbeddingEndpoint(
                    "http://ollama:11434/v1", "text-embedding-3-large", null, 8191));

    assertThat(outcome.succeeded()).isFalse();
    assertThat(outcome.message()).contains("3072", "2000");
  }

  private void givenTable(int current, Integer target) {
    when(table.exists()).thenReturn(true);
    when(table.embeddingDimensions()).thenReturn(current);
    when(table.dimensionsOf(VectorTableCreator.EMBEDDING_COLUMN)).thenReturn(current);
    when(table.dimensionsOf(VectorTableCreator.TARGET_COLUMN)).thenReturn(target);
  }

  private EmbeddingModelSettings saved() {
    ArgumentCaptor<EmbeddingModelSettings> settings =
        ArgumentCaptor.forClass(EmbeddingModelSettings.class);
    verify(repository).save(settings.capture());
    return settings.getValue();
  }

  private EmbeddingModelService service(String settingsKey) {
    return new EmbeddingModelService(
        repository,
        probe,
        new SettingsEncryptor(new SettingsEncryptionProperties(settingsKey)),
        table,
        new DevelopmentEmbeddingConfiguration(environment),
        index,
        mock(ApplicationEventPublisher.class),
        status,
        generations,
        mock(PlatformTransactionManager.class),
        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
  }
}
