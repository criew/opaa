package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

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
import io.opaa.permission.GroupMembershipHistoryRepository;
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

  @Autowired private AuditLogSelfDisclosureExporter exporter;
  @Autowired private UserService userService;
  @Autowired private UserRepository users;
  @Autowired private GroupService groupService;
  @Autowired private GroupRepository groups;
  @Autowired private GroupMembershipHistoryRepository membershipHistory;
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
