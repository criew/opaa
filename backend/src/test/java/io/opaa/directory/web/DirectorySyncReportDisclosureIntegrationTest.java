package io.opaa.directory.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.GroupKind;
import io.opaa.auth.OidcClaimMapping;
import io.opaa.auth.OidcProvider;
import io.opaa.auth.OidcProviderRepository;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.directory.sync.DirectoryGroup;
import io.opaa.directory.sync.DirectorySyncPendingPlanRepository;
import io.opaa.directory.sync.DirectorySyncStatusRepository;
import io.opaa.group.Group;
import io.opaa.group.GroupMembership;
import io.opaa.group.GroupMembershipRepository;
import io.opaa.group.GroupRepository;
import io.opaa.organization.Organization;
import io.opaa.permission.GroupMembershipHistoryRepository;
import io.opaa.test.FakeDirectoryClient;
import io.opaa.test.OpaaIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A directory sync report names the persons it adds, removes and locks; every response handing one
 * to the system administration leaves a summary entry with the number of groups and persons, never
 * the names (ADR-0036, Entscheidung 9). Measured at the endpoints, so a new path to the report that
 * skips the record fails here.
 */
@OpaaIntegrationTest
class DirectorySyncReportDisclosureIntegrationTest {

  private static final UUID ORGANIZATION_ID = Organization.DEFAULT_ID;
  private static final String EVENT = AuditEventType.DIRECTORY_SYNC_REPORT_READ.name();

  @Autowired private MockMvc mockMvc;
  @Autowired private OidcProviderRepository providerRepository;
  @Autowired private GroupRepository groupRepository;
  @Autowired private GroupMembershipRepository membershipRepository;
  @Autowired private GroupMembershipHistoryRepository membershipHistoryRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private DirectorySyncStatusRepository statusRepository;
  @Autowired private DirectorySyncPendingPlanRepository pendingPlanRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private FakeDirectoryClient directoryClient;

  private final List<UUID> createdUserIds = new ArrayList<>();
  private OidcProvider provider;

  @BeforeEach
  void setUp() {
    provider =
        new OidcProvider(
            "Verzeichnis " + UUID.randomUUID(),
            "https://idp.example/realms/" + UUID.randomUUID(),
            "opaa-frontend",
            null,
            OidcClaimMapping.keycloakDefaults());
    provider.configureDirectorySync(true, 360);
    providerRepository.save(provider);
  }

  @AfterEach
  void tearDown() {
    pendingPlanRepository
        .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
        .ifPresent(pendingPlanRepository::delete);
    statusRepository
        .findByOrganizationIdAndProviderId(ORGANIZATION_ID, provider.getId())
        .ifPresent(statusRepository::delete);
    List<Group> ownGroups = groupRepository.findByProviderId(provider.getId());
    membershipHistoryRepository.deleteByUserIdIn(List.copyOf(createdUserIds));
    membershipRepository.deleteAll(
        ownGroups.stream()
            .flatMap(group -> membershipRepository.findByGroupId(group.getId()).stream())
            .toList());
    groupRepository.deleteAll(ownGroups);
    userRepository.deleteAllById(createdUserIds);
    createdUserIds.clear();
    jdbcTemplate.update(
        "DELETE FROM audit_log WHERE event_type = ? AND object_id = ?",
        EVENT,
        provider.getId().toString());
    providerRepository.deleteById(provider.getId());
    directoryClient.respondWith();
  }

  @Test
  void aDryRunNamingMembersLeavesOneSummaryEntryWithoutTheNames() throws Exception {
    createUser("anna", "Anna Amsel");
    createUser("bert", "Bert Buchfink");
    directoryClient.respondWithFor(
        provider.getId(),
        new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("anna", "bert")));

    mockMvc
        .perform(post(path("/dry-run")).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.membershipChanges[0].added.length()").value(2));

    List<Entry> entries = entries();
    assertThat(entries).hasSize(1);
    Entry entry = entries.getFirst();
    assertThat(entry.actorKind()).isEqualTo("USER");
    assertThat(entry.objectType()).isEqualTo("DIRECTORY_SYNC_RUN");
    assertThat(entry.objectLabel()).contains(provider.getDisplayName());
    assertThat(entry.after())
        .contains("\"channel\":\"DRY_RUN\"")
        .contains("\"groupCount\":1")
        .contains("\"personCount\":2")
        .contains("\"providerId\":\"" + provider.getId() + "\"");
    assertThat(entry.after() + entry.objectLabel()).doesNotContain("Anna").doesNotContain("Bert");
  }

