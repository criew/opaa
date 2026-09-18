package io.opaa.externalaccess.token;

import io.opaa.auth.local.LocalAccountAccessEndedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Ends the access tokens of a person whose access itself ended - a locked account, a redeemed
 * handover (ADR-0035; docs/features/external-access.md, "Lebenszyklus": "Ein Token folgt dem
 * Lebenszyklus seiner Person").
 *
 * <p>The tokens of such an account are already refused per call by {@code
 * ExternalAccessTokenAuthenticator}; this listener is about the other half of the promise. Without
 * it the token stays {@code ACTIVE} in the administration's Bestandsliste and its end produces no
 * entry at all - while {@code #1718} names {@code ACCOUNT_LIFECYCLE} as one of the two Anlässe of
 * the Ausserkrafttreten.
 *
 * <p>A plain {@code @EventListener}, so it runs inside the transaction of the act that ended the
 * access: the lock and the end of the tokens commit together or not at all.
 */
@Component
public class ExternalAccessTokenAccountLifecycleListener {

  private static final Logger log =
      LoggerFactory.getLogger(ExternalAccessTokenAccountLifecycleListener.class);

  private final ExternalAccessTokenLapseService lapses;

  public ExternalAccessTokenAccountLifecycleListener(ExternalAccessTokenLapseService lapses) {
    this.lapses = lapses;
  }

  @EventListener
  public void onAccessEnded(LocalAccountAccessEndedEvent event) {
    int ended = lapses.endTokensOfAccount(event.user(), event.actorUserId());
    if (ended > 0) {
      log.info(
          "External access tokens: {} token(s) of account {} ended with its access",
          ended,
          event.user().getId());
    }
  }
}
