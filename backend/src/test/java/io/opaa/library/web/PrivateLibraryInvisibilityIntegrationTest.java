package io.opaa.library.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.connection.account.ConnectedAccountService;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrant;
import io.opaa.diagnosticaccess.DiagnosticImpersonationGrantRepository;
import io.opaa.externalaccess.ExternalAccessSettings;
import io.opaa.externalaccess.ExternalAccessSettingsService;
import io.opaa.externalaccess.token.ExternalAccessTokenService;
import io.opaa.format.ChunkFormatMetadata;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.indexing.source.profileprobe.PersonProbeSourceConnector;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreation;
import io.opaa.library.LibraryExternalAccessService;
import io.opaa.library.LibraryUpdate;
import io.opaa.library.PrivateLibraryCreation;
import io.opaa.organization.Organization;
import io.opaa.permission.GroupMembershipResolver;
import io.opaa.permission.GroupSizeProperties;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.test.ConnectorReleases;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.ProviderFixtures;
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
import org.springframework.http.HttpHeaders;
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
  private static final Pattern PREVIEW_ID = Pattern.compile("\"previewId\"\\s*:\\s*\"[^\"]*\"");
  private static final tools.jackson.databind.ObjectMapper JSON =
      new tools.jackson.databind.ObjectMapper();
  private static final String PERSON_CONTEXT_QUESTION = "Wo steht meine Abrechnung?";

  /** "Sicht als" the owner. */
  private static final String PERSON_CONTEXT_DIAGNOSIS =
      "{\"question\": \""
          + PERSON_CONTEXT_QUESTION
          + "\", \"contextType\": \"USER\", \"targetUserId\": \"{owner}\","
          + " \"justification\": \"Prüfung der Unsichtbarkeit privater Bibliotheken\"}";

  /** Who looks for the private library. */
  enum Observer {
    SYSTEM_ADMIN("dev-admin", "USER", "MEMBER"),
    /** The administration with a befugnis for "Sicht als" over a group the owner belongs to. */
    PERSON_CONTEXT("dev-admin", "USER", "MEMBER"),
    AUDITOR("dev-user", "AUDITOR", "MEMBER"),
    SPACE_MEMBER("dev-user", "USER", "MEMBER"),
    CURATOR("dev-user", "USER", "CURATOR"),
    /** A person with a private library of her own on the same profile. */
    OTHER_OWNER("dev-user", "USER", "MEMBER"),
    /** An external-access token of a person, for a shared library of hers. */
    TOKEN("dev-user", "USER", "MEMBER");

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
   * {@code {space}}, {@code {spare}}, {@code {profile}}, {@code {owner}}, {@code {admin}}, {@code
   * {unknown}} and {@code {expires}}. A value in {@code query} also overrides a path parameter.
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

  /**
   * Operations that name no library but answer a number that private libraries could change; they
   * are relevant besides every GET and every operation naming an id.
   */
  static final Set<String> COUNTING =
      Set.of(
          "reportOrphanedLibraries",
          "reindexPipelineBatch",
          "previewConnectionProfileChange",
          "updateConnectionProfile",
          "disconnectAllConnectionProfileConnections",
          "previewPermissionTransfer",
          "deleteSpace");

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
            "getConnectionRedirect",
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
            "getOidcProviderImpact",
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
            "getMyPrivateStorage",
            "getPrivateStorageQuota",
            "getPrivateStorageSummary",
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
            "listMyConnectionProfileRequests",
            "listConnectionLogProfiles",
            "listDormantSourceConnections",
            "listMcpServers",
            "getMcpServer")) {
      probes.put(read, same());
    }
    probes.put("getHealth", volatileAnswer());
    probes.put("getMetadataFilterOptions", same().withQuery("spaceId", "{space}"));
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
    probes.put("disconnectLibrarySource", same());
    probes.put(
        "startConnectionAuthorization",
        same(
            "{\"profileId\": \"{profile}\", \"purpose\": \"LIBRARY_RECONNECT\","
                + " \"libraryId\": \"{library}\", \"serviceAccountConfirmed\": true}"));
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
    // the counting operations; disconnecting and deleting run in both phases alike
    probes.put("reportOrphanedLibraries", same("{}"));
    probes.put(
        "reindexPipelineBatch",
        same("{\"pipelineId\": \"tika-fallback\", \"belowVersion\": 1, \"batchSize\": 10}"));
    // both change the default only the profile sets, each to a value of its own, while her
    // private library runs
    probes.put(
        "previewConnectionProfileChange",
        same(
            "{\"name\": \"Zugang Personen {profile}\", \"serverUrl\": \"https://anders.example.org\","
                + " \"authMethod\": \"PERSONAL_SECRET\", \"ownership\": \"BOTH\","
                + " \"connectorSettings\": {\"realm\": \"drei\"}}"));
    probes.put(
        "updateConnectionProfile",
        same(
            "{\"name\": \"Zugang Personen {profile}\", \"serverUrl\": \""
                + SERVER
                + "\", \"authMethod\": \"PERSONAL_SECRET\", \"ownership\": \"BOTH\","
                + " \"connectorSettings\": {\"realm\": \"zwei\"}, \"confirmDiscard\": true}"));
    probes.put("disconnectAllConnectionProfileConnections", same());
    probes.put(
        "previewPermissionTransfer",
        same(
            "{\"sourceType\": \"USER\", \"sourceId\": \"{owner}\", \"targetType\": \"USER\","
                + " \"targetId\": \"{admin}\", \"scope\": [\"OWNERSHIP\"]}"));
    probes.put("deleteSpace", same().withQuery("spaceId", "{spare}"));
    return probes;
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private PrivateLibraryCreation privateCreation;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private ConnectedAccountService accounts;
  @Autowired private SpaceAssetAssociationService associations;
  @Autowired private VectorChunkStore chunks;
  @Autowired private OidcProviderRepository providers;
  @Autowired private GroupRepository groups;
  @Autowired private DiagnosticImpersonationGrantRepository befugnisse;
  @Autowired private GroupMembershipResolver memberships;
  @Autowired private GroupSizeProperties groupSize;
  @Autowired private ExternalAccessSettingsService externalSettings;
  @Autowired private LibraryExternalAccessService externalRelease;
  @Autowired private ExternalAccessTokenService tokens;

  private final List<UUID> libraries = new ArrayList<>();
  private final List<UUID> spaces = new ArrayList<>();
  private final List<UUID> extraUsers = new ArrayList<>();
  private UUID owner;
  private UUID devUser;
  private UUID admin;
  private UUID profile;
  private String profileDefaults;
  private boolean devUserOwns;
  private UUID space;
  private UUID chat;
  private UUID provider;
  private UUID group;
  private String token;
  private Instant startedAt;
  private CurrentUser ownerCaller;
  private CurrentUser devCaller;
  private CurrentUser adminCaller;

  @BeforeEach
  void provisionTheDevUsers() throws Exception {
    startedAt = Instant.now();
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    devUser = userIdOf("dev-user@opaa.local");
    admin = userIdOf("admin@opaa.local");
    devCaller = CurrentUser.of(devUser, Organization.DEFAULT_ID, SystemRole.USER, "Dev User");
    adminCaller =
        CurrentUser.of(admin, Organization.DEFAULT_ID, SystemRole.SYSTEM_ADMIN, "Dev Admin");
  }

  @AfterEach
  void removeOwnRows() throws Exception {
    jdbc.update("UPDATE users SET system_role = 'USER' WHERE id = ?", devUser);
    jdbc.update("DELETE FROM external_access_tokens WHERE user_id = ?", devUser);
    jdbc.update("DELETE FROM diagnostic_impersonation_grants WHERE holder_user_id = ?", admin);
    for (UUID removed : spaces) {
      jdbc.update("DELETE FROM chats WHERE space_id = ?", removed);
      mockMvc.perform(as("dev-admin", delete("/api/v1/spaces/" + removed)));
      jdbc.update("DELETE FROM space_membership_history WHERE space_id = ?", removed);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", removed);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", removed.toString());
    }
    spaces.clear();
    for (UUID library : libraries) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
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
    if (group != null) {
      jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", group);
      jdbc.update("DELETE FROM groups WHERE id = ?", group);
      jdbc.update("DELETE FROM oidc_providers WHERE id = ?", provider);
    }
    for (UUID extra : extraUsers) {
      jdbc.update("DELETE FROM users WHERE id = ?", extra);
    }
    extraUsers.clear();
    if (owner != null && !owner.equals(devUser)) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", owner.toString());
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
    assertThat(relevant).containsAll(COUNTING);
  }

  @ParameterizedTest
  @EnumSource(Observer.class)
  void noObserverCanTellAnotherPersonsPrivateLibraryFromNone(Observer observer) throws Exception {
    aSpaceWithTheObserverAndAPersonWithAConnectedAccount(observer);
    theObserversOwnStanding(observer);
    Ids unknown = Ids.random();
    Spare spareBefore = aSpareSpace(observer, null);
    Map<String, Answer> before = new LinkedHashMap<>();
    for (Operation operation : relevantOperations()) {
      before.put(
          operation.id(),
          call(operation, PROBES.get(operation.id()), unknown, observer, spareBefore));
    }
    theConnectionsAreBackAndTheSpareIsGone(observer, spareBefore);

    Ids own = herPrivateLibraries();
    Spare spareAfter = aSpareSpace(observer, own.library());

    List<String> findings = new ArrayList<>();
    Map<String, Answer> answers = new LinkedHashMap<>();
    for (Operation operation : relevantOperations()) {
      Probe probe = PROBES.get(operation.id());
      Answer after = call(operation, probe, own, observer, spareAfter);
      answers.put(operation.id(), after);
      Answer was = before.get(operation.id());
      String label = operation.id() + " (" + operation.method() + " " + operation.path() + ")";
      boolean protocolReader = probe.kind() == Kind.PROTOCOL && observer == Observer.AUDITOR;
      for (String marker : own.markers(!protocolReader)) {
        if (after.raw().contains(marker)) {
          findings.add(label + " names " + marker + ": " + abbreviated(after.raw()));
        }
      }
      for (String mark : PRIVATE_SOURCE_MARKS) {
        if (after.raw().replace(" ", "").contains(mark)) {
          findings.add(label + " carries " + mark + ": " + abbreviated(after.raw()));
        }
      }
      for (String mark : LIFECYCLE_MARKS) {
        if (after.raw().replace(" ", "").contains(mark)) {
          findings.add(label + " carries " + mark + ": " + abbreviated(after.raw()));
        }
      }
      if (probe.kind() == Kind.VOLATILE || protocolReader) {
        if (after.status() >= 500) {
          findings.add(label + " fails with " + after.status());
        }
        continue;
      }
      String afterNormalized = unordered(spareAfter.normalize(after.normalized(own)));
      String wasNormalized = unordered(spareBefore.normalize(was.normalized(unknown)));
      if (after.status() != was.status() || !afterNormalized.equals(wasNormalized)) {
        findings.add(
            label
                + " answers "
                + after.status()
                + " "
                + around(afterNormalized, wasNormalized)
                + " instead of "
                + was.status()
                + " "
                + around(wasNormalized, afterNormalized));
      }
    }
    assertThat(findings).as("what %s learns of the private library", observer).isEmpty();
    theProbesReachedWhatTheyAsk(observer, answers, own);
    herLibrariesAreUntouched(own);
    if (observer == Observer.SYSTEM_ADMIN) {
      theSpareSpacesAreDeletedAlike(spareBefore, spareAfter, own);
      theReindexAdvancedHerStaleDocument(own);
    }
    if (observer == Observer.PERSON_CONTEXT) {
      theDiagnosticProtocolNamesNothingOfHers(own);
    }
  }

  /** The marks of an answer from a private library; only the owner's own answers carry them. */
  private static final List<String> PRIVATE_SOURCE_MARKS =
      List.of("\"privateSource\":true", "\"privateSourcesInContext\":true");

  /** The marks of a private library being erased and of a run ended by the quota. */
  private static final List<String> LIFECYCLE_MARKS =
      List.of(
          "\"erasureRequestedAt\":\"",
          "\"failureCategory\":\"QUOTA_EXHAUSTED\"",
          "LIBRARY_BEING_ERASED");

  /**
   * The marks the owner finds on her own ways, by operation: her library in the catalog, her
   * private source in her chat, her own account on the profile.
   */
  private static final Map<String, List<String>> OWNER_MARKS =
      Map.of(
          "listCatalog", List.of("\"privateLibrary\":true", "\"erasureRequestedAt\":\""),
          "listLibraries", List.of("\"erasureRequestedAt\":\""),
          "getLibraryIndexingStatus", List.of("\"failureCategory\":\"QUOTA_EXHAUSTED\""),
          "getChat", PRIVATE_SOURCE_MARKS,
          "listConnectionProfileOptions", List.of("\"ownAccount\":true"));

  /**
   * The ways the owner herself takes to her private library, with the probes' own requests, and
   * which of its markers each must answer: {@code null} for any of them.
   */
  private static final Map<String, Integer> OWNER_WAYS = ownerWays();

  private static Map<String, Integer> ownerWays() {
    Map<String, Integer> ways = new LinkedHashMap<>();
    ways.put("searchKnowledge", Ids.CONTENT);
    ways.put("fetchSearchHit", Ids.CONTENT);
    ways.put("getMetadataFilterOptions", Ids.VALUE);
    ways.put("listLibraryDocuments", Ids.FILE_NAME);
    ways.put("getDocumentMetadata", Ids.VALUE);
    ways.put("getLibrary", Ids.NAME);
    ways.put("listLibraries", Ids.NAME);
    ways.put("listSearchableLibraries", Ids.NAME);
    ways.put("getLibraryFolder", Ids.FOLDER);
    ways.put("listLibraryIndexingRuns", Ids.FILE_NAME);
    ways.put("listSpaceAssetAssociations", Ids.NAME);
    ways.put("getChat", Ids.CONTENT);
    return ways;
  }

  /**
   * The positive control of the observers' phases: with the same requests, the owner finds her
   * private library on every way they are refused, and the administration's summaries name its
   * figures exactly once enough persons keep a private library.
   */
  @Test
  void theOwnerFindsHerPrivateLibraryOnTheProbedWays() throws Exception {
    devUserOwns = true;
    aSpaceWithTheObserverAndAPersonWithAConnectedAccount(Observer.SPACE_MEMBER);
    Ids own = herPrivateLibraries();
    Spare spare = aSpareSpace(Observer.SPACE_MEMBER, null);
    Map<String, Operation> operations = new LinkedHashMap<>();
    for (Operation operation : relevantOperations()) {
      operations.put(operation.id(), operation);
    }

    List<String> misses = new ArrayList<>();
    for (Map.Entry<String, Integer> way : OWNER_WAYS.entrySet()) {
      Operation operation = operations.get(way.getKey());
      Answer answer = call(operation, PROBES.get(way.getKey()), own, Observer.SPACE_MEMBER, spare);
      String marker = own.names().get(way.getValue());
      if (answer.status() >= 300 || !answer.raw().contains(marker)) {
        misses.add(
            way.getKey()
                + " answers "
                + answer.status()
                + " without "
                + marker
                + ": "
                + abbreviated(answer.raw()));
      }
    }
    for (Map.Entry<String, List<String>> marks : OWNER_MARKS.entrySet()) {
      Operation operation = operations.get(marks.getKey());
      Answer answer =
          call(operation, PROBES.get(marks.getKey()), own, Observer.SPACE_MEMBER, spare);
      for (String mark : marks.getValue()) {
        if (answer.status() >= 300 || !answer.raw().replace(" ", "").contains(mark)) {
          misses.add(marks.getKey() + " answers " + answer.status() + " without " + mark);
        }
      }
    }
    assertThat(misses).as("ways on which the owner misses her private library").isEmpty();

    enoughOwnersForExactFigures();
    for (String summary :
        List.of("/api/v1/admin/search/status", "/api/v1/admin/indexing/pipeline-versions")) {
      String body = body(as("dev-admin", get(summary)));
      assertThat(JsonPath.<Object>read(body, "$.privateLibraries.libraryCount"))
          .as(summary)
          .isNotNull();
      assertThat(JsonPath.<Object>read(body, "$.privateLibraries.libraryCountFewerThan"))
          .as(summary)
          .isNull();
    }
  }

  /** As many persons keep a private library on the profile as the minimum group size asks. */
  private void enoughOwnersForExactFigures() {
    for (int index = 1; index < groupSize.minimumGroupSize(); index++) {
      UUID person = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
              + " last_login_at) VALUES (?, ?, 'opaa-dev', ?, 'Kollegin', ?, now())",
          person,
          "kollegin-" + person,
          "kollegin-" + person + "@example.com",
          Organization.DEFAULT_ID);
      extraUsers.add(person);
      CurrentUser caller =
          CurrentUser.of(person, Organization.DEFAULT_ID, SystemRole.USER, "Kollegin");
      accounts.connect(caller, profile, "kollegin" + index, PASSWORD);
      UUID library = createPrivate("Ablage " + UUID.randomUUID(), caller);
      Map<String, Object> metadata = new LinkedHashMap<>();
      UUID document = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
              + " indexed_at, checksum, status, source_type, library_id, organization_id,"
              + " created_at) VALUES (?, 'notiz.pdf', 'notiz.pdf', 'application/pdf', 1, 1, now(),"
              + " ?, 'INDEXED', 'PERSON_PROBE', ?, ?, now())",
          document,
          "checksum-" + document,
          library,
          Organization.DEFAULT_ID);
      metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, document.toString());
      metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, library.toString());
      metadata.put("organization_id", Organization.DEFAULT_ID.toString());
      metadata.put(
          ChunkFormatMetadata.PIPELINE_ID_METADATA_KEY, ChunkFormatMetadata.LEGACY_PIPELINE_ID);
      metadata.put(ChunkFormatMetadata.PIPELINE_VERSION_METADATA_KEY, 0);
      chunks.addChunks(
          List.of(new org.springframework.ai.document.Document("Notiz " + index, metadata)));
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
        assertThat(answers.get("getPipelineVersionStatus").raw()).contains("privateLibraries");
        for (String counting :
            List.of(
                "reportOrphanedLibraries",
                "reindexPipelineBatch",
                "previewConnectionProfileChange",
                "updateConnectionProfile",
                "disconnectAllConnectionProfileConnections",
                "previewPermissionTransfer",
                "listConnectionProfiles")) {
          assertThat(answers.get(counting).status()).as(counting).isEqualTo(200);
        }
        // what the preview says saving discards is compared like every other number, and the new
        // address of the probe makes it count: every connection, the configuration, the accounts
        String preview = answers.get("previewConnectionProfileChange").raw();
        assertThat(preview).contains("\"secretsDiscarded\"", "\"fullSyncLibraries\"");
        assertThat(JsonPath.<Integer>read(preview, "$.connectionsDiscarded")).isPositive();
        assertThat(JsonPath.<Integer>read(preview, "$.configurationsChanged")).isPositive();
        assertThat(JsonPath.<Object>read(preview, "$.connectedAccountsEnded")).isNotNull();
        assertThat(JsonPath.<String>read(preview, "$.confirmation")).contains("Personen");
        assertThat(answers.get("deleteSpace").status()).isEqualTo(204);
      }
      case PERSON_CONTEXT ->
          assertThat(answers.get("runSearchDiagnosis").status())
              .as(answers.get("runSearchDiagnosis").raw())
              .isEqualTo(200);
      case SPACE_MEMBER, CURATOR, OTHER_OWNER -> {
        assertThat(answers.get("listSpaceAssetAssociations").status()).isEqualTo(200);
        assertThat(answers.get("getSpace").status()).isEqualTo(200);
      }
      case TOKEN -> {
        assertThat(answers.get("listSearchableLibraries").status()).isEqualTo(200);
        assertThat(answers.get("searchKnowledge").status()).isEqualTo(200);
      }
    }
  }

  /**
   * Deleting a space with her private library associated leaves the same protocol entry as deleting
   * one without: neither names nor counts the association.
   */
  private void theSpareSpacesAreDeletedAlike(Spare before, Spare after, Ids own) {
    String without = before.normalize(deletionEntry(before.id()));
    String with = after.normalize(deletionEntry(after.id()));
    assertThat(with).isEqualTo(without);
    for (String marker : own.markers(true)) {
      assertThat(with).doesNotContain(marker);
    }
  }

  /**
   * The re-index the administration started reached her document too, without reporting it: its
   * content lies with the provider, so it is marked for her library's next run (both change markers
   * cleared), which reads it again with her account.
   */
  private void theReindexAdvancedHerStaleDocument(Ids own) {
    assertThat(
            jdbc.queryForObject(
                "SELECT checksum IS NULL AND last_modified_remote IS NULL FROM documents"
                    + " WHERE id = ?",
                Boolean.class,
                own.document()))
        .isTrue();
  }

  private String deletionEntry(UUID spaceId) {
    List<String> entries =
        jdbc.queryForList(
            "SELECT coalesce(object_label, '') || coalesce(before, '') || coalesce(after, '')"
                + " FROM audit_log WHERE object_id = ? AND event_type = 'SPACE_DELETED'",
            String.class,
            spaceId.toString());
    assertThat(entries).hasSize(1);
    return INSTANT.matcher(entries.getFirst()).replaceAll("<instant>");
  }

  /** "Sicht als" the owner wrote its protocol entry without anything of her private library. */
  private void theDiagnosticProtocolNamesNothingOfHers(Ids own) {
    List<String> entries =
        jdbc.queryForList(
            "SELECT permission_snapshot || hit_refs FROM diagnostic_context_log"
                + " WHERE recorded_at >= ? AND test_question = ?",
            String.class,
            java.sql.Timestamp.from(startedAt),
            PERSON_CONTEXT_QUESTION);
    assertThat(entries).hasSize(2);
    for (String marker : own.markers(true)) {
      assertThat(entries).noneMatch(entry -> entry.contains(marker));
    }
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /**
   * Another person with a connected account on a profile for persons, a space she owns with the
   * observer and the administration among its members, and her chat in it.
   */
  private void aSpaceWithTheObserverAndAPersonWithAConnectedAccount(Observer observer)
      throws Exception {
    jdbc.update("UPDATE users SET system_role = ? WHERE id = ?", observer.devUserRole, devUser);
    if (devUserOwns) {
      owner = devUser;
      ownerCaller = devCaller;
    } else {
      owner = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
              + " last_login_at) VALUES (?, ?, 'opaa-dev', ?, 'Ilse Eigen', ?, now())",
          owner,
          "eigen-" + owner,
          "eigen-" + owner + "@example.com",
          Organization.DEFAULT_ID);
      ownerCaller = CurrentUser.of(owner, Organization.DEFAULT_ID, SystemRole.USER, "Ilse Eigen");
    }
    String profileBody =
        body(
            as("dev-admin", post("/api/v1/admin/connection-profiles"))
                .content(
                    """
                    {"name": "Zugang Personen %s", "sourceType": "PERSON_PROBE",
                     "serverUrl": "%s", "authMethod": "PERSONAL_SECRET", "ownership": "BOTH",
                     "connectorSettings": {"realm": "eins"}}
                    """
                        .formatted(UUID.randomUUID(), SERVER)));
    profile = UUID.fromString(JsonPath.read(profileBody, "$.id"));
    jdbc.update(
        "UPDATE connection_profiles SET name = ? WHERE id = ?",
        "Zugang Personen " + profile,
        profile);
    ConnectorReleases.releaseToAllAccounts(jdbc, "PROFILE:" + profile);
    profileDefaults =
        jdbc.queryForObject(
            "SELECT connector_settings FROM connection_profiles WHERE id = ?",
            String.class,
            profile);
    aSharedLibraryOfAColleague();
    accounts.connect(ownerCaller, profile, "ieigen", PASSWORD);
    space = aSpace("Raum " + UUID.randomUUID(), observer);
    chat = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        chat,
        space,
        owner,
        Organization.DEFAULT_ID);
    turn(1, "Was gilt für Urlaub?", "Dazu steht nichts in den Quellen.", null);
  }

  /**
   * A shared library of a colleague on the profile, without a secret of its own, so that a change
   * of the profile's default counts and waits for it in both phases alike.
   */
  private void aSharedLibraryOfAColleague() {
    UUID colleague = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id)"
            + " VALUES (?, ?, 'opaa-dev', ?, 'Kollegin', ?)",
        colleague,
        "kollegin-" + colleague,
        "kollegin-" + colleague + "@example.com",
        Organization.DEFAULT_ID);
    extraUsers.add(colleague);
    UUID shared =
        libraryService
            .createLibrary(
                new LibraryCreation(
                    "Geteilte Ablage",
                    null,
                    null,
                    null,
                    PersonProbeSourceConnector.TYPE,
                    null,
                    URI.create(SERVER + "/geteilt"),
                    null,
                    "geteilt:" + PASSWORD,
                    null,
                    null,
                    null,
                    profile),
                CurrentUser.of(colleague, Organization.DEFAULT_ID, SystemRole.USER, "Kollegin"))
            .library()
            .getId();
    libraries.add(shared);
    jdbc.update("UPDATE knowledge_libraries SET source_credentials = NULL WHERE id = ?", shared);
  }

  private UUID aSpace(String name, Observer observer) throws Exception {
    String observerMember =
        owner.equals(devUser)
            ? ""
            : "{\"subjectType\": \"USER\", \"subjectId\": \"%s\", \"role\": \"%s\"},"
                .formatted(devUser, observer.devUserSpaceRole);
    String spaceBody =
        body(
            as("dev-admin", post("/api/v1/spaces"))
                .content(
                    """
                    {"name": "%s", "ownerId": "%s", "initialMembers": [%s
                      {"subjectType": "USER", "subjectId": "%s", "role": "MEMBER"}]}
                    """
                        .formatted(name, owner, observerMember, admin)));
    UUID created = UUID.fromString(JsonPath.read(spaceBody, "$.id"));
    spaces.add(created);
    return created;
  }

  /** A space like hers that the probe of {@code deleteSpace} deletes, with her library or none. */
  private Spare aSpareSpace(Observer observer, UUID library) throws Exception {
    String name = "Ersatzraum " + UUID.randomUUID();
    UUID spare = aSpace(name, observer);
    if (library != null) {
      associations.associate(spare, KnowledgeLibrary.ASSET_TYPE, library, ownerCaller);
    }
    return new Spare(spare, name);
  }

  /**
   * Between the phases: the accounts the emergency shutdown cut are connected again, and the spare
   * space of the first phase no longer stands beside the second's.
   */
  private void theConnectionsAreBackAndTheSpareIsGone(Observer observer, Spare spare)
      throws Exception {
    jdbc.update(
        "UPDATE connection_profiles SET connector_settings = ? WHERE id = ?",
        profileDefaults,
        profile);
    if (observer == Observer.SYSTEM_ADMIN || observer == Observer.PERSON_CONTEXT) {
      accounts.connect(ownerCaller, profile, "ieigen", PASSWORD);
    } else {
      mockMvc
          .perform(as("dev-admin", delete("/api/v1/spaces/" + spare.id())))
          .andExpect(status().isNoContent());
    }
  }

  /** What the observer holds of her own: a befugnis, a private library or a token. */
  private void theObserversOwnStanding(Observer observer) {
    switch (observer) {
      case PERSON_CONTEXT -> aBefugnisOverAGroupWithTheOwner();
      case OTHER_OWNER -> {
        accounts.connect(devCaller, profile, "devuser", PASSWORD);
        createPrivate("Eigene Ablage " + UUID.randomUUID(), devCaller);
      }
      case TOKEN -> aTokenForASharedLibrary();
      default -> {}
    }
  }

  private void aBefugnisOverAGroupWithTheOwner() {
    provider = ProviderFixtures.tokenProvider(providers).getId();
    group =
        groups
            .save(
                new Group(
                    Organization.DEFAULT_ID,
                    GroupKind.ORG_UNIT,
                    "Personalstelle " + UUID.randomUUID(),
                    null,
                    provider,
                    null,
                    null,
                    null))
            .getId();
    List<UUID> members = new ArrayList<>(List.of(owner));
    for (int index = 1; index < groupSize.minimumGroupSize(); index++) {
      UUID extra = UUID.randomUUID();
      jdbc.update(
          "INSERT INTO users (id, subject, issuer, email, display_name, organization_id)"
              + " VALUES (?, ?, 'opaa-dev', ?, 'Kollegin', ?)",
          extra,
          "kollegin-" + extra,
          "kollegin-" + extra + "@example.com",
          Organization.DEFAULT_ID);
      extraUsers.add(extra);
      members.add(extra);
    }
    for (UUID member : members) {
      jdbc.update(
          "INSERT INTO group_memberships (id, user_id, group_id, organization_id, created_at)"
              + " VALUES (?, ?, ?, ?, now())",
          UUID.randomUUID(),
          member,
          group,
          Organization.DEFAULT_ID);
    }
    memberships.invalidateUsers(members);
    Instant now = Instant.now();
    befugnisse.save(
        new DiagnosticImpersonationGrant(
            Organization.DEFAULT_ID,
            admin,
            group,
            now.minus(1, ChronoUnit.HOURS),
            now.plus(30, ChronoUnit.DAYS),
            admin,
            now));
  }

  private void aTokenForASharedLibrary() {
    ExternalAccessSettings.Values values = externalSettings.current().values();
    externalSettings.update(
        adminCaller,
        new ExternalAccessSettingsService.Update(
            true,
            values.tokenMaxLifetimeDays(),
            1000,
            values.allowedCidrs(),
            values.massRetrievalAlertThreshold(),
            values.serverInstructions()));
    UUID shared = UUID.randomUUID();
    jdbc.update(
        "WITH shell AS (INSERT INTO assets (id, asset_type, organization_id, name, owner_type,"
            + " owner_user_id) VALUES (?, 'KNOWLEDGE_LIBRARY', ?, 'Freigegebene Ablage', 'USER', ?)"
            + " RETURNING id, organization_id) INSERT INTO knowledge_libraries (id,"
            + " organization_id, source_type) SELECT id, organization_id, 'UPLOAD' FROM shell",
        shared,
        Organization.DEFAULT_ID,
        devUser);
    libraries.add(shared);
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_type, asset_id, organization_id, subject_type,"
            + " subject_user_id, role, created_at, updated_at) VALUES (?, 'KNOWLEDGE_LIBRARY', ?,"
            + " ?, 'USER', ?, 'OWNER', now(), now())",
        UUID.randomUUID(),
        shared,
        Organization.DEFAULT_ID,
        devUser);
    Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
    externalRelease.setExternalAccess(devCaller, shared, true, expires);
    token =
        tokens
            .issue(devUser, Organization.DEFAULT_ID, "Prüftoken", List.of(shared), expires)
            .rawValue();
  }

  /**
   * Her two private libraries as after a real run - one on her account, one released from its
   * profile: a document with an indexed chunk in both stores, a metadata field with a value, a
   * folder, a failed run with its events, her chat citing the document; the first renamed and
   * associated with the space.
   */
  private Ids herPrivateLibraries() {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID library = createPrivate("Geheimablage " + suffix, ownerCaller);
    UUID detached = createPrivate("Zweitablage " + suffix, ownerCaller);
    jdbc.update("UPDATE library_connections SET profile_id = NULL WHERE library_id = ?", detached);
    jdbc.update(
        "UPDATE knowledge_libraries SET erasure_requested_at = now(), erasure_cause ="
            + " 'OWNER_REQUEST' WHERE id = ?",
        detached);
    String name = "Personalakte " + suffix;
    libraryService.updateLibrary(
        library,
        new LibraryUpdate(name, "Notiz " + suffix, null, null, null, null, null, null, null, null),
        ownerCaller);
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
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM chunk_full_text WHERE chunk_id = ?", Integer.class, chunk))
        .isEqualTo(1);
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
            + " error_message, failure_category, documents_total, documents_failed, completed_at)"
            + " VALUES (?, 'FAILED', now(), ?, ?, ?, 'NOT_CONNECTED', 2, 1, now())",
        job,
        Organization.DEFAULT_ID,
        library,
        "Verbindung getrennt " + fileName);
    jdbc.update(
        "INSERT INTO indexing_run_events (id, job_id, category, message, reference)"
            + " VALUES (?, ?, 'ERROR', ?, ?)",
        UUID.randomUUID(),
        job,
        "Nicht lesbar: " + fileName,
        path);
    jdbc.update(
        "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id, library_id)"
            + " VALUES (?, 'RUNNING', now(), ?, ?)",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        library);
    jdbc.update(
        "INSERT INTO indexing_jobs (id, status, last_progress_at, organization_id, library_id,"
            + " failure_category, incomplete, started_at, completed_at) VALUES (?, 'COMPLETED',"
            + " now(), ?, ?, 'QUOTA_EXHAUSTED', true, now() + interval '1 minute', now())",
        UUID.randomUUID(),
        Organization.DEFAULT_ID,
        library);
    associations.associate(space, KnowledgeLibrary.ASSET_TYPE, library, ownerCaller);
    turn(
        3,
        "Wie hoch ist mein Gehalt?",
        "Laut Abrechnung: " + content,
        "[{\"fileName\": \"%s\", \"documentId\": \"%s\", \"relevanceScore\": 0.9,"
                .formatted(fileName, document)
            + " \"matchCount\": 1, \"cited\": true, \"sourceType\": \"PERSON_PROBE\","
            + " \"privateSource\": true}]");
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
            "lohn/" + suffix,
            content,
            folderName,
            fieldLabel,
            value));
  }

  /** One question and answer of her chat, the answer citing {@code sources} or nothing. */
  private void turn(int sequence, String question, String answer, String sources) {
    jdbc.update(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content) VALUES (?, ?, ?, 'USER',"
            + " ?)",
        UUID.randomUUID(),
        chat,
        sequence,
        question);
    jdbc.update(
        "INSERT INTO chat_messages (id, chat_id, sequence, role, content, sources)"
            + " VALUES (?, ?, ?, 'ASSISTANT', ?, ?::json)",
        UUID.randomUUID(),
        chat,
        sequence + 1,
        answer,
        sources);
  }

  private UUID createPrivate(String name, CurrentUser creator) {
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
            creator);
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

    /** Positions in {@code names}, as {@link #herPrivateLibraries} lists them. */
    static final int NAME = 0;

    static final int FILE_NAME = 4;
    static final int CONTENT = 6;
    static final int FOLDER = 7;
    static final int VALUE = 9;

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

    String fill(String template, Map<String, UUID> fixture) {
      String filled =
          template
              .replace("{library}", library.toString())
              .replace("{detached}", detached.toString())
              .replace("{document}", document.toString())
              .replace("{chunk}", chunk.toString())
              .replace("{folder}", folder.toString())
              .replace("{unknown}", UNKNOWN.toString())
              .replace("{expires}", Instant.now().plus(30, ChronoUnit.DAYS).toString());
      for (Map.Entry<String, UUID> entry : fixture.entrySet()) {
        filled = filled.replace("{" + entry.getKey() + "}", entry.getValue().toString());
      }
      return filled;
    }

    String normalize(String body) {
      String normalized =
          INSTANT
              .matcher(
                  body.replace(library.toString(), "<library>")
                      .replace(detached.toString(), "<detached>")
                      .replace(document.toString(), "<document>")
                      .replace(chunk.toString(), "<chunk>")
                      .replace(folder.toString(), "<folder>"))
              .replaceAll("<instant>");
      return PREVIEW_ID.matcher(normalized).replaceAll("\"previewId\":\"<preview>\"");
    }
  }

  /** The space the probe of {@code deleteSpace} deletes in one phase. */
  record Spare(UUID id, String name) {
    String normalize(String body) {
      return body.replace(id.toString(), "<spare>").replace(name, "<spare-name>");
    }
  }

  /** One answer: status and body as sent. */
  record Answer(int status, String raw) {
    String normalized(Ids ids) {
      return ids.normalize(raw);
    }
  }

  private Answer call(Operation operation, Probe probe, Ids ids, Observer observer, Spare spare)
      throws Exception {
    Map<String, UUID> fixture = new LinkedHashMap<>();
    fixture.put("space", space);
    fixture.put("spare", spare.id());
    fixture.put("profile", profile);
    fixture.put("owner", owner);
    fixture.put("admin", admin);
    String path = operation.path();
    for (Parameter parameter : operation.parameters()) {
      if (parameter.in().equals("path")) {
        String value = probe.query().get(parameter.name());
        path =
            path.replace(
                "{" + parameter.name() + "}",
                value != null ? ids.fill(value, fixture) : valueOf(parameter, ids));
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
        request.queryParam(parameter.name(), ids.fill(value, fixture));
      }
    }
    String body = probe.body();
    if (observer == Observer.PERSON_CONTEXT && operation.id().equals("runSearchDiagnosis")) {
      body = PERSON_CONTEXT_DIAGNOSIS;
    }
    if (body != null) {
      request.content(ids.fill(body, fixture));
    } else if (operation.body() && !probe.multipart()) {
      request.content("{}");
    }
    if (observer == Observer.TOKEN) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    } else {
      request.header(DevAuthFilter.DEV_USER_HEADER, observer.devUser);
    }
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
      case "chunkId", "hitId" -> ids.chunk().toString();
      case "folderId" -> ids.folder().toString();
      case "spaceId" -> space.toString();
      case "chatId" -> chat.toString();
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
        if (method.equals("get") || names || COUNTING.contains(operation.get("operationId"))) {
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

  /**
   * A top-level JSON array with its elements sorted: lists without a declared order come in the
   * order the database returns, which any write to a row may change.
   */
  private static String unordered(String body) {
    if (!body.startsWith("[")) {
      return body;
    }
    try {
      List<String> elements = new ArrayList<>();
      for (tools.jackson.databind.JsonNode element : JSON.readTree(body)) {
        elements.add(element.toString());
      }
      java.util.Collections.sort(elements);
      return elements.toString();
    } catch (RuntimeException e) {
      return body;
    }
  }

  /** {@code text} around where it first differs from {@code other}. */
  private static String around(String text, String other) {
    int at = 0;
    while (at < text.length() && at < other.length() && text.charAt(at) == other.charAt(at)) {
      at++;
    }
    int from = Math.max(0, at - 150);
    return (from > 0 ? "…" : "")
        + text.substring(from, Math.min(text.length(), at + 150))
        + (at + 150 < text.length() ? "…" : "");
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
