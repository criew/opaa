package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.common.Feature;
import io.opaa.common.FeatureFlags;
import io.opaa.common.TestFeatureFlags;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantService;
import io.opaa.diagnosticaccess.ForeignDiagnosticContextService;
import io.opaa.group.GroupService;
import io.opaa.knowledge.DocumentRepository;
import io.opaa.knowledge.KnowledgeLibraryRepository;
import io.opaa.knowledge.LibraryAccessService;
import io.opaa.llm.RerankModelRole;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.metadata.MetadataFilter;
import io.opaa.retrieval.QueryProperties;
import io.opaa.retrieval.RetrievalContextFactory;
import io.opaa.retrieval.RetrievalPipeline;
import io.opaa.retrieval.RetrievalPipelineTestSupport;
import io.opaa.retrieval.search.VectorChunkSearch;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.vectorstore.SearchRequest;

/**
 * The search diagnosis runs a question "exactly as a chat query" - with the metadata feature off
 * (ADR-0044) that means without the metadata filter the form sent.
 */
class SearchDiagnosisMetadataFeatureTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final MetadataFilter VERMERK = MetadataFilter.ofDocumentTypes(List.of("VERMERK"));

  @Test
  void switchedOffTheDiagnosisRunsWithoutTheMetadataFilter() {
    assertThat(filterOfTheRetrieval(TestFeatureFlags.without(Feature.METADATA)))
        .isEqualTo(MetadataFilter.NONE);
  }

  @Test
  void switchedOnTheDiagnosisRunsWithTheMetadataFilter() {
    assertThat(filterOfTheRetrieval(TestFeatureFlags.allOn())).isEqualTo(VERMERK);
  }

  private MetadataFilter filterOfTheRetrieval(FeatureFlags flags) {
    VectorChunkSearch vectorChunkSearch = mock(VectorChunkSearch.class);
    RerankModelRole rerankModelRole = mock(RerankModelRole.class);
    LibraryAccessService libraryAccessService = mock(LibraryAccessService.class);
    CurrentUser caller =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");
    when(libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId()))
        .thenReturn(Set.of(LIBRARY_ID));
    when(vectorChunkSearch.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    when(rerankModelRole.currentStatus()).thenReturn(RerankRoleStatus.disabled());
    RetrievalPipeline pipeline =
        RetrievalPipelineTestSupport.vectorSearchPipeline(vectorChunkSearch, rerankModelRole);
    QueryProperties properties =
        new QueryProperties(8, 25, 1.0, 0.3, false, 3, 1, false, 20, 20, 2);
    RetrievalContextFactory contextFactory =
        spy(new RetrievalContextFactory(properties, rerankModelRole));
    SearchDiagnosisService service =
        new SearchDiagnosisService(
            pipeline,
            contextFactory,
            libraryAccessService,
            mock(KnowledgeLibraryRepository.class),
            mock(GroupService.class),
            mock(DocumentRepository.class),
            mock(ForeignDiagnosticContextService.class),
            mock(DiagnosticImpersonationGrantService.class),
            mock(io.opaa.diagnosticaccess.LibraryDiagnosticsLockService.class),
            mock(io.opaa.space.SpaceService.class),
            mock(io.opaa.audit.AuditEventRecorder.class),
            new io.opaa.permission.GroupSizeProperties(null),
            mock(io.opaa.asset.OwnerOnlyRule.class),
            Clock.systemUTC(),
            flags);

    service.diagnose(
        caller,
        new DiagnosisQuery(
            "Frage", DiagnosisContextType.SELF, null, null, null, null, null, VERMERK));

    ArgumentCaptor<MetadataFilter> filter = ArgumentCaptor.forClass(MetadataFilter.class);
    verify(contextFactory).contextFor(any(), any(), any(), filter.capture());
    return filter.getValue();
  }
}
