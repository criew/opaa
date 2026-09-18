package io.opaa.externalaccess.token;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.AuditSubjectKind;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place a token stops working without anyone revoking it, and the only writer of {@code
 * API_TOKEN_EXPIRED} (ADR-0035; docs/features/external-access.md, "Lebenszyklus").
 *
 * <p><b>Marker and entry go together or not at all.</b> {@code lapseRecordedAt} is what keeps the
 * entry to exactly one per token - and it is therefore also what would hide a lost entry forever: a
 * row that carries the marker is never looked at again. Both writes are consequently one
 * transaction per token, opened with {@link Propagation#REQUIRES_NEW} so a single failing token
 * rolls back only itself and the daily run continues with the next one.
 */
@Service
public class ExternalAccessTokenLapseService {

  /** The audit actor of the channel's own acts, next to {@code local-auth}. */
  public static final String SYSTEM_ACTOR = "external-access";

  private final ExternalAccessTokenRepository tokens;
  private final UserRepository users;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public ExternalAccessTokenLapseService(
      ExternalAccessTokenRepository tokens,
      UserRepository users,
      AuditEventRecorder audit,
      Clock clock) {
    this.tokens = tokens;
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Records the Ausserkrafttreten of one token that has run out on its own. Already-revoked tokens
   * carry their own {@code API_TOKEN_REVOKED} entry and only get the marker here.
   *
   * @return {@code true} when an {@code API_TOKEN_EXPIRED} entry was written
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean recordLapse(UUID tokenId, Instant now) {
    ExternalAccessToken token = tokens.findById(tokenId).orElse(null);
    if (token == null || token.getLapseRecordedAt() != null) {
      return false;
    }
    boolean alreadyRevoked = token.getRevokedAt() != null;
    if (!alreadyRevoked) {
      token.revoke(ExternalAccessTokenRevocationReason.EXPIRED, now);
      record(token, ExternalAccessTokenRevocationReason.EXPIRED, null);
    }
    token.markLapseRecorded(now);
    tokens.save(token);
    return !alreadyRevoked;
  }

  /**
   * Ends every still-active token of a person whose access itself ended - a locked account, a
   * redeemed handover. Joins the caller's transaction on purpose: the lock and the end of the
   * tokens commit together (see {@code LocalAccountAccessEndedEvent}).
   *
   * @return how many tokens stopped working
   */
  @Transactional(propagation = Propagation.REQUIRED)
  public int endTokensOfAccount(User owner, UUID actorUserId) {
    Instant now = clock.instant();
    int ended = 0;
    for (ExternalAccessToken token : tokens.findByUserIdAndRevokedAtIsNull(owner.getId())) {
      token.revoke(ExternalAccessTokenRevocationReason.ACCOUNT_LIFECYCLE, now);
      token.markLapseRecorded(now);
      tokens.save(token);
      record(token, ExternalAccessTokenRevocationReason.ACCOUNT_LIFECYCLE, actorUserId);
      ended++;
    }
    return ended;
  }

  /**
   * The entry, with the person as a pseudonym and the token by its id - never by its name, which is
   * free text and stays in the token table.
   */
  private void record(
      ExternalAccessToken token, ExternalAccessTokenRevocationReason reason, UUID actorUserId) {
    User owner = users.findById(token.getUserId()).orElse(null);
    if (owner == null) {
      return;
    }
    AuditEvent.Builder builder =
        AuditEvent.builder()
            .organizationId(owner.getOrganizationId())
            .type(AuditEventType.API_TOKEN_EXPIRED)
            .object(AuditObjectType.API_TOKEN, token.getId(), null)
            .subject(AuditSubjectKind.USER, owner.getId())
            .after(Map.of("reason", reason.name()))
            .outcome(AuditOutcome.SUCCESS);
    if (actorUserId == null) {
      audit.recordSystemProcessAction(builder.actorRef(SYSTEM_ACTOR).build());
    } else {
      audit.recordUserActionOnSubject(builder.actor(actorUserId).build());
    }
  }
}
