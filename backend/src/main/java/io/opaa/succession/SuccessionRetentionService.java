package io.opaa.succession;

import io.opaa.audit.AuditRetentionSettings;
import io.opaa.audit.AuditRetentionSettingsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The retention deletion of the succession records (ADR-0036, Entscheidung 8): they are
 * <b>protocol</b>, not rights history - they say nothing about who could read what - and therefore
 * follow the protocol's own window, the one {@code audit_retention_settings} carries.
 *
 * <p>Only a <b>closed</b> record is ever deleted, whatever its age: an open one still describes a
 * state that holds, and the list derives it anyway. The Sichtungsvermerke of a deleted record go
 * with it through the foreign key's cascade - with them the free text and the person who wrote it.
 */
@Service
public class SuccessionRetentionService {

  private static final Logger log = LoggerFactory.getLogger(SuccessionRetentionService.class);

  private final SuccessionCaseRepository cases;
  private final AuditRetentionSettingsRepository retentionSettings;
  private final Clock clock;

  SuccessionRetentionService(
      SuccessionCaseRepository cases,
      AuditRetentionSettingsRepository retentionSettings,
      Clock clock) {
    this.cases = cases;
    this.retentionSettings = retentionSettings;
    this.clock = clock;
  }

  /** One deletion pass; idempotent, and a no-op while nothing has expired. */
  @Transactional
  public int runOnce() {
    int months =
        retentionSettings.findSingleton().map(AuditRetentionSettings::getRetentionMonths).orElse(0);
    if (months <= 0) {
      return 0;
    }
    Instant cutoff = clock.instant().atZone(ZoneOffset.UTC).minusMonths(months).toInstant();
    int deleted = cases.deleteClosedBefore(cutoff);
    if (deleted > 0) {
      log.info("Succession retention: {} closed case(s) deleted, ended before {}", deleted, cutoff);
    }
    return deleted;
  }
}
