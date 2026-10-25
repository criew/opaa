package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.compliance.SelfDisclosureCategory;
import io.opaa.compliance.SelfDisclosureCategory.Field;
import io.opaa.compliance.SelfDisclosureCategory.Section;
import io.opaa.compliance.SelfDisclosureLabels;
import io.opaa.compliance.SelfDisclosureSink;
import io.opaa.compliance.SelfDisclosureSubject;
import io.opaa.organization.Organization;
import io.opaa.test.OpaaIntegrationTest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * The category {@code audit-log} against Postgres: only entries about the person, other people as
 * "andere Person", system runs as "System", no pseudonym, no reason, no foreign label, no foreign
 * name in {@code before}/{@code after}. Rows are written directly; the class removes its own.
 */
@OpaaIntegrationTest
class AuditLogSelfDisclosureExporterIntegrationTest {

  private static final UUID ORG = Organization.DEFAULT_ID;
  private static final String OTHER_NAME = "Odile Fremdname";
  private static final String OTHER_EMAIL = "odile.fremd@sd2990.example";
  private static final String SELF_NAME = "Selma Selbst";

  @Autowired private JdbcTemplate jdbc;
  @Autowired private AuditLogSelfDisclosureExporter exporter;

  private UUID self;
  private UUID other;
  private String selfPseudonym;
  private String otherPseudonym;
  private final List<UUID> events = new ArrayList<>();

  @BeforeEach
  void setUp() {
    self = user("sd2990-self-" + UUID.randomUUID(), SELF_NAME, "selma@sd2990.example");
    other = user("sd2990-other-" + UUID.randomUUID(), OTHER_NAME, OTHER_EMAIL);
    selfPseudonym = pseudonym(self);
    otherPseudonym = pseudonym(other);
  }

  @AfterEach
  void cleanUp() {
    for (UUID event : events) {
      jdbc.update("DELETE FROM audit_log WHERE event_id = ?", event);
    }
    events.clear();
    jdbc.update("DELETE FROM users WHERE id IN (?, ?)", self, other);
  }

  @Test
  void othersAppearAsAnotherPersonOrTheSystemAndNothingNamesThem() throws Exception {
    Instant now = Instant.now();
    entry(
        now,
        "USER",
        selfPseudonym,
        "SPACE_MEMBER_ADDED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Alpha",
        "USER",
        otherPseudonym,
        null,
        "{\"role\":\"MEMBER\",\"memberName\":\""
            + OTHER_NAME
            + "\",\"memberId\":\""
            + other
            + "\"}",
        "Weil " + OTHER_EMAIL + " es wollte");
    entry(
        now.plusMillis(1),
        "USER",
        otherPseudonym,
        "LOCAL_USER_LOCKED",
        "USER_ACCOUNT",
        selfPseudonym,
        SELF_NAME,
        "USER",
        selfPseudonym,
        null,
        "{\"reason\":\"ADMIN\"}",
        "Begründung von " + OTHER_NAME);
    entry(
        now.plusMillis(2),
        "SYSTEM_PROCESS",
        "local-auth",
        "LOCAL_SESSION_REVOKED",
        "USER_ACCOUNT",
        selfPseudonym,
        null,
        "USER",
        selfPseudonym,
        null,
        "{\"reason\":\"ACCOUNT_LOCKED\"}",
        null);
    entry(
        now.plusMillis(3),
        "USER",
        selfPseudonym,
        "LOCAL_USER_CHANGED",
        "USER_ACCOUNT",
        otherPseudonym,
        OTHER_NAME + " <" + OTHER_EMAIL + ">",
        "USER",
        otherPseudonym,
        "{\"expiresAt\":\"2026-12-01T00:00:00Z\"}",
        "{\"changedFields\":[\"expiresAt\"]}",
        null);
    entry(
        now.plusMillis(4),
        "USER",
        otherPseudonym,
        "ASSET_OWNER_CHANGED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Beta",
        "USER",
        selfPseudonym,
        "{\"ownerId\":\"" + self + "\"}",
        "{\"ownerId\":\"" + other + "\"}",
        null);

    List<Map<String, Object>> records = export(self);

    assertThat(records).hasSize(5);
    assertThat(records.get(0))
        .containsEntry("eventType", "SPACE_MEMBER_ADDED")
        .containsEntry("actor", SelfDisclosureLabels.YOU)
        .containsEntry("subject", SelfDisclosureLabels.OTHER_PERSON)
        .containsEntry("objectLabel", "Raum Alpha")
        .containsEntry("after", "{\"role\":\"MEMBER\"}")
        .containsEntry("reasonGiven", true);
    assertThat(records.get(1))
        .containsEntry("actor", SelfDisclosureLabels.OTHER_PERSON)
        .containsEntry("objectAccount", SelfDisclosureLabels.YOU)
        .containsEntry("objectLabel", SELF_NAME)
        .containsEntry("subject", SelfDisclosureLabels.YOU)
        .containsEntry("after", "{\"reason\":\"ADMIN\"}");
    assertThat(records.get(2))
        .containsEntry("actor", SelfDisclosureLabels.SYSTEM)
        .containsEntry("reasonGiven", false);
    assertThat(records.get(3))
        .containsEntry("objectAccount", SelfDisclosureLabels.OTHER_PERSON)
        .containsEntry("objectLabel", null)
        .containsEntry("subject", SelfDisclosureLabels.OTHER_PERSON);
    assertThat(records.get(4))
        .containsEntry("before", "{\"ownerId\":\"Sie\"}")
        .containsEntry("after", "{\"ownerId\":\"andere Person oder Gruppe\"}");
    assertThat(records.toString())
        .doesNotContain(OTHER_NAME, OTHER_EMAIL, "odile", other.toString(), otherPseudonym)
        .doesNotContain(selfPseudonym, "Weil ", "Begründung");
  }

