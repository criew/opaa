package io.opaa.externalaccess.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.DocumentStatus;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.document.DocumentRepository;
import io.opaa.library.LibraryExternalAccessService;
import io.opaa.search.SearchScopeSource;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What a bearer access token actually reaches on the reading path of #1720 - the point of the whole
 * channel, and the first test in which the four factors of the effective view meet a real search.
 *
 * <p>The person owns two released libraries and the token names only one. Every assertion here is
 * about the difference between those two sets: the token finds what is in its own library, finds
 * nothing from the other one although the person may read it, is told only about its own library by
 * {@code /search/libraries}, and is offered no download link anywhere.
 */
@OpaaIntegrationTest
class ExternalAccessSearchIntegrationTest {

  private static final UUID DEFAULT_ORGANIZATION_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private VectorStore vectorStore;
  @Autowired private VectorChunkStore vectorChunkStore;
  @Autowired private DocumentRepository documents;
  @Autowired private UserRepository users;
  @Autowired private ExternalAccessTokenRepository tokens;
  @Autowired private ExternalAccessTokenService tokenService;
  @Autowired private ExternalAccessSettingsService settings;
  @Autowired private LibraryExternalAccessService libraryRelease;
  @Autowired private Clock clock;
  @Autowired private SearchScopeSource searchScopeSource;

  private User owner;
  private User administrator;
  private UUID selectedLibraryId;
  private UUID otherLibraryId;
  private String rawValue;
  private UUID tokenId;

