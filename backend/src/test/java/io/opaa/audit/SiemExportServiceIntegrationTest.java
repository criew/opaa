package io.opaa.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.opaa.api.types.ActorKind;
import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.organization.Organization;
import io.opaa.organization.OrganizationRepository;
import io.opaa.test.OpaaIntegrationTest;
import java.io.IOException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The SIEM export against the real schema (ADR-0055): only entries recorded after the first run,
 * never a pseudonym and, by default, no name; nothing overtaken while an older transaction is open;
 * no loss while the receiver is down; a gap for a pause and for what the retention removed. The
 * service is built here with a recording sender, so the suite's shared context stays as it is.
 */
@OpaaIntegrationTest
class SiemExportServiceIntegrationTest {

  @Autowired private AuditLogService auditLogService;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private List<PersonalAuditObjects> personalObjects;
  @Autowired private AuditEventRecorder auditEventRecorder;
  @Autowired private io.opaa.auth.UserService userService;
  @Autowired private io.opaa.auth.AuthProperties authProperties;
  @Autowired private io.opaa.knowledge.KnowledgeLibraryRepository libraryRepository;
  @Autowired private io.opaa.space.SpaceRepository spaceRepository;

  private final List<UUID> createdLibraries = new ArrayList<>();
  private final List<UUID> createdSpaces = new ArrayList<>();
  private final List<UUID> writtenEvents = new ArrayList<>();

  /** A month no partition of the test database holds, so no chain run competes for its seal. */
  private static final java.sql.Date SEALED_MONTH = java.sql.Date.valueOf("2000-01-01");