  @Test
  void entriesThatNameThePersonNeitherAsActorNorAsAffectedAreMissing() throws Exception {
    Instant now = Instant.now();
    entry(
        now,
        "USER",
        otherPseudonym,
        "SPACE_CREATED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Fremd",
        null,
        null,
        null,
        "{\"name\":\"Raum Fremd\"}",
        null);
    entry(
        now.plusMillis(1),
        "USER",
        otherPseudonym,
        "LOCAL_USER_LOCKED",
        "USER_ACCOUNT",
        otherPseudonym,
        OTHER_NAME,
        "USER",
        otherPseudonym,
        null,
        null,
        "intern");
    // no writer records a revision read with a subject today; the exclusion guards a future one
    // that would name the person other than as reader (anlassbezogene Klärung)
    entry(
        now.plusMillis(2),
        "USER",
        otherPseudonym,
        "AUDIT_LOG_ACCESSED",
        "AUDIT_LOG",
        "audit_log",
        null,
        "USER",
        selfPseudonym,
        null,
        "{\"accessPath\":\"by-incident-scope\"}",
        "Klärung über Selma");
    entry(
        now.plusMillis(3),
        "USER",
        selfPseudonym,
        "AUDIT_LOG_ACCESSED",
        "AUDIT_LOG",
        "audit_log",
        null,
        null,
        null,
        null,
        "{\"accessPath\":\"by-time-range\"}",
        "Prüfung");

    List<Map<String, Object>> records = export(self);

    assertThat(records)
        .singleElement()
        .satisfies(
            record ->
                assertThat(record)
                    .containsEntry("eventType", "AUDIT_LOG_ACCESSED")
                    .containsEntry("actor", SelfDisclosureLabels.YOU)
                    .containsEntry("after", "{\"accessPath\":\"by-time-range\"}"));
    assertThat(records.toString()).doesNotContain("Raum Fremd", "Klärung über Selma");
  }

  /**
   * An owner entry by someone else names the person only under {@code ownerId}: by pseudonym, in
   * entries before the pseudonymisation of owners by account id. Only event types with an owner are
   * searched there.
   */
  @Test
  void ownerEntriesFindThePersonUnderOwnerIdOnlyForEventTypesWithAnOwner() throws Exception {
    Instant now = Instant.now();
    entry(
        now,
        "USER",
        otherPseudonym,
        "ASSET_OWNER_CHANGED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Gamma",
        null,
        null,
        "{\"ownerId\":\"" + selfPseudonym + "\"}",
        "{\"ownerId\":\"" + otherPseudonym + "\"}",
        null);
    entry(
        now.plusMillis(1),
        "USER",
        otherPseudonym,
        "SPACE_DELETED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Delta",
        null,
        null,
        "{\"name\":\"Raum Delta\",\"ownerId\":\"" + self + "\"}",
        null,
        null);
    entry(
        now.plusMillis(2),
        "USER",
        otherPseudonym,
        "LIBRARY_CHANGED",
        "KNOWLEDGE_LIBRARY",
        UUID.randomUUID().toString(),
        "Bibliothek Fremd",
        null,
        null,
        null,
        "{\"changedFields\":[\"name\"],\"ownerId\":\"" + selfPseudonym + "\"}",
        null);
    entry(
        now.plusMillis(3),
        "USER",
        otherPseudonym,
        "SPACE_CREATED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Fremd",
        null,
        null,
        null,
        "{\"name\":\"Raum Fremd\",\"ownerId\":\"" + otherPseudonym + "\"}",
        null);

    List<Map<String, Object>> records = export(self);

    assertThat(records)
        .extracting(r -> r.get("eventType"))
        .containsExactly("ASSET_OWNER_CHANGED", "SPACE_DELETED");
    assertThat(records.get(0))
        .containsEntry("actor", SelfDisclosureLabels.OTHER_PERSON)
        .containsEntry("before", "{\"ownerId\":\"Sie\"}")
        .containsEntry(
            "after", "{\"ownerId\":\"" + AuditSelfDisclosurePayload.OTHER_PERSON_OR_GROUP + "\"}");
    assertThat(records.get(1))
        .containsEntry("before", "{\"name\":\"Raum Delta\",\"ownerId\":\"Sie\"}");
    assertThat(records.toString())
        .doesNotContain("Bibliothek Fremd", "Raum Fremd", self.toString(), selfPseudonym);
  }

