package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService;
import io.opaa.diagnosticaccess.ForeignDiagnosticContextService;
import io.opaa.group.GroupService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.llm.RerankModelRole;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.query.QueryProperties;
import io.opaa.query.RetrievalPipeline;
import io.opaa.query.RetrievalPipelineTestSupport;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * {@link SearchDiagnosisService#diagnose} takes its timestamp from the injected {@link Clock}
 * rather than {@code Clock.systemUTC()} - a constructor seam only, matching {@code
 * FullTextBackfillGate} and {@code SearchStatusService} (#1120). Before this seam existed, the
 * timestamp could not be pinned in a test at all.
 */
class SearchDiagnosisClockTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final Instant FIXED_INSTANT = Instant.parse("2026-09-01T10:15:30Z");

  @Test
  void diagnoseTakesItsTimestampFromTheInjectedClock() {
    VectorStore vectorStore = mock(VectorStore.class);
    RerankModelRole rerankModelRole = mock(RerankModelRole.class);
    LibraryAccessService libraryAccessService = mock(LibraryAccessService.class);
    CurrentUser caller =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");
    when(libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId()))
        .thenReturn(Set.of(LIBRARY_ID));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    when(rerankModelRole.currentStatus()).thenReturn(RerankRoleStatus.disabled());
    RetrievalPipeline pipeline =
        RetrievalPipelineTestSupport.vectorSearchPipeline(vectorStore, rerankModelRole);
    QueryProperties properties = new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 1, false, 20);
    Clock fixedClock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);

    SearchDiagnosisService service =
        new SearchDiagnosisService(
            pipeline,
            properties,
            libraryAccessService,
            mock(KnowledgeLibraryRepository.class),
            mock(GroupService.class),
            mock(DocumentRepository.class),
            rerankModelRole,
            mock(ForeignDiagnosticContextService.class),
            mock(DiagnosticImpersonationGrantService.class),
            fixedClock);

    SearchDiagnosis diagnosis =
        service.diagnose(
            caller, new DiagnosisQuery("Frage", DiagnosisContextType.SELF, null, null, null, null));

    assertThat(diagnosis.executedAt()).isEqualTo(FIXED_INSTANT);
  }
}
