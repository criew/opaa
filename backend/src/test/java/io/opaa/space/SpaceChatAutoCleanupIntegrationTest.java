package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SpaceVisibility;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.PermissionSubject;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The switch of the automatic chat cleanup on a space (#1923): off by default, set at creation or
 * by whoever may change the space's details, its start recorded once, and every change of it in the
 * audit log with the old and the new value.
 */
@OpaaIntegrationTest
class SpaceChatAutoCleanupIntegrationTest {

  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Org Chat-Aufbewahrung"))
            .getId();
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId);
  }

  @Test
  void aNewSpaceHasTheCleanupSwitchedOff() {
    UUID owner = createUser();

    Space space = createSpace(owner, null);

    assertThat(reload(space).isChatAutoCleanupEnabled()).isFalse();
    assertThat(reload(space).getChatAutoCleanupEnabledAt()).isNull();
  }

  @Test
  void creatingWithTheSwitchOnRecordsItsStartAndNamesItInTheCreationEntry() {
    UUID owner = createUser();
    Instant before = Instant.now();

    Space space = createSpace(owner, true);

    assertThat(reload(space).getChatAutoCleanupEnabledAt()).isAfterOrEqualTo(before);
    assertThat(auditPayloads(space.getId(), AuditEventType.SPACE_CREATED, "after"))
        .singleElement()
        .asString()
        .containsPattern(flag(true));
  }

  private static String flag(boolean value) {
    return "\"chatAutoCleanup\"\\s*:\\s*" + value;
  }

  @Test
  void anAdminSwitchesItOnAndOffAndEachChangeIsAuditedWithOldAndNewValue() {
    UUID owner = createUser();
    Space space = createSpace(owner, null);

    spaceService.updateSpace(space.getId(), update(space, true), currentUserOf(owner));
    assertThat(reload(space).isChatAutoCleanupEnabled()).isTrue();
    spaceService.updateSpace(space.getId(), update(space, false), currentUserOf(owner));
    assertThat(reload(space).isChatAutoCleanupEnabled()).isFalse();

    List<Map<String, Object>> changes = auditRows(space.getId(), AuditEventType.SPACE_CHANGED);
    assertThat(changes).hasSize(2);
    assertThat(changes.get(0).get("before")).asString().containsPattern(flag(false));
    assertThat(changes.get(0).get("after")).asString().containsPattern(flag(true));
    assertThat(changes.get(1).get("before")).asString().containsPattern(flag(true));
    assertThat(changes.get(1).get("after")).asString().containsPattern(flag(false));
    assertThat(changes).allSatisfy(row -> assertThat(row.get("actor_ref")).isNotNull());
  }

  @Test
  void switchingOnAgainKeepsTheOriginalStartAndWritesNoEntry() {
    UUID owner = createUser();
    Space space = createSpace(owner, true);
    Instant start = reload(space).getChatAutoCleanupEnabledAt();

    spaceService.updateSpace(space.getId(), update(space, true), currentUserOf(owner));

    assertThat(reload(space).getChatAutoCleanupEnabledAt()).isEqualTo(start);
    assertThat(auditRows(space.getId(), AuditEventType.SPACE_CHANGED)).isEmpty();
  }

  @Test
  void anOmittedSwitchLeavesTheSettingUnchanged() {
    UUID owner = createUser();
    Space space = createSpace(owner, true);

    spaceService.updateSpace(
        space.getId(),
        new SpaceUpdate("Umbenannt", null, SpaceVisibility.PRIVATE, null),
        currentUserOf(owner));

    assertThat(reload(space).isChatAutoCleanupEnabled()).isTrue();
  }

  @Test
  void anOrdinaryMemberMayNotSwitchItButSeesItsState() {
    UUID owner = createUser();
    UUID member = createUser();
    Space space = createSpace(owner, true);
    spaceService.addMember(
        space.getId(),
        PermissionSubject.user(member, organizationId),
        SpaceRole.MEMBER,
        currentUserOf(owner));

    assertThatThrownBy(
            () ->
                spaceService.updateSpace(
                    space.getId(), update(space, false), currentUserOf(member)))
        .isInstanceOf(AccessDeniedException.class);
    SpaceDetail detail =
        spaceService.detailOf(
            spaceService.getSpace(space.getId(), currentUserOf(member)), currentUserOf(member));
    assertThat(detail.space().isChatAutoCleanupEnabled()).isTrue();
    assertThat(detail.chatAutoCleanup().archiveAfterDays()).isEqualTo(90);
    assertThat(detail.chatAutoCleanup().deleteAfterDays()).isEqualTo(365);
  }

  private SpaceUpdate update(Space space, boolean chatAutoCleanup) {
    Space current = reload(space);
    return new SpaceUpdate(
        current.getName(), current.getDescription(), current.getVisibility(), chatAutoCleanup);
  }

  private Space createSpace(UUID owner, Boolean chatAutoCleanup) {
    return spaceService.createSpace(
        new SpaceCreation(
            "Referat " + UUID.randomUUID(),
            null,
            owner,
            SpaceVisibility.PRIVATE,
            List.of(),
            null,
            chatAutoCleanup),
        currentUserOf(owner));
  }

  private Space reload(Space space) {
    return spaceRepository.findById(space.getId()).orElseThrow();
  }

  private List<Map<String, Object>> auditRows(UUID spaceId, AuditEventType type) {
    return jdbcTemplate.queryForList(
        "SELECT before::text AS before, after::text AS after, actor_ref FROM audit_log"
            + " WHERE object_type = 'SPACE' AND object_id = ? AND event_type = ?"
            + " AND outcome = 'SUCCESS' ORDER BY recorded_at",
        spaceId.toString(),
        type.name());
  }

  private List<Object> auditPayloads(UUID spaceId, AuditEventType type, String column) {
    return auditRows(spaceId, type).stream().map(row -> row.get(column)).toList();
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "frist@example.com", "Test User");
    user.setOrganizationId(organizationId);
    return userRepository.save(user).getId();
  }

  private CurrentUser currentUserOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        user.getSystemRole(),
        user.getDisplayName(),
        user.getEmail());
  }
}
