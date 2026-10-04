package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreation;
import io.opaa.library.LibraryUpdate;
import io.opaa.library.PrivateLibraryCreation;
import io.opaa.organization.Organization;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.yaml.snakeyaml.Yaml;

/**
 * A private library does not exist for anyone but its owner (ADR-0041, Entscheidung 6): every
 * operation of the API that names a library, an asset, a document or a chunk - in the path, the
 * query or the body - or that answers a list or a number, gives each observer exactly the answer it
 * gave before the private library existed. The operations come from the bundled specification, so a
 * new one without an entry in {@link #PROBES} fails {@link #everyRelevantOperationHasAProbe}.
 *
 * <p>Each observer is asked twice: once before another person creates her private libraries, with
 * ids nobody knows, and once after, with the private libraries' own ids. Status and body must be
 * the same, ids and instants set aside; the answer after must name nothing of the private library.
 */
@OpaaIntegrationTest
class PrivateLibraryInvisibilityIntegrationTest {

  private static final Pattern ID_NAME =
      Pattern.compile("(?i).*(libraryid|assetid|documentid|chunkid)s?$");
  private static final Pattern INSTANT =
      Pattern.compile(
          "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})?");
  private static final String SERVER = "https://person.example.org";
  private static final String PASSWORD = PersonProbeSourceConnector.ACCEPTED_PASSWORD;
  private static final UUID UNKNOWN = UUID.fromString("7f1d3c58-0d8b-4c39-9a52-5d2a4a8f2e11");

  /** Who looks for the private library. */
  enum Observer {
    SYSTEM_ADMIN("dev-admin", "USER", "MEMBER"),
    AUDITOR("dev-user", "AUDITOR", "MEMBER"),
    SPACE_MEMBER("dev-user", "USER", "MEMBER"),
    CURATOR("dev-user", "USER", "CURATOR");

    private final String devUser;
    private final String devUserRole;
    private final String devUserSpaceRole;

    Observer(String devUser, String devUserRole, String devUserSpaceRole) {
      this.devUser = devUser;
      this.devUserRole = devUserRole;
      this.devUserSpaceRole = devUserSpaceRole;
    }
  }

  /** How the two answers of an operation are held against each other. */
  enum Kind {
    /** Status and body as without the private library. */
    SAME,
    /**
     * A protocol: as {@link #SAME} for whoever may not read it; an auditor reads the private
     * library's id, never its name or content.
     */
    PROTOCOL,
    /** The answer changes by itself between two calls; only nothing of the library may appear. */
    VOLATILE
  }

  /**
   * One operation's request and expectation; {@code body} and {@code query} hold the placeholders
   * {@code {library}}, {@code {detached}}, {@code {document}}, {@code {chunk}}, {@code {folder}},
   * {@code {space}}, {@code {profile}}, {@code {unknown}} and {@code {expires}}.
   */
  record Probe(Kind kind, String body, Map<String, String> query, boolean multipart) {

    Probe withQuery(String name, String value) {
      Map<String, String> next = new LinkedHashMap<>(query);
      next.put(name, value);
      return new Probe(kind, body, next, multipart);
    }
  }

  private static Probe same() {
    return new Probe(Kind.SAME, null, Map.of(), false);
  }

  private static Probe same(String body) {
    return new Probe(Kind.SAME, body, Map.of(), false);
  }

  private static Probe protocol() {
    return new Probe(Kind.PROTOCOL, null, Map.of(), false);
  }

  private static Probe volatileAnswer() {
    return new Probe(Kind.VOLATILE, null, Map.of(), false);
  }

  /** Every relevant operation of the specification, by operation id. */
  static final Map<String, Probe> PROBES = probes();

