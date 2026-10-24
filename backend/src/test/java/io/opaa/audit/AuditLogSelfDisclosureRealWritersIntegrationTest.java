package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.api.types.SpaceRole;
import io.opaa.api.types.SystemRole;
import io.opaa.auth.AuthProperties;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.UserService;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.group.GroupCreation;
import io.opaa.group.GroupRepository;
import io.opaa.group.GroupService;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.permission.AssetOwnershipHistoryRepository;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.space.Space;
import io.opaa.space.SpaceCreation;
import io.opaa.space.SpaceMemberSeed;
import io.opaa.space.SpaceMembershipHistoryRepository;
import io.opaa.space.SpaceRepository;
import io.opaa.space.SpaceService;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The category {@code audit-log} against entries the real writers produce - role change, group
 * membership - so the positive list of {@link AuditSelfDisclosurePayload} meets the payloads as
 * written: the other person's id, pseudonym, name and address never reach the export. Works in an
 * organization of its own and removes it again.
 */
@OpaaIntegrationTest
class AuditLogSelfDisclosureRealWritersIntegrationTest {

  private static final String ADMIN_NAME = "Adalbert Verwalter";
  private static final String ADMIN_EMAIL = "adalbert.verwalter@sd2990.example";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private AuditLogSelfDisclosureExporter exporter;
  @Autowired private UserService userService;
  @Autowired private UserRepository users;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groups;
  @Autowired private GroupMembershipHistoryRepository membershipHistory;
  @Autowired private SpaceService spaceService;
  @Autowired private SpaceRepository spaces;
  @Autowired private SpaceMembershipHistoryRepository spaceMembershipHistory;
  @Autowired private AssetOwnershipHistoryRepository ownershipHistory;
  @Autowired private OrganizationRepository organizations;
  @Autowired private AuditActorPseudonymService pseudonyms;
  @Autowired private AuthProperties authProperties;
  @Autowired private JdbcTemplate jdbc;

