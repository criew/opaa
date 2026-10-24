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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SIEM export against the real schema (ADR-0055): only entries recorded after the first run,
 * never a pseudonym, no loss while the receiver is down, a gap where the retention removed entries.
 * The service is built here with a recording sender, so the suite's shared context stays as it is.
 */
@OpaaIntegrationTest
class SiemExportServiceIntegrationTest {

  @Autowired private AuditLogService auditLogService;
  @Autowired private AuditRetentionSettingsRepository retention;
  @Autowired private OrganizationRepository organizationRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private UUID organizationId;
  private RecordingSender sender;
  private SiemExportService service;
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
    service =
        new SiemExportService(
            jdbcTemplate,
            new SiemExportProperties(true, "siem.example", null, false, null, Duration.ZERO, 1000),
            sender,
            new CefSyslogFormatter("test-host", "0.0.0-dev"),
            retention,
            new SimpleMeterRegistry());
  }

  @AfterEach
  void tearDown() {
    jdbcTemplate.update("DELETE FROM siem_export_cursor");
    jdbcTemplate.update("UPDATE audit_retention_settings SET last_cutoff = ?", originalCutoff);
    jdbcTemplate.update("DELETE FROM audit_log WHERE organization_id = ?", organizationId);
    organizationRepository.deleteById(organizationId);
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
  void onlyEntriesAfterTheStartGoOutWithoutPseudonymReasonOrPayload() {
    AuditLogEntry before = record(AuditObjectType.KNOWLEDGE_LIBRARY, "lib-0", "Vorher");
    service.cursor();
    AuditLogEntry after = record(AuditObjectType.KNOWLEDGE_LIBRARY, "lib-1", "Dienstanweisungen");

    service.run();

    assertThat(ours()).hasSize(1);
    assertThat(ours().getFirst())
        .contains("externalId=" + after.getEventId())
        .contains("cs3=lib-1")
        .doesNotContain(before.getEventId().toString())
        .doesNotContain("pseudonym-of-the-actor")
        .doesNotContain("Begründung")
        .doesNotContain("geheim");
  }

  @Test
  void anAccountAndAPrivateLibraryGoOutWithoutTheirId() {
    service.cursor();
    record(AuditObjectType.USER_ACCOUNT, "pseudonym-of-the-account", null);
    record(
        AuditObjectType.KNOWLEDGE_LIBRARY, "lib-private", SiemExportService.PRIVATE_OBJECT_LABEL);

    service.run();

    assertThat(ours()).hasSize(2);
    assertThat(String.join("\n", ours()))
        .doesNotContain("pseudonym-of-the-account")
        .doesNotContain("lib-private")
        .doesNotContain(SiemExportService.PRIVATE_OBJECT_LABEL);
  }

  @Test
  void whileTheReceiverIsDownNothingIsLost() {
    service.cursor();
    AuditLogEntry entry = record(AuditObjectType.SPACE, "space-1", "Bauamt");
    sender.failing = true;

    service.run();
    assertThat(ours()).isEmpty();

    sender.failing = false;
    service.run();
    assertThat(ours()).hasSize(1).first().asString().contains("externalId=" + entry.getEventId());

    service.run();
    assertThat(ours()).hasSize(1);
  }

  @Test
  void entriesTheRetentionRemovedBeforeTheExportAreReportedAsAGap() {
    service.cursor();
    Instant cutoff = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    jdbcTemplate.update(
        "UPDATE audit_retention_settings SET last_cutoff = ?", Timestamp.from(cutoff));

    service.run();

    assertThat(sender.delivered)
        .anySatisfy(message -> assertThat(message).contains(CefSyslogFormatter.GAP_EVENT));
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