  private static Map<String, Probe> probes() {
    Map<String, Probe> probes = new LinkedHashMap<>();
    for (String read :
        List.of(
            "listAccounts",
            "listCapabilities",
            "getConnectionLogRetention",
            "listConnectionProfiles",
            "getConnectionProfile",
            "getConnectionProfileImpact",
            "listConnectorTypeStates",
            "getConnectorProfileRequirement",
            "getDiagnosticContextRetention",
            "listDiagnosticImpersonationGrants",
            "getClientAddressDiagnostics",
            "listDirectorySyncStatus",
            "listExternalAccessLibraries",
            "listAllExternalAccessTokens",
            "listGroups",
            "listGroupEffects",
            "listGroupPage",
            "listLowChunkDocuments",
            "getPipelineVersionStatus",
            "getLocalAuthSettings",
            "listLocalUsers",
            "getLocalUserSummary",
            "getLocalUser",
            "listLlmModels",
            "getEmbeddingInfo",
            "listOidcProviders",
            "getOidcProvider",
            "getDirectorySyncPendingPlan",
            "getPermissionHistoryRetention",
            "getSearchChunk",
            "getSearchDiagnosisContext",
            "listDocumentChunks",
            "listSuccessionEntries",
            "listUsers",
            "getAssetAccessDerivation",
            "listAssetGrants",
            "listGrantedGroupMembers",
            "listAssetSpaceAssociations",
            "getAuthConfig",
            "getCurrentUser",
            "getBranding",
            "getBrandingLoginBackground",
            "getBrandingLoginLogo",
            "getBrandingLogo",
            "listCatalog",
            "getChat",
            "listConnectionProfileOptions",
            "getDocumentContent",
            "listEligibleExternalAccessLibraries",
            "getOwnExternalAccessChannelInfo",
            "listOwnExternalAccessTokens",
            "searchSelectableGroups",
            "resolveSelectableGroup",
            "getGroup",
            "listGroupMembers",
            "listGroupStewards",
            "listLibraries",
            "getLibrary",
            "listLibraryDocuments",
            "getDocumentMetadata",
            "getLibraryFolder",
            "listLibraryIndexingRuns",
            "getLibraryIndexingStatus",
            "listLibraryMetadataFields",
            "getLibraryMetadataFieldUsage",
            "getLibraryMetadataFieldValueUsage",
            "getLibraryMetadataExtractionSettings",
            "getLibraryMetadataMaintenance",
            "getLibraryMetadataQuality",
            "getLibraryMetadataSample",
            "listMyCapabilities",
            "listMyConnectedAccounts",
            "listOwnDiagnosticContextEvents",
            "listMyGroups",
            "listMyStewardedGroups",
            "listDocumentTypes",
            "listNotifications",
            "listPromptLibraries",
            "getPromptLibrary",
            "listPrompts",
            "getPrompt",
            "listAvailablePrompts",
            "fetchSearchHit",
            "listSearchableLibraries",
            "getMetadataFilterOptions",
            "listSourceTypes",
            "listSpaces",
            "getChatAutoCleanupPeriods",
            "getSpace",
            "getSpaceAccessDerivation",
            "listSpaceAssetAssociations",
            "listChatImports",
            "listSpaceChats",
            "listArchivedSpaceChats",
            "listSpaceMembers",
            "listSpaceGroupMembers",
            "getExternalAccessSettings",
            "getMailSettings",
            "listMailTemplates",
            "getMailTemplate",
            "listUsersForSelection",
            "listConnectionProfileRequests",
            "listMyConnectionProfileRequests")) {
      probes.put(read, same());
    }
    probes.put("getHealth", volatileAnswer());
    probes.put("getSearchStatus", same());
    probes.put(
        "getMetadataChangeImpact",
        same().withQuery("fieldKey", "document_type").withQuery("change", "DELETE"));
    for (String protocol :
        List.of(
            "listAccessAsOf",
            "listConnectionLog",
            "listDiagnosticContextEvents",
            "getDiagnosticContextEvent",
            "listAuditEventsByCorrelation",
            "listAuditEventsByObject",
            "listAuditEventsByTimeRange",
            "listAuditEventsByIncidentScope")) {
      probes.put(protocol, protocol());
    }
    probes.put("listAuditEventsByEventType", protocol().withQuery("eventType", "LIBRARY_CREATED"));
    probes.put("rerunContextPrefixBatch", same("{\"libraryId\": \"{library}\"}"));
    probes.put("backfillMetadataBatch", same("{\"libraryId\": \"{library}\"}"));
    probes.put(
        "runSearchDiagnosis",
        same(
            "{\"question\": \"Wo steht die Abrechnung?\", \"contextType\": \"SELF\","
                + " \"trackedDocumentId\": \"{document}\"}"));
    probes.put("reportOrphanedOriginals", same("{\"libraryId\": \"{library}\"}"));
    probes.put(
        "deleteOrphanedOriginals",
        same("{\"libraryId\": \"{library}\", \"locators\": [\"originale/notiz.pdf\"]}"));
    probes.put(
        "deleteOrphanedLibraryOriginals",
        same("{\"libraryId\": \"{library}\", \"locators\": [\"originale/notiz.pdf\"]}"));
    probes.put("updateChat", same("{\"referencedLibraryIds\": [\"{library}\"]}"));
    probes.put(
        "createExternalAccessToken",
        same(
            "{\"name\": \"Zugriff\", \"libraryIds\": [\"{library}\"], \"expiresAt\":"
                + " \"{expires}\"}"));
    probes.put(
        "testLibrarySource",
        same("{\"sourceType\": \"PERSON_PROBE\", \"libraryId\": \"{library}\"}"));
    probes.put("updateLibrary", same("{\"name\": \"Übernommen\"}"));
    probes.put("deleteLibrary", same());
    probes.put("connectLibraryProfile", same("{\"profileId\": \"{profile}\"}"));
    probes.put("disconnectLibraryProfile", same());
    probes.put("setLibraryDiagnosticsLock", same("{\"locked\": false}"));
    probes.put("uploadLibraryDocument", new Probe(Kind.SAME, null, Map.of(), true));
    probes.put("bulkDeleteLibraryDocuments", same("{\"documentIds\": [\"{document}\"]}"));
    probes.put(
        "bulkSetDocumentMetadata",
        same(
            "{\"fieldKey\": \"document_type\", \"value\": {\"state\": \"NOT_DETERMINABLE\"},"
                + " \"documentIds\": [\"{document}\"]}"));
    probes.put("deleteLibraryDocument", same());
    probes.put("setDocumentMetadataValue", same("{\"state\": \"NOT_DETERMINABLE\"}"));
    probes.put("deleteDocumentMetadataValue", same());
    probes.put(
        "setLibraryExternalAccess", same("{\"enabled\": true, \"expiresAt\": \"{expires}\"}"));
    probes.put("createLibraryFolder", same("{\"name\": \"Neu\"}"));
    probes.put("renameLibraryFolder", same("{\"name\": \"Neu\"}"));
    probes.put("deleteLibraryFolder", same());
    probes.put("triggerLibraryIndexing", same());
    probes.put(
        "createLibraryMetadataField",
        same(
            "{\"fieldKey\": \"aktenzeichen\", \"label\": \"Aktenzeichen\", \"type\": \"PATTERN\"}"));
    probes.put("updateCoreContextPrefix", same("{\"documentType\": true, \"documentDate\": true}"));
    probes.put("runLibraryMetadataSchemaChanges", same("{\"batchSize\": 10}"));
    probes.put("updateLibraryMetadataField", same("{\"label\": \"Neu\"}"));
    probes.put("deleteLibraryMetadataField", same());
    probes.put("addLibraryMetadataFieldValue", same("{\"code\": \"neu\", \"label\": \"Neu\"}"));
    probes.put("relabelLibraryMetadataFieldValue", same("{\"label\": \"Neu\"}"));
    probes.put("remapLibraryMetadataFieldValue", same("{\"targetCode\": \"neu\"}"));
    probes.put(
        "updateLibraryMetadataExtractionSettings",
        same("{\"modelExtractionEnabled\": true, \"keywordsEnabled\": true}"));
    probes.put("receivePush", same("{}"));
    probes.put("generatePushSecret", same());
    probes.put("removePushSecret", same());
    probes.put("updateLibraryShareCap", same("{\"allAccountsGrantAllowed\": false}"));
    probes.put(
        "submitQuery",
        // the query ignores libraryIds without a chat; an unknown prompt ends it before the model
        same(
            "{\"question\": \"Was steht in der Abrechnung?\", \"usedPromptId\": \"{unknown}\","
                + " \"libraryIds\": [\"{library}\"]}"));
    probes.put(
        "searchKnowledge",
        same("{\"question\": \"Was steht in der Abrechnung?\", \"libraryIds\": [\"{library}\"]}"));
    probes.put(
        "browseSource", same("{\"sourceUrl\": \"" + SERVER + "\", \"libraryId\": \"{library}\"}"));
    probes.put(
        "associateSpaceAsset",
        same("{\"assetType\": \"KNOWLEDGE_LIBRARY\", \"assetId\": \"{library}\"}"));
    probes.put(
        "createChat", same("{\"title\": \"Fragen\", \"referencedLibraryIds\": [\"{library}\"]}"));
    probes.put("markAssetFavorite", same());
    probes.put("unmarkAssetFavorite", same());
    probes.put(
        "upsertAssetGrant", same("{\"subjectType\": \"ALL_ACCOUNTS\", \"role\": \"VIEWER\"}"));
    probes.put("revokeAssetGrant", same());
    probes.put(
        "transferAssetOwnership", same("{\"ownerType\": \"USER\", \"ownerId\": \"{unknown}\"}"));
    probes.put("detachSpaceAsset", same());
    // a prompt library is another asset type; its operations name it by an id like a library's
    String prompt =
        "{\"name\": \"zusammenfassen\", \"title\": \"Zusammenfassen\", \"text\": \"Fasse\"}";
    probes.put("createPrompt", same(prompt));
    probes.put("updatePrompt", same(prompt));
    probes.put("deletePrompt", same());
    probes.put("updatePromptLibrary", same("{\"name\": \"Vorlagen\"}"));
    probes.put("deletePromptLibrary", same());
    return probes;
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private PrivateLibraryCreation privateCreation;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private SpaceAssetAssociationService associations;

  private final List<UUID> libraries = new ArrayList<>();
  private UUID owner;
  private UUID devUser;
  private UUID admin;
  private UUID profile;
  private UUID space;
  private CurrentUser ownerCaller;

  @BeforeEach
  void provisionTheDevUsers() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    devUser = userIdOf("dev-user@opaa.local");
    admin = userIdOf("admin@opaa.local");
  }

