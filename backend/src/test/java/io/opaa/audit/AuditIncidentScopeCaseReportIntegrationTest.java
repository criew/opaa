package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.api.types.AuditIncidentScopePurpose;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.compliance.InstanceDisclosure.CaseCount;
import io.opaa.compliance.InstanceDisclosure.IncidentScopeCases;
import io.opaa.compliance.InstanceDisclosure.PurposeCases;
import io.opaa.compliance.InstanceDisclosure.ReportingPeriod;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The counts of the anlassbezogenen Klärungen of a reporting period against real rows, with fixed
 * clocks: requests, approvals and ended approvals per purpose and calendar month, only those of the
 * period, still there once the grants are deleted, and without any person.
 *
 * <p>Like {@link AuditIncidentScopeRetentionServiceIntegrationTest}, the rows live around 2001, out
 * of reach of the grants other classes create at the real present.
 */
@OpaaIntegrationTest
class AuditIncidentScopeCaseReportIntegrationTest {

  private static final String REASON = "Hinweis auf Abfluss von Personalakten 4711";
  private static final Pattern UUID_PATTERN =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

  @Autowired private AuditIncidentScopeGrantRepository grantRepository;
  @Autowired private AuditPersonDirectory people;
  @Autowired private UserRepository userRepository;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private AuditIncidentScopeRetentionSettings retentionSettings;

  private UUID organizationId;
  private UUID requester;
  private UUID approver;
  private UUID subject;
  private final List<UUID> createdUserIds = new ArrayList<>();
  private final List<UUID> grantIds = new ArrayList<>();

  @BeforeEach
  void setUp() {
    organizationId =
        organizationRepository
            .save(new Organization(UUID.randomUUID(), "Incident Scope Report Org"))
            .getId();
    requester = createUser();
    approver = createUser();
    subject = createUser();
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update(
        "DELETE FROM audit_incident_scope_grants WHERE organization_id = ?", organizationId);
    jdbcTemplate.update(
        "DELETE FROM audit_incident_scope_monthly_counts WHERE organization_id = ?",
        organizationId);
    jdbcTemplate.update(
        "DELETE FROM audit_chat_cleanup_hold_monthly_counts WHERE organization_id = ?",
        organizationId);
    for (UUID userId : createdUserIds) {
      userRepository.deleteById(userId);
    }
    organizationRepository.deleteById(organizationId);
  }

  @Test
  void onlyTheCasesOfThePeriodAreCounted() {
    requestAt("2001-02-20T10:00:00Z", AuditIncidentScopePurpose.SECURITY_INCIDENT, 3);
    List<UUID> inside =
        requestAt("2001-03-05T10:00:00Z", AuditIncidentScopePurpose.SECURITY_INCIDENT, 3);
    // approved in March, usable until 9 April: approval and end both inside the period
    approveAt("2001-03-10T10:00:00Z", inside);
    List<UUID> late =
        requestAt(
            "2001-04-28T10:00:00Z", AuditIncidentScopePurpose.UNAUTHORIZED_ACCESS_SUSPICION, 4);
    // approved in May, usable until June: both outside the period
    approveAt("2001-05-02T10:00:00Z", late);

    IncidentScopeCases cases = reportAt("2001-12-01T00:00:00Z", period("2001-03-01", "2001-04-30"));

    assertThat(cases.period()).isEqualTo(period("2001-03-01", "2001-04-30"));
    assertThat(cases.purposes())
        .extracting(PurposeCases::purpose)
        .containsExactly(
            "SECURITY_INCIDENT",
            "UNAUTHORIZED_ACCESS_SUSPICION",
            "DATA_BREACH_INVESTIGATION",
            "EXTERNAL_AUDIT_OR_INSPECTION");
    assertThat(counts(cases, "SECURITY_INCIDENT")).containsExactly(3L, 3L, 3L);
    assertThat(counts(cases, "UNAUTHORIZED_ACCESS_SUSPICION")).containsExactly(4L, 0L, 0L);
    assertThat(counts(cases, "DATA_BREACH_INVESTIGATION")).containsExactly(0L, 0L, 0L);
    assertThat(counts(cases, "EXTERNAL_AUDIT_OR_INSPECTION")).containsExactly(0L, 0L, 0L);
    assertThat(cases.purposes()).allSatisfy(purpose -> assertThat(purpose.label()).isNotBlank());
  }

