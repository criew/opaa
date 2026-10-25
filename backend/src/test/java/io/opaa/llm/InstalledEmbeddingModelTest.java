package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.FakeEmbeddingModel;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * The application's embedding model: refused before one is installed, the client built once and
 * cached until the bus invalidates it, and a completed installation without a model tries the
 * takeover again only after its backoff.
 */
class InstalledEmbeddingModelTest {

  private final EmbeddingModelSettingsRepository repository =
      mock(EmbeddingModelSettingsRepository.class);
  private final EmbeddingClientFactory factory = mock(EmbeddingClientFactory.class);
  private final EmbeddingModelService models = mock(EmbeddingModelService.class);
  private final AtomicLong now = new AtomicLong(0);
  private final InstalledEmbeddingModel model =
      new InstalledEmbeddingModel(
          repository,
          new SettingsEncryptor(new SettingsEncryptionProperties(null)),
          factory,
          models,
          now::get);

  @Test
  void beforeAnInstallationEveryUseIsRefusedWithoutATakeover() {
    when(repository.findInstalled()).thenReturn(Optional.empty());
    when(models.missingAfterInstallation()).thenReturn(false);

    assertThatThrownBy(() -> model.embed("Text"))
        .isInstanceOf(EmbeddingModelNotInstalledException.class);
    assertThatThrownBy(model::dimensions).isInstanceOf(EmbeddingModelNotInstalledException.class);
    verify(models, times(0)).adoptIfMissing();
  }

  @Test
  void aCompletedInstallationWithoutAModelRetriesTheTakeoverAfterItsBackoff() {
    when(repository.findInstalled()).thenReturn(Optional.empty());
    when(models.missingAfterInstallation()).thenReturn(true);
    when(models.adoptIfMissing()).thenReturn(false);

    assertThatThrownBy(() -> model.embed("Text"))
        .isInstanceOf(EmbeddingModelNotInstalledException.class);
    assertThatThrownBy(() -> model.embed("Text"))
        .isInstanceOf(EmbeddingModelNotInstalledException.class);
    verify(models, times(1)).adoptIfMissing();

    now.addAndGet(InstalledEmbeddingModel.FIRST_RETRY.toNanos());
    when(models.adoptIfMissing()).thenReturn(true);
    when(repository.findInstalled())
        .thenReturn(Optional.empty())
        .thenReturn(Optional.of(settings()));
    when(factory.create(any())).thenReturn(new FakeEmbeddingModel());

    assertThat(model.embed("Text")).hasSize(FakeEmbeddingModel.DIMENSIONS);
    verify(models, times(2)).adoptIfMissing();
  }

  @Test
  void theClientIsBuiltOnceAndRebuiltAfterAnInvalidation() {
    when(repository.findInstalled()).thenReturn(Optional.of(settings()));
    when(factory.create(any())).thenReturn(new FakeEmbeddingModel());

    model.embed("eins");
    model.embed("zwei");
    assertThat(model.dimensions()).isEqualTo(1536);
    assertThat(model.maxInputTokens()).isEqualTo(4096);
    verify(factory, times(1)).create(any());

    model.invalidateAll();
    model.embed("drei");
    verify(factory, times(2)).create(any());
  }

  private static EmbeddingModelSettings settings() {
    return new EmbeddingModelSettings(
        "http://ollama:11434/v1", "bge-m3", null, 4096, 1536, Instant.EPOCH);
  }
}