  @AfterEach
  void removeOwnRows() throws Exception {
    jdbc.update("UPDATE users SET system_role = 'USER' WHERE id = ?", devUser);
    if (space != null) {
      jdbc.update("DELETE FROM chats WHERE space_id = ?", space);
      mockMvc.perform(as("dev-admin", delete("/api/v1/spaces/" + space)));
      jdbc.update("DELETE FROM space_membership_history WHERE space_id = ?", space);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", space);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", space.toString());
    }
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
    }
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    libraries.clear();
    if (profile != null) {
      jdbc.update("DELETE FROM connected_accounts WHERE profile_id = ?", profile);
      jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profile);
      ConnectorReleases.withdraw(jdbc, "PROFILE:" + profile);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", profile.toString());
      jdbc.update("DELETE FROM connection_profiles WHERE id = ?", profile);
    }
    if (owner != null) {
      jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", owner);
      jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", owner);
      jdbc.update("DELETE FROM users WHERE id = ?", owner);
    }
  }

  @Test
  void everyRelevantOperationHasAProbe() {
    Set<String> relevant = new TreeSet<>();
    for (Operation operation : relevantOperations()) {
      relevant.add(operation.id());
    }
    Set<String> missing = new TreeSet<>(relevant);
    missing.removeAll(PROBES.keySet());
    Set<String> stale = new TreeSet<>(PROBES.keySet());
    stale.removeAll(relevant);
    assertThat(missing)
        .as("operations naming a library, asset, document or chunk or answering a list, unprobed")
        .isEmpty();
    assertThat(stale).as("probes of operations that no longer qualify").isEmpty();
  }

  @ParameterizedTest
  @EnumSource(Observer.class)
  void noObserverCanTellAnotherPersonsPrivateLibraryFromNone(Observer observer) throws Exception {
    aSpaceWithTheObserverAndAPersonWithAConnectedAccount(observer);
    Ids unknown = Ids.random();
    Map<String, Answer> before = new LinkedHashMap<>();
    for (Operation operation : relevantOperations()) {
      before.put(operation.id(), call(operation, PROBES.get(operation.id()), unknown, observer));
    }

    Ids own = herPrivateLibraries();

    List<String> findings = new ArrayList<>();
    Map<String, Answer> answers = new LinkedHashMap<>();
    for (Operation operation : relevantOperations()) {
      Probe probe = PROBES.get(operation.id());
      Answer after = call(operation, probe, own, observer);
      answers.put(operation.id(), after);
      Answer was = before.get(operation.id());
      String label = operation.id() + " (" + operation.method() + " " + operation.path() + ")";
      boolean protocolReader = probe.kind() == Kind.PROTOCOL && observer == Observer.AUDITOR;
      for (String marker : own.markers(!protocolReader)) {
        if (after.raw().contains(marker)) {
          findings.add(label + " names " + marker + ": " + abbreviated(after.raw()));
        }
      }
      if (probe.kind() == Kind.VOLATILE || protocolReader) {
        if (after.status() >= 500) {
          findings.add(label + " fails with " + after.status());
        }
        continue;
      }
      if (after.status() != was.status()
          || !after.normalized(own).equals(was.normalized(unknown))) {
        findings.add(
            label
                + " answers "
                + after.status()
                + " "
                + abbreviated(after.normalized(own))
                + " instead of "
                + was.status()
                + " "
                + abbreviated(was.normalized(unknown)));
      }
    }
    assertThat(findings).as("what %s learns of the private library", observer).isEmpty();
    theProbesReachedWhatTheyAsk(observer, answers, own);
    herLibrariesAreUntouched(own);
    if (observer == Observer.SYSTEM_ADMIN) {
      theSpaceIsDeletedWithoutNamingTheAssociation(own);
    }
  }

  /**
   * The probes asked what they mean to ask, not a validation error: the auditor reads the private
   * library's protocol under its neutral name, the administration its status page, the members the
   * space the library stands in.
   */
  private static void theProbesReachedWhatTheyAsk(
      Observer observer, Map<String, Answer> answers, Ids own) {
    switch (observer) {
      case AUDITOR -> {
        for (String protocol : List.of("listAuditEventsByObject", "listAccessAsOf")) {
          assertThat(answers.get(protocol).status()).as(protocol).isEqualTo(200);
          assertThat(answers.get(protocol).raw())
              .as(protocol)
              .contains(own.library().toString(), KnowledgeLibrary.PRIVATE_AUDIT_NAME);
        }
      }
      case SYSTEM_ADMIN -> {
        assertThat(answers.get("getSearchStatus").status()).isEqualTo(200);
        assertThat(answers.get("getSearchStatus").raw()).contains("privateLibraries");
        assertThat(answers.get("listConnectionProfiles").status()).isEqualTo(200);
      }
      case SPACE_MEMBER, CURATOR -> {
        assertThat(answers.get("listSpaceAssetAssociations").status()).isEqualTo(200);
        assertThat(answers.get("getSpace").status()).isEqualTo(200);
      }
    }
  }

  /** The space's deletion neither names nor counts the private library associated with it. */
  private void theSpaceIsDeletedWithoutNamingTheAssociation(Ids own) throws Exception {
    MvcResult deletion =
        mockMvc.perform(as("dev-admin", delete("/api/v1/spaces/" + space))).andReturn();
    assertThat(deletion.getResponse().getStatus()).isEqualTo(204);
    List<String> entries =
        jdbc.queryForList(
            "SELECT coalesce(object_label, '') || coalesce(before, '') || coalesce(after, '')"
                + " FROM audit_log WHERE object_id = ? AND event_type = 'SPACE_DELETED'",
            String.class,
            space.toString());
    assertThat(entries).hasSize(1);
    for (String marker : own.markers(true)) {
      assertThat(entries.getFirst()).doesNotContain(marker);
    }
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /**
   * Another person with a connected account on a profile for persons, and a space she owns with the
   * observer and the administration among its members.
   */
  private void aSpaceWithTheObserverAndAPersonWithAConnectedAccount(Observer observer)
      throws Exception {
    jdbc.update("UPDATE users SET system_role = ? WHERE id = ?", observer.devUserRole, devUser);
    owner = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
            + " last_login_at) VALUES (?, ?, 'invisibility-it', ?, 'Ilse Eigen', ?, now())",
        owner,
        "eigen-" + owner,
        "eigen-" + owner + "@example.com",
        Organization.DEFAULT_ID);
    ownerCaller = CurrentUser.of(owner, Organization.DEFAULT_ID, SystemRole.USER, "Ilse Eigen");
    String profileBody =
        body(
            as("dev-admin", post("/api/v1/admin/connection-profiles"))
                .content(
                    """
                    {"name": "Zugang Personen %s", "sourceType": "PERSON_PROBE",
                     "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "PERSON"}
                    """
                        .formatted(UUID.randomUUID(), SERVER)));
    profile = UUID.fromString(JsonPath.read(profileBody, "$.id"));
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    accounts.connect(ownerCaller, profile, "ieigen", PASSWORD);
    String spaceBody =
        body(
            as("dev-admin", post("/api/v1/spaces"))
                .content(
                    """
                    {"name": "Raum %s", "ownerId": "%s", "initialMembers": [
                      {"subjectType": "USER", "subjectId": "%s", "role": "%s"},
                      {"subjectType": "USER", "subjectId": "%s", "role": "MEMBER"}]}
                    """
                        .formatted(
                            UUID.randomUUID(), owner, devUser, observer.devUserSpaceRole, admin)));
    space = UUID.fromString(JsonPath.read(spaceBody, "$.id"));
  }

  /**
   * Her two private libraries - one on her account, one released from its profile - with a
   * document, a chunk, a folder and a failed run, the first renamed and associated with the space.
   */
  private Ids herPrivateLibraries() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID library = createPrivate("Geheimablage " + suffix);
    UUID detached = createPrivate("Zweitablage " + suffix);
    jdbc.update("UPDATE library_connections SET profile_id = NULL WHERE library_id = ?", detached);
    String name = "Personalakte " + suffix;
    libraryService.updateLibrary(
        library,
        new LibraryUpdate(name, "Notiz " + suffix, null, null, null, null, null, null, null, null),
        ownerCaller);
    UUID document = UUID.randomUUID();
    String fileName = "gehaltsabrechnung-" + suffix + ".pdf";
    jdbc.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'application/pdf', 1024, 0, now(), ?, 'INDEXED', 'PERSON_PROBE',"
            + " ?, ?, now())",
        document,
        fileName,
        "invisibility-it/" + document,
        "checksum-" + document,
        library,
        Organization.DEFAULT_ID);
    UUID chunk = UUID.randomUUID();
    String content = "Bruttogehalt " + suffix;
    jdbc.update(
        "INSERT INTO vector_store (id, content, metadata) VALUES (?, ?, ?::jsonb)",
        chunk,
        content,
        "{\"document_id\":\"%s\",\"library_id\":\"%s\",\"file_name\":\"%s\"}"
            .formatted(document, library, fileName));
    UUID folder = UUID.randomUUID();
    String folderName = "Ordner " + suffix;
    jdbc.update(
        "INSERT INTO library_folders (id, library_id, name, organization_id) VALUES (?, ?, ?, ?)",
        folder,
        library,
        folderName,
        Organization.DEFAULT_ID);
    jdbc.update(
        "INSERT INTO indexing_jobs (status, last_progress_at, organization_id, library_id,"
            + " error_message, failure_category, completed_at) VALUES ('FAILED', now(), ?, ?, ?,"
            + " 'NOT_CONNECTED', now())",
        Organization.DEFAULT_ID,
        library,
        "Verbindung getrennt " + fileName);
    associations.associate(space, KnowledgeLibrary.ASSET_TYPE, library, ownerCaller);
    return new Ids(
        library,
        detached,
        document,
        chunk,
        folder,
        List.of(
            name,
            "Geheimablage " + suffix,
            "Zweitablage " + suffix,
            "Notiz " + suffix,
            fileName,
            content,
            folderName));
  }

  private UUID createPrivate(String name) {
    UUID id =
        privateCreation.create(
            new LibraryCreation(
                name,
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

  /** Nothing the observers tried changed her libraries: same owner, one grant, her account. */
  private void herLibrariesAreUntouched(Ids own) {
    for (UUID library : List.of(own.library(), own.detached())) {
      assertThat(
              jdbc.queryForObject(
                  "SELECT owner_user_id FROM assets WHERE id = ? AND owner_only",
                  UUID.class,
                  library))
          .isEqualTo(owner);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM asset_grants WHERE asset_id = ?", Integer.class, library))
          .isEqualTo(1);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE id = ?", Integer.class, own.document()))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT external_access_state FROM knowledge_libraries WHERE id = ?",
                String.class,
                own.library()))
        .isEqualTo("NEVER_SET");
  }

  // -------------------------------------------------------------------------------------------
  // Calls
  // -------------------------------------------------------------------------------------------

  /** The ids a probe names, and what of the private libraries no answer may carry. */
  record Ids(
      UUID library, UUID detached, UUID document, UUID chunk, UUID folder, List<String> names) {

    static Ids random() {
      return new Ids(
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          List.of());
    }

    /** Names and content, and with {@code withIds} also the ids. */
    Set<String> markers(boolean withIds) {
      Set<String> markers = new LinkedHashSet<>(names);
      if (withIds) {
        for (UUID id : List.of(library, detached, document, chunk, folder)) {
          markers.add(id.toString());
        }
      }
      return markers;
    }

    String fill(String template, UUID space, UUID profile) {
      return template
          .replace("{library}", library.toString())
          .replace("{detached}", detached.toString())
          .replace("{document}", document.toString())
          .replace("{chunk}", chunk.toString())
          .replace("{folder}", folder.toString())
          .replace("{space}", space.toString())
          .replace("{profile}", profile.toString())
          .replace("{unknown}", UNKNOWN.toString())
          .replace("{expires}", Instant.now().plus(30, ChronoUnit.DAYS).toString());
    }

    String normalize(String body) {
      return INSTANT
          .matcher(
              body.replace(library.toString(), "<library>")
                  .replace(detached.toString(), "<detached>")
                  .replace(document.toString(), "<document>")
                  .replace(chunk.toString(), "<chunk>")
                  .replace(folder.toString(), "<folder>"))
          .replaceAll("<instant>");
    }
  }

  /** One answer: status and body as sent. */
  record Answer(int status, String raw) {
    String normalized(Ids ids) {
      return ids.normalize(raw);
    }
  }

  private Answer call(Operation operation, Probe probe, Ids ids, Observer observer)
      throws Exception {
    String path = operation.path();
    for (Parameter parameter : operation.parameters()) {
      if (parameter.in().equals("path")) {
        path = path.replace("{" + parameter.name() + "}", valueOf(parameter, ids));
      }
    }
    AbstractMockHttpServletRequestBuilder<?> request;
    if (probe.multipart()) {
      request =
          MockMvcRequestBuilders.multipart(path)
              .file(
                  new MockMultipartFile(
                      "file", "notiz.txt", "text/plain", "Notiz".getBytes(StandardCharsets.UTF_8)));
    } else {
      request =
          MockMvcRequestBuilders.request(HttpMethod.valueOf(operation.method()), path)
              .contentType(MediaType.APPLICATION_JSON);
    }
    for (Parameter parameter : operation.parameters()) {
      if (!parameter.in().equals("query")) {
        continue;
      }
      String value = probe.query().get(parameter.name());
      if (value == null && (parameter.required() || ID_NAME.matcher(parameter.name()).matches())) {
        value = valueOf(parameter, ids);
      }
      if (value != null) {
        request.queryParam(parameter.name(), ids.fill(value, space, profile));
      }
    }
    if (probe.body() != null) {
      request.content(ids.fill(probe.body(), space, profile));
    } else if (operation.body() && !probe.multipart()) {
      request.content("{}");
    }
    request.header(DevAuthFilter.DEV_USER_HEADER, observer.devUser);
    MvcResult result = mockMvc.perform(request).andReturn();
    return new Answer(
        result.getResponse().getStatus(),
        result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /** A parameter's value: the fixture's ids by name, else what its schema admits. */
  private String valueOf(Parameter parameter, Ids ids) {
    String name = parameter.name();
    return switch (name) {
      case "libraryId", "assetId", "libraryIds", "objectId" -> ids.library().toString();
      case "documentId" -> ids.document().toString();
      case "chunkId" -> ids.chunk().toString();
      case "folderId" -> ids.folder().toString();
      case "spaceId" -> space.toString();
      case "profileId" -> profile.toString();
      case "assetType" -> KnowledgeLibrary.ASSET_TYPE.value();
      case "sourceType" -> PersonProbeSourceConnector.TYPE.key();
      case "fieldKey" -> "document_type";
      case "from" -> Instant.now().minus(1, ChronoUnit.HOURS).toString();
      case "to" -> Instant.now().plus(1, ChronoUnit.HOURS).toString();
      case "reason" -> "Prüfung der Unsichtbarkeit privater Bibliotheken";
      default -> {
        Object values = parameter.schema().get("enum");
        if (values instanceof List<?> list && !list.isEmpty()) {
          yield String.valueOf(list.getFirst());
        }
        if ("uuid".equals(parameter.schema().get("format"))) {
          yield UNKNOWN.toString();
        }
        if ("integer".equals(parameter.schema().get("type"))) {
          yield "0";
        }
        yield "unbekannt";
      }
    };
  }

  // -------------------------------------------------------------------------------------------
  // The specification
  // -------------------------------------------------------------------------------------------

  record Parameter(String name, String in, boolean required, Map<String, Object> schema) {}

  record Operation(
      String id, String method, String path, List<Parameter> parameters, boolean body) {}

  private static List<Operation> relevantOperations() {
    Map<String, Object> spec = specification();
    Map<String, Object> components = map(spec.get("components"));
    Map<String, Object> schemas = map(components.get("schemas"));
    Map<String, Object> sharedParameters = map(components.get("parameters"));
    List<Operation> relevant = new ArrayList<>();
    for (Map.Entry<String, Object> path : map(spec.get("paths")).entrySet()) {
      Map<String, Object> item = map(path.getValue());
      for (String method : List.of("get", "post", "put", "patch", "delete")) {
        Map<String, Object> operation = map(item.get(method));
        if (operation.isEmpty()) {
          continue;
        }
        List<Parameter> parameters = new ArrayList<>();
        for (Object declared :
            concat(list(item.get("parameters")), list(operation.get("parameters")))) {
          Map<String, Object> parameter = map(declared);
          if (parameter.containsKey("$ref")) {
            parameter = map(sharedParameters.get(lastSegment(parameter.get("$ref"))));
          }
          parameters.add(
              new Parameter(
                  (String) parameter.get("name"),
                  (String) parameter.get("in"),
                  Boolean.TRUE.equals(parameter.get("required")),
                  resolve(map(parameter.get("schema")), schemas)));
        }
        Map<String, Object> requestBody = map(operation.get("requestBody"));
        boolean names =
            parameters.stream().anyMatch(parameter -> ID_NAME.matcher(parameter.name()).matches());
        for (Object content : map(requestBody.get("content")).values()) {
          names |= namesAnId(map(map(content).get("schema")), schemas, 0);
        }
        if (method.equals("get") || names) {
          relevant.add(
              new Operation(
                  (String) operation.get("operationId"),
                  method.toUpperCase(java.util.Locale.ROOT),
                  path.getKey(),
                  parameters,
                  !requestBody.isEmpty()));
        }
      }
    }
    return relevant;
  }

  /** Whether {@code schema}, or an object two levels below it, has a property naming an id. */
  private static boolean namesAnId(
      Map<String, Object> schema, Map<String, Object> schemas, int depth) {
    Map<String, Object> resolved = resolve(schema, schemas);
    if (depth > 2) {
      return false;
    }
    for (Object part : list(resolved.get("allOf"))) {
      if (namesAnId(map(part), schemas, depth)) {
        return true;
      }
    }
    for (Map.Entry<String, Object> property : map(resolved.get("properties")).entrySet()) {
      if (ID_NAME.matcher(property.getKey()).matches()
          || namesAnId(map(property.getValue()), schemas, depth + 1)) {
        return true;
      }
    }
    return false;
  }

  private static Map<String, Object> resolve(
      Map<String, Object> schema, Map<String, Object> schemas) {
    Map<String, Object> resolved = schema;
    for (int hop = 0; hop < 10 && resolved.containsKey("$ref"); hop++) {
      resolved = map(schemas.get(lastSegment(resolved.get("$ref"))));
    }
    return resolved;
  }

  private static Map<String, Object> specification() {
    try (InputStream in =
        PrivateLibraryInvisibilityIntegrationTest.class.getResourceAsStream(
            "/openapi/opaa-api.yaml")) {
      return new Yaml().load(in);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("the bundled specification is not readable", e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value instanceof Map<?, ?> found ? (Map<String, Object>) found : Map.of();
  }

  private static List<?> list(Object value) {
    return value instanceof List<?> found ? found : List.of();
  }

  private static List<Object> concat(List<?> first, List<?> second) {
    List<Object> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  private static String lastSegment(Object reference) {
    String text = String.valueOf(reference);
    return text.substring(text.lastIndexOf('/') + 1);
  }

  private static String abbreviated(String text) {
    return text.length() > 300 ? text.substring(0, 300) + "…" : text;
  }

  private String body(MockHttpServletRequestBuilder request) throws Exception {
    MvcResult result = mockMvc.perform(request).andReturn();
    assertThat(result.getResponse().getStatus()).isBetween(200, 201);
    return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
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
}
