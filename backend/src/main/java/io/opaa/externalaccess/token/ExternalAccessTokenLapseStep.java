package io.opaa.externalaccess.token;

import io.opaa.auth.local.LocalAccountMaintenanceStep;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The daily pass that lets {@link ExternalAccessTokenLapseService} record every token which has
 * stopped working (ADR-0035; docs/features/external-access.md, "Lebenszyklus").
 *
 * <p>Why this exists at all: with a mandatory expiry of at most 90 days, <b>the ordinary end of a
 * token is the expiry, not the revocation</b>. An access that ends without an entry is the same gap
 * in the trail as one that begins without one.
 *
 * <p>Each token is handled in its own transaction, so a failing one leaves its row without the
 * marker and is picked up again by the next run instead of losing its entry for good.
 */
@Component
@Order(40)
public class ExternalAccessTokenLapseStep implements LocalAccountMaintenanceStep {

  private static final Logger log = LoggerFactory.getLogger(ExternalAccessTokenLapseStep.class);

  private final ExternalAccessTokenRepository tokens;
  private final ExternalAccessTokenLapseService lapses;

  public ExternalAccessTokenLapseStep(
      ExternalAccessTokenRepository tokens, ExternalAccessTokenLapseService lapses) {
    this.tokens = tokens;
    this.lapses = lapses;
  }

  @Override
  public String name() {
    return "external-access-token-lapse";
  }

  @Override
  public void run(Instant now) {
    List<UUID> lapsed = tokens.findLapsed(now).stream().map(ExternalAccessToken::getId).toList();
    int recorded = 0;
    int failed = 0;
    for (UUID tokenId : lapsed) {
      try {
        if (lapses.recordLapse(tokenId, now)) {
          recorded++;
        }
      } catch (RuntimeException e) {
        // The row keeps no marker, so the next run tries again - that is the whole point of the
        // per-token transaction.
        failed++;
        log.error("Recording the lapse of access token {} failed; it stays pending", tokenId, e);
      }
    }
    if (!lapsed.isEmpty()) {
      log.info(
          "External access tokens: {} token(s) lapsed, {} recorded as expired, {} left pending",
          lapsed.size(),
          recorded,
          failed);
    }
  }
}
