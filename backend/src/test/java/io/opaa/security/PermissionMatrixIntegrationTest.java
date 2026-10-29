package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.format.ChunkFormatMetadata;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
import io.opaa.indexing.chunk.VectorChunkStore;
import io.opaa.instance.InvalidationSubscriber;
import io.opaa.knowledge.KnowledgeLibrary;
import io.opaa.knowledge.LibraryFolderService;
import io.opaa.knowledge.SourceType;
import io.opaa.knowledge.UploadedOriginalRef;
import io.opaa.knowledge.UploadedOriginalStore;
import io.opaa.library.KnowledgeLibraryService;
import io.opaa.library.LibraryCreationBuilder;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.prompt.PromptContent;
import io.opaa.prompt.PromptLibrary;
import io.opaa.prompt.PromptLibraryCreation;
import io.opaa.prompt.PromptLibraryService;
import io.opaa.prompt.PromptService;
import io.opaa.security.ApiOperations.Operation;
import io.opaa.security.PermissionMatrix.Outcome;
import io.opaa.security.PermissionMatrix.Role;
import io.opaa.security.RolledBackRequests.Answer;
import io.opaa.space.SpaceAssetAssociationService;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceMemberSeed;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnLibraryFixtures;
import io.opaa.test.OwnOrganizationFixtures;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Holds every operation to {@link PermissionMatrix} for every signed-in role, and refuses every
 * path id of a foreign space, person or organization - also as the child of a parent the caller may
 * manage. Requests run through {@link RolledBackRequests}; observations land in {@code
 * build/permission-matrix/}.
 */
@OpaaIntegrationTest
class PermissionMatrixIntegrationTest {

  private static final ApiOperations API = ApiOperations.bundled();
  private static final PermissionMatrix MATRIX = PermissionMatrix.load();
  private static final Path OBSERVED = Path.of("build", "permission-matrix");

  /** Path parameters naming an object inside one space. */
  private static final Set<String> SPACE_SCOPED =
      Set.of(
          "spaceId",
          "libraryId",
          "assetId",
          "chatId",
          "itemId",
          "folderId",
          "documentId",
          "chunkId",
          "hitId",
          "fieldKey",
          "code",
          "grantId",
          "membershipId",
          "promptLibraryId",
          "promptId");

  /** Path parameters naming an object of one person. */
  private static final Set<String> PERSON_SCOPED = Set.of("tokenId", "notificationId");

  /** Path parameters naming an object of one organization outside any space. */
  private static final Set<String> ORGANIZATION_SCOPED =
      Set.of("groupId", "userId", "id", "eventId", "scopeId");

  /** The lists and searches a person without rights must not find the space's objects in. */
  private static final Set<String> LISTINGS =
      Set.of(
          "listSpaces",
          "listLibraries",
          "listCatalog",
          "listPromptLibraries",
          "listSearchableLibraries",
          "listAvailablePrompts",
          "searchKnowledge",
          "getMetadataFilterOptions");

  /** The status each signed-in role met, by operation, across the role runs of this class. */
  private static final Map<String, Map<Role, Integer>> SIGNED_IN_STATUSES =
      new ConcurrentHashMap<>();

  /** Lists over the caller's whole organization, for the administration and the auditor. */
  private static final Set<String> ORGANIZATION_LISTINGS =
      Set.of(
          "listAllExternalAccessTokens",
          "listGroups",
          "listUsers",
          "listAccounts",
          "listUsersForSelection");

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private List<InvalidationSubscriber> caches;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceAssetAssociationService associations;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private LibraryFolderService folderService;
  @Autowired private PromptLibraryService promptLibraryService;
  @Autowired private PromptService promptService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private VectorChunkStore chunks;
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private OwnOrganizationFixtures organizationFixtures;
  @Autowired private UploadedOriginalStore originals;

  private UUID devUser;
  private UUID admin;
  private UUID foreignOrganization;
  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> spaces = new ArrayList<>();
  private final Map<UUID, UUID> libraries = new LinkedHashMap<>();
  private final List<UUID> promptLibraries = new ArrayList<>();
  private final List<UUID> groups = new ArrayList<>();

  @BeforeEach
  void provisionTheDevUsers() throws Exception {
    mockMvc.perform(as("dev-user", get("/api/v1/spaces"))).andExpect(status().isOk());
    mockMvc.perform(as("dev-admin", get("/api/v1/spaces"))).andExpect(status().isOk());
    devUser = userIdOf("dev-user@opaa.local");
    admin = userIdOf("admin@opaa.local");
  }