  @Test
  void deletedGrantsStillCount() {
    approveAt(
        "2001-03-10T10:00:00Z",
        requestAt("2001-03-05T10:00:00Z", AuditIncidentScopePurpose.SECURITY_INCIDENT, 3));

    new AuditIncidentScopeRetentionService(
            grantRepository,
            retentionSettings,
            Clock.fixed(Instant.parse("2003-01-01T00:00:00Z"), ZoneOffset.UTC))
        .runOnce();

    assertThat(grantIds).allSatisfy(id -> assertThat(grantRepository.existsById(id)).isFalse());
    IncidentScopeCases cases = reportAt("2003-01-02T00:00:00Z", period("2001-01-01", "2001-12-31"));
    assertThat(counts(cases, "SECURITY_INCIDENT")).containsExactly(3L, 3L, 3L);
  }

  @Test
  void endsOfUsabilityCountInTheCompletedMonthTheyFellIn() {
    // usable until 19 April and 1 May
    approveAt(
        "2001-03-20T10:00:00Z",
        requestAt("2001-03-20T09:00:00Z", AuditIncidentScopePurpose.DATA_BREACH_INVESTIGATION, 3));
    approveAt(
        "2001-04-01T10:00:00Z",
        requestAt("2001-04-01T09:00:00Z", AuditIncidentScopePurpose.DATA_BREACH_INVESTIGATION, 3));

    assertThat(
            counts(
                reportAt("2001-04-25T00:00:00Z", period("2001-01-01", "2001-03-31")),
                "DATA_BREACH_INVESTIGATION"))
        .containsExactly(3L, 3L, 0L);
    assertThat(
            counts(
                reportAt("2001-05-02T00:00:00Z", period("2001-01-01", "2001-04-30")),
                "DATA_BREACH_INVESTIGATION"))
        .containsExactly(6L, 6L, 3L);
    assertThat(
            counts(
                reportAt("2001-07-01T00:00:00Z", period("2001-01-01", "2001-06-30")),
                "DATA_BREACH_INVESTIGATION"))
        .containsExactly(6L, 6L, 6L);
  }

  /**
   * Regression guard: a period reaching into the running month would show a request within minutes,
   * so the report refuses it instead of reading the running month.
   */
  @Test
  void theRunningMonthIsNeverReported() {
    requestAt("2001-04-05T10:00:00Z", AuditIncidentScopePurpose.UNAUTHORIZED_ACCESS_SUSPICION, 1);

    assertThatThrownBy(() -> reportAt("2001-04-10T00:00:00Z", period("2001-04-01", "2001-04-30")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            counts(
                reportAt("2001-04-10T00:00:00Z", period("2001-01-01", "2001-03-31")),
                "UNAUTHORIZED_ACCESS_SUSPICION"))
        .containsExactly(0L, 0L, 0L);
  }

  @Test
  void aCountBelowTheThresholdIsNotGivenExactly() {
    List<UUID> two =
        requestAt(
            "2001-03-05T10:00:00Z", AuditIncidentScopePurpose.EXTERNAL_AUDIT_OR_INSPECTION, 4);
    approveAt("2001-03-06T10:00:00Z", two.subList(0, 1));

    PurposeCases cases =
        purpose(
            reportAt("2002-01-02T00:00:00Z", period("2001-01-01", "2001-12-31")),
            "EXTERNAL_AUDIT_OR_INSPECTION");

    assertThat(cases.requested()).isEqualTo(CaseCount.of(4));
    assertThat(cases.approved().belowThreshold()).isTrue();
    assertThat(cases.approved().count()).isNull();
    assertThat(cases.expired().belowThreshold()).isTrue();
  }

  @Test
  void heldChatDeletionsCountPerMonthOfThePeriodOnly() {
    holdAt("2000-12-20T10:00:00Z", 5);
    holdAt("2001-03-05T10:00:00Z", 2);
    holdAt("2001-11-30T23:00:00Z", 2);
    holdAt("2002-01-01T10:00:00Z", 4);

    assertThat(
            reportAt("2002-02-02T00:00:00Z", period("2001-01-01", "2001-12-31"))
                .heldChatDeletions())
        .isEqualTo(CaseCount.of(4));
    assertThat(
            reportAt("2002-02-02T00:00:00Z", period("2001-03-01", "2001-03-31"))
                .heldChatDeletions()
                .belowThreshold())
        .isTrue();
    assertThat(
            reportAt("2002-02-02T00:00:00Z", period("2001-04-01", "2001-10-31"))
                .heldChatDeletions())
        .isEqualTo(CaseCount.of(0));
  }

  @Test
  void theCountsNameNoPersonNoReasonAndNoGrant() {
    approveAt(
        "2001-03-10T10:00:00Z",
        requestAt("2001-03-05T10:00:00Z", AuditIncidentScopePurpose.SECURITY_INCIDENT, 3));

    IncidentScopeCases cases = reportAt("2002-01-02T00:00:00Z", period("2001-01-01", "2001-12-31"));
    JsonNode json = JsonMapper.builder().build().valueToTree(cases);
    String text = json.toString();

    assertThat(UUID_PATTERN.matcher(text).find()).as("no id of any kind").isFalse();
    assertThat(text).doesNotContain(REASON);
    for (UUID person : List.of(requester, approver, subject)) {
      assertThat(text).doesNotContain(person.toString());
    }
    assertThat(fieldNames(json))
        .containsExactlyInAnyOrder(
            "period",
            "from",
            "to",
            "purposes",
            "purpose",
            "label",
            "requested",
            "approved",
            "expired",
            "heldChatDeletions",
            "count",
            "notes");
  }

  private List<UUID> requestAt(String instant, AuditIncidentScopePurpose purpose, int times) {
    AuditIncidentScopeService service = serviceAt(instant);
    Instant scopeStart = Instant.parse(instant).minus(7, ChronoUnit.DAYS);
    Instant scopeEnd = Instant.parse(instant);
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < times; i++) {
      ids.add(
          inTransaction(
              () ->
                  service
                      .request(
                          organizationId, requester, subject, scopeStart, scopeEnd, purpose, REASON)
                      .getId()));
    }
    grantIds.addAll(ids);
    return ids;
  }

