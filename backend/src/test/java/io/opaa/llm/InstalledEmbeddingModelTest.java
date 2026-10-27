package io.opaa.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.FakeEmbeddingModel;
import io.opaa.indexing.chunk.ScriptedEmbeddingEndpoint;
import io.opaa.security.SettingsEncryptionProperties;
import io.opaa.security.SettingsEncryptor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;

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
          event -> {},
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

    // the takeover of the start has just failed: the first retry waits its interval
    assertThatThrownBy(() -> model.embed("Text"))
        .isInstanceOf(EmbeddingModelNotInstalledException.class);
    verify(models, times(0)).adoptIfMissing();
    now.addAndGet(InstalledEmbeddingModel.FIRST_RETRY.toNanos());

    assertThatThrownBy(() -> model.embed("Text"))
        .isInstanceOf(EmbeddingModelNotInstalledException.class);
    assertThatThrownBy(() -> model.embed("Text"))
        .isInstanceOf(EmbeddingModelNotInstalledException.class);
    verify(models, times(1)).adoptIfMissing();

    now.addAndGet(InstalledEmbeddingModel.FIRST_RETRY.multipliedBy(2).toNanos());
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

  // regression guard for #3226: the batch path must embed the formatted content (Kontextpräfix
  // included) the installed client yields, not the bare chunk text of the interface default
  @Test
  void aBatchEmbedsTheFormattedContentOfTheInstalledClient() {
    ScriptedEmbeddingEndpoint client = ScriptedEmbeddingEndpoint.silentlyCutting(4096);
    when(repository.findInstalled()).thenReturn(Optional.of(settings()));
    when(factory.create(any())).thenReturn(client);
    Document chunk = new Document("Antragsfrist drei Monate");
    chunk.setContentFormatter((document, mode) -> "Satzung > §4\n\n" + document.getText());

    model.embed(List.of(chunk), EmbeddingOptions.builder().build(), List::of);

    assertThat(client.requests())
        .containsExactly(List.of("Satzung > §4\n\nAntragsfrist drei Monate"));
  }

  @Test
  void theClientOfTheFactoryEmbedsTheFormattedContent() {
    when(repository.findInstalled()).thenReturn(Optional.of(settings()));
    when(factory.create(any()))
        .thenAnswer(
            invocation ->
                new OpenAiEmbeddingClientFactory(Duration.ofSeconds(1), mock(ObjectProvider.class))
                    .create(invocation.getArgument(0)));
    Document chunk = new Document("Antragsfrist drei Monate");
    chunk.setContentFormatter((document, mode) -> "Satzung > §4\n\n" + document.getText());

    assertThat(model.getEmbeddingContent(chunk))
        .isEqualTo(chunk.getFormattedContent(MetadataMode.EMBED))
        .isEqualTo("Satzung > §4\n\nAntragsfrist drei Monate");
  }

  @Test
  void theFirstSuccessfulEmbeddingOfAModelWithoutAnchorAsksOnceForIt() {
    java.util.List<Object> published = new java.util.ArrayList<>();
    InstalledEmbeddingModel announcing =
        new InstalledEmbeddingModel(
            repository,
            new SettingsEncryptor(new SettingsEncryptionProperties(null)),
            factory,
            models,
            published::add,
            now::get);
    when(repository.findInstalled()).thenReturn(Optional.of(settings()));
    when(factory.create(any())).thenReturn(new FakeEmbeddingModel());

    announcing.embed("eins");
    announcing.embed("zwei");

    assertThat(published).containsExactly(new EmbeddingAnchorMissingEvent(1));
  }

  @Test
  void aClientCarriesTheGenerationOfTheRowItWasBuiltFrom() {
    when(repository.findInstalled()).thenReturn(Optional.of(settings()));
    when(factory.create(any())).thenReturn(new FakeEmbeddingModel());

    assertThat(model.pin().generation()).isEqualTo(1);
    assertThat(model.pin().maxInputTokens()).isEqualTo(4096);
  }

  // regression guard for ADR-0061, Entscheidung 5: an endpoint change keeps the generation, so a
  // client cached meanwhile must not stand in for the row of the new address
  @Test
  void aClientCachedMeanwhileForAnotherAddressOfTheSameGenerationIsNotTaken() {
    EmbeddingModelSettings moved =
        new EmbeddingModelSettings(
            "http://ollama-neu:11434/v1", "bge-m3", null, 4096, 1536, Instant.EPOCH, 1, null);
    java.util.concurrent.atomic.AtomicInteger reads =
        new java.util.concurrent.atomic.AtomicInteger();
    when(repository.findInstalled())
        .thenAnswer(
            invocation -> {
              if (reads.incrementAndGet() == 1) {
                // another caller builds and caches the client of the old address in between
                model.dimensions();
                return Optional.of(moved);
              }
              return Optional.of(settings());
            });
    java.util.List<String> built = new java.util.ArrayList<>();
    when(factory.create(any()))
        .thenAnswer(
            invocation -> {
              built.add(invocation.<EmbeddingEndpoint>getArgument(0).baseUrl());
              return new FakeEmbeddingModel();
            });

    model.embed("eins");

    assertThat(built).containsExactly("http://ollama:11434/v1", "http://ollama-neu:11434/v1");
  }

  private static EmbeddingModelSettings settings() {
    return new EmbeddingModelSettings(
        "http://ollama:11434/v1", "bge-m3", null, 4096, 1536, Instant.EPOCH, 1, null);
  }
}
