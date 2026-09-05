package io.opaa.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DatePrecision;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.MetadataFilterMatch;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.chat.ChatSource;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentIngest;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.FileProcessingResult;
import io.opaa.indexing.FileProcessingService;
import io.opaa.indexing.VectorChunkStore;
import io.opaa.indexing.metadata.CoreMetadata;
import io.opaa.indexing.metadata.DocumentMetadataCorrectionService;
import io.opaa.indexing.metadata.DocumentMetadataService;
import io.opaa.indexing.metadata.DocumentTypeVocabularyEntry;
import io.opaa.indexing.metadata.DocumentTypeVocabularyRepository;
import io.opaa.indexing.metadata.FormatFieldCondition;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.indexing.metadata.MetadataValueInput;
import io.opaa.library.AssetGrant;
import io.opaa.library.AssetGrantRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.library.LibraryAccessService;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIndexingIntegrationTest;
import io.opaa.test.OpaaIndexingTestDirectory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The core-field filter in both search paths against a real PostgreSQL (#1070, metadata-schema.md
 * Wirkstelle 1): the values come from the production ingest, the vector filter runs inside {@code
 * similaritySearch}, the lexical filter inside the full-text SQL - nothing the assertions are about
 * is mocked. The fake embedding model embeds every text identically, so the vector path returns
 * every chunk the filter lets through; what differs between runs is the filter alone.
 */
@OpaaIndexingIntegrationTest
class MetadataFilterSearchIntegrationTest {

  private static final Path classTempDir =
      OpaaIndexingTestDirectory.subdirectory("metadata-filter-search");

  @Autowired private RetrievalPipeline retrievalPipeline;
  @Autowired private QueryProperties queryProperties;
  @Autowired private QueryService queryService;
  @Autowired private FullTextChunkSearch fullTextChunkSearch;
  @Autowired private VectorStore vectorStore;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private FileProcessingService fileProcessingService;
  @Autowired private DocumentMetadataService documentMetadataService;
  @Autowired private DocumentMetadataCorrectionService correctionService;
  @Autowired private DocumentTypeVocabularyRepository vocabularyRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private LibraryAccessService accessService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;

  private KnowledgeLibrary library;
  private KnowledgeLibrary forbiddenLibrary;
  private CurrentUser owner;
  private CurrentUser reader;

  @BeforeEach
  void setUp() throws IOException {
    jdbcTemplate.execute("TRUNCATE TABLE vector_store, chunk_full_text");
    jdbcTemplate.update("DELETE FROM documents");
    jdbcTemplate.update("DELETE FROM asset_grants");
    // Users and libraries of earlier runs stay: the audit rows a manual correction writes
    // reference them. Every run creates its own, by id.
    owner = user("owner", SystemRole.SYSTEM_ADMIN);
    reader = user("reader", SystemRole.USER);
    library = library("Filter-" + UUID.randomUUID(), classTempDir.resolve("readable"));
    forbiddenLibrary =
        library("Filter-fremd-" + UUID.randomUUID(), classTempDir.resolve("forbidden"));
    grant(library, reader, AssetRole.VIEWER);
    Files.createDirectories(classTempDir.resolve("readable"));
    Files.createDirectories(classTempDir.resolve("forbidden"));
    deletePdfsIn(classTempDir.resolve("readable"));
    deletePdfsIn(classTempDir.resolve("forbidden"));
  }

