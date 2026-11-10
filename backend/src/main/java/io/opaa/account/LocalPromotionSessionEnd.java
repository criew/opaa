package io.opaa.account;

import io.opaa.auth.LocalCredentials;
import io.opaa.auth.LocalCredentialsRepository;
import io.opaa.auth.PromotionSessionEnd;
import io.opaa.auth.User;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * A local account that becomes a system administrator loses every running session: the session
 * generation goes up and every refresh token is revoked as {@link RevocationReason#ADMIN}, so no
 * token issued under the lifetimes of a regular account stays presentable. The credentials row is
 * locked before any token row, the order every act that ends sessions keeps.
 */
@Component
public class LocalPromotionSessionEnd implements PromotionSessionEnd {

  private final LocalCredentialsRepository credentials;
  private final LocalRefreshTokenRepository refreshTokens;
  private final ApplicationEventPublisher events;
  private final Clock clock;

  public LocalPromotionSessionEnd(
      LocalCredentialsRepository credentials,
      LocalRefreshTokenRepository refreshTokens,
      ApplicationEventPublisher events,
      Clock clock) {
    this.credentials = credentials;
    this.refreshTokens = refreshTokens;
    this.events = events;
    this.clock = clock;
  }

  @Override
  public void endSessionsOfPromoted(User user, UUID actorUserId) {
    Optional<LocalCredentials> found = credentials.findForUpdate(user.getId());
    if (found.isEmpty()) {
      return;
    }
    Instant now = clock.instant();
    LocalCredentials row = found.get();
    row.endAllSessions(now);
    credentials.save(row);
    refreshTokens.revokeAllForUser(user.getId(), RevocationReason.ADMIN, now);
    credentials.flush();
    events.publishEvent(
        LocalAccountSessionsEndedEvent.by(user, RevocationReason.ADMIN, actorUserId));
  }
}
