package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static io.opaa.library.LibraryUpdateBuilder.libraryUpdate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.dto.ScheduleFrequency;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.LibraryOwnerType;
import io.opaa.api.types.LibraryVisibility;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ConflictException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipHistoryRepository;
import io.opaa.group.GroupMembershipResolver;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.indexing.IndexingJob;
import io.opaa.indexing.IndexingJobRepository;
import io.opaa.indexing.JobStatus;
import io.opaa.indexing.RssFeedState;
import io.opaa.indexing.RssFeedStateRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs against a real Postgres database with the real, versioned Liquibase schema applied ({@code
 * spring.liquibase.enabled=true}, {@code ddl-auto=none}), not Hibernate-generated DDL - see #288
 * and {@code SpaceServiceIntegrationTest}, whose pattern this class follows. Every owner id used
 * here is a real, persisted {@link User} or {@link Group}, because {@code
 * fk_knowledge_libraries_owner_user} and {@code fk_knowledge_libraries_owner_group_organization}
 * (migration 012) are real foreign keys enforced by Liquibase, not by Hibernate's entity mapping.
 *
 * <p>{@link
 * #aGroupGrantOnAPersonallyOwnedLibraryReachesItsMembersAndRevocationTakesEffectImmediately()} and
 * {@link #revokingAGrantTakesEffectOnTheNextCall()} are the mechanism-interaction tests (#202):
 * they exercise a group grant together with {@link GroupMembershipResolver}'s cache invalidation,
 * and a direct grant together with {@code LibraryAccessService}'s own per-library grant cache, not
 * either mechanism in isolation - a regression that reads membership or a grant correctly but
 * forgets to invalidate the relevant cache would still pass a test that only checks access once.
 * {@link #creatingAGroupOwnedLibraryGrantsManagerToTheGroupAndOwnerToTheCreatorButNoOutsider()} is
 * the regression guard for the #201 behaviour #202 replaced - see its own Javadoc for why the group
 * gets MANAGER (not OWNER, which round 1 of the #202 review tried and round 2 reverted) and the
 * creator personally gets OWNER.
 */
@OpaaIntegrationTest
class KnowledgeLibraryServiceIntegrationTest {

  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantService grantService;
  @Autowired private LibraryAccessService accessService;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groupRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private IndexingJobRepository indexingJobRepository;
  @Autowired private RssFeedStateRepository rssFeedStateRepository;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private EntityManagerFactory entityManagerFactory;

  private UUID organizationA;
  private UUID organizationB;

  // This Spring context (and its Postgres container) is shared with other integration test
  // classes carrying the canonical @OpaaIntegrationTest signature (Spring caches the context) -
  // some of those classes (e.g.
  // UserServicePersonalSpaceIntegrationTest) have no @AfterEach and leave Space rows behind that
  // reference their users. A blanket userRepository.deleteAll() here would then fail on
  // fk_spaces_owner for a user this test never created. Every user, group and non-system library
  // this class creates is tracked here instead and removed by id in tearDown() - precise cleanup
  // that never touches another test class's rows, mirroring the caution
  // SpaceRepositoryTest/SpaceServiceIntegrationTest apply to Organization.DEFAULT_ID but extended
  // to every row this class did not itself create.
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> createdGroupIds = new ArrayList<>();
  private final List<UUID> createdSpaceIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    createdGroupIds.clear();
    createdSpaceIds.clear();
    organizationA =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org A")).getId();
    organizationB =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org B")).getId();
  }

  @AfterEach
  void tearDown() {
    // Documents first (fk_documents_library_organization is RESTRICT - a library a test left
    // non-empty, e.g. after an assertion failure before its own cleanup ran, would otherwise block
    // the library delete below), then libraries (they reference users/groups, not the other way
    // round), then groups, then users, then the two throwaway organizations.
    List<KnowledgeLibrary> ownLibraries =
        libraryRepository.findAll().stream()
            .filter(
                l ->
                    createdUserIds.contains(l.getOwnerUserId())
                        || createdGroupIds.contains(l.getOwnerGroupId()))
            .toList();
    for (KnowledgeLibrary library : ownLibraries) {
      documentRepository.deleteAll(documentRepository.findByLibraryId(library.getId()));
    }
    libraryRepository.deleteAll(ownLibraries);
    for (UUID spaceId : createdSpaceIds) {
      spaceRepository.deleteById(spaceId);
    }
    // #238 code review, finding 3+4: asset_grant_history.subject_user_id and
    // group_membership_history.user_id are ON DELETE RESTRICT (the history must survive a library
    // or group deletion, but an account deletion is deliberately blocked until a pseudonymisation
    // mechanism exists - see 018-permission-history.yaml's "Deletion survival" comment). Every
    // library/grant/membership operation this class exercises now writes such a row, so it must be
    // purged before this teardown's own user deletion below, which is not a real account deletion
    // but this test's own cleanup.
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    membershipHistoryRepository.deleteByUserIdIn(createdUserIds);
    for (UUID groupId : createdGroupIds) {
      groupRepository.deleteById(groupId);
    }
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    // #392: every library/grant operation this class exercises now also writes an audit_log row
    // (fk_audit_log_organization is ON DELETE RESTRICT, migration 017) - purged the same way
    // AuditLogServiceIntegrationTest does, via JdbcTemplate against the Testcontainers superuser
    // account, since AuditLogEntry#isNew() being unconditionally true makes the repository's own
    // deleteAll a silent no-op for it.
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE organization_id IN (?, ?)", organizationA, organizationB);
    organizationRepository.deleteById(organizationA);
    organizationRepository.deleteById(organizationB);
  }

  private UUID createUser(UUID organizationId) {
    return createUser(organizationId, "Test User");
  }

  private UUID createUser(UUID organizationId, String displayName) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", displayName);
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  /**
   * {@link CurrentUser} snapshot for a user id this test already created, as a non-admin caller.
   */
  private CurrentUser currentUserOf(UUID userId) {
    return currentUserOf(userId, false);
  }

  /**
   * {@link CurrentUser} snapshot for a user id this test already created - {@code systemAdmin}
   * overrides the snapshot's role regardless of the row's actual, always-USER {@code system_role}
   * (see {@link #createUser}), mirroring the {@code systemAdmin} boolean the pre-#884 signatures
   * let every caller here set independently of the seeded row.
   */
  private CurrentUser currentUserOf(UUID userId, boolean systemAdmin) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        userId,
        user.getOrganizationId(),
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        user.getDisplayName());
  }

  // sourceUrl is a URI on the generated response, a String on LibraryManagementDetail (and thus
  // absent whenever the caller was below MANAGER, i.e. managementDetail() is null) - this helper
  // keeps the assertions below reading like the pre-#860 DTO-typed ones.
  private static URI sourceUrl(LibraryDetail detail) {
    if (detail.managementDetail() == null || detail.managementDetail().sourceUrl() == null) {
      return null;
    }
    return URI.create(detail.managementDetail().sourceUrl());
  }

  private Group createGroup(UUID organizationId, UUID... memberIds) {
    Group group =
        new Group(organizationId, GroupKind.AD_HOC, "Referat", "Ad-hoc-Gruppe", null, null);
    for (UUID memberId : memberIds) {
      group.addMembership(new GroupMembership(memberId, organizationId));
    }
    Group saved = groupRepository.save(group);
    createdGroupIds.add(saved.getId());
    return saved;
  }

  @Test
  void createLibraryDefaultsToUserOwnershipAndPrivateVisibility() {
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build();

    LibraryDetail response = libraryService.createLibrary(request, currentUserOf(owner));

    assertThat(response.library().getOwnerType()).isEqualTo(LibraryOwnerType.USER);
    assertThat(response.library().getOwnerId()).isEqualTo(owner);
    assertThat(response.library().getVisibility()).isEqualTo(LibraryVisibility.PRIVATE);
    assertThat(response.library().isListed()).isFalse();
  }

  @Test
  void createLibraryRequiresASourceType() {
    // ADR-0018: sourceType is mandatory at creation, not defaulted - a caller-supplied null is
    // rejected with 400, unlike ownerType/visibility, which do default.
    UUID owner = createUser(organizationA);
    LibraryCreation request = libraryCreation("Ohne Typ", null).build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeWithoutAPath() {
    UUID owner = createUser(organizationA);
    LibraryCreation request = libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM).build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeCombinedWithAUrl() {
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents")
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsHttpDirectorySourceTypeWithoutAUrl() {
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY).build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsUploadSourceTypeCombinedWithAnyConfiguration() {
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Upload", DocumentSourceType.UPLOAD).sourcePath("/data/documents").build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeCombinedWithCredentialsAndProxy() {
    // PR #489 review, Befund 6b: a FILESYSTEM request that only ever carries sourceCredentials/
    // sourceProxy (no sourceUrl at all) must still be rejected - the earlier
    // createLibraryRejectsFilesystemSourceTypeCombinedWithAUrl only exercised the sourceUrl branch
    // of the same check.
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents")
            .sourceCredentials("admin:secret")
            .sourceProxy("proxy.example.com:8080")
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsFilesystemSourceTypeWithARelativePath() {
    // PR #489 review, Befund 5: sourcePath must be absolute.
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("relative/documents")
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsHttpDirectorySourceTypeWithANonHttpUrl() {
    // PR #489 review, Befund 5: sourceUrl is restricted to http/https.
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
            .sourceUrl(URI.create("ftp://files.example.com/documents/"))
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryAcceptsARssFeedSourceTypeWithAUrl() {
    // PR #489 review, Befund 1: RSS_FEED (#474) is validated exactly like HTTP_DIRECTORY - a
    // required sourceUrl, no sourcePath.
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Feed-Bibliothek", DocumentSourceType.RSS_FEED)
            .sourceUrl(URI.create("https://example.com/feed.xml"))
            .build();

    LibraryDetail response = libraryService.createLibrary(request, currentUserOf(owner));

    assertThat(response.library().getSourceType()).isEqualTo(DocumentSourceType.RSS_FEED);
    assertThat(sourceUrl(response)).isEqualTo(URI.create("https://example.com/feed.xml"));
    assertThat(response.managementDetail().sourcePath()).isNull();
  }

  @Test
  void createLibraryRejectsRssFeedSourceTypeWithoutAUrl() {
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Feed-Bibliothek", DocumentSourceType.RSS_FEED).build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsRssFeedSourceTypeCombinedWithAPath() {
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Feed-Bibliothek", DocumentSourceType.RSS_FEED)
            .sourceUrl(URI.create("https://example.com/feed.xml"))
            .sourcePath("/data/documents")
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void updateLibraryChangingSourceUrlDeletesTheLibrarysOwnStaleRssFeedState() {
    // #646, PR #665 review "should" finding 3: fk_rss_feed_state_library's ON DELETE CASCADE
    // (migration 045) only fires on a library *deletion* - a sourceUrl change on an
    // otherwise-surviving library needs its own cleanup, or a later reconfiguration back to a
    // previously-used address would find this library's own stale ETag/Last-Modified again and end
    // that run in a false 304 (the same defect #646 fixed, one level down: the same library reusing
    // its own former address instead of a different library reusing another's).
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Feed-Bibliothek", DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create("https://example.com/feed.xml"))
                .build(),
            currentUserOf(owner));
    rssFeedStateRepository.save(
        new RssFeedState(
            library.library().getId(),
            "https://example.com/feed.xml",
            "\"etag\"",
            "Mon, 01 Jan 2024 00:00:00 GMT"));
    assertThat(
            rssFeedStateRepository.findByLibraryIdAndFeedUrl(
                library.library().getId(), "https://example.com/feed.xml"))
        .isPresent();

    LibraryUpdate request =
        libraryUpdate("Feed-Bibliothek")
            .sourceUrl(URI.create("https://example.com/other-feed.xml"))
            .build();
    libraryService.updateLibrary(library.library().getId(), request, currentUserOf(owner, false));

    assertThat(
            rssFeedStateRepository.findByLibraryIdAndFeedUrl(
                library.library().getId(), "https://example.com/feed.xml"))
        .isEmpty();
  }

  @Test
  void updateLibraryWithoutChangingSourceUrlLeavesTheRssFeedStateRowUntouched() {
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Feed-Bibliothek", DocumentSourceType.RSS_FEED)
                .sourceUrl(URI.create("https://example.com/feed.xml"))
                .build(),
            currentUserOf(owner));
    rssFeedStateRepository.save(
        new RssFeedState(
            library.library().getId(),
            "https://example.com/feed.xml",
            "\"etag\"",
            "Mon, 01 Jan 2024 00:00:00 GMT"));

    // A rename alone (no source configuration fields in the request at all) must not touch the
    // feed state - mirrors updateLibraryLeavesTheSourceConfigurationUntouchedWhenTheRequestCarries
    // NoConfigField's reasoning for the source columns themselves.
    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Feed-Bibliothek umbenannt").build(),
        currentUserOf(owner, false));

    assertThat(
            rssFeedStateRepository.findByLibraryIdAndFeedUrl(
                library.library().getId(), "https://example.com/feed.xml"))
        .isPresent();
  }

  @Test
  void createLibraryAcceptsAFilesystemSourceTypeWithAPath() {
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/documents")
            .build();

    LibraryDetail response = libraryService.createLibrary(request, currentUserOf(owner));

    assertThat(response.library().getSourceType()).isEqualTo(DocumentSourceType.FILESYSTEM);
    assertThat(response.managementDetail().sourcePath()).isEqualTo("/data/documents");
    assertThat(sourceUrl(response)).isNull();
  }

  @Test
  void createLibraryRejectsAFilesystemSourceTypeWithAPathOutsideTheAllowlist() {
    // #484/ADR-0018 Entscheidung 6: the test suite's dev-profile allowlist (application.yml) is
    // /data,/tmp - a path outside both must be rejected even though it is a perfectly valid
    // absolute path.
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/etc/shadow")
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryRejectsAFilesystemSourceTypeWithATraversalPathThatEscapesTheAllowlist() {
    // #484: sourcePath is normalised (Path.normalize()) before the allowlist check, so a "../"
    // segment cannot lexically escape an allowed base directory - /data/../etc/shadow normalises
    // to /etc/shadow, outside both configured base directories (/data, /tmp).
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
            .sourcePath("/data/../etc/shadow")
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void updateLibraryRejectsAFilesystemSourcePathOutsideTheAllowlist() {
    // #484: the allowlist is enforced again on update, not only at creation - moving an existing
    // FILESYSTEM library's crawl target to a path outside the allowlist must fail exactly like
    // choosing that path at creation would have.
    UUID owner = createUser(organizationA);
    LibraryDetail created =
        libraryService.createLibrary(
            libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
                .sourcePath("/data/documents")
                .build(),
            currentUserOf(owner));

    LibraryUpdate update = libraryUpdate("Verzeichnis").sourcePath("/etc/shadow").build();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    created.library().getId(), update, currentUserOf(owner, false)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void createLibraryAcceptsAnHttpDirectorySourceTypeWithAUrlAndNeverReturnsCredentials() {
    // Abnahmekriterium: Zugangsdaten tauchen in keiner API-Antwort auf (ADR-0018, Entscheidung 4).
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .sourceProxy("proxy.example.com:8080")
            .sourceCredentials("admin:secret")
            .sourceInsecureSsl(true)
            .build();

    LibraryDetail response = libraryService.createLibrary(request, currentUserOf(owner));

    assertThat(response.library().getSourceType()).isEqualTo(DocumentSourceType.HTTP_DIRECTORY);
    assertThat(sourceUrl(response)).isEqualTo(URI.create("https://files.example.com/documents/"));
    assertThat(response.managementDetail().sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(response.managementDetail().sourceInsecureSsl()).isTrue();
    assertThat(response.toString()).doesNotContain("admin:secret");
    assertThat(response.getClass().getMethods())
        .noneMatch(method -> method.getName().equals("getSourceCredentials"));

    // The stored value is still there for the (not-yet-built) indexing run to use - only the API
    // response omits it.
    KnowledgeLibrary stored = libraryRepository.findById(response.library().getId()).orElseThrow();
    assertThat(stored.getSourceCredentials()).isEqualTo("admin:secret");

    LibraryDetail reloaded =
        libraryService.getLibrary(response.library().getId(), currentUserOf(owner, false));
    assertThat(reloaded.toString()).doesNotContain("admin:secret");
  }

  @Test
  void sourceCredentialsAreStoredEncryptedNotAsCleartextInTheDatabase() {
    // #483: knowledge_libraries.source_credentials must never hold the plaintext value - checked
    // against the raw column via JdbcTemplate, bypassing SourceCredentialsConverter entirely, so
    // this actually exercises what is on disk rather than what the entity mapping presents.
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Verschluesselte Zugangsdaten", DocumentSourceType.HTTP_DIRECTORY)
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .sourceCredentials("admin:super-secret-password")
            .build();

    LibraryDetail response = libraryService.createLibrary(request, currentUserOf(owner));

    String rawColumnValue =
        jdbcTemplate.queryForObject(
            "SELECT source_credentials FROM knowledge_libraries WHERE id = ?",
            String.class,
            response.library().getId());
    assertThat(rawColumnValue).isNotNull();
    assertThat(rawColumnValue).doesNotContain("admin:super-secret-password");
    assertThat(rawColumnValue).startsWith("enc:v1:");

    // The entity mapping (via SourceCredentialsConverter) still transparently decrypts on load -
    // both the indexing executors and libraryRepository.findById see the plaintext, unchanged from
    // before #483 (see
    // createLibraryAcceptsAnHttpDirectorySourceTypeWithAUrlAndNeverReturnsCredentials
    // above).
    KnowledgeLibrary reloaded =
        libraryRepository.findById(response.library().getId()).orElseThrow();
    assertThat(reloaded.getSourceCredentials()).isEqualTo("admin:super-secret-password");
  }

  @Test
  void aMaximumLengthCredentialSurvivesEncryptionWithoutTruncation() {
    // #483/migration 029: source_credentials was widened from varchar(500) to varchar(3000) to fit
    // the encrypted encoding of exactly the longest plaintext LibraryCreation.sourceCredentials
    // still
    // allows (maxLength: 500, openapi/opaa-api.yaml) - this pins that the column is actually wide
    // enough, not just declared so in the migration's comment.
    UUID owner = createUser(organizationA);
    String longCredentials = "u".repeat(245) + ":" + "p".repeat(254); // exactly 500 characters
    assertThat(longCredentials).hasSize(500);
    LibraryCreation request =
        libraryCreation("Maximallange Zugangsdaten", DocumentSourceType.HTTP_DIRECTORY)
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .sourceCredentials(longCredentials)
            .build();

    LibraryDetail response = libraryService.createLibrary(request, currentUserOf(owner));

    KnowledgeLibrary reloaded =
        libraryRepository.findById(response.library().getId()).orElseThrow();
    assertThat(reloaded.getSourceCredentials()).isEqualTo(longCredentials);
  }

  @Test
  void
      aCredentialThatCanNoLongerBeDecryptedIsReadAsNullInsteadOfFailingTheWholeLibraryLoadAndCanBeRepairedByRotatingIt() {
    // PR #504 review, finding 1: a lost/rotated key (or a corrupted stored value) must not turn
    // GET /api/v1/libraries into a 503 for every library that shares this key - only the affected
    // library's sourceCredentials reads as null. The documented repair path (docs/deployment.md,
    // "Bei Schluesselverlust") - setting new credentials via the update API - depends on that same
    // load succeeding first.
    UUID owner = createUser(organizationA);
    LibraryCreation request =
        libraryCreation("Zugangsdaten mit verlorenem Schluessel", DocumentSourceType.HTTP_DIRECTORY)
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .sourceCredentials("admin:super-secret-password")
            .build();
    LibraryDetail response = libraryService.createLibrary(request, currentUserOf(owner));

    // Simulate a lost/rotated encryption key (or a corrupted column value) by writing a value
    // directly that carries the "enc:v1:" marker but no key can ever turn back into plaintext.
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET source_credentials = ? WHERE id = ?",
        "enc:v1:not-decryptable-with-any-key",
        response.library().getId());

    // The list read must not fail for the whole set just because one library's credentials are
    // undecryptable.
    assertThat(libraryService.listLibraries(currentUserOf(owner, false)))
        .anySatisfy(
            listed -> assertThat(listed.library().getId()).isEqualTo(response.library().getId()));

    KnowledgeLibrary reloaded =
        libraryRepository.findById(response.library().getId()).orElseThrow();
    assertThat(reloaded.getSourceCredentials()).isNull();

    // The repair: rotating the credentials via the existing update API works, precisely because
    // the load that precedes the update no longer fails on the old, undecryptable value.
    LibraryUpdate repair =
        libraryUpdate("Zugangsdaten mit verlorenem Schluessel")
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .sourceCredentials("admin:repaired-password")
            .build();
    libraryService.updateLibrary(response.library().getId(), repair, currentUserOf(owner, false));

    KnowledgeLibrary repaired =
        libraryRepository.findById(response.library().getId()).orElseThrow();
    assertThat(repaired.getSourceCredentials()).isEqualTo("admin:repaired-password");
  }

  @Test
  void updateLibraryRejectsAChangeOfSourceType() {
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Upload", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Upload").sourceType(DocumentSourceType.FILESYSTEM).build();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.library().getId(), request, currentUserOf(owner, false)))
        .isInstanceOf(ValidationException.class);
    assertThat(libraryRepository.findById(library.library().getId()).orElseThrow().getSourceType())
        .isEqualTo(DocumentSourceType.UPLOAD);
  }

  @Test
  void updateLibraryAcceptsAnUnchangedSourceType() {
    // Resending the current value (e.g. a client echoing LibraryDetail back) must not itself be
    // treated as a rejected type change.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Upload", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Upload umbenannt").sourceType(DocumentSourceType.UPLOAD).build();

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.library().getId(), request, currentUserOf(owner, false));

    assertThat(updated.library().getName()).isEqualTo("Upload umbenannt");
    assertThat(updated.library().getSourceType()).isEqualTo(DocumentSourceType.UPLOAD);
  }

  @Test
  void updateLibraryReplacesTheSourceConfigurationWithoutChangingTheSourceType() {
    // PR #489 review, Befund 4: rotating credentials or moving a crawl target must not require
    // deleting and recreating the library.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://old.example.com/documents/"))
                .sourceCredentials("admin:old-secret")
                .build(),
            currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis")
            .sourceUrl(URI.create("https://new.example.com/documents/"))
            .sourceCredentials("admin:new-secret")
            .sourceProxy("proxy.example.com:8080")
            .sourceInsecureSsl(true)
            .build();

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.library().getId(), request, currentUserOf(owner, false));

    assertThat(updated.library().getSourceType()).isEqualTo(DocumentSourceType.HTTP_DIRECTORY);
    assertThat(sourceUrl(updated)).isEqualTo(URI.create("https://new.example.com/documents/"));
    assertThat(updated.managementDetail().sourceProxy()).isEqualTo("proxy.example.com:8080");
    assertThat(updated.managementDetail().sourceInsecureSsl()).isTrue();
    assertThat(updated.toString()).doesNotContain("new-secret").doesNotContain("old-secret");

    KnowledgeLibrary stored = libraryRepository.findById(library.library().getId()).orElseThrow();
    assertThat(stored.getSourceCredentials()).isEqualTo("admin:new-secret");
  }

  @Test
  void updateLibrarySavesADailyScheduleAndComputesTheNextRunAt() {
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://example.com/documents/"))
                .build(),
            currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis")
            .schedule(new LibraryScheduleUpdate(ScheduleFrequency.DAILY).hour(3).minute(30))
            .build();

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.library().getId(), request, currentUserOf(owner, false));

    assertThat(updated.managementDetail().schedule()).isNotNull();
    assertThat(updated.managementDetail().schedule().frequency())
        .isEqualTo(ScheduleFrequency.DAILY);
    assertThat(updated.managementDetail().schedule().hour()).isEqualTo(3);
    assertThat(updated.managementDetail().schedule().minute()).isEqualTo(30);
    assertThat(updated.managementDetail().schedule().nextRunAt()).isNotNull();

    KnowledgeLibrary stored = libraryRepository.findById(library.library().getId()).orElseThrow();
    assertThat(stored.isScheduleEnabled()).isTrue();
    assertThat(stored.getScheduleCron()).isEqualTo("0 30 3 * * *");
  }

  @Test
  void updateLibraryDisablingAnExistingScheduleClearsTheStoredCron() {
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://example.com/documents/"))
                .build(),
            currentUserOf(owner));
    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Web-Verzeichnis")
            .schedule(new LibraryScheduleUpdate(ScheduleFrequency.HOURLY))
            .build(),
        currentUserOf(owner, false));

    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Web-Verzeichnis")
            .schedule(new LibraryScheduleUpdate(ScheduleFrequency.DISABLED))
            .build(),
        currentUserOf(owner, false));

    KnowledgeLibrary stored = libraryRepository.findById(library.library().getId()).orElseThrow();
    assertThat(stored.isScheduleEnabled()).isFalse();
    assertThat(stored.getScheduleCron()).isNull();
  }

  @Test
  void updateLibraryRejectsAScheduleOnAnUploadLibrary() {
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Upload", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Upload")
            .schedule(new LibraryScheduleUpdate(ScheduleFrequency.HOURLY))
            .build();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.library().getId(), request, currentUserOf(owner, false)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void updateLibraryRejectsAWeeklyScheduleWithoutAWeekday() {
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://example.com/documents/"))
                .build(),
            currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis")
            .schedule(new LibraryScheduleUpdate(ScheduleFrequency.WEEKLY).hour(9).minute(0))
            .build();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.library().getId(), request, currentUserOf(owner, false)))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  void updateLibraryLeavesAnUntouchedScheduleWhenTheRequestOmitsIt() {
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://example.com/documents/"))
                .build(),
            currentUserOf(owner));
    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Web-Verzeichnis")
            .schedule(new LibraryScheduleUpdate(ScheduleFrequency.HOURLY))
            .build(),
        currentUserOf(owner, false));

    // A request that only renames the library (no schedule field at all) must leave the stored
    // schedule untouched, mirroring the source configuration's own replace-as-a-whole rule.
    libraryService.updateLibrary(
        library.library().getId(), libraryUpdate("Umbenannt").build(), currentUserOf(owner, false));

    KnowledgeLibrary stored = libraryRepository.findById(library.library().getId()).orElseThrow();
    assertThat(stored.isScheduleEnabled()).isTrue();
    assertThat(stored.getScheduleCron()).isEqualTo("0 0 * * * *");
  }

  @Test
  void aDefectiveStoredCronExpressionDoesNotFailTheLibraryLoadOrHideTheSchedule() {
    // PR #705 review, blocker 3: an undecodable schedule_cron value (a hand-edited row, a
    // corrupted value) must not turn GET/PUT /api/v1/libraries/{id} into an unhandled 500 for
    // this one library - mirrors
    // aCredentialThatCanNoLongerBeDecryptedIsReadAsNullInsteadOfFailingTheWholeLibraryLoadAndCanBeRepairedByRotatingIt's
    // pattern of writing directly via JdbcTemplate to simulate data this class never itself wrote.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://example.com/documents/"))
                .build(),
            currentUserOf(owner));
    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Web-Verzeichnis")
            .schedule(new LibraryScheduleUpdate(ScheduleFrequency.HOURLY))
            .build(),
        currentUserOf(owner, false));
    // schedule_enabled stays true (only NOT NULL is enforced at the database level, not the cron
    // syntax itself) - simulates a corrupted value rather than a disabled schedule.
    jdbcTemplate.update(
        "UPDATE knowledge_libraries SET schedule_cron = ? WHERE id = ?",
        "not a cron expression",
        library.library().getId());

    LibraryDetail reloaded =
        libraryService.getLibrary(library.library().getId(), currentUserOf(owner, false));

    assertThat(reloaded.managementDetail().schedule()).isNotNull();
    assertThat(reloaded.managementDetail().schedule().nextRunAt()).isNull();
  }

  @Test
  void
      updateLibraryPreservesStoredCredentialsWhenTheUpdateRequestOmitsThemAndTheOriginIsUnchanged() {
    // Issue #516: sourceCredentials is write-only (never returned by any API response,
    // ADR-0018), so a client editing e.g. only the path portion of sourceUrl through a UI dialog
    // has no value it could resend even if it wanted to. Omitting the field must not be
    // indistinguishable from "clear the credential" the way it would be for a plain full-object
    // replace - as long as the request still names the same origin (PR #542 review finding 1;
    // see the sibling test below for a host change, which must drop the credential instead).
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://files.example.com/documents/"))
                .sourceCredentials("admin:old-secret")
                .build(),
            currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis")
            .sourceUrl(URI.create("https://files.example.com/other-documents/"))
            .build();

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.library().getId(), request, currentUserOf(owner, false));

    assertThat(sourceUrl(updated))
        .isEqualTo(URI.create("https://files.example.com/other-documents/"));
    KnowledgeLibrary stored = libraryRepository.findById(library.library().getId()).orElseThrow();
    assertThat(stored.getSourceCredentials()).isEqualTo("admin:old-secret");
  }

  @Test
  void updateLibraryDropsStoredCredentialsWhenSourceUrlMovesToADifferentHost() {
    // PR #542 review finding 1: without this, a MANAGER who does not know a configured
    // credential could redirect it to a host they control by pointing sourceUrl at their own
    // server and leaving the credentials field blank - AutoindexCrawlerService sends the stored
    // Authorization header preemptively on the very first request, so the attacker's server would
    // receive the credential in plaintext without ever needing to know it.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://internal.example.com/documents/"))
                .sourceCredentials("admin:old-secret")
                .build(),
            currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis")
            .sourceUrl(URI.create("https://attacker.example.com/documents/"))
            .build();

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.library().getId(), request, currentUserOf(owner, false));

    assertThat(sourceUrl(updated)).isEqualTo(URI.create("https://attacker.example.com/documents/"));
    KnowledgeLibrary stored = libraryRepository.findById(library.library().getId()).orElseThrow();
    assertThat(stored.getSourceCredentials()).isNull();
  }

  @Test
  void updateLibraryRejectsAConfigurationThatContradictsTheExistingSourceType() {
    // The same 400-before-write validation applies on update, keyed on the library's own,
    // unchangeable sourceType (FILESYSTEM here), not any sourceType in the request.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
                .sourcePath("/data/documents")
                .build(),
            currentUserOf(owner));

    LibraryUpdate request =
        libraryUpdate("Verzeichnis")
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .build();

    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.library().getId(), request, currentUserOf(owner, false)))
        .isInstanceOf(ValidationException.class);
    assertThat(libraryRepository.findById(library.library().getId()).orElseThrow().getSourcePath())
        .isEqualTo("/data/documents");
  }

  @Test
  void updateLibraryRecordsALibrarySourceUpdatedAuditEntryForAPureSourceConfigurationChange() {
    // #545: a pure source-configuration change (credential rotation here) previously left no
    // audit trace at all - neither LIBRARY_CHANGED (name/description) nor
    // ASSET_VISIBILITY_CHANGED (visibility/listed) fires for it, since this request resends
    // name/description/visibility/listed unchanged and only touches sourceCredentials.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://files.example.com/documents/"))
                .sourceCredentials("admin:old-secret")
                .build(),
            currentUserOf(owner));
    // The creation event itself must not satisfy the assertion below.
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE object_id = ?", library.library().getId().toString());

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis")
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .sourceCredentials("admin:new-secret")
            .build();

    libraryService.updateLibrary(library.library().getId(), request, currentUserOf(owner, false));

    List<String> afterPayloads =
        jdbcTemplate.queryForList(
            "SELECT after FROM audit_log WHERE object_id = ? AND event_type = ?",
            String.class,
            library.library().getId().toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(afterPayloads).hasSize(1);
    assertThat(afterPayloads.get(0))
        .contains("sourceCredentials")
        .doesNotContain("old-secret")
        .doesNotContain("new-secret");
  }

  @Test
  void updateLibraryWritesNoLibrarySourceUpdatedEntryWhenOnlyTheNameChanges() {
    // The counterpart of the test above: a rename-only request must not fire
    // LIBRARY_SOURCE_UPDATED - only LIBRARY_CHANGED, exactly as before #545.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
                .sourcePath("/data/documents")
                .build(),
            currentUserOf(owner));
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE object_id = ?", library.library().getId().toString());

    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Verzeichnis umbenannt").build(),
        currentUserOf(owner, false));

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type = ?",
            Integer.class,
            library.library().getId().toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(count).isZero();
  }

  @Test
  void
      updateLibraryWritesNoLibrarySourceUpdatedEntryWhenTheDialogResendsTheSourceFieldsUnchanged() {
    // Code review finding 2 (PR #578): the real EditLibrarySourceDialog case - it resends
    // sourceUrl unchanged and leaves sourceCredentials blank (relying on the same-origin
    // fallback in validateSourceConfigurationForUpdate). This walks the new #545 block all the
    // way to the empty-changedSourceFields guard, unlike the rename-only test above, which never
    // enters replacesSourceConfiguration at all.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://files.example.com/documents/"))
                .sourceCredentials("admin:old-secret")
                .build(),
            currentUserOf(owner));
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE object_id = ?", library.library().getId().toString());

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis")
            .sourceUrl(URI.create("https://files.example.com/documents/"))
            .build();

    libraryService.updateLibrary(library.library().getId(), request, currentUserOf(owner, false));

    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_log WHERE object_id = ? AND event_type = ?",
            Integer.class,
            library.library().getId().toString(),
            "LIBRARY_SOURCE_UPDATED");
    assertThat(count).isZero();
  }

  @Test
  void updateLibraryRecordsBothLibraryChangedAndLibrarySourceUpdatedWhenNameAndSourceBothChange() {
    // Code review finding 3 (PR #578): a rename combined with a source configuration change
    // must write both events, not just one - pinning the same "independent events" guarantee
    // #392's ASSET_VISIBILITY_CHANGED/LIBRARY_CHANGED pairing already has, now for
    // LIBRARY_CHANGED/LIBRARY_SOURCE_UPDATED.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Web-Verzeichnis", DocumentSourceType.HTTP_DIRECTORY)
                .sourceUrl(URI.create("https://old.example.com/documents/"))
                .sourceCredentials("admin:old-secret")
                .build(),
            currentUserOf(owner));
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE object_id = ?", library.library().getId().toString());

    LibraryUpdate request =
        libraryUpdate("Web-Verzeichnis umbenannt")
            .sourceUrl(URI.create("https://new.example.com/documents/"))
            .sourceCredentials("admin:new-secret")
            .build();

    libraryService.updateLibrary(library.library().getId(), request, currentUserOf(owner, false));

    List<String> eventTypes =
        jdbcTemplate.queryForList(
            "SELECT event_type FROM audit_log WHERE object_id = ?",
            String.class,
            library.library().getId().toString());
    assertThat(eventTypes).containsExactlyInAnyOrder("LIBRARY_CHANGED", "LIBRARY_SOURCE_UPDATED");
  }

  @Test
  void updateLibraryLeavesTheSourceConfigurationUntouchedWhenTheRequestCarriesNoConfigField() {
    // A request that only renames the library - every caller today, e.g.
    // LibraryManagementPage's rename/visibility form - must not null out an existing
    // FILESYSTEM/HTTP_DIRECTORY/RSS_FEED configuration merely because those fields were absent
    // from that unrelated request.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
                .sourcePath("/data/documents")
                .build(),
            currentUserOf(owner));

    LibraryUpdate request = libraryUpdate("Verzeichnis umbenannt").build();

    LibraryDetail updated =
        libraryService.updateLibrary(
            library.library().getId(), request, currentUserOf(owner, false));

    assertThat(updated.library().getName()).isEqualTo("Verzeichnis umbenannt");
    assertThat(updated.managementDetail().sourcePath()).isEqualTo("/data/documents");
    assertThat(libraryRepository.findById(library.library().getId()).orElseThrow().getSourcePath())
        .isEqualTo("/data/documents");
  }

  @Test
  void createGroupOwnedLibraryRequiresCallerToBeAMemberOfThatGroup() {
    UUID member = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    Group group = createGroup(organizationA, member);

    LibraryCreation asMember =
        libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD)
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(group.getId())
            .build();
    LibraryDetail response = libraryService.createLibrary(asMember, currentUserOf(member));
    assertThat(response.library().getOwnerType()).isEqualTo(LibraryOwnerType.GROUP);
    assertThat(response.library().getOwnerId()).isEqualTo(group.getId());

    LibraryCreation asOutsider =
        libraryCreation("Zweiter Versuch", DocumentSourceType.UPLOAD)
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(group.getId())
            .build();
    assertThatThrownBy(() -> libraryService.createLibrary(asOutsider, currentUserOf(outsider)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void createGroupOwnedLibraryTreatsAGroupFromAnotherOrganizationAsNotFound() {
    UUID caller = createUser(organizationA);
    UUID otherOrgMember = createUser(organizationB);
    Group groupInOtherOrg = createGroup(organizationB, otherOrgMember);

    LibraryCreation request =
        libraryCreation("Fremde Organisation", DocumentSourceType.UPLOAD)
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(groupInOtherOrg.getId())
            .build();

    // 404, not 403 - a caller must not be able to distinguish "no such group" from "group in
    // another organization" (#199's lesson for foreign ids in a request body).
    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(caller)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void createGroupOwnedLibraryRejectsADissolvedGroupAsOwner() {
    // #441: createLibrary used to write the group's MANAGER grant directly, bypassing
    // AssetGrantService#requireGrantableGroup's dissolved-group check that upsertGrant already
    // enforces for every other grant - see that method's Javadoc.
    UUID member = createUser(organizationA);
    Group group = createGroup(organizationA, member);
    group.dissolve(Instant.now());
    groupRepository.save(group);

    LibraryCreation request =
        libraryCreation("Aufgeloeste Gruppe", DocumentSourceType.UPLOAD)
            .ownerType(LibraryOwnerType.GROUP)
            .ownerId(group.getId())
            .build();

    assertThatThrownBy(() -> libraryService.createLibrary(request, currentUserOf(member)))
        .isInstanceOf(ValidationException.class);
    assertThat(libraryRepository.findAll())
        .noneMatch(l -> "Aufgeloeste Gruppe".equals(l.getName()));
  }

  @Test
  void getLibraryTreatsALibraryFromAnotherOrganizationAsNotFoundEvenForASystemAdmin() {
    UUID ownerInA = createUser(organizationA);
    UUID adminInB = createUser(organizationB);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Bibliothek A", DocumentSourceType.UPLOAD).build(),
            currentUserOf(ownerInA));

    assertThatThrownBy(
            () ->
                libraryService.getLibrary(library.library().getId(), currentUserOf(adminInB, true)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void organizationWideVisibilityGrantsReadButNotManageToOtherOrganizationMembers() {
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen", DocumentSourceType.UPLOAD)
                .visibility(LibraryVisibility.ORGANIZATION)
                .build(),
            currentUserOf(owner));

    // Read succeeds for any member of the same organization once visibility is ORGANIZATION.
    LibraryDetail read =
        libraryService.getLibrary(library.library().getId(), currentUserOf(otherMember, false));
    assertThat(read.library().getId()).isEqualTo(library.library().getId());

    // Organization-wide visibility grants read, not manage - only the owner (or a group member,
    // or a system admin) may update.
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.library().getId(),
                    libraryUpdate("Umbenannt").build(),
                    currentUserOf(otherMember, false)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void getLibraryHidesSourceConfigurationFromAViewerButNotFromAManagerOrAbove() {
    // #507: sourcePath/sourceUrl/sourceProxy/sourceInsecureSsl/sourceCredentialsSet expose
    // internal server infrastructure (a filesystem path here) - fine for whoever may change it
    // (MANAGER+, the same bar updateLibrary enforces), a leak for a mere VIEWER of an
    // organization-wide connector library.
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    UUID editorGrantee = createUser(organizationA);
    UUID managerGrantee = createUser(organizationA);
    LibraryDetail created =
        libraryService.createLibrary(
            libraryCreation("Verzeichnis", DocumentSourceType.FILESYSTEM)
                .sourcePath("/data/documents")
                .visibility(LibraryVisibility.ORGANIZATION)
                .build(),
            currentUserOf(owner));
    // #507 code review, finding 2: an explicit EDITOR grant (one rank below the MANAGER bar) and
    // an explicit MANAGER grant, so an accidental weakening to atLeast(EDITOR) - a plausible typo
    // given AssetRole's general Javadoc calls EDITOR the "changes configuration" rank - fails this
    // test, not just the coarser OWNER/VIEWER pairing above.
    grantService.upsertGrant(
        created.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, editorGrantee, AssetRole.EDITOR),
        currentUserOf(owner, false));
    grantService.upsertGrant(
        created.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, managerGrantee, AssetRole.MANAGER),
        currentUserOf(owner, false));

    // The owner holds OWNER (at least MANAGER) and sees the full source configuration.
    LibraryDetail asOwner =
        libraryService.getLibrary(created.library().getId(), currentUserOf(owner, false));
    assertThat(asOwner.myRole()).isEqualTo(AssetRole.OWNER);
    assertThat(asOwner.managementDetail().sourcePath()).isEqualTo("/data/documents");
    assertThat(asOwner.managementDetail().sourceInsecureSsl()).isNotNull();
    assertThat(asOwner.managementDetail().sourceCredentialsSet()).isNotNull();

    // A direct MANAGER grantee sees it too - the bar is MANAGER, not OWNER specifically.
    LibraryDetail asManager =
        libraryService.getLibrary(created.library().getId(), currentUserOf(managerGrantee, false));
    assertThat(asManager.myRole()).isEqualTo(AssetRole.MANAGER);
    assertThat(asManager.managementDetail().sourcePath()).isEqualTo("/data/documents");
    assertThat(asManager.managementDetail().sourceInsecureSsl()).isNotNull();
    assertThat(asManager.managementDetail().sourceCredentialsSet()).isNotNull();

    // An EDITOR grantee - one rank above VIEWER, one below the MANAGER bar - must not receive the
    // source configuration fields either.
    LibraryDetail asEditor =
        libraryService.getLibrary(created.library().getId(), currentUserOf(editorGrantee, false));
    assertThat(asEditor.myRole()).isEqualTo(AssetRole.EDITOR);
    assertThat(asEditor.managementDetail().sourcePath()).isNull();
    assertThat(sourceUrl(asEditor)).isNull();
    assertThat(asEditor.managementDetail().sourceProxy()).isNull();
    assertThat(asEditor.managementDetail().sourceInsecureSsl()).isNull();
    assertThat(asEditor.managementDetail().sourceCredentialsSet()).isNull();

    // Another organization member only reaches VIEWER through the ORGANIZATION-wide visibility
    // and must not receive the source configuration fields at all.
    LibraryDetail asViewer =
        libraryService.getLibrary(created.library().getId(), currentUserOf(otherMember, false));
    assertThat(asViewer.myRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(asViewer.managementDetail().sourcePath()).isNull();
    assertThat(sourceUrl(asViewer)).isNull();
    assertThat(asViewer.managementDetail().sourceProxy()).isNull();
    assertThat(asViewer.managementDetail().sourceInsecureSsl()).isNull();
    assertThat(asViewer.managementDetail().sourceCredentialsSet()).isNull();
    // sourceType itself (the connector kind, not where it points) stays visible to everyone.
    assertThat(asViewer.library().getSourceType()).isEqualTo(DocumentSourceType.FILESYSTEM);
  }

  @Test
  void getLibraryShowsStorageQuotaUsageOnlyToAManagerOrAbove() {
    // #119: storage quota/usage is administration detail (like sourcePath above), gated the same
    // way at MANAGER - a mere VIEWER does not need it to read the library.
    UUID owner = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    LibraryDetail created =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD)
                .visibility(LibraryVisibility.ORGANIZATION)
                .build(),
            currentUserOf(owner));

    LibraryDetail asOwner =
        libraryService.getLibrary(created.library().getId(), currentUserOf(owner, false));
    assertThat(asOwner.myRole()).isEqualTo(AssetRole.OWNER);
    assertThat(asOwner.managementDetail().storageQuotaBytes()).isNotNull().isPositive();
    assertThat(asOwner.managementDetail().storageUsedBytes()).isNotNull().isZero();

    LibraryDetail asViewer =
        libraryService.getLibrary(created.library().getId(), currentUserOf(otherMember, false));
    assertThat(asViewer.myRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(asViewer.managementDetail().storageQuotaBytes()).isNull();
    assertThat(asViewer.managementDetail().storageUsedBytes()).isNull();
  }

  @Test
  void noAccessAtAllAnswers404ButInsufficientAccessAnswers403AcrossEveryLibraryEndpoint() {
    // #436: the same "no access at all" (404) vs. "some access, but not enough" (403) distinction
    // #420 introduced for the two upload endpoints, unified across the rest of the library API -
    // getLibrary, listDocuments, updateLibrary, deleteLibrary and AssetGrantService#listGrants must
    // all agree, so a caller cannot infer a library's existence from whichever endpoint still
    // answered 403 before this fix.
    UUID owner = createUser(organizationA);
    UUID viewer = createUser(organizationA);
    UUID stranger = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, viewer, AssetRole.VIEWER),
        currentUserOf(owner, false));

    // stranger holds no grant at all on this (default PRIVATE) library - every endpoint answers
    // 404, not 403.
    assertThatThrownBy(
            () ->
                libraryService.getLibrary(
                    library.library().getId(), currentUserOf(stranger, false)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                libraryService.listDocuments(
                    library.library().getId(),
                    currentUserOf(stranger),
                    null,
                    null,
                    PageRequest.of(0, 10)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.library().getId(),
                    libraryUpdate("Umbenannt").build(),
                    currentUserOf(stranger, false)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                libraryService.deleteLibrary(
                    library.library().getId(), currentUserOf(stranger, false)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                grantService.listGrants(library.library().getId(), currentUserOf(stranger, false)))
        .isInstanceOf(NotFoundException.class);

    // viewer holds VIEWER - enough to read, not enough to manage or delete - every
    // insufficient-access endpoint answers 403, not 404.
    assertThat(
            libraryService
                .getLibrary(library.library().getId(), currentUserOf(viewer, false))
                .library()
                .getId())
        .isEqualTo(library.library().getId());
    assertThat(
            libraryService
                .listDocuments(
                    library.library().getId(),
                    currentUserOf(viewer, false),
                    null,
                    null,
                    PageRequest.of(0, 10))
                .page())
        .isZero();
    assertThatThrownBy(
            () ->
                libraryService.updateLibrary(
                    library.library().getId(),
                    libraryUpdate("Umbenannt").build(),
                    currentUserOf(viewer, false)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                libraryService.deleteLibrary(
                    library.library().getId(), currentUserOf(viewer, false)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () -> grantService.listGrants(library.library().getId(), currentUserOf(viewer, false)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void cannotDeleteALibraryThatStillContainsDocuments() {
    // #201/#305 code review: fk_documents_library_organization is RESTRICT, so deleting a library
    // that still contains documents must be blocked with a clean 409, not surface an unhandled
    // DataIntegrityViolationException (500).
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Nicht leer", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));
    Document document = new Document("dienstanweisung.pdf", "/tmp/dienstanweisung.pdf", null, 10L);
    document.setLibraryId(library.library().getId());
    document.setOrganizationId(organizationA);
    documentRepository.save(document);

    assertThatThrownBy(
            () ->
                libraryService.deleteLibrary(
                    library.library().getId(), currentUserOf(owner, false)))
        .isInstanceOf(ConflictException.class);
    assertThat(libraryRepository.findById(library.library().getId())).isPresent();

    // Once the library is empty, deletion succeeds - the check is a live guard, not a one-time
    // flag on the library.
    documentRepository.delete(document);
    libraryService.deleteLibrary(library.library().getId(), currentUserOf(owner, false));
    assertThat(libraryRepository.findById(library.library().getId())).isEmpty();
  }

  @Test
  void cannotDeleteALibraryWhileAnIndexingRunIsRunningButCanOnceItFinishes() {
    // #433: deleting the library a RUNNING indexing run targets used to let that run's
    // documentRepository.save fail against fk_documents_library_organization once the library was
    // gone, surfacing per document as a failed DataIntegrityViolationException instead of a clean
    // outcome. The maintainer decided to block the delete itself with a 409 instead.
    UUID owner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Laufende Indizierung", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    IndexingJob job = new IndexingJob(JobStatus.RUNNING);
    job.setLibraryId(library.library().getId());
    job.setOrganizationId(organizationA);
    indexingJobRepository.save(job);

    assertThatThrownBy(
            () ->
                libraryService.deleteLibrary(
                    library.library().getId(), currentUserOf(owner, false)))
        .isInstanceOf(ConflictException.class)
        .satisfies(
            ex -> {
              assertThat(ex.getMessage()).contains("indiziert");
            });
    assertThat(libraryRepository.findById(library.library().getId())).isPresent();

    // Once the run leaves RUNNING, deletion succeeds - the check is a live guard against the
    // current job state, not a one-time flag on the library.
    job.setStatus(JobStatus.COMPLETED);
    indexingJobRepository.save(job);
    libraryService.deleteLibrary(library.library().getId(), currentUserOf(owner, false));
    assertThat(libraryRepository.findById(library.library().getId())).isEmpty();

    indexingJobRepository.delete(job);
  }

  @Test
  void creatingAGroupOwnedLibraryGrantsManagerToTheGroupAndOwnerToTheCreatorButNoOutsider() {
    // #202 code review, two rounds. Round 1 corrected the original bug: granting OWNER to the
    // *creator personally* on a GROUP-owned library left the library owned by an individual in
    // every way that matters, so round 1 moved OWNER onto the group instead. Measurement in round
    // 2 (Befund 2) showed that went a step too far: every current *and future* member of the
    // owning group automatically became OWNER - able to delete the library and transfer ownership
    // - growing without a human decision point as a directory-synchronised group's membership
    // grows (#237), structurally the same defect #201 had, one level up. It was also not
    // demotable: being the library's only OWNER grant, both the escalation guard
    // (AssetGrantService#requireCallerCanTouchExistingGrant, once it existed) and the
    // last-active-OWNER guard permanently protected it - measured as a 409 on both the downgrade
    // and the revoke path, contradicting the round-1 Javadoc's claim that "a MANAGER can downgrade
    // or revoke it at any time".
    //
    // The settled rule: the group gets MANAGER (sharing, granting roles to others - the actual
    // day-to-day need behind "Rechtsquellen Soziales", owner "Referat 50 * Grundsatz", surviving
    // its creator's departure), and the creator personally gets OWNER (delete, transfer
    // ownership - reserved for a named, accountable person). The known price - OWNER is lost when
    // its holder leaves - is what #240 (succession instead of blocking) exists to regulate, not
    // this class. An outsider - not a member of the owning group at all - still has no access
    // whatsoever, which is the actual invariant #201 broke (mere existence of *a* group somewhere
    // never implies access; only membership in the group an explicit grant targets does).
    UUID creator = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    Group group = createGroup(organizationA, creator, otherMember);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(group.getId())
                .build(),
            currentUserOf(creator));

    // The creator personally holds OWNER; other group members hold MANAGER via the group grant.
    KnowledgeLibrary persistedLibrary =
        libraryRepository.findById(library.library().getId()).orElseThrow();
    assertThat(accessService.effectiveRole(persistedLibrary, creator, false))
        .isEqualTo(AssetRole.OWNER);
    assertThat(accessService.effectiveRole(persistedLibrary, otherMember, false))
        .isEqualTo(AssetRole.MANAGER);

    // MANAGER (via the group grant) is still enough to read and to manage - rename, change
    // visibility - the library.
    assertThat(
            libraryService
                .getLibrary(library.library().getId(), currentUserOf(otherMember, false))
                .library()
                .getId())
        .isEqualTo(library.library().getId());
    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Umbenannt von otherMember").build(),
        currentUserOf(otherMember, false));

    // An outsider - not a member of this group - has no access at all, so getLibrary answers 404
    // (#436), the same "does not exist" a caller with no relationship to the library at all sees.
    assertThatThrownBy(
            () ->
                libraryService.getLibrary(
                    library.library().getId(), currentUserOf(outsider, false)))
        .isInstanceOf(NotFoundException.class);

    // otherMember, holding only MANAGER, cannot revoke the creator's personal OWNER grant - the
    // escalation guard this exact scenario motivated (Befund 1): a MANAGER may never touch a grant
    // that already carries a role higher than its own, regardless of the last-active-OWNER count.
    AssetGrant creatorsOwnerGrant =
        grantRepository.findByLibraryId(library.library().getId()).stream()
            .filter(g -> g.getSubjectType() == PermissionSubjectType.USER)
            .filter(g -> creator.equals(g.getSubjectUserId()))
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                grantService.revokeGrant(
                    library.library().getId(),
                    creatorsOwnerGrant.getId(),
                    currentUserOf(otherMember, false)))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aGroupMemberHoldingOnlyManagerCannotDeleteTheLibraryEvenThoughItCanManageIt() {
    // #202 code review round 3 (Blocker 1): AssetRole reserves "delete the asset and transfer
    // ownership" for OWNER alone - deleteLibrary must gate on that, not on canManage (MANAGER).
    // Before this fix, otherMember - holding only the group's MANAGER grant, never able to touch
    // the creator's OWNER grant directly (see the escalation guard exercised above) - could still
    // delete the whole library outright, taking every grant on it down with it via
    // fk_asset_grants_library_organization's ON DELETE CASCADE (migration 013): a detour all the
    // way around the round-1/round-2 escalation guards instead of being stopped by them. This is
    // strictly worse for a migrated, backfilled group-owned library, which deliberately carries no
    // OWNER grant at all (013-asset-grants.yaml's backfill comment) - there, every member could
    // delete a library nobody could even downgrade the group's grant on.
    UUID creator = createUser(organizationA);
    UUID otherMember = createUser(organizationA);
    Group group = createGroup(organizationA, creator, otherMember);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(group.getId())
                .build(),
            currentUserOf(creator));

    // otherMember can still manage (rename, change visibility) via the group's MANAGER grant...
    libraryService.updateLibrary(
        library.library().getId(),
        libraryUpdate("Umbenannt von otherMember").build(),
        currentUserOf(otherMember, false));
    // ...but cannot delete: that requires OWNER, which only the creator personally holds.
    assertThatThrownBy(
            () ->
                libraryService.deleteLibrary(
                    library.library().getId(), currentUserOf(otherMember, false)))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(libraryRepository.findById(library.library().getId())).isPresent();

    // The creator, holding OWNER, can delete it.
    libraryService.deleteLibrary(library.library().getId(), currentUserOf(creator, false));
    assertThat(libraryRepository.findById(library.library().getId())).isEmpty();
  }

  @Test
  void aGroupGrantOnAPersonallyOwnedLibraryReachesItsMembersAndRevocationTakesEffectImmediately() {
    // The group-grant counterpart of revokingAGrantTakesEffectOnTheNextCall, using a plain
    // USER-owned library (not the owning group itself, to keep this test independent of
    // creatingAGroupOwnedLibraryGrantsManagerToTheGroupAndOwnerToTheCreatorButNoOutsider's
    // concern):
    // an explicit grant to an ordinary AD_HOC group reaches its current members exactly like a
    // direct grant would, and losing membership removes access on the next call.
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = createGroup(organizationA, member);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));

    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner, false));

    LibraryDetail read =
        libraryService.getLibrary(library.library().getId(), currentUserOf(member, false));
    assertThat(read.library().getId()).isEqualTo(library.library().getId());

    // Removing the membership through the real GroupService (not a raw repository update) is the
    // point of this test: GroupService#removeMember evicts GroupMembershipResolver's per-user
    // cache entry after its own transaction commits (see GroupService#invalidateAfterCommit).
    // LibraryAccessService reads group membership exclusively through that same resolver, so this
    // proves the two classes are wired to the same cache instance and that the eviction actually
    // reaches it - a raw repository update bypassing GroupService would leave the resolver's
    // cache stale and make this assertion pass for the wrong reason (a cache that was never
    // populated) or fail where it should not.
    groupService.removeMember(group.getId(), member, currentUserOf(owner));

    // No grant left at all reaches the (private, ORGANIZATION-less-by-default) library, so this
    // answers 404 (#436), not 403.
    assertThatThrownBy(
            () ->
                libraryService.getLibrary(library.library().getId(), currentUserOf(member, false)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void revokingAGrantTakesEffectOnTheNextCall() {
    // #202 acceptance criteria: "Revoking a grant takes effect on the next query." Exercises
    // LibraryAccessService's per-library grant cache and AssetGrantService's afterCompletion
    // invalidation together, not either in isolation.
    UUID owner = createUser(organizationA);
    UUID viewer = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));

    var grant =
        grantService.upsertGrant(
            library.library().getId(),
            new AssetGrantUpsert(PermissionSubjectType.USER, viewer, AssetRole.VIEWER),
            currentUserOf(owner, false));
    assertThat(
            libraryService
                .getLibrary(library.library().getId(), currentUserOf(viewer, false))
                .library()
                .getId())
        .isEqualTo(library.library().getId());

    grantService.revokeGrant(
        library.library().getId(), grant.grant().getId(), currentUserOf(owner, false));

    // The revoked grant was the viewer's only access to this (default-PRIVATE) library, so this
    // answers 404 (#436), not 403.
    assertThatThrownBy(
            () ->
                libraryService.getLibrary(library.library().getId(), currentUserOf(viewer, false)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void listGrantsResolvesDisplayNamesForACallerWithoutSystemAdmin() {
    // #423 code review, finding 1: GET /v1/admin/users and /v1/admin/groups both require
    // SYSTEM_ADMIN, so the frontend cannot resolve subject/granter names itself for a MANAGER
    // without that role - the exact caller this endpoint's own MANAGER threshold is meant to
    // admit. This proves the backend resolves both names through the real repositories/schema
    // with systemAdmin = false, not just in the AssetGrantServiceTest unit test's mocks.
    UUID owner = createUser(organizationA, "Eigentümerin");
    UUID subjectUser = createUser(organizationA, "Empfänger Person");
    Group subjectGroup = createGroup(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));

    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, subjectUser, AssetRole.VIEWER),
        currentUserOf(owner, false));
    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.GROUP, subjectGroup.getId(), AssetRole.VIEWER),
        currentUserOf(owner, false));

    // owner only ever holds a MANAGER-or-above library grant here, never SYSTEM_ADMIN - the
    // `false` below is the same systemAdmin flag AuthenticatedUserResolver would pass for a
    // caller whose SystemRole is USER.
    List<AssetGrantView> grants =
        grantService.listGrants(library.library().getId(), currentUserOf(owner, false));

    // Three grants in total: the two upserted above, plus the OWNER grant createLibrary always
    // creates for its caller (see createLibrarySetsMyRoleToOwnerForTheCreator) - "Eigentümerin"
    // is that third one's subject, not a granter-only name.
    assertThat(grants)
        .extracting(AssetGrantView::subjectDisplayName)
        .containsExactlyInAnyOrder("Eigentümerin", "Empfänger Person", "Referat");
    assertThat(grants)
        .extracting(AssetGrantView::grantedByDisplayName)
        .containsOnly("Eigentümerin");
  }

  @Test
  void concurrentRevocationOfTwoOwnerGrantsNeverLeavesTheLibraryWithoutAnActiveOwner()
      throws Exception {
    // #202 code review round 2, nit 2: isLastActiveOwnerGrant is a read-then-decide check with no
    // locking of its own. Two OWNER grants, two threads each revoking one at (as close to)
    // literally the same instant as CyclicBarrier can arrange, against the real Postgres schema
    // (not a mock - a mocked PlatformTransactionManager would not exercise
    // AssetGrantRepository#lockLibraryGrantsForMutation's real advisory lock, per this project's
    // rule for concurrency-sensitive invariants). Without that lock, both threads could read the
    // other's grant as still active and both would proceed, leaving zero active OWNER grants -
    // exactly the state the guard exists to prevent. With it, exactly one thread must see a 409
    // and the library must retain exactly one active OWNER grant afterwards, never zero.
    //
    // This test alone cannot catch a guard that serializes correctly but decides on stale data
    // (#202 code review round 3, blocker 2): a deleted row simply disappears from any subsequent
    // read, so a revoke-vs-revoke race can never observe staleness. See
    // #concurrentDowngradeAndRevocationOfTwoOwnerGrantsNeverLeavesTheLibraryWithoutAnActiveOwner
    // below, which pairs a downgrade with a revoke - an *updated*, not deleted, row - and is the
    // scenario that actually exposed the round-2 fix's remaining staleness bug.
    UUID firstOwner = createUser(organizationA);
    UUID secondOwner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(firstOwner));
    AssetGrant firstOwnerGrant =
        grantRepository.findByLibraryId(library.library().getId()).stream()
            .filter(g -> firstOwner.equals(g.getSubjectUserId()))
            .findFirst()
            .orElseThrow();
    var secondOwnerGrant =
        grantService.upsertGrant(
            library.library().getId(),
            new AssetGrantUpsert(PermissionSubjectType.USER, secondOwner, AssetRole.OWNER),
            currentUserOf(firstOwner, false));

    var barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Exception>> results =
          executor.invokeAll(
              List.of(
                  revokeAfterBarrier(
                      barrier, library.library().getId(), firstOwnerGrant.getId(), firstOwner),
                  revokeAfterBarrier(
                      barrier,
                      library.library().getId(),
                      secondOwnerGrant.grant().getId(),
                      secondOwner)));

      long conflicts = 0;
      long successes = 0;
      for (var result : results) {
        Exception outcome = result.get();
        if (outcome == null) {
          successes++;
        } else if (outcome instanceof ConflictException) {
          conflicts++;
        } else {
          throw new AssertionError("Unexpected outcome", outcome);
        }
      }

      assertThat(successes).as("exactly one revoke must succeed").isEqualTo(1);
      assertThat(conflicts).as("the other must be rejected as the last active owner").isEqualTo(1);
      List<AssetGrant> remainingGrants = grantRepository.findByLibraryId(library.library().getId());
      long activeOwnerCount =
          remainingGrants.stream()
              .filter(g -> g.getRole() == AssetRole.OWNER && !g.isExpired(Instant.now()))
              .count();
      assertThat(activeOwnerCount)
          .as("the library must retain exactly one active owner")
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * A task that waits for the other thread at {@code barrier} before calling {@code
   * grantService.revokeGrant}, returning the {@link ConflictException} it threw (if any) instead of
   * letting it propagate, so both outcomes can be inspected on the calling thread.
   */
  private Callable<Exception> revokeAfterBarrier(
      CyclicBarrier barrier, UUID libraryId, UUID grantId, UUID callerId) {
    return () -> {
      barrier.await();
      try {
        grantService.revokeGrant(libraryId, grantId, currentUserOf(callerId, false));
        return null;
      } catch (ConflictException e) {
        return e;
      }
    };
  }

  @Test
  void concurrentDowngradeAndRevocationOfTwoOwnerGrantsNeverLeavesTheLibraryWithoutAnActiveOwner()
      throws Exception {
    // #202 code review round 3, blocker 2: two earlier versions of this guard both failed this
    // exact scenario, for two different reasons, both only visible with a real downgrade racing a
    // real revoke - a revoke-vs-revoke race (the test above) cannot expose either, because a
    // deleted row simply disappears from any subsequent read, stale or not:
    //  1. Locking the grant rows with SELECT ... FOR UPDATE and mapping the result back to
    //     AssetGrant entities decided on a stale, already-loaded managed instance for the count,
    //     because requireManageable -> effectiveRole had already loaded the same rows into this
    //     transaction's persistence context beforehand (via LibraryAccessService's own
    //     findByLibraryId) - fixed by counting via a plain scalar aggregate query instead, which
    // has
    //     no entity identity to resolve against the persistence context.
    //  2. That scalar aggregate, still built on SELECT ... FOR UPDATE over every grant row of the
    //     library, then deadlocked under real concurrency even with a deterministic ORDER BY id on
    //     the locking query - fixed by replacing the row lock with a single per-library Postgres
    //     advisory lock (AssetGrantRepository#lockLibraryGrantsForMutation) acquired before the
    //     plain count, removing the multi-row lock-ordering question entirely.
    UUID firstOwner = createUser(organizationA);
    UUID secondOwner = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(firstOwner));
    AssetGrant firstOwnerGrant =
        grantRepository.findByLibraryId(library.library().getId()).stream()
            .filter(g -> firstOwner.equals(g.getSubjectUserId()))
            .findFirst()
            .orElseThrow();
    var secondOwnerGrant =
        grantService.upsertGrant(
            library.library().getId(),
            new AssetGrantUpsert(PermissionSubjectType.USER, secondOwner, AssetRole.OWNER),
            currentUserOf(firstOwner, false));

    var barrier = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Exception> downgradeFirstOwner =
          () -> {
            barrier.await();
            try {
              grantService.upsertGrant(
                  library.library().getId(),
                  new AssetGrantUpsert(PermissionSubjectType.USER, firstOwner, AssetRole.VIEWER),
                  currentUserOf(firstOwner, false));
              return null;
            } catch (ConflictException e) {
              return e;
            }
          };
      Callable<Exception> revokeSecondOwner =
          revokeAfterBarrier(
              barrier, library.library().getId(), secondOwnerGrant.grant().getId(), secondOwner);

      List<Future<Exception>> results =
          executor.invokeAll(List.of(downgradeFirstOwner, revokeSecondOwner));

      long conflicts = 0;
      long successes = 0;
      for (var result : results) {
        Exception outcome = result.get();
        if (outcome == null) {
          successes++;
        } else if (outcome instanceof ConflictException) {
          conflicts++;
        } else {
          throw new AssertionError("Unexpected outcome", outcome);
        }
      }

      assertThat(successes).as("exactly one of downgrade/revoke must succeed").isEqualTo(1);
      assertThat(conflicts).as("the other must be rejected as the last active owner").isEqualTo(1);
      List<AssetGrant> remainingGrants = grantRepository.findByLibraryId(library.library().getId());
      long activeOwnerCount =
          remainingGrants.stream()
              .filter(g -> g.getRole() == AssetRole.OWNER && !g.isExpired(Instant.now()))
              .count();
      assertThat(activeOwnerCount)
          .as("the library must retain exactly one active owner")
          .isEqualTo(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void readableLibraryIdsResolvesWellWithinTheHundredMillisecondBudgetOnARealDatabase() {
    // #202 asks that permission resolution add "less than 50ms" to query time; that specific
    // number could not be assessed as a load-tested SLO in this PR (see the PR description for
    // why). What this test does establish, against the real Postgres schema this codebase now
    // ships, not a mock: LibraryAccessService#readableLibraryIds - the method QueryService calls
    // on every query - resolves via a small number of indexed queries (group membership, cached
    // after the first call per GroupMembershipResolver; two asset_grants queries; one
    // organization-wide-visibility query), not a full scan or anything that grows with unrelated
    // data. A generous 100ms bound (double the target, on a Testcontainers-backed single query
    // measured by wall clock, not warmed up or repeated) catches a gross regression - an
    // accidental N+1 or a missing index - without being a flaky micro-benchmark.
    UUID owner = createUser(organizationA);
    UUID reader = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner, false));

    long startNanos = System.nanoTime();
    var readable = accessService.readableLibraryIds(reader, organizationA);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

    assertThat(readable).contains(library.library().getId());
    assertThat(elapsedMillis)
        .as("readableLibraryIds took %dms against a real Postgres schema", elapsedMillis)
        .isLessThan(100);
  }

  @Test
  void spaceMembershipAloneGrantsNoAccessToAnyLibraryNotEvenAsSpaceAdmin() {
    // #202 acceptance criteria, explicit negative test (code review nit 4): "Space membership
    // alone grants no access to any library." docs/features/spaces-and-assets.md is explicit that
    // space associations do not appear in the readableLibraries formula at all - this proves it
    // end to end against the real SpaceService, not just by the absence of wiring between the two
    // packages. spaceAdmin becomes the space's ADMIN (the highest space role, able to manage
    // members and settings) purely by creating it - full space authority, zero library authority.
    UUID libraryOwner = createUser(organizationA);
    UUID spaceAdmin = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(libraryOwner));
    var space =
        spaceService.createSpace(
            new SpaceCreation("Team Leistungsgewaehrung", null, null, null, null, null),
            currentUserOf(spaceAdmin, false));
    createdSpaceIds.add(space.getId());

    // Space authority is not library authority at all here, so this answers 404 (#436), not 403.
    assertThatThrownBy(
            () ->
                libraryService.getLibrary(
                    library.library().getId(), currentUserOf(spaceAdmin, false)))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void listLibrariesFindsALibraryReachedOnlyThroughADirectViewerGrant() {
    // #418 acceptance criterion: a user without ownership who holds a direct VIEWER grant must find
    // the library in listLibraries - the divergence between listLibraries (formerly ownership-only)
    // and LibraryAccessService#readableLibraryIds (the formula) that this issue closes.
    UUID owner = createUser(organizationA);
    UUID viewer = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, viewer, AssetRole.VIEWER),
        currentUserOf(owner, false));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(viewer, false));

    assertThat(listed).extracting(s -> s.library().getId()).contains(library.library().getId());
    assertThat(listed)
        .filteredOn(l -> l.library().getId().equals(library.library().getId()))
        .extracting(LibrarySummary::myRole)
        .containsExactly(AssetRole.VIEWER);
  }

  @Test
  void listLibrariesFindsALibraryReachedOnlyThroughAGroupGrant() {
    // Same criterion, via a grant on a group the caller belongs to rather than a direct grant.
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = createGroup(organizationA, member);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.EDITOR),
        currentUserOf(owner, false));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(member, false));

    assertThat(listed).extracting(s -> s.library().getId()).contains(library.library().getId());
    assertThat(listed)
        .filteredOn(l -> l.library().getId().equals(library.library().getId()))
        .extracting(LibrarySummary::myRole)
        .containsExactly(AssetRole.EDITOR);
  }

  @Test
  void listLibrariesExcludesALibraryReachedOnlyThroughAnExpiredGrant() {
    // Negative test: an expired grant must not surface the library, matching
    // LibraryAccessService#readableLibraryIds's own expiry check.
    UUID owner = createUser(organizationA);
    UUID formerViewer = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, formerViewer, AssetRole.VIEWER)
            .expiresAt(Instant.now().minusSeconds(60)),
        currentUserOf(owner, false));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(formerViewer, false));

    assertThat(listed)
        .extracting(s -> s.library().getId())
        .doesNotContain(library.library().getId());
  }

  @Test
  void listLibrariesShowsNothingToAUserWithNoAccessPath() {
    // Negative test: without ownership, a grant or organization-wide visibility, a library must not
    // appear - the formula knows no exception for any library, including the well-known system
    // library that existed until #521.
    UUID owner = createUser(organizationA);
    UUID outsider = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(outsider, false));

    assertThat(listed)
        .extracting(s -> s.library().getId())
        .doesNotContain(library.library().getId());
  }

  @Test
  void listLibrariesIdsMatchLibraryAccessServiceReadableLibraryIdsForTheSameUser() {
    // #418 explicit criterion: listLibraries and LibraryAccessService#readableLibraryIds must never
    // disagree on which libraries a user may see - the same divergence #406 already closed between
    // effectiveRole and readableLibraryIds, now closed between listLibraries and
    // readableLibraryIds.
    UUID owner = createUser(organizationA);
    UUID member = createUser(organizationA);
    Group group = createGroup(organizationA, member);
    LibraryDetail ownedByMember =
        libraryService.createLibrary(
            libraryCreation("Eigene Bibliothek", DocumentSourceType.UPLOAD).build(),
            currentUserOf(member));
    LibraryDetail directGrantLibrary =
        libraryService.createLibrary(
            libraryCreation("Direkter Grant", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        directGrantLibrary.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, member, AssetRole.VIEWER),
        currentUserOf(owner, false));
    LibraryDetail groupGrantLibrary =
        libraryService.createLibrary(
            libraryCreation("Gruppen-Grant", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        groupGrantLibrary.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.GROUP, group.getId(), AssetRole.VIEWER),
        currentUserOf(owner, false));
    LibraryDetail orgWideLibrary =
        libraryService.createLibrary(
            libraryCreation("Organisationsweit", DocumentSourceType.UPLOAD)
                .visibility(LibraryVisibility.ORGANIZATION)
                .build(),
            currentUserOf(owner));
    libraryService.createLibrary(
        libraryCreation("Unerreichbar fuer member", DocumentSourceType.UPLOAD).build(),
        currentUserOf(owner));

    Set<UUID> listedIds =
        libraryService.listLibraries(currentUserOf(member, false)).stream()
            .map(s -> s.library().getId())
            .collect(Collectors.toSet());
    Set<UUID> readableIds = accessService.readableLibraryIds(member, organizationA);

    assertThat(listedIds).isEqualTo(readableIds);
    assertThat(listedIds)
        .containsExactlyInAnyOrder(
            ownedByMember.library().getId(),
            directGrantLibrary.library().getId(),
            groupGrantLibrary.library().getId(),
            orgWideLibrary.library().getId());
  }

  @Test
  void listLibrariesNeverIncludesAnOrganizationWideLibraryFromAnotherOrganization() {
    // #425 review, nit 6: findAllById does not itself filter by organization - the boundary holds
    // only because readableLibraryIds draws it in every one of its three branches. Explicit
    // regression guard: an organization-wide library in organizationB must not leak into a
    // organizationA user's list.
    UUID ownerInB = createUser(organizationB);
    UUID userInA = createUser(organizationA);
    LibraryDetail orgWideInB =
        libraryService.createLibrary(
            libraryCreation("Organisationsweit in B", DocumentSourceType.UPLOAD)
                .visibility(LibraryVisibility.ORGANIZATION)
                .build(),
            currentUserOf(ownerInB));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(userInA, false));

    assertThat(listed)
        .extracting(s -> s.library().getId())
        .doesNotContain(orgWideInB.library().getId());
  }

  @Test
  void listLibrariesReturnsAStableOrderSortedByNameThenId() {
    // #425 review, nit 5: readableLibraryIds returns a HashSet with no guaranteed iteration order.
    // Two consecutive calls must return the same order, and that order must be by name.
    UUID owner = createUser(organizationA);
    LibraryDetail zebra =
        libraryService.createLibrary(
            libraryCreation("Zebra", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));
    LibraryDetail apple =
        libraryService.createLibrary(
            libraryCreation("Apple", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));
    LibraryDetail mango =
        libraryService.createLibrary(
            libraryCreation("Mango", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));

    List<UUID> firstCall =
        libraryService.listLibraries(currentUserOf(owner, false)).stream()
            .map(s -> s.library().getId())
            .toList();
    List<UUID> secondCall =
        libraryService.listLibraries(currentUserOf(owner, false)).stream()
            .map(s -> s.library().getId())
            .toList();

    assertThat(firstCall).isEqualTo(secondCall);
    List<UUID> testLibraryIds =
        List.of(zebra.library().getId(), apple.library().getId(), mango.library().getId());
    assertThat(firstCall.stream().filter(testLibraryIds::contains).toList())
        .containsExactly(apple.library().getId(), mango.library().getId(), zebra.library().getId());
  }

  @Test
  void listLibrariesReportsDocumentCountPerLibraryWithoutNPlusOne() {
    // #477: the list response carries documentCount per row, computed by DocumentRepository
    // #countByLibraryIdIn - one grouped query for the whole page, not countByLibraryId once per
    // library. A library with no documents at all (mango) must default to zero, not be missing
    // from the response or throw on a lookup miss.
    UUID owner = createUser(organizationA);
    LibraryDetail zebra =
        libraryService.createLibrary(
            libraryCreation("Zebra", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));
    LibraryDetail mango =
        libraryService.createLibrary(
            libraryCreation("Mango", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));

    Document first = new Document("a.pdf", "/tmp/477-a.pdf", null, 10L);
    first.setLibraryId(zebra.library().getId());
    first.setOrganizationId(organizationA);
    documentRepository.save(first);
    Document second = new Document("b.pdf", "/tmp/477-b.pdf", null, 10L);
    second.setLibraryId(zebra.library().getId());
    second.setOrganizationId(organizationA);
    documentRepository.save(second);

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(owner, false));

    assertThat(listed)
        .filteredOn(entry -> entry.library().getId().equals(zebra.library().getId()))
        .extracting(LibrarySummary::documentCount)
        .containsExactly(2L);
    assertThat(listed)
        .filteredOn(entry -> entry.library().getId().equals(mango.library().getId()))
        .extracting(LibrarySummary::documentCount)
        .containsExactly(0L);

    // Review finding (PR #488): the assertions above would still pass on a rollback to
    // countByLibraryId once per row - they only check the resulting numbers, not the query
    // shape. Prove the query count stays flat instead: Hibernate's own prepared-statement
    // counter for the same call must not grow when a third library (apple, with its own
    // document) is added to the page. PR #601 review, finding 2: apple is owned by a *different*
    // user than zebra/mango - the same user for all three would let the first-level persistence
    // context cache a single owner lookup and mask a regression to one owner-name query per row
    // (#438's own batching, resolveOwnerNames).
    UUID appleOwner = createUser(organizationA);
    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    boolean statisticsWerePreviouslyEnabled = statistics.isStatisticsEnabled();
    statistics.setStatisticsEnabled(true);
    try {
      statistics.clear();
      libraryService.listLibraries(currentUserOf(owner, false));
      long statementsWithTwoLibraries = statistics.getPrepareStatementCount();

      LibraryDetail apple =
          libraryService.createLibrary(
              libraryCreation("Apple", DocumentSourceType.UPLOAD)
                  .visibility(LibraryVisibility.ORGANIZATION)
                  .build(),
              currentUserOf(appleOwner));
      Document third = new Document("c.pdf", "/tmp/477-c.pdf", null, 10L);
      third.setLibraryId(apple.library().getId());
      third.setOrganizationId(organizationA);
      documentRepository.save(third);

      statistics.clear();
      libraryService.listLibraries(currentUserOf(owner, false));
      long statementsWithThreeLibraries = statistics.getPrepareStatementCount();

      assertThat(statementsWithThreeLibraries).isEqualTo(statementsWithTwoLibraries);
    } finally {
      statistics.setStatisticsEnabled(statisticsWerePreviouslyEnabled);
    }
  }

  @Test
  void listLibrariesReportsSourceTypePerLibrary() {
    // #481 review comment on #476: LibrarySummary did not carry sourceType (unlike
    // LibraryDetail), so the overview could not show the type chip without a per-library
    // detail round trip. Mirrors the documentCount coverage above for the same reason.
    UUID owner = createUser(organizationA);
    LibraryDetail upload =
        libraryService.createLibrary(
            libraryCreation("Zebra", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));
    LibraryDetail filesystem =
        libraryService.createLibrary(
            libraryCreation("Mango", DocumentSourceType.FILESYSTEM).sourcePath("/tmp/481").build(),
            currentUserOf(owner));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(owner, false));

    assertThat(listed)
        .filteredOn(entry -> entry.library().getId().equals(upload.library().getId()))
        .extracting(s -> s.library().getSourceType())
        .containsExactly(DocumentSourceType.UPLOAD);
    assertThat(listed)
        .filteredOn(entry -> entry.library().getId().equals(filesystem.library().getId()))
        .extracting(s -> s.library().getSourceType())
        .containsExactly(DocumentSourceType.FILESYSTEM);
  }

  @Test
  void listLibrariesReportsOwnerNamePerLibraryForUserAndGroupOwners() {
    // #438: the overview shows a resolved owner name instead of a generic "Gruppen-Bibliothek"
    // label - a group-owned library resolves to the group's name, a user-owned library to the
    // owner's display name, batched (not one lookup per row, mirroring the documentCount
    // coverage above).
    UUID owner = createUser(organizationA, "Erika Musterfrau");
    Group group = createGroup(organizationA, owner);
    LibraryDetail userOwned =
        libraryService.createLibrary(
            libraryCreation("Zebra", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));
    LibraryDetail groupOwned =
        libraryService.createLibrary(
            libraryCreation("Mango", DocumentSourceType.UPLOAD)
                .ownerType(LibraryOwnerType.GROUP)
                .ownerId(group.getId())
                .build(),
            currentUserOf(owner));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(owner, false));

    assertThat(listed)
        .filteredOn(entry -> entry.library().getId().equals(userOwned.library().getId()))
        .extracting(LibrarySummary::ownerName)
        .containsExactly("Erika Musterfrau");
    assertThat(listed)
        .filteredOn(entry -> entry.library().getId().equals(groupOwned.library().getId()))
        .extracting(LibrarySummary::ownerName)
        .containsExactly(group.getName());
  }

  @Test
  void listLibrariesNeverFallsBackToTheOwnersEmailAddress() {
    // PR #601 review, finding 1: unlike AssetGrantService#toResponses (audience limited to a
    // library's MANAGERs), this list reaches every reader of an organization-wide or shared
    // library - potentially the whole organization - so a USER owner with no displayName must
    // resolve to null here, not fall back to their email address the way #446 does elsewhere.
    User ownerWithoutDisplayName =
        new User(UUID.randomUUID().toString(), "test-issuer", "owner@example.com", null);
    ownerWithoutDisplayName.setOrganizationId(organizationA);
    UUID owner = userRepository.save(ownerWithoutDisplayName).getId();
    createdUserIds.add(owner);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Zebra", DocumentSourceType.UPLOAD).build(), currentUserOf(owner));

    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(owner, false));

    assertThat(listed)
        .filteredOn(entry -> entry.library().getId().equals(library.library().getId()))
        .extracting(LibrarySummary::ownerName)
        .containsOnlyNulls();
  }

  @Test
  void listLibrariesNeverBypassesToOwnerForASystemAdminUnlikeGetLibrary() {
    // #425 review, nit 2 and 3 (orchestrator decision): unlike getLibrary/updateLibrary/
    // deleteLibrary, myRole in listLibraries never bypasses to OWNER for a system admin, and
    // membership never bypasses either - a library reachable only through administering
    // everything must not look like one the admin actually owns or manages.
    UUID owner = createUser(organizationA);
    UUID admin = createUser(organizationA);
    LibraryDetail privateLibraryNoGrantForAdmin =
        libraryService.createLibrary(
            libraryCreation("Nur fuer Eigentuemer", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    LibraryDetail orgWideLibrary =
        libraryService.createLibrary(
            libraryCreation("Organisationsweit", DocumentSourceType.UPLOAD)
                .visibility(LibraryVisibility.ORGANIZATION)
                .build(),
            currentUserOf(owner));

    // getLibrary does bypass for a system admin, on a library the admin has no grant on at all.
    assertThat(
            libraryService
                .getLibrary(
                    privateLibraryNoGrantForAdmin.library().getId(), currentUserOf(admin, true))
                .myRole())
        .isEqualTo(AssetRole.OWNER);

    // listLibraries(admin, true) does not: membership still follows the formula alone...
    List<LibrarySummary> listed = libraryService.listLibraries(currentUserOf(admin, true));
    assertThat(listed)
        .extracting(s -> s.library().getId())
        .doesNotContain(privateLibraryNoGrantForAdmin.library().getId());

    // ...and myRole on a library the formula does reach (here: via organization-wide visibility)
    // reports the real VIEWER role, not an admin-bypassed OWNER.
    assertThat(listed)
        .filteredOn(l -> l.library().getId().equals(orgWideLibrary.library().getId()))
        .extracting(LibrarySummary::myRole)
        .containsExactly(AssetRole.VIEWER);
  }

  @Test
  void createLibrarySetsMyRoleToOwnerForTheCreator() {
    // #425 review, nit 2: myRole was untested on LibraryDetail (create/get/update); #418's own
    // acceptance criterion requires it on both LibrarySummary and LibraryDetail.
    UUID owner = createUser(organizationA);

    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));

    assertThat(library.myRole()).isEqualTo(AssetRole.OWNER);
  }

  @Test
  void getLibraryAndUpdateLibrarySetMyRoleToTheCallersEffectiveRole() {
    UUID owner = createUser(organizationA);
    UUID viewer = createUser(organizationA);
    LibraryDetail library =
        libraryService.createLibrary(
            libraryCreation("Rechtsquellen Soziales", DocumentSourceType.UPLOAD).build(),
            currentUserOf(owner));
    grantService.upsertGrant(
        library.library().getId(),
        new AssetGrantUpsert(PermissionSubjectType.USER, viewer, AssetRole.VIEWER),
        currentUserOf(owner, false));

    assertThat(
            libraryService
                .getLibrary(library.library().getId(), currentUserOf(viewer, false))
                .myRole())
        .isEqualTo(AssetRole.VIEWER);
    assertThat(
            libraryService
                .updateLibrary(
                    library.library().getId(),
                    libraryUpdate("Umbenannt").build(),
                    currentUserOf(owner, false))
                .myRole())
        .isEqualTo(AssetRole.OWNER);
  }

  @Test
  void savingALibraryWithANonExistentOwnerUserFailsInsteadOfSilentlyPersisting() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByUser(
            organizationA,
            "Ghost",
            "Owner does not exist",
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);

    assertThatThrownBy(() -> libraryRepository.saveAndFlush(library))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_knowledge_libraries_owner_user");
  }

  @Test
  void savingALibraryWithANonExistentOwnerGroupFailsInsteadOfSilentlyPersisting() {
    KnowledgeLibrary library =
        KnowledgeLibrary.ownedByGroup(
            organizationA,
            "Ghost",
            "Owner group does not exist",
            UUID.randomUUID(),
            LibraryVisibility.PRIVATE,
            false);

    assertThatThrownBy(() -> libraryRepository.saveAndFlush(library))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("fk_knowledge_libraries_owner_group_organization");
  }
}