  /**
   * The target of a transfer of rights is named only under {@code targetId}, by pseudonym or, in
   * older entries, by account id; only the two transfer event types are searched there.
   */
  @Test
  void transferEntriesFindTheTargetUnderTargetIdOnlyForTransferEventTypes() throws Exception {
    Instant now = Instant.now();
    entry(
        now,
        "USER",
        otherPseudonym,
        "PERMISSION_TRANSFER_EXECUTED",
        "USER_ACCOUNT",
        otherPseudonym,
        OTHER_NAME,
        "USER",
        otherPseudonym,
        null,
        "{\"targetType\":\"USER\",\"targetId\":\"" + selfPseudonym + "\",\"rows\":2}",
        null);
    entry(
        now.plusMillis(1),
        "USER",
        otherPseudonym,
        "PERMISSION_TRANSFER_PREVIEWED",
        "USER_ACCOUNT",
        otherPseudonym,
        OTHER_NAME,
        "USER",
        otherPseudonym,
        null,
        "{\"targetType\":\"USER\",\"targetId\":\"" + self + "\",\"rows\":2}",
        null);
    entry(
        now.plusMillis(2),
        "USER",
        otherPseudonym,
        "GROUP_CHANGED",
        "GROUP",
        UUID.randomUUID().toString(),
        "Gruppe Fremd",
        null,
        null,
        null,
        "{\"targetId\":\"" + selfPseudonym + "\"}",
        null);

    List<Map<String, Object>> records = export(self);

    assertThat(records)
        .extracting(r -> r.get("eventType"))
        .containsExactly("PERMISSION_TRANSFER_EXECUTED", "PERMISSION_TRANSFER_PREVIEWED");
    assertThat(records)
        .allSatisfy(
            r ->
                assertThat(r)
                    .containsEntry("subject", SelfDisclosureLabels.OTHER_PERSON)
                    .containsEntry(
                        "after", "{\"targetType\":\"USER\",\"targetId\":\"Sie\",\"rows\":2}"));
    assertThat(records.toString()).doesNotContain("Gruppe Fremd", self.toString(), selfPseudonym);
  }

  @Test
  void readsEveryMonthAndBeyondOnePage() throws Exception {
    Instant now = Instant.now();
    entry(
        now.minus(Duration.ofDays(45)),
        "USER",
        selfPseudonym,
        "SPACE_CREATED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Alt",
        null,
        null,
        null,
        null,
        null);
    List<Object[]> batch = new ArrayList<>();
    for (int i = 0; i < AuditLogSelfDisclosureExporter.PAGE_SIZE + 20; i++) {
      UUID event = UUID.randomUUID();
      events.add(event);
      batch.add(
          new Object[] {
            event,
            Timestamp.from(now.minusMillis(5000 - i)),
            ORG,
            "USER",
            selfPseudonym,
            "SPACE_CHANGED",
            "SPACE",
            UUID.randomUUID().toString(),
            "Raum " + i,
            null,
            null,
            null,
            null,
            "SUCCESS",
            null,
            null
          });
    }
    jdbc.batchUpdate(INSERT, batch);

    List<Map<String, Object>> records = export(self);

    assertThat(records).hasSize(AuditLogSelfDisclosureExporter.PAGE_SIZE + 21);
    assertThat(records.getFirst()).containsEntry("objectLabel", "Raum Alt");
    assertThat(records.stream().map(r -> (Instant) r.get("recordedAt")).toList()).isSorted();
  }

