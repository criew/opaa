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
    jdbcTemplate.update("DELETE FROM siem_export_cursor");
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
        .doesNotContain(before.getEventId().toString())
        .doesNotContain("Dienstanweisungen")
        .doesNotContain("pseudonym-of-the-actor")
        .doesNotContain("Begründung")
        .doesNotContain("geheim");
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
