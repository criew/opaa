package io.opaa.library;

import static io.opaa.library.LibraryCreationBuilder.libraryCreation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AssetOwnerType;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.DocumentSourceType;
import io.opaa.api.types.ExternalAccessState;
import io.opaa.api.types.PermissionSubjectType;
import io.opaa.api.types.SystemRole;
import io.opaa.asset.AssetGrantService;
import io.opaa.asset.AssetGrantUpsert;
import io.opaa.asset.AssetVisibilityHistoryService;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrantHistoryRepository;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.SuccessionReachGuard;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Freigabe einer Bibliothek für Fremdzugänge (#1731) against a real Postgres with the versioned
 * Liquibase schema: who may set it, that it cannot be set without a Befristung within the
 * systemwide bound, that it stops taking effect on its own, and that every one of those leaves an
 * audit entry with no Klarname.
 *
 * <p>The history side of the same operations - that the Stichtag reconstruction agrees with the
 * library - lives in {@code PermissionHistoryServiceIntegrationTest}, next to the two reach fields
 * this one shares its interval with.
 */
@OpaaIntegrationTest
class LibraryExternalAccessServiceIntegrationTest {

  @Autowired private LibraryExternalAccessService externalAccessService;
  @Autowired private SuccessionReachGuard successionGuard;
  @Autowired private KnowledgeLibraryService libraryService;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private AssetGrantService grantService;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private AssetGrantHistoryRepository grantHistoryRepository;
  @Autowired private AssetVisibilityHistoryService visibilityHistoryService;
  @Autowired private AuditEventRecorder auditEventRecorder;
  @Autowired private LibraryAccessService accessService;
  @Autowired private ExternalAccessProperties externalAccessProperties;
  @Autowired private LibraryExternalAccessTokenCounter tokenCounter;
  @Autowired private org.springframework.context.ApplicationEventPublisher eventPublisher;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    createdUserIds.clear();
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org")).getId();
  }

  @AfterEach
  void tearDown() {
    List<KnowledgeLibrary> ownLibraries =
        libraryRepository.findAll().stream()
            .filter(library -> createdUserIds.contains(library.getOwnerUserId()))
            .toList();
    libraryRepository.deleteAll(ownLibraries);
    grantHistoryRepository.deleteBySubjectUserIdIn(createdUserIds);
    // Since #1819 a library carries ownership intervals; their owner column is RESTRICT, so
    // they have to go before the accounts that hold them.
    jdbcTemplate.update(
        "DELETE FROM asset_ownership_history WHERE organization_id = ?", organizationId);
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  /** A library nobody released is not released - the shipped default, read back through the API. */
  @Test
  void aNewLibraryIsNotReleasedForExternalAccess() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);

    LibraryExternalAccess access =
        externalAccessService.describe(libraryRepository.findById(libraryId).orElseThrow());

    assertThat(access.state()).isEqualTo(ExternalAccessState.NEVER_SET);
    assertThat(access.expiresAt()).isNull();
    assertThat(access.setAt()).isNull();
    assertThat(access.tokenCount()).isZero();
    assertThat(access.maxReleaseDays()).isEqualTo(365);
  }

  @Test
  void aManagerReleasesTheLibraryUntilItsMandatoryExpiry() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

    LibraryExternalAccess access =
        externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, expiresAt);

    assertThat(access.state()).isEqualTo(ExternalAccessState.ACTIVE);
    assertThat(access.expiresAt()).isEqualTo(expiresAt);
    assertThat(access.setByDisplayName()).isEqualTo("Test User");
    assertThat(
            libraryRepository
                .findById(libraryId)
                .orElseThrow()
                .isExternalAccessActive(Instant.now()))
        .isTrue();
    assertThat(auditEventTypes(libraryId)).containsExactly("ASSET_EXTERNAL_ACCESS_CHANGED");
  }

  /**
   * The bar is "who may hand out read access", not "who may read" - a VIEWER is refused, and the
   * system administration reaches it through the ordinary administrative floor.
   */
  @Test
  void aReaderWithoutTheRightToHandOutAccessIsRefusedAndASystemAdminIsNot() {
    UUID owner = createUser();
    UUID reader = createUser();
    UUID libraryId = createLibrary(owner);
    grantService.upsertGrant(
        KnowledgeLibrary.ASSET_TYPE,
        libraryId,
        new AssetGrantUpsert(PermissionSubjectType.USER, reader, AssetRole.VIEWER),
        currentUserOf(owner));
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);

    assertThatThrownBy(
            () ->
                externalAccessService.setExternalAccess(
                    currentUserOf(reader), libraryId, true, expiresAt))
        .isInstanceOf(AccessDeniedException.class);

    externalAccessService.setExternalAccess(
        currentUserOf(reader, true), libraryId, true, expiresAt);
    assertThat(
            libraryRepository
                .findById(libraryId)
                .orElseThrow()
                .isExternalAccessActive(Instant.now()))
        .isTrue();
  }

  @Test
  void aReleaseWithoutAnExpiryOrBeyondTheSystemwideBoundIsRefused() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    CurrentUser caller = currentUserOf(owner);

    assertThatThrownBy(() -> externalAccessService.setExternalAccess(caller, libraryId, true, null))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Ablaufdatum");
    assertThatThrownBy(
            () ->
                externalAccessService.setExternalAccess(
                    caller, libraryId, true, Instant.now().plus(400, ChronoUnit.DAYS)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("365");
    assertThatThrownBy(
            () ->
                externalAccessService.setExternalAccess(
                    caller, libraryId, true, Instant.now().minusSeconds(60)))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Zukunft");

    assertThat(libraryRepository.findById(libraryId).orElseThrow().getExternalAccessState())
        .isEqualTo(ExternalAccessState.NEVER_SET);
  }

  @Test
  void takingTheReleaseBackLeavesTheWithdrawnStateAndItsOwnAuditEntry() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));

    LibraryExternalAccess access =
        externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, false, null);

    assertThat(access.state()).isEqualTo(ExternalAccessState.WITHDRAWN);
    assertThat(
            libraryRepository
                .findById(libraryId)
                .orElseThrow()
                .isExternalAccessActive(Instant.now()))
        .isFalse();
    assertThat(auditEventTypes(libraryId))
        .containsExactly("ASSET_EXTERNAL_ACCESS_CHANGED", "ASSET_EXTERNAL_ACCESS_CHANGED");
  }

  /**
   * The Befristung is the one measure that keeps the Bestand of releases from ratcheting upwards,
   * and it only is one if nobody has to remember it: the run takes the release out of effect and
   * records the Anlass, under a system actor rather than under the last person to touch it.
   */
  @Test
  void anExpiredReleaseStopsTakingEffectWithoutAnyoneActingAndSaysWhy() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));

    int expired = expiryServiceAt(Instant.now().plus(31, ChronoUnit.DAYS)).runOnce();

    assertThat(expired).isEqualTo(1);
    KnowledgeLibrary library = libraryRepository.findById(libraryId).orElseThrow();
    assertThat(library.getExternalAccessState()).isEqualTo(ExternalAccessState.EXPIRED);
    assertThat(library.isExternalAccessActive(Instant.now())).isFalse();
    assertThat(auditEventTypes(libraryId))
        .containsExactly("ASSET_EXTERNAL_ACCESS_CHANGED", "ASSET_EXTERNAL_ACCESS_EXPIRED");
    assertThat(auditActorRefs(libraryId)).contains("external-access-expiry");
    assertThat(auditAfterPayloads(libraryId))
        .anyMatch(after -> after.contains("RELEASE_PERIOD_ELAPSED"));
  }

  /**
   * #1731 review, Befund 1: the Befristung takes effect the moment it passes, not when the nightly
   * run gets round to writing it down. Between the two, a single instance can be down for days -
   * and it is this predicate that the enforcement of #1720/#1721 will ask.
   */
  @Test
  void theReleaseStopsTakingEffectAtItsExpiryEvenBeforeTheRunWritesItDown() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
    externalAccessService.setExternalAccess(currentUserOf(owner), libraryId, true, expiresAt);

    KnowledgeLibrary library = libraryRepository.findById(libraryId).orElseThrow();
    assertThat(library.getExternalAccessState()).isEqualTo(ExternalAccessState.ACTIVE);
    assertThat(library.isExternalAccessActive(expiresAt.minusSeconds(1))).isTrue();
    assertThat(library.isExternalAccessActive(expiresAt.plusSeconds(1))).isFalse();
    assertThat(library.effectiveExternalAccessState(expiresAt.plusSeconds(1)))
        .isEqualTo(ExternalAccessState.EXPIRED);

    // ... and neither the administration's Bestandsliste nor the library view still calls it
    // released, although no run has happened.
    assertThat(
            externalAccessServiceAt(expiresAt.plusSeconds(1))
                .listReleasedLibraries(currentUserOf(owner, true))
                .stream()
                .map(entry -> entry.library().getId()))
        .doesNotContain(libraryId);
    assertThat(externalAccessServiceAt(expiresAt.plusSeconds(1)).describe(library).state())
        .isEqualTo(ExternalAccessState.EXPIRED);
  }

  /**
   * #1731 review, Befund 6: history ({@code actorUserId == null}) and protocol (system actor) both
   * say "nobody acted" - the fact row must not contradict them by moving the last human act to the
   * night of the run.
   */
  @Test
  void theExpiryRunLeavesTheLastHumanActUntouched() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    KnowledgeLibrary released = libraryRepository.findById(libraryId).orElseThrow();
    Instant setAt = released.getExternalAccessSetAt();

    expiryServiceAt(Instant.now().plus(31, ChronoUnit.DAYS)).runOnce();

    KnowledgeLibrary expired = libraryRepository.findById(libraryId).orElseThrow();
    assertThat(expired.getExternalAccessSetAt()).isEqualTo(setAt);
    assertThat(expired.getExternalAccessSetByUserId()).isEqualTo(owner);
  }

  /** A second run finds nothing left to do - the run is a sweep, not a state machine tick. */
  @Test
  void asecondExpiryRunWritesNothingMore() {
    UUID owner = createUser();
    UUID libraryId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), libraryId, true, Instant.now().plus(30, ChronoUnit.DAYS));
    Instant afterwards = Instant.now().plus(31, ChronoUnit.DAYS);
    expiryServiceAt(afterwards).runOnce();

    assertThat(expiryServiceAt(afterwards).runOnce()).isZero();
    assertThat(auditEventTypes(libraryId)).hasSize(2);
  }

  @Test
  void theAdministrationSeesTheReleasedLibrariesAndAReaderDoesNot() {
    UUID owner = createUser();
    UUID releasedId = createLibrary(owner);
    UUID untouchedId = createLibrary(owner);
    externalAccessService.setExternalAccess(
        currentUserOf(owner), releasedId, true, Instant.now().plus(30, ChronoUnit.DAYS));

    List<ExternalAccessLibrary> released =
        externalAccessService.listReleasedLibraries(currentUserOf(owner, true));

    assertThat(released.stream().map(entry -> entry.library().getId()))
        .contains(releasedId)
        .doesNotContain(untouchedId);
    assertThat(released.stream().filter(entry -> entry.library().getId().equals(releasedId)))
        .allSatisfy(
            entry -> {
              assertThat(entry.access().setByDisplayName()).isEqualTo("Test User");
              assertThat(entry.access().tokenCount()).isZero();
            });
    assertThatThrownBy(() -> externalAccessService.listReleasedLibraries(currentUserOf(owner)))
        .isInstanceOf(AccessDeniedException.class);
  }

  // -----------------------------------------------------------------------------------------
  // Fixture
  // -----------------------------------------------------------------------------------------

  /**
   * The expiry run with its clock moved past the Befristung. A second instance rather than the
   * context's bean: the production bean reads the wall clock, and a class-local replacement bean
   * would split the Spring context (AGENTS.md, "Spring-Testkontexte").
   */
  private LibraryExternalAccessExpiryService expiryServiceAt(Instant now) {
    return new LibraryExternalAccessExpiryService(
        libraryRepository, visibilityHistoryService, auditEventRecorder, () -> now);
  }

  /** The release service with its clock moved - same reasoning as {@link #expiryServiceAt}. */
  private LibraryExternalAccessService externalAccessServiceAt(Instant now) {
    return new LibraryExternalAccessService(
        libraryRepository,
        accessService,
        userRepository,
        eventPublisher,
        externalAccessProperties,
        tokenCounter,
        successionGuard,
        () -> now);
  }

  private List<String> auditEventTypes(UUID libraryId) {
    return jdbcTemplate.queryForList(
        "SELECT event_type FROM audit_log WHERE object_id = ? AND event_type LIKE 'ASSET_EXTERNAL_ACCESS%' ORDER BY recorded_at, event_type",
        String.class, libraryId.toString());
  }

  private List<String> auditActorRefs(UUID libraryId) {
    return jdbcTemplate.queryForList(
        "SELECT actor_ref FROM audit_log WHERE object_id = ? AND event_type LIKE 'ASSET_EXTERNAL_ACCESS%'",
        String.class, libraryId.toString());
  }

  private List<String> auditAfterPayloads(UUID libraryId) {
    return jdbcTemplate.queryForList(
        "SELECT coalesce(after::text, '') FROM audit_log WHERE object_id = ? AND event_type LIKE 'ASSET_EXTERNAL_ACCESS%'",
        String.class, libraryId.toString());
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private UUID createLibrary(UUID ownerId) {
    return libraryService
        .createLibrary(
            libraryCreation("Bibliothek", DocumentSourceType.UPLOAD)
                .ownerType(AssetOwnerType.USER)
                .ownerId(ownerId)
                .build(),
            currentUserOf(ownerId))
        .library()
        .getId();
  }

  private CurrentUser currentUserOf(UUID userId) {
    return currentUserOf(userId, false);
  }

  private CurrentUser currentUserOf(UUID userId, boolean systemAdmin) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        userId,
        user.getOrganizationId(),
        systemAdmin ? SystemRole.SYSTEM_ADMIN : SystemRole.USER,
        user.getDisplayName());
  }
}