  @Test
  void aPersonWithoutPseudonymHasNoEntriesAndGetsNoneMinted() throws Exception {
    jdbc.update("DELETE FROM audit_actor_pseudonyms WHERE user_id = ?", self);

    assertThat(export(self)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_actor_pseudonyms WHERE user_id = ?", Long.class, self))
        .isZero();
  }

  /**
   * Without a pseudonym the person still finds the older entries that name her as owner or target
   * by account id; an entry under her former pseudonym no longer leads to her, and none is minted.
   */
  @Test
  void aPersonWithoutPseudonymStillFindsOlderEntriesNamingHerAccountId() throws Exception {
    jdbc.update("DELETE FROM audit_actor_pseudonyms WHERE user_id = ?", self);
    Instant now = Instant.now();
    entry(
        now,
        "USER",
        otherPseudonym,
        "ASSET_OWNER_CHANGED",
        "SPACE",
        UUID.randomUUID().toString(),
        "Raum Alt",
        null,
        null,
        "{\"ownerId\":\"" + self + "\"}",
        "{\"ownerId\":\"" + other + "\"}",
        null);
    entry(
        now.plusMillis(1),
        "USER",
        otherPseudonym,
        "PERMISSION_TRANSFER_EXECUTED",
        "USER_ACCOUNT",
        otherPseudonym,
        OTHER_NAME,
        "USER",
        otherPseudonym,
        null,
        "{\"targetType\":\"USER\",\"targetId\":\"" + self + "\"}",
        null);
    entry(
        now.plusMillis(2),
        "USER",
        selfPseudonym,
        "LIBRARY_CHANGED",
        "KNOWLEDGE_LIBRARY",
        UUID.randomUUID().toString(),
        "Bibliothek Alt",
        null,
        null,
        null,
        "{\"changedFields\":[\"name\"]}",
        null);

    List<Map<String, Object>> records = export(self);

    assertThat(records)
        .extracting(r -> r.get("eventType"))
        .containsExactly("ASSET_OWNER_CHANGED", "PERMISSION_TRANSFER_EXECUTED");
    assertThat(records.get(0)).containsEntry("before", "{\"ownerId\":\"Sie\"}");
    assertThat(records.get(1))
        .containsEntry("after", "{\"targetType\":\"USER\",\"targetId\":\"Sie\"}");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit_actor_pseudonyms WHERE user_id = ?", Long.class, self))
        .isZero();
  }

  private static final String INSERT =
      "INSERT INTO audit_log (event_id, recorded_at, organization_id, actor_kind, actor_ref,"
          + " event_type, object_type, object_id, object_label, subject_kind, subject_ref, before,"
          + " after, outcome, reason, correlation_ref)"
          + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

  private void entry(
      Instant at,
      String actorKind,
      String actorRef,
      String eventType,
      String objectType,
      String objectId,
      String objectLabel,
      String subjectKind,
      String subjectRef,
      String before,
      String after,
      String reason) {
    UUID event = UUID.randomUUID();
    events.add(event);
    jdbc.update(
        INSERT,
        event,
        Timestamp.from(at),
        ORG,
        actorKind,
        actorRef,
        eventType,
        objectType,
        objectId,
        objectLabel,
        subjectKind,
        subjectRef,
        before,
        after,
        "SUCCESS",
        reason,
        null);
  }

  private UUID user(String subject, String name, String email) {
    return jdbc.queryForObject(
        "INSERT INTO users (subject, issuer, email, display_name, organization_id)"
            + " VALUES (?, 'sd2990-test', ?, ?, ?) RETURNING id",
        UUID.class,
        subject,
        email,
        name,
        ORG);
  }

  private String pseudonym(UUID user) {
    UUID pseudonym = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO audit_actor_pseudonyms (pseudonym_id, user_id, organization_id)"
            + " VALUES (?, ?, ?)",
        pseudonym,
        user,
        ORG);
    return pseudonym.toString();
  }

  private List<Map<String, Object>> export(UUID account) throws Exception {
    RecordingSink sink = new RecordingSink(exporter.category());
    exporter.export(new SelfDisclosureSubject(account, ORG), sink);
    return sink.records;
  }

  /** Keeps every record and holds it against the positive list, as the archive does. */
  private static final class RecordingSink implements SelfDisclosureSink {

    private final SelfDisclosureCategory category;
    private final List<Map<String, Object>> records = new ArrayList<>();

    RecordingSink(SelfDisclosureCategory category) {
      this.category = category;
    }

    @Override
    public RecordWriter section(String key) {
      Section section = category.section(key);
      assertThat(section).as("section %s", key).isNotNull();
      return record -> {
        assertThat(section.fields().stream().map(Field::key).toList()).containsAll(record.keySet());
        for (Field field : section.fields()) {
          Object value = record.get(field.key());
          if (field.derived() && value != null && !(value instanceof Boolean)) {
            assertThat(SelfDisclosureLabels.ALL).contains((String) value);
          }
        }
        records.add(new LinkedHashMap<>(record));
      };
    }
  }
}