  /** A report naming nobody discloses nothing, so it writes nothing. */
  @Test
  void aRunThatChangesNothingWritesNoEntry() throws Exception {
    createUser("anna", "Anna Amsel");
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("anna")));
    mockMvc.perform(post(path("/run"))).andExpect(status().isOk());
    assertThat(entries())
        .singleElement()
        .satisfies(entry -> assertThat(entry.after()).contains("\"channel\":\"RUN\""));

    mockMvc
        .perform(post(path("/run")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.membershipChanges.length()").value(0));

    assertThat(entries()).hasSize(1);
  }

  /** Every retrieval of the pending plan is recorded, and so is the confirmation's report. */
  @Test
  void everyRetrievalOfThePendingPlanAndItsConfirmationAreRecorded() throws Exception {
    groupLosingMostOfItsMembers();
    mockMvc.perform(post(path("/run"))).andExpect(status().isOk());

    String body =
        mockMvc
            .perform(get(path("/pending-plan")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mockMvc.perform(get(path("/pending-plan"))).andExpect(status().isOk());
    String planId = JsonPath.read(body, "$.id");

    List<Entry> pendingPlanReads =
        entries().stream().filter(e -> e.after().contains("\"channel\":\"PENDING_PLAN\"")).toList();
    assertThat(pendingPlanReads).hasSize(2);
    assertThat(pendingPlanReads.getFirst().after())
        .contains("\"planId\":\"" + planId + "\"")
        .contains("\"groupCount\":1")
        .contains("\"personCount\":2");

    mockMvc
        .perform(
            post(path("/pending-plan/" + planId + "/confirm"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Reorganisation\"}"))
        .andExpect(status().isOk());

    assertThat(entries())
        .extracting(Entry::after)
        .filteredOn(after -> after.contains("\"channel\":\"PLAN_CONFIRMATION\""))
        .singleElement()
        .satisfies(after -> assertThat(after).contains("\"planId\":\"" + planId + "\""));
  }

  @Test
  void anAbsentPendingPlanWritesNoEntry() throws Exception {
    mockMvc.perform(get(path("/pending-plan"))).andExpect(status().isNotFound());

    assertThat(entries()).isEmpty();
  }

  // ---------------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------------

  private String path(String suffix) {
    return "/api/v1/admin/oidc-providers/" + provider.getId() + "/directory-sync" + suffix;
  }

  /** One group of three members, of which the directory only reports one - above the threshold. */
  private void groupLosingMostOfItsMembers() {
    UUID keep = createUser("keep", "Kai Kleiber");
    UUID lostA = createUser("lost-a", "Lea Lerche");
    UUID lostB = createUser("lost-b", "Lutz Lori");
    Group group =
        new Group(
            ORGANIZATION_ID,
            GroupKind.ORG_UNIT,
            "Referat 50",
            null,
            provider.getId(),
            "dir-1",
            null,
            null);
    group.addMembership(new GroupMembership(keep, ORGANIZATION_ID));
    group.addMembership(new GroupMembership(lostA, ORGANIZATION_ID));
    group.addMembership(new GroupMembership(lostB, ORGANIZATION_ID));
    groupRepository.save(group);
    directoryClient.respondWithFor(
        provider.getId(), new DirectoryGroup("dir-1", "Referat 50", null, null, Set.of("keep")));
  }

  private UUID createUser(String subject, String displayName) {
    User user = new User(subject, provider.getIssuerUri(), subject + "@example.com", displayName);
    user.setOrganizationId(ORGANIZATION_ID);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }

  private List<Entry> entries() {
    return jdbcTemplate.query(
        "SELECT actor_kind, object_type, object_label, after FROM audit_log"
            + " WHERE event_type = ? AND object_id = ? ORDER BY recorded_at",
        (rs, row) ->
            new Entry(
                rs.getString("actor_kind"),
                rs.getString("object_type"),
                rs.getString("object_label"),
                rs.getString("after")),
        EVENT,
        provider.getId().toString());
  }

  private record Entry(String actorKind, String objectType, String objectLabel, String after) {}
}