  /**
   * The acceptance criterion in one run: both paths carry the identical condition inside their
   * query - the same documents come out of the vector search and the full-text search, a document
   * of the wrong Dokumentart and date is out, one without a value is kept as "ohne Angabe", and the
   * forbidden library never contributes.
   */
  @Test
  void bothSearchPathsApplyTheSameFilterInsideTheirQueries() throws IOException {
    Document vermerk2024 = indexed(library, "2024-03-12_Vermerk_Nutzung.pdf");
    Document dienstanweisung2023 = indexed(library, "2023-06-01_Dienstanweisung_Nutzung.pdf");
    Document untypedYear2024 = indexed(library, "Unterlage_2024.pdf");
    Document vermerkUndated = indexed(library, "Vermerk_Nutzung.pdf");
    indexed(forbiddenLibrary, "2024-05-01_Vermerk_Nutzung.pdf");
    assertThat(core(untypedYear2024).documentTypeCode()).isNull();
    assertThat(core(untypedYear2024).documentDatePrecision()).isEqualTo(DatePrecision.YEAR);
    assertThat(core(vermerkUndated).documentDate()).isNull();

    MetadataFilter filter =
        new MetadataFilter(Set.of("VERMERK"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));
    RetrievalPipelineResult result = run(filter, Set.of(library.getId()));

    Set<String> expected =
        Set.of(
            vermerk2024.getId().toString(),
            untypedYear2024.getId().toString(),
            vermerkUndated.getId().toString());
    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH)).isEqualTo(expected);
    assertThat(documentKeys(result, RetrievalStageName.FULL_TEXT_SEARCH)).isEqualTo(expected);
    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH))
        .doesNotContain(dienstanweisung2023.getId().toString());
    // The protocol names the filter and how many candidates each path kept without a value.
    assertThat(stage(result, RetrievalStageName.METADATA_FILTER).notes())
        .contains("metadata filter: document type in [VERMERK]");
    assertThat(stage(result, RetrievalStageName.VECTOR_SEARCH).notes())
        .contains(RetrievalNote.METADATA_FILTER_NO_VALUE_CANDIDATES.format(2, 3));
    assertThat(stage(result, RetrievalStageName.FULL_TEXT_SEARCH).notes())
        .contains(RetrievalNote.METADATA_FILTER_NO_VALUE_CANDIDATES.format(2, 3));
  }

  /**
   * Precision: a YEAR value covers its whole year and a MONTH value its whole month, in both paths
   * - the window 2024-06-15..2024-08-31 keeps "2024" and "06/2024", drops "12.03.2024" and
   * "05/2024".
   */
  @Test
  void aValueCountsForTheWholeSpanItsPrecisionLeavesOpenInBothPaths() throws IOException {
    Document day = indexed(library, "2024-03-12_Vermerk_Nutzung.pdf");
    Document year = indexed(library, "Vermerk_Nutzung_2024.pdf");
    Document june = indexed(library, "Vermerk_Nutzung_2024-06.pdf");
    Document may = indexed(library, "Vermerk_Nutzung_2024-05.pdf");
    assertThat(core(year).documentDatePrecision()).isEqualTo(DatePrecision.YEAR);
    assertThat(core(june).documentDatePrecision()).isEqualTo(DatePrecision.MONTH);

    RetrievalPipelineResult result =
        run(
            MetadataFilter.ofDateWindow(LocalDate.of(2024, 6, 15), LocalDate.of(2024, 8, 31)),
            Set.of(library.getId()));

    Set<String> expected = Set.of(year.getId().toString(), june.getId().toString());
    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH)).isEqualTo(expected);
    assertThat(documentKeys(result, RetrievalStageName.FULL_TEXT_SEARCH)).isEqualTo(expected);
    assertThat(expected)
        .doesNotContain(day.getId().toString())
        .doesNotContain(may.getId().toString());
  }

  /**
   * "Kein Wert ermittelbar" behaves like a Leerwert for the filter: the document is found in both
   * paths, and the answer's source is marked as "ohne Angabe" rather than as a match.
   */
  @Test
  void aDocumentMarkedNotDeterminableIsKeptAndReportedWithoutValue() throws IOException {
    Document marked = indexed(library, "2024-03-12_Dienstanweisung_Nutzung.pdf");
    Document matching = indexed(library, "2024-04-01_Vermerk_Nutzung.pdf");
    correctionService.setValue(
        library.getId(),
        marked.getId(),
        "document_type",
        MetadataValueInput.notDeterminable(),
        owner);

    MetadataFilter filter = MetadataFilter.ofDocumentTypes(List.of("VERMERK"));
    RetrievalPipelineResult result = run(filter, Set.of(library.getId()));
    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH))
        .containsExactlyInAnyOrder(marked.getId().toString(), matching.getId().toString());
    assertThat(documentKeys(result, RetrievalStageName.FULL_TEXT_SEARCH))
        .containsExactlyInAnyOrder(marked.getId().toString(), matching.getId().toString());

    // Only now stub the answer model: with it in place the decomposition takes the stubbed reply
    // as its sub-query, which the lexical path above would not find.
    stubAnswer();
    List<ChatSource> sources =
        queryService.query("Nutzung", null, reader, true, List.of(), filter).getSources();
    assertThat(sources)
        .extracting(ChatSource::getDocumentId, ChatSource::getMetadataFilterMatch)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(marked.getId(), MetadataFilterMatch.NO_VALUE),
            org.assertj.core.groups.Tuple.tuple(matching.getId(), MetadataFilterMatch.MATCHED));
    // Without a filter no source claims a match state at all.
    assertThat(queryService.query("Nutzung", null, reader, true, List.of()).getSources())
        .extracting(ChatSource::getMetadataFilterMatch)
        .containsOnlyNulls();
  }

  /**
   * The filter is subordinate to the permission filter: with no filter, an empty one, and one that
   * names a Dokumentart only the forbidden library carries, no chunk of that library reaches either
   * path - the readable set is the ceiling under every filter.
   */
  @Test
  void noMetadataFilterEverWidensTheSearchBeyondTheReadableLibraries() throws IOException {
    Document readable = indexed(library, "2024-03-12_Vermerk_Nutzung.pdf");
    Document forbidden = indexed(forbiddenLibrary, "2024-03-12_Protokoll_Nutzung.pdf");
    assertThat(core(forbidden).documentTypeCode()).isEqualTo("PROTOKOLL");

    for (MetadataFilter filter :
        List.of(
            MetadataFilter.NONE,
            new MetadataFilter(Set.of(), null, null),
            MetadataFilter.ofDocumentTypes(List.of("PROTOKOLL")),
            MetadataFilter.ofDocumentTypes(List.of("KEIN_CODE_DES_VOKABULARS")))) {
      RetrievalPipelineResult result = run(filter, Set.of(library.getId()));
      for (RetrievalStageName path :
          List.of(RetrievalStageName.VECTOR_SEARCH, RetrievalStageName.FULL_TEXT_SEARCH)) {
        assertThat(documentKeys(result, path))
            .as("filter %s, path %s", filter, path)
            .doesNotContain(forbidden.getId().toString())
            .isSubsetOf(Set.of(readable.getId().toString()));
      }
    }
    // A filter on a Dokumentart the readable library does not carry matches nothing there - it
    // does not reach for the forbidden library's PROTOKOLL either.
    RetrievalPipelineResult protokoll =
        run(MetadataFilter.ofDocumentTypes(List.of("PROTOKOLL")), Set.of(library.getId()));
    assertThat(documentKeys(protokoll, RetrievalStageName.VECTOR_SEARCH)).isEmpty();
    assertThat(documentKeys(protokoll, RetrievalStageName.FULL_TEXT_SEARCH)).isEmpty();
  }

  /**
   * The permission filter must stay the outer operand of the whole metadata condition, not of its
   * first OR-branch only: a date window is an OR over the three precisions plus the Leerwert
   * branch, and an unbracketed AND would bind the permission filter to the DAY branch alone -
   * letting MONTH/YEAR-dated and undated chunks of a forbidden library through the vector path.
   * Both paths must drop them.
   */
  @Test
  void aDateWindowNeverLetsForbiddenChunksThroughViaTheOtherPrecisionBranches() throws IOException {
    Document readable = indexed(library, "2024-03-12_Vermerk_Nutzung.pdf");
    Document forbiddenYear = indexed(forbiddenLibrary, "Vermerk_Nutzung_2024.pdf");
    Document forbiddenMonth = indexed(forbiddenLibrary, "Vermerk_Nutzung_2024-03.pdf");
    Document forbiddenUndated = indexed(forbiddenLibrary, "Vermerk_Nutzung.pdf");
    assertThat(core(forbiddenYear).documentDatePrecision()).isEqualTo(DatePrecision.YEAR);
    assertThat(core(forbiddenMonth).documentDatePrecision()).isEqualTo(DatePrecision.MONTH);
    assertThat(core(forbiddenUndated).documentDate()).isNull();

    for (MetadataFilter filter :
        List.of(
            MetadataFilter.ofDateWindow(LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
            new MetadataFilter(
                Set.of("VERMERK"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)))) {
      RetrievalPipelineResult result = run(filter, Set.of(library.getId()));
      for (RetrievalStageName path :
          List.of(RetrievalStageName.VECTOR_SEARCH, RetrievalStageName.FULL_TEXT_SEARCH)) {
        assertThat(documentKeys(result, path))
            .as("filter %s, path %s", filter, path)
            .containsExactly(readable.getId().toString());
      }
    }
  }

  /**
   * The same bracket rule inside the metadata condition: with Dokumentart and date combined, the
   * Dokumentart must constrain every date branch, so a document of the wrong Dokumentart with a
   * YEAR date or no date is out of both paths.
   */
  @Test
  void aCombinedFilterAppliesTheDocumentTypeToEveryDateBranch() throws IOException {
    Document wanted = indexed(library, "2024-03-12_Vermerk_Nutzung.pdf");
    Document wrongTypeYear = indexed(library, "Dienstanweisung_Nutzung_2024.pdf");
    Document wrongTypeUndated = indexed(library, "Dienstanweisung_Nutzung.pdf");
    assertThat(core(wrongTypeYear).documentDatePrecision()).isEqualTo(DatePrecision.YEAR);
    assertThat(core(wrongTypeUndated).documentDate()).isNull();

    RetrievalPipelineResult result =
        run(
            new MetadataFilter(
                Set.of("VERMERK"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)),
            Set.of(library.getId()));

    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH))
        .containsExactly(wanted.getId().toString());
    assertThat(documentKeys(result, RetrievalStageName.FULL_TEXT_SEARCH))
        .containsExactly(wanted.getId().toString());
  }

  /**
   * Why the filter must be part of the query: {@code fetch-k} matching documents of the wrong
   * Dokumentart rank ahead of the one wanted document. A post-filter over the {@code fetch-k}
   * window finds nothing; the in-query filter finds it.
   */
  @Test
  void aPostFilterOverTheFetchKWindowDoesNotReproduceTheInQueryFilter() {
    int fetchK = queryProperties.fetchK();
    List<org.springframework.ai.document.Document> chunks = new ArrayList<>();
    for (int i = 0; i < fetchK + 5; i++) {
      chunks.add(chunk("aaa-dienstanweisung-" + String.format("%03d", i), "DIENSTANWEISUNG"));
    }
    String wanted = UUID.randomUUID().toString();
    chunks.add(chunk("zzz-vermerk", "VERMERK", wanted));
    // The production write path: vector and full-text row of every chunk in one transaction.
    vectorChunkStore.addChunks(chunks);
    MetadataFilter filter = MetadataFilter.ofDocumentTypes(List.of("VERMERK"));

    // Lexical path, deterministic order (ties broken by file name): the post-filter finds nothing.
    List<org.springframework.ai.document.Document> unfiltered =
        fullTextChunkSearch.search("Nutzung", Set.of(library.getId()), fetchK);
    assertThat(unfiltered).hasSize(fetchK);
    assertThat(unfiltered.stream().filter(c -> "VERMERK".equals(c.getMetadata().get("doc_type"))))
        .as("the post-filter over the fetch-k window is empty")
        .isEmpty();
    List<String> vocabularyCodes =
        vocabularyRepository.findAllByOrderBySortOrderAsc().stream()
            .map(DocumentTypeVocabularyEntry::getCode)
            .toList();
    List<org.springframework.ai.document.Document> filtered =
        fullTextChunkSearch.search(
            "Nutzung", Set.of(library.getId()), filter, vocabularyCodes, fetchK);
    assertThat(filtered)
        .extracting(c -> c.getMetadata().get("document_id"))
        .containsExactly(wanted);

    // The vector path carries the same condition: the wanted document is in its list too.
    RetrievalPipelineResult result = run(filter, Set.of(library.getId()));
    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH)).containsExactly(wanted);
    assertThat(documentKeys(result, RetrievalStageName.FULL_TEXT_SEARCH)).containsExactly(wanted);
  }

  /**
   * The parity assumption made visible: a Dokumentart the vocabulary does not know (a code removed
   * while chunks still carry it) reads as "no value" in both paths and is kept by both - never kept
   * by one and dropped by the other.
   */
  @Test
  void aChunkWithACodeOutsideTheVocabularyIsTreatedAlikeByBothPaths() {
    String foreign = UUID.randomUUID().toString();
    String selected = UUID.randomUUID().toString();
    vectorChunkStore.addChunks(
        List.of(
            chunk("altcode.pdf", "ALTCODE", foreign), chunk("vermerk.pdf", "VERMERK", selected)));

    RetrievalPipelineResult result =
        run(MetadataFilter.ofDocumentTypes(List.of("VERMERK")), Set.of(library.getId()));

    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH))
        .containsExactlyInAnyOrder(foreign, selected);
    assertThat(documentKeys(result, RetrievalStageName.FULL_TEXT_SEARCH))
        .containsExactlyInAnyOrder(foreign, selected);
  }

  /**
   * #1242: the Absender of a mail is a filterable format field, and both paths carry the identical
   * condition inside their query - a mail of another sender is out, a document that is no mail at
   * all has no value and stays in (Leerwert-Regel).
   */
  @Test
  void bothPathsApplyTheSameFormatFieldConditionAndKeepDocumentsWithoutASender()
      throws IOException {
    Document fromMueller = indexedMail(library, "anfrage.eml", "Max Mueller <max@stadt.de>");
    Document fromSchmidt = indexedMail(library, "hinweis.eml", "schmidt@kreis.de");
    Document noMail = indexed(library, "2024-03-12_Vermerk_Nutzung.pdf");
    indexedMail(forbiddenLibrary, "fremd.eml", "max@stadt.de");

    MetadataFilter filter =
        MetadataFilter.NONE.withFormatFields(
            List.of(FormatFieldCondition.parse("mail_sender", List.of("max@stadt.de"))));
    RetrievalPipelineResult result = run(filter, Set.of(library.getId()));

    Set<String> expected = Set.of(fromMueller.getId().toString(), noMail.getId().toString());
    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH)).isEqualTo(expected);
    assertThat(documentKeys(result, RetrievalStageName.FULL_TEXT_SEARCH)).isEqualTo(expected);
    assertThat(documentKeys(result, RetrievalStageName.VECTOR_SEARCH))
        .doesNotContain(fromSchmidt.getId().toString());
    assertThat(stage(result, RetrievalStageName.METADATA_FILTER).notes())
        .contains("metadata filter: format field mail_sender in [max@stadt.de]");
    // The PDF is kept by the Leerwert rule but is not "ohne Angabe": the sender was never a
    // question its format could answer (#1242). Only a mail without a sender would be.
    assertThat(stage(result, RetrievalStageName.VECTOR_SEARCH).notes())
        .contains(RetrievalNote.METADATA_FILTER_NO_VALUE_CANDIDATES.format(0, 2));
  }

  private RetrievalPipelineResult run(MetadataFilter filter, Set<UUID> scope) {
    return retrievalPipeline.run(
        new RetrievalContext(
            "Nutzung", List.of(), scope, filter, queryProperties, RerankAvailability.SWITCHED_OFF));
  }

  private static StageExplanation stage(RetrievalPipelineResult result, RetrievalStageName name) {
    return result.explanation().stages().stream()
        .filter(stage -> stage.stage() == name)
        .findFirst()
        .orElseThrow();
  }

  private static Set<String> documentKeys(RetrievalPipelineResult result, RetrievalStageName path) {
    Set<String> keys = new HashSet<>();
    stage(result, path).verdicts().forEach(verdict -> keys.add(verdict.documentKey()));
    return keys;
  }

  private CoreMetadata core(Document document) {
    return documentMetadataService.coreMetadataFor(document.getId());
  }

  private void stubAnswer() {
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.call(any(Prompt.class)))
        .thenReturn(
            new ChatResponse(List.of(new Generation(new AssistantMessage("Antwort ohne Beleg.")))));
  }

  private org.springframework.ai.document.Document chunk(String fileName, String documentType) {
    return chunk(fileName, documentType, UUID.randomUUID().toString());
  }

  private org.springframework.ai.document.Document chunk(
      String fileName, String documentType, String documentId) {
    return new org.springframework.ai.document.Document(
        "Diese Unterlage regelt die Nutzung der IT.",
        Map.of(
            "file_name", fileName,
            "document_id", documentId,
            "chunk_index", 0,
            "library_id", library.getId().toString(),
            "doc_type", documentType));
  }

  private CurrentUser user(String name, SystemRole role) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), ?, ?)",
        id,
        "metadata-filter-" + id,
        "metadata-filter-" + name + "-" + id + "@example.com",
        "Filter " + name,
        role.name(),
        Organization.DEFAULT_ID);
    return CurrentUser.of(id, Organization.DEFAULT_ID, role, "Filter " + name);
  }

  private KnowledgeLibrary library(String name, Path sourcePath) {
    return libraryRepository.save(
        KnowledgeLibrary.ownedByUser(
            Organization.DEFAULT_ID,
            name,
            null,
            owner.id(),
            LibraryVisibility.PRIVATE,
            false,
            DocumentSourceType.FILESYSTEM,
            sourcePath.toString(),
            null,
            null,
            null,
            false));
  }

  private void grant(KnowledgeLibrary target, CurrentUser subject, AssetRole role) {
    grantRepository.save(
        AssetGrant.forUser(
            target.getId(), Organization.DEFAULT_ID, subject.id(), role, null, owner.id()));
    accessService.invalidateLibrary(target.getId());
  }

  private Document indexed(KnowledgeLibrary target, String fileName) throws IOException {
    Path file = Path.of(target.getSourcePath()).resolve(fileName);
    writePdf(file);
    assertThat(fileProcessingService.ingest(DocumentIngest.localFile(target, file).build(), null))
        .isEqualTo(FileProcessingResult.PROCESSED);
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .filter(document -> target.getId().equals(document.getLibraryId()))
        .findFirst()
        .orElseThrow();
  }

  /** An indexed mail of {@code sender}, whose body carries the query term of every run here. */
  private Document indexedMail(KnowledgeLibrary target, String fileName, String sender)
      throws IOException {
    Path file =
        classTempDir.resolve(target == library ? "readable" : "forbidden").resolve(fileName);
    Files.writeString(
        file,
        "From: "
            + sender
            + "\nTo: poststelle@stadt.de\nSubject: Nutzung der IT\n"
            + "Date: Thu, 12 Mar 2026 09:15:00 +0100\n"
            + "Content-Type: text/plain; charset=UTF-8\n\n"
            + "Diese Unterlage regelt die Nutzung der IT.\n");
    fileProcessingService.processFile(file, target);
    return documentRepository.findAll().stream()
        .filter(document -> fileName.equals(document.getFileName()))
        .findFirst()
        .orElseThrow();
  }

  private static void writePdf(Path file) throws IOException {
    Files.createDirectories(file.getParent());
    try (PDDocument doc = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      doc.addPage(page);
      try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(50, 700);
        content.showText("Diese Unterlage regelt die Nutzung der IT.");
        content.endText();
      }
      doc.save(file.toFile());
    }
  }

  private static void deletePdfsIn(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (var files = Files.list(directory)) {
      for (Path file : files.toList()) {
        if (Files.isRegularFile(file)) {
          Files.deleteIfExists(file);
        }
      }
    }
  }
}
