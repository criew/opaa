package io.opaa.space;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.NotFoundException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.PermissionSubject;
import io.opaa.test.OpaaIntegrationTest;
import io.opaa.test.OwnOrganizationFixtures;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The instruction a space gives the model for every answer in it: set, changed and cleared only by
 * a holder of the space role ADMIN, readable by every member, each change in the audit log by name
 * of the field only - the text itself never reaches the log.
 */
@OpaaIntegrationTest
class SpaceModelInstructionIntegrationTest {

  private static final String INSTRUCTION = "Wir sind ein Landkreis, keine kreisfreie Stadt.";

  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaceRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private OwnOrganizationFixtures ownOrganizationFixtures;

  private UUID organizationId;
  private UUID otherOrganizationId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Org Anweisung")).getId();
    otherOrganizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Org Anweisung fremd"))
            .getId();
  }

  @AfterEach
  void tearDown() {
    ownOrganizationFixtures.removeOrganizations(organizationId, otherOrganizationId);
  }

  @Test
  void aNewSpaceHasNoInstruction() {
    Space space = createSpace(createUser(organizationId));

    assertThat(reload(space).getModelInstruction()).isNull();
  }

  @Test
  void anAdminSetsChangesAndClearsItAndEachChangeIsAuditedWithoutTheText() {
    UUID owner = createUser(organizationId);
    Space space = createSpace(owner);

    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(owner));
    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
    spaceService.updateSpace(
        space.getId(), update(space, "Fasse dich kurz."), currentUserOf(owner));
    assertThat(reload(space).getModelInstruction()).isEqualTo("Fasse dich kurz.");
    spaceService.updateSpace(space.getId(), update(space, ""), currentUserOf(owner));
    assertThat(reload(space).getModelInstruction()).isNull();

    List<Map<String, Object>> changes = auditRows(space.getId());
    assertThat(changes).hasSize(3);
    assertThat(changes)
        .allSatisfy(
            row -> {
              assertThat(row.get("before")).asString().contains("\"modelInstruction\"");
              assertThat(row.get("after")).asString().contains("\"modelInstruction\"");
              assertThat(row.get("before") + " " + row.get("after"))
                  .doesNotContain("Landkreis")
                  .doesNotContain("Fasse dich kurz");
            });
  }

  @Test
  void aBlankTextClearsTheInstructionAndSurroundingWhitespaceIsStripped() {
    UUID owner = createUser(organizationId);
    Space space = createSpace(owner);

    spaceService.updateSpace(
        space.getId(), update(space, "  " + INSTRUCTION + "\n"), currentUserOf(owner));
    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
    spaceService.updateSpace(space.getId(), update(space, " \n\t "), currentUserOf(owner));
    assertThat(reload(space).getModelInstruction()).isNull();
  }

  @Test
  void anOmittedInstructionLeavesItUnchangedAndAnUnchangedOneWritesNoEntry() {
    UUID owner = createUser(organizationId);
    Space space = createSpace(owner);
    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(owner));

    spaceService.updateSpace(
        space.getId(), new SpaceUpdate("Umbenannt", null), currentUserOf(owner));
    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(owner));

    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
    assertThat(auditRows(space.getId()))
        .filteredOn(row -> String.valueOf(row.get("after")).contains("modelInstruction"))
        .hasSize(1);
  }

  @Test
  void aTextOfMoreThanOneThousandCharactersIsRejected() {
    UUID owner = createUser(organizationId);
    Space space = createSpace(owner);

    spaceService.updateSpace(space.getId(), update(space, "x".repeat(1000)), currentUserOf(owner));
    assertThat(reload(space).getModelInstruction()).hasSize(1000);
    assertThatThrownBy(
            () ->
                spaceService.updateSpace(
                    space.getId(), update(space, "x".repeat(1001)), currentUserOf(owner)))
        .isInstanceOf(ValidationException.class);
    assertThat(reload(space).getModelInstruction()).hasSize(1000);
  }

  @Test
  void aCuratorMayNotSetItButEveryMemberReadsIt() {
    UUID owner = createUser(organizationId);
    UUID curator = createUser(organizationId);
    UUID member = createUser(organizationId);
    Space space = createSpace(owner);
    addMember(space, owner, curator, SpaceRole.CURATOR);
    addMember(space, owner, member, SpaceRole.MEMBER);
    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(owner));

    assertThatThrownBy(
            () ->
                spaceService.updateSpace(
                    space.getId(), update(space, "Fasse dich kurz."), currentUserOf(curator)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                spaceService.updateSpace(
                    space.getId(), update(space, "Fasse dich kurz."), currentUserOf(member)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
    assertThat(spaceService.getSpace(space.getId(), currentUserOf(member)).getModelInstruction())
        .isEqualTo(INSTRUCTION);
  }

  @Test
  void anAdminMemberWhoIsNotTheOwnerSetsIt() {
    UUID owner = createUser(organizationId);
    UUID admin = createUser(organizationId);
    Space space = createSpace(owner);
    addMember(space, owner, admin, SpaceRole.ADMIN);

    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(admin));

    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
  }

  @Test
  void theSystemAdministrationWithoutTheSpaceRoleMayNotSetItButStillRenamesTheSpace() {
    UUID owner = createUser(organizationId);
    UUID systemAdmin = createUser(organizationId);
    Space space = createSpace(owner);
    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(owner));

    assertThatThrownBy(
            () ->
                spaceService.updateSpace(
                    space.getId(), update(space, "Fasse dich kurz."), systemAdminOf(systemAdmin)))
        .isInstanceOf(AccessDeniedException.class);
    spaceService.updateSpace(
        space.getId(), new SpaceUpdate("Umbenannt", null), systemAdminOf(systemAdmin));

    assertThat(reload(space).getName()).isEqualTo("Umbenannt");
    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
  }

  @Test
  void nobodyFromAnotherOrganizationReachesItNotEvenItsSystemAdministration() {
    UUID owner = createUser(organizationId);
    UUID foreignUser = createUser(otherOrganizationId);
    Space space = createSpace(owner);
    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(owner));

    assertThatThrownBy(
            () ->
                spaceService.updateSpace(
                    space.getId(), update(space, "Fasse dich kurz."), systemAdminOf(foreignUser)))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> spaceService.getSpace(space.getId(), systemAdminOf(foreignUser)))
        .isInstanceOf(NotFoundException.class);

    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
    assertThat(auditRows(space.getId())).hasSize(1);
  }

  @Test
  void thePersonSetsAnInstructionInTheirPersonalSpace() {
    UUID owner = createUser(organizationId);
    Space personal = new Space("Privater Bereich", null, true, owner, organizationId);
    personal.addMembership(SpaceMembership.ofUser(owner, SpaceRole.ADMIN, organizationId));
    Space space = spaceRepository.save(personal);

    spaceService.updateSpace(space.getId(), update(space, INSTRUCTION), currentUserOf(owner));

    assertThat(reload(space).getModelInstruction()).isEqualTo(INSTRUCTION);
  }

  private void addMember(Space space, UUID owner, UUID user, SpaceRole role) {
    spaceService.addMember(
        space.getId(), PermissionSubject.user(user, organizationId), role, currentUserOf(owner));
  }

  private SpaceUpdate update(Space space, String modelInstruction) {
    Space current = reload(space);
    return new SpaceUpdate(current.getName(), current.getDescription(), null, modelInstruction);
  }

  private Space createSpace(UUID owner) {
    return spaceService.createSpace(
        new SpaceCreation("Referat " + UUID.randomUUID(), null, owner, List.of(), null),
        currentUserOf(owner));
  }

  private Space reload(Space space) {
    return spaceRepository.findById(space.getId()).orElseThrow();
  }

  private List<Map<String, Object>> auditRows(UUID spaceId) {
    return jdbcTemplate.queryForList(
        "SELECT before::text AS before, after::text AS after FROM audit_log"
            + " WHERE object_type = 'SPACE' AND object_id = ? AND event_type = ?"
            + " AND outcome = 'SUCCESS' ORDER BY recorded_at",
        spaceId.toString(),
        AuditEventType.SPACE_CHANGED.name());
  }

  private UUID createUser(UUID organization) {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "anweisung@example.com", "Test User");
    user.setOrganizationId(organization);
    return userRepository.save(user).getId();
  }

  private CurrentUser systemAdminOf(UUID userId) {
    User user = userRepository.findById(userId).orElseThrow();
    return CurrentUser.of(
        user.getId(),
        user.getOrganizationId(),
        SystemRole.SYSTEM_ADMIN,
        user.getDisplayName(),
        user.getEmail());
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
