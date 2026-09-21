package io.opaa.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.opaa.api.types.AccessAsOfObjectType;
import io.opaa.api.types.AccessBasis;
import io.opaa.api.types.AssetRole;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.GroupKind;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.oidc.OidcProviderRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.group.Group;
import io.opaa.group.GroupRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.library.KnowledgeLibraryRepository;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetGrant;
import io.opaa.permission.AssetGrantRepository;
import io.opaa.permission.GroupMembershipHistoryCause;
import io.opaa.permission.PermissionHistoryService;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.ProviderFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The Stichtagsauskunft (#1822, ADR-0036 Entscheidung 8): the right answer for one named object,
 * still answerable after the group is gone; the window and the paging bounds that reject rather
 * than trim; the AUDITOR bar; and the entry every attempt leaves, the rejected one included.
 */
@OpaaIntegrationTest
class PointInTimeAccessIntegrationTest {

  private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-04-01T00:00:00Z");

  @Autowired private PointInTimeAccessService service;
  @Autowired private AssetGrantRepository grantRepository;
  @Autowired private PermissionHistoryService permissionHistory;
  @Autowired private KnowledgeLibraryRepository libraryRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private UUID providerId;
  private UUID auditorId;
  private UUID ordinaryUserId;
  private UUID libraryId;
  private final List<UUID> createdUserIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Stichtag " + UUID.randomUUID()))
            .getId();
    providerId = ProviderFixtures.tokenProvider(providerRepository, "groups").getId();
    auditorId = user("Revision");
    ordinaryUserId = user("Sachbearbeitung");
    libraryId =
        libraryRepository
            .save(
                KnowledgeLibrary.ownedByUser(
                    organizationId,
                    "Vorgangsablage",
                    null,
                    ordinaryUserId,
                    io.opaa.api.types.LibraryVisibility.PRIVATE,
                    false))
            .getId();
    jdbcTemplate.update(
        "UPDATE users SET system_role = ? WHERE id = ?", SystemRole.AUDITOR.name(), auditorId);
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM asset_grant_history WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM group_membership_history WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM asset_grants WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM group_memberships WHERE organization_id = ?", organizationId);
    jdbcTemplate.update("DELETE FROM groups WHERE organization_id = ?", organizationId);
    libraryRepository.deleteById(libraryId);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM audit_actor_pseudonyms WHERE organization_id = ?", organizationId);
    userRepository.deleteAllById(createdUserIds);
    providerRepository.deleteById(providerId);
    organizationRepository.deleteById(organizationId);
    createdUserIds.clear();
  }

  /**
   * The answer names the person who held the right through a group - and still does so after the
   * group has been deleted, because the history carries its id and not a foreign key (ADR-0016).
   */
  @Test
  void aPersonReachedThroughAGroupIsNamedEvenAfterTheGroupIsGone() {
    UUID groupId = group("Referat 50");
    grantHistory(groupId, AssetRole.VIEWER, FROM.plus(Duration.ofDays(1)), null);
    membershipHistory(groupId, ordinaryUserId, FROM.plus(Duration.ofDays(2)), null);
    // The live grant goes with the group; the history row is what has to answer afterwards.
    jdbcTemplate.update("DELETE FROM asset_grants WHERE subject_group_id = ?", groupId);
    groupRepository.deleteById(groupId);

    AccessAsOfResult result = readers(FROM, TO);

    assertThat(result.objectName()).isEqualTo("Vorgangsablage");
    assertThat(result.entries()).hasSize(1);
    AccessAsOfEntry entry = result.entries().get(0);
    assertThat(entry.basis()).isEqualTo(AccessBasis.GROUP_GRANT);
    assertThat(entry.userId()).isEqualTo(ordinaryUserId);
    assertThat(entry.userName()).isEqualTo("Sachbearbeitung");
    assertThat(entry.groupId()).isEqualTo(groupId);
    assertThat(entry.groupName())
        .as("a deleted group leaves its id in the history, never a name snapshot")
        .isNull();
    assertThat(entry.assetRole()).isEqualTo(AssetRole.VIEWER);
    assertThat(entry.validFrom())
        .as("the interval starts where both the grant and the membership were in force")
        .isEqualTo(FROM.plus(Duration.ofDays(2)));
    assertThat(entry.validTo()).isNull();
  }

  /**
   * The organization boundary holds for every source of the answer, not only for the object's name:
   * an organization-wide release of a foreign library must not reach it either - that would
   * disclose the existence and the release periods of another organization's library.
   */
  @Test
  void aLibraryOfAnotherOrganizationYieldsNothingAtAll() {
    UUID foreignOrganizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Fremde Stelle " + UUID.randomUUID()))
            .getId();
    UUID foreignOwnerId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO users (id, subject, issuer, email, display_name, created_at, system_role,"
            + " organization_id) VALUES (?, ?, 'test-issuer', ?, 'Fremde Person', now(), 'USER', ?)",
        foreignOwnerId,
        "stichtag-fremd-" + foreignOwnerId,
        "stichtag-fremd-" + foreignOwnerId + "@example.com",
        foreignOrganizationId);
    UUID foreignLibraryId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO knowledge_libraries (id, organization_id, name, owner_type, owner_user_id,"
            + " visibility, listed, source_type, created_at, updated_at)"
            + " VALUES (?, ?, 'Fremde Bibliothek', 'USER', ?, 'ORGANIZATION', true, 'UPLOAD',"
            + " now(), now())",
        foreignLibraryId,
        foreignOrganizationId,
        foreignOwnerId);
    jdbcTemplate.update(
        "INSERT INTO library_visibility_history (id, library_id, organization_id, visibility,"
            + " listed, cause, valid_from, created_at, external_access_state)"
            + " VALUES (?, ?, ?, 'ORGANIZATION', true, 'CREATED', ?, now(), 'NEVER_SET')",
        UUID.randomUUID(),
        foreignLibraryId,
        foreignOrganizationId,
        java.sql.Timestamp.from(FROM));

    try {
      AccessAsOfResult result =
          service.readersOf(
              organizationId,
              auditorId,
              "Beschwerde 4711",
              AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
              foreignLibraryId,
              FROM,
              TO,
              0,
              50);

      assertThat(result.entries())
          .as("a foreign object answers like an unknown one - no basis, no period")
          .isEmpty();
      assertThat(result.objectName()).isNull();
    } finally {
      jdbcTemplate.update(
          "DELETE FROM library_visibility_history WHERE library_id = ?", foreignLibraryId);
      jdbcTemplate.update("DELETE FROM knowledge_libraries WHERE id = ?", foreignLibraryId);
      jdbcTemplate.update("DELETE FROM users WHERE id = ?", foreignOwnerId);
      organizationRepository.deleteById(foreignOrganizationId);
    }
  }

  /** A membership that ended inside the window bounds the access, not the grant alone. */
  @Test
  void anAccessEndsWithTheMembershipThatCarriedIt() {
    UUID groupId = group("Projektgruppe");
    grantHistory(groupId, AssetRole.EDITOR, FROM, null);
    membershipHistory(groupId, ordinaryUserId, FROM, FROM.plus(Duration.ofDays(10)));

    AccessAsOfResult result = readers(FROM, TO);

    assertThat(result.entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.validFrom()).isEqualTo(FROM);
              assertThat(entry.validTo()).isEqualTo(FROM.plus(Duration.ofDays(10)));
            });
  }

  /** Personalrat D4: too wide is refused, not trimmed - and the refusal is itself an entry. */
  @Test
  void aWindowBeyondTheBoundIsRejectedAndRecorded() {
    assertThatThrownBy(() -> readers(TO.minus(Duration.ofDays(200)), TO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("92");

    assertThat(retrievalEvents()).hasSize(1);
    assertThat(retrievalEvents().get(0).get("outcome")).isEqualTo(AuditOutcome.DENIED.name());
  }

  @Test
  void aPageBeyondTheBoundIsRejected() {
    assertThatThrownBy(
            () ->
                service.readersOf(
                    organizationId,
                    auditorId,
                    "Beschwerde 4711",
                    AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
                    libraryId,
                    FROM,
                    TO,
                    50,
                    50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** The bar is the AUDITOR role, and the turned-away attempt is on the record as DENIED. */
  @Test
  void anAccountWithoutTheAuditorRoleIsTurnedAwayAndRecorded() {
    assertThatThrownBy(
            () ->
                service.readersOf(
                    organizationId,
                    ordinaryUserId,
                    "Neugier",
                    AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
                    libraryId,
                    FROM,
                    TO,
                    0,
                    50))
        .isInstanceOf(AccessDeniedException.class);

    List<Map<String, Object>> events = retrievalEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("outcome")).isEqualTo(AuditOutcome.DENIED.name());
    assertThat(events.get(0).get("object_type"))
        .isEqualTo(AuditObjectType.KNOWLEDGE_LIBRARY.name());
    assertThat(events.get(0).get("object_id")).isEqualTo(libraryId.toString());
  }

  @Test
  void aQueryWithoutAnAnlassIsRejected() {
    assertThatThrownBy(
            () ->
                service.readersOf(
                    organizationId,
                    auditorId,
                    "  ",
                    AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
                    libraryId,
                    FROM,
                    TO,
                    0,
                    50))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** A successful retrieval is an entry too, with the window in its payload. */
  @Test
  void everySuccessfulRetrievalIsRecordedWithItsWindow() {
    readers(FROM, TO);

    List<Map<String, Object>> events = retrievalEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("outcome")).isEqualTo(AuditOutcome.SUCCESS.name());
    assertThat((String) events.get(0).get("after")).contains(FROM.toString());
    assertThat(events.get(0).get("reason")).isEqualTo("Beschwerde 4711");
  }

  /**
   * #1833: before the cutoff the intervals are deleted, so an empty answer there is "no longer on
   * record" rather than "no access" - and the answer says which of the two it is.
   */
  @Test
  void anAnswerAboutATimeBeforeTheRetentionCutoffSaysSo() {
    AccessAsOfResult inside = readers(Instant.now().minus(Duration.ofDays(10)), Instant.now());
    assertThat(inside.beyondRetention()).isFalse();
    assertThat(inside.retentionCutoff()).isNotNull();

    Instant longAgo = inside.retentionCutoff().minus(Duration.ofDays(30));
    AccessAsOfResult outside = readers(longAgo, longAgo.plus(Duration.ofDays(1)));
    assertThat(outside.beyondRetention()).isTrue();
  }

  /**
   * The space half of the answer: a person's own membership and a membership held through a group,
   * the latter bounded by the group membership that carried it. Composed differently from the
   * library half (membership x membership instead of grant x membership), so it needs its own case.
   */
  @Test
  void aSpaceAnswersWhoWasAMemberThroughTheirOwnRowAndThroughAGroup() {
    UUID spaceId = space();
    UUID groupId = group("Projektgruppe Ost");
    spaceMembershipHistory(spaceId, ordinaryUserId, null, SpaceRole.ADMIN, FROM, null);
    spaceMembershipHistory(
        spaceId, null, groupId, SpaceRole.MEMBER, FROM, FROM.plus(Duration.ofDays(20)));
    membershipHistory(groupId, auditorId, FROM.plus(Duration.ofDays(5)), null);

    AccessAsOfResult result =
        service.readersOf(
            organizationId,
            auditorId,
            "Beschwerde 4711",
            AccessAsOfObjectType.SPACE,
            spaceId,
            FROM,
            TO,
            0,
            50);

    assertThat(result.objectName()).isEqualTo("Projekt Ost");
    assertThat(result.entries())
        .extracting(AccessAsOfEntry::basis, AccessAsOfEntry::userId, AccessAsOfEntry::spaceRole)
        .containsExactlyInAnyOrder(
            tuple(AccessBasis.DIRECT_MEMBERSHIP, ordinaryUserId, SpaceRole.ADMIN),
            tuple(AccessBasis.GROUP_MEMBERSHIP, auditorId, SpaceRole.MEMBER));
    AccessAsOfEntry throughGroup =
        result.entries().stream()
            .filter(entry -> entry.basis() == AccessBasis.GROUP_MEMBERSHIP)
            .findFirst()
            .orElseThrow();
    assertThat(throughGroup.validFrom())
        .as("the access starts with the later of the two intervals")
        .isEqualTo(FROM.plus(Duration.ofDays(5)));
    assertThat(throughGroup.validTo())
        .as("and ends with the earlier one")
        .isEqualTo(FROM.plus(Duration.ofDays(20)));
    assertThat(throughGroup.groupName()).isEqualTo("Projektgruppe Ost");

    jdbcTemplate.update("DELETE FROM space_membership_history WHERE space_id = ?", spaceId);
    jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
  }

  /** A retrieval about a space is recorded against that space, not against the log. */
  @Test
  void aSpaceRetrievalIsRecordedAgainstTheSpace() {
    UUID spaceId = space();

    service.readersOf(
        organizationId,
        auditorId,
        "Beschwerde 4711",
        AccessAsOfObjectType.SPACE,
        spaceId,
        FROM,
        TO,
        0,
        50);

    List<Map<String, Object>> events = retrievalEvents();
    assertThat(events).hasSize(1);
    assertThat(events.get(0).get("object_type")).isEqualTo(AuditObjectType.SPACE.name());
    assertThat(events.get(0).get("object_id")).isEqualTo(spaceId.toString());

    jdbcTemplate.update("DELETE FROM spaces WHERE id = ?", spaceId);
  }

  /**
   * The bound is on the work, not on the page: a group whose membership intervals exceed the cap is
   * refused outright, because composing them to hand out fifty rows is the cost the cap exists to
   * prevent. Refused, not trimmed - a trimmed answer would look complete.
   */
  @Test
  void aWindowWhoseSourceExceedsTheCapIsRefusedRatherThanTrimmed() {
    UUID groupId = group("Grosses Referat");
    grantHistory(groupId, AssetRole.VIEWER, FROM, null);
    insertMembershipRows(groupId, PointInTimeAccessService.MAX_SOURCE_INTERVALS + 1);

    assertThatThrownBy(() -> readers(FROM, TO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("enger");

    assertThat(retrievalEvents()).as("the refusal is itself an entry").hasSize(1);
  }

  private UUID space() {
    UUID spaceId = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO spaces (id, name, owner_id, created_at, updated_at, visibility,"
            + " organization_id, is_default, archived)"
            + " VALUES (?, 'Projekt Ost', ?, now(), now(), 'PRIVATE', ?, false, false)",
        spaceId,
        ordinaryUserId,
        organizationId);
    return spaceId;
  }

  /** Writes a space-membership interval with the boundaries the case needs - see grantHistory. */
  private void spaceMembershipHistory(
      UUID spaceId, UUID userId, UUID groupId, SpaceRole role, Instant validFrom, Instant validTo) {
    jdbcTemplate.update(
        "INSERT INTO space_membership_history (id, space_id, organization_id, subject_type,"
            + " subject_user_id, subject_group_id, role, cause, valid_from, valid_to, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, 'ADDED', ?, ?, now())",
        UUID.randomUUID(),
        spaceId,
        organizationId,
        userId != null ? "USER" : "GROUP",
        userId,
        groupId,
        role.name(),
        java.sql.Timestamp.from(validFrom),
        validTo == null ? null : java.sql.Timestamp.from(validTo));
  }

  /** Enough membership intervals of one group to take the read over its cap. */
  private void insertMembershipRows(UUID groupId, int count) {
    List<Object[]> rows = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      rows.add(
          new Object[] {
            UUID.randomUUID(),
            groupId,
            organizationId,
            ordinaryUserId,
            java.sql.Timestamp.from(FROM.plus(Duration.ofSeconds(i))),
            java.sql.Timestamp.from(FROM.plus(Duration.ofSeconds(i + 1)))
          });
    }
    jdbcTemplate.batchUpdate(
        "INSERT INTO group_membership_history (id, group_id, organization_id, user_id, cause,"
            + " valid_from, valid_to, created_at) VALUES (?, ?, ?, ?, 'ADDED', ?, ?, now())",
        rows);
  }

  private AccessAsOfResult readers(Instant from, Instant to) {
    return service.readersOf(
        organizationId,
        auditorId,
        "Beschwerde 4711",
        AccessAsOfObjectType.KNOWLEDGE_LIBRARY,
        libraryId,
        from,
        to,
        0,
        50);
  }

  private List<Map<String, Object>> retrievalEvents() {
    return jdbcTemplate.queryForList(
        "SELECT outcome, object_type, object_id, reason, \"after\" FROM audit_log"
            + " WHERE organization_id = ? AND event_type = ?",
        organizationId,
        AuditEventType.PERMISSION_HISTORY_ACCESSED.name());
  }

  private UUID group(String name) {
    return groupRepository
        .save(
            new Group(
                organizationId,
                GroupKind.IDENTITY_PROVIDER,
                name,
                null,
                providerId,
                name,
                null,
                null))
        .getId();
  }

  /**
   * Writes the history row directly with the boundaries the case needs: {@link
   * PermissionHistoryService} always stamps "now", and a Stichtag question is about the past.
   */
  private void grantHistory(UUID groupId, AssetRole role, Instant validFrom, Instant validTo) {
    AssetGrant grant =
        grantRepository.save(
            AssetGrant.forGroup(
                KnowledgeLibrary.ASSET_TYPE,
                libraryId,
                organizationId,
                groupId,
                role,
                null,
                ordinaryUserId));
    permissionHistory.recordGrantCreated(grant, ordinaryUserId);
    jdbcTemplate.update(
        "UPDATE asset_grant_history SET valid_from = ?, valid_to = ?"
            + " WHERE asset_id = ? AND subject_group_id = ?",
        java.sql.Timestamp.from(validFrom),
        validTo == null ? null : java.sql.Timestamp.from(validTo),
        libraryId,
        groupId);
  }

  private void membershipHistory(UUID groupId, UUID userId, Instant validFrom, Instant validTo) {
    permissionHistory.recordMembershipAdded(
        groupId, organizationId, userId, GroupMembershipHistoryCause.ADDED, ordinaryUserId);
    jdbcTemplate.update(
        "UPDATE group_membership_history SET valid_from = ?, valid_to = ?"
            + " WHERE group_id = ? AND user_id = ?",
        java.sql.Timestamp.from(validFrom),
        validTo == null ? null : java.sql.Timestamp.from(validTo),
        groupId,
        userId);
  }

  private UUID user(String displayName) {
    User user =
        new User(
            "stichtag-" + UUID.randomUUID(),
            "https://issuer.example",
            UUID.randomUUID() + "@example.com",
            displayName);
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }
}