  @BeforeEach
  void setUp() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-user"))).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/auth/me").with(devUser("dev-admin"))).andExpect(status().isOk());
    owner = users.findBySubjectAndIssuer("dev-user", "opaa-dev").orElseThrow();
    administrator = users.findBySubjectAndIssuer("dev-admin", "opaa-dev").orElseThrow();
    jdbc.update("DELETE FROM external_access_tokens WHERE user_id = ?", owner.getId());
    setChannel(true, 1000);

    selectedLibraryId = insertLibrary("Ausgewählte Bibliothek");
    otherLibraryId = insertLibrary("Nicht ausgewählte Bibliothek");
    release(selectedLibraryId);
    release(otherLibraryId);
    insertDocument(
        selectedLibraryId, "widerspruch.md", "Die Widerspruchsfrist beträgt einen Monat.");
    insertDocument(otherLibraryId, "fremd.md", "Die Widerspruchsfrist einer anderen Bibliothek.");

    ExternalAccessTokenService.IssuedExternalAccessToken issued =
        tokenService.issue(
            owner.getId(),
            owner.getOrganizationId(),
            "Suchtoken",
            List.of(selectedLibraryId),
            clock.instant().plus(Duration.ofDays(30)));
    rawValue = issued.rawValue();
    tokenId = issued.token().getId();
  }

  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM external_access_tokens WHERE user_id = ?", owner.getId());
    for (UUID library : List.of(selectedLibraryId, otherLibraryId)) {
      vectorChunkStore.deleteByLibraryId(library);
      jdbc.update("DELETE FROM documents WHERE library_id = ?", library);
      jdbc.update("DELETE FROM asset_grants WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM assets WHERE id = ?", library);
    }
  }

  @Test
  void theTokenAwareScopeSourceIsTheOnlyOneInTheContext() {
    // Named, not inferred: a second SearchScopeSource switched in by a condition would decide by
    // bean-registration order, and losing that race serves a token call the person's full readable
    // set - past the very intersection this channel promises.
    assertThat(searchScopeSource).isExactlyInstanceOf(ExternalAccessSearchScopeSource.class);
  }

  @Test
  void aTokenFindsOnlyWhatItsOwnLibrariesHold() throws Exception {
    String body = search();

    List<String> libraryIds = JsonPath.read(body, "$.hits[*].libraryId");
    assertThat(libraryIds).isNotEmpty().containsOnly(selectedLibraryId.toString());
    assertThat(body).doesNotContain(otherLibraryId.toString());
  }

  @Test
  void thePersonHerselfStillFindsBothLibraries() throws Exception {
    // The same question, the same person, without the token: the narrowing is the token's, not
    // the person's - otherwise the assertion above would prove nothing.
    String body =
        mockMvc
            .perform(
                post("/api/v1/search")
                    .with(devUser("dev-user"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"Welche Frist gilt für den Widerspruch?\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(body, "$.hits[*].libraryId"))
        .contains(selectedLibraryId.toString(), otherLibraryId.toString());
  }

  @Test
  void aQuestionAimedAtAForeignLibraryFindsNothing() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/search")
                .with(bearer(rawValue))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"question\":\"Welche Frist gilt für den Widerspruch?\",\"libraryIds\":[\""
                        + otherLibraryId
                        + "\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.hits").isEmpty());
  }

  @Test
  void theListOfSearchableLibrariesIsTheEffectiveViewOfTheToken() throws Exception {
    String body =
        mockMvc
            .perform(get("/api/v1/search/libraries").with(bearer(rawValue)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<List<String>>read(body, "$[*].id"))
        .containsExactly(selectedLibraryId.toString());
  }

  @Test
  void aWithdrawnReleaseEmptiesTheViewOnTheVeryNextCall() throws Exception {
    assertThat(JsonPath.<List<String>>read(search(), "$.hits[*].libraryId")).isNotEmpty();

    libraryRelease.setExternalAccess(
        CurrentUser.of(
            owner.getId(), owner.getOrganizationId(), owner.getSystemRole(), "Verantwortliche"),
        selectedLibraryId,
        false,
        null);

    assertThat(JsonPath.<List<String>>read(search(), "$.hits[*].libraryId")).isEmpty();
  }

  @Test
  void aTokenIsOfferedNoDownloadLink() throws Exception {
    String body = search();

    // The person herself is offered one for the same document - so an absent link here is the
    // token's doing, not a missing original.
    String asPerson =
        mockMvc
            .perform(
                post("/api/v1/search")
                    .with(devUser("dev-user"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"Welche Frist gilt für den Widerspruch?\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(JsonPath.<List<String>>read(asPerson, "$.hits[*].downloadUrl"))
        .isNotEmpty()
        .anySatisfy(url -> assertThat(url).isNotNull());

    assertThat(JsonPath.<List<String>>read(body, "$.hits[*].downloadUrl")).containsOnlyNulls();

    String hitId = JsonPath.<List<String>>read(body, "$.hits[*].hitId").getFirst();
    mockMvc
        .perform(get("/api/v1/search/hits/" + hitId).with(bearer(rawValue)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.downloadUrl").doesNotExist());

    // ... and the content endpoint is not on the allowlist at all.
    mockMvc
        .perform(get("/api/v1/documents/" + UUID.randomUUID() + "/content").with(bearer(rawValue)))
        .andExpect(status().isForbidden());
  }

  @Test
  void theQuotaOfTheChannelAppliesToATokenCall() throws Exception {
    setChannel(true, 1);

    search();
    mockMvc
        .perform(
            post("/api/v1/search")
                .with(bearer(rawValue))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Welche Frist gilt für den Widerspruch?\"}"))
        .andExpect(status().isTooManyRequests());
  }

  @Test
  void theQuotaDoesNotApplyToTheSignedInPerson() throws Exception {
    setChannel(true, 1);

    for (int attempt = 0; attempt < 3; attempt++) {
      mockMvc
          .perform(
              post("/api/v1/search")
                  .with(devUser("dev-user"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"question\":\"Welche Frist gilt für den Widerspruch?\"}"))
          .andExpect(status().isOk());
    }
  }

  @Test
  void aTokenCallWritesNoAuditEntryAndNoUsageBeyondTheDay() throws Exception {
    long before =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM audit_log WHERE object_id = ?", Long.class, tokenId.toString());

    search();

    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE object_id = ?",
                Long.class,
                tokenId.toString()))
        .isEqualTo(before);
    assertThat(tokens.findById(tokenId).orElseThrow().getLastUsedOn()).isNotNull();
  }

  private String search() throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/search")
                .with(bearer(rawValue))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Welche Frist gilt für den Widerspruch?\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private void setChannel(boolean enabled, int quotaPerHour) {
    ExternalAccessSettings.Values values = settings.current().values();
    settings.update(
        CurrentUser.of(
            administrator.getId(),
            administrator.getOrganizationId(),
            administrator.getSystemRole(),
            "Systemverwaltung"),
        new ExternalAccessSettingsService.Update(
            enabled,
            values.tokenMaxLifetimeDays(),
            quotaPerHour,
            values.allowedCidrs(),
            values.massRetrievalAlertThreshold(),
            values.serverInstructions()));
  }

  private void release(UUID library) {
    libraryRelease.setExternalAccess(
        CurrentUser.of(
            owner.getId(), owner.getOrganizationId(), owner.getSystemRole(), "Verantwortliche"),
        library,
        true,
        clock.instant().plus(Duration.ofDays(60)));
  }

  private UUID insertLibrary(String name) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id, listed) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, false)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        id,
        DEFAULT_ORGANIZATION_ID,
        name,
        owner.getId());
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type, subject_user_id,"
            + " role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        id,
        DEFAULT_ORGANIZATION_ID,
        owner.getId());
    return id;
  }

  private void insertDocument(UUID library, String fileName, String text) {
    io.opaa.indexing.document.Document document =
        new io.opaa.indexing.document.Document(
            fileName, "/" + fileName, "text/markdown", 100L, DocumentSourceType.UPLOAD);
    document.setLibraryId(library);
    document.setOrganizationId(DEFAULT_ORGANIZATION_ID);
    document.setStatus(DocumentStatus.INDEXED);
    document.setChunkCount(1);
    document.setIndexedAt(Instant.now());
    documents.save(document);

    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("document_id", document.getId().toString());
    metadata.put("library_id", library.toString());
    metadata.put("file_name", fileName);
    metadata.put("chunk_index", 0);
    List<Document> chunks = new ArrayList<>();
    chunks.add(new Document(text, metadata));
    vectorStore.add(chunks);
  }

  private RequestPostProcessor devUser(String subject) {
    return request -> {
      request.addHeader(DevAuthFilter.DEV_USER_HEADER, subject);
      return request;
    };
  }

  private RequestPostProcessor bearer(String value) {
    return request -> {
      request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + value);
      return request;
    };
  }
}
