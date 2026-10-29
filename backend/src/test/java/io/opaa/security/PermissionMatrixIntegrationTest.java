package io.opaa.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupRepository;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
 * Holds every operation of the specification to {@link PermissionMatrix}: each role of the matrix
 * but the anonymous one (that is {@code PermissionMatrixAnonymousIntegrationTest}) calls every
 * operation once with the ids of a space it holds that role in, and the outcome must be the one the
 * matrix names. Two more runs call every operation that names a space's or an organization's object
 * in its path with the id of a foreign one - a space administrator with a space of the same
 * organization she is no member of, the system administration with another organization - and
 * expect 401, 403 or 404. That every operation has an entry at all is {@code PermissionMatrixTest}.
 *
 * <p>Every request runs in a transaction that is rolled back after it ({@link RolledBackRequests}),
 * so a delete or a change of one operation never reaches the next. What each run observed is
 * written to {@code build/permission-matrix/}, in the matrix's own spelling.
 */
@OpaaIntegrationTest
class PermissionMatrixIntegrationTest {

  private static final ApiOperations API = ApiOperations.bundled();
  private static final PermissionMatrix MATRIX = PermissionMatrix.load();
  private static final Path OBSERVED = Path.of("build", "permission-matrix");

  /** Path parameters naming an object of one space. */
  private static final Set<String> SPACE_SCOPED =
      Set.of(
          "spaceId",
          "libraryId",
          "assetId",
          "chatId",
          "folderId",
          "grantId",
          "promptLibraryId",
          "promptId");

  /** Path parameters naming an object of one organization, besides those of a space. */
  private static final Set<String> ORGANIZATION_SCOPED = Set.of("groupId", "userId", "id");

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
  @Autowired private OwnLibraryFixtures libraryFixtures;
  @Autowired private OwnOrganizationFixtures organizationFixtures;
  @Autowired private UploadedOriginalStore originals;

  private UUID devUser;
  private UUID admin;
  private UUID foreignOrganization;
  private final List<UUID> users = new ArrayList<>();
  private final List<UUID> spaces = new ArrayList<>();
  private final List<UUID> libraries = new ArrayList<>();
  private final Map<UUID, UUID> libraryOrganizations = new LinkedHashMap<>();
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
    for (UUID library : concat(libraries, promptLibraries)) {
      jdbc.update("DELETE FROM audit_log WHERE object_id = ?", library.toString());
      jdbc.update("DELETE FROM asset_grant_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_ownership_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM asset_visibility_history WHERE asset_id = ?", library);
      jdbc.update("DELETE FROM notifications WHERE object_id = ?", library);
    }
    promptLibraries.clear();
    libraryFixtures.removeLibraries(libraries.toArray(UUID[]::new));
    // An upload stores its original outside the transaction that rolled back.
    libraryOrganizations.forEach(
        (library, organization) -> {
          List<String> stored = new ArrayList<>();
          originals.forEachStoredOriginal(
              organization, library, original -> stored.add(original.locator()));
          stored.forEach(
              locator -> originals.delete(new UploadedOriginalRef(organization, library, locator)));
        });
    libraryOrganizations.clear();
    libraries.clear();
    for (UUID group : groups) {
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
    String user = role == Role.SYSTEM_ADMIN ? "dev-admin" : "dev-user";
    if (role == Role.AUDITOR) {
      jdbc.update("UPDATE users SET system_role = 'AUDITOR' WHERE id = ?", devUser);
    }
    ProbeRequests probes = new ProbeRequests(API, own.ids());

    Map<String, Integer> statuses =
        requests().statuses(API.operations(), operation -> as(user, probes.build(operation)));

    List<String> findings = new ArrayList<>();
    StringBuilder observed = new StringBuilder();
    for (Operation operation : API.operations()) {
      int status = statuses.get(operation.id());
      Outcome outcome = Outcome.of(status);
      observed.append(operation.id()).append(": ").append(outcome.key).append(" # ");
      observed.append(status).append('\n');
      PermissionMatrix.Entry entry = MATRIX.entry(operation.id());
      if (entry == null) {
        continue;
      }
      Outcome expected = entry.expected().get(role);
      if (expected != outcome) {
        findings.add(
            "%s: %s expects '%s', observed '%s' (status %d)"
                .formatted(operation.label(), role.key, expected.key, outcome.key, status));
      }
    }
    write("observed-" + role.key + ".yaml", observed);
    assertThat(findings)
        .as(
            "operations whose outcome for %s departs from %s (observed run in %s)",
            role.key, PermissionMatrix.FILE, OBSERVED)
        .isEmpty();
  }

  @Test
  void aSpaceAdministratorReachesNothingOfAForeignSpace() throws IOException {
    ownFixture(Role.SPACE_ADMIN);
    UUID owner = users.getFirst();
    Fixture foreign = spaceFixture("Fremder Raum", Organization.DEFAULT_ID, owner, null);

    assertForeignIdsAreRefused("dev-user", foreign, SPACE_SCOPED, "foreign-space");
  }

  @Test
  void theSystemAdministrationReachesNothingOfAForeignOrganization() throws IOException {
    foreignOrganization =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Fremde Stelle " + UUID.randomUUID()))
            .getId();
    UUID stranger = aUser(foreignOrganization, "Fremde Person");
    Fixture foreign = spaceFixture("Fremder Raum", foreignOrganization, stranger, null);

    Set<String> scoped = new TreeSet<>(SPACE_SCOPED);
    scoped.addAll(ORGANIZATION_SCOPED);
    assertForeignIdsAreRefused("dev-admin", foreign, scoped, "foreign-organization");
  }

