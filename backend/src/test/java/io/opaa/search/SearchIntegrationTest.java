package io.opaa.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.chat.ChatSource;
import io.opaa.common.NotFoundException;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsRepository;
import io.opaa.indexing.chunk.ChunkingService;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.indexing.metadata.MetadataFilter;
import io.opaa.llm.ActiveChatModelResolver;
import io.opaa.query.QueryResult;
import io.opaa.query.QueryService;
import io.opaa.test.OpaaMockedChatModelIntegrationTest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The reading path without generation, end to end against a real Postgres (#1720): the hits, their
 * parity with the Fundstellen of {@code POST /api/v1/query}, the fetch of a passage and of a whole
 * document, and the three things that must not happen - a generation call, a hit outside the
 * caller's view, an audit entry.
 *
 * <p>Carries {@link OpaaMockedChatModelIntegrationTest} because the parity test has to script the
 * same model answer for both runs; scripting it is what makes the sub-question decomposition
 * deterministic and the comparison meaningful.
 */
@OpaaMockedChatModelIntegrationTest
class SearchIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  /** The opening line of the answer prompt's system text - the marker of a generation call. */
  private static final String GENERATION_PROMPT_MARKER = "ZITIERREGELN (verbindlich)";

  private static final String QUESTION = "Welche Frist gilt für den Widerspruch?";

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private VectorStore vectorStore;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private DocumentRepository documents;
  @Autowired private UserRepository users;
  @Autowired private SearchService searchService;
  @Autowired private PassageFetchService passageFetchService;
  @Autowired private QueryService queryService;
  @Autowired private ChatModel chatModel;
  @Autowired private ActiveChatModelResolver activeChatModelResolver;
  @Autowired private ExternalAccessSettingsRepository externalAccessSettings;

  private UUID callerId;
  private UUID libraryId;
  private UUID documentId;
  private UUID relatedDocumentId;
  private UUID distantDocumentId;
  private UUID strangerId;
  private UUID foreignLibraryId;
  private UUID foreignDocumentId;
  private final List<String> promptTexts = Collections.synchronizedList(new ArrayList<>());

  @BeforeEach
  void setUp() throws Exception {
    promptTexts.clear();
    when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
    when(activeChatModelResolver.resolveChatClient())
        .thenReturn(ChatClient.builder(chatModel).build());
    when(chatModel.call(any(Prompt.class)))
        .thenAnswer(
            invocation -> {
              Prompt prompt = invocation.getArgument(0);
              String text =
                  prompt.getInstructions().stream()
                      .map(Message::getText)
                      .reduce("", (a, b) -> a + "\n" + b);
              promptTexts.add(text);
              // The decomposition gets the question back verbatim, so both runs of the parity
              // test search with exactly the same query.
              String reply = text.contains(GENERATION_PROMPT_MARKER) ? "Ein Monat." : QUESTION;
              return new ChatResponse(
                  List.of(new Generation(new AssistantMessage(reply))),
                  ChatResponseMetadata.builder().model("test-model").build());
            });

    // Provisions the seeded dev-user through the real security chain, then reads its row.
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-user"))).andExpect(status().isOk());
    callerId = userIdOf("dev-user@opaa.local");

    strangerId = UUID.randomUUID();
    insertUser(strangerId, "search-it-stranger");
    libraryId = insertLibrary("Such-IT-Bibliothek", callerId);
    foreignLibraryId = insertLibrary("Fremde Such-IT-Bibliothek", strangerId);

    relatedDocumentId =
        insertDocument(
            libraryId,
            "fristen.md",
            List.of(
                passage(
                    0,
                    "Eine Frist im Verfahren wird nach Kalendertagen berechnet.",
                    "Abschn. Fristen › Berechnung")));
    distantDocumentId =
        insertDocument(
            libraryId,
            "kantine.md",
            List.of(
                passage(0, "Die Kantine öffnet montags um elf Uhr.", "Abschn. Haus › Kantine")));
    documentId =
        insertDocument(
            libraryId,
            "widerspruch.md",
            List.of(
                passage(
                    0,
                    "Die Widerspruchsfrist beträgt einen Monat."
                        + " Sie beginnt mit der Bekanntgabe des Bescheids.",
                    "Abschn. Verfahren › Fristsetzung"),
                passage(
                    1,
                    "Sie beginnt mit der Bekanntgabe des Bescheids."
                        + " Der Widerspruch ist schriftlich zu erheben.",
                    "Abschn. Verfahren › Form"),
                passage(
                    2,
                    "Die Behörde entscheidet über den Widerspruch innerhalb von drei Monaten.",
                    "Abschn. Verfahren › Entscheidung")));
    foreignDocumentId =
        insertDocument(
            foreignLibraryId,
            "fremd.md",
            List.of(passage(0, "Die Widerspruchsfrist einer fremden Behörde.", null)));
  }

  @AfterEach
  void tearDown() {
    vectorChunkStore.deleteByLibraryId(libraryId);
    vectorChunkStore.deleteByLibraryId(foreignLibraryId);
    jdbc.update("DELETE FROM documents WHERE library_id IN (?, ?)", libraryId, foreignLibraryId);
    jdbc.update("DELETE FROM asset_grants WHERE asset_id IN (?, ?)", libraryId, foreignLibraryId);
    jdbc.update(
        "DELETE FROM library_visibility_history WHERE library_id IN (?, ?)",
        libraryId,
        foreignLibraryId);
    jdbc.update("DELETE FROM knowledge_libraries WHERE id IN (?, ?)", libraryId, foreignLibraryId);
    jdbc.update("DELETE FROM users WHERE id = ?", strangerId);
  }

  /**
   * The one barrier against two diverging search paths (#1720): the hits and the Fundstellen of the
   * same question, in the same rights context, name the same documents in the same order.
   *
   * <p>Should this ever become flaky, the answer is <b>not</b> to loosen it - a loosened equality
   * test is no barrier at all - but to find the divergence it is reporting.
   */
  @Test
  void theHitsAreTheSameSelectionInTheSameOrderAsTheFundstellenOfTheQuery() {
    QueryResult answered =
        queryService.query(
            QUESTION, null, caller(), false, List.of(libraryId), MetadataFilter.NONE);
    SearchOutcome searched =
        searchService.search(caller(), QUESTION, List.of(libraryId), MetadataFilter.NONE, 50);

    // The document sequence: one Beleg per document, in first-appearance order of the selection.
    // With three readable documents of different closeness to the question this is a real
    // sequence, so a changed order on either side breaks the comparison.
    List<UUID> fundstellenOrder =
        answered.sources().stream().map(ChatSource::getDocumentId).toList();
    List<UUID> hitOrder = searched.hits().stream().map(SearchHit::documentId).distinct().toList();
    assertThat(hitOrder).hasSizeGreaterThan(1).isEqualTo(fundstellenOrder);

    // The passage level, which is where KnowledgeRetrieval actually selects: every retrieved chunk
    // behind a Beleg is one hit and vice versa. POST /api/v1/query exposes its chunks only per
    // Beleg and ordered by chunk index (ChatSource#chunkLocations), never in selection order - a
    // set is therefore everything the two public paths can be compared on here, and it already
    // catches a selection differing by a single passage.
    Set<String> fundstellenPassages =
        answered.sources().stream()
            .filter(source -> source.getDocumentId() != null)
            .flatMap(
                source ->
                    source.getChunkLocations().stream()
                        .map(location -> source.getDocumentId() + "#" + location.getChunkIndex()))
            .collect(Collectors.toSet());
    Set<String> hitPassages =
        searched.hits().stream()
            .map(hit -> hit.documentId() + "#" + hit.chunkIndex())
            .collect(Collectors.toSet());
    assertThat(hitPassages).isNotEmpty().isEqualTo(fundstellenPassages);

    // The unreadable document appears in neither: the permission filter is part of the one search.
    assertThat(hitOrder).doesNotContain(foreignDocumentId);
  }

  @Test
  void aSearchAsksNoModelToGenerateAnAnswer() {
    SearchOutcome searched =
        searchService.search(caller(), QUESTION, List.of(libraryId), MetadataFilter.NONE, null);

    assertThat(searched.hits()).isNotEmpty();
    // Without this, the assertion below would also pass on an empty list - that is, if the
    // recording path stopped running at all. The load-bearing guard is
    // SearchDependencyStructureTest; this one only adds that no generation prompt went out.
    assertThat(promptTexts).as("the search reached the model at all").isNotEmpty();
    assertThat(promptTexts)
        .as("no prompt of a search carries the answer generation's system text")
        .noneMatch(text -> text.contains(GENERATION_PROMPT_MARKER));
  }

  @Test
  void aHitCarriesItsOriginAndAnExcerptOfItsOwnPassage() {
    SearchOutcome searched =
        searchService.search(caller(), QUESTION, List.of(libraryId), MetadataFilter.NONE, null);

    assertThat(searched.searchedLibraries())
        .singleElement()
        .satisfies(library -> assertThat(library.name()).isEqualTo("Such-IT-Bibliothek"));
    SearchHit hit =
        searched.hits().stream()
            .filter(candidate -> documentId.equals(candidate.documentId()))
            .findFirst()
            .orElseThrow();
    assertThat(hit.hitId()).isNotBlank();
    assertThat(hit.libraryId()).isEqualTo(libraryId);
    assertThat(hit.libraryName()).isEqualTo("Such-IT-Bibliothek");
    assertThat(hit.fileName()).isEqualTo("widerspruch.md");
    assertThat(hit.excerpt()).contains("Widerspruch");
    assertThat(hit.location()).startsWith("Abschn. Verfahren");
    // The rank-derived value of the best hit, whichever document it belongs to.
    assertThat(searched.hits().get(0).relevanceScore()).isEqualTo(1.0);
    assertThat(hit.relevanceScore()).isBetween(0.0, 1.0);
  }

  @Test
  void theScopeIsNeverWidenedBeyondWhatTheCallerMayRead() {
    SearchOutcome searched =
        searchService.search(
            caller(), QUESTION, List.of(foreignLibraryId), MetadataFilter.NONE, null);

    assertThat(searched.hits()).isEmpty();
    assertThat(searched.searchedLibraries()).isEmpty();
  }

  @Test
  void theFetchReturnsThePassageWithItsNeighboursByDefault() {
    String hitId = firstHitOfChunk(0);

    FetchedPassage fetched = passageFetchService.fetch(caller(), hitId, false);

    assertThat(fetched.whole()).isFalse();
    assertThat(fetched.truncated()).isFalse();
    assertThat(fetched.headingPath()).containsExactly("Verfahren", "Fristsetzung");
    assertThat(fetched.text())
        .contains("Die Widerspruchsfrist beträgt einen Monat.")
        .contains("Der Widerspruch ist schriftlich zu erheben.")
        .doesNotContain("innerhalb von drei Monaten");
    // The sentence chunk 0 and chunk 1 share is written once, not twice.
    assertThat(fetched.text().split("Sie beginnt mit der Bekanntgabe des Bescheids\\.", -1))
        .hasSize(2);
  }

  @Test
  void theWholeDocumentComesOnlyWithTheExplicitParameter() {
    String hitId = firstHitOfChunk(0);

    FetchedPassage whole = passageFetchService.fetch(caller(), hitId, true);

    assertThat(whole.whole()).isTrue();
    assertThat(whole.text()).contains("innerhalb von drei Monaten");
    assertThat(whole.characterLimit()).isPositive();
    assertThat(passageFetchService.fetch(caller(), hitId, false).text())
        .doesNotContain("innerhalb von drei Monaten");
  }

  @Test
  void aHitOutsideTheEffectiveViewIsIndistinguishableFromAnUnknownOne() {
    String foreignHitId = chunkIdOf(foreignDocumentId, 0);

    assertThatThrownBy(() -> passageFetchService.fetch(caller(), foreignHitId, false))
        .isInstanceOf(NotFoundException.class)
        .hasMessage(PassageFetchService.NOT_FOUND_MESSAGE);
    assertThatThrownBy(
            () -> passageFetchService.fetch(caller(), UUID.randomUUID().toString(), false))
        .isInstanceOf(NotFoundException.class)
        .hasMessage(PassageFetchService.NOT_FOUND_MESSAGE);
  }

  @Test
  void theListingNamesTheLibrariesOfTheEffectiveViewAndNoOther() {
    List<SearchableLibrary> listed = searchService.libraries(caller());

    assertThat(listed).extracting(SearchableLibrary::id).contains(libraryId);
    assertThat(listed).extracting(SearchableLibrary::id).doesNotContain(foreignLibraryId);
    assertThat(listed)
        .filteredOn(library -> library.id().equals(libraryId))
        .singleElement()
        .satisfies(library -> assertThat(library.name()).isEqualTo("Such-IT-Bibliothek"));
  }

  /** The promise of security-and-compliance.md: the single query leaves no trail. */
  @Test
  void neitherSearchNorFetchWritesAnAuditEntry() {
    long before = auditEntries();

    searchService.search(caller(), QUESTION, List.of(libraryId), MetadataFilter.NONE, null);
    passageFetchService.fetch(caller(), firstHitOfChunk(0), true);

    assertThat(auditEntries()).isEqualTo(before);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theEndpointAnswersOverHttpAndDoesNotHangOnTheExternalAccessSwitch() throws Exception {
    assertThat(channelEnabled()).as("the delivered default is a closed channel").isFalse();

    String body =
        "{\"question\":\"" + QUESTION + "\",\"libraryIds\":[\"" + libraryId + "\"],\"maxHits\":5}";
    String response =
        mockMvc
            .perform(
                post("/api/v1/search")
                    .with(devUser("dev-user"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hits[0].relevanceScore").value(1.0))
            .andExpect(jsonPath("$.hits[0].hitId").isNotEmpty())
            .andExpect(jsonPath("$.searchedLibraries[0].name").value("Such-IT-Bibliothek"))
            // The hit of the document this class asserts on, wherever it ranks - the fixture has
            // three readable documents precisely so the order is not a foregone conclusion.
            .andExpect(
                jsonPath("$.hits[?(@.documentId == '" + documentId + "')].downloadUrl")
                    .value(everyItem(equalTo("/api/v1/documents/" + documentId + "/content"))))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String hitId =
        ((List<String>)
                com.jayway.jsonpath.JsonPath.read(
                    response, "$.hits[?(@.documentId == '" + documentId + "')].hitId"))
            .get(0);

    mockMvc
        .perform(get("/api/v1/search/hits/" + hitId).with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.whole").value(false))
        .andExpect(jsonPath("$.documentId").value(documentId.toString()));
    mockMvc
        .perform(get("/api/v1/search/hits/" + UUID.randomUUID()).with(devUser("dev-user")))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/search/libraries").with(devUser("dev-user")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id == '" + libraryId + "')].name").exists())
        .andExpect(jsonPath("$[?(@.id == '" + foreignLibraryId + "')]").isEmpty());
  }

  private boolean channelEnabled() {
    return externalAccessSettings
        .findSingleton()
        .map(ExternalAccessSettings::isEnabled)
        .orElse(false);
  }

  /**
   * Only rows about this class's own objects and actor - a count over the whole table would be open
   * to an entry some other path wrote between the two measurements.
   */
  private long auditEntries() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE object_id IN (?, ?, ?, ?)",
        Long.class,
        documentId.toString(),
        relatedDocumentId.toString(),
        distantDocumentId.toString(),
        libraryId.toString());
  }

  private CurrentUser caller() {
    return CurrentUser.of(callerId, DEFAULT_ORGANIZATION_ID, SystemRole.USER, null);
  }

  private String firstHitOfChunk(int chunkIndex) {
    return chunkIdOf(documentId, chunkIndex);
  }

  private String chunkIdOf(UUID document, int chunkIndex) {
    return jdbc.queryForObject(
        "SELECT id::text FROM vector_store WHERE metadata->>'document_id' = ?"
            + " AND metadata->>'chunk_index' = ?",
        String.class,
        document.toString(),
        String.valueOf(chunkIndex));
  }

  private UUID userIdOf(String email) {
    return users.findAll().stream()
        .filter(user -> email.equals(user.getEmail()))
        .map(User::getId)
        .findFirst()
        .orElseThrow();
  }

  private void insertUser(UUID id, String subject) {
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, ?, now(), 'USER', ?)",
        id,
        subject + "-" + id,
        subject + "@example.com",
        "Search IT " + subject,
        DEFAULT_ORGANIZATION_ID);
  }

  private UUID insertLibrary(String name, UUID ownerId) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, ?, 'USER', ?, 'PRIVATE', false, 'UPLOAD', now(), now())",
        id,
        DEFAULT_ORGANIZATION_ID,
        name,
        ownerId);
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        id,
        DEFAULT_ORGANIZATION_ID,
        ownerId);
    return id;
  }

  private record Passage(int index, String text, String location) {}

  private static Passage passage(int index, String text, String location) {
    return new Passage(index, text, location);
  }

  private UUID insertDocument(UUID library, String fileName, List<Passage> passages) {
    io.opaa.indexing.document.Document document =
        new io.opaa.indexing.document.Document(
            fileName, "/" + fileName, "text/markdown", 100L, DocumentSourceType.UPLOAD);
    document.setLibraryId(library);
    document.setOrganizationId(DEFAULT_ORGANIZATION_ID);
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(passages.size());
    document.setIndexedAt(Instant.now());
    documents.save(document);

    List<Document> chunks = new ArrayList<>();
    for (Passage passage : passages) {
      Map<String, Object> metadata = new LinkedHashMap<>();
      metadata.put("document_id", document.getId().toString());
      metadata.put("library_id", library.toString());
      metadata.put("file_name", fileName);
      metadata.put("chunk_index", passage.index());
      if (passage.location() != null) {
        metadata.put(ChunkingService.LOCATION_METADATA_KEY, passage.location());
      }
      chunks.add(new Document(passage.text(), metadata));
    }
    vectorStore.add(chunks);
    return document.getId();
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }
}
