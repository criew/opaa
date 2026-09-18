package io.opaa.externalaccess.token;

import io.opaa.api.types.AuditEventType;
import io.opaa.api.types.AuditObjectType;
import io.opaa.api.types.AuditOutcome;
import io.opaa.api.types.ExternalAccessTokenStatus;
import io.opaa.audit.AuditEvent;
import io.opaa.audit.AuditEventRecorder;
import io.opaa.auth.User;
import io.opaa.auth.UserRepository;
import io.opaa.common.NotFoundException;
import io.opaa.externalaccess.token.ExternalAccessTokenService.SelectedLibrary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Bestandsliste of every access token and the two blocking levers of the Systemverwaltung
 * (ADR-0035, Entscheidung 2; docs/features/external-access.md, "Zugangstokens").
 *
 * <p>What this class deliberately cannot do is as much its contract as what it can: it never
 * returns a token value, never a usage count and never a usage date, and it offers no filter, no
 * sorting and no output by person. The promise in {@code security-and-compliance.md}
 * ("Mitbestimmungsfaehigkeit" no. 2) is that no surface filters, groups or sorts usage data by
 * person - a list filterable by person that carried a usage date would be exactly that. For the
 * decision this list serves, the value is not needed: blocking follows an incident or an account
 * lifecycle, never disuse. Filtering is therefore over state and expiry only, and the order is
 * fixed at "soonest expiry first".
 *
 * <p>The Sperre je Person is a write taking an owner id, not a filter over the list.
 */
@Service
public class ExternalAccessTokenAdminService {

  private final ExternalAccessTokenRepository tokens;
  private final UserRepository users;
  private final ExternalAccessTokenService selfService;
  private final AuditEventRecorder audit;
  private final Clock clock;

  public ExternalAccessTokenAdminService(
      ExternalAccessTokenRepository tokens,
      UserRepository users,
      ExternalAccessTokenService selfService,
      AuditEventRecorder audit,
      Clock clock) {
    this.tokens = tokens;
    this.users = users;
    this.selfService = selfService;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Every token of the installation, soonest expiry first, optionally narrowed by state and by "is
   * still valid and runs out within N days".
   */
  @Transactional(readOnly = true)
  public List<ExternalAccessTokenAdminView> list(
      ExternalAccessTokenStatus status, Integer expiringWithinDays) {
    Instant now = clock.instant();
    Instant horizon =
        expiringWithinDays == null ? null : now.plus(Duration.ofDays(expiringWithinDays));
    List<ExternalAccessToken> rows =
        tokens.findAllByOrderByExpiresAtAsc().stream()
            .filter(token -> status == null || token.status(now) == status)
            .filter(
                token ->
                    horizon == null
                        || (token.isActive(now) && !token.getExpiresAt().isAfter(horizon)))
            .toList();
    Map<UUID, String> libraryNames = selfService.namesOf(selfService.allSelected(rows));
    Map<UUID, User> owners =
        users
            .findAllById(rows.stream().map(ExternalAccessToken::getUserId).distinct().toList())
            .stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));
    return rows.stream()
        .map(
            token ->
                new ExternalAccessTokenAdminView(
                    token,
                    ownerLabel(owners.get(token.getUserId())),
                    new ExternalAccessTokenService.ExternalAccessTokenView(token, libraryNames)
                        .libraries()))
        .toList();
  }

  /** Blocks one token; already dead tokens keep the reason that ended them. */
  @Transactional
  public void block(UUID tokenId, UUID actorUserId, UUID organizationId) {
    ExternalAccessToken token =
        tokens
            .findById(tokenId)
            .orElseThrow(() -> new NotFoundException("Zugangstoken nicht gefunden"));
    if (token.getRevokedAt() != null) {
      return;
    }
    token.revoke(ExternalAccessTokenRevocationReason.ADMIN, clock.instant());
    tokens.save(token);
    recordBlock(token, actorUserId, organizationId);
  }

  /** Blocks every still-active token of one person; returns how many that was. */
  @Transactional
  public int blockAllOf(UUID ownerUserId, UUID actorUserId, UUID organizationId) {
    User owner =
        users
            .findById(ownerUserId)
            .orElseThrow(() -> new NotFoundException("Konto nicht gefunden"));
    Instant now = clock.instant();
    List<ExternalAccessToken> active = tokens.findByUserIdAndRevokedAtIsNull(owner.getId());
    for (ExternalAccessToken token : active) {
      token.revoke(ExternalAccessTokenRevocationReason.ADMIN, now);
      tokens.save(token);
      recordBlock(token, actorUserId, organizationId);
    }
    return active.size();
  }

  private void recordBlock(ExternalAccessToken token, UUID actorUserId, UUID organizationId) {
    audit.recordUserAction(
        AuditEvent.builder()
            .organizationId(organizationId)
            .actor(actorUserId)
            .type(AuditEventType.API_TOKEN_REVOKED)
            .object(AuditObjectType.API_TOKEN, token.getId(), null)
            .after(
                Map.of("reason", ExternalAccessTokenRevocationReason.ADMIN.name(), "by", "ADMIN"))
            .outcome(AuditOutcome.SUCCESS)
            .build());
  }

  private static String ownerLabel(User owner) {
    if (owner == null) {
      return "";
    }
    return owner.getDisplayName() == null ? "" : owner.getDisplayName();
  }

  /** One row of the administration's list - without a usage date, by design. */
  public record ExternalAccessTokenAdminView(
      ExternalAccessToken token, String ownerDisplayName, List<SelectedLibrary> libraries) {}
}