  private void holdAt(String instant, int times) {
    ChatCleanupHoldCounts counts =
        new ChatCleanupHoldCounts(
            jdbcTemplate, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    for (int i = 0; i < times; i++) {
      counts.countHold(organizationId);
    }
  }

  private void approveAt(String instant, List<UUID> ids) {
    AuditIncidentScopeService service = serviceAt(instant);
    for (UUID id : ids) {
      inTransaction(() -> service.approve(organizationId, id, approver));
    }
  }

  private IncidentScopeCases reportAt(String instant, ReportingPeriod period) {
    Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    return new AuditIncidentScopeCaseReport(
            grantRepository, new ChatCleanupHoldCounts(jdbcTemplate, clock), clock)
        .report(organizationId, period);
  }

  private AuditIncidentScopeService serviceAt(String instant) {
    return new AuditIncidentScopeService(
        grantRepository, people, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
  }

  private <T> T inTransaction(Supplier<T> work) {
    return new TransactionTemplate(transactionManager).execute(status -> work.get());
  }

  private static ReportingPeriod period(String from, String to) {
    return new ReportingPeriod(LocalDate.parse(from), LocalDate.parse(to));
  }

  /** Requested, approved and ended, exactly; fails on a count below the threshold. */
  private static List<Long> counts(IncidentScopeCases cases, String purpose) {
    PurposeCases counts = purpose(cases, purpose);
    return List.of(exact(counts.requested()), exact(counts.approved()), exact(counts.expired()));
  }

  private static long exact(CaseCount count) {
    assertThat(count.belowThreshold()).as("count given exactly").isFalse();
    return count.count();
  }

  private static PurposeCases purpose(IncidentScopeCases cases, String purpose) {
    return cases.purposes().stream()
        .filter(candidate -> candidate.purpose().equals(purpose))
        .findFirst()
        .orElseThrow();
  }

  private static Set<String> fieldNames(JsonNode node) {
    Set<String> names = new HashSet<>();
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> field : node.properties()) {
        names.add(field.getKey());
        names.addAll(fieldNames(field.getValue()));
      }
    } else if (node.isArray()) {
      for (JsonNode element : node) {
        names.addAll(fieldNames(element));
      }
    }
    return names;
  }

  private UUID createUser() {
    User user =
        new User(UUID.randomUUID().toString(), "test-issuer", "user@example.com", "Test User");
    user.setOrganizationId(organizationId);
    UUID id = userRepository.save(user).getId();
    createdUserIds.add(id);
    return id;
  }
}
