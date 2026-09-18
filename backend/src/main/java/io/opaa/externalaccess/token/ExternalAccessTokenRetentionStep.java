package io.opaa.externalaccess.token;

import io.opaa.audit.AuditRetentionSettingsService;
import io.opaa.auth.local.LocalAccountMaintenanceStep;
import java.time.Instant;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes dead token rows once the installation's audit retention has run out over them
 * (docs/features/external-access.md, "Was von einem Token uebrig bleibt").
 *
 * <p>The frist is <b>coupled to the audit retention, not chosen</b>, and the coupling is
 * two-directional on purpose. Not earlier: a row that disappears before the entry that proves it
 * existed leaves that entry pointing at nothing. Not later: a token table kept beyond its trail is
 * itself the usage archive this channel rules out everywhere else. A blanket "delete expired rows
 * the day after" is expressly not what this is.
 *
 * <p>Measured from {@code lapseRecordedAt}, the moment the token stopped working and its last entry
 * was written - the same start the audit entry's own retention counts from.
 */
@Component
@Order(42)
public class ExternalAccessTokenRetentionStep implements LocalAccountMaintenanceStep {

  private static final Logger log = LoggerFactory.getLogger(ExternalAccessTokenRetentionStep.class);

  private final ExternalAccessTokenRepository tokens;
  private final AuditRetentionSettingsService retention;

  public ExternalAccessTokenRetentionStep(
      ExternalAccessTokenRepository tokens, AuditRetentionSettingsService retention) {
    this.tokens = tokens;
    this.retention = retention;
  }

  @Override
  public String name() {
    return "external-access-token-retention";
  }

  @Override
  @Transactional
  public void run(Instant now) {
    int months = retention.currentRetentionMonths();
    Instant cutoff = now.atOffset(ZoneOffset.UTC).minusMonths(months).toInstant();
    int removed = tokens.deleteLapsedBefore(cutoff);
    if (removed > 0) {
      log.info(
          "External access tokens: removed {} row(s) that stopped working before {} ({} months"
              + " retention)",
          removed,
          cutoff,
          months);
    }
  }
}