  private UUID organizationId;
  private final List<UUID> userIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    userIds.clear();
    organizationId =
        organizations.save(new Organization(UUID.randomUUID(), "Selbstauskunft 2990")).getId();
  }

  @AfterEach
  void tearDown() {
    membershipHistory.deleteByUserIdIn(userIds);
    spaces.deleteAll(
        spaces.findAll().stream()
            .filter(s -> s.getOrganizationId().equals(organizationId))
            .toList());
    spaceMembershipHistory.deleteBySubjectUserIdIn(userIds);
    ownershipHistory.deleteByOwnerUserIdIn(userIds);
    groups.deleteAll(
        groups.findAll().stream()
            .filter(g -> g.getOrganizationId().equals(organizationId))
            .toList());
    jdbc.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    userIds.forEach(users::deleteById);
    organizations.deleteById(organizationId);
  }

  @Test
  void entriesOfRealWritersNameNoOtherPerson() throws Exception {
    User admin = user(ADMIN_NAME, ADMIN_EMAIL);
    User person = user("Petra Person", "petra.person@sd2990.example");
    CurrentUser byAdmin =
        CurrentUser.of(
            admin.getId(), organizationId, SystemRole.SYSTEM_ADMIN, ADMIN_NAME, ADMIN_EMAIL);

    userService.updateRole(person.getId(), SystemRole.AUDITOR, byAdmin);
    UUID groupId =
        groupService
            .createGroup(new GroupCreation("Referat 2990", "Prüfung"), byAdmin)
            .group()
            .getId();
    groupService.addMember(groupId, person.getId(), byAdmin);
    groupService.addMember(groupId, admin.getId(), byAdmin);
    groupService.removeMember(groupId, person.getId(), byAdmin);

    List<Map<String, Object>> records = export(person.getId());

    assertThat(records)
        .extracting(r -> r.get("eventType"))
        .contains("AUDITOR_ROLE_GRANTED", "GROUP_MEMBER_ADDED", "GROUP_MEMBER_REMOVED")
        .doesNotContain("GROUP_CREATED");
    assertThat(records)
        .filteredOn(r -> "AUDITOR_ROLE_GRANTED".equals(r.get("eventType")))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r)
                    .containsEntry("actor", SelfDisclosureLabels.OTHER_PERSON)
                    .containsEntry("objectAccount", SelfDisclosureLabels.YOU)
                    .containsEntry("subject", SelfDisclosureLabels.YOU)
                    .containsEntry("after", "{\"role\":\"AUDITOR\"}"));
    assertThat(records)
        .filteredOn(r -> "GROUP_MEMBER_ADDED".equals(r.get("eventType")))
        .singleElement()
        .satisfies(r -> assertThat(r).containsEntry("objectLabel", "Referat 2990"));
    String all = records.toString();
    assertThat(all)
        .doesNotContain(admin.getId().toString(), person.getId().toString())
        .doesNotContain(ADMIN_NAME, ADMIN_EMAIL)
        .doesNotContain(pseudonyms.findExistingPseudonym(admin.getId()).orElseThrow().toString())
        .doesNotContain(pseudonyms.findExistingPseudonym(person.getId()).orElseThrow().toString());
  }

  /**
   * An owner change by an administrator names neither owner as actor or subject: previous and new
   * owner find it through their pseudonym under {@code ownerId}, one entry for both, and so does
   * the owner a space was created for.
   */
  @Test
  void anOwnerChangeByAnAdminShowsInTheSelfDisclosureOfBothOwners() throws Exception {
    User admin = user(ADMIN_NAME, ADMIN_EMAIL);
    User previous = user("Berta Bisher", "berta.bisher@sd2990.example");
    User next = user("Carla Neu", "carla.neu@sd2990.example");
    CurrentUser byAdmin =
        CurrentUser.of(
            admin.getId(), organizationId, SystemRole.SYSTEM_ADMIN, ADMIN_NAME, ADMIN_EMAIL);
    Space space =
        spaceService.createSpace(
            new SpaceCreation(
                "Referat 3135",
                null,
                previous.getId(),
                List.of(SpaceMemberSeed.user(next.getId(), SpaceRole.MEMBER)),
                null),
            byAdmin);

    spaceService.transferOwnership(space.getId(), next.getId(), byAdmin);

    List<Map<String, Object>> ofPrevious = export(previous.getId());
    assertThat(ofPrevious)
        .filteredOn(r -> "SPACE_CREATED".equals(r.get("eventType")))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r)
                  .containsEntry("actor", SelfDisclosureLabels.OTHER_PERSON)
                  .containsEntry("subject", null);
              // the recorder keeps no key order: compared as JSON, not as text
              assertThat(JSON.readTree((String) r.get("after")))
                  .isEqualTo(
                      JSON.readTree(
                          "{\"name\":\"Referat 3135\",\"ownerId\":\"Sie\",\"chatAutoCleanup\":false}"));
            });
    assertThat(ofPrevious)
        .filteredOn(r -> "ASSET_OWNER_CHANGED".equals(r.get("eventType")))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r)
                    .containsEntry("actor", SelfDisclosureLabels.OTHER_PERSON)
                    .containsEntry("before", "{\"ownerId\":\"Sie\"}")
                    .containsEntry(
                        "after",
                        "{\"ownerId\":\"" + AuditSelfDisclosurePayload.OTHER_OWNER + "\"}"));
    List<Map<String, Object>> ofNext = export(next.getId());
    assertThat(ofNext)
        .extracting(r -> r.get("eventType"))
        .contains("SPACE_MEMBER_ADDED", "ASSET_OWNER_CHANGED")
        .doesNotContain("SPACE_CREATED");
    assertThat(ofNext)
        .filteredOn(r -> "ASSET_OWNER_CHANGED".equals(r.get("eventType")))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r)
                    .containsEntry(
                        "before",
                        "{\"ownerId\":\"" + AuditSelfDisclosurePayload.OTHER_OWNER + "\"}")
                    .containsEntry("after", "{\"ownerId\":\"Sie\"}"));
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type ="
                    + " 'ASSET_OWNER_CHANGED'",
                Integer.class,
                organizationId))
        .isEqualTo(1);
    String all = ofPrevious + "\n" + ofNext;
    for (User user : List.of(admin, previous, next)) {
      assertThat(all)
          .doesNotContain(user.getId().toString())
          .doesNotContain(pseudonyms.findExistingPseudonym(user.getId()).orElseThrow().toString());
    }
  }

  private User user(String name, String email) {
    User user = new User(UUID.randomUUID().toString(), authProperties.dev().issuer(), email, name);
    user.setOrganizationId(organizationId);
    User saved = users.save(user);
    userIds.add(saved.getId());
    return saved;
  }

  private List<Map<String, Object>> export(UUID account) throws Exception {
    List<Map<String, Object>> records = new ArrayList<>();
    SelfDisclosureSink sink = key -> record -> records.add(new LinkedHashMap<>(record));
    exporter.export(new SelfDisclosureSubject(account, organizationId), sink);
    return records;
  }
}
