package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.format.ChunkFormatMetadata;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.source.profileprobe.PersonProbeIndexingExecutor;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.ErasureCause;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.library.LibraryCreation;
import io.opaa.library.PrivateLibraryCreation;
import io.opaa.library.PrivateLibraryErasure;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The erasure of a private library against the Liquibase schema (#2165): afterwards no table of the
 * schema holds a row naming the library, a document, a folder, a run, a chunk or a name or path of
 * it - the tables come from the catalog, so a new table without an erasure step fails here. Only
 * the protocols keep the library's id, never a name. The erasure is its owner's alone, waits for a
 * running run and continues after a failure.
 */
@OpaaIntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class PrivateLibraryErasureIntegrationTest {

  private static final String LIBRARIES = "/api/v1/libraries";
  private static final String SERVER = "https://person.example.org";
  private static final String PASSWORD = PersonProbeSourceConnector.ACCEPTED_PASSWORD;
  private static final String PRIVATE_NAME = "Private Bibliothek";

  /**
   * Protocols the application only appends to, each deleted by its own retention: they keep the
   * library's id by design and are checked for names separately.
   */
  private static final List<String> PROTOCOL_TABLES =
      List.of(
          "audit_log",
          "connection_log",
          "diagnostic_context_log",
          "asset_grant_history",
          "asset_ownership_history",
          "asset_visibility_history",
          "databasechangelog");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private PrivateLibraryCreation privateCreation;
  @Autowired private PrivateLibraryErasure erasure;
  @Autowired private VectorChunkStore chunks;
  @Autowired private SpaceAssetAssociationService associations;
  @Autowired private PersonProbeIndexingExecutor executor;

  private final List<UUID> libraries = new ArrayList<>();
  private final List<UUID> spaces = new ArrayList<>();
  private UUID owner;
  private UUID admin;
  private UUID profile;
  private CurrentUser ownerCaller;

  @BeforeEach
  void aConnectedAccountOnAProfileForPersons() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    owner = userIdOf("dev-user@opaa.local");
    admin = userIdOf("admin@opaa.local");
    ownerCaller = CurrentUser.of(owner, Organization.DEFAULT_ID, SystemRole.USER, "Dev User");
    profile = profile();
    mockMvc
        .perform(
            as("dev-user", put("/api/v1/me/connected-accounts/" + profile))
                .content("{\"username\": \"avogt\", \"secret\": \"" + PASSWORD + "\"}"))
        .andExpect(status().isOk());
  }

  @AfterEach
  void removeOwnRows() throws Exception {
    for (UUID space : spaces) {
      jdbc.update("DELETE FROM chats WHERE space_id = ?", space);
      mockMvc.perform(as("dev-admin", delete("/api/v1/spaces/" + space)));
      jdbc.update("DELETE FROM space_membership_history WHERE space_id = ?", space);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", space);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", space.toString());
    }
    spaces.clear();
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
    ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
    jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
    jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
  }

  /** Acceptance criterion of #2165 against the Liquibase schema, tables from the catalog. */
  @Test
  void afterItsOwnerErasedItNoTableHoldsARowOfThePrivateLibrary(CapturedOutput output)
      throws Exception {
    Fixture fixture = aFullPrivateLibrary();
    assertThat(leftOf(fixture)).as("the fixture reaches the tables it claims").isNotEmpty();
    mockMvc
        .perform(as("dev-user", delete("/api/v1/me/connected-accounts/" + profile)))
        .andExpect(status().isNoContent());
    assertThat(accountRows())
        .as("a disconnected account stays while a library runs on it")
        .isEqualTo(1);

    mockMvc
        .perform(as("dev-user", delete(LIBRARIES + "/" + fixture.library())))
        .andExpect(status().isNoContent());

    assertThat(leftOf(fixture)).isEmpty();
    assertThat(redactedSources(fixture.chat())).containsExactly("Quelle entfernt");
    assertThat(accountRows()).as("the orphaned disconnected account went with it").isZero();
    theProofNamesNothing(fixture, "OWNER_REQUEST");
    assertThat(output.getAll()).doesNotContain(fixture.names());
    mockMvc
        .perform(as("dev-user", get(LIBRARIES + "/" + fixture.library())))
        .andExpect(status().isNotFound());
  }

  /** The administration can neither erase another person's private library nor learn of it. */
  @Test
  void theAdministrationCannotEraseItAndLearnsNothing() throws Exception {
    UUID library = aPrivateLibrary();

    String foreign = body(as("dev-admin", delete(LIBRARIES + "/" + library)));
    String unknown = body(as("dev-admin", delete(LIBRARIES + "/" + UUID.randomUUID())));

    assertThat(JsonPath.<String>read(foreign, "$.error"))
        .isEqualTo(JsonPath.<String>read(unknown, "$.error"));
    mockMvc
        .perform(as("dev-admin", delete(LIBRARIES + "/" + library)))
        .andExpect(status().isNotFound());
    assertThat(markerOf(library)).isNull();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM assets WHERE id = ?", Integer.class, library))
        .isEqualTo(1);
    mockMvc
        .perform(as("dev-admin", get("/api/v1/admin/search/status")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.privateLibraries.scheduledErasureCount").doesNotExist());
  }

  /**
   * An erasure during a running run marks the library at once (202) and waits: no new run starts,
   * and once the run has ended - whatever it wrote meanwhile - the next run of the deletion
   * completes the erasure.
   */
  @Test
  void anErasureDuringARunningRunWaitsForItAndCompletesAfterwards() throws Exception {
    UUID library = aPrivateLibrary();
    PersonProbeIndexingExecutor.Hold hold = executor.holdNextRun(library);
    CompletableFuture<Void> running =
        CompletableFuture.runAsync(
            () -> {
              try {
                mockMvc
                    .perform(as("dev-user", post(LIBRARIES + "/" + library + "/indexing")))
                    .andExpect(status().isAccepted());
              } catch (Exception e) {
                throw new IllegalStateException(e);
              }
            });
    hold.awaitEntered();
    try {
      mockMvc
          .perform(as("dev-user", delete(LIBRARIES + "/" + library)))
          .andExpect(status().isAccepted());
      assertThat(markerOf(library)).isEqualTo("OWNER_REQUEST");
      mockMvc
          .perform(as("dev-user", get(LIBRARIES + "/" + library)))
          .andExpect(jsonPath("$.erasureRequestedAt").isNotEmpty());
      assertThat(erasure.erase(library, ErasureCause.OWNER_REQUEST, owner))
          .isEqualTo(PrivateLibraryErasure.Outcome.PENDING);
    } finally {
      hold.release();
    }
    running.get(30, TimeUnit.SECONDS);
    await()
        .atMost(Duration.ofSeconds(20))
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM indexing_jobs WHERE library_id = ?"
                            + " AND status = 'RUNNING'",
                        Integer.class,
                        library)
                    == 0);
    mockMvc
        .perform(as("dev-user", post(LIBRARIES + "/" + library + "/indexing")))
        .andExpect(status().isConflict());

    assertThat(erasure.erase(library, ErasureCause.OWNER_REQUEST, null))
        .isEqualTo(PrivateLibraryErasure.Outcome.ERASED);

    assertThat(leftOf(new Fixture(library, List.of(), null, null, null, new String[0]))).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM source_sync_state WHERE library_id = ?",
                Integer.class,
                library))
        .as("the run state the run wrote after the marker")
        .isZero();
  }

  /**
   * A failure in the middle of the erasure rolls it back as a whole and leaves the marker: the
   * library still runs no more, and the next call completes it with one proof.
   */
  @Test
  void anErasureFailingHalfwayKeepsItsMarkerAndTheNextCallCompletesIt() throws Exception {
    Fixture fixture = aFullPrivateLibrary();
    String suffix = fixture.library().toString().replace("-", "");
    jdbc.execute(
        "CREATE FUNCTION fail_erasure_"
            + suffix
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN"
            + " IF OLD.id = '"
            + fixture.library()
            + "' THEN RAISE EXCEPTION 'storage failed'; END IF; RETURN OLD; END; $$");
    jdbc.execute(
        "CREATE TRIGGER fail_erasure_"
            + suffix
            + " BEFORE DELETE ON knowledge_libraries FOR EACH ROW EXECUTE FUNCTION fail_erasure_"
            + suffix
            + "()");
    try {
      assertThatThrownBy(() -> erasure.erase(fixture.library(), ErasureCause.OWNER_REQUEST, owner))
          .isInstanceOf(RuntimeException.class);
    } finally {
      jdbc.execute("DROP TRIGGER fail_erasure_" + suffix + " ON knowledge_libraries");
      jdbc.execute("DROP FUNCTION fail_erasure_" + suffix + "()");
    }
    assertThat(markerOf(fixture.library())).isEqualTo("OWNER_REQUEST");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE library_id = ?",
                Integer.class,
                fixture.library()))
        .as("the deletion was rolled back as a whole")
        .isEqualTo(1);
    assertThat(proofsOf(fixture.library())).isZero();
    mockMvc
        .perform(as("dev-user", post(LIBRARIES + "/" + fixture.library() + "/indexing")))
        .andExpect(status().isConflict());

    assertThat(erasure.erase(fixture.library(), ErasureCause.DELETION_PERIOD_EXPIRED, null))
        .isEqualTo(PrivateLibraryErasure.Outcome.ERASED);

    assertThat(leftOf(fixture)).isEmpty();
    assertThat(proofsOf(fixture.library())).isEqualTo(1);
    theProofNamesNothing(fixture, "OWNER_REQUEST");
  }

  // -------------------------------------------------------------------------------------------

  /** Every catalog column of the schema that still names something of {@code fixture}. */
  private Map<String, Integer> leftOf(Fixture fixture) {
    List<String> needles = new ArrayList<>();
    needles.add(fixture.library().toString());
    for (UUID id : fixture.ids()) {
      needles.add(id.toString());
    }
    needles.addAll(List.of(fixture.names()));
    String[] patterns = needles.stream().map(needle -> "%" + needle + "%").toArray(String[]::new);
    Map<String, Integer> left = new LinkedHashMap<>();
    for (Map<String, Object> column :
        jdbc.queryForList(
            "SELECT c.table_name, c.column_name FROM information_schema.columns c"
                + " JOIN information_schema.tables t ON t.table_schema = c.table_schema"
                + " AND t.table_name = c.table_name"
                + " WHERE c.table_schema = current_schema() AND t.table_type = 'BASE TABLE'"
                + " AND c.data_type IN ('uuid', 'text', 'character varying', 'json', 'jsonb')"
                + " ORDER BY c.table_name, c.column_name")) {
      String table = (String) column.get("table_name");
      if (PROTOCOL_TABLES.stream().anyMatch(table::startsWith)) {
        continue;
      }
      String name = (String) column.get("column_name");
      Integer rows =
          jdbc.queryForObject(
              "SELECT count(*) FROM \"" + table + "\" WHERE \"" + name + "\"::text LIKE ANY (?)",
              Integer.class,
              (Object) patterns);
      if (rows != null && rows > 0) {
        left.put(table + "." + name, rows);
      }
    }
    return left;
  }

  /** The proof names the library neutrally and carries cause and counts, no name or path. */
  private void theProofNamesNothing(Fixture fixture, String cause) {
    List<Map<String, Object>> entries =
        jdbc.queryForList(
            "SELECT object_label, before FROM audit_log"
                + " WHERE object_id = ? AND event_type = 'PRIVATE_LIBRARY_ERASED'",
            fixture.library().toString());
    assertThat(entries).hasSize(1);
    assertThat(entries.getFirst().get("object_label")).isEqualTo(PRIVATE_NAME);
    String before = (String) entries.getFirst().get("before");
    assertThat(before)
        .contains("\"cause\"", cause, "\"documentsRemoved\"", "\"chunksRemoved\"")
        .doesNotContain(fixture.names());
    for (String label :
        jdbc.queryForList(
            "SELECT coalesce(object_label, '') || coalesce(before, '')"
                + " || coalesce(after, '') FROM audit_log WHERE object_id = ?",
            String.class,
            fixture.library().toString())) {
      assertThat(label).doesNotContain(fixture.names());
    }
  }

  private int proofsOf(UUID library) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type = ?",
        Integer.class,
        library.toString(),
        "PRIVATE_LIBRARY_ERASED");
  }

  private String markerOf(UUID library) {
    List<String> causes =
        jdbc.queryForList(
            "SELECT erasure_cause FROM knowledge_libraries WHERE id = ?", String.class, library);
    return causes.isEmpty() ? null : causes.getFirst();
  }

  private int accountRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM connected_accounts WHERE user_id = ? AND profile_id = ?",
        Integer.class,
        owner,
        profile);
  }

  private List<String> redactedSources(UUID chat) {
    return jdbc.queryForList(
        "SELECT s->>'fileName' FROM chat_messages m, json_array_elements(m.sources) s"
            + " WHERE m.chat_id = ? AND m.sources IS NOT NULL",
        String.class,
        chat);
  }

  /**
   * A private library as after real runs: a document with a chunk in both stores, a keyword, a
   * metadata field with a value, a folder, a failed run with its events, a sync state with presence
   * and a revisit, a notification, a space association and a chat citing the document.
   */
  private Fixture aFullPrivateLibrary() throws Exception {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID library = aPrivateLibrary();
    UUID document = UUID.randomUUID();
    String fileName = "gehaltsabrechnung-" + suffix + ".pdf";
    String path = "lohn/" + suffix + "/" + fileName;
    jdbc.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'application/pdf', 1024, 1, now(), ?, 'INDEXED', 'PERSON_PROBE',"
            + " ?, ?, now())",
        document,
        fileName,
        path,
        "checksum-" + document,
        library,
        Organization.DEFAULT_ID);
    String content = "Bruttogehalt " + suffix;
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, document.toString());
    metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, library.toString());
    metadata.put("organization_id", Organization.DEFAULT_ID.toString());
    metadata.put("file_name", fileName);
    metadata.put("chunk_index", 0);
    metadata.put(
        ChunkFormatMetadata.PIPELINE_ID_METADATA_KEY, ChunkFormatMetadata.LEGACY_PIPELINE_ID);
    metadata.put(ChunkFormatMetadata.PIPELINE_VERSION_METADATA_KEY, 0);
    metadata.put(
        ChunkFormatMetadata.ROUTING_EXTENSION_METADATA_KEY,
        ChunkFormatMetadata.NO_ROUTING_EXTENSION);
    chunks.addChunks(List.of(new org.springframework.ai.document.Document(content, metadata)));
    UUID chunk =
        jdbc.queryForObject(
            "SELECT id FROM vector_store WHERE metadata->>'document_id' = ?",
            UUID.class,
            document.toString());
    jdbc.update(
        "INSERT INTO document_keywords (id, document_id, library_id, keyword)"
            + " VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        document,
        library,
        "lohn-" + suffix);
    UUID field = UUID.randomUUID();
    String fieldLabel = "Aktenzeichen " + suffix;
    String value = "AZ-" + suffix;
    jdbc.update(
        "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
            + " value_pattern, filter_enabled, sort_order, created_at, updated_at)"
            + " VALUES (?, ?, 'aktenzeichen', ?, 'PATTERN', '.*', true, 10, now(), now())",
        field,
        library,
        fieldLabel);
    jdbc.update(
        "INSERT INTO document_metadata_values (id, document_id, field_key, text_value, origin,"
            + " library_field_id, created_at, updated_at)"
            + " VALUES (?, ?, 'lib:aktenzeichen', ?, 'MANUAL', ?, now(), now())",
        UUID.randomUUID(),
        document,
        value,
        field);
    UUID folder = UUID.randomUUID();
    String folderName = "Ordner " + suffix;
    jdbc.update(
        "INSERT INTO library_folders (id, library_id, name, organization_id) VALUES (?, ?, ?, ?)",
        folder,
        library,
        folderName,
        Organization.DEFAULT_ID);
    UUID job = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id, library_id,"
            + " error_message, documents_total, documents_failed, completed_at)"
            + " VALUES (?, 'FAILED', now(), ?, ?, ?, 2, 1, now())",
        job,
        Organization.DEFAULT_ID,
        library,
        "Nicht lesbar " + fileName);
    jdbc.update(
        "INSERT INTO indexing_run_events (id, job_id, category, message, reference)"
            + " VALUES (?, ?, 'ERROR', ?, ?)",
        UUID.randomUUID(),
        job,
        "Nicht lesbar: " + fileName,
        path);
    UUID state = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO source_sync_state (id, library_id, updated_at) VALUES (?, ?, now())",
        state,
        library);
    jdbc.update(
        "INSERT INTO source_sync_presence (document_id, sync_state_id, scan_id) VALUES (?, ?, ?)",
        document,
        state,
        UUID.randomUUID());
    jdbc.update(
        "INSERT INTO source_sync_revisits (id, sync_state_id, container_key, hierarchy_path,"
            + " created_at) VALUES (?, ?, 'ablage', ?, now())",
        UUID.randomUUID(),
        state,
        path);
    jdbc.update(
        "INSERT INTO notifications (id, organization_id, recipient_user_id, type, object_type,"
            + " object_id, title, body) VALUES (?, ?, ?, 'PRIVATE_LIBRARY_RELEASED',"
            + " 'KNOWLEDGE_LIBRARY', ?, 'Hinweis', ?)",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        owner,
        library,
        "Ihre Bibliothek " + folderName);
    UUID space = aSpace("Raum " + UUID.randomUUID());
    associations.associate(space, KnowledgeLibrary.ASSET_TYPE, library, ownerCaller);
    UUID chat = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        chat,
        space,
        owner,
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content) VALUES (?, ?, 1, 'USER',"
            + " 'Wie hoch ist mein Gehalt?')",
        UUID.randomUUID(),
        chat);
    jdbc.update(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content, sources)"
            + " VALUES (?, ?, 2, 'ASSISTANT', 'Laut Abrechnung, siehe [1].', ?::json)",
        UUID.randomUUID(),
        chat,
        ("[{\"fileName\": \"%s\", \"documentId\": \"%s\", \"relevanceScore\": 1.0,"
                + " \"matchCount\": 1, \"cited\": true, \"sourceType\": \"PERSON_PROBE\","
                + " \"sourceUrl\": \"%s/%s\"}]")
            .formatted(fileName, document, SERVER, path));
    return new Fixture(
        library,
        List.of(document, chunk, folder, job, state, field),
        chat,
        space,
        fileName,
        new String[] {fileName, path, folderName, fieldLabel, value, "lohn-" + suffix});
  }

  private UUID aPrivateLibrary() {
    UUID id =
        privateCreation.create(
            new LibraryCreation(
                "Ablage " + UUID.randomUUID(),
                null,
                null,
                null,
                PersonProbeSourceConnector.TYPE,
                null,
                URI.create(SERVER + "/ablage"),
                null,
                null,
                null,
                null,
                null,
                profile),
            ownerCaller);
    libraries.add(id);
    return id;
  }

  private UUID aSpace(String name) throws Exception {
    String created =
        body(
            as("dev-admin", post("/api/v1/spaces"))
                .content(
                    """
                    {"name": "%s", "ownerId": "%s", "initialMembers": [
                      {"subjectType": "USER", "subjectId": "%s", "role": "MEMBER"}]}
                    """
                        .formatted(name, owner, admin)));
    UUID space = UUID.fromString(JsonPath.read(created, "$.id"));
    spaces.add(space);
    return space;
  }

  private UUID profile() throws Exception {
    String created =
        body(
            as("dev-admin", post("/api/v1/admin/connection-profiles"))
                .content(
                    """
                    {"name": "Zugang Löschung %s", "sourceType": "PERSON_PROBE",
                     "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "PERSON"}
                    """
                        .formatted(UUID.randomUUID(), SERVER)));
    UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + id);
    return id;
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    return mockMvc
        .perform(request)
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private UUID userIdOf(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private static MockHttpServletRequestBuilder as(
      String user, MockHttpServletRequestBuilder request) {
    return request
        .header(DevAuthFilter.DEV_USER_HEADER, user)
        .contentType(MediaType.APPLICATION_JSON);
  }

  /** What a private library held, by id and by name. */
  private record Fixture(
      UUID library, List<UUID> ids, UUID chat, UUID space, String fileName, String[] names) {}
}
