package io.opaa.externalaccess.token;

import io.opaa.account.AccountErasureEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Deletes the access tokens of an erased account with their library lists, in the erasure's
 * transaction. They lapsed already when the account was deactivated; the rows go with the person. A
 * service account she was responsible for keeps its tokens and loses only the reference to her.
 */
@Component
class ExternalAccessTokenAccountErasureListener {

  private final JdbcTemplate jdbc;

  ExternalAccessTokenAccountErasureListener(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Order(AccountErasureEvent.PERSONAL_DATA_ORDER)
  @EventListener
  void onAccountErasure(AccountErasureEvent event) {
    jdbc.update("DELETE FROM external_access_tokens WHERE user_id = ?", event.user().getId());
    jdbc.update(
        "UPDATE service_accounts SET responsible_user_id = NULL WHERE responsible_user_id = ?",
        event.user().getId());
  }
}