  private void assertForeignIdsAreRefused(
      String user, Fixture foreign, Set<String> scoped, String run) throws IOException {
    List<Operation> naming =
        API.operations().stream()
            .filter(
                operation ->
                    operation.in("path").stream()
                        .anyMatch(parameter -> scoped.contains(parameter.name())))
            .toList();
    ProbeRequests probes = new ProbeRequests(API, foreign.ids());

    Map<String, Integer> statuses =
        requests().statuses(naming, operation -> as(user, probes.build(operation)));

    List<String> findings = new ArrayList<>();
    StringBuilder observed = new StringBuilder();
    for (Operation operation : naming) {
      int status = statuses.get(operation.id());
      observed.append(operation.id()).append(": ").append(status).append('\n');
      PermissionMatrix.Entry entry = MATRIX.entry(operation.id());
      boolean refused = status == 401 || status == 403 || status == 404;
      if (!refused && (entry == null || !entry.foreignAllowed())) {
        findings.add("%s answers a foreign id with %d".formatted(operation.label(), status));
      }
    }
    write("observed-" + run + ".txt", observed);
    assertThat(naming).isNotEmpty();
    assertThat(findings).as("operations that do not refuse a foreign id (%s)", run).isEmpty();
  }

  // -------------------------------------------------------------------------------------------
  // Fixture
  // -------------------------------------------------------------------------------------------

  /** The ids a run's requests use, by the name of the parameter or property that carries them. */
  record Fixture(Map<String, String> ids) {}

  /**
   * A space of a third person with a library, a folder, a prompt library with a prompt and a chat
   * of the dev user, a group of the organization - and the dev user in the space with the role's
   * space role, if it has one.
   */
  private Fixture ownFixture(Role role) {
    UUID owner = aUser(Organization.DEFAULT_ID, "Ilse Eigen");
    SpaceRole spaceRole =
        switch (role) {
          case SPACE_ADMIN -> SpaceRole.ADMIN;
          case EDITOR -> SpaceRole.CURATOR;
          case VIEWER -> SpaceRole.MEMBER;
          default -> null;
        };
    return spaceFixture("Matrix-Raum", Organization.DEFAULT_ID, owner, spaceRole);
  }

  private Fixture spaceFixture(String name, UUID organization, UUID owner, SpaceRole devUserRole) {
    CurrentUser ownerCaller = CurrentUser.of(owner, organization, SystemRole.SYSTEM_ADMIN, "Eigen");
    List<SpaceMemberSeed> members =
        devUserRole == null ? List.of() : List.of(SpaceMemberSeed.user(devUser, devUserRole));
    UUID space =
        spaceService
            .createSpace(
                new SpaceCreation(name + " " + UUID.randomUUID(), null, owner, members, List.of()),
                ownerCaller)
            .getId();
    spaces.add(space);
    UUID library =
        libraryService
            .createLibrary(
                LibraryCreationBuilder.libraryCreation("Matrix-Wissen", SourceType.UPLOAD).build(),
                ownerCaller)
            .library()
            .getId();
    libraries.add(library);
    libraryOrganizations.put(library, organization);
    associations.associate(space, KnowledgeLibrary.ASSET_TYPE, library, ownerCaller);
    UUID folder = folderService.createFolder(library, "Ablage", null, ownerCaller).folder().getId();
    UUID promptLibrary =
        promptLibraryService
            .create(new PromptLibraryCreation("Matrix-Prompts", null, null, null), ownerCaller)
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
    UUID chatAuthor =
        organization.equals(Organization.DEFAULT_ID) && devUserRole != null ? devUser : owner;
    UUID chat = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO chats (id, space_id, author_id, organization_id, use_knowledge, status,"
            + " created_at, updated_at) VALUES (?, ?, ?, ?, true, 'PRIVATE', now(), now())",
        chat,
        space,
        chatAuthor,
        organization);
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
    groups.add(groupRepository.save(group).getId());

    Map<String, String> ids = new LinkedHashMap<>();
    ids.put("spaceId", space.toString());
    ids.put("libraryId", library.toString());
    ids.put("assetId", library.toString());
    ids.put("assetType", KnowledgeLibrary.ASSET_TYPE.value());
    ids.put("folderId", folder.toString());
    ids.put("parentFolderId", folder.toString());
    ids.put("promptLibraryId", promptLibrary.toString());
    ids.put("promptId", prompt.toString());
    ids.put("chatId", chat.toString());
    ids.put("groupId", groups.getLast().toString());
    ids.put("userId", owner.toString());
    ids.put("sourceId", owner.toString());
    ids.put("targetId", aUser(organization, "Nachfolge").toString());
    ids.put("id", owner.toString());
    ids.put("objectId", library.toString());
    UUID grant =
        jdbc.queryForObject(
            "SELECT id FROM asset_grants WHERE asset_id = ? ORDER BY id LIMIT 1",
            UUID.class,
            library);
    ids.put("grantId", grant.toString());
    if (devUserRole != null) {
      ids.put(
          "membershipId",
          jdbc.queryForObject(
                  "SELECT id FROM space_memberships WHERE space_id = ? AND user_id = ?",
                  UUID.class,
                  space,
                  devUser)
              .toString());
    }
    return new Fixture(ids);
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

  private RolledBackRequests requests() {
    return new RolledBackRequests(mockMvc, transactionManager, caches);
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

  private static List<UUID> concat(List<UUID> first, List<UUID> second) {
    List<UUID> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }
}
