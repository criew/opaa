package io.opaa.connection.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.opaa.api.types.ConnectionEndCause;
import io.opaa.api.types.ConnectionLogEventType;
import io.opaa.api.types.ConnectionLogOwnerKind;
import io.opaa.api.types.SystemRole;
import io.opaa.audit.AuditActorPseudonymService;
import io.opaa.auth.CurrentUser;
import io.opaa.auth.DevAuthFilter;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.AccessDeniedException;
import io.opaa.common.ValidationException;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * The connection log against the real schema: {@link ConnectionLog} writes persons as audit
 * pseudonyms, only {@code AUDITOR} reads (a system administrator without the role does not), every
 * read lands in the audit log, and the daily retention drops expired partitions only.
 */
@OpaaIntegrationTest
class ConnectionLogIntegrationTest {

  private static final String ENDPOINT = "/api/v1/audit/connection-log";
  private static final String REASON = "Prüfung der Verbindungen nach Hinweis";

  @Autowired private ConnectionLog connectionLog;
  @Autowired private ConnectionLogQueryService queryService;
  @Autowired private ConnectionLogRetention retention;
  @Autowired private ConnectionLogRetentionService retentionService;
  @Autowired private AuditActorPseudonymService pseudonyms;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private MockMvc mockMvc;

  private final UUID profileId = UUID.randomUUID();
  private final Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
  private final Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
  private UUID organizationId;
  private UUID personId;
  private UUID adminId;
  private UUID auditorId;

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Connection Log Org"))
            .getId();
    personId = persistUser("person", SystemRole.USER);
    adminId = persistUser("admin", SystemRole.SYSTEM_ADMIN);
    auditorId = persistUser("auditor", SystemRole.AUDITOR);
  }

  /**
   * The organization stays so that its audit entries keep their reference; the log rows of this
   * class's profile go, then the accounts.
   */
  @AfterEach
  void tearDown() {
    jdbc.update("DELETE FROM connection_log WHERE profile_id = ?", profileId);
    jdbc.update("DELETE FROM users WHERE organization_id = ?", organizationId);
    jdbc.update("UPDATE users SET system_role = 'USER' WHERE email = 'dev-user@opaa.local'");
  }

  @Test
  void anEntryNamesPersonsOnlyByTheirAuditPseudonym() {
    connectionLog.record(
        organizationId,
        ConnectionLogEventType.CONNECTED,
        ConnectionLogActor.person(personId),
        ConnectionLogOwner.person(personId),
        profileId,
        "Nextcloud Rathaus",
        null);
    connectionLog.record(
        organizationId,
        ConnectionLogEventType.EMERGENCY_DISCONNECTED,
        ConnectionLogActor.person(adminId),
        ConnectionLogOwner.person(personId),
        profileId,
        "Nextcloud Rathaus",
        ConnectionEndCause.EMERGENCY);
    connectionLog.record(
        organizationId,
        ConnectionLogEventType.DELETED,
        ConnectionLogActor.system(),
        ConnectionLogOwner.person(personId),
        profileId,
        "Nextcloud Rathaus",
        ConnectionEndCause.ACCOUNT_DEACTIVATED);

    String person = pseudonyms.findExistingPseudonym(personId).orElseThrow().toString();
    String admin = pseudonyms.findExistingPseudonym(adminId).orElseThrow().toString();
    List<Map<String, Object>> rows =
        jdbc.queryForList("SELECT * FROM connection_log WHERE profile_id = ?", profileId);
    assertThat(rows)
        .extracting(
            row -> row.get("event_type"),
            row -> row.get("actor_ref"),
            row -> row.get("person_ref"),
            row -> row.get("cause"))
        .containsExactlyInAnyOrder(
            tuple("CONNECTED", person, person, null),
            tuple("EMERGENCY_DISCONNECTED", admin, person, "EMERGENCY"),
            tuple("DELETED", ConnectionLogActor.SYSTEM_LABEL, person, "ACCOUNT_DEACTIVATED"));
    assertThat(rows)
        .allSatisfy(
            row -> {
              assertThat(row.get("organization_id")).isEqualTo(organizationId);
              assertThat(row.get("profile_name")).isEqualTo("Nextcloud Rathaus");
              assertThat(row.values().toString())
                  .doesNotContain(personId.toString(), adminId.toString());
            });
  }

  @Test
  void aSystemAdministratorWithoutTheAuditorRoleCannotReadAndTheAttemptIsLogged() {
    record(ConnectionLogEventType.CONNECTED);

    assertThatThrownBy(() -> queryService.find(organizationId, adminId, REASON, query(null, null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> queryService.find(organizationId, personId, REASON, query(null, null)))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(accessEntries(adminId, "DENIED")).isEqualTo(1);
    assertThat(accessEntries(personId, "DENIED")).isEqualTo(1);
  }

  @Test
  void anAuditorReadsFilteredByEventTypeAndProfileAndTheReadIsLogged() {
    record(ConnectionLogEventType.CONNECTED);
    record(ConnectionLogEventType.DISCONNECTED);

    Page<ConnectionLogEntry> all =
        queryService.find(organizationId, auditorId, REASON, query(null, profileId));
    Page<ConnectionLogEntry> disconnected =
        queryService.find(
            organizationId,
            auditorId,
            REASON,
            query(ConnectionLogEventType.DISCONNECTED, profileId));
    Page<ConnectionLogEntry> otherProfile =
        queryService.find(organizationId, auditorId, REASON, query(null, UUID.randomUUID()));

    assertThat(all.getContent())
        .extracting(ConnectionLogEntry::getEventType)
        .containsExactlyInAnyOrder(
            ConnectionLogEventType.CONNECTED, ConnectionLogEventType.DISCONNECTED);
    assertThat(disconnected.getContent())
        .extracting(ConnectionLogEntry::getEventType)
        .containsExactly(ConnectionLogEventType.DISCONNECTED);
    assertThat(otherProfile.getContent()).isEmpty();
    assertThat(accessEntries(auditorId, "SUCCESS")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE organization_id = ? AND event_type ="
                    + " 'CONNECTION_LOG_ACCESSED' AND object_type = 'AUDIT_LOG' AND object_id ="
                    + " 'connection_log' AND reason = ?",
                Long.class,
                organizationId,
                REASON))
        .isEqualTo(3);
  }

  @Test
  void anAuditorMustGiveAReasonAndABoundedTimeRange() {
    assertThatThrownBy(() -> queryService.find(organizationId, auditorId, " ", query(null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                queryService.find(
                    organizationId,
                    auditorId,
                    REASON,
                    new ConnectionLogQueryService.Query(
                        to.minus(93, ChronoUnit.DAYS), to, null, null, 0, 50)))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(accessEntries(auditorId, "DENIED")).isEqualTo(2);
  }

  /**
   * A library's source connection names its library and service account and no person; a profile's
   * own connection names neither.
   */
  @Test
  void aLibrarysAndAProfilesConnectionAreLoggedWithoutAPerson() {
    UUID libraryId = UUID.randomUUID();
    connectionLog.record(
        organizationId,
        ConnectionLogEventType.CONNECTED,
        ConnectionLogActor.person(adminId),
        ConnectionLogOwner.library(libraryId, "svc-opaa@rathaus.de"),
        profileId,
        "Nextcloud Rathaus",
        null);
    connectionLog.record(
        organizationId,
        ConnectionLogEventType.EXPIRED,
        ConnectionLogActor.system(),
        ConnectionLogOwner.profile(),
        profileId,
        "Nextcloud Rathaus",
        ConnectionEndCause.SECRET_EXPIRED);

    Page<ConnectionLogEntry> page =
        queryService.find(organizationId, auditorId, REASON, query(null, profileId));

    assertThat(page.getContent())
        .extracting(
            ConnectionLogEntry::getOwnerKind,
            ConnectionLogEntry::getPersonRef,
            ConnectionLogEntry::getLibraryId,
            ConnectionLogEntry::getAccountLabel)
        .containsExactlyInAnyOrder(
            tuple(ConnectionLogOwnerKind.LIBRARY, null, libraryId, "svc-opaa@rathaus.de"),
            tuple(ConnectionLogOwnerKind.PROFILE, null, null, null));
    assertThat(pseudonyms.findExistingPseudonym(personId)).isEmpty();
  }

  /** An over-long service account address is refused, never cut to a different account. */
  @Test
  void anAccountLabelLongerThanItsColumnIsRefused() {
    UUID libraryId = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                connectionLog.record(
                    organizationId,
                    ConnectionLogEventType.CONNECTED,
                    ConnectionLogActor.person(adminId),
                    ConnectionLogOwner.library(libraryId, "a".repeat(501)),
                    profileId,
                    "Nextcloud Rathaus",
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    connectionLog.record(
        organizationId,
        ConnectionLogEventType.CONNECTED,
        ConnectionLogActor.person(adminId),
        ConnectionLogOwner.library(libraryId, "a".repeat(500)),
        profileId,
        "Nextcloud Rathaus",
        null);

    assertThat(
            jdbc.queryForList(
                "SELECT length(account_label) FROM connection_log WHERE library_id = ?",
                Integer.class,
                libraryId))
        .containsExactly(500);
  }

  /** An entry of another organization never reaches this organization's auditor. */
  @Test
  void theReadPathStaysInsideTheCallersOrganization() {
    UUID otherOrganization =
        organizationRepository.save(new Organization(UUID.randomUUID(), "Other Org")).getId();
    record(ConnectionLogEventType.CONNECTED);
    connectionLog.record(
        otherOrganization,
        ConnectionLogEventType.DISCONNECTED,
        ConnectionLogActor.system(),
        ConnectionLogOwner.person(personId),
        profileId,
        "Nextcloud Nachbarhaus",
        ConnectionEndCause.ACCOUNT_DEACTIVATED);

    Page<ConnectionLogEntry> page =
        queryService.find(organizationId, auditorId, REASON, query(null, profileId));

    assertThat(page.getContent())
        .extracting(ConnectionLogEntry::getOrganizationId)
        .containsExactly(organizationId);
  }

  @Test
  void anEndWithoutACauseOrAStartWithOneIsRefused() {
    assertThatThrownBy(
            () ->
                connectionLog.record(
                    organizationId,
                    ConnectionLogEventType.DISCONNECTED,
                    ConnectionLogActor.person(personId),
                    ConnectionLogOwner.person(personId),
                    profileId,
                    "Nextcloud Rathaus",
                    null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                connectionLog.record(
                    organizationId,
                    ConnectionLogEventType.CONNECTED,
                    ConnectionLogActor.person(personId),
                    ConnectionLogOwner.person(personId),
                    profileId,
                    "Nextcloud Rathaus",
                    ConnectionEndCause.SELF))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * The period belongs to the system administration, as for the rights history; a change is an
   * audit event with both values, and the bounds are refused with a German message.
   */
  @Test
  void theSystemAdministrationSetsThePeriodWithinItsBounds() {
    CurrentUser admin = CurrentUser.of(adminId, organizationId, SystemRole.SYSTEM_ADMIN, "Admin");
    CurrentUser auditor = CurrentUser.of(auditorId, organizationId, SystemRole.AUDITOR, "Revision");

    assertThat(retentionService.read(admin).getRetentionMonths()).isEqualTo(12);
    assertThatThrownBy(() -> retentionService.read(auditor))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> retentionService.updateRetentionMonths(auditor, 18))
        .isInstanceOf(AccessDeniedException.class);
    for (int outside : new int[] {5, 25}) {
      assertThatThrownBy(() -> retentionService.updateRetentionMonths(admin, outside))
          .isInstanceOf(ValidationException.class)
          .hasMessageContaining("zwischen 6 und 24 Monaten");
    }

    assertThat(retentionService.updateRetentionMonths(admin, 18).getRetentionMonths())
        .isEqualTo(18);

    Map<String, Object> event =
        jdbc.queryForMap(
            "SELECT before, after, object_type FROM audit_log WHERE organization_id = ? AND"
                + " event_type = 'CONNECTION_LOG_RETENTION_CHANGED'",
            organizationId);
    assertThat(event.get("before")).isEqualTo("{\"retentionMonths\":12}");
    assertThat(event.get("after")).isEqualTo("{\"retentionMonths\":18}");
    assertThat(event.get("object_type")).isEqualTo("SYSTEM_SETTING");
  }

  @Test
  void theRetentionEndpointAnswersTheSystemAdministrationOnly() throws Exception {
    String path = "/api/v1/admin/connection-log/retention";

    mockMvc
        .perform(get(path).header(DevAuthFilter.DEV_USER_HEADER, "dev-user"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(get(path).header(DevAuthFilter.DEV_USER_HEADER, "dev-admin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionMonths").value(12));
    mockMvc
        .perform(
            put(path)
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"retentionMonths\":25}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put(path)
                .header(DevAuthFilter.DEV_USER_HEADER, "dev-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"retentionMonths\":24}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.retentionMonths").value(24));
  }

  /** The endpoint answers only the auditor, with exactly the log's columns per entry. */
  @Test
  void theEndpointAnswersTheAuditorAndNoOneElse() throws Exception {
    mockMvc.perform(get("/api/v1/me").header(DevAuthFilter.DEV_USER_HEADER, "dev-user"));
    UUID devUser =
        jdbc.queryForObject("SELECT id FROM users WHERE email = 'dev-user@opaa.local'", UUID.class);
    UUID devOrganization =
        jdbc.queryForObject("SELECT organization_id FROM users WHERE id = ?", UUID.class, devUser);
    connectionLog.record(
        devOrganization,
        ConnectionLogEventType.DISCONNECTED,
        ConnectionLogActor.person(devUser),
        ConnectionLogOwner.person(devUser),
        profileId,
        "Nextcloud Rathaus",
        ConnectionEndCause.SELF);

    for (String subject : List.of("dev-user", "dev-admin")) {
      mockMvc.perform(request(subject)).andExpect(status().isForbidden());
    }
    jdbc.update("UPDATE users SET system_role = 'AUDITOR' WHERE id = ?", devUser);

    String body =
        mockMvc
            .perform(request("dev-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.entries.length()").value(1))
            .andExpect(jsonPath("$.entries[0].eventType").value("DISCONNECTED"))
            .andExpect(jsonPath("$.entries[0].cause").value("SELF"))
            .andExpect(jsonPath("$.entries[0].profileName").value("Nextcloud Rathaus"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> entry = JsonPath.read(body, "$.entries[0]");
    assertThat(entry)
        .containsKeys("eventId", "recordedAt", "organizationId", "eventType", "actorRef")
        .containsKeys("ownerKind", "personRef", "profileId", "profileName", "cause");
    assertThat(entry.keySet())
        .isSubsetOf(
            "eventId",
            "recordedAt",
            "organizationId",
            "eventType",
            "actorRef",
            "ownerKind",
            "personRef",
            "libraryId",
            "accountLabel",
            "profileId",
            "profileName",
            "cause");
    assertThat(entry.get("ownerKind")).isEqualTo("PERSON");
    assertThat(entry.get("libraryId")).isNull();
    assertThat(entry.get("accountLabel")).isNull();
    assertThat(body).doesNotContain(devUser.toString());
  }

  /** The daily run drops a partition past the period with its rows and leaves the current one. */
  @Test
  void theRetentionDropsAnExpiredPartitionAndKeepsTheCurrentOne() {
    String expired =
        jdbc.queryForObject(
            "SELECT 'connection_log_' || to_char(date_trunc('month', now()) - interval '30"
                + " months', 'YYYY_MM')",
            String.class);
    jdbc.execute(
        "CREATE TABLE "
            + expired
            + " PARTITION OF connection_log FOR VALUES FROM ((date_trunc('month', now()) -"
            + " interval '30 months')::date) TO ((date_trunc('month', now()) - interval '29"
            + " months')::date)");
    jdbc.execute("ALTER TABLE " + expired + " OWNER TO opaa_audit_owner");
    jdbc.update(
        "INSERT INTO connection_log (event_id, organization_id, recorded_at, event_type,"
            + " actor_ref, owner_kind, person_ref, profile_id, profile_name) VALUES (gen_random_uuid(), ?,"
            + " date_trunc('month', now()) - interval '30 months' + interval '1 day', 'CONNECTED',"
            + " 'a', 'PERSON', 'p', ?, 'Alt')",
        organizationId,
        profileId);
    record(ConnectionLogEventType.CONNECTED);

    retention.deleteExpiredPartitions();

    assertThat(jdbc.queryForObject("SELECT to_regclass(?) IS NULL", Boolean.class, expired))
        .isTrue();
    assertThat(
            jdbc.queryForList(
                "SELECT profile_name FROM connection_log WHERE profile_id = ?",
                String.class,
                profileId))
        .containsExactly("Nextcloud Rathaus");
  }

  private RequestBuilder request(String subject) {
    return get(ENDPOINT)
        .header(DevAuthFilter.DEV_USER_HEADER, subject)
        .param("from", from.toString())
        .param("to", to.toString())
        .param("profileId", profileId.toString())
        .param("reason", REASON);
  }

  private void record(ConnectionLogEventType eventType) {
    connectionLog.record(
        organizationId,
        eventType,
        ConnectionLogActor.person(personId),
        ConnectionLogOwner.person(personId),
        profileId,
        "Nextcloud Rathaus",
        eventType == ConnectionLogEventType.DISCONNECTED ? ConnectionEndCause.SELF : null);
  }

  private ConnectionLogQueryService.Query query(ConnectionLogEventType eventType, UUID profile) {
    return new ConnectionLogQueryService.Query(from, to, eventType, profile, 0, 50);
  }

  private long accessEntries(UUID actorId, String outcome) {
    String actorRef = pseudonyms.findExistingPseudonym(actorId).orElseThrow().toString();
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE organization_id = ? AND actor_ref = ? AND"
            + " event_type = 'CONNECTION_LOG_ACCESSED' AND outcome = ?",
        Long.class,
        organizationId,
        actorRef,
        outcome);
  }

  private UUID persistUser(String subject, SystemRole role) {
    User user = new User(subject + "-" + UUID.randomUUID(), "test-issuer", null, subject);
    user.setOrganizationId(organizationId);
    user.setSystemRole(role);
    return userRepository.save(user).getId();
  }
}