  @AfterEach
  void removeOwnRows() {
    jdbc.update("UPDATE users SET system_role = 'USER' WHERE id = ?", devUser);
    for (UUID space : spaces) {
      jdbc.update("DELETE FROM chats WHERE space_id = ?", space);
      jdbc.update("DELETE FROM space_asset_associations WHERE space_id = ?", space);
      jdbc.update("DELETE FROM space_memberships WHERE space_id = ?", space);
      jdbc.update("DELETE FROM spaces WHERE id = ?", space);
      jdbc.update("DELETE FROM space_membership_history WHERE space_id = ?", space);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", space);
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", space.toString());
    }
    spaces.clear();
    for (UUID library : promptLibraries) {
      jdbc.update("DELETE FROM prompts WHERE library_id = ?", library);
      jdbc.update("DELETE FROM prompt_libraries WHERE id = ?", library);
      jdbc.update("DELETE FROM assets WHERE id = ?", library);
    }
    List<UUID> assets = new ArrayList<>(libraries.keySet());
    assets.addAll(promptLibraries);
    for (UUID asset : assets) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", asset.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", asset);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", asset);
      jdbc.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", asset);
      jdbc.update("DELETE FROM asset_favorites WHERE asset_id = ?", asset);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", asset);
    }
    promptLibraries.clear();
    libraryFixtures.removeLibraries(libraries.keySet().toArray(UUID[]::new));
    // An upload stores its original outside the transaction that rolled back.
    libraries.forEach(
        (library, organization) -> {
          List<String> stored = new ArrayList<>();
          originals.forEachStoredOriginal(
              organization, library, original -> stored.add(original.locator()));
          stored.forEach(
              locator -> originals.delete(new UploadedOriginalRef(organization, library, locator)));
        });
    libraries.clear();
    for (UUID group : groups) {
      jdbc.update("DELETE FROM group_stewards WHERE group_id = ?", group);
      jdbc.update("DELETE FROM group_memberships WHERE group_id = ?", group);
      jdbc.update("DELETE FROM group_membership_history WHERE group_id = ?", group);
      jdbc.update("DELETE FROM groups WHERE id = ?", group);
    }
    groups.clear();
    for (UUID user : users) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", user.toString());
      jdbc.update("DELETE FROM notifications WHERE recipient_user_id = ?", user);
      jdbc.update("DELETE FROM asset_ownership_history WHERE owner_user_id = ?", user);
      jdbc.update("DELETE FROM users WHERE id = ?", user);
    }
    users.clear();
    if (foreignOrganization != null) {
      organizationFixtures.removeOrganizations(foreignOrganization);
      foreignOrganization = null;
    }
  }

  @ParameterizedTest
  @EnumSource(value = Role.class, names = "ANONYMOUS", mode = EnumSource.Mode.EXCLUDE)
  void everyOperationAnswersTheRoleAsTheMatrixSays(Role role) throws IOException {
    Fixture own = ownFixture(role);
    Map<String, Answer> answers = run(API.operations(), role, own);

    List<String> findings = new ArrayList<>();
    StringBuilder observed = new StringBuilder();
    for (Operation operation : API.operations()) {
      int status = answers.get(operation.id()).status();
      Outcome outcome = Outcome.of(status);
      observed.append(
          "%s: %s # %s %d%n"
              .formatted(
                  operation.id(), outcome.key, PermissionMatrix.statusClass(status), status));
      PermissionMatrix.Entry entry = MATRIX.entry(operation.id());
      String serverError = MATRIX.serverErrorOf(operation, status);
      Map<Role, Integer> byRole =
          SIGNED_IN_STATUSES.computeIfAbsent(operation.id(), id -> new ConcurrentHashMap<>());
      byRole.put(role, status);
      String sameClientError = MATRIX.sameClientErrorOf(operation, byRole);
      if (sameClientError != null) {
        findings.add(sameClientError);
      }
      if (serverError != null) {
        findings.add(serverError);
      } else if (entry != null && entry.expected().get(role) != outcome) {
        findings.add(
            "%s: %s expects '%s', observed '%s' (status %d)"
                .formatted(
                    operation.label(),
                    role.key,
                    entry.expected().get(role).key,
                    outcome.key,
                    status));
      }
    }
    write("observed-" + role.key + ".yaml", observed);
    assertThat(findings)
        .as("operations whose outcome for %s departs from %s", role.key, PermissionMatrix.FILE)
        .isEmpty();
  }

  @Test
  void aSpaceAdministratorReachesNothingOfAForeignSpaceOrPerson() throws IOException {
    ownFixture(Role.SPACE_ADMIN);
    Fixture foreign = spaceFixture(Organization.DEFAULT_ID, null, null);

    Set<String> scoped = new TreeSet<>(SPACE_SCOPED);
    scoped.addAll(PERSON_SCOPED);
    assertRefused("foreign-space", Role.SPACE_ADMIN, foreign, naming(scoped), foreign);
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"SYSTEM_ADMIN", "AUDITOR"})
  void theAdministrationAndTheAuditorReachNothingOfAForeignOrganization(Role role)
      throws IOException {
    foreignOrganization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Fremde Stelle " + UUID.randomUUID()))
            .getId();
    Fixture foreign = spaceFixture(foreignOrganization, null, null);
    if (role == Role.AUDITOR) {
      jdbc.update("UPDATE users SET system_role = 'AUDITOR' WHERE id = ?", devUser);
    }

    Set<String> scoped = new TreeSet<>(SPACE_SCOPED);
    scoped.addAll(PERSON_SCOPED);
    scoped.addAll(ORGANIZATION_SCOPED);
    assertRefused("foreign-organization-" + role.key, role, foreign, naming(scoped), foreign);
  }

  /**
   * The child of a parent the space administrator manages - with the manager's right on its
   * libraries - comes from a foreign space, one path id at a time: the operation must check that
   * the child belongs to the parent, not only that the parent may be reached.
   */
  @Test
  void aForeignChildIsNotReachedThroughAnOwnParent() throws IOException {
    Fixture own = ownFixture(Role.SPACE_ADMIN);
    Fixture foreign = spaceFixture(Organization.DEFAULT_ID, null, null);
    Set<String> scoped = new TreeSet<>(SPACE_SCOPED);
    scoped.addAll(PERSON_SCOPED);
    scoped.addAll(ORGANIZATION_SCOPED);

    List<String> findings = new ArrayList<>();
    StringBuilder observed = new StringBuilder();
    for (Operation operation : API.operations()) {
      List<String> named =
          operation.in("path").stream()
              .map(ApiOperations.Parameter::name)
              .filter(scoped::contains)
              .toList();
      if (named.size() < 2) {
        continue;
      }
      for (String swapped : named) {
        Map<String, String> ids = new LinkedHashMap<>(own.ids());
        ids.put(swapped, foreign.ids().get(swapped));
        Fixture mixed = new Fixture(ids, own.seed().andThen(foreign.seed()), own.markers());
        int status = run(List.of(operation), Role.SPACE_ADMIN, mixed).get(operation.id()).status();
        observed.append("%s[%s]: %d%n".formatted(operation.id(), swapped, status));
        PermissionMatrix.Entry entry = MATRIX.entry(operation.id());
        boolean excepted = entry != null && entry.foreignChild().contains(swapped);
        if (status != 401 && status != 403 && status != 404 && !(excepted && status < 500)) {
          findings.add(
              "%s answers %d with a foreign %s under an own parent"
                  .formatted(operation.label(), status, swapped));
        }
      }
    }
    write("observed-foreign-child.txt", observed);
    assertThat(observed).isNotEmpty();
    assertThat(findings).as("operations reaching a foreign child").isEmpty();
  }

  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"SPACE_ADMIN", "CURATOR", "MEMBER"})
  void noMemberReachesAnotherMembersChat(Role role) throws IOException {
    Fixture own = ownFixture(role);
    Map<String, String> ids = new LinkedHashMap<>(own.ids());
    ids.put("chatId", own.ids().get("otherChatId"));
    ids.put("itemId", own.ids().get("otherItemId"));

    assertRefused(
        "other-chat-" + role.key,
        role,
        new Fixture(ids, own.seed(), own.markers()),
        naming(Set.of("chatId")),
        null);
  }

  /**
   * A status shows no leak in a list or a search; the space's objects must not appear in the
   * answers of an account without rights, and appear in those of a member.
   */
  @Test
  void anAccountWithoutRightsFindsNothingOfTheSpaceInListsAndSearches() throws IOException {
    Fixture own = ownFixture(Role.NO_RIGHTS);
    List<Operation> listings =
        API.operations().stream().filter(operation -> LISTINGS.contains(operation.id())).toList();
    assertThat(listings).hasSize(LISTINGS.size());

    Map<String, Answer> answers = run(listings, Role.NO_RIGHTS, own);

    List<String> findings = new ArrayList<>();
    for (Operation operation : listings) {
      Answer answer = answers.get(operation.id());
      for (String marker : own.markers()) {
        if (answer.body().contains(marker)) {
          findings.add(operation.label() + " names " + marker);
        }
      }
    }
    assertThat(findings).as("what an account without rights finds").isEmpty();

    jdbc.update(
        "INSERT INTO space_memberships (id, user_id, space_id, role, organization_id,"
            + " subject_type) VALUES (?, ?, ?, 'MEMBER', ?, 'USER')",
        UUID.randomUUID(),
        devUser,
        UUID.fromString(own.ids().get("spaceId")),
        Organization.DEFAULT_ID);
    grant(UUID.fromString(own.ids().get("libraryId")), "KNOWLEDGE_LIBRARY", "VIEWER");
    Map<String, Answer> asMember = run(listings, Role.MEMBER, own);
    assertThat(asMember.get("listSpaces").body()).contains(own.ids().get("spaceId"));
    assertThat(asMember.get("listLibraries").body()).contains(own.ids().get("libraryId"));
    assertThat(asMember.get("searchKnowledge").body()).contains(own.ids().get("chunkId"));
  }

  /**
   * The administration and the auditor of one organization find nothing of another in the lists,
   * the search and the organization-wide administration lists.
   */
  @ParameterizedTest
  @EnumSource(
      value = Role.class,
      names = {"SYSTEM_ADMIN", "AUDITOR"})
  void theAdministrationFindsNothingOfAForeignOrganizationInLists(Role role) {
    foreignOrganization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Fremde Stelle " + UUID.randomUUID()))
            .getId();
    Fixture foreign = spaceFixture(foreignOrganization, null, null);
    if (role == Role.AUDITOR) {
      jdbc.update("UPDATE users SET system_role = 'AUDITOR' WHERE id = ?", devUser);
    }
    Set<String> lists = new TreeSet<>(LISTINGS);
    lists.addAll(ORGANIZATION_LISTINGS);
    List<Operation> listings =
        API.operations().stream().filter(operation -> lists.contains(operation.id())).toList();
    assertThat(listings).hasSize(lists.size());
    List<String> markers = new ArrayList<>(foreign.markers());
    for (String name : List.of("tokenId", "userId", "groupId")) {
      markers.add(foreign.ids().get(name));
    }

    Map<String, Answer> answers = run(listings, role, foreign);

    List<String> findings = new ArrayList<>();
    for (Operation operation : listings) {
      for (String marker : markers) {
        if (answers.get(operation.id()).body().contains(marker)) {
          findings.add(operation.label() + " names " + marker);
        }
      }
    }
    assertThat(findings).as("what %s finds of a foreign organization", role.key).isEmpty();
  }

  private void assertRefused(
      String run, Role role, Fixture ids, List<Operation> operations, Fixture foreign)
      throws IOException {
    Map<String, Answer> answers = run(operations, role, ids);
    List<String> findings = new ArrayList<>();
    StringBuilder observed = new StringBuilder();
    for (Operation operation : operations) {
      int status = answers.get(operation.id()).status();
      observed.append("%s: %d%n".formatted(operation.id(), status));
      PermissionMatrix.Entry entry = MATRIX.entry(operation.id());
      boolean refused = status == 401 || status == 403 || status == 404;
      boolean excepted = foreign != null && entry != null && entry.foreignAllowed();
      if (!refused && !excepted) {
        findings.add("%s answers %d".formatted(operation.label(), status));
      }
    }
    write("observed-" + run + ".txt", observed);
    assertThat(operations).isNotEmpty();
    assertThat(findings).as("operations that do not refuse a foreign id (%s)", run).isEmpty();
  }

  private static List<Operation> naming(Set<String> scoped) {
    return API.operations().stream()
        .filter(
            operation ->
                operation.in("path").stream()
                    .anyMatch(parameter -> scoped.contains(parameter.name())))
        .toList();
  }

  private Map<String, Answer> run(List<Operation> operations, Role role, Fixture fixture) {
    ProbeRequests probes = new ProbeRequests(API, fixture.ids());
    String user = role == Role.SYSTEM_ADMIN ? "dev-admin" : "dev-user";
    return new RolledBackRequests(mockMvc, transactionManager, jdbc, caches)
        .answers(operations, operation -> as(user, probes.build(operation)), fixture.seed());
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /**
   * The ids a run's requests use, by the name of the parameter or property that carries them; the
   * rows {@code seed} writes inside each rolled-back request; names that only the fixture uses.
   */
  record Fixture(Map<String, String> ids, Consumer<JdbcTemplate> seed, List<String> markers) {}

  /**
   * The role's space in the dev users' organization: the dev user holds the role's space role in it
   * and the matching right on its libraries, or neither.
   */
  private Fixture ownFixture(Role role) {
    if (role == Role.AUDITOR) {
      jdbc.update("UPDATE users SET system_role = 'AUDITOR' WHERE id = ?", devUser);
    }
    SpaceRole spaceRole =
        switch (role) {
          case SPACE_ADMIN -> SpaceRole.ADMIN;
          case CURATOR -> SpaceRole.CURATOR;
          case MEMBER -> SpaceRole.MEMBER;
          default -> null;
        };
    String assetRole =
        switch (role) {
          case SPACE_ADMIN -> "MANAGER";
          case CURATOR -> "EDITOR";
          case MEMBER -> "VIEWER";
          default -> null;
        };
    UUID person = role == Role.SYSTEM_ADMIN ? admin : devUser;
    return spaceFixture(Organization.DEFAULT_ID, spaceRole, assetRole, person);
  }

  private Fixture spaceFixture(UUID organization, SpaceRole devUserRole, String devUserAssetRole) {
    return spaceFixture(organization, devUserRole, devUserAssetRole, null);
  }

  /**
   * A space of a third person with her knowledge library (a folder, a document with a chunk, a
   * selection field with one value), her prompt library with a prompt, a chat of the dev user (if a
   * member, else hers) and one of a second member, a group with a grant on the library, and - for
   * {@code person}, else for the owner - a notification and an access token.
   */
  private Fixture spaceFixture(
      UUID organization, SpaceRole devUserRole, String devUserAssetRole, UUID person) {
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    UUID owner = aUser(organization, "Ilse Eigen " + suffix);
    UUID colleague = aUser(organization, "Nachfolge " + suffix);
    CurrentUser ownerCaller = CurrentUser.of(owner, organization, SystemRole.SYSTEM_ADMIN, "Eigen");
    List<SpaceMemberSeed> members = new ArrayList<>();
    members.add(SpaceMemberSeed.user(colleague, SpaceRole.MEMBER));
    if (devUserRole != null) {
      members.add(SpaceMemberSeed.user(devUser, devUserRole));
    }
    String spaceName = "Matrix-Raum " + suffix;
    UUID space =
        spaceService
            .createSpace(new SpaceCreation(spaceName, null, owner, members, List.of()), ownerCaller)
            .getId();
    spaces.add(space);

    String libraryName = "Matrix-Wissen " + suffix;
    UUID library =
        libraryService
            .createLibrary(
                LibraryCreationBuilder.libraryCreation(libraryName, SourceType.UPLOAD).build(),
                ownerCaller)
            .library()
            .getId();
    libraries.put(library, organization);
    associations.associate(space, KnowledgeLibrary.ASSET_TYPE, library, ownerCaller);
    UUID folder =
        folderService.createFolder(library, "Ablage " + suffix, null, ownerCaller).folder().getId();
    UUID document = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO documents (id, file_name, file_path, content_type, file_size, chunk_count,"
            + " indexed_at, checksum, status, source_type, library_id, organization_id, created_at)"
            + " VALUES (?, ?, ?, 'text/plain', 64, 1, now(), ?, 'INDEXED', 'UPLOAD', ?, ?, now())",
        document,
        "matrix-" + suffix + ".txt",
        "matrix/" + suffix + ".txt",
        "checksum-" + document,
        library,
        organization);
    String content = "Matrixinhalt " + suffix;
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put(VectorChunkStore.DOCUMENT_ID_METADATA_KEY, document.toString());
    metadata.put(VectorChunkStore.LIBRARY_ID_METADATA_KEY, library.toString());
    metadata.put("organization_id", organization.toString());
    metadata.put("file_name", "matrix-" + suffix + ".txt");
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
    String fieldKey = "matrix_" + suffix.replace('-', '_');
    UUID field = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO library_metadata_fields (id, library_id, field_key, label, field_type,"
            + " filter_enabled, sort_order, created_at, updated_at)"
            + " VALUES (?, ?, ?, ?, 'SELECT', true, 10, now(), now())",
        field,
        library,
        fieldKey,
        "Matrixfeld " + suffix);
    String code = "wert-" + suffix;
    jdbc.update(
        "INSERT INTO library_metadata_field_values (id, field_id, code, label, sort_order)"
            + " VALUES (?, ?, ?, 'Wert', 0)",
        UUID.randomUUID(),
        field,
        code);

    UUID promptLibrary =
        promptLibraryService
            .create(
                new PromptLibraryCreation("Matrix-Prompts " + suffix, null, null, null),
                ownerCaller)
            .library()
            .getId();
    promptLibraries.add(promptLibrary);
    associations.associate(space, PromptLibrary.ASSET_TYPE, promptLibrary, ownerCaller);
    UUID prompt =
        promptService
            .create(
                promptLibrary,
                new PromptContent("probe", "Probe", null, "Ein Text.", List.of(), 0),
                ownerCaller)
            .getId();

    UUID chat = aChat(space, devUserRole != null ? devUser : owner, organization);
    UUID otherChat = aChat(space, colleague, organization);
    UUID group = aGroup(organization, owner);
    UUID grant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_id, organization_id, subject_type, subject_group_id,"
            + " role, granted_by_user_id, asset_type) VALUES (?, ?, ?, 'GROUP', ?, 'VIEWER', ?,"
            + " 'KNOWLEDGE_LIBRARY')",
        grant,
        library,
        organization,
        group,
        owner);
    if (devUserAssetRole != null) {
      grant(library, "KNOWLEDGE_LIBRARY", devUserAssetRole);
      grant(promptLibrary, "PROMPT_LIBRARY", devUserAssetRole);
    }
    UUID membership =
        jdbc.queryForObject(
            "SELECT id FROM space_memberships WHERE space_id = ? AND user_id = ?",
            UUID.class,
            space,
            colleague);

    UUID recipient = person != null ? person : owner;
    UUID item = UUID.randomUUID();
    UUID otherItem = UUID.randomUUID();
    UUID notification = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    UUID scope = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    UUID profile = UUID.randomUUID();
    UUID oauthProfile = UUID.randomUUID();
    Consumer<JdbcTemplate> seed =
        rows -> {
          noteItem(rows, item, chat, organization);
          noteItem(rows, otherItem, otherChat, organization);
          rows.update(
              "INSERT INTO notifications (id, organization_id, recipient_user_id, type, title)"
                  + " VALUES (?, ?, ?, 'GROUP_MEMBER_ADDED', 'Probe')",
              notification,
              organization,
              recipient);
          rows.update(
              "INSERT INTO external_access_tokens (id, user_id, name, token_prefix,"
                  + " token_lookup_hash, expires_at) VALUES (?, ?, 'Probe', 'opaa_probe', ?, ?)",
              token,
              recipient,
              HexFormat.of().formatHex(token.toString().getBytes()).substring(0, 64),
              Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)));
          rows.update(
              "INSERT INTO audit_incident_scope_grants (id, organization_id, subject_user_id,"
                  + " scope_start, scope_end, purpose, reason, requested_by_user_id,"
                  + " requested_at, status) VALUES (?, ?, ?, now() - interval '1 day', now(),"
                  + " 'SECURITY_INCIDENT', 'Probe', ?, now(), 'PENDING')",
              scope,
              organization,
              owner,
              owner);
          rows.update(
              "INSERT INTO diagnostic_context_log (event_id, recorded_at, organization_id,"
                  + " actor_ref, target_kind, target_ref, test_question, hit_count, hit_refs,"
                  + " permission_snapshot) VALUES (?, now(), ?, 'probe', 'PERMISSION_PROFILE',"
                  + " 'probe', 'Probe', 0, '[]', '{}')",
              event,
              organization);
          rows.update(
              "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
                  + " ownership, created_at, updated_at, version) VALUES (?, ?, 'PERSON_PROBE',"
                  + " 'https://person.example.org', 'PERSONAL_SECRET', 'BOTH', now(), now(), 0)",
              profile,
              "Matrix-Zugang " + profile);
          rows.update(
              "INSERT INTO connection_profiles (id, name, source_type, server_url, auth_method,"
                  + " ownership, created_at, updated_at, version) VALUES (?, ?, 'DROPBOX',"
                  + " 'https://api.dropboxapi.com', 'OAUTH', 'LIBRARY', now(), now(), 0)",
              oauthProfile,
              "Matrix-Anmeldung " + oauthProfile);
        };

    Map<String, String> ids = new LinkedHashMap<>();
    ids.put("spaceId", space.toString());
    ids.put("libraryId", library.toString());
    ids.put("assetId", library.toString());
    ids.put("assetType", KnowledgeLibrary.ASSET_TYPE.value());
    ids.put("objectId", library.toString());
    ids.put("folderId", folder.toString());
    ids.put("parentFolderId", folder.toString());
    ids.put("documentId", document.toString());
    ids.put("chunkId", chunk.toString());
    ids.put("hitId", chunk.toString());
    ids.put("fieldKey", fieldKey);
    ids.put("code", code);
    ids.put("promptLibraryId", promptLibrary.toString());
    ids.put("promptId", prompt.toString());
    ids.put("chatId", chat.toString());
    ids.put("itemId", item.toString());
    ids.put("otherChatId", otherChat.toString());
    ids.put("otherItemId", otherItem.toString());
    ids.put("groupId", group.toString());
    ids.put("grantId", grant.toString());
    ids.put("membershipId", membership.toString());
    ids.put("userId", owner.toString());
    ids.put("id", owner.toString());
    ids.put("sourceId", owner.toString());
    ids.put("targetId", colleague.toString());
    ids.put("notificationId", notification.toString());
    ids.put("tokenId", token.toString());
    ids.put("scopeId", scope.toString());
    ids.put("eventId", event.toString());
    ids.put("profileId", profile.toString());
    ids.put("oauthProfileId", oauthProfile.toString());
    List<String> markers =
        List.of(
            space.toString(),
            library.toString(),
            promptLibrary.toString(),
            document.toString(),
            chunk.toString(),
            spaceName,
            libraryName,
            content,
            fieldKey);
    return new Fixture(ids, seed, markers);
  }

  private void grant(UUID asset, String assetType, String role) {
    jdbc.update(
        "INSERT INTO asset_grants (id, asset_id, organization_id, subject_type, subject_user_id,"
            + " role, asset_type) VALUES (?, ?, ?, 'USER', ?, ?, ?)",
        UUID.randomUUID(),
        asset,
        Organization.DEFAULT_ID,
        devUser,
        role,
        assetType);
  }

  private static void noteItem(JdbcTemplate rows, UUID item, UUID chat, UUID organization) {
    rows.update(
        "INSERT INTO chat_note_items (id, chat_id, organization_id, position, text, kind)"
            + " VALUES (?, ?, ?, 0, 'Probe', 'RAHMEN')",
        item,
        chat,
        organization);
  }

  private UUID aChat(UUID space, UUID author, UUID organization) {
    UUID chat = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        chat,
        space,
        author,
        organization);
    return chat;
  }

  private UUID aGroup(UUID organization, UUID owner) {
    Group group =
        new Group(
            organization,
            GroupKind.AD_HOC,
            "Matrix-Gruppe " + UUID.randomUUID(),
            null,
            null,
            null,
            null,
            null);
    group.release(true);
    group.addMembership(new GroupMembership(owner, organization));
    UUID id = groupRepository.save(group).getId();
    groups.add(id);
    jdbc.update(
        "INSERT INTO group_stewards (id, group_id, user_id, organization_id) VALUES (?, ?, ?, ?)",
        UUID.randomUUID(),
        id,
        owner,
        organization);
    return id;
  }

  private UUID aUser(UUID organization, String displayName) {
    UUID user = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, organization_id,"
            + " last_login_at) VALUES (?, ?, 'opaa-dev', ?, ?, ?, now())",
        user,
        "matrix-" + user,
        "matrix-" + user + "@example.com",
        displayName,
        organization);
    users.add(user);
    return user;
  }

  private UUID userIdOf(String email) {
    return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
  }

  private static AbstractMockHttpServletRequestBuilder<?> as(
      String user, AbstractMockHttpServletRequestBuilder<?> request) {
    return request.header(DevAuthFilter.DEV_USER_HEADER, user);
  }

  private static void write(String name, CharSequence content) throws IOException {
    Files.createDirectories(OBSERVED);
    Files.writeString(OBSERVED.resolve(name), content);
  }
}
