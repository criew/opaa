package io.opaa.searchadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.llm.RerankClient.ScoredCandidate;
import io.opaa.llm.RerankModelRole;
import io.opaa.llm.RerankRoleState;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.query.QueryProperties;
import io.opaa.query.RetrievalPipeline;
import io.opaa.query.RetrievalPipelineTestSupport;
import io.opaa.query.RetrievalStageName;
import io.opaa.query.StageExplanation;
import io.opaa.query.StageStatus;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

/**
 * The administration's diagnosis must run the retrieval a chat query runs, reranking included
 * (docs/features/hybrid-retrieval.md, "Das Diagnosewerkzeug"): the tool exists to answer "why these
 * findings?", and a run that skipped a stage the real search performs would answer it about
 * findings nobody ever got.
 *
 * <p>Runs the real pipeline ({@link RetrievalPipelineTestSupport}) with only the search source and
 * the rerank endpoint stubbed - a test against a mocked pipeline could not tell whether this
 * service built the context correctly.
 */
class SearchDiagnosisRerankParityTest {

  private static final UUID LIBRARY_ID = UUID.randomUUID();
  private static final int TOP_K = 8;
  private static final int CANDIDATE_WINDOW = 20;

  private static final QueryProperties PROPERTIES =
      new QueryProperties(TOP_K, 25, 1.0, 0.3, 1.0, false, 3, 1, false, CANDIDATE_WINDOW);

  private final VectorStore vectorStore = mock(VectorStore.class);
  private final RerankModelRole rerankModelRole = mock(RerankModelRole.class);
  private final LibraryAccessService libraryAccessService = mock(LibraryAccessService.class);

  private SearchDiagnosis diagnose(RerankRoleStatus roleStatus) {
    CurrentUser caller =
        CurrentUser.of(UUID.randomUUID(), UUID.randomUUID(), SystemRole.SYSTEM_ADMIN, "Admin");
    when(libraryAccessService.readableLibraryIds(caller.id(), caller.organizationId()))
        .thenReturn(Set.of(LIBRARY_ID));
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(
            IntStream.range(0, 25).mapToObj(SearchDiagnosisRerankParityTest::chunk).toList());
    when(rerankModelRole.currentStatus()).thenReturn(roleStatus);
    when(rerankModelRole.rerank(anyString(), any()))
        .thenReturn(
            IntStream.range(0, CANDIDATE_WINDOW)
                .mapToObj(i -> new ScoredCandidate(i, i))
                .toList()
                .reversed());

    SearchDiagnosisService service =
        new SearchDiagnosisService(
            pipeline(),
            PROPERTIES,
            libraryAccessService,
            mock(KnowledgeLibraryRepository.class),
            mock(io.opaa.group.GroupService.class),
            mock(DocumentRepository.class),
            rerankModelRole);

    return service.diagnose(
        caller, new DiagnosisQuery("Frage", DiagnosisContextType.SELF, null, null));
  }

  private RetrievalPipeline pipeline() {
    return RetrievalPipelineTestSupport.vectorSearchPipeline(vectorStore, rerankModelRole);
  }

  private static Document chunk(int i) {
    return Document.builder()
        .id("c" + i)
        .text("Text c" + i)
        .metadata(Map.of("document_id", UUID.randomUUID().toString(), "file_name", "c" + i + ".md"))
        .score(1.0 - i / 100.0)
        .build();
  }

  private static StageExplanation rerankStage(SearchDiagnosis diagnosis) {
    return diagnosis.explanation().stages().stream()
        .filter(stage -> stage.stage() == RetrievalStageName.RERANK)
        .findFirst()
        .orElseThrow();
  }

  /**
   * The finding this test exists for: with a usable role the diagnosis reranks, exactly as a chat
   * query does. Before the fix it constructed its context without the role's state and reported
   * {@code DISABLED} while the real search reranked.
   */
  @Test
  void aUsableRerankRoleIsUsedByTheDiagnosisJustAsAChatQueryUsesIt() {
    SearchDiagnosis diagnosis =
        diagnose(new RerankRoleStatus(RerankRoleState.READY, "http://reranker/v1", "bge", null));

    assertThat(rerankStage(diagnosis).status()).isEqualTo(StageStatus.EXECUTED);
    assertThat(rerankStage(diagnosis).incomingCount()).isEqualTo(CANDIDATE_WINDOW);
    assertThat(diagnosis.selection()).hasSize(TOP_K);
    // The reranker's order, not the fused one: it scored the window in reverse.
    assertThat(diagnosis.selection().getFirst().chunkId()).isEqualTo("c19");
  }

  /** A switched-off role is reported as switched off, and the run keeps the narrow budget. */
  @Test
  void aSwitchedOffRerankRoleLeavesTheDiagnosisWithTheUnrerankedSelection() {
    SearchDiagnosis diagnosis = diagnose(RerankRoleStatus.disabled());

    assertThat(rerankStage(diagnosis).status()).isEqualTo(StageStatus.DISABLED);
    assertThat(diagnosis.selection()).hasSize(TOP_K);
    assertThat(diagnosis.selection().getFirst().chunkId()).isEqualTo("c0");
  }

  /** Switched on but broken is a Störung in the protocol, never an "aus". */
  @Test
  void anUnreachableRerankRoleIsReportedAsUnavailable() {
    SearchDiagnosis diagnosis =
        diagnose(
            new RerankRoleStatus(
                RerankRoleState.UNREACHABLE, "http://reranker/v1", "bge", "connection refused"));

    assertThat(rerankStage(diagnosis).status()).isEqualTo(StageStatus.UNAVAILABLE);
    assertThat(diagnosis.selection()).hasSize(TOP_K);
  }
}
