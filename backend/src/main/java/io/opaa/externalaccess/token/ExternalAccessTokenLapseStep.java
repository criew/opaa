package io.opaa.externalaccess.token;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.auth.local.LocalAccountMaintenanceStep;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Writes the {@code API_TOKEN_EXPIRED} entry of every token that has stopped working and clears its
 * "zuletzt benutzt" (ADR-0035; docs/features/external-access.md, "Lebenszyklus").
 *
 * <p>Why this exists at all: with a mandatory expiry of at most 90 days, <b>the ordinary end of a
 * token is the expiry, not the revocation</b>. An access that ends without an entry is the same gap
 * in the trail as one that begins without one, and the entry fills the "Ablauf einer Befristung,
 * sobald sie wirkt" point of {@code security-and-compliance.md}.
 *
 * <p>Exactly one entry per token: {@code lapseRecordedAt} is the marker, and it doubles as the
 * start of the Loeschfrist {@link ExternalAccessTokenRetentionStep} measures. A token the person or
 * the administration revoked already has its own {@code API_TOKEN_REVOKED} entry - it gets the
 * marker here without a second event. The entry names the token by id, never by name, and the
 * person only as a pseudonym.
 */
@Component
@Order(40)
public class ExternalAccessTokenLapseStep implements LocalAccountMaintenanceStep {

  /** The audit actor of the channel's own acts, next to {@code local-auth}. */
  public static final String SYSTEM_ACTOR = "external-access";

  private static final Logger log = LoggerFactory.getLogger(ExternalAccessTokenLapseStep.class);

  private final ExternalAccessTokenRepository tokens;
  private final UserRepository users;
  private final AuditEventRecorder audit;

  public ExternalAccessTokenLapseStep(
      ExternalAccessTokenRepository tokens, UserRepository users, AuditEventRecorder audit) {
    this.tokens = tokens;
    this.users = users;
    this.audit = audit;
  }

  @Override
  public String name() {
    return "external-access-token-lapse";
  }

  @Override
  public void run(Instant now) {
    List<ExternalAccessToken> lapsed = tokens.findLapsed(now);
    int recorded = 0;
    for (ExternalAccessToken token : lapsed) {
      boolean alreadyRevoked = token.getRevokedAt() != null;
      if (!alreadyRevoked) {
        token.revoke(ExternalAccessTokenRevocationReason.EXPIRED, now);
      }
      token.markLapseRecorded(now);
      tokens.save(token);
      if (!alreadyRevoked) {
        record(token);
        recorded++;
      }
    }
    if (!lapsed.isEmpty()) {
      log.info(
          "External access tokens: {} token(s) lapsed, {} recorded as expired",
          lapsed.size(),
          recorded);
    }
  }

  private void record(ExternalAccessToken token) {
    User owner = users.findById(token.getUserId()).orElse(null);
    if (owner == null) {
      return;
    }
    audit.recordSystemProcessAction(
        AuditEvent.builder()
            .organizationId(owner.getOrganizationId())
            .actorRef(SYSTEM_ACTOR)
            .type(AuditEventType.API_TOKEN_EXPIRED)
            .object(AuditObjectType.API_TOKEN, token.getId(), null)
            .subject(AuditSubjectKind.USER, owner.getId())
            .after(Map.of("reason", ExternalAccessTokenRevocationReason.EXPIRED.name()))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }
}
