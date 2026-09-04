package io.opaa.query;

import io.opaa.chat.ChatService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.PermissionHistoryService;
import io.opaa.llm.RerankModelRole;
import io.opaa.observability.QueryMetrics;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.ApplicationContext;

/**
 * Every {@link QueryService} constructor dependency except {@link QueryProperties} itself (issue
 * #1041, docs/features/retrieval-benchmark.md §2). {@code QueryService} takes its {@code
 * QueryProperties} through the constructor rather than as a per-call argument, so measuring several
 * parameter sets in one harness run means building several {@code QueryService} instances that
 * share every other production collaborator — {@link #fromContext} pulls each of those
 * collaborators from the harness's Spring context exactly once, and {@link #buildQueryService}
 * assembles a fresh {@code QueryService} around whichever variant's {@code QueryProperties} is
 * being measured. Deliberately <b>not</b> a second Spring context per variant: that would cost
 * minutes (Testcontainers, migrations, bean graph) per variant instead of milliseconds.
 *
 * <p>Lives in {@code io.opaa.query} (not {@code io.opaa.eval}, where the rest of the variant
 * mechanism lives) so it can name {@link QueryService}'s collaborators directly, including
 * package-private ones. {@link #fromContext} is the seam: it is the only place a caller from {@code
 * io.opaa.eval} needs to cross that boundary, via {@link ApplicationContext} bean lookups rather
 * than a direct type reference.
 *
 * <p>All variants share one {@link RetrievalPipeline} instance: a variant differs only in {@link
 * QueryProperties}, which the pipeline reads per run from its {@link RetrievalContext} instead of
 * holding it in its stages.
 */
public record QueryServiceDependencies(
    RetrievalPipeline retrievalPipeline,
    AnswerGenerationService answerGenerationService,
    ChatMemory chatMemory,
    CitationParser citationParser,
    CitationValidator citationValidator,
    DocumentRepository documentRepository,
    LibraryAccessService libraryAccessService,
    PermissionHistoryService permissionHistoryService,
    ChatService chatService,
    QueryMetrics metrics,
    KnowledgeLibraryRepository knowledgeLibraryRepository,
    RerankModelRole rerankModelRole,
    DocumentMetadataService documentMetadataService,
    DocumentTypeVocabularyRepository vocabularyRepository) {

  public static QueryServiceDependencies fromContext(ApplicationContext context) {
    return new QueryServiceDependencies(
        context.getBean(RetrievalPipeline.class),
        context.getBean(AnswerGenerationService.class),
        context.getBean(ChatMemory.class),
        context.getBean(CitationParser.class),
        context.getBean(CitationValidator.class),
        context.getBean(DocumentRepository.class),
        context.getBean(LibraryAccessService.class),
        context.getBean(PermissionHistoryService.class),
        context.getBean(ChatService.class),
        context.getBean(QueryMetrics.class),
        context.getBean(KnowledgeLibraryRepository.class),
        context.getBean(RerankModelRole.class),
        context.getBean(DocumentMetadataService.class),
        context.getBean(DocumentTypeVocabularyRepository.class));
  }

  public QueryService buildQueryService(QueryProperties queryProperties) {
    return new QueryService(
        retrievalPipeline,
        answerGenerationService,
        chatMemory,
        citationParser,
        citationValidator,
        documentRepository,
        libraryAccessService,
        permissionHistoryService,
        chatService,
        metrics,
        queryProperties,
        knowledgeLibraryRepository,
        rerankModelRole,
        documentMetadataService,
        vocabularyRepository);
  }
}
