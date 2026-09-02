package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.indexing.FullTextBackfillProgressService;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.llm.EmbeddingInfo;
import io.opaa.llm.EmbeddingInfoService;
import io.opaa.llm.LlmModel;
import io.opaa.llm.LlmModelConnectionTester;
import io.opaa.llm.LlmModelService;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.llm.RerankRoleStatusProvider;
import io.opaa.query.QueryProperties;
import io.opaa.query.RetrievalPipelineProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The reachability probes of the status page (#1053). Both bounds asserted here exist so that one
 * page load cannot cost one round trip per administrator and per StrictMode double mount, and so
 * that an unresponsive embedding endpoint cannot hold the request thread indefinitely - remove
 * either bound and the page is back to blocking on every view.
 */
class SearchStatusProbeCacheTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private final LlmModelService llmModelService = mock(LlmModelService.class);
  private final LlmModelConnectionTester connectionTester = mock(LlmModelConnectionTester.class);
  private final EmbeddingInfoService embeddingInfoService = mock(EmbeddingInfoService.class);
  private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
  private final RerankRoleStatusProvider rerankRoleStatusProvider =
      mock(RerankRoleStatusProvider.class);
  private final KnowledgeLibraryRepository libraryRepository =
      mock(KnowledgeLibraryRepository.class);
  private final LibraryDocumentStatsReader documentStatsReader =
      mock(LibraryDocumentStatsReader.class);
  private final FullTextBackfillProgressService backfillProgressService =
      mock(FullTextBackfillProgressService.class);
  private final AdvanceableClock clock =
      new AdvanceableClock(Instant.parse("2026-09-01T10:00:00Z"));

  private SearchStatusService service;

  @BeforeEach
  void setUp() {
    LlmModel activeModel = mock(LlmModel.class);
    when(activeModel.isActive()).thenReturn(true);
    when(activeModel.getId()).thenReturn(UUID.randomUUID());
    when(activeModel.getBaseUrl()).thenReturn("https://llm.example.invalid");
    when(activeModel.getModelIdentifier()).thenReturn("gpt-test");
    when(llmModelService.listModels()).thenReturn(List.of(activeModel));
    when(connectionTester.test(any(), any(), isNull(), any()))
        .thenReturn(new LlmModelConnectionTester.TestOutcome(true, "Verbindung erfolgreich."));
    when(embeddingInfoService.getEmbeddingInfo())
        .thenReturn(new EmbeddingInfo("openai", "text-embedding-3-small", 1536));
    when(embeddingModel.embed(anyString())).thenReturn(new float[] {0.1f});
    when(rerankRoleStatusProvider.currentStatus()).thenReturn(RerankRoleStatus.disabled());
    when(libraryRepository.findByOrganizationId(ORGANIZATION_ID)).thenReturn(List.of());
    when(documentStatsReader.statsForOrganization(ORGANIZATION_ID)).thenReturn(Map.of());
    when(backfillProgressService.progressForLibraries(any())).thenReturn(List.of());

    service =
        new SearchStatusService(
            llmModelService,
            connectionTester,
            embeddingInfoService,
            embeddingModel,
            rerankRoleStatusProvider,
            libraryRepository,
            documentStatsReader,
            backfillProgressService,
            new QueryProperties(8, 25, 1.0, 0.0, 1.0, true, 3, 2, true, 50),
            new RetrievalPipelineProperties(Set.of()),
            clock);
  }

  /** Two page loads inside the TTL cost exactly one chat and one embedding round trip. */
  @Test
  void sharesOneProbePairWithinTheCacheTtl() {
    assertThat(conditionOf(service.statusForOrganization(ORGANIZATION_ID), ModelRole.EMBEDDING))
        .isEqualTo(ModelRoleCondition.ACTIVE);
    clock.advanceSeconds(SearchStatusService.PROBE_CACHE_TTL.toSeconds() - 1);
    assertThat(conditionOf(service.statusForOrganization(ORGANIZATION_ID), ModelRole.EMBEDDING))
        .isEqualTo(ModelRoleCondition.ACTIVE);

    verify(connectionTester, times(1)).test(any(), any(), isNull(), any());
    verify(embeddingModel, times(1)).embed(anyString());
  }

  /** Once the pair is stale the next caller probes again - the page stays a live status. */
  @Test
  void probesAgainOnceTheCachedPairIsStale() {
    service.statusForOrganization(ORGANIZATION_ID);
    clock.advanceSeconds(SearchStatusService.PROBE_CACHE_TTL.toSeconds() + 1);
    service.statusForOrganization(ORGANIZATION_ID);

    verify(connectionTester, times(2)).test(any(), any(), isNull(), any());
    verify(embeddingModel, times(2)).embed(anyString());
  }

  /**
   * An embedding endpoint that never answers is reported as unreachable rather than held on to:
   * without the bound on the probe this call would not return at all.
   */
  @Test
  @Timeout(60)
  void reportsUnreachableWhenTheEmbeddingProbeOutlastsItsBound() {
    CountDownLatch neverReleased = new CountDownLatch(1);
    when(embeddingModel.embed(anyString()))
        .thenAnswer(
            invocation -> {
              neverReleased.await();
              return new float[] {0.1f};
            });

    SearchStatus status = service.statusForOrganization(ORGANIZATION_ID);

    assertThat(conditionOf(status, ModelRole.EMBEDDING)).isEqualTo(ModelRoleCondition.UNREACHABLE);
    assertThat(conditionOf(status, ModelRole.CHAT)).isEqualTo(ModelRoleCondition.ACTIVE);
  }

  private static ModelRoleCondition conditionOf(SearchStatus status, ModelRole role) {
    return status.modelRoles().stream()
        .filter(r -> r.role() == role)
        .findFirst()
        .orElseThrow()
        .condition();
  }

  /** A {@link Clock} whose instant the test moves by hand instead of waiting out the TTL. */
  private static final class AdvanceableClock extends Clock {

    private Instant instant;

    private AdvanceableClock(Instant instant) {
      this.instant = instant;
    }

    private void advanceSeconds(long seconds) {
      instant = instant.plusSeconds(seconds);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
