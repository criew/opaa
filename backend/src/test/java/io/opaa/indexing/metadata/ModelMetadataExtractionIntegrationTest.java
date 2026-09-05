package io.opaa.indexing.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataOrigin;
import io.opaa.api.types.SystemRole;
import io.opaa.common.ValidationException;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.llm.ActiveChatModelDescription;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Step 2 of the extraction order end to end (#1073): the switches decide whether a model is called
 * at all, the threshold and the vocabulary decide what is stored, a failure never blocks the
 * ingest, and a freies Schlagwort reaches the full-text index without ever becoming filterable or
 * appearing in a Beleg.
 */
@OpaaIndexingIntegrationTest
class ModelMetadataExtractionIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("model-metadata-extraction");

  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private DocumentMetadataValueRepository valueRepository;
  @Autowired private DocumentKeywordRepository keywordRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private CitationMetadataReader citationMetadataReader;
  @Autowired private MetadataFilterValidator filterValidator;
  @Autowired private ModelExtractionCounters counters;
  @Autowired private MetadataBackfillService backfillService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;
  @Autowired private DocumentTypeVocabularyRepository vocabularyRepository;
  @Autowired private LibraryMetadataFieldRepository libraryFieldRepository;
  @Autowired private LibraryMetadataFieldValueRepository libraryFieldValueRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private KnowledgeLibrary library;

  @BeforeEach
  void setUp() throws IOException {
    Files.createDirectories(classTempDir);
    removeOwnRows();
    UUID userId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', 'model-extraction-it@example.com',"
            + " 'Model Extraction IT User', now(), ?, ?)",
        userId,
        "model-extraction-it-" + userId,
        SystemRole.SYSTEM_ADMIN.name(),
        Organization.DEFAULT_ID);
    library =
        libraryRepository.save(
            KnowledgeLibrary.ownedByUser(
                Organization.DEFAULT_ID,
                "Modellextraktion",
                null,
                userId,
                LibraryVisibility.PRIVATE,
                false,
                DocumentSourceType.FILESYSTEM,
                classTempDir.toString(),
                null,
                null,
                null,
                false));
  }

  @Test
  void bothSwitchesAreOffByDefaultAndNoModelIsCalled() throws IOException {
    ingest(file("unterlage-ohne-modell.txt"));

    verify(chatModel, never()).call(any(Prompt.class));
    Document document = onlyDocument();
    assertThat(documentMetadataService.coreMetadataFor(document.getId()).documentTypeCode())
        .isNull();
    assertThat(counters.statsFor(library.getId()).calls()).isZero();
  }

  @Test
  void anAcceptedValueIsStoredAsDerivedWithItsConfidenceAndModel() throws IOException {
    switchOn(true, false);
    answerWith(
        """
        {"fields": {"document_type": {"value": "SATZUNG_ORDNUNG", "confidence": 0.93}}}
        """);

    ingest(file("unterlage-angenommen.txt"));

    Document document = onlyDocument();
    DocumentMetadataValue value =
        valueRepository
            .findByDocumentIdAndFieldKey(document.getId(), CoreMetadataField.DOCUMENT_TYPE.key())
            .orElseThrow();
    assertThat(value.getOrigin()).isEqualTo(MetadataOrigin.DERIVED);
    assertThat(value.getVocabularyCode()).isEqualTo("SATZUNG_ORDNUNG");
    assertThat(value.getConfidence()).isEqualTo(0.93);
    assertThat(value.getModelId()).isEqualTo("test-model");
    assertThat(value.getExtractionVersion()).isEqualTo(CoreMetadataExtractor.EXTRACTION_VERSION);
    ModelExtractionStats stats = counters.statsFor(library.getId());
    assertThat(stats.calls()).isEqualTo(1);
    assertThat(stats.acceptedValues()).isEqualTo(1);
    assertThat(stats.lastCallAt()).isNotNull();
  }

  @Test
  void aValueBelowTheThresholdLeavesTheFieldEmptyAndIsOnlyRecorded() throws IOException {
    switchOn(true, false);
    answerWith(
        """
        {"fields": {"document_type": {"value": "SATZUNG_ORDNUNG", "confidence": 0.79}}}
        """);

    ingest(file("unterlage-unsicher.txt"));

    Document document = onlyDocument();
    assertThat(
            valueRepository.findByDocumentIdAndFieldKey(
                document.getId(), CoreMetadataField.DOCUMENT_TYPE.key()))
        .isEmpty();
    ModelExtractionStats stats = counters.statsFor(library.getId());
    assertThat(stats.acceptedValues()).isZero();
    assertThat(stats.rejectedBelowThreshold()).isEqualTo(1);
    // The discarded value keeps its confidence, which is what makes the threshold calibratable.
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT field_key, proposed_value, confidence, reason FROM"
                    + " metadata_model_rejections WHERE library_id = ?",
                library.getId()))
        .singleElement()
        .satisfies(
            row -> {
              assertThat(row.get("field_key")).isEqualTo("document_type");
              assertThat(row.get("proposed_value")).isEqualTo("SATZUNG_ORDNUNG");
              assertThat((Double) row.get("confidence")).isEqualTo(0.79);
              assertThat(row.get("reason")).isEqualTo("BELOW_THRESHOLD");
            });
  }

  @Test
  void aValueOutsideTheVocabularyIsDiscardedNoMatterHowConfidentTheModelIs() throws IOException {
    switchOn(true, false);
    answerWith(
        """
        {"fields": {"document_type": {"value": "STELLPLATZSATZUNG", "confidence": 1.0}}}
        """);

    ingest(file("unterlage-erfunden.txt"));

    assertThat(
            valueRepository.findByDocumentIdAndFieldKey(
                onlyDocument().getId(), CoreMetadataField.DOCUMENT_TYPE.key()))
        .isEmpty();
    ModelExtractionStats stats = counters.statsFor(library.getId());
    assertThat(stats.rejectedOutsideVocabulary()).isEqualTo(1);
    assertThat(stats.rejectedBelowThreshold()).isZero();
  }

  @Test
  void aFieldTheDeterministicStepFilledIsNeverOfferedToTheModel() throws IOException {
    switchOn(true, false);
    answerWith(
        """
        {"fields": {"document_type": {"value": "VERMERK", "confidence": 1.0}}}
        """);

    // The file name carries the Dokumentart, so step 1 fills it and step 2 must not be asked.
    ingest(file("2026-03-12_Dienstanweisung_IT-Nutzung.txt"));

    Document document = onlyDocument();
    DocumentMetadataValue value =
        valueRepository
            .findByDocumentIdAndFieldKey(document.getId(), CoreMetadataField.DOCUMENT_TYPE.key())
            .orElseThrow();
    assertThat(value.getOrigin()).isEqualTo(MetadataOrigin.DETERMINISTIC);
    assertThat(value.getVocabularyCode()).isEqualTo("DIENSTANWEISUNG");
    // With nothing unscharf left to decide and keywords off, no call is made at all - the cheapest
    // possible outcome, and the one that keeps a deterministic value out of the model's reach.
    verify(chatModel, never()).call(any(Prompt.class));
    assertThat(counters.statsFor(library.getId()).calls()).isZero();
  }

  @Test
  void aModelFailureNeverBlocksTheIngest() throws IOException {
    switchOn(true, false);
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient(any(Duration.class)))
        .thenReturn(ChatClient.builder(chatModel).build());
    when(activeChatModelResolver.resolveDescription())
        .thenReturn(new ActiveChatModelDescription("http://localhost:11434/v1", "test-model"));
    when(chatModel.call(any(Prompt.class))).thenThrow(new IllegalStateException("model down"));

    assertThat(ingest(file("unterlage-ausfall.txt"))).isEqualTo(FileProcessingResult.PROCESSED);

    Document document = onlyDocument();
    assertThat(document.getChunkCount()).isPositive();
    assertThat(
            valueRepository.findByDocumentIdAndFieldKey(
                document.getId(), CoreMetadataField.DOCUMENT_TYPE.key()))
        .isEmpty();
    assertThat(counters.statsFor(library.getId()).failures()).isEqualTo(1);
  }

  @Test
  void keywordsReachTheFullTextIndexButNeitherAFilterNorABeleg() throws IOException {
    switchOn(false, true);
    answerWith(
        """
        {"keywords": ["Fahrradstellplatz", "Rathausvorplatz"]}
        """);

    ingest(file("unterlage-schlagworte.txt"));

    Document document = onlyDocument();
    assertThat(keywordRepository.findByDocumentIdOrderByKeywordAsc(document.getId()))
        .extracting(DocumentKeyword::getKeyword)
        .containsExactly("Fahrradstellplatz", "Rathausvorplatz");
    // Found through the lexical path although the word appears nowhere in the document text: the
    // keywords ride in the Kontextpräfix, which is what both indexes are built from.
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM chunk_full_text WHERE document_id = ? AND content_tsv @@"
                    + " plainto_tsquery('german', 'Fahrradstellplatz')",
                Long.class,
                document.getId()))
        .isPositive();
    // No chunk metadata key carries them, so no filter can ever name them.
    assertThat(
            jdbcTemplate.queryForList(
                "SELECT metadata::text AS metadata FROM vector_store WHERE metadata->>'document_id'"
                    + " = ?",
                document.getId().toString()))
        .allSatisfy(
            row -> assertThat((String) row.get("metadata")).doesNotContain("Fahrradstellplatz"));
    // Not part of the Beleg either.
    assertThat(citationMetadataReader.forDocuments(List.of(document)).get(document.getId()))
        .satisfies(
            values ->
                assertThat(values == null ? List.<CitationFieldValue>of() : values)
                    .noneMatch(value -> "Fahrradstellplatz".equals(value.value())));
    // And naming them in a filter is a caller error, not an empty result.
    assertThatThrownBy(
            () ->
                filterValidator.validate(
                    MetadataFilter.NONE.withLibraryFields(
                        List.of(
                            LibraryFieldCondition.ofCodes(
                                library.getId(), "keywords", List.of("Fahrradstellplatz")))),
                    Set.of(library.getId())))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void theBestandslaufRunsTheModelStepForADocumentIngestedWithTheSwitchOff() throws IOException {
    ingest(file("unterlage-nachlauf.txt"));
    Document document = onlyDocument();
    assertThat(document.getModelExtractionVersion()).isNull();

    switchOn(true, true);
    answerWith(
        """
        {"fields": {"document_type": {"value": "VERMERK", "confidence": 0.95}},
         "keywords": ["Radverkehr"]}
        """);
    MetadataBackfillResult result =
        backfillService.backfillBatch(Organization.DEFAULT_ID, library.getId(), 10);

    assertThat(result.processedDocuments()).isEqualTo(1);
    assertThat(
            valueRepository
                .findByDocumentIdAndFieldKey(
                    document.getId(), CoreMetadataField.DOCUMENT_TYPE.key())
                .orElseThrow()
                .getOrigin())
        .isEqualTo(MetadataOrigin.DERIVED);
    assertThat(keywordRepository.findByDocumentIdOrderByKeywordAsc(document.getId()))
        .extracting(DocumentKeyword::getKeyword)
        .containsExactly("Radverkehr");
    // A keyword found here moves the Kontextpräfix, so the document goes to that Nachlauf - which
    // is the one run that pays for re-embedding; the Bestandslauf never does.
    assertThat(documentRepository.findById(document.getId()).orElseThrow().getContextPrefixStamp())
        .isNull();
    assertThat(
            documentRepository.findById(document.getId()).orElseThrow().getModelExtractionVersion())
        .isEqualTo(ModelMetadataExtractor.EXTRACTION_VERSION);

    // Drained: a second call finds nothing left, so the same document is not paid for twice.
    assertThat(
            backfillService
                .backfillBatch(Organization.DEFAULT_ID, library.getId(), 10)
                .processedDocuments())
        .isZero();
  }

  @Test
  void atMostFiveKeywordsAndNoneLongerThanFortyCharacters() throws IOException {
    switchOn(false, true);
    answerWith(
        """
        {"keywords": ["eins", "zwei", "drei", "vier", "%s", "fuenf", "sechs"]}
        """
            .formatted("z".repeat(41)));

    ingest(file("unterlage-viele-schlagworte.txt"));

    assertThat(keywordRepository.findByDocumentIdOrderByKeywordAsc(onlyDocument().getId()))
        .extracting(DocumentKeyword::getKeyword)
        .containsExactlyInAnyOrder("eins", "zwei", "drei", "vier", "fuenf");
  }

  @Test
  void aSwitchTurnedOnLaterStillReachesTheAltbestandOfTheOtherOne() throws IOException {
    // Regression guard for #1073 review, finding 2: one drain marker for both capabilities would
    // let the keyword runs stamp every document, and the Dokumentart of this Altbestand could then
    // never be filled.
    switchOn(false, true);
    answerWith(
        """
        {"keywords": ["Radverkehr"]}
        """);
    ingest(file("unterlage-nur-schlagworte.txt"));
    Document document = onlyDocument();
    assertThat(document.getKeywordExtractionVersion())
        .isEqualTo(ModelMetadataExtractor.EXTRACTION_VERSION);
    assertThat(document.getModelExtractionVersion()).isNull();

    switchOn(true, true);
    answerWith(
        """
        {"fields": {"document_type": {"value": "VERMERK", "confidence": 0.95}},
         "keywords": ["Radverkehr"]}
        """);

    assertThat(
            backfillService
                .backfillBatch(Organization.DEFAULT_ID, library.getId(), 10)
                .processedDocuments())
        .isEqualTo(1);
    assertThat(
            valueRepository
                .findByDocumentIdAndFieldKey(
                    document.getId(), CoreMetadataField.DOCUMENT_TYPE.key())
                .orElseThrow()
                .getOrigin())
        .isEqualTo(MetadataOrigin.DERIVED);
  }

  @Test
  void theZustandsuebersichtCountsExactlyWhatABestandslaufCallWouldSelect() throws IOException {
    // Regression guard for #1073 review, finding 3: the only start button of the page hangs on this
    // count, so a library whose Altbestand is model-pending must not read as "0 ausstehend".
    ingest(file("unterlage-zaehlung.txt"));
    assertThat(pendingDocumentsOfLibrary()).isZero();

    switchOn(true, false);

    assertThat(pendingDocumentsOfLibrary())
        .as("the count follows the switch, exactly like the selection does")
        .isEqualTo(1);
  }

  @Test
  void aCallOverItsTimeLimitLeavesTheFieldEmptyAndIndexesTheDocumentAnyway() throws IOException {
    // Regression guard for #1073 review, finding 4: the timeout bounds the ingest's waiting time.
    // Ingested with both switches off, so the field is still empty when the model is asked.
    ingest(file("unterlage-langsam.txt"));
    Document document = onlyDocument();
    switchOn(true, false);
    answerWith(
        """
        {"fields": {"document_type": {"value": "VERMERK", "confidence": 1.0}}}
        """);
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(2000);
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage("{\"fields\": {}}"))));
            });
    ModelMetadataExtractor impatient = extractorWithTimeout(Duration.ofMillis(150));

    long startedAt = System.nanoTime();
    ModelExtractionOutcome outcome =
        impatient.extract(document, library, "Titel", "Text des Dokuments");
    Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

    assertThat(waited).isLessThan(Duration.ofSeconds(2));
    assertThat(outcome.keywords()).isEmpty();
    assertThat(
            valueRepository.findByDocumentIdAndFieldKey(
                document.getId(), CoreMetadataField.DOCUMENT_TYPE.key()))
        .isEmpty();
    assertThat(documentRepository.findById(document.getId()).orElseThrow().getStatus())
        .isEqualTo(DocumentStatus.INDEXED);
    assertThat(counters.statsFor(library.getId()).failures()).isEqualTo(1);
  }

  @Test
  void aFullPoolSkipsTheCallInsteadOfRunningItInline() throws Exception {
    // Regression guard for the re-review of #1073: with a caller-runs policy the call ran on the
    // ingest thread, where supplyAsync returns only after the model answered - the limit did not
    // apply at all. A full pool therefore skips the call, counts it and moves on.
    // Ingested with both switches off, so the field is still empty when the model would be asked.
    ingest(file("unterlage-ausgelastet.txt"));
    Document document = onlyDocument();
    switchOn(true, false);
    answerWith(
        """
        {"fields": {"document_type": {"value": "VERMERK", "confidence": 0.95}}}
        """);
    ThreadPoolTaskExecutor executor = singleThreadExecutor();
    CountDownLatch occupied = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    executor.execute(
        () -> {
          occupied.countDown();
          try {
            release.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();

    try {
      long startedAt = System.nanoTime();
      ModelExtractionOutcome outcome =
          extractorWith(executor, Duration.ofSeconds(30))
              .extract(document, library, "Titel", "Text des Dokuments");
      Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

      assertThat(waited)
          .as("the ingest waits neither for the occupying task nor for a model")
          .isLessThan(Duration.ofSeconds(3));
      verify(chatModel, never()).call(any(Prompt.class));
      assertThat(outcome.chunkMetadata()).isNull();
      assertThat(
              valueRepository.findByDocumentIdAndFieldKey(
                  document.getId(), CoreMetadataField.DOCUMENT_TYPE.key()))
          .isEmpty();
      ModelExtractionStats stats = counters.statsFor(library.getId());
      assertThat(stats.rejectedPoolFull()).isEqualTo(1);
      assertThat(stats.failures()).as("a full pool is no model failure").isZero();
      Document afterwards = documentRepository.findById(document.getId()).orElseThrow();
      assertThat(afterwards.getStatus()).isEqualTo(DocumentStatus.INDEXED);
      // Never asked means never marked: an unasked document must stay on the Bestandslauf's
      // selection, or a run under load would silently lose part of its Altbestand.
      assertThat(afterwards.getModelExtractionVersion()).isNull();
      assertThat(
              backfillService
                  .backfillBatch(Organization.DEFAULT_ID, library.getId(), 10)
                  .processedDocuments())
          .as("the next Bestandslauf call picks it up again")
          .isEqualTo(1);
    } finally {
      release.countDown();
    }
  }

  /** The extractor with a short limit on a single-threaded executor, as in production. */
  private ModelMetadataExtractor extractorWithTimeout(Duration timeout) {
    return extractorWith(singleThreadExecutor(), timeout);
  }

  private ThreadPoolTaskExecutor singleThreadExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.setQueueCapacity(0);
    // Mirrors the production bean: abort, never caller-runs - an inline call ignores the limit.
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }

  private ModelMetadataExtractor extractorWith(Executor executor, Duration timeout) {
    return new ModelMetadataExtractor(
        activeChatModelResolver,
        documentMetadataService,
        valueRepository,
        vocabularyRepository,
        libraryFieldRepository,
        libraryFieldValueRepository,
        keywordRepository,
        counters,
        documentRepository,
        executor,
        timeout);
  }

  private long pendingDocumentsOfLibrary() {
    return backfillService
        .progressForLibraries(List.of(library.getId()))
        .get(library.getId())
        .pendingDocuments();
  }

  @AfterEach
  void tearDown() {
    // Cleaned up afterwards as well, not only before: every class carrying this signature shares
    // one database, and a leftover library of this class blocks another class's
    // libraryRepository.deleteAll() through the RESTRICT of documents.library_id.
    removeOwnRows();
  }

  /** One DELETE per table, in dependency order - a self-referencing parent chain and all. */
  private void removeOwnRows() {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    // A single statement rather than deleteAll(): PostgreSQL checks the self-reference of
    // documents.parent_document_id only after it, so a parent and its attachment go together.
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update(
        "DELETE FROM knowledge_libraries WHERE owner_user_id IN (SELECT id FROM users WHERE"
            + " email = 'model-extraction-it@example.com')");
    jdbcTemplate.update("DELETE FROM users WHERE email = 'model-extraction-it@example.com'");
  }

  private void switchOn(boolean modelExtraction, boolean keywords) {
    library.setModelExtractionSwitches(modelExtraction, keywords);
    library = libraryRepository.save(library);
  }

  private void answerWith(String json) {
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient(any(Duration.class)))
        .thenReturn(ChatClient.builder(chatModel).build());
    when(activeChatModelResolver.resolveDescription())
        .thenReturn(new ActiveChatModelDescription("http://localhost:11434/v1", "test-model"));
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(json)))));
  }

  private FileProcessingResult ingest(Path file) throws IOException {
    return fileProcessingService.ingest(DocumentIngest.localFile(library, file).build(), null);
  }

  private Document onlyDocument() {
    List<Document> documents = documentRepository.findByLibraryId(library.getId());
    assertThat(documents).hasSize(1);
    return documents.getFirst();
  }

  private Path file(String name) throws IOException {
    Path file = classTempDir.resolve(name);
    Files.writeString(
        file,
        "Hinweise zur Nutzung der Abstellanlagen am Rathaus. Die Nutzung ist unentgeltlich und"
            + " richtet sich nach den allgemeinen Regelungen der Stadt.");
    return file;
  }
}