  private UUID organizationId;
  private RecordingSender sender;
  private Timestamp originalCutoff;

  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM siem_export_cursor");
    organizationId =
        organizationRepository.save(new Organization(UUID.randomUUID(), "SIEM Test Org")).getId();
    originalCutoff =
        jdbcTemplate.queryForObject(
            "SELECT last_cutoff FROM audit_retention_settings", Timestamp.class);
    sender = new RecordingSender();
  }

  @AfterEach
  void tearDown() {
    for (UUID eventId : writtenEvents) {
      jdbcTemplate.update("DELETE FROM audit_log WHERE event_id = ?", eventId);
    }
    createdLibraries.forEach(libraryRepository::deleteById);
    createdSpaces.forEach(spaceRepository::deleteById);
    jdbcTemplate.update("DELETE FROM siem_export_cursor");
    jdbcTemplate.update("DELETE FROM audit_chain_seals WHERE month = ?", SEALED_MONTH);
    jdbcTemplate.update("UPDATE audit_retention_settings SET last_cutoff = ?", originalCutoff);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
  }

  private SiemExportService service(boolean includeLabels) {
    return new SiemExportService(
        jdbcTemplate,
        new SiemExportProperties(
            true, "siem.example", null, false, includeLabels, null, Duration.ZERO, 1000),
        sender,
        new CefSyslogFormatter("test-host", "0.0.0-dev"),
        personalObjects,
        new SimpleMeterRegistry());
  }

  private SiemExportService service() {
    return service(false);
  }

  private AuditLogEntry record(AuditObjectType objectType, String objectId, String label) {
    return auditLogService.record(
        AuditLogEntry.withoutSubject(
            organizationId,
            ActorKind.USER,
            "pseudonym-of-the-actor",
            AuditEventType.LIBRARY_CREATED,
            objectType,
            objectId,
            label,
            null,
            "{\"name\":\"geheim\"}",
            AuditOutcome.SUCCESS,
            "Begründung der Person",
            null));
  }

  private List<String> ours() {
    return sender.delivered.stream().filter(m -> m.contains(organizationId.toString())).toList();
  }

  @Test
  void onlyEntriesAfterTheStartGoOutWithoutPseudonymReasonPayloadOrName() {
    SiemExportService service = service();
    AuditLogEntry before = record(AuditObjectType.KNOWLEDGE_LIBRARY, "lib-0", "Vorher");
    service.cursor();
    AuditLogEntry after = record(AuditObjectType.KNOWLEDGE_LIBRARY, "lib-1", "Dienstanweisungen");

    service.run();

    assertThat(ours()).hasSize(1);
    assertThat(ours().getFirst())
        .contains("externalId=" + after.getEventId())
        .contains("cs3=lib-1")
        .contains("cn1Label=schemaVersion cn1=" + AuditEventCatalog.SCHEMA_VERSION)
        .doesNotContain(before.getEventId().toString())
        .doesNotContain("Dienstanweisungen")
        .doesNotContain("pseudonym-of-the-actor")
        .doesNotContain("Begründung")
        .doesNotContain("geheim");
  }

  /** The one payload the export carries: the seal of a month, read from the seal table. */
  @Test
  void theMonthSealGoesOutWithItsHashAndNothingElseOfThePayload() {
    SiemExportService service = service();
    service.cursor();
    AuditLogEntry sealEvent =
        auditLogService.record(
            AuditLogEntry.withoutSubject(
                organizationId,
                ActorKind.SYSTEM_PROCESS,
                AuditChainService.ACTOR,
                AuditEventType.AUDIT_LOG_MONTH_SEALED,
                AuditObjectType.AUDIT_LOG,
                "2000-01",
                null,
                null,
                "{\"month\":\"2000-01\",\"entries\":2,\"headHash\":\"secret\","
                    + "\"algorithm\":\"OPAA-AUDIT-CHAIN-1\"}",
                AuditOutcome.SUCCESS,
                null,
                null));
    String hash = "ab".repeat(32);
    jdbcTemplate.update(
        "INSERT INTO audit_chain_seals (month, entry_count, head_hash, algorithm, sealed_at,"
            + " seal_event_id) VALUES (?, 2, decode(?, 'hex'), ?, clock_timestamp(), ?)",
        SEALED_MONTH,
        hash,
        AuditChainHasher.ALGORITHM,
        sealEvent.getEventId());

    service.run();

    assertThat(ours()).hasSize(1);
    assertThat(ours().getFirst())
        .contains("|AUDIT_LOG_MONTH_SEALED|")
        .contains("cs3=2000-01")
        .contains("cs5=2000-01")
        .contains("cs6=" + hash)
        .contains("cn2Label=sealedEntries cn2=2")
        .doesNotContain("secret");
  }

  @Test
  void theNameGoesOutOnlyWithItsOwnSwitch() {
    SiemExportService service = service(true);
    service.cursor();
    record(AuditObjectType.SPACE, "space-1", "Bauamt");

    service.run();

    assertThat(ours()).hasSize(1).first().asString().contains("cs4=Bauamt");
  }

  @Test
  void anObjectOfOnePersonGoesOutWithoutIdAndName() {
    SiemExportService service = service(true);
    service.cursor();
    record(AuditObjectType.USER_ACCOUNT, "pseudonym-of-the-account", null);
    record(AuditObjectType.API_TOKEN, "token-of-a-person", null);
    record(AuditObjectType.KNOWLEDGE_LIBRARY, "lib-private", AuditNeutralLabels.PRIVATE_ASSET);
    record(AuditObjectType.SPACE, "space-personal", AuditNeutralLabels.PERSONAL_SPACE);
    record(AuditObjectType.PROMPT, "prompt-private", AuditNeutralLabels.PRIVATE_PROMPT);

    service.run();

    assertThat(ours()).hasSize(5);
    assertThat(String.join("\n", ours()))
        .doesNotContain("pseudonym-of-the-account")
        .doesNotContain("token-of-a-person")
        .doesNotContain("lib-private")
        .doesNotContain("space-personal")
        .doesNotContain("prompt-private")
        .doesNotContain("cs3=")
        .doesNotContain("cs4=");
  }

  /**
   * The person of the dev profile, who owns the personal objects of the next test; provisioned here
   * as the dev filter would, so the test does not depend on an earlier request of another class.
   */
  private UUID person() {
    return userService
        .findOrCreateUser(
            "dev-user", authProperties.dev().issuer(), "dev-user@opaa.local", "Dev User")
        .getId();
  }

  private UUID personalSpaceOf(UUID person) {
    List<UUID> existing =
        jdbcTemplate.queryForList(
            "SELECT id FROM spaces WHERE owner_id = ? AND is_default", UUID.class, person);
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    UUID id =
        spaceRepository
            .save(
                new io.opaa.space.Space(
                    "Privater Bereich", null, true, person, Organization.DEFAULT_ID))
            .getId();
    createdSpaces.add(id);
    return id;
  }

  private UUID privateLibraryOf(UUID person) {
    UUID id =
        libraryRepository
            .save(
                io.opaa.knowledge.KnowledgeLibrary.ownerOnly(
                    Organization.DEFAULT_ID,
                    "Meine Ablage " + UUID.randomUUID(),
                    null,
                    person,
                    io.opaa.knowledge.SourceType.UPLOAD,
                    null,
                    null,
                    null))
            .getId();
    createdLibraries.add(id);
    return id;
  }

  private List<UUID> eventsOn(String objectId) {
    List<UUID> ids =
        jdbcTemplate.queryForList(
            "SELECT event_id FROM audit_log WHERE object_id = ?", UUID.class, objectId);
    writtenEvents.addAll(ids);
    return ids;
  }

  @Test
  void theRealWritersPutNoNameOfAPersonalObjectIntoTheLogAndTheExportSendsNoId() {
    // regression guard for ADR-0055, Entscheidung 2: the writers of the review - admin calls with
    // "Bibliothek <id>", the point-in-time access without label, a space context with its own
    // name - are neutralised in the one write path, whatever label they pass
    UUID person = person();
    UUID space = personalSpaceOf(person);
    UUID library = privateLibraryOf(person);
    io.opaa.auth.CurrentUser admin =
        io.opaa.auth.CurrentUser.of(
            person, Organization.DEFAULT_ID, io.opaa.api.types.SystemRole.SYSTEM_ADMIN, "Admin");
    SiemExportService service = service(true);
    service.cursor();

    // built like the admin controllers build it: AuditedAdminCall is no bean of its own
    new io.opaa.audit.web.AuditedAdminCall(auditEventRecorder)
        .run(
            admin,
            AuditEventType.INDEXING_METADATA_BACKFILL_TRIGGERED,
            AuditObjectType.KNOWLEDGE_LIBRARY,
            library,
            "Bibliothek " + library,
            java.util.Map.of(),
            () -> "done",
            result -> java.util.Map.of());
    auditEventRecorder.recordPermissionHistoryAccess(
        Organization.DEFAULT_ID,
        person,
        AuditObjectType.SPACE,
        space,
        java.util.Map.of(),
        AuditOutcome.SUCCESS,
        "Prüfung");
    auditEventRecorder.recordUserAction(
        AuditEvent.builder()
            .organizationId(Organization.DEFAULT_ID)
            .actor(person)
            .type(AuditEventType.SEARCH_DIAGNOSIS_PROFILE_RUN)
            .object(AuditObjectType.SPACE, space, "Mein frei gewählter Name")
            .outcome(AuditOutcome.SUCCESS)
            .build());

    assertThat(
            jdbcTemplate.queryForList(
                "SELECT object_label FROM audit_log WHERE object_id IN (?, ?)",
                String.class,
                library.toString(),
                space.toString()))
        .containsOnly(AuditNeutralLabels.PRIVATE_ASSET, AuditNeutralLabels.PERSONAL_SPACE);
    assertThat(eventsOn(library.toString())).hasSize(1);
    assertThat(eventsOn(space.toString())).hasSize(2);

    service.run();

    assertThat(String.join("\n", sender.delivered))
        .doesNotContain(library.toString())
        .doesNotContain(space.toString())
        .doesNotContain("Mein frei gewählter Name");
  }

  @Test
  void anEntryOfAStillOpenOlderTransactionIsNotOvertaken() throws Exception {
    SiemExportService service = service();
    service.cursor();
    CountDownLatch written = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    UUID[] lateId = new UUID[1];
    CompletableFuture<Void> slow =
        CompletableFuture.runAsync(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status -> {
                          lateId[0] =
                              record(AuditObjectType.SPACE, "space-late", "Spät").getEventId();
                          written.countDown();
                          try {
                            release.await(30, TimeUnit.SECONDS);
                          } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                          }
                        }));
    assertThat(written.await(30, TimeUnit.SECONDS)).isTrue();
    AuditLogEntry younger = record(AuditObjectType.SPACE, "space-young", "Jung");

    service.run();
    assertThat(String.join("\n", ours()))
        .doesNotContain(younger.getEventId().toString())
        .doesNotContain(lateId[0].toString());

    release.countDown();
    slow.get(30, TimeUnit.SECONDS);
    service.run();
    assertThat(ours()).hasSize(2);
    assertThat(ours().getFirst()).contains("externalId=" + lateId[0]);
  }

  @Test
  void whileTheReceiverIsDownNothingIsLost() {
    SiemExportService service = service();
    service.cursor();
    AuditLogEntry entry = record(AuditObjectType.SPACE, "space-1", "Bauamt");
    sender.failing = true;

    service.run();
    assertThat(ours()).isEmpty();

    sender.failing = false;
    SiemExportService afterBackoff = service();
    afterBackoff.run();
    assertThat(ours()).hasSize(1).first().asString().contains("externalId=" + entry.getEventId());

    afterBackoff.run();
    assertThat(ours()).hasSize(1);
  }

  @Test
  void afterAPauseTheExportBeginsAnewAndReportsTheGap() {
    SiemExportService service = service();
    service.cursor();
    SiemExportService.markDisabled(jdbcTemplate);
    AuditLogEntry duringThePause = record(AuditObjectType.SPACE, "space-pause", "Pause");

    service.run();

    assertThat(sender.delivered)
        .anySatisfy(message -> assertThat(message).contains("reason=disabled"));
    assertThat(String.join("\n", ours())).doesNotContain(duringThePause.getEventId().toString());
    assertThat(service.cursor().disabledAt()).isNull();
  }

  @Test
  void entriesTheRetentionRemovedBeforeTheExportAreReportedAsAGap() {
    SiemExportService service = service();
    service.cursor();
    Instant cutoff = Instant.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS);
    jdbcTemplate.update(
        "UPDATE audit_retention_settings SET last_cutoff = ?", Timestamp.from(cutoff));

    service.run();

    assertThat(sender.delivered)
        .anySatisfy(
            message ->
                assertThat(message)
                    .contains(CefSyslogFormatter.GAP_EVENT)
                    .contains("reason=retention"));
    assertThat(service.cursor().recordedAt()).isEqualTo(cutoff);
  }

  /** Records every delivered message; {@code failing} makes a delivery throw. */
  private static final class RecordingSender extends SyslogSender {

    private final List<String> delivered = new ArrayList<>();
    private boolean failing;

    RecordingSender() {
      super("siem.example", 6514, false, Duration.ofSeconds(1));
    }

    @Override
    void send(List<String> messages) throws IOException {
      if (failing) {
        throw new IOException("receiver down");
      }
      delivered.addAll(messages);
    }
  }
}
