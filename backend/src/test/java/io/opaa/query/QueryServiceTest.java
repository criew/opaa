package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.Chat;
import io.opaa.chat.ChatService;
import io.opaa.chat.ChatSource;
import io.opaa.chat.ChatSourceLocation;
import io.opaa.chat.ChatSourceMetadataEntry;
import io.opaa.common.ConflictException;
import io.opaa.indexing.ChunkingService;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.library.PermissionHistoryService;
import io.opaa.llm.RerankModelRole;
import io.opaa.llm.RerankRoleStatus;
import io.opaa.observability.QueryMetrics;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

  @Mock private VectorStore vectorStore;
  @Mock private AnswerGenerationService answerGenerationService;
  @Mock private ChatMemory chatMemory;
  @Mock private DocumentRepository documentRepository;
  @Mock private LibraryAccessService libraryAccessService;
  @Mock private PermissionHistoryService permissionHistoryService;
  @Mock private ChatService chatService;
  @Mock private KnowledgeLibraryRepository knowledgeLibraryRepository;
  @Mock private DocumentMetadataService documentMetadataService;
  @Mock private ChunkEmbeddingLookup chunkEmbeddingLookup;
  // Unstubbed by default: Mockito returns an empty List for #decompose, which QueryService treats
  // as a decomposition failure and falls back to the pre-#923 single-query path (see
  // SubQueryDecompositionStage) - every test in this class relies on that unless it
  // explicitly stubs this mock (see the "Query decomposition (#923)" nested class below).
  @Mock private QueryDecompositionService queryDecompositionService;
  private QueryService queryService;

  private final UUID currentUserId = UUID.randomUUID();
  private final UUID organizationId = UUID.randomUUID();
  private final UUID readableLibraryId = UUID.randomUUID();
  private final CurrentUser caller =
      CurrentUser.of(currentUserId, organizationId, SystemRole.USER, "User");

  /**
   * Assembles the production pipeline around this class's mocked collaborators - through {@link
   * QueryConfiguration#retrievalPipeline} itself, not a second stage list of its own, so these
   * tests can never run a different stage order than the application does.
   */
  private QueryService newQueryService(QueryProperties queryProperties, ChatMemory memory) {
    RetrievalPipeline pipeline =
        new QueryConfiguration()
            .retrievalPipeline(
                new SearchScopeStage(),
                new MetadataFilterStage(mock(DocumentTypeVocabularyRepository.class)),
                new SubQueryDecompositionStage(queryDecompositionService),
                new VectorSearchStage(vectorStore),
                // The lexical path is switched off in every QueryProperties this class builds
                // (fullTextSearchEnabled = false): this class is about what QueryService does with
                // the selection, not about how the selection is retrieved (see
                // FullTextSearchStageTest).
                new FullTextSearchStage(
                    mock(FullTextChunkSearch.class), mock(FullTextBackfillGate.class)),
                new MmrSelectionStage(chunkEmbeddingLookup),
                new RankFusionStage(),
                new RerankStage(disabledRerankRole()),
                new DocumentCompletionStage(),
                RetrievalPipelineProperties.allStagesEnabled());
    return new QueryService(
        pipeline,
        answerGenerationService,
        memory,
        new CitationParser(),
        new CitationValidator(),
        documentRepository,
        libraryAccessService,
        permissionHistoryService,
        chatService,
        new QueryMetrics(new SimpleMeterRegistry()),
        queryProperties,
        knowledgeLibraryRepository,
        disabledRerankRole(),
        documentMetadataService,
        mock(DocumentTypeVocabularyRepository.class));
  }

  /**
   * A rerank role whose switch is off - the state {@link QueryService} reads once per run. A bare
   * {@code mock(RerankModelRole.class)} returns {@code null} there, which no production caller
   * sees.
   */
  private static RerankModelRole disabledRerankRole() {
    RerankModelRole role = mock(RerankModelRole.class);
    lenient().when(role.currentStatus()).thenReturn(RerankRoleStatus.disabled());
    return role;
  }

  @BeforeEach
  void setUp() {
    // mmrLambda=1.0 (pure top-K by relevance): the tests in this class stub small,
    // already-descending-score candidate lists and assert on their exact order/content -
    // MmrSelector's own diversity behaviour (mmrLambda != 1.0) is covered separately by
    // MmrSelectorTest.
    queryService =
        newQueryService(
            new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, false, 50), chatMemory);

    // lenient: not every test in this class exercises the full query() path (e.g. the
    // mergeSourceReferences nested tests call other members directly), so MockitoExtension's
    // strict stubbing would otherwise flag these as unused.
    lenient()
        .when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId));
    // #238's regression check - matches the applied scope by default so it never flags a
    // mismatch in tests that do not care about it.
    lenient()
        .when(permissionHistoryService.readableLibraryIdsAsOf(eq(currentUserId), any(), any()))
        .thenReturn(Set.of(readableLibraryId));
    // #525 default: no chatId given (or it does not resolve to a chat the caller authored) runs
    // the query ephemerally, exactly as before persisted chats existed.
    lenient().when(chatService.findOwnedChat(any(), any())).thenReturn(Optional.empty());
  }

  /**
   * At {@code mmrLambda = 1.0} the diversity term is always multiplied by zero (see {@link
   * MmrSelector#select}'s Javadoc), so {@code query()} must skip the {@link ChunkEmbeddingLookup}
   * round trip entirely rather than pay for a database call whose result could not affect the final
   * selection - every test in this class relies on that (see {@link #setUp}'s comment), but none of
   * them actually verifies it until now.
   */
  @Test
  void queryNeverCallsChunkEmbeddingLookupWhenMmrLambdaIsOne() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Question", null, caller, true, List.of());

    verifyNoInteractions(chunkEmbeddingLookup);
  }

  /** Below {@code mmrLambda = 1.0}, the diversity term is live and the lookup actually runs. */
  @Test
  void queryCallsChunkEmbeddingLookupWhenMmrLambdaIsBelowOne() {
    QueryService serviceWithMmrEnabled =
        newQueryService(
            new QueryProperties(8, 25, 0.5, 0.3, 1.0, true, 3, 2, false, 50), chatMemory);
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));
    when(chunkEmbeddingLookup.findByIds(any())).thenReturn(Map.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    serviceWithMmrEnabled.query("Question", null, caller, true, List.of());

    verify(chunkEmbeddingLookup).findByIds(List.of(chunk.getId()));
  }

  /**
   * #889 (O1): {@code permissionHistorySampleRate = 0.0} must skip the check entirely - the whole
   * point of sampling is to spare the reconstruction cost for queries it does not run for.
   */
  @Test
  void permissionHistoryCheckNeverRunsWhenSampleRateIsZero() {
    QueryService serviceWithNoSampling =
        newQueryService(
            new QueryProperties(8, 25, 1.0, 0.3, 0.0, true, 3, 2, false, 50), chatMemory);
    when(chatMemory.get(any())).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    serviceWithNoSampling.query("Question", null, caller, true, List.of());

    verifyNoInteractions(permissionHistoryService);
  }

  /**
   * #889 (O1): {@code permissionHistorySampleRate = 1.0} is the pre-#889 "every query" behaviour -
   * a mismatch must still be logged exactly as before sampling existed. No test previously
   * exercised this log line at all (only the lenient default stub in {@link #setUp} existed); this
   * closes that gap while proving sampling's "1.0 = always" boundary.
   */
  @Test
  void permissionHistoryCheckLogsMismatchWhenSampleRateIsOne() {
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(QueryService.class);
    var logAppender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logAppender.start();
    logger.addAppender(logAppender);
    try {
      when(permissionHistoryService.readableLibraryIdsAsOf(eq(currentUserId), any(), any()))
          .thenReturn(Set.of());
      when(chatMemory.get(any())).thenReturn(List.of());
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      queryService.query("Question", null, caller, true, List.of());

      var mismatchEvents =
          logAppender.list.stream()
              .filter(
                  event ->
                      event.getFormattedMessage().contains("Permission history regression check"))
              .toList();
      assertThat(mismatchEvents).isNotEmpty();
      assertThat(mismatchEvents)
          .allSatisfy(
              event -> assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
    } finally {
      logger.detachAppender(logAppender);
    }
  }

  /**
   * #639: {@code sourceEntryUrl} is resolved via the same {@code document_id} -> DocumentRepository
   * lookup {@code indexedAt} already uses ({@link QueryService#lookupSourceDocuments}), not carried
   * on the chunk metadata itself.
   */
  @Test
  void queryPopulatesSourceEntryUrlFromDocumentLookup() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    var chunk =
        Document.builder()
            .text("Feed entry content")
            .metadata(Map.of("file_name", "entry.html", "document_id", documentId.toString()))
            .score(0.8)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var indexedDocument = new io.opaa.indexing.Document("entry.html", "/path", "text/html", 100L);
    indexedDocument.setSourceEntryUrl("https://example.com/feed/entry-123");
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(indexedDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources().getFirst().getSourceEntryUrl())
        .isEqualTo("https://example.com/feed/entry-123");
  }

  /**
   * #667: every retrieved chunk's location rides along on its ChatSource, keyed by the chunk index
   * the citation marker names - including a chunk the pipeline stored no location for, which still
   * needs its entry (location null) so the frontend knows the number is real.
   */
  @Test
  void queryCarriesTheLocationOfEveryRetrievedChunk() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    var located =
        Document.builder()
            .text("Frist")
            .metadata(
                Map.of(
                    "file_name",
                    "anweisung.md",
                    "document_id",
                    documentId.toString(),
                    "chunk_index",
                    3,
                    ChunkingService.LOCATION_METADATA_KEY,
                    "Abschn. 4.2 Fristsetzung"))
            .score(0.9)
            .build();
    var unlocated =
        Document.builder()
            .text("Einleitung")
            .metadata(
                Map.of(
                    "file_name", "anweisung.md",
                    "document_id", documentId.toString(),
                    "chunk_index", "0"))
            .score(0.5)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(located, unlocated));
    when(documentRepository.findById(documentId)).thenReturn(Optional.empty());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    List<ChatSourceLocation> locations = response.getSources().getFirst().getChunkLocations();
    assertThat(locations).extracting(ChatSourceLocation::getChunkIndex).containsExactly(0, 3);
    assertThat(locations)
        .extracting(ChatSourceLocation::getLocation)
        .containsExactly(null, "Abschn. 4.2 Fristsetzung");
  }

  /**
   * #1066 (ADR-0024): a document's core fields ride along on its ChatSource, read from the document
   * (via {@link DocumentMetadataService}) rather than from the chunk, so every chunk of the same
   * document reports the same values; a document without any stays without a snapshot.
   */
  @Test
  void queryCarriesTheCoreMetadataOfARetrievedDocument() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    UUID plainDocumentId = UUID.randomUUID();
    var chunk =
        Document.builder()
            .text("Die Nutzung privater Geraete ist untersagt.")
            .metadata(
                Map.of(
                    "file_name",
                    "dienstanweisung.pdf",
                    "document_id",
                    documentId.toString(),
                    "chunk_index",
                    0))
            .score(0.9)
            .build();
    var plainChunk =
        Document.builder()
            .text("Ein normales Dokument.")
            .metadata(
                Map.of(
                    "file_name",
                    "anweisung.md",
                    "document_id",
                    plainDocumentId.toString(),
                    "chunk_index",
                    0))
            .score(0.5)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk, plainChunk));
    io.opaa.indexing.Document document =
        new io.opaa.indexing.Document("dienstanweisung.pdf", "/d.pdf", "application/pdf", 1L);
    io.opaa.indexing.Document plainDocument =
        new io.opaa.indexing.Document("anweisung.md", "/a.md", "text/markdown", 1L);
    when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
    when(documentRepository.findById(plainDocumentId)).thenReturn(Optional.of(plainDocument));
    when(documentMetadataService.coreMetadataFor(Set.of(document.getId(), plainDocument.getId())))
        .thenReturn(
            Map.of(
                document.getId(),
                new CoreMetadata(
                    "Dienstanweisung IT-Nutzung",
                    MetadataOrigin.DETERMINISTIC,
                    "DIENSTANWEISUNG",
                    "Dienstanweisung",
                    MetadataOrigin.DETERMINISTIC,
                    LocalDate.of(2026, 3, 12),
                    DatePrecision.DAY,
                    MetadataOrigin.DETERMINISTIC)));
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    ChatSource withCore =
        response.getSources().stream()
            .filter(source -> source.getFileName().equals("dienstanweisung.pdf"))
            .findFirst()
            .orElseThrow();
    assertThat(withCore.getMetadata())
        .extracting(
            ChatSourceMetadataEntry::fieldKey,
            ChatSourceMetadataEntry::label,
            ChatSourceMetadataEntry::value,
            ChatSourceMetadataEntry::displayValue,
            ChatSourceMetadataEntry::origin,
            ChatSourceMetadataEntry::datePrecision)
        .containsExactly(
            tuple(
                "title",
                "Titel",
                "Dienstanweisung IT-Nutzung",
                "Dienstanweisung IT-Nutzung",
                MetadataOrigin.DETERMINISTIC,
                null),
            tuple(
                "document_type",
                "Dokumentart",
                "DIENSTANWEISUNG",
                "Dienstanweisung",
                MetadataOrigin.DETERMINISTIC,
                null),
            tuple(
                "document_date",
                "Datum/Stand",
                "2026-03-12",
                "12.03.2026",
                MetadataOrigin.DETERMINISTIC,
                DatePrecision.DAY));
    ChatSource plainSource =
        response.getSources().stream()
            .filter(source -> source.getFileName().equals("anweisung.md"))
            .findFirst()
            .orElseThrow();
    assertThat(plainSource.getMetadata()).isNull();
  }

  /**
   * #1164: a mail chunk's mail_* metadata (ChunkMailMetadata) rides along on its ChatSource, so the
   * Fundstellen-Anzeige can show Absender/Betreff/Datum without the caller re-fetching the
   * document. A non-mail source's fields stay null (see the mixed-scope assertions below) - the
   * regression guard this test doubles as: before #1164, QueryService#mapSources never read these
   * keys at all, so every source's mail fields were null regardless of the chunk's metadata.
   */
  @Test
  void queryCarriesTheMailKopfdatenOfARetrievedMailChunk() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID mailDocumentId = UUID.randomUUID();
    UUID plainDocumentId = UUID.randomUUID();
    var mailChunk =
        Document.builder()
            .text("Bitte pruefen Sie den Antrag.")
            .metadata(
                Map.of(
                    "file_name",
                    "anfrage.eml",
                    "document_id",
                    mailDocumentId.toString(),
                    "chunk_index",
                    0,
                    "mail_from",
                    "mueller@stadt.de",
                    "mail_to",
                    "poststelle@stadt.de",
                    "mail_subject",
                    "Bebauungsplan Nord",
                    "mail_date",
                    "2026-03-14T09:15:00Z"))
            .score(0.9)
            .build();
    var plainChunk =
        Document.builder()
            .text("Ein normales Dokument.")
            .metadata(
                Map.of(
                    "file_name",
                    "anweisung.md",
                    "document_id",
                    plainDocumentId.toString(),
                    "chunk_index",
                    0))
            .score(0.5)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(mailChunk, plainChunk));
    when(documentRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    ChatSource mailSource =
        response.getSources().stream()
            .filter(source -> source.getFileName().equals("anfrage.eml"))
            .findFirst()
            .orElseThrow();
    assertThat(mailSource.getMailFrom()).isEqualTo("mueller@stadt.de");
    assertThat(mailSource.getMailTo()).isEqualTo("poststelle@stadt.de");
    assertThat(mailSource.getMailSubject()).isEqualTo("Bebauungsplan Nord");
    assertThat(mailSource.getMailDate()).isEqualTo("2026-03-14T09:15:00Z");
    ChatSource plainSource =
        response.getSources().stream()
            .filter(source -> source.getFileName().equals("anweisung.md"))
            .findFirst()
            .orElseThrow();
    assertThat(plainSource.getMailFrom()).isNull();
    assertThat(plainSource.getMailTo()).isNull();
    assertThat(plainSource.getMailSubject()).isNull();
    assertThat(plainSource.getMailDate()).isNull();
  }

  /** #667: the "Durchsucht wurden" line names the effective scope, by library name, sorted. */
  @Test
  void queryNamesTheLibrariesActuallySearched() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Nichts"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);
    var library = mock(KnowledgeLibrary.class);
    when(library.getId()).thenReturn(readableLibraryId);
    when(library.getName()).thenReturn("Dienstanweisungen");
    when(knowledgeLibraryRepository.findAllById(Set.of(readableLibraryId)))
        .thenReturn(List.of(library));

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getMetadata().getSearchedLibraries())
        .extracting(SearchedLibraryRef::getId, SearchedLibraryRef::getName)
        .containsExactly(tuple(readableLibraryId, "Dienstanweisungen"));
  }

  /** #667: when no search ran, nothing was searched - the list is empty, not a guess. */
  @Test
  void queryListsNoSearchedLibrariesWhenNoSearchRan() {
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, false, List.of());

    assertThat(response.getMetadata().getSearchedLibraries()).isEmpty();
    verify(knowledgeLibraryRepository, never()).findAllById(any());
  }

  /**
   * #639 acceptance criterion: a source referencing any other document (no source entry URL, e.g.
   * not RSS-sourced) still carries {@code sourceEntryUrl: null}.
   */
  @Test
  void queryLeavesSourceEntryUrlNullWhenDocumentHasNone() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    var chunk =
        Document.builder()
            .text("Uploaded content")
            .metadata(Map.of("file_name", "upload.pdf", "document_id", documentId.toString()))
            .score(0.8)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var indexedDocument = new io.opaa.indexing.Document("entry.html", "/path", "text/html", 100L);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(indexedDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources().getFirst().getSourceEntryUrl()).isNull();
  }

  /**
   * #739: a citation of a local original (FILESYSTEM/UPLOAD) carries {@code documentId} and {@code
   * sourceType} so the client can open GET /api/v1/documents/{documentId}/content, but {@code
   * sourceUrl} stays null - there is no remote deep link, the download endpoint is the only way in.
   */
  @Test
  void queryPopulatesDocumentIdAndSourceTypeForALocalOriginal() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    var chunk =
        Document.builder()
            .text("Uploaded content")
            .metadata(Map.of("file_name", "upload.pdf", "document_id", documentId.toString()))
            .score(0.8)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var indexedDocument =
        new io.opaa.indexing.Document(
            "upload.pdf",
            "/data/upload.pdf",
            "application/pdf",
            100L,
            io.opaa.api.types.DocumentSourceType.UPLOAD);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(indexedDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    ChatSource source = response.getSources().getFirst();
    assertThat(source.getDocumentId()).isEqualTo(documentId);
    assertThat(source.getSourceType()).isEqualTo(io.opaa.api.types.DocumentSourceType.UPLOAD);
    assertThat(source.getSourceUrl()).isNull();
  }

  /**
   * #739: a citation of a remote-only document (HTTP_DIRECTORY/RSS_FEED, no local file behind the
   * download endpoint) carries {@code sourceUrl} - the document's own remote location, mirroring
   * {@code LibraryDocumentResponse.sourceUrl} (#738).
   */
  @Test
  void queryPopulatesSourceUrlForARemoteOnlyDocument() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID documentId = UUID.randomUUID();
    var chunk =
        Document.builder()
            .text("Crawled content")
            .metadata(
                Map.of("file_name", "dienstanweisung.pdf", "document_id", documentId.toString()))
            .score(0.8)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var indexedDocument =
        new io.opaa.indexing.Document(
            "dienstanweisung.pdf",
            "https://example.gov/verzeichnis/dienstanweisung.pdf",
            "application/pdf",
            100L,
            io.opaa.api.types.DocumentSourceType.HTTP_DIRECTORY);
    when(documentRepository.findById(documentId)).thenReturn(Optional.of(indexedDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    ChatSource source = response.getSources().getFirst();
    assertThat(source.getDocumentId()).isEqualTo(documentId);
    assertThat(source.getSourceType())
        .isEqualTo(io.opaa.api.types.DocumentSourceType.HTTP_DIRECTORY);
    assertThat(source.getSourceUrl())
        .isEqualTo("https://example.gov/verzeichnis/dienstanweisung.pdf");
  }

  /**
   * #739: a synthetic entry (#386, invalid citation matching no retrieved chunk) has no underlying
   * document to resolve - {@code documentId}, {@code sourceType} and {@code sourceUrl} must all
   * stay null, there is nothing real to link to.
   */
  @Test
  void querySynthesizesNoDocumentLinkForAFabricatedCitation() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var answer = "Info 【source: nonexistent-doc#0 | fabricated.pdf】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    ChatSource source = response.getSources().getFirst();
    assertThat(source.getFileName()).isEqualTo("fabricated.pdf");
    assertThat(source.getDocumentId()).isNull();
    assertThat(source.getSourceType()).isNull();
    assertThat(source.getSourceUrl()).isNull();
  }

  /**
   * #78: a chunk carrying a malformed (non-UUID) {@code document_id} in its metadata points at a
   * data problem - corrupt indexing, a botched migration or a version mismatch between indexer and
   * query service - not a transient failure, so both {@link QueryService#lookupSourceDocuments} and
   * {@link QueryService#parseDocumentId} must log it at WARN, where it survives a production log
   * level, rather than at DEBUG where it is silently dropped.
   */
  @Test
  void queryLogsInvalidDocumentIdAtWarnLevel() {
    var logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(QueryService.class);
    var logAppender =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logAppender.start();
    logger.addAppender(logAppender);
    try {
      when(chatMemory.get(any())).thenReturn(List.of());
      var chunk =
          Document.builder()
              .text("Corrupted metadata content")
              .metadata(Map.of("file_name", "broken.pdf", "document_id", "not-a-uuid"))
              .score(0.8)
              .build();
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      queryService.query("Question", null, caller, true, List.of());

      var invalidDocumentIdEvents =
          logAppender.list.stream()
              .filter(event -> event.getFormattedMessage().contains("not-a-uuid"))
              .toList();
      assertThat(invalidDocumentIdEvents).isNotEmpty();
      assertThat(invalidDocumentIdEvents)
          .allSatisfy(
              event -> assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.WARN));
    } finally {
      logger.detachAppender(logAppender);
    }
  }

  /**
   * #739: {@code mapSources}/{@code mergeSourceReferences} now dedupe by {@code document_id}, not
   * {@code fileName} (previously the opposite, see the #666 review this test used to document below
   * - superseded here) - two distinct documents that happen to share a file name (e.g. two RSS
   * entries both attaching a same-named PDF) no longer collapse into one {@link ChatSource} row
   * with the origin link dropped; each keeps its own row with its own {@code documentId} and {@code
   * sourceEntryUrl}, since #739 needs every entry's own document id for its deep link.
   */
  @Test
  void queryKeepsTwoDocumentsThatShareAFileNameAsSeparateSources() {
    when(chatMemory.get(any())).thenReturn(List.of());
    UUID firstDocumentId = UUID.randomUUID();
    UUID secondDocumentId = UUID.randomUUID();
    var firstChunk =
        Document.builder()
            .text("From the first feed entry")
            .metadata(
                Map.of("file_name", "attachment.pdf", "document_id", firstDocumentId.toString()))
            .score(0.9)
            .build();
    var secondChunk =
        Document.builder()
            .text("From the second feed entry")
            .metadata(
                Map.of("file_name", "attachment.pdf", "document_id", secondDocumentId.toString()))
            .score(0.7)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(firstChunk, secondChunk));

    var firstDocument =
        new io.opaa.indexing.Document("attachment.pdf", "/path1", "application/pdf", 100L);
    firstDocument.setSourceEntryUrl("https://example.com/feed/entry-1");
    var secondDocument =
        new io.opaa.indexing.Document("attachment.pdf", "/path2", "application/pdf", 100L);
    secondDocument.setSourceEntryUrl("https://example.com/feed/entry-2");
    when(documentRepository.findById(firstDocumentId)).thenReturn(Optional.of(firstDocument));
    when(documentRepository.findById(secondDocumentId)).thenReturn(Optional.of(secondDocument));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    assertThat(response.getSources())
        .extracting(ChatSource::getDocumentId, ChatSource::getSourceEntryUrl)
        .containsExactlyInAnyOrder(
            tuple(firstDocumentId, "https://example.com/feed/entry-1"),
            tuple(secondDocumentId, "https://example.com/feed/entry-2"));
  }

  /**
   * PR #745 review, nit 1: a chunk without {@code document_id} metadata (pre-#739 index entries)
   * used to compute its match count under the literal string {@code "unknown"} in {@link
   * #countMatchesPerDocument} but look it back up under {@code ""} in {@link #mapSources} - the
   * lookup always missed and silently fell back to a match count of 1. It also collapsed two such
   * chunks from <em>different</em> files into a single merged entry, since both shared the same
   * empty key. Falling back to {@code file_name} in both places fixes both: each file gets its own
   * entry with the correct match count.
   */
  @Test
  void queryFallsBackToFileNameForMatchCountingWhenDocumentIdMetadataIsMissing() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var firstChunkOfA =
        Document.builder()
            .text("From legacy document A, chunk 1")
            .metadata(Map.of("file_name", "legacy-a.pdf"))
            .score(0.9)
            .build();
    var secondChunkOfA =
        Document.builder()
            .text("From legacy document A, chunk 2")
            .metadata(Map.of("file_name", "legacy-a.pdf"))
            .score(0.8)
            .build();
    var chunkOfB =
        Document.builder()
            .text("From legacy document B")
            .metadata(Map.of("file_name", "legacy-b.pdf"))
            .score(0.7)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(firstChunkOfA, secondChunkOfA, chunkOfB));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    assertThat(response.getSources())
        .extracting(ChatSource::getFileName, ChatSource::getMatchCount)
        .containsExactlyInAnyOrder(tuple("legacy-a.pdf", 2), tuple("legacy-b.pdf", 1));
  }

  @Test
  void queryMarksCitedSourcesCorrectly() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var usage = createUsage(100, 200);
    var metadata = ChatResponseMetadata.builder().model("gpt-4o").usage(usage).build();
    var answer = "The answer is 42 【source: doc-123#0 | readme.md】";
    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage(answer))), metadata);
    when(answerGenerationService.generateAnswer(eq("What?"), any(), any()))
        .thenReturn(chatResponse);

    QueryResult response = queryService.query("What?", null, caller, true, List.of());

    assertThat(response.getAnswer()).contains("【source:");
    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("readme.md");
    // #1102: the reciprocal of the fused rank, not the chunk's raw score.
    assertThat(response.getSources().getFirst().getRelevanceScore()).isEqualTo(1.0);
    assertThat(response.getSources().getFirst().getCited()).isTrue();
    assertThat(response.getSources().getFirst().getMatchCount()).isEqualTo(1);
    assertThat(response.getMetadata().getModel()).isEqualTo("gpt-4o");
    assertThat(response.getMetadata().getTokenCount()).isEqualTo(300);
    assertThat(response.getChatId()).isNotNull();
  }

  @Test
  void queryGeneratesChatIdWhenNoneGiven() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getChatId()).isNotNull();
  }

  @Test
  void queryScopesAndPersistsWhenChatIdBelongsToTheCaller() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    String conversationKey = currentUserId + ":" + chatId;
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(conversationKey)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), eq(conversationKey)))
        .thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", chatId, caller, true, List.of());

    assertThat(response.getChatId()).isEqualTo(chatId);
    verify(chatService).appendTurn(eq(chat), eq("Question"), eq("Answer"), any());
  }

  /**
   * #561: {@code ChatService#appendTurn} now returns the chat's title after the turn (rather than
   * QueryService reading it back off the possibly-mutated {@link Chat} instance, which appendTurn
   * no longer mutates - see that method's Javadoc) - QueryService simply forwards whatever the
   * (here fully mocked) {@code chatService} returns.
   */
  @Test
  void queryIncludesTheChatsCurrentTitleInTheResponse() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    String conversationKey = currentUserId + ":" + chatId;
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(conversationKey)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), eq(conversationKey)))
        .thenReturn(chatResponse);
    when(chatService.appendTurn(eq(chat), any(), any(), any())).thenReturn("Frage zur Frist");

    QueryResult response = queryService.query("Frage zur Frist", chatId, caller, true, List.of());

    assertThat(response.getChatTitle()).isEqualTo("Frage zur Frist");
  }

  @Test
  void queryLeavesChatTitleNullForAnEphemeralQuery() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getChatTitle()).isNull();
  }

  @Test
  void queryUsesTheChatsRestrictedScopeWhenUseKnowledgeIsOff() {
    UUID otherReadableLibraryId = UUID.randomUUID();
    lenient()
        .when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId, otherReadableLibraryId));
    Chat chat =
        new Chat(
            UUID.randomUUID(),
            currentUserId,
            organizationId,
            null,
            false,
            Set.of(readableLibraryId));
    UUID chatId = chat.getId();
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(currentUserId + ":" + chatId)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    // Only the sticky reference, not the second library the caller can also read - the scope
    // that the chat's own useKnowledge=false restricts to (epic #523), regardless of the
    // request-level useKnowledge/libraryIds passed below.
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId, otherReadableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Question", chatId, caller, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    String filter = captor.getValue().getFilterExpression().toString();
    assertThat(filter).contains(readableLibraryId.toString());
    assertThat(filter).doesNotContain(otherReadableLibraryId.toString());
  }

  // #706 review, finding 3: the space-curated fail-open case - useKnowledge stays true
  // (@Alles-Wissen), the chat's space has at least one library association, but none of them are
  // readable by this caller, so effectiveLibraryScope legitimately resolves to empty. This must be
  // marked distinctly from answeredWithoutKnowledge (which only ever covers useKnowledge=false).
  @Test
  void queryMarksNoKnowledgeAvailableInSpaceWhenTheSpaceIsCuratedButNothingIsReadable() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(currentUserId + ":" + chatId)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId))).thenReturn(Set.of());
    when(chatService.spaceHasLibraryAssociations(chat.getSpaceId())).thenReturn(true);
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", chatId, caller, true, List.of());

    assertThat(response.getMetadata().getNoKnowledgeAvailableInSpace()).isTrue();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isFalse();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  // The unrelated ordinary case must stay unmarked: a space without any association resolving to
  // an empty scope would mean the caller simply has no readable library at all - not curation.
  @Test
  void queryDoesNotMarkNoKnowledgeAvailableInSpaceWhenTheSpaceHasNoAssociations() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    when(chatMemory.get(currentUserId + ":" + chatId)).thenReturn(List.of());
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(List.of());
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    lenient().when(chatService.spaceHasLibraryAssociations(chat.getSpaceId())).thenReturn(false);
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", chatId, caller, true, List.of());

    assertThat(response.getMetadata().getNoKnowledgeAvailableInSpace()).isFalse();
  }

  // An ephemeral query (no persisted chat) never marks this flag, regardless of scope - curation
  // only exists at the chat/space level.
  @Test
  void queryNeverMarksNoKnowledgeAvailableInSpaceForAnEphemeralQuery() {
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, false, List.of());

    assertThat(response.getMetadata().getNoKnowledgeAvailableInSpace()).isFalse();
  }

  @Test
  void queryRunsEphemerallyWhenChatIdDoesNotBelongToTheCaller() {
    // Pre-#525 behaviour, preserved for callers that have not moved to persisted chats yet (see
    // #527): a chatId that does not resolve to an owned chat is treated as a plain, non-persisted
    // conversation-cache key rather than being rejected - and it is echoed back unchanged, so a
    // client round-tripping it still gets multi-turn continuity without anything being persisted.
    UUID foreignChatId = UUID.randomUUID();
    when(chatService.findOwnedChat(foreignChatId, currentUserId)).thenReturn(Optional.empty());
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", foreignChatId, caller, true, List.of());

    assertThat(response.getChatId()).isEqualTo(foreignChatId);
    verify(chatService, never()).appendTurn(any(), any(), any(), any());
  }

  // #840: a space archived between a persisted chat's creation and this query must reject the
  // query before retrieval/the LLM call, not merely at appendTurn afterwards - the whole point
  // being that the ordinary case never pays for a paid LLM call whose answer would be discarded
  // anyway. ChatService#requireSpaceNotArchived is mocked here (it is chatService's own
  // production logic, exercised for real in ChatServiceIntegrationTest's
  // appendingATurnInAnArchivedSpaceIsRejected, which still covers appendTurn's own race-guard
  // call to the same method) purely as the trigger; this test's job is only to prove QueryService
  // calls it before any of the readable-scope/retrieval/model work below, not to re-verify the
  // archived-space rule itself.
  @Test
  void queryRejectsArchivedSpaceBeforeCallingTheModel() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));
    doThrow(new ConflictException("Der Space ist archiviert und lässt keine neuen Inhalte mehr zu"))
        .when(chatService)
        .requireSpaceNotArchived(chat.getSpaceId());

    assertThatThrownBy(() -> queryService.query("Question", chatId, caller, true, List.of()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("archiviert");

    // Not just "before the model": nothing past the early check runs at all, including the
    // readable-scope computation and the conversation-memory cache - proving the check's early
    // placement, not merely that it precedes the LLM call specifically.
    verifyNoInteractions(
        vectorStore,
        answerGenerationService,
        libraryAccessService,
        permissionHistoryService,
        chatMemory);
    verify(chatService, never()).appendTurn(any(), any(), any(), any());
  }

  /**
   * #123 follow-up: explicit two-account test against the real cache stack ({@link
   * MessageWindowChatMemory} over {@link CaffeineChatMemoryRepository}, exactly as {@link
   * QueryConfiguration} wires it) instead of this test class's otherwise-mocked {@code chatMemory}
   * field. Both accounts submit the same {@code chatId}; neither resolves it via {@link
   * ChatService#findOwnedChat} (stubbed empty for every argument in {@link #setUp}), so both run
   * ephemerally - see the class Javadoc on {@link
   * #queryRunsEphemerallyWhenChatIdDoesNotBelongToTheCaller} for that pre-existing behaviour. The
   * persisted-chat-owner-versus-foreign-account combination is not additionally covered here: it
   * exercises the same key formula ({@code currentUserId + ":" + effectiveChatId}, {@link
   * QueryService#query}) on the owner side that {@link
   * #queryScopesAndPersistsWhenChatIdBelongsToTheCaller} already asserts via the mocked {@code
   * chatMemory}, so it would not add coverage of a code path this test does not already reach. The
   * test simulates what {@link AnswerGenerationService} does in production - write two messages per
   * call under the given conversation key - and captures the key actually used per call instead of
   * recomputing the formula, so the assertions stay valid even if the key format changes.
   */
  @Test
  void sameChatIdForTwoDifferentUsersProducesIsolatedConversationHistories() {
    ChatMemoryRepository realRepository = new CaffeineChatMemoryRepository(50, 60);
    ChatMemory realChatMemory =
        MessageWindowChatMemory.builder()
            .chatMemoryRepository(realRepository)
            .maxMessages(20)
            .build();
    QueryService serviceWithRealMemory =
        newQueryService(
            new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, 2, false, 50), realChatMemory);

    UUID otherUserId = UUID.randomUUID();
    CurrentUser otherCaller =
        CurrentUser.of(otherUserId, organizationId, SystemRole.USER, "Other User");
    // useKnowledge=false with no requested library keeps the search scope empty regardless of
    // what this account may read, so the readable-set stub's content is irrelevant here - the
    // vector store and permission-history check are simply skipped for an empty scope (see
    // QueryService#query and #checkAgainstPermissionHistory).
    when(libraryAccessService.readableLibraryIds(otherUserId, organizationId)).thenReturn(Set.of());

    UUID sharedChatId = UUID.randomUUID();
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    List<String> usedConversationKeys = new ArrayList<>();
    when(answerGenerationService.generateAnswer(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              String conversationKey = invocation.getArgument(2);
              usedConversationKeys.add(conversationKey);
              realChatMemory.add(
                  conversationKey, new UserMessage("question from " + conversationKey));
              realChatMemory.add(
                  conversationKey, new AssistantMessage("answer to " + conversationKey));
              return chatResponse;
            });

    serviceWithRealMemory.query("Question A", sharedChatId, caller, false, List.of());
    serviceWithRealMemory.query("Question B", sharedChatId, otherCaller, false, List.of());

    assertThat(usedConversationKeys).hasSize(2);
    String keyUserA = usedConversationKeys.get(0);
    String keyUserB = usedConversationKeys.get(1);
    assertThat(keyUserA).isNotEqualTo(keyUserB);

    List<Message> historyUserA = realChatMemory.get(keyUserA);
    List<Message> historyUserB = realChatMemory.get(keyUserB);

    assertThat(historyUserA).hasSize(2);
    assertThat(historyUserB).hasSize(2);
    assertThat(historyUserA.stream().map(Message::getText))
        .allMatch(text -> text.contains(keyUserA))
        .noneMatch(text -> text.contains(keyUserB));
    assertThat(historyUserB.stream().map(Message::getText))
        .allMatch(text -> text.contains(keyUserB))
        .noneMatch(text -> text.contains(keyUserA));
  }

  /**
   * #525 acceptance criterion: "Folgefragen im selben Chat nutzen den persistierten Verlauf als
   * Kontext". Simulates a cold in-memory cache (the very first {@code chatMemory.get} call in this
   * test returns empty, as it would after a restart or eviction) with history nonetheless available
   * from the persisted store - and asserts the search query is enriched from that persisted
   * history, not left as the plain question.
   */
  @Test
  void queryEnrichesSearchFromPersistedHistoryOnAColdConversationCache() {
    Chat chat = new Chat(UUID.randomUUID(), currentUserId, organizationId, null, true, Set.of());
    UUID chatId = chat.getId();
    String conversationKey = currentUserId + ":" + chatId;
    when(chatService.findOwnedChat(chatId, currentUserId)).thenReturn(Optional.of(chat));

    List<Message> persistedHistory =
        List.of(
            new UserMessage("Was sind meine Ausgaben bei Apple?"),
            new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR."));
    // First call (the seeding check) sees a cold cache; the second call (inside
    // buildSearchQuery) sees it warmed by the seeding this test asserts happened.
    when(chatMemory.get(conversationKey)).thenReturn(List.of(), persistedHistory);
    when(chatService.historyAsSpringAiMessages(chatId)).thenReturn(persistedHistory);
    when(chatService.effectiveLibraryScope(chat, Set.of(readableLibraryId)))
        .thenReturn(Set.of(readableLibraryId));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Tabelle"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query(
        "Mach daraus eine tabellarische Auflistung", chatId, caller, true, List.of());

    verify(chatMemory).add(conversationKey, persistedHistory);
    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Mach daraus eine tabellarische Auflistung");
  }

  @Test
  void queryMarksUncitedSourcesCorrectly() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var citedChunk =
        Document.builder()
            .text("Cited content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var uncitedChunk =
        Document.builder()
            .text("Uncited content")
            .metadata(Map.of("file_name", "other.pdf", "document_id", "doc-2"))
            .score(0.7)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(citedChunk, uncitedChunk));

    var answer = "Info from readme 【source: doc-1#0 | readme.md】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    assertThat(response.getSources().get(0).getCited()).isTrue();
    assertThat(response.getSources().get(1).getCited()).isFalse();
  }

  /**
   * #386 acceptance criterion + reproduction proof: a citation whose document id is not among the
   * chunks retrieved for this answer must be flagged as invalid - never silently dropped, and never
   * allowed to pass through as an ordinary, unflagged citation. Before the fix this fabricated
   * citation produced no source entry at all (it matches no real chunk's document id in {@code
   * mapSources}' original iteration), so nothing in the response indicated anything was wrong with
   * it - this assertion is red on the pre-fix code (no entry exists to carry {@code citationValid:
   * false} at all) and green after. Uses a file name that collides with no retrieved chunk, so the
   * result is unaffected by the collision-folding {@link
   * #queryFoldsACollidingSyntheticEntryIntoTheRealUncitedSourceInsteadOfAddingARow} covers
   * separately.
   */
  @Test
  void queryMarksCitationToAFabricatedDocumentIdAsInvalid() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: fabricated-id#0 | fabricated-name.pdf】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("What?", null, caller, true, List.of());

    assertThat(response.getSources())
        .anySatisfy(
            source -> {
              assertThat(source.getFileName()).isEqualTo("fabricated-name.pdf");
              assertThat(source.getCitationValid()).isFalse();
              assertThat(source.getCited()).isTrue();
            });
  }

  /**
   * #386 acceptance criterion: a citation with a valid document id and section but a file name that
   * does not match that document is invalid - it is more misleading than no citation at all.
   */
  @Test
  void queryMarksCitationWithMismatchedFileNameAsInvalid() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: doc-123#0 | wrong-name.pdf】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("What?", null, caller, true, List.of());

    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("readme.md");
    assertThat(response.getSources().getFirst().getCitationValid()).isFalse();
  }

  /**
   * #386 acceptance criterion: a section number that belongs to the cited document but was not
   * among the chunks retrieved for this answer is invalid.
   */
  @Test
  void queryMarksCitationWithSectionOutsideRetrievedChunksAsInvalid() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: doc-123#9 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("What?", null, caller, true, List.of());

    assertThat(response.getSources().getFirst().getFileName()).isEqualTo("readme.md");
    assertThat(response.getSources().getFirst().getCitationValid()).isFalse();
  }

  /** #386: a citation matching the retrieved chunk exactly stays unflagged. */
  @Test
  void queryLeavesAValidCitationUnflagged() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer is 42 【source: doc-123#0 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("What?", null, caller, true, List.of());

    assertThat(response.getSources().getFirst().getCitationValid()).isTrue();
  }

  /**
   * #697 review, finding 4 + second round: a fabricated citation's synthetic entry must never merge
   * with a real, retrieved-but-uncited source that happens to share its file name via the normal
   * dedupe-by-filename merge - that would have made the real document appear falsely cited, with
   * its real relevance score and its real "open in document" link, for a citation it was never
   * actually named in. The second review round found that the first fix (never merging the two
   * groups at all) traded that failure for another: two {@code ChatSource} rows sharing one file
   * name, which the frontend's marker-to-source join resolves last-wins - always to the synthetic,
   * zero-relevance row, corrupting even a source that {@code was} genuinely, validly cited (see the
   * next test). The fix folds a colliding synthetic entry into the real one instead of adding a
   * second row: only {@code citationValid} moves to {@code false}, nothing else about the real
   * entry changes.
   */
  @Test
  void queryFoldsACollidingSyntheticEntryIntoTheRealUncitedSourceInsteadOfAddingARow() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    // "fabricated-id" is not among the retrieved chunks, but the model still copied the correct,
    // real file name into the fabricated citation - the exact collision finding 4 describes.
    var answer = "The answer is 42 【source: fabricated-id#0 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("What?", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    ChatSource source = response.getSources().getFirst();
    assertThat(source.getFileName()).isEqualTo("readme.md");
    assertThat(source.getRelevanceScore()).isEqualTo(1.0);
    assertThat(source.getMatchCount()).isEqualTo(1);
    assertThat(source.getCited()).isFalse();
    assertThat(source.getCitationValid()).isFalse();
  }

  /**
   * #697 second review round's concrete scenario: a source is both validly cited <em>and</em> named
   * by a colliding fabricated citation. Before this fix, the frontend's file-name join would have
   * resolved to the synthetic, zero-relevance row for this file - the validly cited real source
   * would have displayed with 0% relevance and no document link, even though a genuine citation
   * pointed at it. Folding the collision into the real entry keeps its real {@code cited = true},
   * relevance score and link intact; only {@code citationValid} reflects the separate, invalid
   * citation that also named this file.
   */
  @Test
  void queryPreservesRealMetadataOnAValidlyCitedSourceThatAlsoCollidesWithASyntheticEntry() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Relevant content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-123"))
            .score(0.85)
            .build();
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer =
        "The answer is 42 【source: doc-123#0 | readme.md】 【source: fabricated-id#0 | readme.md】";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("What?", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    ChatSource source = response.getSources().getFirst();
    assertThat(source.getFileName()).isEqualTo("readme.md");
    assertThat(source.getRelevanceScore()).isEqualTo(1.0);
    assertThat(source.getCited()).isTrue();
    assertThat(source.getCitationValid()).isFalse();
  }

  @Test
  void queryCountsMatchesPerFile() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk1 =
        Document.builder()
            .text("First chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var chunk2 =
        Document.builder()
            .text("Second chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();
    var chunk3 =
        Document.builder()
            .text("Readme chunk")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-2"))
            .score(0.8)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2, chunk3));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    assertThat(response.getSources().get(0).getFileName()).isEqualTo("report.pdf");
    assertThat(response.getSources().get(0).getMatchCount()).isEqualTo(2);
    assertThat(response.getSources().get(1).getFileName()).isEqualTo("readme.md");
    assertThat(response.getSources().get(1).getMatchCount()).isEqualTo(1);
  }

  @Test
  void queryRetainsCitationMarkersInAnswer() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk =
        Document.builder()
            .text("Content")
            .metadata(Map.of("file_name", "readme.md", "document_id", "doc-1"))
            .score(0.9)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));

    var answer = "The answer 【source: doc-1#0 | readme.md】 is here.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getAnswer()).isEqualTo(answer);
  }

  @Test
  void queryDeduplicatesSourcesByFileName() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var chunk1 =
        Document.builder()
            .text("High relevance chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.9)
            .build();
    var chunk2 =
        Document.builder()
            .text("Lower relevance chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(chunk1, chunk2));

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getRelevanceScore()).isEqualTo(1.0);
  }

  /**
   * #914: {@code similaritySearch} itself is called with {@code fetchK}, not {@code topK} - the
   * larger candidate pool {@link MmrSelector} narrows down afterwards, in {@link
   * QueryService#query} itself, not inside this mocked call.
   */
  @Test
  void queryPassesSearchRequestWithCorrectParameters() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse =
        new ChatResponse(List.of(new Generation(new AssistantMessage("No results"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Test query", null, caller, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    SearchRequest request = captor.getValue();
    assertThat(request.getQuery()).isEqualTo("Test query");
    assertThat(request.getTopK()).isEqualTo(25);
    assertThat(request.getSimilarityThreshold()).isEqualTo(0.3);
    // The permission filter is asserted here too, not only in the dedicated
    // queryFiltersOnReadableLibraryIds test below - this test's job is exactly "every
    // SearchRequest parameter", and the filter is one of them.
    assertThat(request.getFilterExpression()).isNotNull();
    assertThat(request.getFilterExpression().toString()).contains(readableLibraryId.toString());
  }

  @Test
  void queryFiltersOnReadableLibraryIds() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Test query", null, caller, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getFilterExpression()).isNotNull();
    assertThat(captor.getValue().getFilterExpression().toString())
        .contains(readableLibraryId.toString());
  }

  @Test
  void queryWithNoReadableLibrariesSkipsVectorStoreAndReturnsEmptySources() {
    when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).isEmpty();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  @Test
  void queryWithUseKnowledgeFalseAndOneReferencedLibraryOnlySearchesThatLibrary() {
    UUID otherReadableLibraryId = UUID.randomUUID();
    when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId, otherReadableLibraryId));
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Question", null, caller, false, List.of(readableLibraryId));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    String filterExpression = captor.getValue().getFilterExpression().toString();
    assertThat(filterExpression).contains(readableLibraryId.toString());
    assertThat(filterExpression).doesNotContain(otherReadableLibraryId.toString());
  }

  @Test
  void queryWithUseKnowledgeFalseAndUnreadableLibrarySkipsVectorStoreAndReturnsEmptySources() {
    UUID unreadableLibraryId = UUID.randomUUID();
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response =
        queryService.query("Question", null, caller, false, List.of(unreadableLibraryId));

    assertThat(response.getSources()).isEmpty();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  @Test
  void queryWithUseKnowledgeFalseAndNoReferencesSkipsVectorStoreAndMarksAnsweredWithoutKnowledge() {
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, false, List.of());

    assertThat(response.getSources()).isEmpty();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
    // An empty search scope must not pay for the decomposition LLM call either.
    org.mockito.Mockito.verifyNoInteractions(queryDecompositionService);
  }

  @Test
  void queryWithUseKnowledgeTrueDoesNotMarkAnsweredWithoutKnowledge() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isFalse();
  }

  @Test
  void queryWithUseKnowledgeTrueIgnoresLibraryIdsAndSearchesAllReadableLibraries() {
    UUID otherReadableLibraryId = UUID.randomUUID();
    when(libraryAccessService.readableLibraryIds(currentUserId, organizationId))
        .thenReturn(Set.of(readableLibraryId, otherReadableLibraryId));
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    // useKnowledge = true with a non-empty libraryIds: the list must be ignored, and the search
    // scope stays every readable library - not just the one referenced here.
    queryService.query("Question", null, caller, true, List.of(readableLibraryId));

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    String filterExpression = captor.getValue().getFilterExpression().toString();
    assertThat(filterExpression).contains(readableLibraryId.toString());
    assertThat(filterExpression).contains(otherReadableLibraryId.toString());
  }

  @Test
  void
      queryWithUseKnowledgeFalseAndNullLibraryIdsSkipsVectorStoreAndMarksAnsweredWithoutKnowledge() {
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    // null requestedLibraryIds must behave exactly like an empty list, not throw or search
    // everything readable.
    QueryResult response = queryService.query("Question", null, caller, false, null);

    assertThat(response.getSources()).isEmpty();
    assertThat(response.getMetadata().getAnsweredWithoutKnowledge()).isTrue();
    org.mockito.Mockito.verifyNoInteractions(vectorStore);
  }

  // #739: two chunks sharing the same document_id (multiple chunks retrieved from one document) -
  // the merge that still applies since the dedupe key changed from fileName to document_id.
  @Test
  void queryPreservesCitedFlagWhenDeduplicatingChunksFromSameFile() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var citedChunk =
        Document.builder()
            .text("Cited chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();
    var higherScoreUncitedChunk =
        Document.builder()
            .text("Higher score uncited")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.95)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(citedChunk, higherScoreUncitedChunk));

    var answer = "Info 【source: doc-1#0 | report.pdf】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(1);
    assertThat(response.getSources().getFirst().getCited()).isTrue();
    assertThat(response.getSources().getFirst().getRelevanceScore()).isEqualTo(1.0);
  }

  /**
   * #739: the collision the previous test used to exercise (two <b>different</b> document ids
   * sharing one file name) is now covered by {@link
   * #queryKeepsTwoDocumentsThatShareAFileNameAsSeparateSources} instead - each keeps its own row,
   * so neither collapses into the other and {@code cited} is per-document, not per-file-name.
   */
  @Test
  void queryDoesNotMergeCitedFlagAcrossTwoDifferentDocumentsSharingAFileName() {
    when(chatMemory.get(any())).thenReturn(List.of());
    var citedChunk =
        Document.builder()
            .text("Cited chunk")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-1"))
            .score(0.7)
            .build();
    var higherScoreUncitedChunk =
        Document.builder()
            .text("Higher score uncited")
            .metadata(Map.of("file_name", "report.pdf", "document_id", "doc-2"))
            .score(0.95)
            .build();

    when(vectorStore.similaritySearch(any(SearchRequest.class)))
        .thenReturn(List.of(citedChunk, higherScoreUncitedChunk));

    var answer = "Info 【source: doc-1#0 | report.pdf】.";
    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    QueryResult response = queryService.query("Question", null, caller, true, List.of());

    assertThat(response.getSources()).hasSize(2);
    assertThat(response.getSources())
        .extracting(ChatSource::getFileName, ChatSource::getCited)
        .containsExactlyInAnyOrder(tuple("report.pdf", true), tuple("report.pdf", false));
  }

  @Test
  void queryEnrichesSearchWithConversationHistory() {
    UUID chatId = UUID.randomUUID();
    when(chatMemory.get(currentUserId + ":" + chatId))
        .thenReturn(
            List.of(
                new UserMessage("Was sind meine Ausgaben bei Apple?"),
                new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR.")));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Tabelle"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query(
        "Mach daraus eine tabellarische Auflistung", chatId, caller, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Mach daraus eine tabellarische Auflistung");
  }

  @Test
  void queryEnrichesThirdMessageWithFirstUserQuestion() {
    UUID chatId = UUID.randomUUID();
    when(chatMemory.get(currentUserId + ":" + chatId))
        .thenReturn(
            List.of(
                new UserMessage("Was sind meine Ausgaben bei Apple?"),
                new AssistantMessage("Ihre Apple-Ausgaben betragen 500 EUR."),
                new UserMessage("Mach daraus eine Tabelle"),
                new AssistantMessage("Hier ist die Tabelle...")));
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Sortiert"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("Sortiere nach Datum", chatId, caller, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery())
        .isEqualTo("Was sind meine Ausgaben bei Apple? Sortiere nach Datum");
  }

  @Test
  void queryUsesPlainQuestionWhenNoHistory() {
    when(chatMemory.get(any())).thenReturn(List.of());
    when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

    var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
    when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

    queryService.query("First question", null, caller, true, List.of());

    ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
    verify(vectorStore).similaritySearch(captor.capture());
    assertThat(captor.getValue().getQuery()).isEqualTo("First question");
  }

  /**
   * #525 review round 2, finding A: {@code query()} deliberately carries no {@code @Transactional}
   * annotation at all - an ambient (even read-only) transaction held for this method's whole
   * duration, including the LLM call, would starve the connection pool once {@code
   * ChatService#appendTurn} needed a second connection to write under load (see {@code
   * QueryService#query}'s Javadoc for the full pool-deadlock reasoning, the same class of bug #299
   * fixed in {@code UserService.findOrCreateUser}). This test pins the absence of the annotation
   * itself, not merely its former {@code readOnly = true} value - the previous version of this test
   * asserted the exact setting finding A required removing.
   */
  @Test
  void queryMethodCarriesNoTransactionalAnnotation() throws NoSuchMethodException {
    Method queryMethod =
        QueryService.class.getMethod(
            "query", String.class, UUID.class, CurrentUser.class, boolean.class, List.class);

    assertThat(queryMethod.getAnnotation(Transactional.class)).isNull();
  }

  @Nested
  class MergeSourceReferences {

    private static final Instant INDEXED_AT = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void keepsHigherRelevanceScore() {
      var high = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);
      var low = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(high, low);

      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
    }

    /** #667: the merged entry knows every retrieved chunk's location, in chunk order. */
    @Test
    void unionsChunkLocationsInChunkOrder() {
      var high = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);
      high.setChunkLocations(List.of(new ChatSourceLocation(5).location("S. 3")));
      var low = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, true);
      low.setChunkLocations(List.of(new ChatSourceLocation(2).location("S. 1")));

      var result = QueryService.mergeSourceReferences(high, low);

      assertThat(result.getChunkLocations())
          .extracting(ChatSourceLocation::getChunkIndex, ChatSourceLocation::getLocation)
          .containsExactly(tuple(2, "S. 1"), tuple(5, "S. 3"));
    }

    @Test
    void keepsHigherScoreRegardlessOfOrder() {
      var low = sourceReference("file.pdf", 0.3, 1, INDEXED_AT, false);
      var high = sourceReference("file.pdf", 0.8, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(low, high);

      assertThat(result.getRelevanceScore()).isEqualTo(0.8);
    }

    @Test
    void prefersFirstWhenScoresAreEqual() {
      var first = sourceReference("file.pdf", 0.7, 2, INDEXED_AT, true);
      var second = sourceReference("file.pdf", 0.7, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(first, second);

      assertThat(result).isEqualTo(first);
    }

    @Test
    void preservesCitedWhenHigherScoreIsCited() {
      var cited = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, true);
      var uncited = sourceReference("file.pdf", 0.5, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(cited, uncited);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
    }

    @Test
    void forcesCitedWhenLowerScoreIsCitedButHigherWins() {
      var citedLow = sourceReference("file.pdf", 0.3, 1, INDEXED_AT, true);
      var uncitedHigh = sourceReference("file.pdf", 0.9, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(citedLow, uncitedHigh);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getRelevanceScore()).isEqualTo(0.9);
      assertThat(result.getFileName()).isEqualTo("file.pdf");
    }

    @Test
    void returnsFalseWhenNeitherIsCited() {
      var a = sourceReference("file.pdf", 0.8, 1, INDEXED_AT, false);
      var b = sourceReference("file.pdf", 0.6, 1, INDEXED_AT, false);

      var result = QueryService.mergeSourceReferences(a, b);

      assertThat(result.getCited()).isFalse();
    }

    @Test
    void preservesMetadataFromPreferredSource() {
      var indexedEarly = Instant.parse("2024-01-01T00:00:00Z");
      var indexedLate = Instant.parse("2025-06-01T00:00:00Z");
      var high = sourceReference("report.pdf", 0.95, 3, indexedLate, false);
      var low = sourceReference("report.pdf", 0.4, 1, indexedEarly, true);

      var result = QueryService.mergeSourceReferences(high, low);

      assertThat(result.getMatchCount()).isEqualTo(3);
      assertThat(result.getIndexedAt()).isEqualTo(indexedLate);
      assertThat(result.getCited()).isTrue();
    }

    /**
     * #639: the branch that builds a fresh {@code ChatSource} to force {@code cited = true}
     * (because a lower-scoring duplicate was cited but the higher-scoring one is preferred) carries
     * {@code sourceEntryUrl} over from the preferred source, same as {@code indexedAt}.
     */
    @Test
    void preservesSourceEntryUrlWhenForcingCited() {
      var citedLow =
          sourceReference("report.pdf", 0.3, 1, INDEXED_AT, true, "https://example.com/entry-1");
      var uncitedHigh =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");

      var result = QueryService.mergeSourceReferences(citedLow, uncitedHigh);

      assertThat(result.getCited()).isTrue();
      assertThat(result.getSourceEntryUrl()).isEqualTo("https://example.com/entry-1");
    }

    /**
     * #666 review: two distinct documents can share a file name, each with its own {@code
     * sourceEntryUrl} - picking either side's URL for the merged citation would be an unverifiable,
     * potentially wrong claim about where the other chunk actually came from. The merge must drop
     * to {@code null} rather than assert one of two disagreeing URLs.
     */
    @Test
    void dropsSourceEntryUrlWhenMergedSourcesDisagree() {
      var a =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");
      var b =
          sourceReference("report.pdf", 0.5, 1, INDEXED_AT, false, "https://example.com/entry-2");

      var result = QueryService.mergeSourceReferences(a, b);

      assertThat(result.getSourceEntryUrl()).isNull();
    }

    /**
     * #666 review: one side carrying no {@code sourceEntryUrl} at all (not merely a different one)
     * is also a disagreement - a document with a URL and one without do not corroborate each other.
     */
    @Test
    void dropsSourceEntryUrlWhenOnlyOneSourceHasOne() {
      var withUrl =
          sourceReference("report.pdf", 0.9, 1, INDEXED_AT, false, "https://example.com/entry-1");
      var withoutUrl = sourceReference("report.pdf", 0.5, 1, INDEXED_AT, false, null);

      var result = QueryService.mergeSourceReferences(withUrl, withoutUrl);

      assertThat(result.getSourceEntryUrl()).isNull();
    }
  }

  /**
   * Query decomposition and multi-search retrieval fusion (#923). {@link
   * #queryDecompositionService} is unstubbed (empty list) by every other test in this class - see
   * that field's Javadoc - so these are the only tests here exercising the multi-sub-query path.
   */
  @Nested
  class QueryDecomposition {

    @Test
    void aSingleSubQueryTakesTheUnfusedPreDecompositionPath() {
      when(chatMemory.get(any())).thenReturn(List.of());
      when(queryDecompositionService.decompose(eq("Question"), any(), eq(3)))
          .thenReturn(List.of("Umformulierte Frage"));
      var chunk =
          Document.builder()
              .text("Relevant content")
              .metadata(Map.of("file_name", "readme.md", "document_id", "doc-1"))
              .score(0.9)
              .build();
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(chunk));
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      queryService.query("Question", null, caller, true, List.of());

      ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
      verify(vectorStore, times(1)).similaritySearch(captor.capture());
      assertThat(captor.getValue().getQuery()).isEqualTo("Umformulierte Frage");
      // The single-search path uses fetchK (25), not the per-sub-query budget - unchanged from the
      // pre-#923 behaviour MmrSelectorTest/this class's other tests already cover.
      assertThat(captor.getValue().getTopK()).isEqualTo(25);
    }

    /**
     * Two sub-queries each run their own {@code similaritySearch} against the identical
     * permission/threshold filter, and the results are fused rather than either search's result
     * simply winning - proven here by each sub-query contributing a chunk the other's search never
     * returned at all.
     */
    @Test
    void multipleSubQueriesRunSeparateSearchesAndFuseTheResults() {
      when(chatMemory.get(any())).thenReturn(List.of());
      when(queryDecompositionService.decompose(eq("Kombifrage"), any(), eq(3)))
          .thenReturn(List.of("Teilfrage A", "Teilfrage B"));
      var chunkA =
          Document.builder()
              .id("chunk-a")
              .text("Content A")
              .metadata(Map.of("file_name", "a.md", "document_id", "doc-a"))
              .score(0.9)
              .build();
      var chunkB =
          Document.builder()
              .id("chunk-b")
              .text("Content B")
              .metadata(Map.of("file_name", "b.md", "document_id", "doc-b"))
              .score(0.9)
              .build();
      // Null-safe: registering the second argThat below re-evaluates the first stub's matcher
      // against Mockito's internal null-argument probe call, which would otherwise NPE.
      when(vectorStore.similaritySearch(
              argThat(
                  (SearchRequest request) ->
                      request != null && "Teilfrage A".equals(request.getQuery()))))
          .thenReturn(List.of(chunkA));
      when(vectorStore.similaritySearch(
              argThat(
                  (SearchRequest request) ->
                      request != null && "Teilfrage B".equals(request.getQuery()))))
          .thenReturn(List.of(chunkB));
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response = queryService.query("Kombifrage", null, caller, true, List.of());

      verify(vectorStore, times(2)).similaritySearch(any(SearchRequest.class));
      assertThat(response.getSources())
          .extracting(ChatSource::getFileName)
          .containsExactlyInAnyOrder("a.md", "b.md");
    }

    /** Every sub-query's {@code similaritySearch} call carries the identical permission filter. */
    @Test
    void everySubQuerysSearchCarriesTheSamePermissionFilter() {
      when(chatMemory.get(any())).thenReturn(List.of());
      when(queryDecompositionService.decompose(eq("Kombifrage"), any(), eq(3)))
          .thenReturn(List.of("Teilfrage A", "Teilfrage B"));
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      queryService.query("Kombifrage", null, caller, true, List.of());

      ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
      verify(vectorStore, times(2)).similaritySearch(captor.capture());
      List<SearchRequest> requests = captor.getAllValues();
      assertThat(requests.get(0).getFilterExpression())
          .isEqualTo(requests.get(1).getFilterExpression());
    }

    /**
     * An empty decomposition result (LLM failure or unparsable output, {@code
     * QueryDecompositionService#decompose}'s documented failure signal) falls back to {@link
     * SubQueryDecompositionStage#buildSearchQuery}'s pre-#923 single-query behaviour unchanged.
     */
    @Test
    void emptyDecompositionResultFallsBackToBuildSearchQuery() {
      when(chatMemory.get(any())).thenReturn(List.of());
      when(queryDecompositionService.decompose(eq("Question"), any(), eq(3))).thenReturn(List.of());
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      queryService.query("Question", null, caller, true, List.of());

      ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
      verify(vectorStore, times(1)).similaritySearch(captor.capture());
      assertThat(captor.getValue().getQuery()).isEqualTo("Question");
    }

    /** {@code queryDecompositionEnabled = false} skips the decomposition call entirely. */
    @Test
    void decompositionDisabledSkipsTheDecompositionServiceEntirely() {
      QueryService serviceWithDecompositionDisabled =
          newQueryService(
              new QueryProperties(8, 25, 1.0, 0.3, 1.0, false, 3, 2, false, 50), chatMemory);
      when(chatMemory.get(any())).thenReturn(List.of());
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      serviceWithDecompositionDisabled.query("Question", null, caller, true, List.of());

      verifyNoInteractions(queryDecompositionService);
    }

    /**
     * #923 review: every sub-query is independently MMR-narrowed to the full {@code topK}, not
     * {@code topK} divided across sub-queries - the fused, deduplicated result is capped at {@code
     * topK} afterwards, so a full overlap between sub-queries' results still yields exactly {@code
     * topK} chunks instead of an under-filled result.
     */
    @Test
    void eachSubQueryIsMmrNarrowedToTheFullTopKBeforeFusion() {
      when(chatMemory.get(any())).thenReturn(List.of());
      when(queryDecompositionService.decompose(eq("Kombifrage"), any(), eq(3)))
          .thenReturn(List.of("Teilfrage A", "Teilfrage B"));
      List<Document> eightChunks =
          IntStream.range(0, 8)
              .mapToObj(
                  i ->
                      Document.builder()
                          .id("chunk-" + i)
                          .text("Content " + i)
                          .metadata(Map.of("file_name", "f" + i + ".md", "document_id", "doc-" + i))
                          .score(0.9 - i * 0.01)
                          .build())
              .toList();
      // Both sub-queries return the identical, fully overlapping candidate set.
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(eightChunks);
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Answer"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response = queryService.query("Kombifrage", null, caller, true, List.of());

      assertThat(response.getSources()).hasSize(8);
    }
  }

  /**
   * Document completion after fusion (#932): a document already represented in the final selection
   * preferably keeps up to {@code maxChunksPerDocument} of its chunks from the candidate pool,
   * evicting the RRF/MMR-weakest chunk of an already-≥2-chunk document rather than losing a chunk
   * to a completely unrelated document. Mirrors the #912 defect this fixes: a combined question
   * retrieves a document's introduction chunk but not its fee-table chunk, even though the
   * fee-table chunk was among the permission/threshold-filtered candidates all along.
   */
  @Nested
  class DocumentCompletionAfterFusion {

    private Document chunk(String id, String documentId, String fileName, double score) {
      return Document.builder()
          .id(id)
          .text(id)
          .metadata(Map.of("document_id", documentId, "file_name", fileName))
          .score(score)
          .build();
    }

    /**
     * Single-query path (no decomposition): {@code doc-x} occupies two of the eight slots MMR picks
     * by pure score, crowding {@code doc-a}'s fee chunk out of the {@code topK} = 8 window
     * entirely. Completion recovers the fee chunk by evicting {@code doc-x}'s weaker of its two
     * chunks - document diversity (7 distinct documents: doc-x, doc-a, doc-f1..doc-f5) is
     * unchanged, but {@code doc-a} now carries both its chunks instead of {@code doc-x}.
     */
    @Test
    void singleQueryPathRecoversAFeeChunkByEvictingAnOverrepresentedDocumentsWeakerChunk() {
      when(chatMemory.get(any())).thenReturn(List.of());
      List<Document> candidates =
          new ArrayList<>(
              List.of(
                  chunk("x-0", "doc-x", "x.md", 0.95),
                  chunk("x-1", "doc-x", "x.md", 0.90),
                  chunk("a-intro", "doc-a", "a.md", 0.85),
                  chunk("f-1", "doc-f1", "f1.md", 0.80),
                  chunk("f-2", "doc-f2", "f2.md", 0.75),
                  chunk("f-3", "doc-f3", "f3.md", 0.70),
                  chunk("f-4", "doc-f4", "f4.md", 0.65),
                  chunk("f-5", "doc-f5", "f5.md", 0.60)));
      // Ranked 9th - below the topK=8 window MMR alone would keep, and thus excluded before
      // completion recovers it.
      candidates.add(chunk("a-fee", "doc-a", "a.md", 0.55));
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response = queryService.query("Frage", null, caller, true, List.of());

      assertThat(response.getSources()).hasSize(7);
      assertThat(response.getSources())
          .filteredOn(source -> source.getFileName().equals("a.md"))
          .hasSize(1)
          .allSatisfy(source -> assertThat(source.getMatchCount()).isEqualTo(2));
      assertThat(response.getSources())
          .filteredOn(source -> source.getFileName().equals("x.md"))
          .hasSize(1)
          .allSatisfy(source -> assertThat(source.getMatchCount()).isEqualTo(1));
    }

    /**
     * The identical scenario through the decomposed, multi-sub-query path (#923): both sub-queries
     * return the same permission/threshold-filtered candidates, so each independently MMR-narrows
     * to the same top-8 window and RRF-fuses back to that unchanged set - completion then runs on
     * the fused result exactly as it does on the single-query path.
     */
    @Test
    void multiQueryPathRecoversAFeeChunkByEvictingAnOverrepresentedDocumentsWeakerChunk() {
      when(chatMemory.get(any())).thenReturn(List.of());
      when(queryDecompositionService.decompose(eq("Kombifrage"), any(), eq(3)))
          .thenReturn(List.of("Teilfrage A", "Teilfrage B"));
      List<Document> candidates =
          new ArrayList<>(
              List.of(
                  chunk("x-0", "doc-x", "x.md", 0.95),
                  chunk("x-1", "doc-x", "x.md", 0.90),
                  chunk("a-intro", "doc-a", "a.md", 0.85),
                  chunk("f-1", "doc-f1", "f1.md", 0.80),
                  chunk("f-2", "doc-f2", "f2.md", 0.75),
                  chunk("f-3", "doc-f3", "f3.md", 0.70),
                  chunk("f-4", "doc-f4", "f4.md", 0.65),
                  chunk("f-5", "doc-f5", "f5.md", 0.60)));
      candidates.add(chunk("a-fee", "doc-a", "a.md", 0.55));
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response = queryService.query("Kombifrage", null, caller, true, List.of());

      assertThat(response.getSources()).hasSize(7);
      assertThat(response.getSources())
          .filteredOn(source -> source.getFileName().equals("a.md"))
          .hasSize(1)
          .allSatisfy(source -> assertThat(source.getMatchCount()).isEqualTo(2));
      assertThat(response.getSources())
          .filteredOn(source -> source.getFileName().equals("x.md"))
          .hasSize(1)
          .allSatisfy(source -> assertThat(source.getMatchCount()).isEqualTo(1));
    }

    /**
     * Code review of #932's original PR: the two tests above give both sub-queries the identical
     * candidate set, which never exercises fusion with two genuinely different, disjoint result
     * lists. Here sub-query A carries {@code doc-x}/{@code doc-a}/five fillers, and sub-query B
     * returns an entirely disjoint set of eight documents at a much lower, unrelated score range -
     * own topic, own search vector, no shared ids. {@code ReciprocalRankFusion} scores purely by
     * rank, not by the underlying score (that comparison is exactly the #912 failure mode its own
     * Javadoc documents) - a sub-query's rank-<i>k</i> item always contributes the identical amount
     * regardless of which sub-query it came from, so A's and B's same-rank items tie and interleave
     * by rank tier (rank 1 pair, then rank 2 pair, ...) once sorted by fused score. With {@code
     * topK} = 8 that tier-by-tier cut lands after the rank-4 pair, well before sub-query A's own
     * {@code doc-a}'s fee chunk (permanently excluded from every sub-query's own top-8 window,
     * ranked 9th in sub-query A and absent from sub-query B entirely) ever has a chance to survive
     * fusion on its own - completion is exactly what recovers it here, from the pooled raw
     * candidates of <em>both</em> sub-queries.
     */
    @Test
    void multiQueryPathRecoversAFeeChunkAcrossTwoDisjointSubQueryCandidateSets() {
      when(chatMemory.get(any())).thenReturn(List.of());
      when(queryDecompositionService.decompose(eq("Kombifrage"), any(), eq(3)))
          .thenReturn(List.of("Teilfrage A", "Teilfrage B"));
      List<Document> candidatesA =
          new ArrayList<>(
              List.of(
                  chunk("x-0", "doc-x", "x.md", 0.95),
                  chunk("x-1", "doc-x", "x.md", 0.90),
                  chunk("a-intro", "doc-a", "a.md", 0.85),
                  chunk("f-1", "doc-f1", "f1.md", 0.80),
                  chunk("f-2", "doc-f2", "f2.md", 0.75),
                  chunk("f-3", "doc-f3", "f3.md", 0.70),
                  chunk("f-4", "doc-f4", "f4.md", 0.65),
                  chunk("f-5", "doc-f5", "f5.md", 0.60)));
      candidatesA.add(chunk("a-fee", "doc-a", "a.md", 0.55));
      List<Document> candidatesB =
          List.of(
              chunk("g-1", "doc-g1", "g1.md", 0.40),
              chunk("g-2", "doc-g2", "g2.md", 0.35),
              chunk("g-3", "doc-g3", "g3.md", 0.30),
              chunk("g-4", "doc-g4", "g4.md", 0.25),
              chunk("g-5", "doc-g5", "g5.md", 0.20),
              chunk("g-6", "doc-g6", "g6.md", 0.15),
              chunk("g-7", "doc-g7", "g7.md", 0.10),
              chunk("g-8", "doc-g8", "g8.md", 0.05));
      // Null-safe registration order, same reasoning as the #923 decomposition tests above.
      when(vectorStore.similaritySearch(
              argThat(
                  (SearchRequest request) ->
                      request != null && "Teilfrage A".equals(request.getQuery()))))
          .thenReturn(candidatesA);
      when(vectorStore.similaritySearch(
              argThat(
                  (SearchRequest request) ->
                      request != null && "Teilfrage B".equals(request.getQuery()))))
          .thenReturn(candidatesB);
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response = queryService.query("Kombifrage", null, caller, true, List.of());

      assertThat(response.getSources()).hasSize(7);
      assertThat(response.getSources())
          .filteredOn(source -> source.getFileName().equals("a.md"))
          .hasSize(1)
          .allSatisfy(source -> assertThat(source.getMatchCount()).isEqualTo(2));
      assertThat(response.getSources())
          .filteredOn(source -> source.getFileName().equals("x.md"))
          .hasSize(1)
          .allSatisfy(source -> assertThat(source.getMatchCount()).isEqualTo(1));
      // The rank-tiered fused cut (see this test's Javadoc) keeps only the rank-1..4 pair from
      // each sub-query - sub-query A's f2..f5 (ranks 5-8) and sub-query B's g5..g8 never make it,
      // pushed out by the interleave exactly as f5 alone was in the single-candidate-set tests
      // above.
      assertThat(response.getSources())
          .extracting(ChatSource::getFileName)
          .containsExactlyInAnyOrder("x.md", "a.md", "f1.md", "g1.md", "g2.md", "g3.md", "g4.md");
    }

    /**
     * Tier 2 (#932 scope v2) - the exact live-verification shape: eight distinct documents, each
     * contributing exactly one chunk to the topK=8 window, so tier 1 (which needs a document
     * already holding two chunks) finds no source. {@code doc-3} (standing in for {@code
     * 001_personalausweis.md}) recovers its detail chunk (ranked 9th, below the window) by evicting
     * {@code doc-7} - the lowest-ranked of the eight - via tier 2.
     */
    @Test
    void recoversADetailChunkByEvictingTheLowestRankedDocumentWhenEveryDocumentHoldsOnlyOneChunk() {
      when(chatMemory.get(any())).thenReturn(List.of());
      List<Document> candidates =
          new ArrayList<>(
              List.of(
                  chunk("d0-0", "doc-0", "d0.md", 0.95),
                  chunk("d1-0", "doc-1", "d1.md", 0.90),
                  chunk("d2-0", "doc-2", "d2.md", 0.85),
                  chunk("d3-0", "doc-3", "d3.md", 0.80),
                  chunk("d4-0", "doc-4", "d4.md", 0.75),
                  chunk("d5-0", "doc-5", "d5.md", 0.70),
                  chunk("d6-0", "doc-6", "d6.md", 0.65),
                  chunk("d7-0", "doc-7", "d7.md", 0.60)));
      // Ranked 9th - below the topK=8 window, exactly like the fee chunk in the tests above, but
      // here every one of the eight window slots is held by a single-chunk document.
      candidates.add(chunk("d3-1", "doc-3", "d3.md", 0.10));
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(candidates);
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response = queryService.query("Frage", null, caller, true, List.of());

      assertThat(response.getSources()).hasSize(7);
      assertThat(response.getSources()).extracting(ChatSource::getFileName).doesNotContain("d7.md");
      assertThat(response.getSources())
          .filteredOn(source -> source.getFileName().equals("d3.md"))
          .hasSize(1)
          .allSatisfy(source -> assertThat(source.getMatchCount()).isEqualTo(2));
    }
  }

  /**
   * #1102: {@code relevanceScore} must mean the same thing no matter which search path found the
   * chunk - unlike every other test in this class (see {@link #newQueryService}), the lexical path
   * is wired in here.
   */
  @Nested
  class RelevanceScoreAcrossSearchPaths {

    private QueryService newHybridQueryService(
        FullTextChunkSearch fullTextChunkSearch, FullTextBackfillGate backfillGate) {
      return newHybridQueryService(fullTextChunkSearch, backfillGate, 1);
    }

    private QueryService newHybridQueryService(
        FullTextChunkSearch fullTextChunkSearch,
        FullTextBackfillGate backfillGate,
        int maxChunksPerDocument) {
      RetrievalPipeline pipeline =
          new QueryConfiguration()
              .retrievalPipeline(
                  new SearchScopeStage(),
                  new MetadataFilterStage(mock(DocumentTypeVocabularyRepository.class)),
                  new SubQueryDecompositionStage(queryDecompositionService),
                  new VectorSearchStage(vectorStore),
                  new FullTextSearchStage(fullTextChunkSearch, backfillGate),
                  new MmrSelectionStage(chunkEmbeddingLookup),
                  new RankFusionStage(),
                  new RerankStage(disabledRerankRole()),
                  new DocumentCompletionStage(),
                  RetrievalPipelineProperties.allStagesEnabled());
      return new QueryService(
          pipeline,
          answerGenerationService,
          chatMemory,
          new CitationParser(),
          new CitationValidator(),
          documentRepository,
          libraryAccessService,
          permissionHistoryService,
          chatService,
          new QueryMetrics(new SimpleMeterRegistry()),
          new QueryProperties(8, 25, 1.0, 0.3, 1.0, true, 3, maxChunksPerDocument, true, 50),
          knowledgeLibraryRepository,
          disabledRerankRole(),
          documentMetadataService,
          mock(DocumentTypeVocabularyRepository.class));
    }

    /**
     * A lone vector hit (cosine 0.8) and a lone lexical hit (ts_rank 0.09) tie in the fusion, so
     * the fused order keeps the search-stage order - and the exposed scores follow that order (1.0,
     * 0.5) instead of the incomparable raw scores, which would have dropped the lexical hit to the
     * bottom of the evidence list.
     */
    @Test
    void aLexicalOnlyChunkKeepsTheRelevanceScoreOfItsFusedRank() {
      when(chatMemory.get(any())).thenReturn(List.of());
      FullTextChunkSearch fullTextChunkSearch = mock(FullTextChunkSearch.class);
      FullTextBackfillGate backfillGate = mock(FullTextBackfillGate.class);
      when(backfillGate.searchableLibraries(Set.of(readableLibraryId)))
          .thenReturn(Set.of(readableLibraryId));
      var vectorChunk =
          Document.builder()
              .text("vector hit")
              .metadata(Map.of("file_name", "vector.md", "document_id", "doc-vector"))
              .score(0.8)
              .build();
      var lexicalChunk =
          Document.builder()
              .text("literal term")
              .metadata(Map.of("file_name", "lexical.md", "document_id", "doc-lexical"))
              .score(0.09)
              .build();
      when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(vectorChunk));
      when(fullTextChunkSearch.search(any(), any(), any(), anyInt()))
          .thenReturn(List.of(lexicalChunk));
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response =
          newHybridQueryService(fullTextChunkSearch, backfillGate)
              .query("Frage", null, caller, true, List.of());

      assertThat(response.getSources())
          .extracting(ChatSource::getFileName, ChatSource::getRelevanceScore)
          .containsExactly(tuple("vector.md", 1.0), tuple("lexical.md", 0.5));
    }

    /**
     * The rank is a source's own position, not its best chunk's: a document contributing two of the
     * three selected chunks occupies one row, and the next document is rank 2 - never rank 3, which
     * would label a two-row list "Rang 1" and "Rang 3" (#1102).
     */
    @Test
    void ranksSourcesByTheirOwnPositionWhenOneDocumentContributesSeveralChunks() {
      when(chatMemory.get(any())).thenReturn(List.of());
      FullTextChunkSearch fullTextChunkSearch = mock(FullTextChunkSearch.class);
      FullTextBackfillGate backfillGate = mock(FullTextBackfillGate.class);
      when(backfillGate.searchableLibraries(Set.of(readableLibraryId)))
          .thenReturn(Set.of(readableLibraryId));
      var firstChunkOfA = chunkOf("a.md", "doc-a", "A, erster Abschnitt", 0.9);
      var secondChunkOfA = chunkOf("a.md", "doc-a", "A, zweiter Abschnitt", 0.85);
      var chunkOfB = chunkOf("b.md", "doc-b", "B, einziger Abschnitt", 0.8);
      when(vectorStore.similaritySearch(any(SearchRequest.class)))
          .thenReturn(List.of(firstChunkOfA, secondChunkOfA, chunkOfB));
      when(fullTextChunkSearch.search(any(), any(), any(), anyInt())).thenReturn(List.of());
      var chatResponse = new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort"))));
      when(answerGenerationService.generateAnswer(any(), any(), any())).thenReturn(chatResponse);

      QueryResult response =
          newHybridQueryService(fullTextChunkSearch, backfillGate, 2)
              .query("Frage", null, caller, true, List.of());

      assertThat(response.getSources())
          .extracting(ChatSource::getFileName, ChatSource::getRelevanceScore)
          .containsExactly(tuple("a.md", 1.0), tuple("b.md", 0.5));
    }

    private Document chunkOf(String fileName, String documentId, String text, double score) {
      return Document.builder()
          .text(text)
          .metadata(Map.of("file_name", fileName, "document_id", documentId))
          .score(score)
          .build();
    }
  }

  private static ChatSource sourceReference(
      String fileName, double relevanceScore, int matchCount, Instant indexedAt, boolean cited) {
    return sourceReference(fileName, relevanceScore, matchCount, indexedAt, cited, null);
  }

  private static ChatSource sourceReference(
      String fileName,
      double relevanceScore,
      int matchCount,
      Instant indexedAt,
      boolean cited,
      String sourceEntryUrl) {
    ChatSource sourceReference = new ChatSource(fileName, relevanceScore, matchCount, cited);
    sourceReference.setIndexedAt(indexedAt);
    sourceReference.setSourceEntryUrl(sourceEntryUrl);
    return sourceReference;
  }

  private Usage createUsage(int promptTokens, int completionTokens) {
    return new Usage() {
      @Override
      public Integer getPromptTokens() {
        return promptTokens;
      }

      @Override
      public Integer getCompletionTokens() {
        return completionTokens;
      }

      @Override
      public Object getNativeUsage() {
        return null;
      }
    };
  }
}
